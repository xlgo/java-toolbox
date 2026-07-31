package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.misc.ssh.model.SshConfigStore;
import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.model.SshSecurityUtils;
import com.aqishi.toolbox.misc.ssh.model.SshTunnelConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SshConfigStoreTest {

    @Test
    public void testSecurityUtilsEncryptDecrypt() {
        String originalPassword = "MySecretPassWord123!@#";
        String encrypted = SshSecurityUtils.encrypt(originalPassword);
        Assertions.assertNotEquals(originalPassword, encrypted);
        Assertions.assertNotEquals(encrypted, SshSecurityUtils.encrypt(originalPassword));

        String decrypted = SshSecurityUtils.decrypt(encrypted);
        Assertions.assertEquals(originalPassword, decrypted);

        String[] encryptedParts = encrypted.split("\\.", -1);
        byte[] encryptedPayload = java.util.Base64.getUrlDecoder().decode(encryptedParts[3]);
        encryptedPayload[encryptedPayload.length - 1] ^= 1;
        encryptedParts[3] = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(encryptedPayload);
        String tampered = String.join(".", encryptedParts);
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> SshSecurityUtils.decrypt(tampered));
    }

    @Test
    public void testSensitiveFieldsAreNotSerializedAsPlaintext() throws Exception {
        SshConnectionConfig config = new SshConnectionConfig();
        config.setKeyContent("-----BEGIN PRIVATE KEY-----\nprivate-secret\n-----END PRIVATE KEY-----");
        config.setEncryptedPassword(SshSecurityUtils.encrypt("password-secret"));
        config.setEncryptedPassphrase(SshSecurityUtils.encrypt("passphrase-secret"));
        SshTunnelConfig tunnel = new SshTunnelConfig();
        tunnel.setStatus(SshTunnelConfig.Status.RUNNING);
        tunnel.setAssignedLocalPort(45678);
        config.getTunnels().add(tunnel);

        String json = new ObjectMapper().writeValueAsString(config);
        Assertions.assertFalse(json.contains("private-secret"));
        Assertions.assertFalse(json.contains("password-secret"));
        Assertions.assertFalse(json.contains("passphrase-secret"));
        Assertions.assertTrue(json.contains("encryptedKeyContent"));
        Assertions.assertFalse(json.contains("45678"));
    }

    @Test
    public void testLegacyKeyContentIsMigratedBeforeSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String legacyKey = "-----BEGIN PRIVATE KEY-----\nlegacy-key\n-----END PRIVATE KEY-----";
        String json = mapper.createObjectNode()
                .put("keyContent", legacyKey)
                .put("encryptedPassword", "legacy-password")
                .toString();

        SshConnectionConfig config = mapper.readValue(json, SshConnectionConfig.class);
        Assertions.assertEquals(legacyKey, config.getKeyContent());

        config.normalizeSensitiveValues();
        String migrated = mapper.writeValueAsString(config);
        Assertions.assertFalse(migrated.contains("legacy-key"));
        Assertions.assertFalse(migrated.contains("legacy-password"));
        Assertions.assertTrue(SshSecurityUtils.isEncrypted(config.getEncryptedKeyContent()));
        Assertions.assertEquals(legacyKey, config.getKeyContent());
        Assertions.assertEquals("legacy-password",
                SshSecurityUtils.decrypt(config.getEncryptedPassword()));
    }

    @Test
    public void testBrowserUrlSupportsExplicitHttpsAndPath() {
        SshTunnelConfig tunnel = new SshTunnelConfig();
        tunnel.setAssignedLocalPort(45678);
        tunnel.setBrowserScheme(SshTunnelConfig.BrowserScheme.HTTPS);
        tunnel.setBrowserPath("admin/health");
        Assertions.assertEquals("https://127.0.0.1:45678/admin/health", tunnel.getBrowserUrl());
    }

    @Test
    public void testConfigStoreCrud() {
        SshConfigStore store = SshConfigStore.getInstance();

        SshConnectionConfig config = new SshConnectionConfig();
        config.setName("Test-Linux-Server");
        config.setGroup("测试分组");
        config.setHost("192.168.1.100");
        config.setPort(22);
        config.setUsername("root");
        config.setAuthType(SshConnectionConfig.AuthType.PASSWORD);
        config.setEncryptedPassword(SshSecurityUtils.encrypt("123456"));

        store.addOrUpdate(config);

        SshConnectionConfig fetched = store.findById(config.getId());
        Assertions.assertNotNull(fetched);
        Assertions.assertEquals("Test-Linux-Server", fetched.getName());
        Assertions.assertEquals("192.168.1.100", fetched.getHost());
        Assertions.assertEquals("123456", SshSecurityUtils.decrypt(fetched.getEncryptedPassword()));

        // Delete test
        store.delete(config.getId());
        Assertions.assertNull(store.findById(config.getId()));
    }
}
