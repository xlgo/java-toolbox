package com.aqishi.toolbox.feature.network.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 压测要反复发送的那一个请求：方法、URL、请求头、请求体。
 *
 * <p>请求头保持用户输入的顺序并允许重名（{@code Cookie}、{@code Accept} 等可以出现多次）。
 * {@code java.net.http.HttpClient} 禁止调用方设置的受限头（{@code Host}、{@code Connection}、
 * {@code Content-Length} 等）会被剔出 {@link #headers()}，放进 {@link #droppedHeaders()}
 * 供界面提示，而不是等发送时才抛异常——从浏览器"复制为 cURL"的命令里经常带着它们。</p>
 */
public final class BenchRequest {

    /** HttpClient 自己管理、不允许调用方设置的请求头（小写）。 */
    public static final Set<String> RESTRICTED_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "upgrade",
            "keep-alive", "transfer-encoding", "te", "trailer", "proxy-connection");

    private final String method;
    private final URI uri;
    private final List<Map.Entry<String, String>> headers;
    private final List<String> droppedHeaders;
    private final String body;

    private BenchRequest(String method, URI uri, List<Map.Entry<String, String>> headers,
                         List<String> droppedHeaders, String body) {
        this.method = method;
        this.uri = uri;
        this.headers = Collections.unmodifiableList(headers);
        this.droppedHeaders = Collections.unmodifiableList(droppedHeaders);
        this.body = body;
    }

    /**
     * @param method      HTTP 方法，空则为 GET
     * @param url         http 或 https 绝对地址
     * @param headers     {@code Name: value} 形式的请求头
     * @param body        请求体，可为空
     * @param contentType 请求体类型；非空且请求头里没有 Content-Type 时补上
     * @throws IllegalArgumentException URL 不合法、请求头格式错误
     */
    public static BenchRequest of(String method, String url, List<String> headers,
                                  String body, String contentType) {
        String verb = method == null || method.trim().isEmpty()
                ? "GET" : method.trim().toUpperCase(Locale.ROOT);
        if (!verb.matches("[A-Z][A-Z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid HTTP method: " + method);
        }
        URI uri = parseUri(url);
        List<Map.Entry<String, String>> kept = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        boolean hasContentType = false;
        for (String line : headers == null ? List.<String>of() : headers) {
            Map.Entry<String, String> header = parseHeaderLine(line);
            if (header == null) {
                continue;
            }
            String lower = header.getKey().toLowerCase(Locale.ROOT);
            if (RESTRICTED_HEADERS.contains(lower)) {
                dropped.add(header.getKey());
                continue;
            }
            hasContentType |= "content-type".equals(lower);
            kept.add(header);
        }
        String payload = body == null ? "" : body;
        if (!hasContentType && contentType != null && !contentType.trim().isEmpty() && !payload.isEmpty()) {
            kept.add(new AbstractMap.SimpleImmutableEntry<>("Content-Type", contentType.trim()));
        }
        return new BenchRequest(verb, uri, kept, dropped, payload);
    }

    /** 从 cURL 命令导入：方法、URL、请求头、请求体一一对应。 */
    public static BenchRequest fromCurl(CurlCommand command) {
        Objects.requireNonNull(command, "command");
        return of(command.method(), command.url(), command.headers(), command.body(), null);
    }

    /** 把多行文本按 {@code Name: value} 拆成请求头列表；空行与 {@code #} 注释行跳过。 */
    public static List<String> splitHeaderLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * 解析一行 {@code Name: value}。空行返回 null。
     *
     * @throws IllegalArgumentException 缺少冒号或名称含非法字符
     */
    static Map.Entry<String, String> parseHeaderLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String text = line.trim();
        int colon = text.indexOf(':');
        if (colon <= 0) {
            throw new IllegalArgumentException("Invalid header line (expected Name: value): " + text);
        }
        String name = text.substring(0, colon).trim();
        String value = text.substring(colon + 1).trim();
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) {
            throw new IllegalArgumentException("Invalid header name: " + name);
        }
        return new AbstractMap.SimpleImmutableEntry<>(name, value);
    }

    private static URI parseUri(String url) {
        String text = url == null ? "" : url.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("URL is required");
        }
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Invalid URL: " + error.getMessage(), error);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("URL has no host");
        }
        return uri;
    }

    public String method() {
        return method;
    }

    public URI uri() {
        return uri;
    }

    /** URI 中的主机名；IPv6 字面量不带方括号。 */
    public String host() {
        String host = uri.getHost();
        if (host.startsWith("[") && host.endsWith("]")) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    public List<Map.Entry<String, String>> headers() {
        return headers;
    }

    /** 被剔除的受限请求头名称，按出现顺序。 */
    public List<String> droppedHeaders() {
        return droppedHeaders;
    }

    public String body() {
        return body;
    }

    public boolean hasBody() {
        return !body.isEmpty();
    }
}
