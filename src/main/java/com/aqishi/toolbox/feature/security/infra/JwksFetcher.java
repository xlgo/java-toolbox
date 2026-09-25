package com.aqishi.toolbox.feature.security.infra;

import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;

/**
 * 拉取远端 JWKS，必要时先走 OIDC Discovery。
 *
 * <p>用户贴进来的地址三种都常见：JWKS 本身、{@code .well-known/openid-configuration}、
 * 或者只是 issuer（{@code https://login.example.com/realms/demo}）。路径里不带 {@code jwks}
 * 的一律先按 issuer 试 Discovery，失败再把原地址当 JWKS 或 Discovery 文档直接取。</p>
 *
 * <p>响应体上限 1 MB：JWKS 通常只有几 KB，填错地址拉到一个巨大的 HTML 或文件时，
 * 不能让它把内存吃光。http 地址允许（本机调试 Keycloak 很常见），但结果里会带上警告。</p>
 */
public final class JwksFetcher implements AutoCloseable {

    public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final String DISCOVERY_PATH = "/.well-known/openid-configuration";

    /** 结果上的非致命提示。 */
    public enum Warning {
        /** 使用了明文 http（JWKS 被篡改就能伪造任意令牌）。 */
        INSECURE_HTTP,
        /** Discovery 文档里的 issuer 与请求的 issuer 不一致（OIDC Discovery §4.3）。 */
        ISSUER_MISMATCH,
        /** Content-Type 不是 JSON，但内容能按 JSON 解析。 */
        UNEXPECTED_CONTENT_TYPE
    }

    /**
     * 拉取结果。
     *
     * @param requestedUrl 用户输入的地址
     * @param jwksUrl      最终取到 JWKS 的地址
     * @param discoveryUrl 用到的 Discovery 文档地址，未经 Discovery 时为 null
     * @param issuer       Discovery 文档声明的 issuer，可能为 null
     * @param body         JWKS 原文
     * @param warnings     提示
     * @param elapsedMs    总耗时
     */
    public record Result(String requestedUrl, String jwksUrl, String discoveryUrl, String issuer,
                         String body, List<Warning> warnings, long elapsedMs) {
        public Result {
            warnings = List.copyOf(warnings);
        }
    }

    /** 可预期的拉取失败；{@link #getCode()} 供界面本地化，参数只含状态码、地址等公开信息。 */
    public static final class FetchException extends Exception {
        private final String code;
        private final List<Object> params;

        public FetchException(String code, Object... params) {
            super(code + (params.length == 0 ? "" : " " + List.of(params)));
            this.code = code;
            this.params = List.of(params);
        }

        public String getCode() {
            return code;
        }

        public List<Object> getParams() {
            return params;
        }
    }

    private final HttpClient client;
    private final int maxBodyBytes;
    private final Object executorLock = new Object();
    private ExecutorService executor;

    public JwksFetcher() {
        this(HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build(), DEFAULT_MAX_BODY_BYTES);
    }

