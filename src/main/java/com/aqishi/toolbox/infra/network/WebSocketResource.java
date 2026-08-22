package com.aqishi.toolbox.infra.network;

import com.aqishi.toolbox.infra.ManagedResource;
import org.java_websocket.client.WebSocketClient;

/**
 * Lifecycle adapter for the Java-WebSocket client used by network tools.
 */
public final class WebSocketResource implements ManagedResource {
    private WebSocketClient client;

    /**
     * Wraps a WebSocket client.
     *
     * @param client client to own
     */
    public WebSocketResource(WebSocketClient client) {
        if (client == null) {
            throw new NullPointerException("client");
        }
        this.client = client;
    }

    /**
     * Returns the wrapped client.
     *
     * @return WebSocket client
     * @throws IllegalStateException when this resource is closed
     */
    public synchronized WebSocketClient client() {
        if (client == null) {
            throw new IllegalStateException("WebSocket is closed");
        }
        return client;
    }

    @Override
    public synchronized boolean isOpen() {
        return client != null && client.isOpen();
    }

    @Override
    public synchronized void close() {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } finally {
            client = null;
        }
    }
}
