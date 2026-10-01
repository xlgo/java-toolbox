package com.aqishi.toolbox.feature.network.application;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class SseSessionTest {
    @Test
    void reconnectSendsLastEventIdAndStopsOn204() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> lastId = new AtomicReference<>();
        CountDownLatch twice = new CountDownLatch(1);
        List<SseSession.Received> events = new CopyOnWriteArrayList<>();
        server.createContext(
                "/",
                e -> {
                    int n = calls.incrementAndGet();
                    if (n == 1) {
                        e.getResponseHeaders()
                                .add("Content-Type", "text/event-stream; charset=UTF-8");
                        byte[] bytes =
                                "id: abc\nretry: 100\ndata: 中文\n\n"
                                        .getBytes(StandardCharsets.UTF_8);
                        e.sendResponseHeaders(200, bytes.length);
                        e.getResponseBody().write(bytes);
                    } else {
                        lastId.set(e.getRequestHeaders().getFirst("Last-Event-ID"));
                        e.sendResponseHeaders(204, -1);
                        twice.countDown();
                    }
                    e.close();
                });
        server.start();
        var session =
                new SseSession(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "GET",
                        Map.of(),
                        "",
                        true,
                        events::add,
                        s -> {});
        try {
            session.start();
            assertTrue(twice.await(5, TimeUnit.SECONDS));
            awaitClosed(session);
            assertEquals("abc", lastId.get());
            assertEquals("中文", events.get(0).event().data());
            assertEquals(2, calls.get());
        } finally {
            session.close();
            server.stop(0);
        }
    }

    @Test
    void postBodyAndCancelAnIdleStream() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                received = new CountDownLatch(1);
        AtomicReference<String> body = new AtomicReference<>();
        server.createContext(
                "/",
                e -> {
                    body.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    e.getResponseHeaders().add("Content-Type", "text/event-stream");
                    e.sendResponseHeaders(200, 0);
                    e.getResponseBody().write("data: started\n\n".getBytes(StandardCharsets.UTF_8));
                    e.getResponseBody().flush();
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        e.close();
                    }
                });
        server.start();
        var session =
                new SseSession(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "POST",
                        Map.of("Content-Type", "application/json"),
                        "{\"stream\":true}",
                        false,
                        e -> received.countDown(),
                        s -> {});
        try {
            session.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals("{\"stream\":true}", body.get());
            session.close();
            session.completion().get(3, TimeUnit.SECONDS);
            assertTrue(session.isClosed());
        } finally {
            session.close();
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    void wrongContentTypeIsAnErrorAndPostCannotAutoReconnect() throws Exception {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new SseSession(
                                "http://localhost", "POST", Map.of(), "", true, e -> {}, s -> {}));
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    e.getResponseHeaders().add("Content-Type", "application/json");
                    e.sendResponseHeaders(200, 2);
                    e.getResponseBody().write("{}".getBytes());
                    e.close();
                });
        server.start();
        List<String> statuses = new CopyOnWriteArrayList<>();
        var session =
                new SseSession(
                        "http://127.0.0.1:" + server.getAddress().getPort(),
                        "GET",
                        Map.of(),
                        "",
                        false,
                        e -> fail(),
                        statuses::add);
        try {
            session.start();
            awaitClosed(session);
            assertTrue(statuses.stream().anyMatch(s -> s.contains("text/event-stream")));
        } finally {
            session.close();
            server.stop(0);
        }
    }

    private static void awaitClosed(SseSession session) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!session.isClosed() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(session.isClosed());
    }
}
