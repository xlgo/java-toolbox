package com.aqishi.toolbox.infra.messaging;

import com.aqishi.toolbox.infra.ManagedResource;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * Lifecycle adapter for an Eclipse Paho MQTT client.
 */
public final class MqttResource implements ManagedResource {
    private IMqttClient client;

    /**
     * Wraps an MQTT client.
     *
     * @param client client to own
     */
    public MqttResource(IMqttClient client) {
        if (client == null) {
            throw new NullPointerException("client");
        }
        this.client = client;
    }

    /**
     * Returns the wrapped MQTT client.
     *
     * @return MQTT client
     * @throws IllegalStateException when this resource is closed
     */
    public synchronized IMqttClient client() {
        if (client == null) {
            throw new IllegalStateException("MQTT client is closed");
        }
        return client;
    }

    @Override
    public synchronized boolean isOpen() {
        return client != null && client.isConnected();
    }

    @Override
    public synchronized void close() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
            client.close();
        } catch (MqttException ignored) {
            // MQTT shutdown is best-effort at the common lifecycle boundary
        } finally {
            client = null;
        }
    }
}
