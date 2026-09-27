package com.aqishi.toolbox.infra.secrets;

import com.aqishi.toolbox.domain.DatabaseProfile;
import com.aqishi.toolbox.infra.database.DatabaseProfileStore;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager.Resolution;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager.SaveOutcome;
import com.aqishi.toolbox.infra.secrets.SecretPrompter.Answer;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Save / connect / lock behaviour of vault-backed connection profiles. */
class ProfileSecretManagerTest {
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

    private ProfileSecretManager<DatabaseProfile> manager() {
        ProfileSecretManager<DatabaseProfile> manager = new ProfileSecretManager<>(
                DatabaseProfileStore.SECRET_NAMESPACE, new DatabaseProfileStore(prefs), store, Runnable::run);
        manager.reload();
        return manager;
    }

    private static DatabaseProfile profile(String name, String password) {
        return new DatabaseProfile(name, "MySQL", "127.0.0.1", "3306", "test", "root",
                password, "com.mysql.cj.jdbc.Driver", "jdbc:mysql://127.0.0.1:3306/test", "");
    }

    private String rawPrefs() {
        return String.valueOf(prefs.raw());
    }

    @Test
    void unlockedSaveKeepsSecretInVaultAndOnlyAFlagInPreferences() throws Exception {
        vault.service().create("master".toCharArray()).get();
        ProfileSecretManager<DatabaseProfile> manager = manager();

        SaveOutcome outcome = manager.save("orders", profile("orders", "pw-123"),
                new ScriptedPrompter()).get();

        assertEquals(SaveOutcome.SAVED_WITH_SECRET, outcome);
        DatabaseProfile saved = manager.profiles().get("orders");
        assertNull(saved.password, "the live profile never keeps the secret");
        assertTrue(saved.secretStored);
        assertFalse(rawPrefs().contains("pw-123"));
        assertTrue(rawPrefs().contains("\"secretStored\":true"));
        assertEquals("{\"password\":\"pw-123\"}", store.get("db", saved.id));

        ProfileSecretManager<DatabaseProfile> reloaded = manager();
        DatabaseProfile again = reloaded.profiles().get("orders");
        assertEquals(saved.id, again.id, "ids are stable across reloads");
        assertFalse(again.plaintextPending);
        assertEquals("pw-123", reloaded.knownSecrets(again).get("password"));
    }

    @Test
    void resavingUnderSameNameKeepsIdAndClearingPasswordRemovesSecret() throws Exception {
        vault.service().create("master".toCharArray()).get();
        ProfileSecretManager<DatabaseProfile> manager = manager();
        manager.save("orders", profile("orders", "one"), new ScriptedPrompter()).get();
        String id = manager.profiles().get("orders").id;

        manager.save("orders", profile("orders", "two"), new ScriptedPrompter()).get();
        assertEquals(id, manager.profiles().get("orders").id);
        assertEquals("{\"password\":\"two\"}", store.get("db", id));

        assertEquals(SaveOutcome.SAVED_WITHOUT_SECRET,
                manager.save("orders", profile("orders", ""), new ScriptedPrompter()).get());
        assertFalse(manager.profiles().get("orders").secretStored);
        assertNull(store.get("db", id));
    }

    @Test
    void lockedSaveAsksAndCanSkipUnlockOrCancel() throws Exception {
        vault.service().create("master".toCharArray()).get();
        vault.service().lock();
        ProfileSecretManager<DatabaseProfile> manager = manager();
        ScriptedPrompter prompter = new ScriptedPrompter()
                .then(Answer::skipSecret)
                .then(() -> Answer.unlock("master".toCharArray()))
                .then(Answer::cancel);

        assertEquals(SaveOutcome.SAVED_WITHOUT_SECRET,
                manager.save("a", profile("a", "secret-a"), prompter).get());
        assertFalse(manager.profiles().get("a").secretStored);
        assertFalse(rawPrefs().contains("secret-a"));

        assertEquals(SaveOutcome.SAVED_WITH_SECRET,
                manager.save("b", profile("b", "secret-b"), prompter).get());
        assertEquals(SecretStore.Status.UNLOCKED, store.status());
        assertEquals("{\"password\":\"secret-b\"}", store.get("db", manager.profiles().get("b").id));

        vault.service().lock();
        assertEquals(SaveOutcome.CANCELLED,
                manager.save("c", profile("c", "secret-c"), prompter).get());
        assertFalse(manager.profiles().containsKey("c"));
        assertEquals(Arrays.asList("save:a:LOCKED", "save:b:LOCKED", "save:c:LOCKED"), prompter.asked);
        assertFalse(rawPrefs().contains("secret-"));
    }

    @Test
    void wrongMasterPasswordOnSaveLeavesProfileUnsaved() throws Exception {
        vault.service().create("master".toCharArray()).get();
        vault.service().lock();
        ProfileSecretManager<DatabaseProfile> manager = manager();
        ScriptedPrompter prompter = new ScriptedPrompter().then(() -> Answer.unlock("bad".toCharArray()));
        assertEquals(SaveOutcome.UNLOCK_FAILED, manager.save("a", profile("a", "x"), prompter).get());
        assertFalse(manager.profiles().containsKey("a"));
    }

