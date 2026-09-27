package com.aqishi.toolbox.infra.secrets;

import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 单个令牌设置：值进保险库，旧明文只读并在解锁后迁走。 */
class VaultTokenSettingTest {

    private static final String KEY = "replicate_api_token";

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

    private VaultTokenSetting setting(SecretStore secrets) {
        return new VaultTokenSetting(secrets, prefs, KEY, "replicate", "api-token");
    }

    @Test
    void legacyPlaintextKeepsWorkingWhileLockedAndMovesOnUnlock() throws Exception {
        vault.service().create("master".toCharArray()).get();
        vault.service().lock();
        prefs.put(KEY, "r8_legacy");
        VaultTokenSetting token = setting(store);

        assertEquals("r8_legacy", token.current(), "the old token must keep working until migrated");
        assertFalse(token.migrate().get(), "nothing moves while locked");
        assertEquals("r8_legacy", prefs.get(KEY, null));

        vault.service().unlock("master".toCharArray()).get();
        assertTrue(token.migrate().get());

        assertNull(prefs.get(KEY, null), "plaintext is removed once the vault holds it");
        assertEquals("r8_legacy", store.get("replicate", "api-token"));
        assertEquals("r8_legacy", token.current());
        assertFalse(token.migrate().get(), "migration is idempotent");
    }

    @Test
    void rememberWritesToTheVaultOnlyWhenUnlocked() throws Exception {
        vault.service().create("master".toCharArray()).get();
        VaultTokenSetting token = setting(store);

        token.remember("r8_new").get();
        assertEquals("r8_new", store.get("replicate", "api-token"));
        assertNull(prefs.get(KEY, null), "never written as plaintext");

        vault.service().lock();
        token.remember("r8_session_only").get();
        vault.service().unlock("master".toCharArray()).get();
        assertEquals("r8_new", store.get("replicate", "api-token"), "a locked vault is not written");
        assertNull(prefs.get(KEY, null));
    }

    @Test
    void aReplacedTokenDropsTheStalePlaintextInsteadOfMigratingIt() throws Exception {
        vault.service().create("master".toCharArray()).get();
        vault.service().lock();
        prefs.put(KEY, "r8_stale");
        VaultTokenSetting token = setting(store);

        token.remember("r8_fresh").get();

        assertNull(prefs.get(KEY, null));
        vault.service().unlock("master".toCharArray()).get();
        assertFalse(token.migrate().get());
        assertNull(store.get("replicate", "api-token"), "the stale token never reaches the vault");
    }

    @Test
    void withoutAVaultNothingIsPersisted() throws Exception {
        VaultTokenSetting token = setting(SecretStore.disabled());

        token.remember("r8_typed").get();

        assertNull(prefs.get(KEY, null));
        assertFalse(token.persistent());
        assertEquals("", token.current());
    }
}
