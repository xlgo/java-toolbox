package com.aqishi.toolbox.infra.secrets;

import com.aqishi.toolbox.domain.DatabaseProfile;
import com.aqishi.toolbox.domain.KafkaProfile;
import com.aqishi.toolbox.domain.KubernetesProfile;
import com.aqishi.toolbox.feature.data.application.KafkaProfileStore;
import com.aqishi.toolbox.infra.database.DatabaseProfileStore;
import com.aqishi.toolbox.infra.kubernetes.KubeconfigStore;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One-time move of legacy plaintext secrets from preferences into the vault. */
class PlaintextMigrationTest {
    private static final String LEGACY_DB = "{\"orders\":{\"name\":\"orders\",\"dbType\":\"MySQL\","
            + "\"host\":\"h\",\"port\":\"3306\",\"database\":\"d\",\"username\":\"root\","
            + "\"password\":\"legacy-secret\",\"driverClass\":\"x\",\"url\":\"jdbc:x\",\"jarPath\":\"\"}}";

    @TempDir
    Path temp;

    private VaultUiTestSupport vault;
    private VaultSecretStore store;
    private InMemoryPreferences prefs;

    @BeforeEach
    void setUp() throws Exception {
        vault = new VaultUiTestSupport(temp);
        store = new VaultSecretStore(vault.service());
        prefs = new InMemoryPreferences();
    }

    @AfterEach
    void tearDown() {
        vault.close();
    }

    private ProfileSecretManager<DatabaseProfile> dbManager(SecretStore secrets) {
        ProfileSecretManager<DatabaseProfile> manager = new ProfileSecretManager<>(
                "db", new DatabaseProfileStore(prefs), secrets, Runnable::run);
        manager.reload();
        return manager;
    }

    @Test
    void plaintextStaysUsableUntilUnlockThenMovesAndIsStripped() throws Exception {
        vault.service().create("master".toCharArray()).get();
        vault.service().lock();
        prefs.put("db_profiles", LEGACY_DB);

        ProfileSecretManager<DatabaseProfile> manager = dbManager(store);
        DatabaseProfile orders = manager.profiles().get("orders");
        assertEquals(1, manager.plaintextCount());
        assertNotNull(orders.id);
        assertTrue(prefs.get("db_profiles", "").contains(orders.id), "new ids are persisted at once");
        assertEquals("legacy-secret", manager.knownSecrets(orders).get("password"),
                "until migrated the plaintext keeps working");
        assertTrue(prefs.get("db_profiles", "").contains("legacy-secret"));

        // Unrelated saves while locked must not lose the not-yet-migrated password.
        manager.save("other", new DatabaseProfile("other", "MySQL", "h", "1", "d", "u", "",
                "x", "u", ""), new ScriptedPrompter()).get();
        assertTrue(prefs.get("db_profiles", "").contains("legacy-secret"));
        assertEquals(0, (int) manager.migratePlaintext().get(), "no migration while locked");

        int flushesBefore = prefs.flushCount;
        vault.service().unlock("master".toCharArray()).get();

        assertEquals(0, manager.plaintextCount());
        assertFalse(prefs.get("db_profiles", "").contains("legacy-secret"));
        assertTrue(prefs.flushCount > flushesBefore, "stripped preferences are flushed");
        assertTrue(orders.secretStored);
        assertEquals("{\"password\":\"legacy-secret\"}", store.get("db", orders.id));
        assertEquals("legacy-secret", manager.knownSecrets(orders).get("password"));

        String afterFirst = prefs.get("db_profiles", "");
        assertEquals(0, (int) manager.migratePlaintext().get(), "migration is idempotent");
        assertEquals(afterFirst, prefs.get("db_profiles", ""));

        ProfileSecretManager<DatabaseProfile> reloaded = dbManager(store);
        assertEquals(0, reloaded.plaintextCount());
        assertEquals("legacy-secret",
                reloaded.knownSecrets(reloaded.profiles().get("orders")).get("password"));
    }

    @Test
    void failedVaultWriteLeavesPreferencesUntouched() throws Exception {
        vault.service().create("master".toCharArray()).get();
        prefs.put("db_profiles", LEGACY_DB);
        FailingStore failing = new FailingStore(store);
        ProfileSecretManager<DatabaseProfile> manager = dbManager(failing);
        String before = prefs.get("db_profiles", "");

        assertThrows(ExecutionException.class, () -> manager.migratePlaintext().get());

        assertEquals(before, prefs.get("db_profiles", ""));
        assertEquals(1, manager.plaintextCount());
        assertEquals("legacy-secret",
                manager.knownSecrets(manager.profiles().get("orders")).get("password"));
        assertFalse(manager.migrating());
    }

