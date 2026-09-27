package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.BenchPlan;
import com.aqishi.toolbox.feature.network.domain.BenchRequest;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 按压测参数构造 {@link HttpClient} 与要复用的 {@link HttpRequest}。
 *
 * <p>连接复用沿用 {@code HttpClient} 默认的 keep-alive 连接池：HTTP/1.1 下并发 N 最多占用
 * N 条连接，HTTP/2 在一条连接上多路复用。不提供"每个请求新建连接"：{@code HttpClient}
 * 没有逐请求关闭连接的开关，而每请求新建客户端在 JDK 17 上会各留下一个选择器线程，代价远高于被测对象。</p>
 */
final class BenchClientFactory {

    private BenchClientFactory() {
    }

    static HttpClient client(BenchPlan plan, Executor executor) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .executor(executor)
                .version(plan.httpVersion())
                .connectTimeout(plan.connectTimeout())
                .followRedirects(plan.followRedirects() ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
        if (plan.insecureTls()) {
            builder.sslContext(trustAllContext());
        }
        return builder.build();
    }

    /**
     * 请求对象不可变，可以被所有工作线程反复发送；请求体发布者基于字节数组，也可以重复订阅。
     */
    static HttpRequest request(BenchPlan plan) {
        BenchRequest source = plan.request();
        HttpRequest.BodyPublisher publisher = source.hasBody()
                ? HttpRequest.BodyPublishers.ofByteArray(source.body().getBytes(StandardCharsets.UTF_8))
                : HttpRequest.BodyPublishers.noBody();
        HttpRequest.Builder builder = HttpRequest.newBuilder(source.uri())
                .timeout(plan.requestTimeout())
                .method(source.method(), publisher);
        for (Map.Entry<String, String> header : source.headers()) {
            builder.header(header.getKey(), header.getValue());
        }
        return builder.build();
    }

    /**
     * 信任任意证书且不校验主机名，只在用户明确勾选"忽略 TLS 校验"时使用。
     *
     * <p>必须实现 {@link X509ExtendedTrustManager}：只实现 {@code X509TrustManager} 时，
     * JSSE 会把它包一层并照样做主机名校验，自签证书配 IP 访问仍会失败。</p>
     */
    private static SSLContext trustAllContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new TrustAll()}, new SecureRandom());
            return context;
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Cannot create TLS context: " + error.getMessage(), error);
        }
    }

    private static final class TrustAll extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
