package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * One reconnectable WebSocket client connection plus its heartbeat, with no Swing
 * dependency.
 *
 * <p>Every {@link #connect} / {@link #disconnect} starts a new generation; events
 * from a client of an older generation are ignored, so the late {@code onClose}
 * of a replaced socket can no longer flip the UI to "disconnected" or stop the
 * heartbeat of the new connection. Socket open/close work runs on a private
 * daemon thread, never on the caller's (EDT) thread.</p>
 *
 * <p>State events ({@link Listener#onOpen}, {@link Listener#onClose},
 * {@link Listener#onError}) are delivered through the callback executor
 * (the EDT in production). Log events ({@link Listener#onMessage},
 * {@link Listener#onSent}, {@link Listener#onHeartbeat}) are delivered directly
 * on network threads and must only feed a thread-safe sink.</p>
 */
public final class WebSocketSession {

    public static final int CONNECT_TIMEOUT_MS = 10_000;

    /** Receives session events; see the class comment for threading. */
    public interface Listener {
        void onOpen(int httpStatus);

        void onClose(int code, String reason, boolean remote);

        void onError(String message);

        void onMessage(String text);

        void onSent(String text);

        void onHeartbeat(String payload);
    }

    private final Object lock = new Object();
    private final Listener listener;
    private final Executor callbacks;
    private final ExecutorService io = DaemonThreads.single("websocket-io");
    private final HeartbeatScheduler heartbeat;

    private long generation;
    private volatile WebSocketClient client;
    private volatile String heartbeatPayload = "ping";
    private boolean closed;

    public WebSocketSession(Listener listener, Executor callbacks) {
        this(listener, callbacks, HeartbeatScheduler.daemonTicker("websocket-heartbeat"));
    }

    public WebSocketSession(Listener listener, Executor callbacks, HeartbeatScheduler.Ticker ticker) {
        if (listener == null || callbacks == null) {
            throw new NullPointerException("listener/callbacks");
        }
        this.listener = listener;
        this.callbacks = callbacks;
        this.heartbeat = new HeartbeatScheduler(ticker, this::beat);
    }

    /** Replaces any current connection with a new one to {@code uri}. Non-blocking. */
    public void connect(URI uri, Map<String, String> headers) {
        Map<String, String> copy = new LinkedHashMap<>(headers == null ? Map.of() : headers);
        long gen;
        WebSocketClient old;
        synchronized (lock) {
            if (closed) {
                return;
            }
            gen = ++generation;
            old = detachLocked();
        }
        submit(() -> {
            closeQuietly(old);
            WebSocketClient fresh = new Client(uri, copy, gen);
            synchronized (lock) {
                if (gen != generation) {
                    return;
                }
                client = fresh;
            }
            fresh.connect();
        });
    }

    /** Closes the current connection; no further events of it reach the listener. */
    public void disconnect() {
        WebSocketClient old;
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            old = detachLocked();
        }
        submit(() -> closeQuietly(old));
    }

    /** Sends a text frame off the caller thread; failures arrive as {@link Listener#onError}. */
    public void send(String text) {
        long gen;
        synchronized (lock) {
            gen = generation;
        }
        submit(() -> {
            WebSocketClient c = client;
            try {
                if (c == null || !c.isOpen()) {
                    throw new IllegalStateException("not connected");
                }
                c.send(text);
                listener.onSent(text);
            } catch (RuntimeException ex) {
                dispatch(gen, () -> listener.onError(String.valueOf(ex.getMessage())));
            }
        });
    }

    /** Applies heartbeat settings; takes effect immediately and survives reconnects. */
    public void configureHeartbeat(boolean enabled, long intervalMs, String payload) {
        heartbeatPayload = payload == null ? "" : payload;
        heartbeat.setIntervalMs(intervalMs);
        heartbeat.setEnabled(enabled);
    }

    public boolean isOpen() {
        WebSocketClient c = client;
        return c != null && c.isOpen();
    }

    /** Visible for tests and status display. */
    public HeartbeatScheduler heartbeat() {
        return heartbeat;
    }

    /** Releases socket, heartbeat thread and io thread. Idempotent, never blocks. */
    public void close() {
        WebSocketClient old;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            old = detachLocked();
        }
        heartbeat.shutdown();
        try {
            io.execute(() -> closeQuietly(old));
        } catch (RejectedExecutionException ignored) {
            closeQuietly(old);
        }
        io.shutdown();
    }

    public boolean isTerminated() {
        return io.isTerminated();
    }

    private WebSocketClient detachLocked() {
        WebSocketClient old = client;
        client = null;
        heartbeat.connectionClosed();
        return old;
    }

    private void beat() {
        WebSocketClient c = client;
        if (c != null && c.isOpen()) {
            String payload = heartbeatPayload;
            c.send(payload);
            listener.onHeartbeat(payload);
        }
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Session closed concurrently; nothing left to do.
        }
    }

    private void dispatch(long gen, Runnable event) {
        callbacks.execute(() -> {
            if (isCurrent(gen)) {
                event.run();
            }
        });
    }

    private boolean isCurrent(long gen) {
        synchronized (lock) {
            return !closed && gen == generation;
        }
    }

    private static void closeQuietly(WebSocketClient c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (RuntimeException ignored) {
            // Best effort: a half-open socket may reject the close frame.
        }
    }

    private final class Client extends WebSocketClient {
        private final long gen;

        Client(URI uri, Map<String, String> headers, long gen) {
            super(uri, new Draft_6455(), headers, CONNECT_TIMEOUT_MS);
            this.gen = gen;
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            synchronized (lock) {
                if (closed || gen != generation) {
                    closeQuietly(this);
                    return;
                }
                heartbeat.connectionOpened();
            }
            int status = handshake.getHttpStatus();
            dispatch(gen, () -> listener.onOpen(status));
        }

        @Override
        public void onMessage(String message) {
            if (isCurrent(gen)) {
                listener.onMessage(message);
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            synchronized (lock) {
                if (closed || gen != generation) {
                    return;
                }
                heartbeat.connectionClosed();
            }
            dispatch(gen, () -> listener.onClose(code, reason, remote));
        }

        @Override
        public void onError(Exception ex) {
            dispatch(gen, () -> listener.onError(String.valueOf(ex.getMessage())));
        }
    }
}
