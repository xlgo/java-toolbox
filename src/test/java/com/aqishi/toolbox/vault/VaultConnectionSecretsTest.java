package com.aqishi.toolbox.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Schema-2 connection secret section: data model, repository round trip, service API. */
class VaultConnectionSecretsTest {
    @TempDir
    Path temp;

    @Test
    void copyIsDeepAndValidateChecksConnectionSecrets() throws Exception {
        VaultData data = new VaultData();
        Map<String, String> input = new HashMap<>();
        input.put("db/1", "{\"password\":\"p\"}");
        data.setConnectionSecrets(input);
        input.put("db/2", "later");

        VaultData copy = data.copy();
        Map<String, String> changed = copy.copyConnectionSecrets();
        changed.put("db/3", "x");
        copy.setConnectionSecrets(changed);

        assertEquals(Collections.singleton("db/1"), data.copyConnectionSecrets().keySet());
        assertEquals(2, copy.copyConnectionSecrets().size());
        assertEquals("{\"password\":\"p\"}", data.connectionSecret("db/1"));
        data.validate();

        VaultData nullValue = new VaultData();
        nullValue.setConnectionSecrets(Collections.singletonMap("db/1", null));
        assertCode(VaultErrorCode.INVALID_ENVELOPE, nullValue);

        VaultData emptyKey = new VaultData();
        emptyKey.setConnectionSecrets(Collections.singletonMap("", "v"));
        assertCode(VaultErrorCode.INVALID_ENVELOPE, emptyKey);

        char[] tooLongKey = new char[VaultData.MAX_TEXT_LENGTH + 1];
        Arrays.fill(tooLongKey, 'k');
        VaultData longKey = new VaultData();
        longKey.setConnectionSecrets(Collections.singletonMap(new String(tooLongKey), "v"));
        assertCode(VaultErrorCode.INVALID_ENVELOPE, longKey);

        data.setConnectionSecrets(null);
        assertTrue(data.copyConnectionSecrets().isEmpty());
    }

    @Test
    void upgradeTurnsSchemaOneIntoCurrentAndLeavesOthersAlone() {
        VaultData legacy = new VaultData();
        legacy.setSchemaVersion(1);
        legacy.upgradeLegacySchema();
        assertEquals(VaultData.SCHEMA_VERSION, legacy.getSchemaVersion());
        assertTrue(legacy.copyConnectionSecrets().isEmpty());

        VaultData future = new VaultData();
        future.setSchemaVersion(VaultData.SCHEMA_VERSION + 1);
        future.upgradeLegacySchema();
        assertEquals(VaultData.SCHEMA_VERSION + 1, future.getSchemaVersion());
        assertCode(VaultErrorCode.UNSUPPORTED_FORMAT, future);

        VaultData broken = new VaultData();
        broken.setSchemaVersion(0);
        broken.upgradeLegacySchema();
        assertCode(VaultErrorCode.INVALID_ENVELOPE, broken);
    }

    @Test
    void repositoryRoundTripsConnectionSecrets() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultData data = support.sampleData();
            data.setConnectionSecrets(Collections.singletonMap("redis/abc", "{\"password\":\"r\"}"));
            support.repository().create(data, "master".toCharArray()).close();

