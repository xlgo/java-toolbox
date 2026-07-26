package com.aqishi.toolbox.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyVaultMigratorTest {
    @TempDir
    Path temp;

    @Test
    void probesAllLegacySourceCombinations() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            LegacyVaultMigrator migrator = migrator(support);
            assertEquals(LegacyVaultMigrator.MigrationMode.NONE, migrator.probe());

            writeLegacyPasswords(support.paths().getLegacyPasswordFile(), "master");
            assertEquals(LegacyVaultMigrator.MigrationMode.PASSWORD_ONLY, migrator.probe());

            Files.delete(support.paths().getLegacyPasswordFile());
            writeLegacyTotp(support.paths().getLegacyConfigFile());
            assertEquals(LegacyVaultMigrator.MigrationMode.TOTP_ONLY, migrator.probe());

            writeLegacyPasswords(support.paths().getLegacyPasswordFile(), "master");
            assertEquals(LegacyVaultMigrator.MigrationMode.BOTH, migrator.probe());
        }
    }

    @Test
    void migratesBothSourcesAndRemovesPlaintextOnlyAfterVerification() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            writeLegacyPasswords(support.paths().getLegacyPasswordFile(), "old-master");
            writeLegacyTotp(support.paths().getLegacyConfigFile());

            LegacyVaultMigrator.MigrationResult result = migrator(support)
                    .migrate("old-master".toCharArray());
            result.getOpenedVault().close();

            VaultData data = support.repository().open("old-master".toCharArray()).getData();
            assertEquals(1, data.copyPasswordAccounts().size());
            assertEquals("GitHub", data.copyPasswordAccounts().get(0).getName());
            assertEquals(1, data.copyTotpAccounts().size());
            assertEquals("JBSWY3DPEHPK3PXP", data.copyTotpAccounts().get(0).getSecret());
            assertFalse(Files.exists(support.paths().getLegacyPasswordFile()));
            assertFalse(Files.exists(support.paths().getLegacyConfigFile()));
            String sanitized = new String(
                    Files.readAllBytes(support.paths().getConfigFile()),
                    StandardCharsets.ISO_8859_1);
            assertFalse(sanitized.contains("totp.accounts"));
            assertFalse(sanitized.contains("JBSWY3DPEHPK3PXP"));
            assertTrue(sanitized.contains("theme"));
            try (java.util.stream.Stream<Path> backups =
                         Files.list(support.paths().getBackupDirectory())) {
                assertEquals(2, backups.filter(Files::isRegularFile).count());
            }
        }
    }

    @Test
    void wrongPasswordPreservesEveryLegacySource() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            writeLegacyPasswords(support.paths().getLegacyPasswordFile(), "right");
            writeLegacyTotp(support.paths().getLegacyConfigFile());

            assertThrows(VaultException.class,
                    () -> migrator(support).migrate("wrong".toCharArray()));

            assertTrue(Files.exists(support.paths().getLegacyPasswordFile()));
            assertTrue(Files.exists(support.paths().getLegacyConfigFile()));
            assertFalse(Files.exists(support.paths().getVaultFile()));
        }
    }

    @Test
    void createsAnEmptyVaultWhenNoLegacyDataExists() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            LegacyVaultMigrator.MigrationResult result = migrator(support)
                    .migrate("master".toCharArray());

            assertEquals(LegacyVaultMigrator.MigrationMode.NONE, result.getMode());
            assertTrue(result.getOpenedVault().getData().copyPasswordAccounts().isEmpty());
            assertTrue(result.getOpenedVault().getData().copyTotpAccounts().isEmpty());
        }
    }

    @Test
    void survivesRestartWithoutPlaintextSecrets() throws Exception {
        try (VaultTestSupport first = new VaultTestSupport(temp)) {
            writeLegacyPasswords(first.paths().getLegacyPasswordFile(), "old-master");
            writeLegacyTotp(first.paths().getLegacyConfigFile());
            LegacyVaultMigrator.MigrationResult result = migrator(first)
                    .migrate("old-master".toCharArray());
            result.getOpenedVault().close();
        }

        try (VaultTestSupport second = new VaultTestSupport(temp)) {
            VaultRepository.OpenedVault reopened = second.repository()
                    .open("old-master".toCharArray());
            assertEquals("pw", reopened.getData().copyPasswordAccounts()
                    .get(0).getPassword());
            assertEquals("JBSWY3DPEHPK3PXP", reopened.getData().copyTotpAccounts()
                    .get(0).getSecret());
            reopened.close();
        }

        try (java.util.stream.Stream<Path> files = Files.walk(temp)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                if (file.getFileName().toString().endsWith(".enc")) continue;
                String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                assertFalse(content.contains("JBSWY3DPEHPK3PXP"));
                assertFalse(content.contains("old-master"));
            }
        }
    }

    @Test
    void cleanupRefusesToDeleteLegacyRecordsMissingFromExistingVault() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            support.repository().create(new VaultData(), "master".toCharArray()).close();
            writeLegacyPasswords(support.paths().getLegacyPasswordFile(), "master");

            VaultException error = assertThrows(VaultException.class,
                    () -> migrator(support).migrate("master".toCharArray()));

            assertEquals(VaultErrorCode.MIGRATION_FAILED, error.getCode());
            assertTrue(Files.exists(support.paths().getLegacyPasswordFile()));
            assertTrue(support.repository().open("master".toCharArray())
                    .getData().copyPasswordAccounts().isEmpty());
        }
    }

    private LegacyVaultMigrator migrator(VaultTestSupport support) {
        return new LegacyVaultMigrator(
                support.paths(), support.repository(), new AtomicFiles(), new VaultCrypto());
    }

    private static void writeLegacyTotp(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        String json = "[{\"id\":\"1\",\"label\":\"Mail\","
                + "\"secret\":\"JBSWY3DPEHPK3PXP\",\"issuer\":\"Example\","
                + "\"algorithm\":\"SHA1\",\"digits\":6,\"period\":30,"
                + "\"showDirectly\":true}]";
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("theme", "Arc");
        properties.setProperty("totp.accounts", json);
        try (java.io.OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "legacy");
        }
    }

    private static void writeLegacyPasswords(Path file, String password) throws Exception {
        Files.createDirectories(file.getParent());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("magic", "TOOLBOX_PWD_MGR");
        ArrayNode accounts = root.putArray("accounts");
        ObjectNode account = accounts.addObject();
        account.put("name", "GitHub");
        account.put("username", "dev");
        account.put("password", "pw");
        account.put("url", "https://github.com");

        byte[] key = Arrays.copyOf(
                MessageDigest.getInstance("SHA-256")
                        .digest(password.getBytes(StandardCharsets.UTF_8)), 16);
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(mapper.writeValueAsBytes(root));
        byte[] encoded = Arrays.copyOf(iv, iv.length + encrypted.length);
        System.arraycopy(encrypted, 0, encoded, iv.length, encrypted.length);
        Files.write(file, encoded);
        VaultCrypto.wipe(key);
        VaultCrypto.wipe(encrypted);
        VaultCrypto.wipe(encoded);
    }
}
