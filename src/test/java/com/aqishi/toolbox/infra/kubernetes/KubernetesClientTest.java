package com.aqishi.toolbox.infra.kubernetes;

import org.junit.jupiter.api.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesClientTest {

    /**
     * 回归：自定义 TLS 必须在取输出流（即建连握手）之前装上。
     * 顺序反了之后 GET 正常、带请求体的 PUT/POST 在自签 CA 集群上握手失败，很难联想到原因。
     */
    @Test
    void installsTlsSettingsBeforeWritingTheBody() throws Exception {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        HostnameVerifier verifier = (host, session) -> true;
        KubernetesClient client = new KubernetesClient("https://cluster.test", "token", factory, verifier);
        RecordingConnection connection = new RecordingConnection(new URL("https://cluster.test/api"));

        client.configure(connection, "PUT", "{\"spec\":{}}");

        int tls = connection.events.indexOf("setSSLSocketFactory");
        int verifierSet = connection.events.indexOf("setHostnameVerifier");
        int body = connection.events.indexOf("getOutputStream");
        assertTrue(tls >= 0 && verifierSet >= 0 && body >= 0, connection.events.toString());
        assertTrue(tls < body, "TLS factory must be set before connecting: " + connection.events);
        assertTrue(verifierSet < body, "hostname verifier must be set before connecting: " + connection.events);
        assertEquals("{\"spec\":{}}", connection.written.toString(StandardCharsets.UTF_8));
    }

    @Test
    void doesNotOpenOutputStreamWithoutBody() throws Exception {
        KubernetesClient client = new KubernetesClient("https://cluster.test", "", null, null);
        RecordingConnection connection = new RecordingConnection(new URL("https://cluster.test/api"));

        client.configure(connection, "GET", null);

        assertTrue(!connection.events.contains("getOutputStream"), connection.events.toString());
    }

    /** 只记录调用顺序；getOutputStream 之后再改 TLS 相当于对已握手的连接无效。 */
    private static final class RecordingConnection extends HttpsURLConnection {
        final List<String> events = new ArrayList<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();

        RecordingConnection(URL url) {
            super(url);
        }

        @Override
        public void setSSLSocketFactory(SSLSocketFactory factory) {
            events.add("setSSLSocketFactory");
        }

        @Override
        public void setHostnameVerifier(HostnameVerifier verifier) {
            events.add("setHostnameVerifier");
        }

        @Override
        public OutputStream getOutputStream() {
            events.add("getOutputStream");
            return written;
        }

        @Override
        public String getCipherSuite() {
            return null;
        }

        @Override
        public Certificate[] getLocalCertificates() {
            return null;
        }

        @Override
        public Certificate[] getServerCertificates() {
            return null;
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }
    }
}