            try (VaultRepository.OpenedVault opened =
                         support.repository().open("master".toCharArray())) {
                VaultData loaded = opened.getData();
                assertEquals("{\"password\":\"r\"}", loaded.connectionSecret("redis/abc"));
                assertEquals(1, loaded.copyPasswordAccounts().size());
            }
            byte[] file = Files.readAllBytes(support.paths().getVaultFile());
            assertFalse(new String(file, StandardCharsets.UTF_8).contains("redis/abc"),
                    "connection secret keys must be encrypted with the payload");
        }
    }

    @Test
    void schemaOneVaultOpensWithEmptySectionAndIsRewrittenAsCurrentSchema() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            writeRawPayload(support, "master",
                    "{\"schemaVersion\":1,\"passwordAccounts\":[{\"name\":\"GitHub\","
                            + "\"username\":\"dev\",\"password\":\"pw\",\"url\":\"u\"}],"
                            + "\"totpAccounts\":[]}");

            VaultRepository.OpenedVault opened = support.repository().open("master".toCharArray());
            VaultData loaded = opened.getData();
            assertEquals(VaultData.SCHEMA_VERSION, loaded.getSchemaVersion());
            assertTrue(loaded.copyConnectionSecrets().isEmpty());
            assertEquals("pw", loaded.copyPasswordAccounts().get(0).getPassword());

            loaded.setConnectionSecrets(Collections.singletonMap("db/1", "s"));
            support.repository().save(opened, loaded);
            opened.close();

            try (VaultRepository.OpenedVault reopened =
                         support.repository().open("master".toCharArray())) {
                assertEquals("s", reopened.getData().connectionSecret("db/1"));
                assertEquals("pw", reopened.getData().copyPasswordAccounts().get(0).getPassword());
            }
        }
    }

    @Test
    void rejectsConnectionSecretsThatAreNotAnObject() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            writeRawPayload(support, "master",
                    "{\"schemaVersion\":2,\"passwordAccounts\":[],\"totpAccounts\":[],"
                            + "\"connectionSecrets\":[]}");
            VaultException error = assertThrows(VaultException.class,
                    () -> support.repository().open("master".toCharArray()));
            assertEquals(VaultErrorCode.INVALID_ENVELOPE, error.getCode());
        }
    }

    @Test
    void serviceAppliesPutsAndRemovalsAtomicallyAndHidesThemWhileLocked() throws Exception {
        try (VaultUiTestSupport support = new VaultUiTestSupport(temp)) {
            VaultService service = support.service();
            ExecutionException locked = assertThrows(ExecutionException.class,
                    () -> service.updateConnectionSecrets(
                            Collections.singletonMap("db/1", "x"), null).get());
            assertInstanceOf(VaultException.class, locked.getCause());

            service.create("master".toCharArray()).get();
            Map<String, String> puts = new HashMap<>();
            puts.put("db/1", "one");
            puts.put("db/2", "two");
            service.updateConnectionSecrets(puts, null).get();
            service.updateConnectionSecrets(Collections.singletonMap("db/3", "three"),
                    Collections.singletonList("db/1")).get();
            assertNull(service.getConnectionSecret("db/1"));
            assertEquals("two", service.getConnectionSecret("db/2"));
            assertEquals(Arrays.asList("db/2", "db/3"), service.getConnectionSecretKeys());

            service.lock();
            assertNull(service.getConnectionSecret("db/2"));
            assertTrue(service.getConnectionSecretKeys().isEmpty());

            service.unlock("master".toCharArray()).get();
            assertEquals("three", service.getConnectionSecret("db/3"));
            // Password accounts saved later must not drop the connection section.
            service.replacePasswordAccounts(Collections.singletonList(
                    new PasswordAccount("n", "u", "p", "url"))).get();
            assertEquals("two", service.getConnectionSecret("db/2"));
        }
    }

    @Test
    void jsonContractIncludesConnectionSecretsAsObject() throws Exception {
        VaultData data = new VaultData();
        data.setConnectionSecrets(Collections.singletonMap("k8s/1", "v"));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree = mapper.readTree(mapper.writeValueAsBytes(data));
        assertTrue(tree.get("connectionSecrets").isObject());
        assertEquals("v", tree.get("connectionSecrets").get("k8s/1").asText());
        assertEquals(VaultData.SCHEMA_VERSION, tree.get("schemaVersion").asInt());
    }

    private static void writeRawPayload(VaultTestSupport support, String master, String json)
            throws Exception {
        VaultCrypto crypto = new VaultCrypto();
        char[] password = master.toCharArray();
        byte[] salt = crypto.randomBytes(VaultEnvelope.SALT_BYTES);
        byte[] nonce = crypto.randomBytes(VaultEnvelope.NONCE_BYTES);
        byte[] key = crypto.deriveKey(password, salt, VaultEnvelope.NEW_FILE_ITERATIONS);
        VaultEnvelope header = VaultEnvelope.newEnvelope(
                VaultEnvelope.NEW_FILE_ITERATIONS, salt, nonce, new byte[]{1});
        byte[] ciphertext = crypto.encrypt(json.getBytes(StandardCharsets.UTF_8),
                key, nonce, header.aad());
        VaultEnvelope envelope = VaultEnvelope.newEnvelope(
                VaultEnvelope.NEW_FILE_ITERATIONS, salt, nonce, ciphertext);
        new ObjectMapper().writeValue(support.paths().getVaultFile().toFile(), envelope);
        VaultCrypto.wipe(password);
        VaultCrypto.wipe(key);
    }

    private static void assertCode(VaultErrorCode expected, VaultData data) {
        VaultException error = assertThrows(VaultException.class, data::validate);
        assertEquals(expected, error.getCode());
    }
}
