package com.aqishi.toolbox.feature.network.application;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Echo server on 127.0.0.1 and an ephemeral port that records what it receives. */
public final class LoopbackWebSocketServer extends WebSocketServer {

    public final List<String> received = new CopyOnWriteArrayList<>();
    public final AtomicInteger opened = new AtomicInteger();
    private final CountDownLatch started = new CountDownLatch(1);

    public LoopbackWebSocketServer() {
        super(new InetSocketAddress("127.0.0.1", 0));
        setReuseAddr(true);
    }

    public static LoopbackWebSocketServer startNew() throws InterruptedException {
        LoopbackWebSocketServer server = new LoopbackWebSocketServer();
        server.start();
        if (!server.started.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("server did not start");
        }
        return server;
    }

    public URI uri() {
        return URI.create("ws://127.0.0.1:" + getPort() + "/ws");
    }

    public int openConnections() {
        return getConnections().size();
    }

    public long count(String message) {
        return received.stream().filter(message::equals).count();
    }

    public void stopQuietly() {
        try {
            stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        opened.incrementAndGet();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        // recorded through getConnections()
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        received.add(message);
        conn.send("echo:" + message);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // tests assert on client-side events
    }

    @Override
    public void onStart() {
        started.countDown();
    }
}