    @Test
    void failedPreferencesRewriteKeepsPlaintextAndRetrySucceeds() throws Exception {
        vault.service().create("master".toCharArray()).get();
        vault.service().lock();
        prefs.put("db_profiles", LEGACY_DB);
        ProfileSecretManager<DatabaseProfile> manager = dbManager(store);
        prefs.failWrites = true;

        vault.service().unlock("master".toCharArray()).get();

        // The vault copy was written first; preferences could not be stripped.
        DatabaseProfile orders = manager.profiles().get("orders");
        assertEquals("{\"password\":\"legacy-secret\"}", store.get("db", orders.id));
        assertTrue(orders.plaintextPending);
        assertEquals("legacy-secret", orders.password);
        assertTrue(prefs.get("db_profiles", "").contains("legacy-secret"));

        prefs.failWrites = false;
        assertEquals(1, (int) manager.migratePlaintext().get());
        assertFalse(prefs.get("db_profiles", "").contains("legacy-secret"));
    }

    @Test
    void kafkaCredentialLinesMoveButPublicPropertiesStay() throws Exception {
        vault.service().create("master".toCharArray()).get();
        String props = "security.protocol=SASL_SSL\\n"
                + "sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required \\\\\\n"
                + "  username=\\\"u\\\" password=\\\"kafka-secret\\\";\\n"
                + "ssl.truststore.password=trust-secret";
        prefs.put("kafka_profiles", "{\"k\":{\"name\":\"k\",\"bootstrapServers\":\"b:9092\","
                + "\"customProperties\":\"" + props + "\"}}");
        ProfileSecretManager<KafkaProfile> manager = new ProfileSecretManager<>(
                "kafka", new KafkaProfileStore(prefs), store, Runnable::run);
        manager.reload();

        String persisted = prefs.get("kafka_profiles", "");
        assertFalse(persisted.contains("kafka-secret"));
        assertFalse(persisted.contains("trust-secret"));
        assertTrue(persisted.contains("security.protocol=SASL_SSL"));
        KafkaProfile k = manager.profiles().get("k");
        String secrets = manager.knownSecrets(k).get(KafkaProfile.SECRET_PROPERTIES);
        assertTrue(secrets.contains("kafka-secret") && secrets.contains("trust-secret"));
        k.applySecretFields(manager.knownSecrets(k));
        assertTrue(k.customProperties.startsWith("security.protocol=SASL_SSL"));
        assertTrue(k.customProperties.contains("password=\"kafka-secret\""));
    }

    @Test
    void kubeconfigTokenAndClientKeyAreSecretsButCertificatesAreNot() throws Exception {
        vault.service().create("master".toCharArray()).get();
        KubeconfigStore repository = new KubeconfigStore(prefs);
        ProfileSecretManager<KubernetesProfile> manager = new ProfileSecretManager<>(
                KubeconfigStore.SECRET_NAMESPACE, repository, store, Runnable::run);
        manager.reload();
        KubernetesProfile p = new KubernetesProfile("prod", "https://k:6443", "tok-secret", false,
                "CERT-PUBLIC", "KEY-SECRET", "CA-PUBLIC");

        manager.save("prod", p, new ScriptedPrompter()).get();

        String persisted = prefs.get("k8s_manager_profiles", "");
        assertFalse(persisted.contains("tok-secret"));
        assertFalse(persisted.contains("KEY-SECRET"));
        assertTrue(persisted.contains("CERT-PUBLIC") && persisted.contains("CA-PUBLIC"));
        Map<String, String> secrets = manager.knownSecrets(manager.profiles().get("prod"));
        assertEquals("tok-secret", secrets.get("token"));
        assertEquals("KEY-SECRET", secrets.get("clientKeyData"));
        assertEquals(1, repository.list().size());
    }

    /** Delegates everything but fails the batch write used by the migration. */
    private static final class FailingStore implements SecretStore {
        private final SecretStore delegate;

        FailingStore(SecretStore delegate) {
            this.delegate = delegate;
        }

        @Override public Status status() { return delegate.status(); }
        @Override public String get(String namespace, String id) { return delegate.get(namespace, id); }
        @Override public List<String> ids(String namespace) { return delegate.ids(namespace); }
        @Override public CompletableFuture<Void> put(String n, String id, String s) { return delegate.put(n, id, s); }
        @Override public CompletableFuture<Void> remove(String n, String id) { return delegate.remove(n, id); }
        @Override public CompletableFuture<Void> move(String n, String f, String t) { return delegate.move(n, f, t); }
        @Override public CompletableFuture<Void> unlock(char[] m) { return delegate.unlock(m); }
        @Override public void addListener(Listener l) { delegate.addListener(l); }
        @Override public void removeListener(Listener l) { delegate.removeListener(l); }

        @Override
        public CompletableFuture<Void> apply(String namespace, Map<String, String> puts,
                                             Collection<String> removals) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("disk full"));
            return failed;
        }
    }
}
