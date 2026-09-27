package com.aqishi.toolbox.feature.network.application;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 压测测试用的本地 HTTP 服务：只绑 127.0.0.1 的临时端口，统计命中数与服务端并发峰值。
 *
 * <p>慢响应用门闩阻塞而不是固定 sleep：{@link #close()} 时打开门闩，
 * 测试结束不必等处理线程睡醒。</p>
 */
final class BenchTestServer implements AutoCloseable {

    /** 捕获到的一次请求。 */
    static final class Captured {
        final String method;
        final String header;
        final String body;

        Captured(String method, String header, String body) {
            this.method = method;
            this.header = header;
            this.body = body;
        }
    }

    final AtomicInteger hits = new AtomicInteger();
    final AtomicInteger current = new AtomicInteger();
    final AtomicInteger peak = new AtomicInteger();
    final List<Captured> captured = new CopyOnWriteArrayList<>();

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "bench-test-server");
        thread.setDaemon(true);
        return thread;
    });
    private final CountDownLatch release = new CountDownLatch(1);

    private volatile int status = 200;
    private volatile String responseBody = "ok";
    private volatile long delayMillis;
    private volatile boolean block;

    BenchTestServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1000);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    BenchTestServer status(int value) {
        this.status = value;
        return this;
    }

    BenchTestServer body(String value) {
        this.responseBody = value;
        return this;
    }

    BenchTestServer delay(long millis) {
        this.delayMillis = millis;
        return this;
    }

    /** 让每个请求一直挂着，直到 {@link #close()}。 */
    BenchTestServer blockForever() {
        this.block = true;
        return this;
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/bench";
    }

    private void handle(HttpExchange exchange) throws IOException {
        int now = current.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);
        try (InputStream in = exchange.getRequestBody()) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            captured.add(new Captured(exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("X-Bench"), body));
            hits.incrementAndGet();
            if (block) {
                release.await(30, TimeUnit.SECONDS);
            } else if (delayMillis > 0) {
                release.await(delayMillis, TimeUnit.MILLISECONDS);
            }
            byte[] payload = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            current.decrementAndGet();
            exchange.close();
        }
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        executor.shutdownNow();
    }
}
