package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.feature.network.domain.SocketOpenException;
import com.aqishi.toolbox.feature.security.domain.CertUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLS 客户端对本地自签名 SSLServerSocket：默认校验必须拒绝，勾选"信任所有"才放行。
 */
class TcpClientTlsTest {

    private static SSLServerSocket server;
    private static Thread acceptor;

    @BeforeAll
    static void startTlsEchoServer() throws Exception {
        CertUtils.CertResult cert = CertUtils.createRootCA(0, "localhost", "Toolbox Test", "QA",
                "Test", "Test", "CN", 1);
        char[] password = "changeit".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", cert.getPrivateKey(), password,
                new Certificate[]{cert.getCertificate()});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(kmf.getKeyManagers(), null, null);
        server = (SSLServerSocket) context.getServerSocketFactory()
                .createServerSocket(0, 8, InetAddress.getLoopbackAddress());

        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    SSLSocket socket = (SSLSocket) server.accept();
                    Thread handler = new Thread(() -> echo(socket), "tls-test-echo");
                    handler.setDaemon(true);
                    handler.start();
                } catch (Exception closed) {
                    return;
                }
            }
        }, "tls-test-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private static void echo(SSLSocket socket) {
        try (SSLSocket s = socket) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (Exception ignored) {
            // 握手失败或对端断开
        }
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
        acceptor.join(2000);
    }

    @Test
    void defaultVerificationRejectsSelfSignedCertificate() {
        TcpClientSession client = new TcpClientSession(new TcpClientSession.Options()
                .host("localhost").port(server.getLocalPort()).tls(true).connectTimeoutMillis(3000),
                new EventRecorder());
        try {
            SocketOpenException error = assertThrows(SocketOpenException.class, client::open);
            assertEquals(SocketError.TLS_FAILED, error.getError());
            assertTrue(client.isClosed());
        } finally {
            client.close();
        }
    }

    @Test
    void trustAllAcceptsSelfSignedCertificateAndExchangesData() throws Exception {
        EventRecorder events = new EventRecorder();
        AtomicReference<TcpClientSession> ref = new AtomicReference<>();
        TcpClientSession client = new TcpClientSession(new TcpClientSession.Options()
                .host("localhost").port(server.getLocalPort()).tls(true).trustAll(true)
                .connectTimeoutMillis(3000), events);
        ref.set(client);
        try {
            client.open();
            events.await(SocketEvent.Type.CONNECTED);
            client.send("over tls".getBytes(StandardCharsets.UTF_8));
            events.awaitReceived(0, "over tls");
        } finally {
            ref.get().close();
        }
        events.await(SocketEvent.Type.DISCONNECTED);
    }
}
