package com.aqishi.toolbox.feature.network.application;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MqttSessionTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private final List<FakeMqttClient> created = new CopyOnWriteArrayList<>();
    private final List<Boolean> eventsOnEdt = new CopyOnWriteArrayList<>();
    private MqttSession session;

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.close();
        }
    }

    private MqttSession newSession(FakeMqttClient... clients) {
        List<FakeMqttClient> queue = new CopyOnWriteArrayList<>(List.of(clients));
        session = new MqttSession(new Recorder(), SwingUtilities::invokeLater, (uri, id) -> {
            FakeMqttClient next = queue.isEmpty() ? new FakeMqttClient() : queue.remove(0);
            created.add(next);
            return next;
        });
        return session;
    }

    private static MqttSession.Settings settings() {
        return new MqttSession.Settings("tcp://127.0.0.1:1", "test", "user", "pw".toCharArray(), 30, true);
    }

    @Test
    void connectAndPublishRunOffTheEdtAndNeverBlockIt() throws Exception {
        FakeMqttClient client = new FakeMqttClient();
        client.connectGate = new CountDownLatch(1); // broker "hangs"
        newSession(client);

        long elapsed = onEdtTimed(() -> session.connect(settings()));
        assertTrue(client.connectCalled.await(5, TimeUnit.SECONDS));
        assertTrue(elapsed < 1000, "connect blocked the EDT for " + elapsed + " ms");
        assertTrue(client.connectWasOn("mqtt-io"), "connect ran on " + client.connectThread.get());

        client.connectGate.countDown();
        await("connected", () -> events.contains("connected:tcp://127.0.0.1:1"));

        client.publishGate = new CountDownLatch(1);
        elapsed = onEdtTimed(() -> session.publish("t/1", "hi".getBytes(StandardCharsets.UTF_8), 1, false, "hi"));
        assertTrue(client.publishCalled.await(5, TimeUnit.SECONDS));
        assertTrue(elapsed < 1000, "publish blocked the EDT for " + elapsed + " ms");
        assertTrue(client.publishThread.get().startsWith("mqtt-io"));
        client.publishGate.countDown();
        await("published", () -> events.contains("published:t/1:1:false:hi"));

        session.subscribe("s/#", 2);
        await("subscribed", () -> events.contains("subscribed:s/#:2"));
        assertTrue(client.subscribeThread.get().startsWith("mqtt-io"));
        assertTrue(eventsOnEdt.stream().allMatch(Boolean::booleanValue), "state events must arrive on the EDT");
    }

    @Test
    void publishFinishingAfterDisconnectIsIgnored() throws Exception {
        FakeMqttClient client = new FakeMqttClient();
        newSession(client);
        session.connect(settings());
        await("connected", () -> events.stream().anyMatch(e -> e.startsWith("connected")));

        client.publishGate = new CountDownLatch(1);
        session.publish("t/late", new byte[]{1}, 1, false, "late");
        assertTrue(client.publishCalled.await(5, TimeUnit.SECONDS));
        session.disconnect();
        client.publishGate.countDown();

        assertTrue(client.closed.await(5, TimeUnit.SECONDS), "disconnect tears the client down");
        drainEdt();
        assertFalse(events.stream().anyMatch(e -> e.startsWith("published")), events.toString());
        assertFalse(events.stream().anyMatch(e -> e.startsWith("lost")),
                "connectionLost of a replaced client is stale: " + events);
        assertTrue(client.calls.contains("disconnect"));
    }

    @Test
    void connectCompletingAfterDisconnectIsIgnoredAndCleanedUp() throws Exception {
        FakeMqttClient client = new FakeMqttClient();
        client.connectGate = new CountDownLatch(1);
        newSession(client);
        session.connect(settings());
        assertTrue(client.connectCalled.await(5, TimeUnit.SECONDS));
        session.disconnect();
        client.connectGate.countDown();

        assertTrue(client.closed.await(5, TimeUnit.SECONDS));
        drainEdt();
        assertFalse(events.stream().anyMatch(e -> e.startsWith("connected")), events.toString());
    }

    @Test
    void failedConnectReleasesTheClientAndReportsOnce() throws Exception {
        FakeMqttClient client = new FakeMqttClient();
        client.connectFailure = new MqttException(MqttException.REASON_CODE_SERVER_CONNECT_ERROR);
        newSession(client);
        session.connect(settings());
        await("failure", () -> events.stream().anyMatch(e -> e.startsWith("failed:CONNECT")));
        assertTrue(client.closed.await(5, TimeUnit.SECONDS), "failed client is closed, not leaked");
        assertEquals(1, events.stream().filter(e -> e.startsWith("failed")).count());
    }

    @Test
    void operationsWithoutConnectionFailFast() throws Exception {
        newSession();
        session.publish("t", new byte[0], 0, false, "");
        await("publish failure", () -> events.contains("failed:PUBLISH:not connected"));
    }

    @Test
    void reconnectReplacesTheOldClientAndDeliversMessagesOfTheCurrentOneOnly() throws Exception {
        FakeMqttClient first = new FakeMqttClient();
        FakeMqttClient second = new FakeMqttClient();
        newSession(first, second);
        session.connect(settings());
        await("first connect", () -> events.stream().filter(e -> e.startsWith("connected")).count() == 1);

        session.connect(settings());
        await("second connect", () -> events.stream().filter(e -> e.startsWith("connected")).count() == 2);
        assertTrue(first.closed.await(5, TimeUnit.SECONDS));

        first.deliver("old", new byte[]{'x'}, 0);
        second.deliver("new", "y".getBytes(StandardCharsets.UTF_8), 1);
        assertEquals(List.of("message:new:y"), events.stream().filter(e -> e.startsWith("message")).toList());

        second.failConnection();
        await("lost", () -> events.stream().anyMatch(e -> e.startsWith("lost")));
    }

    @Test
    void closeReleasesClientAndExecutor() throws Exception {
        FakeMqttClient client = new FakeMqttClient();
        newSession(client);
        session.connect(settings());
        await("connected", () -> events.stream().anyMatch(e -> e.startsWith("connected")));

        session.close();
        session.close();
        assertTrue(session.isShutdown());
        assertTrue(client.closed.await(5, TimeUnit.SECONDS));
        await("terminated", session::isTerminated);
        session.connect(settings()); // ignored after close, must not throw
        assertEquals(1, created.size());
    }

    private final class Recorder implements MqttSession.Listener {
        private void record(String event) {
            eventsOnEdt.add(SwingUtilities.isEventDispatchThread());
            events.add(event);
        }

        @Override
        public void onConnected(String brokerUrl) {
            record("connected:" + brokerUrl);
        }

        @Override
        public void onConnectionLost(String reason) {
            record("lost:" + reason);
        }

        @Override
        public void onSubscribed(String topic, int qos) {
            record("subscribed:" + topic + ":" + qos);
        }

        @Override
        public void onUnsubscribed(String topic) {
            record("unsubscribed:" + topic);
        }

        @Override
        public void onPublished(String topic, int qos, boolean retain, String payload) {
            record("published:" + topic + ":" + qos + ":" + retain + ":" + payload);
        }

        @Override
        public void onFailed(MqttSession.Operation operation, String message) {
            record("failed:" + operation + ":" + message);
        }

        @Override
        public void onMessage(String topic, MqttMessage message) {
            events.add("message:" + topic + ":" + new String(message.getPayload(), StandardCharsets.UTF_8));
        }
    }

    private static long onEdtTimed(Runnable body) throws Exception {
        AtomicLong elapsed = new AtomicLong();
        SwingUtilities.invokeAndWait(() -> {
            long start = System.nanoTime();
            body.run();
            elapsed.set((System.nanoTime() - start) / 1_000_000);
        });
        return elapsed.get();
    }

    private static void drainEdt() throws Exception {
        Thread.sleep(50);
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            SwingUtilities.invokeAndWait(() -> { });
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Timed out waiting for " + what);
    }
}
