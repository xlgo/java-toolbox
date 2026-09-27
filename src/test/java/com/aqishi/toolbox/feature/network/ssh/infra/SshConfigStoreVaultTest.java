package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.domain.SshSecurityUtils;
import com.aqishi.toolbox.infra.secrets.ScriptedPrompter;
import com.aqishi.toolbox.infra.secrets.SecretPrompter.Answer;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.infra.secrets.VaultSecretStore;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSH credentials move from ssh_servers.json into the vault. */
class SshConfigStoreVaultTest {
    @TempDir
    Path temp;

    private VaultUiTestSupport vault;
    private VaultSecretStore secrets;
    private File file;

    @BeforeEach
    void setUp() throws Exception {
        vault = new VaultUiTestSupport(temp.resolve("vault"));
        secrets = new VaultSecretStore(vault.service());
        Files.createDirectories(temp.resolve("ssh"));
        file = temp.resolve("ssh").resolve("ssh_servers.json").toFile();
        SshConfigStore legacy = new SshConfigStore(file);
        SshConnectionConfig config = new SshConnectionConfig();
        config.setId("srv-1");
        config.setHost("10.0.0.1");
        config.setEncryptedPassword(SshSecurityUtils.encrypt("ssh-secret"));
        legacy.addOrUpdate(config);
    }

    @AfterEach
    void tearDown() {
        vault.close();
    }

    private JsonNode persisted() throws Exception {
        return new ObjectMapper().readTree(file).get(0);
    }

    @Test
    void localCredentialsMoveOnUnlockAndFileKeepsOnlyTheFlag() throws Exception {
        vault.service().create("master".toCharArray()).get();
        vault.service().lock();
        SshConfigStore store = new SshConfigStore(file);
        store.attachSecretStore(secrets, null, Runnable::run);
        assertEquals(1, store.localSecretCount());
        assertEquals("ssh-secret", store.findById("srv-1").resolvedPassword(), "usable until migrated");

        vault.service().unlock("master".toCharArray()).get();

        assertEquals(0, store.localSecretCount());
        JsonNode json = persisted();
        assertEquals("", json.get("encryptedPassword").asText());
        assertTrue(json.get("secretStored").asBoolean());
        assertEquals("{\"password\":\"ssh-secret\"}", secrets.get("ssh", "srv-1"));
        assertEquals("ssh-secret", store.findById("srv-1").resolvedPassword());
        assertEquals(0, (int) store.migrateLocalSecrets().get(), "idempotent");

        vault.service().lock();
        assertEquals("", store.findById("srv-1").resolvedPassword(), "lock drops loaded credentials");
        assertEquals(1, store.vaultStoredCount());

        SshConfigStore reloaded = new SshConfigStore(file);
        reloaded.attachSecretStore(secrets, null, Runnable::run);
        assertTrue(reloaded.findById("srv-1").isSecretStored());
        assertEquals("", reloaded.findById("srv-1").resolvedPassword());
    }

    @Test
    void lockedConnectUsesInjectedPromptForSessionPasswordOrUnlock() throws Exception {
        vault.service().create("master".toCharArray()).get();
        SshConfigStore store = new SshConfigStore(file);
        ScriptedPrompter prompter = new ScriptedPrompter()
                .then(Answer::cancel)
                .then(() -> Answer.session("typed".toCharArray()))
                .then(() -> Answer.unlock("master".toCharArray()));
        store.attachSecretStore(secrets, new SshVaultCredentials(prompter), Runnable::run);
        vault.service().lock();

        SshConnectionConfig first = store.findById("srv-1").clone();
        assertFalse(store.ensureCredentialsFor(first), "cancel aborts the connection");

        SshConnectionConfig session = store.findById("srv-1").clone();
        assertTrue(store.ensureCredentialsFor(session));
        assertEquals("typed", session.resolvedPassword());
        assertEquals(SecretStore.Status.LOCKED, secrets.status());
        assertFalse(Files.readString(file.toPath()).contains("typed"));

        SshConnectionConfig unlocked = store.findById("srv-1").clone();
        assertTrue(store.ensureCredentialsFor(unlocked));
        assertEquals("ssh-secret", unlocked.resolvedPassword());
        assertEquals(3, prompter.asked.size());
    }

    @Test
    void failedVaultWriteLeavesFileUntouchedAndDeleteForgetsVaultCopy() throws Exception {
        vault.service().create("master".toCharArray()).get();
        byte[] before = Files.readAllBytes(file.toPath());
        SshConfigStore failing = new SshConfigStore(file);
        failing.attachSecretStore(new RejectingStore(secrets), null, Runnable::run);
        assertArrayEquals(before, Files.readAllBytes(file.toPath()));
        assertEquals(1, failing.localSecretCount());

        SshConfigStore store = new SshConfigStore(file);
        store.attachSecretStore(secrets, null, Runnable::run);
        assertEquals("{\"password\":\"ssh-secret\"}", secrets.get("ssh", "srv-1"));
        assertTrue(store.delete("srv-1"));
        assertNull(secrets.get("ssh", "srv-1"));
    }

    /** Fails every batch write (the migration path); everything else is delegated. */
    private static final class RejectingStore implements SecretStore {
        private final SecretStore delegate;

        RejectingStore(SecretStore delegate) {
            this.delegate = delegate;
        }

        @Override public Status status() { return delegate.status(); }
        @Override public String get(String n, String id) { return delegate.get(n, id); }
        @Override public List<String> ids(String n) { return delegate.ids(n); }
        @Override public CompletableFuture<Void> put(String n, String id, String s) { return delegate.put(n, id, s); }
        @Override public CompletableFuture<Void> remove(String n, String id) { return delegate.remove(n, id); }
        @Override public CompletableFuture<Void> move(String n, String f, String t) { return delegate.move(n, f, t); }
        @Override public CompletableFuture<Void> unlock(char[] m) { return delegate.unlock(m); }
        @Override public void addListener(Listener l) { }
        @Override public void removeListener(Listener l) { }

        @Override
        public CompletableFuture<Void> apply(String n, Map<String, String> puts, Collection<String> removals) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("vault write failed"));
            return failed;
        }
    }
}
