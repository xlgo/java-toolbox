package com.aqishi.toolbox.feature.network.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WebSocketSessionTest {

    private LoopbackWebSocketServer server;
    private FakeTicker ticker;
    private WebSocketSession session;
    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = LoopbackWebSocketServer.startNew();
        ticker = new FakeTicker();
        session = new WebSocketSession(new Recorder(), SwingUtilities::invokeLater, ticker);
    }

    @AfterEach
    void tearDown() {
        session.close();
        server.stopQuietly();
    }

    private long count(String prefix) {
        return events.stream().filter(e -> e.startsWith(prefix)).count();
    }

    @Test
    void heartbeatStartsOnConnectStopsOnDisconnectAndRestartsOnceOnReconnect() throws Exception {
        session.configureHeartbeat(true, 1000, "hb");
        assertFalse(session.heartbeat().isRunning(), "no heartbeat before connecting");

        session.connect(server.uri(), Map.of());
        await("open", () -> count("open") == 1);
        assertTrue(session.heartbeat().isRunning());
        assertEquals(1, session.heartbeat().startCount());
        assertEquals(1, ticker.active());

        ticker.tick();
        await("heartbeat reaches server", () -> server.count("hb") == 1);
        await("heartbeat logged", () -> count("heartbeat:hb") == 1);

        session.disconnect();
        assertFalse(session.heartbeat().isRunning(), "disconnect stops the heartbeat at once");
        assertEquals(0, ticker.active());
        await("server sees close", () -> server.openConnections() == 0);

        session.connect(server.uri(), Map.of());
        await("reopen", () -> count("open") == 2);
        assertTrue(session.heartbeat().isRunning(), "heartbeat resumes after a reconnect");
        assertEquals(2, session.heartbeat().startCount(), "restarted exactly once");
        assertEquals(1, ticker.active());

        ticker.tick();
        await("heartbeat after reconnect", () -> server.count("hb") == 2);
        assertEquals(0, count("close"), "late close of the replaced socket is ignored: " + events);
    }

    @Test
    void repeatedReconnectsNeverLeaveDuplicateTimers() throws Exception {
        session.configureHeartbeat(true, 1000, "hb");
        for (int i = 1; i <= 5; i++) {
            session.connect(server.uri(), Map.of());
            final int expected = i;
            await("open #" + i, () -> count("open") == expected);
            assertEquals(1, ticker.active(), "one timer after reconnect #" + i);
        }
        // Connect again without waiting: a burst of reconnects still ends with one timer.
        session.connect(server.uri(), Map.of());
        session.connect(server.uri(), Map.of());
        await("burst open", () -> count("open") >= 6 && session.isOpen());
        await("old sockets closed", () -> server.openConnections() == 1);
        assertEquals(1, ticker.active());

        ticker.tick();
        await("single beat", () -> server.count("hb") == 1);
        Thread.sleep(100);
        assertEquals(1, server.count("hb"), "only one connection sends heartbeats");
    }

    @Test
    void remoteCloseStopsHeartbeatAndIsReported() throws Exception {
        session.configureHeartbeat(true, 1000, "hb");
        session.connect(server.uri(), Map.of());
        await("open", () -> count("open") == 1);
        await("server has connection", () -> server.openConnections() == 1);

        server.getConnections().forEach(c -> c.close(1001, "bye"));
        await("close event", () -> count("close:1001") == 1);
        assertFalse(session.heartbeat().isRunning());
        assertEquals(0, ticker.active());
    }

    @Test
    void enablingHeartbeatWhileConnectedStartsItAndSendIsOffCallerThread() throws Exception {
        session.connect(server.uri(), Map.of());
        await("open", () -> count("open") == 1);
        assertFalse(session.heartbeat().isRunning(), "disabled heartbeat does not run");

        session.configureHeartbeat(true, 500, "ping");
        assertTrue(session.heartbeat().isRunning());
        assertEquals(500, ticker.lastPeriod);

        SwingUtilities.invokeAndWait(() -> session.send("hello"));
        await("echo", () -> events.contains("message:echo:hello"));
        assertTrue(events.contains("sent:hello"));
    }

    @Test
    void failedConnectReportsCloseAndCloseReleasesThreads() throws Exception {
        int port = server.getPort();
        server.stopQuietly();
        session.configureHeartbeat(true, 1000, "hb");
        session.connect(java.net.URI.create("ws://127.0.0.1:" + port + "/ws"), Map.of());
        await("close after refused connect", () -> count("close") == 1);
        assertFalse(session.heartbeat().isRunning());

        session.close();
        session.close();
        assertTrue(ticker.shutdown);
        await("io terminated", session::isTerminated);
    }

    private final class Recorder implements WebSocketSession.Listener {
        @Override
        public void onOpen(int httpStatus) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            events.add("open:" + httpStatus);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            events.add("close:" + code);
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
        }

        @Override
        public void onMessage(String text) {
            events.add("message:" + text);
        }

        @Override
        public void onSent(String text) {
            events.add("sent:" + text);
        }

        @Override
        public void onHeartbeat(String payload) {
            events.add("heartbeat:" + payload);
        }
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