    public JwksFetcher(HttpClient client, int maxBodyBytes) {
        this.client = Objects.requireNonNull(client, "client");
        if (maxBodyBytes < 1) {
            throw new IllegalArgumentException("maxBodyBytes must be >= 1");
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    /**
     * 在后台线程拉取。回调在后台线程上执行，调用方自行切回 EDT。
     *
     * <p>返回的 Future 被 {@code cancel(true)} 时会中断正在进行的 HTTP 请求，且不再回调。</p>
     */
    public Future<Result> fetchAsync(String url, Consumer<Result> onSuccess, Consumer<Throwable> onFailure) {
        FutureTask<Result> task = new FutureTask<>(() -> fetch(url)) {
            @Override
            protected void done() {
                if (isCancelled()) {
                    return;
                }
                try {
                    onSuccess.accept(get());
                } catch (java.util.concurrent.ExecutionException error) {
                    onFailure.accept(error.getCause() == null ? error : error.getCause());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException error) {
                    onFailure.accept(error);
                }
            }
        };
        executor().execute(task);
        return task;
    }

    /** 同步拉取。 */
    public Result fetch(String url) throws FetchException, InterruptedException {
        long started = System.nanoTime();
        URI requested = parseUrl(url);
        List<Warning> warnings = new ArrayList<>();
        if ("http".equals(requested.getScheme())) {
            warnings.add(Warning.INSECURE_HTTP);
        }

        String path = requested.getPath() == null ? "" : requested.getPath().toLowerCase(Locale.ROOT);
        boolean looksLikeIssuer = !path.contains("jwks") && !path.contains("/.well-known/");
        if (looksLikeIssuer) {
            URI discovery = discoveryUri(requested);
            // 试探用的提示单独收集：试探失败时不能把它的 Content-Type 提示混进最终结果。
            List<Warning> probeWarnings = new ArrayList<>(warnings);
            try {
                Fetched document = get(discovery, probeWarnings);
                if (document.json.path("jwks_uri").isTextual()) {
                    return followDiscovery(requested, discovery, document, probeWarnings, started);
                }
            } catch (FetchException ignored) {
                // 不是 issuer：退回把原地址当 JWKS / Discovery 直接取。
            }
        }

        Fetched fetched = get(requested, warnings);
        if (fetched.json.has("keys")) {
            return new Result(url.trim(), requested.toString(), null, null, fetched.body,
                    distinct(warnings), elapsed(started));
        }
        if (fetched.json.path("jwks_uri").isTextual()) {
            return followDiscovery(null, requested, fetched, warnings, started);
        }
        throw new FetchException("notJwks", requested.toString());
    }

    private Result followDiscovery(URI issuerUri, URI discovery, Fetched document, List<Warning> warnings,
                                   long started) throws FetchException, InterruptedException {
        String issuer = document.json.path("issuer").isTextual() ? document.json.get("issuer").asText() : null;
        if (issuerUri != null && issuer != null && !stripSlash(issuer).equals(stripSlash(issuerUri.toString()))) {
            warnings.add(Warning.ISSUER_MISMATCH);
        }
        URI jwksUri = parseUrl(document.json.get("jwks_uri").asText());
        if ("http".equals(jwksUri.getScheme())) {
            warnings.add(Warning.INSECURE_HTTP);
        }
        Fetched jwks = get(jwksUri, warnings);
        if (!jwks.json.has("keys")) {
            throw new FetchException("notJwks", jwksUri.toString());
        }
        return new Result(issuerUri == null ? discovery.toString() : issuerUri.toString(), jwksUri.toString(),
                discovery.toString(), issuer, jwks.body, distinct(warnings), elapsed(started));
    }

    private Fetched get(URI uri, List<Warning> warnings) throws FetchException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/jwk-set+json, application/json;q=0.9, */*;q=0.1")
                .GET()
                .build();
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException error) {
            throw new FetchException("connectTimeout", uri.getHost());
        } catch (HttpTimeoutException error) {
            throw new FetchException("timeout", uri.toString());
        } catch (IOException error) {
            String message = error.getMessage();
            throw new FetchException("network", message == null ? error.getClass().getSimpleName() : message);
        }

        try (InputStream stream = response.body()) {
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new FetchException("httpStatus", status, uri.toString());
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > maxBodyBytes) {
                throw new FetchException("bodyTooLarge", maxBodyBytes / 1024);
            }
            byte[] bytes = readCapped(stream);
            String body = new String(bytes, StandardCharsets.UTF_8);
            String contentType = response.headers().firstValue("Content-Type").orElse("")
                    .toLowerCase(Locale.ROOT);
            JsonNode json;
            try {
                json = Json.mapper().readTree(body);
            } catch (IOException error) {
                throw new FetchException("notJson", contentType.isEmpty() ? "-" : contentType);
            }
            if (json == null || !json.isObject()) {
                throw new FetchException("notJson", contentType.isEmpty() ? "-" : contentType);
            }
            if (!contentType.isEmpty() && !contentType.contains("json")) {
                warnings.add(Warning.UNEXPECTED_CONTENT_TYPE);
            }
            return new Fetched(body, json);
        } catch (IOException error) {
            String message = error.getMessage();
            throw new FetchException("network", message == null ? error.getClass().getSimpleName() : message);
        }
    }

    /** 读到上限 +1 字节就停：既能判断超限，又不会先把整个响应读进内存。 */
    private byte[] readCapped(InputStream stream) throws IOException, FetchException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = stream.read(buffer)) != -1) {
            total += read;
            if (total > maxBodyBytes) {
                throw new FetchException("bodyTooLarge", maxBodyBytes / 1024);
            }
            out.write(buffer, 0, read);
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("interrupted");
            }
        }
        return out.toByteArray();
    }

    static URI parseUrl(String url) throws FetchException {
        String text = url == null ? "" : url.trim();
        if (text.isEmpty()) {
            throw new FetchException("emptyUrl");
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException error) {
            throw new FetchException("invalidUrl", text);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new FetchException("unsupportedScheme", scheme.isEmpty() ? "-" : scheme);
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new FetchException("invalidUrl", text);
        }
        return uri;
    }

    /** OIDC Discovery §4：issuer 后直接拼 /.well-known/openid-configuration（issuer 可带路径）。 */
    static URI discoveryUri(URI issuer) throws FetchException {
        String base = stripSlash(issuer.toString());
        int query = base.indexOf('?');
        if (query >= 0) {
            base = stripSlash(base.substring(0, query));
        }
        return parseUrl(base + DISCOVERY_PATH);
    }

    private static String stripSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static List<Warning> distinct(List<Warning> warnings) {
        List<Warning> result = new ArrayList<>();
        for (Warning warning : warnings) {
            if (!result.contains(warning)) {
                result.add(warning);
            }
        }
        return result;
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private ExecutorService executor() {
        synchronized (executorLock) {
            if (executor == null || executor.isShutdown()) {
                executor = DaemonThreads.single("jwks-fetch");
            }
            return executor;
        }
    }

    /** 取消进行中的请求并释放线程；可重复调用，之后再次 fetchAsync 会新建线程。 */
    @Override
    public void close() {
        synchronized (executorLock) {
            DaemonThreads.shutdownQuietly(executor);
            executor = null;
        }
    }

    private static final class Fetched {
        final String body;
        final JsonNode json;

        Fetched(String body, JsonNode json) {
            this.body = body;
            this.json = json;
        }
    }
}
