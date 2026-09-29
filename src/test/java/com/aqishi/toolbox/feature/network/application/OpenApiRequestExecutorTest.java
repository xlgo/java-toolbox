package com.aqishi.toolbox.feature.network.application;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiRequestExecutorTest {
    private HttpServer server;
    private String base;
    private final OpenApiRequestExecutor executor = new OpenApiRequestExecutor();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }

    @ParameterizedTest
    @ValueSource(strings = {"PATCH", "POST", "PUT", "DELETE"})
    void sendsMethodHeadersAndBodyWithoutModification(String method) throws Exception {
        String body = "{\"message\":\"中文\\nline\"}";
        server.createContext("/echo", exchange -> {
            String received = exchange.getRequestMethod() + "|" + exchange.getRequestHeaders().getFirst("Content-Type")
                    + "|" + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = received.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        var response = executor.execute(base + "/echo", method, Map.of("Content-Type", "application/json"), body);
        assertEquals(200, response.status());
        assertEquals(method + "|application/json|" + body, response.body());
    }

    @Test void returnsErrorBodyUsingDeclaredCharsetAndPreservesLineEndings() throws Exception {
        String body = "café\r\nerror";
        server.createContext("/error", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=ISO-8859-1");
            exchange.sendResponseHeaders(422, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        var response = executor.execute(base + "/error", "GET", Map.of(), null);
        assertEquals(422, response.status());
        assertEquals(body, response.body());
    }

    @Test void leavesRedirectVisible() throws Exception {
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        var response = executor.execute(base + "/redirect", "GET", Map.of("Authorization", "Bearer test"), null);
        assertEquals(302, response.status());
        assertEquals("/elsewhere", response.headers().get("location").get(0));
    }

    @Test void rejectsOversizedResponse() {
        server.createContext("/large", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 9 * 1024 * 1024);
                byte[] block = new byte[1024 * 1024];
                for (int i = 0; i < 9; i++) exchange.getResponseBody().write(block);
            } catch (IOException cancelled) {
                // The client intentionally cancels when the response limit is reached.
            } finally { exchange.close(); }
        });
        assertThrows(IOException.class, () -> executor.execute(base + "/large", "GET", Map.of(), null));
    }

    @Test void timeoutIncludesStalledBody() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        stall(release, new CountDownLatch(1));
        try {
            assertThrows(HttpTimeoutException.class, () -> executor.execute(base + "/stall", "GET", Map.of(), null,
                    Duration.ofMillis(500)));
        } finally { release.countDown(); }
    }

    @Test void interruptCancelsInFlightRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        stall(release, entered);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { executor.execute(base + "/stall", "GET", Map.of(), null); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
        } finally {
            release.countDown();
            worker.interrupt();
            worker.join(3000);
        }
    }

    private void stall(CountDownLatch release, CountDownLatch entered) {
        server.createContext("/stall", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write('x');
                exchange.getResponseBody().flush();
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
    }
}
