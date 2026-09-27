package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.infra.SshConfigStore;
import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.domain.SshHostKeyPrompt;
import com.aqishi.toolbox.feature.network.ssh.infra.SshTunnelBridge;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SshTunnelBridgeTest {

    @Test
    public void testBridgeWithInvalidConfig() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            SshTunnelBridge.bridge(null, "127.0.0.1", 3306);
        });

        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            SshTunnelBridge.bridge("non_exist_id", "127.0.0.1", 3306);
        });
    }

    /** 回归：隧道会话曾固定用 denyAll，只走隧道的用户首次连接新主机必然失败。 */
    @Test
    public void hostKeyPromptIsInjectableAndDeniesByDefault() {
        try {
            SshTunnelBridge.setHostKeyPrompt(null);
            Assertions.assertFalse(SshTunnelBridge.hostKeyPrompt().confirmHostKey("unknown host"),
                    "without a UI nobody can confirm, so unknown hosts must be rejected");

            SshHostKeyPrompt accepting = new SshHostKeyPrompt() {
                @Override
                public boolean confirmHostKey(String message) {
                    return true;
                }

                @Override
                public void showMessage(String message) {
                }
            };
            SshTunnelBridge.setHostKeyPrompt(accepting);
            Assertions.assertSame(accepting, SshTunnelBridge.hostKeyPrompt());
        } finally {
            SshTunnelBridge.setHostKeyPrompt(null);
        }
    }

    @Test
    public void testConfigStoreIntegration() {
        SshConnectionConfig config = new SshConnectionConfig();
        config.setName("Tunnel-Bridge-Test");
        config.setHost("127.0.0.1");

        SshConfigStore.getInstance().addOrUpdate(config);

        SshConnectionConfig fetched = SshConfigStore.getInstance().findById(config.getId());
        Assertions.assertNotNull(fetched);
        Assertions.assertEquals("Tunnel-Bridge-Test", fetched.getName());

        SshConfigStore.getInstance().delete(config.getId());
    }
}
