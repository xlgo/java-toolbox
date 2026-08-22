package com.aqishi.toolbox.infra.messaging;

import com.aqishi.toolbox.infra.ManagedResource;

/**
 * Lifecycle adapter for Kafka producers, consumers, and admin clients.
 *
 * <p>Kafka client implementations are closeable but do not share a common
 * public client interface, so this adapter accepts the JDK
 * {@link AutoCloseable}
 * contract and exposes the client as-is to the owning service.</p>
 */
public final class KafkaResource implements ManagedResource {
    private AutoCloseable client;
    private boolean open = true;

    /**
     * Wraps a Kafka client that is owned by the caller's service.
     *
     * @param client Kafka producer, consumer, or admin client
     */
    public KafkaResource(AutoCloseable client) {
        if (client == null) {
            throw new NullPointerException("client");
        }
        this.client = client;
    }

    /**
     * Returns the underlying Kafka client.
     *
     * @return client
     * @throws IllegalStateException when this resource is closed
     */
    public synchronized AutoCloseable client() {
        if (!isOpen()) {
            throw new IllegalStateException("Kafka client is closed");
        }
        return client;
    }

    @Override
    public synchronized boolean isOpen() {
        return open && client != null;
    }

    @Override
    public synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (Exception ignored) {
            // Kafka close is best-effort at the common lifecycle boundary
        } finally {
            client = null;
        }
    }
}
