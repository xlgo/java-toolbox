package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * One reconnectable Paho MQTT connection whose blocking calls all run on a
 * private single daemon thread, never on the caller's (EDT) thread.
 *
 * <p>Each {@link #connect} / {@link #disconnect} starts a new generation.
 * Completions and callbacks belonging to an older generation are discarded, so
 * a publish or connect that finishes after the user pressed "disconnect" no
 * longer flips the UI. State events are delivered through the callback executor
 * (the EDT in production) and re-checked there; {@link Listener#onMessage} is
 * delivered directly on the Paho callback thread and must only feed a
 * thread-safe sink.</p>
 */
public final class MqttSession {

    public static final int CONNECT_TIMEOUT_SEC = 10;
    static final long OPERATION_TIMEOUT_MS = 15_000;
    static final long QUIESCE_MS = 500;
    static final long FORCED_DISCONNECT_MS = 1000;

    /** Creates the Paho client; injectable so tests can use a fake. */
    public interface ClientFactory {
        IMqttClient create(String serverUri, String clientId) throws MqttException;
    }

    /** What a user operation was, for failure reporting. */
    public enum Operation { CONNECT, SUBSCRIBE, UNSUBSCRIBE, PUBLISH }

    /** Connection parameters captured on the EDT before the work is handed off. */
    public record Settings(String brokerUrl, String clientId, String username, char[] password,
                           int keepAliveSec, boolean cleanSession) {
    }

    /** Receives session events; see the class comment for threading. */
    public interface Listener {
        void onConnected(String brokerUrl);

        void onConnectionLost(String reason);

        void onSubscribed(String topic, int qos);

        void onUnsubscribed(String topic);

        void onPublished(String topic, int qos, boolean retain, String payload);

        void onFailed(Operation operation, String message);

        void onMessage(String topic, MqttMessage message);
    }

    private final Object lock = new Object();
    private final Listener listener;
    private final Executor callbacks;
    private final ClientFactory factory;
    private final ExecutorService io = DaemonThreads.single("mqtt-io");

    private long generation;
    private IMqttClient current;
    private boolean closed;

    public MqttSession(Listener listener, Executor callbacks) {
        this(listener, callbacks, MqttSession::defaultClient);
    }

    /**
     * Paho client with a bounded wait: without it a QoS 1/2 publish to a broker
     * that never acknowledges would park the io thread forever and queue every
     * later disconnect behind it.
     */
    private static IMqttClient defaultClient(String uri, String clientId) throws MqttException {
        MqttClient client = new MqttClient(uri, clientId, new MemoryPersistence());
        client.setTimeToWait(OPERATION_TIMEOUT_MS);
        return client;
    }

    public MqttSession(Listener listener, Executor callbacks, ClientFactory factory) {
        if (listener == null || callbacks == null || factory == null) {
            throw new NullPointerException("listener/callbacks/factory");
        }
        this.listener = listener;
        this.callbacks = callbacks;
        this.factory = factory;
    }

    /** Replaces any current connection with a new one. Non-blocking. */
    public void connect(Settings settings) {
        long gen;
        IMqttClient old;
        synchronized (lock) {
            if (closed) {
                return;
            }
            gen = ++generation;
            old = detachLocked();
        }
        submit(() -> {
            teardown(old);
            if (!isCurrent(gen)) {
                return;
            }
            IMqttClient client;
            try {
                client = factory.create(settings.brokerUrl(), settings.clientId());
                client.setCallback(new Callback(gen));
            } catch (MqttException | RuntimeException ex) {
                dispatch(gen, () -> listener.onFailed(Operation.CONNECT, describe(ex)));
                return;
            }
            synchronized (lock) {
                if (closed || gen != generation) {
                    closeQuietly(client);
                    return;
                }
                current = client;
            }
            try {
                client.connect(toOptions(settings));
                dispatch(gen, () -> listener.onConnected(settings.brokerUrl()));
            } catch (MqttException | RuntimeException ex) {
                boolean mine;
                synchronized (lock) {
                    mine = current == client;
                    if (mine) {
                        current = null;
                    }
                }
                if (mine) {
                    teardown(client);
                }
                dispatch(gen, () -> listener.onFailed(Operation.CONNECT, describe(ex)));
            }
        });
    }

    /** Drops the current connection; its late completions are ignored. Non-blocking. */
    public void disconnect() {
        IMqttClient old;
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            old = detachLocked();
        }
        submit(() -> teardown(old));
    }

    public void subscribe(String topic, int qos) {
        run(Operation.SUBSCRIBE, client -> {
            client.subscribe(topic, qos);
            return () -> listener.onSubscribed(topic, qos);
        });
    }

    public void unsubscribe(String topic) {
        run(Operation.UNSUBSCRIBE, client -> {
            client.unsubscribe(topic);
            return () -> listener.onUnsubscribed(topic);
        });
    }

    public void publish(String topic, byte[] payload, int qos, boolean retain, String displayPayload) {
        byte[] copy = payload.clone();
        run(Operation.PUBLISH, client -> {
            MqttMessage message = new MqttMessage(copy);
            message.setQos(qos);
            message.setRetained(retain);
            client.publish(topic, message);
            return () -> listener.onPublished(topic, qos, retain, displayPayload);
        });
    }

    /** Releases the client and the io thread. Idempotent, never blocks the caller. */
    public void close() {
        IMqttClient old;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            old = detachLocked();
        }
        try {
            io.execute(() -> teardown(old));
        } catch (RejectedExecutionException ignored) {
            // Already shut down.
        }
        io.shutdown();
    }

    public boolean isShutdown() {
        return io.isShutdown();
    }

    public boolean isTerminated() {
        return io.isTerminated();
    }

    private interface ClientOp {
        Runnable apply(IMqttClient client) throws MqttException;
    }

    private void run(Operation operation, ClientOp op) {
        long gen;
        synchronized (lock) {
            if (closed) {
                return;
            }
            gen = generation;
        }
        submit(() -> {
            IMqttClient client;
            synchronized (lock) {
                client = gen == generation ? current : null;
            }
            if (client == null || !client.isConnected()) {
                dispatch(gen, () -> listener.onFailed(operation, "not connected"));
                return;
            }
            try {
                Runnable done = op.apply(client);
                dispatch(gen, done);
            } catch (MqttException | RuntimeException ex) {
                dispatch(gen, () -> listener.onFailed(operation, describe(ex)));
            }
        });
    }

    private IMqttClient detachLocked() {
        IMqttClient old = current;
        current = null;
        return old;
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Session closed concurrently.
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

    private static MqttConnectOptions toOptions(Settings settings) {
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(settings.cleanSession());
        options.setKeepAliveInterval(settings.keepAliveSec());
        options.setConnectionTimeout(CONNECT_TIMEOUT_SEC);
        String user = settings.username();
        if (user != null && !user.isEmpty()) {
            options.setUserName(user);
            char[] password = settings.password();
            options.setPassword(password == null ? new char[0] : password.clone());
        }
        return options;
    }

    /** Graceful disconnect with a short quiesce, then forcibly, then release. */
    static void teardown(IMqttClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect(QUIESCE_MS);
            }
        } catch (MqttException | RuntimeException graceful) {
            try {
                client.disconnectForcibly(0, FORCED_DISCONNECT_MS);
            } catch (MqttException | RuntimeException ignored) {
                // Best effort.
            }
        }
        closeQuietly(client);
    }

    private static void closeQuietly(IMqttClient client) {
        try {
            client.close();
        } catch (MqttException | RuntimeException ignored) {
            // Best effort.
        }
    }

    private static String describe(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }

    private final class Callback implements MqttCallback {
        private final long gen;

        Callback(long gen) {
            this.gen = gen;
        }

        @Override
        public void connectionLost(Throwable cause) {
            String reason = cause == null ? "" : describe(cause);
            dispatch(gen, () -> listener.onConnectionLost(reason));
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            if (isCurrent(gen)) {
                listener.onMessage(topic, message);
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // Publish completion is reported by the blocking publish on the io thread.
        }
    }
}
