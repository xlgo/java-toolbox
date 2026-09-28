package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.FakeTicker;
import com.aqishi.toolbox.feature.network.application.LoopbackWebSocketServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

public class WebSocketClientPanelTest {

    private LoopbackWebSocketServer server;
    private WebSocketClientPanel panel;

    @AfterEach
    void tearDown() {
        if (panel != null) {
            panel.closeResources();
        }
        if (server != null) {
            server.stopQuietly();
        }
    }

    @Test
    public void testWebSocketClientPanelInstantiation() {
        WebSocketClientPanel panel = new WebSocketClientPanel();
        assertEquals("network", panel.getGroup());
        assertEquals("websocket.client", panel.getName());
        assertNotNull(panel.getView());
        panel.closeResources();
        panel.closeResources();
        new WebSocketClientPanel().closeResources(); // never built
    }

    @Test
    void heartbeatSurvivesManualReconnectExactlyOnce() throws Exception {
        server = LoopbackWebSocketServer.startNew();
        FakeTicker ticker = new FakeTicker();
        panel = new WebSocketClientPanel(ticker);
        SwingUtilities.invokeAndWait(() -> {
            panel.getView();
            panel.setUrlForTest(server.uri().toString());
            panel.setHeartbeatForTest(true, 1, "hb");
        });
        assertEquals(0, ticker.active(), "heartbeat waits for the connection");

        SwingUtilities.invokeAndWait(panel::clickConnectForTest);
        await("connected", () -> "CONNECTED".equals(panel.stateForTest()));
        assertEquals(1, ticker.active());
        ticker.tick();
        awaitPlain("heartbeat on server", () -> server.count("hb") == 1);

        SwingUtilities.invokeAndWait(panel::clickDisconnectForTest);
        assertEquals(0, ticker.active(), "disconnect stops the heartbeat");
        SwingUtilities.invokeAndWait(() -> assertEquals("DISCONNECTED", panel.stateForTest()));

        for (int i = 2; i <= 4; i++) {
            SwingUtilities.invokeAndWait(panel::clickConnectForTest);
            await("reconnected", () -> "CONNECTED".equals(panel.stateForTest()));
            assertEquals(1, ticker.active(), "one heartbeat after reconnect");
            assertEquals(i, panel.sessionForTest().heartbeat().startCount(), "restarted exactly once");
            SwingUtilities.invokeAndWait(panel::clickDisconnectForTest);
        }
        SwingUtilities.invokeAndWait(panel::clickConnectForTest);
        await("connected again", () -> "CONNECTED".equals(panel.stateForTest()));
        ticker.tick();
        awaitPlain("heartbeat after reconnect", () -> server.count("hb") == 2);
        // A late onClose of an earlier socket must not flip the panel back.
        Thread.sleep(100);
        SwingUtilities.invokeAndWait(() -> assertEquals("CONNECTED", panel.stateForTest()));

        await("log flushed", () -> {
            panel.logForTest().flush();
            return panel.logForTest().shownLines() > 0;
        });
    }

    @Test
    void realHeartbeatTimerFiresAfterReconnect() throws Exception {
        server = LoopbackWebSocketServer.startNew();
        panel = new WebSocketClientPanel();
        SwingUtilities.invokeAndWait(() -> {
            panel.getView();
            panel.setUrlForTest(server.uri().toString());
            panel.setHeartbeatForTest(true, 1, "beat");
            panel.clickConnectForTest();
        });
        await("connected", () -> "CONNECTED".equals(panel.stateForTest()));
        SwingUtilities.invokeAndWait(panel::clickDisconnectForTest);
        SwingUtilities.invokeAndWait(panel::clickConnectForTest);
        await("reconnected", () -> "CONNECTED".equals(panel.stateForTest()));
        awaitPlain("heartbeat after reconnect", () -> server.count("beat") >= 1);
        assertTrue(panel.sessionForTest().heartbeat().isRunning());
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

    private static void awaitPlain(String what, BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Timed out waiting for " + what);
    }
}
