package com.aqishi.toolbox.feature.network.application;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.regex.Pattern;

/**
 * TCP 客户端的 TLS 包装。默认使用 JDK 信任库并校验主机名；"信任所有证书"只在用户明确勾选时启用，
 * 用于调试自签名的设备或内网服务。
 */
final class SocketTls {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    private SocketTls() {
    }

    /**
     * 在已连接的明文套接字上完成 TLS 握手。握手期间沿用连接超时，防止对端不说 TLS 时永远卡住。
     */
    static SSLSocket handshake(Socket plain, String host, int port, boolean trustAll, int timeoutMillis)
            throws java.io.IOException, GeneralSecurityException {
        SSLSocketFactory factory = trustAll ? trustAllContext().getSocketFactory()
                : SSLContext.getDefault().getSocketFactory();
        SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
        SSLParameters parameters = ssl.getSSLParameters();
        if (!isIpLiteral(host)) {
            try {
                parameters.setServerNames(Collections.singletonList(new SNIHostName(host)));
            } catch (IllegalArgumentException invalidName) {
                // 不合法的 SNI 名称（如带下划线）直接不发，交给服务端默认证书
            }
        }
        if (!trustAll) {
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
        }
        ssl.setSSLParameters(parameters);
        ssl.setSoTimeout(Math.max(0, timeoutMillis));
        ssl.startHandshake();
        ssl.setSoTimeout(0);
        return ssl;
    }

    static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || IPV4.matcher(host).matches();
    }

    private static SSLContext trustAllContext() throws GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
        return context;
    }

    /**
     * 继承 X509ExtendedTrustManager 而非 X509TrustManager：后者会被 JSSE 包一层再做主机名等附加校验，
     * "信任所有"就名不副实了。
     */
    private static final class TrustAllManager extends X509ExtendedTrustManager {
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
