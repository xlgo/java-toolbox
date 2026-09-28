package com.aqishi.toolbox.feature.network.application;

import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttTopic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hand-written {@link IMqttClient} stand-in: records the calling thread of every
 * operation, counts closes, and lets a test inject a connect failure or the loss
 * of the connection callback.
 */
public final class FakeMqttClient implements IMqttClient {

    public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    public final AtomicReference<String> connectThread = new AtomicReference<>();
    public final AtomicReference<String> publishThread = new AtomicReference<>();
    public final AtomicReference<String> subscribeThread = new AtomicReference<>();
    public final CountDownLatch connectCalled = new CountDownLatch(1);
    public final CountDownLatch publishCalled = new CountDownLatch(1);
    public final CountDownLatch disconnectCalled = new CountDownLatch(1);
    public final CountDownLatch closed = new CountDownLatch(1);

    public volatile MqttException connectFailure;
    public volatile MqttException publishFailure;
    public volatile boolean suppressConnectionLost;
    public volatile boolean connected;
    /** Blocks connect until released, to model an unreachable broker. */
    public volatile CountDownLatch connectGate;
    /** Blocks a publish until released, to model a slow broker. */
    public volatile CountDownLatch publishGate;

    private volatile MqttCallback callback;

    public boolean connectWasOn(String threadName) {
        String name = connectThread.get();
        return name != null && name.startsWith(threadName);
    }

    public void failConnection() {
        MqttCallback c = callback;
        if (c != null) {
            c.connectionLost(new MqttException(MqttException.REASON_CODE_CONNECTION_LOST));
        }
    }

    public void deliver(String topic, byte[] payload, int qos) throws Exception {
        MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);
        MqttCallback c = callback;
        if (c != null) {
            c.messageArrived(topic, message);
        }
    }

    @Override
    public void connect() throws MqttException {
        connect(null);
    }

    @Override
    public void connect(MqttConnectOptions options) throws MqttException {
        calls.add("connect");
        connectThread.set(Thread.currentThread().getName());
        connectCalled.countDown();
        await(connectGate);
        if (connectFailure != null) {
            throw connectFailure;
        }
        connected = true;
    }

    @Override
    public IMqttToken connectWithResult(MqttConnectOptions options) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void disconnect() throws MqttException {
        calls.add("disconnect");
        connected = false;
        disconnectCalled.countDown();
    }

    @Override
    public void disconnect(long quiesceTimeout) throws MqttException {
        disconnect();
        if (!suppressConnectionLost) {
            failConnection();
        }
    }

    @Override
    public void disconnectForcibly() {
        calls.add("disconnectForcibly");
        connected = false;
        disconnectCalled.countDown();
    }

    @Override
    public void disconnectForcibly(long disconnectTimeout) {
        disconnectForcibly();
    }

    @Override
    public void disconnectForcibly(long disconnectTimeout, long quiesceTimeout) {
        disconnectForcibly();
    }

    @Override
    public void subscribe(String topicFilter) {
        subscribe(topicFilter, 1);
    }

    @Override
    public void subscribe(String[] topicFilters) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void subscribe(String topicFilter, int qos) {
        calls.add("subscribe:" + topicFilter + ":" + qos);
        subscribeThread.set(Thread.currentThread().getName());
    }

    @Override
    public void subscribe(String[] topicFilters, int[] qos) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void subscribe(String topicFilter, IMqttMessageListener listener) {
        subscribe(topicFilter, 1);
    }

    @Override
    public void subscribe(String[] topicFilters, IMqttMessageListener[] listeners) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void subscribe(String topicFilter, int qos, IMqttMessageListener listener) {
        subscribe(topicFilter, qos);
    }

    @Override
    public void subscribe(String[] topicFilters, int[] qos, IMqttMessageListener[] listeners) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IMqttToken subscribeWithResponse(String topicFilter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IMqttToken subscribeWithResponse(String topicFilter, IMqttMessageListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IMqttToken subscribeWithResponse(String topicFilter, int qos) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IMqttToken subscribeWithResponse(String topicFilter, int qos, IMqttMessageListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IMqttToken subscribeWithResponse(String[] topicFilters) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IMqttToken subscribeWithResponse(String[] topicFilters, IMqttMessageListener[] listeners) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IMqttToken subscribeWithResponse(String[] topicFilters, int[] qos) {
        throw new UnsupportedOperationException();
    }

    @Override
    public IMqttToken subscribeWithResponse(String[] topicFilters, int[] qos, IMqttMessageListener[] listeners) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void unsubscribe(String topicFilter) {
        calls.add("unsubscribe:" + topicFilter);
    }

    @Override
    public void unsubscribe(String[] topicFilters) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void publish(String topic, byte[] payload, int qos, boolean retained) throws MqttException {
        MqttMessage message = new MqttMessage(payload);
        message.setQos(qos);
        message.setRetained(retained);
        publish(topic, message);
    }

    @Override
    public void publish(String topic, MqttMessage message) throws MqttException {
        calls.add("publish:" + topic);
        publishThread.set(Thread.currentThread().getName());
        publishCalled.countDown();
        await(publishGate);
        if (publishFailure != null) {
            throw publishFailure;
        }
    }

    @Override
    public void setCallback(MqttCallback callback) {
        this.callback = callback;
    }

    @Override
    public MqttTopic getTopic(String topic) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getClientId() {
        return "fake";
    }

    @Override
    public String getServerURI() {
        return "tcp://fake:1883";
    }

    @Override
    public IMqttDeliveryToken[] getPendingDeliveryTokens() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setManualAcks(boolean manualAcks) {
        // no-op
    }

    @Override
    public void reconnect() {
        calls.add("reconnect");
        connected = true;
    }

    @Override
    public void messageArrivedComplete(int messageId, int qos) {
        // no-op
    }

    @Override
    public void close() {
        calls.add("close");
        connected = false;
        closed.countDown();
    }

    private static void await(CountDownLatch gate) {
        if (gate == null) {
            return;
        }
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
