package com.aqishi.toolbox.feature.generation.application;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Replicate 上 QR ControlNet 模型的最小客户端：提交任务、查询状态、下载结果图。
 *
 * <p>所有请求都有连接与读取超时。非 2xx 响应一律抛出 {@link ReplicateException}，
 * 并标明是否值得重试（5xx、429 与网络错误可以重试；401、404、422 之类重试也不会好）——
 * 调用方据此决定继续轮询还是立即终止，而不是像早先那样吞掉所有异常、无限轮询下去。</p>
 *
 * <p>方法都是阻塞的，调用方负责放到后台线程。</p>
 */
public final class ReplicateQrClient {

    /** z-uo/qrcode-controlnet 模型版本。 */
    public static final String MODEL_VERSION =
            "628e604e13fc636433fbe4d9c0e5a95efd58117a421b4700d11f9746e16694e8";

    private static final URI PREDICTIONS = URI.create("https://api.replicate.com/v1/predictions");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** 任务状态。{@code imageUrl} 仅在成功时非空，{@code error} 仅在失败时可能非空。 */
    public record Status(State state, String imageUrl, String error) {
        public boolean isTerminal() {
            return state != State.RUNNING;
        }
    }

    public enum State {
        RUNNING, SUCCEEDED, FAILED
    }

    /** 请求失败。{@link #isRetryable()} 为 true 表示稍后重试可能成功。 */
    public static final class ReplicateException extends IOException {
        private final boolean retryable;

        ReplicateException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        ReplicateException(String message, Throwable cause) {
            super(message, cause);
            this.retryable = true;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    private final HttpClient http;
    private final String token;
    private final ObjectMapper mapper = Json.mapper();

    public ReplicateQrClient(String token) {
        this.token = token;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 提交生成任务，返回用于轮询的状态查询地址。 */
    public URI submit(String content, String prompt, String negativePrompt) throws IOException, InterruptedException {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("qr_code_content", content);
        input.put("prompt", prompt);
        input.put("negative_prompt", negativePrompt);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", MODEL_VERSION);
        body.put("input", input);

        HttpRequest request = authorized(PREDICTIONS)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return parseSubmit(send(request));
    }

    /** 查询一次任务状态。 */
    public Status poll(URI statusUrl) throws IOException, InterruptedException {
        return parseStatus(send(authorized(statusUrl).GET().build()));
    }

    /** 下载结果图片的原始字节。 */
    public byte[] download(String imageUrl) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(imageUrl)).timeout(REQUEST_TIMEOUT).GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        requireSuccess(response.statusCode(), "");
        return response.body();
    }

    static URI parseSubmit(String body) throws IOException {
        JsonNode root = Json.mapper().readTree(body);
        String getUrl = root.path("urls").path("get").asText("");
        if (getUrl.isEmpty()) {
            throw new ReplicateException("Response has no urls.get: " + abbreviate(body), false);
        }
        return URI.create(getUrl);
    }

    static Status parseStatus(String body) throws IOException {
        JsonNode root = Json.mapper().readTree(body);
        String status = root.path("status").asText("");
        switch (status) {
            case "succeeded": {
                // 该模型的 output 是图片地址数组；个别版本直接返回字符串。
                JsonNode output = root.path("output");
                String url = output.isArray() && output.size() > 0 ? output.get(0).asText("")
                        : output.isTextual() ? output.asText() : "";
                if (url.isEmpty()) {
                    return new Status(State.FAILED, null, "succeeded without output: " + abbreviate(body));
                }
                return new Status(State.SUCCEEDED, url, null);
            }
            case "failed":
            case "canceled":
                return new Status(State.FAILED, null, root.path("error").asText(status));
            default:
                // starting / processing，或未来新增的中间状态
                return new Status(State.RUNNING, null, null);
        }
    }

    private HttpRequest.Builder authorized(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token);
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException network) {
            throw new ReplicateException("Network error: " + network.getMessage(), network);
        }
        requireSuccess(response.statusCode(), response.body());
        return response.body();
    }

    private static void requireSuccess(int status, String body) throws ReplicateException {
        if (status >= 200 && status < 300) {
            return;
        }
        boolean retryable = status >= 500 || status == 429;
        throw new ReplicateException("HTTP " + status + (body == null || body.isEmpty() ? "" : ": " + abbreviate(body)),
                retryable);
    }

    private static String abbreviate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }
}
