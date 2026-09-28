package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.domain.SshSecurityUtils;
import com.aqishi.toolbox.feature.network.ssh.domain.SshTunnelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tunnel definitions survive server edits, tunnel edits are persisted and reload with stable IDs. */
class SshConfigStoreTunnelTest {
    @TempDir
    Path temp;

    private File file;
    private SshConfigStore store;
    private SshConnectionConfig stored;

    @BeforeEach
    void setUp() {
        file = temp.resolve("ssh_servers.json").toFile();
        store = new SshConfigStore(file);
        SshConnectionConfig config = new SshConnectionConfig();
        config.setId("srv-1");
        config.setName("jump");
        config.setHost("10.0.0.1");
        config.setEncryptedPassword(SshSecurityUtils.encrypt("pw"));
        config.getTunnels().add(tunnel("t-mysql", "db.internal", 3306));
        config.getTunnels().add(tunnel("t-web", "web.internal", 8080));
        store.addOrUpdate(config);
        stored = store.findById("srv-1");
    }

    private static SshTunnelConfig tunnel(String id, String host, int port) {
        SshTunnelConfig tunnel = new SshTunnelConfig();
        tunnel.setId(id);
        tunnel.setName(id);
        tunnel.setRemoteHost(host);
        tunnel.setRemotePort(port);
        return tunnel;
    }

    private SshConfigStore reload() {
        return new SshConfigStore(file);
    }

    /** What SshClientPanel does when the user edits a server: dialog clone, then addOrUpdate. */
    private void editServerLikeTheDialog(String newHost) {
        SshConnectionConfig edited = stored.clone();
        edited.setHost(newHost);
        edited.setName("jump-renamed");
        store.addOrUpdate(edited);
    }

    @Test
    void editingConnectionFieldsKeepsTunnelsAndTheCanonicalInstance() {
        editServerLikeTheDialog("10.0.0.2");

        assertSame(stored, store.findById("srv-1"), "open sessions keep sharing the stored object");
        assertEquals("10.0.0.2", stored.getHost());
        assertEquals("jump-renamed", stored.getName());
        assertEquals(List.of("t-mysql", "t-web"), ids(stored.getTunnels()));

        SshConnectionConfig reloaded = reload().findById("srv-1");
        assertEquals("10.0.0.2", reloaded.getHost());
        assertEquals(List.of("t-mysql", "t-web"), ids(reloaded.getTunnels()));
        assertEquals("pw", reloaded.resolvedPassword());
    }

    @Test
    void tunnelEditAfterServerEditIsPersisted() {
        // A session tab opened before the edit keeps the object it was created with.
        SshConnectionConfig sessionConfig = stored;
        editServerLikeTheDialog("10.0.0.2");

        sessionConfig.getTunnels().add(tunnel("t-redis", "cache.internal", 6379));
        sessionConfig.getTunnels().get(0).setRemotePort(3307);
        store.saveTunnels(sessionConfig);

        SshConnectionConfig reloaded = reload().findById("srv-1");
        assertEquals(List.of("t-mysql", "t-web", "t-redis"), ids(reloaded.getTunnels()));
        assertEquals(3307, reloaded.getTunnels().get(0).getRemotePort());
        assertEquals("10.0.0.2", reloaded.getHost(), "tunnel save must not revert the server edit");
    }

    @Test
    void tunnelsSavedFromADetachedCopyReachTheStore() {
        SshConnectionConfig detached = stored.clone();
        detached.getTunnels().remove(1);
        detached.getTunnels().get(0).setName("MySQL");
        store.saveTunnels(detached);

        assertEquals(List.of("t-mysql"), ids(stored.getTunnels()));
        SshConnectionConfig reloaded = reload().findById("srv-1");
        assertEquals(List.of("t-mysql"), ids(reloaded.getTunnels()));
        assertEquals("MySQL", reloaded.getTunnels().get(0).getName());
    }

    @Test
    void savingTunnelsOfADeletedServerDoesNotResurrectIt() {
        SshConnectionConfig sessionConfig = stored;
        store.delete("srv-1");
        store.saveTunnels(sessionConfig);
        assertEquals(0, reload().getAll().size());
    }

    @Test
    void runtimeStateIsNotCopiedIntoTheStoreByAServerEdit() {
        SshTunnelConfig live = stored.getTunnels().get(0);
        live.setStatus(SshTunnelConfig.Status.RUNNING);
        live.setAssignedLocalPort(45000);

        editServerLikeTheDialog("10.0.0.3");

        assertSame(live, stored.getTunnels().get(0));
        assertEquals(SshTunnelConfig.Status.RUNNING, live.getStatus());
        assertEquals(45000, live.getAssignedLocalPort());
    }

    @Test
    void duplicateGetsFreshIdsAndNoRuntimeState() {
        SshTunnelConfig live = stored.getTunnels().get(0);
        live.setStatus(SshTunnelConfig.Status.RUNNING);
        live.setAssignedLocalPort(45000);

        SshConnectionConfig copy = stored.duplicate();
        store.addOrUpdate(copy);

        assertNotEquals("srv-1", copy.getId());
        assertEquals(2, copy.getTunnels().size());
        for (SshTunnelConfig tunnel : copy.getTunnels()) {
            assertNotNull(tunnel.getId());
            assertTrue(!tunnel.getId().equals("t-mysql") && !tunnel.getId().equals("t-web"));
            assertEquals(SshTunnelConfig.Status.STOPPED, tunnel.getStatus());
            assertEquals(0, tunnel.getAssignedLocalPort());
        }
        assertEquals("db.internal", copy.getTunnels().get(0).getRemoteHost());
        assertEquals(List.of("t-mysql", "t-web"), ids(reload().findById("srv-1").getTunnels()));
        assertEquals(2, reload().getAll().size());
    }

    private static List<String> ids(List<SshTunnelConfig> tunnels) {
        return tunnels.stream().map(SshTunnelConfig::getId).toList();
    }
}
