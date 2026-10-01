package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SseParser;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.I18n;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Cancellable streaming HTTP. Auto-reconnect is limited to GET and never forwards credentials
 * across redirects.
 */
public final class SseSession implements AutoCloseable {
    public record Received(Instant time, long elapsedMillis, SseParser.Event event) {}

    private static final HttpClient CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final HttpRequest request;
    private final boolean reconnect;
    private final Consumer<Received> events;
    private final Consumer<String> status;
    private volatile boolean closed;
    private volatile InputStream stream;
    private volatile CompletableFuture<?> pending;
    private Thread thread;
    private boolean started;
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    public CompletableFuture<Void> completion() {
        return completion;
    }

    public SseSession(
            String url,
            String method,
            Map<String, String> headers,
            String body,
            boolean reconnect,
            Consumer<Received> events,
            Consumer<String> status) {
        if (!method.equals("GET") && !method.equals("POST"))
            throw new IllegalArgumentException("SSE supports GET or POST");
        if (reconnect && !method.equals("GET"))
            throw new IllegalArgumentException(I18n.get("sse.getReconnect"));
        var builder =
                HttpRequest.newBuilder(URI.create(url))
                        .method(
                                method,
                                method.equals("POST")
                                        ? HttpRequest.BodyPublishers.ofString(body)
                                        : HttpRequest.BodyPublishers.noBody());
        headers.forEach(builder::header);
        builder.setHeader("Accept", "text/event-stream");
        request = builder.build();
        this.reconnect = reconnect;
        this.events = events;
        this.status = status;
    }

    public synchronized void start() {
        if (started || closed) throw new IllegalStateException("Session already started or closed");
        started = true;
        thread = DaemonThreads.factory("sse-client").newThread(this::run);
        thread.start();
    }

    private void run() {
        String lastId = request.headers().firstValue("Last-Event-ID").orElse("");
        long retry = 3000;
        long startedAt = System.nanoTime();
        int reconnects = 0;
        boolean terminal = false;
        try {
            while (!closed) {
                status.accept(I18n.get("sse.connecting"));
                var next =
                        HttpRequest.newBuilder(
                                request, (k, v) -> !k.equalsIgnoreCase("Last-Event-ID"));
                SseParser parser =
                        new SseParser(
                                lastId,
                                retry,
                                event -> {
                                    if (!closed)
                                        events.accept(
                                                new Received(
                                                        Instant.now(),
                                                        TimeUnit.NANOSECONDS.toMillis(
                                                                System.nanoTime() - startedAt),
                                                        event));
                                });
                try {
                    if (!lastId.isEmpty()) next.setHeader("Last-Event-ID", lastId);
                    CompletableFuture<HttpResponse<InputStream>> future =
                            CLIENT.sendAsync(
                                    next.build(), HttpResponse.BodyHandlers.ofInputStream());
                    pending = future;
                    if (closed) {
                        future.cancel(true);
                        return;
                    }
                    HttpResponse<InputStream> response = future.get(15, TimeUnit.SECONDS);
                    stream = response.body();
                    if (closed) return;
                    if (response.statusCode() == 204) {
                        status.accept(I18n.get("sse.serverStopped"));
                        terminal = true;
                        return;
                    }
                    if (response.statusCode() != 200)
                        throw new IllegalArgumentException(
                                I18n.get("sse.status", response.statusCode()));
                    String type =
                            response.headers()
                                    .firstValue("Content-Type")
                                    .orElse("")
                                    .split(";", 2)[0]
                                    .trim();
                    if (!type.equalsIgnoreCase("text/event-stream"))
                        throw new IllegalArgumentException(I18n.get("sse.contentType"));
                    status.accept(I18n.get("sse.connected"));
                    parser.read(new InputStreamReader(stream, StandardCharsets.UTF_8));
                } catch (IllegalArgumentException protocol) {
                    status.accept(protocol.getMessage());
                    terminal = true;
                    return;
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception error) {
                    if (!closed) {
                        status.accept(I18n.get("sse.failed", error.getClass().getSimpleName()));
                        if (!reconnect) {
                            terminal = true;
                            return;
                        }
                    }
                } finally {
                    lastId = parser.lastId();
                    retry = parser.retryMillis();
                    closeStream();
                    CompletableFuture<?> waiting = pending;
                    if (waiting != null && !waiting.isDone()) waiting.cancel(true);
                    pending = null;
                }
                if (!reconnect || closed) return;
                if (++reconnects > 20) {
                    status.accept(I18n.get("sse.retryLimit"));
                    terminal = true;
                    return;
                }
                status.accept(I18n.get("sse.reconnecting", retry));
                Thread.sleep(retry);
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } finally {
            closed = true;
            closeStream();
            if (!terminal) status.accept(I18n.get("sse.stopped"));
            completion.complete(null);
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private void closeStream() {
        InputStream current = stream;
        stream = null;
        if (current != null)
            try {
                current.close();
            } catch (IOException ignored) {
            }
    }

    @Override
    public void close() {
        closed = true;
        CompletableFuture<?> future = pending;
        if (future != null) future.cancel(true);
        closeStream();
        Thread worker = thread;
        if (worker != null) worker.interrupt();
        else completion.complete(null);
    }
}