    @Test
    void withoutVaultSaveOffersOnlyToSkipTheSecret() throws Exception {
        ProfileSecretManager<DatabaseProfile> manager = manager();
        ScriptedPrompter prompter = new ScriptedPrompter().then(Answer::skipSecret);
        assertEquals(SaveOutcome.SAVED_WITHOUT_SECRET,
                manager.save("a", profile("a", "no-vault-secret"), prompter).get());
        assertEquals(Arrays.asList("save:a:NO_VAULT"), prompter.asked);
        assertFalse(rawPrefs().contains("no-vault-secret"));
    }

    @Test
    void connectWithLockedVaultUsesInjectedPromptStrategy() throws Exception {
        vault.service().create("master".toCharArray()).get();
        ProfileSecretManager<DatabaseProfile> manager = manager();
        manager.save("orders", profile("orders", "stored-pw"), new ScriptedPrompter()).get();
        DatabaseProfile orders = manager.profiles().get("orders");
        vault.service().lock();
        assertTrue(manager.needsUnlock(orders));

        ScriptedPrompter prompter = new ScriptedPrompter()
                .then(Answer::cancel)
                .then(() -> Answer.unlock("wrong".toCharArray()))
                .then(() -> Answer.session("typed-now".toCharArray()))
                .then(() -> Answer.unlock("master".toCharArray()));

        assertTrue(manager.resolve(orders, prompter, true).get().cancelled());
        assertEquals(Resolution.Kind.UNLOCK_FAILED, manager.resolve(orders, prompter, true).get().kind());

        Resolution session = manager.resolve(orders, prompter, true).get();
        assertEquals("typed-now", session.field("password"));
        assertEquals(SecretStore.Status.LOCKED, store.status(), "session entry does not unlock");
        assertFalse(rawPrefs().contains("typed-now"), "session passwords are never persisted");
        // The typed password is reused for this session without asking again...
        assertEquals("typed-now", manager.resolve(orders, prompter, true).get().field("password"));
        // ...until the vault locks; the vault itself still holds the saved password.
        assertEquals("{\"password\":\"stored-pw\"}", peekVault(orders.id));
        Resolution unlocked = manager.resolve(orders, prompter, true).get();
        assertEquals("stored-pw", unlocked.field("password"));
        assertEquals(4, prompter.asked.size());
        assertEquals("connect:orders:LOCKED:true", prompter.asked.get(0));
    }

    private String peekVault(String id) throws Exception {
        vault.service().unlock("master".toCharArray()).get();
        String value = store.get("db", id);
        vault.service().lock();
        return value;
    }

    @Test
    void lockingDropsCachedSecretsAndNotifiesPanels() throws Exception {
        vault.service().create("master".toCharArray()).get();
        ProfileSecretManager<DatabaseProfile> manager = manager();
        AtomicInteger locked = new AtomicInteger();
        AtomicInteger unlocked = new AtomicInteger();
        manager.addListener(new ProfileSecretManager.Listener() {
            @Override
            public void onSecretsLocked() {
                locked.incrementAndGet();
            }

            @Override
            public void onSecretsUnlocked() {
                unlocked.incrementAndGet();
            }
        });
        manager.save("orders", profile("orders", "pw"), new ScriptedPrompter()).get();
        DatabaseProfile orders = manager.profiles().get("orders");
        assertEquals("pw", manager.knownSecrets(orders).get("password"));
        assertEquals(1, manager.cachedSecretCount());

        vault.service().lock();

        assertEquals(1, locked.get());
        assertEquals(0, manager.cachedSecretCount());
        assertTrue(manager.knownSecrets(orders).isEmpty());
        assertTrue(manager.needsUnlock(orders));
        vault.service().unlock("master".toCharArray()).get();
        assertEquals(1, unlocked.get());
        assertEquals("pw", manager.knownSecrets(orders).get("password"));
    }

    @Test
    void deleteRemovesSecretNowOrAfterNextUnlockAndRenameKeepsIt() throws Exception {
        vault.service().create("master".toCharArray()).get();
        ProfileSecretManager<DatabaseProfile> manager = manager();
        manager.save("a", profile("a", "pa"), new ScriptedPrompter()).get();
        manager.save("b", profile("b", "pb"), new ScriptedPrompter()).get();
        String idA = manager.profiles().get("a").id;
        String idB = manager.profiles().get("b").id;

        assertTrue(manager.rename("a", "renamed"));
        assertEquals(idA, manager.profiles().get("renamed").id);
        assertEquals("pa", manager.knownSecrets(manager.profiles().get("renamed")).get("password"));
        assertTrue(rawPrefs().contains("renamed"));

        assertNotNull(manager.delete("renamed"));
        assertNull(store.get("db", idA));

        vault.service().lock();
        manager.delete("b");
        vault.service().unlock("master".toCharArray()).get();
        assertNull(store.get("db", idB), "deferred removal runs once the vault is open");
        assertTrue(store.ids("db").isEmpty());
    }

    @Test
    void disabledStoreNeverPromptsAndNeverPersistsSecrets() throws Exception {
        ProfileSecretManager<DatabaseProfile> manager = new ProfileSecretManager<>(
                "db", new DatabaseProfileStore(prefs), SecretStore.disabled(), Runnable::run);
        manager.reload();
        assertEquals(SaveOutcome.SAVED_WITHOUT_SECRET,
                manager.save("a", profile("a", "pw-disabled"), new ScriptedPrompter()).get());
        assertFalse(rawPrefs().contains("pw-disabled"));
        assertEquals(Resolution.Kind.NONE,
                manager.resolve(manager.profiles().get("a"), new ScriptedPrompter(), true).get().kind());
    }
}
