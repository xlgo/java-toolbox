package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.domain.SshTunnelConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tunnel bookkeeping of a live session: an edited definition must not interrupt a running
 * forward unless the remote target really changed, and only then is the same local port
 * rebound. Uses the {@link SshSessionInstance.PortForwarder} seam instead of a real SSH server.
 */
class SshSessionTunnelTest {
    @TempDir
    Path temp;

    private SshConfigStore store;
    private SshConnectionConfig config;
    private SshSessionInstance session;
    private FakeForwarder forwarder;

    @BeforeEach
    void setUp() {
        store = new SshConfigStore(temp.resolve("ssh_servers.json").toFile());
        config = new SshConnectionConfig();
        config.setId("srv-1");
        config.setHost("10.0.0.1");
        store.addOrUpdate(config);
        forwarder = new FakeForwarder();
        session = new SshSessionInstance(store.findById("srv-1"));
        session.usePortForwarder(forwarder);
    }

    @AfterEach
    void tearDown() {
        session.close();
    }

    private static SshTunnelConfig definition(String id, String host, int port) {
        SshTunnelConfig tunnel = new SshTunnelConfig();
        tunnel.setId(id);
        tunnel.setName(id);
        tunnel.setRemoteHost(host);
        tunnel.setRemotePort(port);
        return tunnel;
    }

    @Test
    void renamingARunningTunnelLeavesTheForwardAlone() {
        SshTunnelConfig live = definition("t1", "db.internal", 3306);
        session.addTunnel(live);
        assertTrue(session.startTunnel(live));
        int localPort = live.getAssignedLocalPort();

        SshTunnelConfig edited = live.copyDefinition();
        edited.setName("MySQL prod");
        edited.setAutoStart(true);

        assertEquals(SshSessionInstance.TunnelChange.UPDATED, session.updateTunnel(live, edited));
        assertEquals(SshTunnelConfig.Status.RUNNING, live.getStatus());
        assertEquals(localPort, live.getAssignedLocalPort());
        assertEquals("MySQL prod", live.getName());
        assertTrue(live.isAutoStart());
        assertEquals(1, forwarder.bindings.size(), "the forward was not rebound");
    }

    @Test
    void changingTheRemoteTargetRebindsTheSameLocalPort() {
        SshTunnelConfig live = definition("t1", "db.internal", 3306);
        session.addTunnel(live);
        assertTrue(session.startTunnel(live));
        int localPort = live.getAssignedLocalPort();

        SshTunnelConfig edited = live.copyDefinition();
        edited.setRemoteHost("db2.internal");
        edited.setRemotePort(3307);

        assertEquals(SshSessionInstance.TunnelChange.RESTARTED, session.updateTunnel(live, edited));
        assertEquals("db2.internal:3307", forwarder.bindings.get("127.0.0.1:" + localPort));
        assertEquals(localPort, live.getAssignedLocalPort(), "clients keep their local address");
        assertEquals(SshTunnelConfig.Status.RUNNING, live.getStatus());
    }

    @Test
    void editingAStoppedTunnelOnlyUpdatesTheDefinition() {
        SshTunnelConfig live = definition("t1", "db.internal", 3306);
        session.addTunnel(live);

        SshTunnelConfig edited = live.copyDefinition();
        edited.setRemoteHost("db2.internal");

        assertEquals(SshSessionInstance.TunnelChange.UPDATED, session.updateTunnel(live, edited));
        assertEquals("db2.internal", live.getRemoteHost());
        assertTrue(forwarder.bindings.isEmpty(), "nothing was started");
    }

    @Test
    void identicalDefinitionReportsNoChange() {
        SshTunnelConfig live = definition("t1", "db.internal", 3306);
        session.addTunnel(live);
        assertEquals(SshSessionInstance.TunnelChange.UNCHANGED,
                session.updateTunnel(live, live.copyDefinition()));
    }

    @Test
    void removingATunnelUnbindsItAndDropsTheDefinition() {
        SshTunnelConfig live = definition("t1", "db.internal", 3306);
        session.addTunnel(live);
        assertTrue(session.startTunnel(live));

        session.removeTunnel(live);

        assertTrue(forwarder.bindings.isEmpty());
        assertEquals(SshTunnelConfig.Status.STOPPED, live.getStatus());
        assertEquals(0, config.getTunnels().size());
    }

