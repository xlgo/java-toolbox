package com.aqishi.toolbox.feature.network.domain;

import com.aqishi.toolbox.util.ShellQuote;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 一条 cURL 命令的请求部分：方法、URL、请求头、请求体。
 *
 * <p>{@link #toShell()} 与 {@link #parse(String)} 互为逆运算：导出的命令再导入能还原出同一个请求。
 * 解析按 POSIX shell 的规则切词（单引号、双引号、反斜杠续行），而不是用正则去猜引号的边界——
 * 浏览器"复制为 cURL"生成的命令里，请求头和 JSON 请求体里的引号、{@code '\''} 转义都很常见。</p>
 *
 * <p>只识别与请求内容有关的选项；{@code -L}、{@code -k}、{@code --compressed} 之类的开关会被忽略。</p>
 */
public final class CurlCommand {

    /** 这些选项后面跟一个值，但与请求内容无关，解析时连同值一起跳过。 */
    private static final Set<String> IGNORED_WITH_VALUE = Set.of(
            "-o", "--output", "-m", "--max-time", "--connect-timeout", "-x", "--proxy",
            "--cacert", "--cert", "--key", "-w", "--write-out", "--retry");

    private final String method;
    private final String url;
    private final List<String> headers;
    private final String body;

    public CurlCommand(String method, String url, List<String> headers, String body) {
        this.method = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.ROOT);
        this.url = Objects.requireNonNull(url, "url");
        this.headers = Collections.unmodifiableList(new ArrayList<>(headers));
        this.body = body == null ? "" : body;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    /** 形如 {@code Name: value} 的请求头，保持原有顺序。 */
    public List<String> headers() {
        return headers;
    }

    public String body() {
        return body;
    }

    /** 生成可以直接粘贴进 bash / zsh 的多行命令，所有参数都用单引号包裹。 */
    public String toShell() {
        StringBuilder command = new StringBuilder("curl -X ").append(method).append(' ')
                .append(ShellQuote.single(url));
        for (String header : headers) {
            command.append(" \\\n  -H ").append(ShellQuote.single(header));
        }
        if (!body.isEmpty()) {
            command.append(" \\\n  --data-raw ").append(ShellQuote.single(body));
        }
        return command.toString();
    }

    /**
     * 解析 cURL 命令。
     *
     * @throws IllegalArgumentException 不是 curl 命令、引号未闭合或找不到 URL
     */
    public static CurlCommand parse(String command) {
        List<String> words = ShellQuote.split(command == null ? "" : command);
        if (words.isEmpty() || !"curl".equals(words.get(0))) {
            throw new IllegalArgumentException("Not a curl command");
        }
        String method = null;
        String url = null;
        List<String> headers = new ArrayList<>();
        StringBuilder body = null;

        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            String value = i + 1 < words.size() ? words.get(i + 1) : null;
            switch (word) {
                case "-X":
                case "--request":
                    method = require(word, value);
                    i++;
                    break;
                case "-H":
                case "--header":
                    headers.add(require(word, value));
                    i++;
                    break;
                case "-d":
                case "--data":
                case "--data-raw":
                case "--data-binary":
                case "--data-ascii":
                case "--data-urlencode":
                    // 多个 -d 按 curl 的规则用 & 连接
                    body = body == null ? new StringBuilder() : body.append('&');
                    body.append(require(word, value));
                    i++;
                    break;
                case "-A":
                case "--user-agent":
                    headers.add("User-Agent: " + require(word, value));
                    i++;
                    break;
                case "-e":
                case "--referer":
                    headers.add("Referer: " + require(word, value));
                    i++;
                    break;
                case "-b":
                case "--cookie":
                    headers.add("Cookie: " + require(word, value));
                    i++;
                    break;
                case "-u":
                case "--user":
                    headers.add("Authorization: Basic " + Base64.getEncoder()
                            .encodeToString(require(word, value).getBytes(StandardCharsets.UTF_8)));
                    i++;
                    break;
                case "-I":
                case "--head":
                    method = "HEAD";
                    break;
                case "--url":
                    url = require(word, value);
                    i++;
                    break;
                default:
                    if (IGNORED_WITH_VALUE.contains(word)) {
                        i++;
                    } else if (!word.startsWith("-") && url == null) {
                        url = word;
                    }
                    break;
            }
        }
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("No URL found in curl command");
        }
        if (method == null) {
            method = body != null ? "POST" : "GET";
        }
        return new CurlCommand(method, url, headers, body == null ? "" : body.toString());
    }

    private static String require(String option, String value) {
        if (value == null) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return value;
    }
}
