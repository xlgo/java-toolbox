package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.BoundedLogBuffer;
import com.aqishi.toolbox.feature.network.application.FakeMqttClient;
import com.aqishi.toolbox.feature.network.application.MqttLogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MqttClientPanelTest {

    private final List<FakeMqttClient> clients = new CopyOnWriteArrayList<>();
    private MqttClientPanel panel;

    @AfterEach
    void tearDown() {
        if (panel != null) {
            panel.closeResources();
        }
    }

    private MqttClientPanel newPanel() throws Exception {
        panel = new MqttClientPanel((uri, id) -> {
            FakeMqttClient client = new FakeMqttClient();
            clients.add(client);
            return client;
        });
        SwingUtilities.invokeAndWait(() -> assertNotNull(panel.getView()));
        return panel;
    }

    @Test
    void buildsViewAndClosesIdempotently() throws Exception {
        MqttClientPanel p = new MqttClientPanel();
        assertEquals("network", p.getGroup());
        assertEquals("mqtt.client", p.getName());
        p.closeResources(); // never built: still safe
        p.closeResources();

        newPanel();
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("DISCONNECTED", panel.stateForTest());
            assertFalse(panel.publishEnabledForTest());
            assertTrue(panel.logTimerRunningForTest());
        });
        panel.closeResources();
        panel.closeResources();
        SwingUtilities.invokeAndWait(() -> assertFalse(panel.logTimerRunningForTest()));
        await("io thread released", () -> panel.sessionForTest().isTerminated());
    }

    @Test
    void connectAndPublishNeverBlockTheEdtAndLatePublishIsIgnored() throws Exception {
        newPanel();
        long elapsed = onEdtTimed(panel::clickConnectForTest);
        assertTrue(elapsed < 1000, "connect blocked the EDT for " + elapsed + " ms");
        await("connected", () -> "CONNECTED".equals(panel.stateForTest()));
        FakeMqttClient client = clients.get(0);
        assertTrue(client.connectWasOn("mqtt-io"));

        client.publishGate = new CountDownLatch(1); // slow broker
        elapsed = onEdtTimed(panel::clickPublishForTest);
        assertTrue(elapsed < 1000, "publish blocked the EDT for " + elapsed + " ms");
        assertTrue(client.publishCalled.await(5, TimeUnit.SECONDS));
        assertTrue(client.publishThread.get().startsWith("mqtt-io"));
        SwingUtilities.invokeAndWait(() -> assertFalse(panel.publishEnabledForTest(), "no double publish"));

        SwingUtilities.invokeAndWait(panel::clickDisconnectForTest);
        client.publishGate.countDown();
        assertTrue(client.closed.await(5, TimeUnit.SECONDS));
        Thread.sleep(50);
        SwingUtilities.invokeAndWait(() -> {
            panel.flushLogForTest();
            assertEquals("DISCONNECTED", panel.stateForTest());
            assertFalse(panel.publishEnabledForTest());
            MqttMessageTableModel model = panel.logModelForTest();
            for (int i = 0; i < model.getRowCount(); i++) {
                assertFalse(model.entryAt(i).direction() == MqttLogEntry.Direction.SENT,
                        "publish that completed after disconnect must not be logged");
            }
        });
    }

    @Test
    void publishAfterConnectIsLoggedOnce() throws Exception {
        newPanel();
        SwingUtilities.invokeAndWait(panel::clickConnectForTest);
        await("connected", () -> "CONNECTED".equals(panel.stateForTest()));
        SwingUtilities.invokeAndWait(panel::clickPublishForTest);
        await("sent row", () -> {
            panel.flushLogForTest();
            MqttMessageTableModel model = panel.logModelForTest();
            for (int i = 0; i < model.getRowCount(); i++) {
                if (model.entryAt(i).direction() == MqttLogEntry.Direction.SENT) {
                    return true;
                }
            }
            return false;
        });
        await("publish re-enabled", panel::publishEnabledForTest);
    }

    @Test
    void messageFloodStaysBoundedAndReportsTrimming() throws Exception {
        newPanel();
        int total = 12_000;
        Thread producer = new Thread(() -> {
            for (int i = 0; i < total; i++) {
                panel.offerLogForTest(MqttLogEntry.of(MqttLogEntry.Direction.RECEIVED, "t", 0, false, "m" + i));
            }
        });
        producer.start();
        producer.join(10_000);

        AtomicBoolean drained = new AtomicBoolean();
        long end = System.currentTimeMillis() + 10_000;
        while (!drained.get() && System.currentTimeMillis() < end) {
            SwingUtilities.invokeAndWait(() -> {
                panel.flushLogForTest();
                assertTrue(panel.logRowsForTest() <= BoundedLogBuffer.DEFAULT_MAX_RETAINED);
                MqttMessageTableModel model = panel.logModelForTest();
                int rows = model.getRowCount();
                drained.set(rows > 0 && ("m" + (total - 1)).equals(model.entryAt(rows - 1).payload()));
            });
        }
        assertTrue(drained.get(), "all batches flushed");
        SwingUtilities.invokeAndWait(() -> {
            assertTrue(panel.logRowsForTest() <= BoundedLogBuffer.DEFAULT_MAX_RETAINED);
            assertEquals(total - panel.logRowsForTest(), panel.logTrimmedForTest());
            String stats = panel.logStatsForTest();
            // Resource text is supplied by the main session; accept the key or the rendered numbers.
            assertTrue(stats.contains("tool.mqtt.log.trimmed") || stats.matches(".*\\d.*"), stats);
        });
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

    private static void await(String what, BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        AtomicBoolean ok = new AtomicBoolean();
        while (System.currentTimeMillis() < end) {
            SwingUtilities.invokeAndWait(() -> ok.set(condition.getAsBoolean()));
            if (ok.get()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Timed out waiting for " + what);
    }
}