    @Test
    void addingTheSameTunnelTwiceKeepsOneEntry() {
        SshTunnelConfig live = definition("t1", "db.internal", 3306);
        session.addTunnel(live);
        session.addTunnel(live);
        session.addTunnel(live.copyDefinition());
        assertEquals(1, config.getTunnels().size());
        assertSame(live, config.getTunnels().get(0));
    }

    /** A required local port (Kafka broker routes) must fail loudly, never fall back. */
    @Test
    void aTakenRequiredLocalPortFailsInsteadOfMovingTheForward() {
        forwarder.taken.add("127.0.0.1:9092");
        SshTunnelConfig live = definition("t1", "kafka-1.internal", 9092);
        live.setRequiredLocalPort(9092);
        session.addTunnel(live);

        assertFalse(session.startTunnel(live));
        assertEquals(SshTunnelConfig.Status.ERROR, live.getStatus());
        assertTrue(forwarder.bindings.isEmpty());
    }

    @Test
    void aRequiredLocalPortBindsExactlyThatPort() {
        SshTunnelConfig live = definition("t1", "kafka-1.internal", 9092);
        live.setRequiredLocalPort(9092);
        session.addTunnel(live);

        assertTrue(session.startTunnel(live));
        assertEquals(9092, live.getAssignedLocalPort());
        assertEquals("kafka-1.internal:9092", forwarder.bindings.get("127.0.0.1:9092"));
    }

    /** Bridge tunnels may listen on a distinct loopback address. */
    @Test
    void theForwardUsesTheConfiguredBindAddress() {
        SshTunnelConfig live = definition("t1", "kafka-1.internal", 9092);
        live.setBindAddress("127.0.0.2");
        live.setRequiredLocalPort(9092);

        assertTrue(session.startTunnel(live));
        assertEquals("127.0.0.2:9092", forwarder.bindings.keySet().iterator().next());
        assertEquals("127.0.0.2:9092", live.getLocalConnectionString());
    }

    /**
     * The reported defect: an edit dialog replaced the stored object, so a session kept a
     * detached copy and every later tunnel edit went to a configuration nobody persisted.
     */
    @Test
    void editingTheServerKeepsTheSessionOnTheStoredConfiguration() {
        SshTunnelConfig live = definition("t1", "db.internal", 3306);
        session.addTunnel(live);
        assertTrue(session.startTunnel(live));

        SshConnectionConfig edited = session.getConfig().clone();
        edited.setHost("10.0.0.9");
        store.addOrUpdate(edited);

        assertSame(session.getConfig(), store.findById("srv-1"));
        assertEquals("10.0.0.9", session.getConfig().getHost());
        assertEquals(SshTunnelConfig.Status.RUNNING, live.getStatus());
        assertNotEquals(0, live.getAssignedLocalPort());
        assertEquals(1, store.findById("srv-1").getTunnels().size());
    }

    /** Port forwarding of a disconnected session must not allocate anything. */
    @Test
    void aDisconnectedSessionPausesWithoutBindingAPort() {
        forwarder.connected = false;
        SshTunnelConfig live = definition("t1", "db.internal", 3306);
        session.addTunnel(live);

        assertFalse(session.startTunnel(live));
        assertEquals(SshTunnelConfig.Status.PAUSED, live.getStatus());
        assertTrue(forwarder.bindings.isEmpty());
    }

    private static final class FakeForwarder implements SshSessionInstance.PortForwarder {
        private final Map<String, String> bindings = new LinkedHashMap<>();
        private final java.util.Set<String> taken = new java.util.HashSet<>();
        private boolean connected = true;
        private int nextPort = 40000;

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public int bind(String bindAddress, int localPort, String remoteHost, int remotePort)
                throws Exception {
            int port = localPort;
            if (port <= 0) {
                port = nextPort++;
            }
            String key = bindAddress + ":" + port;
            if (taken.contains(key) || bindings.containsKey(key)) {
                throw new java.io.IOException("Address already in use");
            }
            bindings.put(key, remoteHost + ":" + remotePort);
            return port;
        }

        @Override
        public void unbind(String bindAddress, int localPort) {
            bindings.remove(bindAddress + ":" + localPort);
        }
    }
}
