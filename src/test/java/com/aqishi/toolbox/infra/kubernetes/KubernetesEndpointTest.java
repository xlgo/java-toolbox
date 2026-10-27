package com.aqishi.toolbox.infra.kubernetes;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.infra.network.RoutedTlsSocketFactory;
import com.sun.net.httpserver.*;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.server.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.*;

@org.junit.jupiter.api.Timeout(45)
class KubernetesEndpointTest {
    record TlsFixture(SSLContext context, String certificate, String key) {}

    private static String pem(Object value) throws Exception {
        StringWriter text = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(text)) {
            w.writeObject(value);
        }
        return text.toString();
    }

    private static TlsFixture tls() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Name name = new X500Name("CN=cluster.test.invalid");
        var builder =
                new JcaX509v3CertificateBuilder(
                        name,
                        BigInteger.ONE,
                        new Date(now - 60000),
                        new Date(now + 86400000),
                        name,
                        pair.getPublic());
        builder.addExtension(
                Extension.subjectAlternativeName,
                false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, "cluster.test.invalid")));
        X509Certificate cert =
                new JcaX509CertificateConverter()
                        .getCertificate(
                                builder.build(
                                        new JcaContentSignerBuilder("SHA256withRSA")
                                                .build(pair.getPrivate())));
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(null, null);
        keys.setKeyEntry(
                "server",
                pair.getPrivate(),
                new char[0],
                new java.security.cert.Certificate[] {cert});
        KeyManagerFactory km =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys, new char[0]);
        TrustManagerFactory tm =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tm.init(keys);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(km.getKeyManagers(), tm.getTrustManagers(), null);
        return new TlsFixture(context, pem(cert), pem(pair.getPrivate()));
    }

    @Test
    void restThroughSshRouteVerifiesOriginalHostAndClientCertificateAndReleasesLease()
            throws Exception {
        TlsFixture tls = tls();
        AtomicInteger releases = new AtomicInteger();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(
                new HttpsConfigurator(tls.context()) {
                    @Override
                    public void configure(HttpsParameters params) {
                        var p = getSSLContext().getDefaultSSLParameters();
                        p.setNeedClientAuth(true);
                        params.setSSLParameters(p);
                    }
                });
        server.createContext(
                "/api/v1/namespaces",
                exchange -> {
                    byte[] data = "{\"items\":[]}".getBytes();
                    if (!"Bearer fixture-token"
                            .equals(exchange.getRequestHeaders().getFirst("Authorization")))
                        exchange.sendResponseHeaders(401, -1);
                    else {
                        exchange.sendResponseHeaders(200, data.length);
                        exchange.getResponseBody().write(data);
                    }
                    exchange.close();
                });
        server.start();
        int port = server.getAddress().getPort();
        KubernetesEndpoint.TunnelOpener opener =
                (id, host, remotePort) -> {
                    assertEquals("saved-ssh", id);
                    assertEquals("cluster.test.invalid", host);
                    assertEquals(6443, remotePort);
                    return new KubernetesEndpoint.Tunnel(
                            "127.0.0.1", port, releases::incrementAndGet);
                };
        try {
            KubernetesEndpoint route =
                    KubernetesEndpoint.open(
                            "https://cluster.test.invalid:6443",
                            "saved-ssh",
                            "",
                            false,
                            tls.certificate(),
                            tls.certificate(),
                            tls.key(),
                            opener);
            assertTrue(route.server().startsWith("https://127.0.0.1:"));
            try (route;
                    var client =
                            new KubernetesClient(
                                    route.server(),
                                    "fixture-token",
                                    route.socketFactory(),
                                    KubernetesTls.hostnameVerifier(false))) {
                assertEquals("{\"items\":[]}", client.execute("GET", "/api/v1/namespaces", null));
                assertEquals("{\"items\":[]}", client.execute("POST", "/api/v1/namespaces", "{}"));
            }
            route.close();
            assertEquals(1, releases.get());
            try (var mismatch =
                            KubernetesEndpoint.open(
                                    "https://127.0.0.1:" + port,
                                    "",
                                    "wrong.test.invalid",
                                    false,
                                    tls.certificate(),
                                    tls.certificate(),
                                    tls.key());
                    var client =
                            new KubernetesClient(
                                    mismatch.server(),
                                    "",
                                    mismatch.socketFactory(),
                                    KubernetesTls.hostnameVerifier(false))) {
                assertThrows(
                        Exception.class, () -> client.execute("GET", "/api/v1/namespaces", null));
            }
            assertThrows(
                    Exception.class,
                    () ->
                            KubernetesEndpoint.open(
                                    "https://cluster.test.invalid:6443",
                                    "saved-ssh",
                                    "",
                                    false,
                                    "invalid-cert",
                                    null,
                                    null,
                                    opener));
            assertEquals(2, releases.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void execWebSocketUsesRoutedTlsIdentityAndRejectsWrongName() throws Exception {
        TlsFixture tls = tls();
        CountDownLatch started = new CountDownLatch(1);
        WebSocketServer server =
                new WebSocketServer(new InetSocketAddress("127.0.0.1", 0), 1) {
                    public void onOpen(
                            org.java_websocket.WebSocket c,
                            org.java_websocket.handshake.ClientHandshake h) {}

                    public void onClose(
                            org.java_websocket.WebSocket c,
                            int code,
                            String reason,
                            boolean remote) {}

                    public void onMessage(org.java_websocket.WebSocket c, String message) {
                        c.send(message);
                    }

                    public void onError(org.java_websocket.WebSocket c, Exception e) {}

                    public void onStart() {
                        started.countDown();
                    }
                };
        server.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(tls.context()));
        server.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        try {
            for (boolean correct : List.of(true, false)) {
                CompletableFuture<String> echo = new CompletableFuture<>();
                try (var endpoint =
                        KubernetesEndpoint.open(
                                "https://127.0.0.1:" + server.getPort(),
                                "",
                                correct ? "cluster.test.invalid" : "wrong.test.invalid",
                                false,
                                tls.certificate(),
                                null,
                                null)) {
                    WebSocketClient client =
                            new WebSocketClient(
                                    URI.create("wss://127.0.0.1:" + server.getPort() + "/exec")) {
                                public void onOpen(org.java_websocket.handshake.ServerHandshake h) {
                                    send("fixture-echo");
                                }

                                public void onMessage(String message) {
                                    echo.complete(message);
                                }

                                public void onClose(int code, String reason, boolean remote) {}

                                public void onError(Exception e) {
                                    echo.completeExceptionally(e);
                                }
                            };
                    client.setSocketFactory(
                            ((RoutedTlsSocketFactory) endpoint.socketFactory()).webSocketFactory());
                    try {
                        assertEquals(correct, client.connectBlocking(5, TimeUnit.SECONDS));
                        if (correct) assertEquals("fixture-echo", echo.get(5, TimeUnit.SECONDS));
                    } finally {
                        // A rejected TLS handshake can leave Java-WebSocket's close latch unset.
                        // Release the socket directly instead of waiting on that latch indefinitely.
                        client.closeConnection(1000, "fixture complete");
                    }
                }
            }
        } finally {
            server.stop(1000);
        }
    }

    @Test
    void rejectsUnsafeEndpointsBeforeOpeningSshAndPreservesApiPrefix() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        KubernetesEndpoint.TunnelOpener opener =
                (id, host, port) -> {
                    opened.incrementAndGet();
                    return new KubernetesEndpoint.Tunnel("127.0.0.1", 12345, () -> {});
                };
        for (String url :
                List.of(
                        "http://user:pass@localhost",
                        "file:///tmp/nope",
                        "https://localhost/?token=x",
                        "https://localhost/#x",
                        "http://localhost:0"))
            assertThrows(
                    Exception.class,
                    () -> KubernetesEndpoint.open(url, "ssh", "", false, null, null, null, opener));
        assertEquals(0, opened.get());
        try (var route =
                KubernetesEndpoint.open(
                        "http://[::1]:8080/prefix/", "ssh", "", false, null, null, null, opener)) {
            assertEquals("http://127.0.0.1:12345/prefix", route.server());
        }
    }
}
