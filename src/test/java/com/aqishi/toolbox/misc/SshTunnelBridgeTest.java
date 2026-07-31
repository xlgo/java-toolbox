package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.misc.ssh.model.SshConfigStore;
import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.session.SshTunnelBridge;
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
