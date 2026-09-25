package com.aqishi.toolbox.feature.security.infra;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwksFetcherTest {

    private static final String JWKS = "{\"keys\":[{\"kty\":\"oct\",\"kid\":\"a\",\"k\":\"AAAA\"}]}";

    private HttpServer server;
    private String base;
    private JwksFetcher fetcher;
    private final CountDownLatch slowRelease = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/realms/demo/.well-known/openid-configuration", exchange ->
                respond(exchange, 200, "application/json",
                        "{\"issuer\":\"" + base + "/realms/demo\",\"jwks_uri\":\"" + base + "/realms/demo/certs\"}"));
        server.createContext("/realms/demo/certs", exchange -> respond(exchange, 200, "application/json", JWKS));
        server.createContext("/keys/jwks.json", exchange -> respond(exchange, 200, "application/jwk-set+json", JWKS));
        server.createContext("/big/jwks", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            // 分块发送、不声明长度，逼读取端靠计数截断。
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) ' ');
                for (int i = 0; i < 40; i++) {
                    out.write(chunk);
                }
            } catch (IOException ignored) {
                // 客户端提前断开属正常
            }
        });
        server.createContext("/missing/jwks", exchange -> respond(exchange, 404, "text/plain", "nope"));
        server.createContext("/html/jwks", exchange -> respond(exchange, 200, "text/html", "<html></html>"));
        server.createContext("/slow/jwks", exchange -> {
            try {
                slowRelease.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "application/json", JWKS);
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        fetcher = new JwksFetcher(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL).build(), 1024 * 1024);
    }

    @AfterEach
    void stop() {
        slowRelease.countDown();
        fetcher.close();
        server.stop(0);
    }

    @Test
    void issuerBaseGoesThroughDiscovery() throws Exception {
        JwksFetcher.Result result = fetcher.fetch(base + "/realms/demo/");

        assertEquals(base + "/realms/demo/certs", result.jwksUrl());
        assertEquals(base + "/realms/demo/.well-known/openid-configuration", result.discoveryUrl());
        assertEquals(base + "/realms/demo", result.issuer());
        assertEquals(JWKS, result.body());
        assertTrue(result.warnings().contains(JwksFetcher.Warning.INSECURE_HTTP));
        assertFalse(result.warnings().contains(JwksFetcher.Warning.ISSUER_MISMATCH));
    }

    @Test
    void discoveryDocumentUrlIsFollowed() throws Exception {
        JwksFetcher.Result result = fetcher.fetch(base + "/realms/demo/.well-known/openid-configuration");

        assertEquals(base + "/realms/demo/certs", result.jwksUrl());
        assertEquals(JWKS, result.body());
    }

    @Test
    void directJwksUrl() throws Exception {
        JwksFetcher.Result result = fetcher.fetch(base + "/keys/jwks.json");

        assertEquals(JWKS, result.body());
        assertNull(result.discoveryUrl());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void bodyIsCapped() {
        JwksFetcher.FetchException error = assertThrows(JwksFetcher.FetchException.class,
                () -> fetcher.fetch(base + "/big/jwks"));
        assertEquals("bodyTooLarge", error.getCode());
    }

    @Test
    void non2xxAndNonJsonAreRejected() {
        JwksFetcher.FetchException status = assertThrows(JwksFetcher.FetchException.class,
                () -> fetcher.fetch(base + "/missing/jwks"));
        assertEquals("httpStatus", status.getCode());
        assertEquals(404, status.getParams().get(0));

        JwksFetcher.FetchException html = assertThrows(JwksFetcher.FetchException.class,
                () -> fetcher.fetch(base + "/html/jwks"));
        assertEquals("notJson", html.getCode());
    }

    @Test
    void rejectsBadUrls() {
        assertEquals("unsupportedScheme", assertThrows(JwksFetcher.FetchException.class,
                () -> fetcher.fetch("file:///etc/passwd")).getCode());
        assertEquals("emptyUrl", assertThrows(JwksFetcher.FetchException.class,
                () -> fetcher.fetch("  ")).getCode());
    }

    @Test
    void asyncFetchDeliversAndCancelSuppressesCallback() throws Exception {
        AtomicReference<JwksFetcher.Result> delivered = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        fetcher.fetchAsync(base + "/keys/jwks.json", result -> {
            delivered.set(result);
            done.countDown();
        }, error -> done.countDown());
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(JWKS, delivered.get().body());

        AtomicReference<Object> late = new AtomicReference<>();
        Future<JwksFetcher.Result> slow = fetcher.fetchAsync(base + "/slow/jwks", late::set, late::set);
        assertTrue(slow.cancel(true));
        slowRelease.countDown();
        Thread.sleep(300);
        assertTrue(slow.isCancelled());
        assertNull(late.get());
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
