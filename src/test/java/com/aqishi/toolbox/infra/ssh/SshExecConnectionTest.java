package com.aqishi.toolbox.infra.ssh;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.network.ssh.domain.*;
import com.jcraft.jsch.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.concurrent.atomic.*;

@Timeout(20)
class SshExecConnectionTest {
    private static SshHostKeyPrompt accepting(AtomicInteger calls) {
        return new SshHostKeyPrompt() {
            public boolean confirmHostKey(String message) {
                calls.incrementAndGet();
                return true;
            }

            public void showMessage(String message) {}
        };
    }

    @Test
    void closeIsIndependentFromTheTerminalProfileAndItsRunningTunnels() {
        var config = new SshConnectionConfig();
        config.setName("terminal-profile");
        var tunnel = new SshTunnelConfig();
        tunnel.setStatus(SshTunnelConfig.Status.RUNNING);
        tunnel.setAssignedLocalPort(43210);
        config.getTunnels().add(tunnel);
        var connection = new SshExecConnection(config, SshHostKeyPrompt.denyAll());
        assertThrows(IOException.class, connection::openExecChannel);
        connection.close();
        connection.close();
        assertThrows(IOException.class, connection::connect);
        assertThrows(IOException.class, connection::openExecChannel);
        assertSame(tunnel, config.getTunnels().get(0));
        assertEquals(43210, tunnel.getAssignedLocalPort());
        assertEquals(SshTunnelConfig.Status.RUNNING, tunnel.getStatus());
        assertEquals("terminal-profile", config.getName());
    }

    @Test
    void changedHostKeysAndClosedConnectionsNeverReachTheApprovalPrompt() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean(), changed = new AtomicBoolean();
        UserInfo info = SshExecConnection.userInfo(accepting(calls), closed, changed);
        assertTrue(info.promptYesNo("unknown fingerprint"));
        assertEquals(1, calls.get());
        changed.set(true);
        assertFalse(info.promptYesNo("changed fingerprint"));
        assertEquals(1, calls.get());
        changed.set(false);
        closed.set(true);
        assertFalse(info.promptYesNo("cancelled"));
        assertEquals(1, calls.get());
        assertFalse(info.promptPassword("password"));
        assertFalse(info.promptPassphrase("passphrase"));
    }

    @Test
    void approvalArrivingAfterCancellationDoesNotReconnect() {
        AtomicBoolean closed = new AtomicBoolean();
        SshHostKeyPrompt prompt =
                new SshHostKeyPrompt() {
                    public boolean confirmHostKey(String message) {
                        closed.set(true);
                        return true;
                    }

                    public void showMessage(String message) {}
                };
        assertFalse(
                SshExecConnection.userInfo(prompt, closed, new AtomicBoolean())
                        .promptYesNo("fingerprint"));
    }

    @Test
    void hostRepositoryChecksExistingFingerprintsAndRejectsAChangedKey() throws Exception {
        JSch client = new JSch();
        KeyPair first = KeyPair.genKeyPair(client, KeyPair.RSA, 2048),
                second = KeyPair.genKeyPair(client, KeyPair.RSA, 2048);
        try {
            var repository = client.getHostKeyRepository();
            repository.add(new HostKey("fixture", first.getPublicKeyBlob()), null);
            AtomicBoolean changed = new AtomicBoolean();
            var checked = SshExecConnection.checkingRepository(repository, changed);
            assertEquals(HostKeyRepository.OK, checked.check("fixture", first.getPublicKeyBlob()));
            assertFalse(changed.get());
            assertEquals(
                    HostKeyRepository.NOT_INCLUDED,
                    checked.check("unknown", first.getPublicKeyBlob()));
            assertFalse(changed.get());
            assertEquals(
                    HostKeyRepository.CHANGED, checked.check("fixture", second.getPublicKeyBlob()));
            assertTrue(changed.get());
            assertFalse(
                    SshExecConnection.userInfo(
                                    accepting(new AtomicInteger()), new AtomicBoolean(), changed)
                            .promptYesNo("replace key"));
            assertEquals(1, checked.getHostKey().length);
        } finally {
            first.dispose();
            second.dispose();
        }
    }
}
