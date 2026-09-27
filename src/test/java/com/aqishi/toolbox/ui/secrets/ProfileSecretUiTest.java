package com.aqishi.toolbox.ui.secrets;

import com.aqishi.toolbox.domain.DatabaseProfile;
import com.aqishi.toolbox.infra.database.DatabaseProfileStore;
import com.aqishi.toolbox.infra.secrets.InMemoryPreferences;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager;
import com.aqishi.toolbox.infra.secrets.ScriptedPrompter;
import com.aqishi.toolbox.infra.secrets.SecretPrompter.Answer;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.infra.secrets.VaultSecretStore;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileSecretUiTest {
    @TempDir
    Path temp;

    private static final String LEGACY = "{\"a\":{\"name\":\"a\",\"dbType\":\"MySQL\",\"host\":\"h\","
            + "\"port\":\"1\",\"database\":\"d\",\"username\":\"u\",\"password\":\"old-pw\","
            + "\"driverClass\":\"x\",\"url\":\"u\",\"jarPath\":\"\"}}";

    @Test
    void noticeTracksPlaintextLockAndMigration() throws Exception {
        I18n.init();
        try (VaultUiTestSupport vault = new VaultUiTestSupport(temp)) {
            InMemoryPreferences prefs = new InMemoryPreferences();
            prefs.put("db_profiles", LEGACY);
            ProfileSecretManager<DatabaseProfile> manager = new ProfileSecretManager<>(
                    "db", new DatabaseProfileStore(prefs), new VaultSecretStore(vault.service()), Runnable::run);
            onEdt(() -> {
                ProfileSecretUi<DatabaseProfile> ui = new ProfileSecretUi<>(manager, new ScriptedPrompter(), JPanel::new);
                ui.wrap(new JPanel());
                manager.ensureLoaded();
                assertEquals(I18n.get("secrets.notice.plaintext.noVault", 1), ui.notice().messageText());
                assertFalse(ui.notice().unlockOffered());
            });
            // Creating a vault unlocks it and would migrate at once; test the locked case
            // with a fresh manager over the still-plaintext preferences.
            manager.dispose();
            vault.service().create("master".toCharArray()).get();
            vault.service().lock();
            ProfileSecretManager<DatabaseProfile> lockedManager = new ProfileSecretManager<>(
                    "db", new DatabaseProfileStore(prefs), new VaultSecretStore(vault.service()), Runnable::run);
            onEdt(() -> {
                lockedManager.ensureLoaded();
                ProfileSecretUi<DatabaseProfile> ui = new ProfileSecretUi<>(lockedManager, new ScriptedPrompter(), JPanel::new);
                ui.refreshNotice();
                assertEquals(I18n.get("secrets.notice.plaintext.locked", 1), ui.notice().messageText());
                assertTrue(ui.notice().unlockOffered());
            });
            vault.service().unlock("master".toCharArray()).get();
            onEdt(() -> {
                ProfileSecretUi<DatabaseProfile> ui = new ProfileSecretUi<>(lockedManager, new ScriptedPrompter(), JPanel::new);
                ui.refreshNotice();
                assertEquals("", ui.notice().messageText(), "nothing left to report once migrated");
            });
            vault.service().lock();
            onEdt(() -> {
                ProfileSecretUi<DatabaseProfile> ui = new ProfileSecretUi<>(lockedManager, new ScriptedPrompter(), JPanel::new);
                ui.refreshNotice();
                assertEquals(I18n.get("secrets.notice.locked", 1), ui.notice().messageText());
            });
        }
    }

    @Test
    void boundFieldIsClearedOnLockRefilledOnUnlockAndConnectPromptsWhenLocked() throws Exception {
        try (VaultUiTestSupport vault = new VaultUiTestSupport(temp)) {
            vault.service().create("master".toCharArray()).get();
            InMemoryPreferences prefs = new InMemoryPreferences();
            ProfileSecretManager<DatabaseProfile> manager = new ProfileSecretManager<>(
                    "db", new DatabaseProfileStore(prefs), new VaultSecretStore(vault.service()), Runnable::run);
            ScriptedPrompter prompter = new ScriptedPrompter().then(() -> Answer.session("sess".toCharArray()));
            JPasswordField field = new JPasswordField();
            JPasswordField typedField = new JPasswordField();
            AtomicInteger connects = new AtomicInteger();
            ProfileSecretUi<DatabaseProfile>[] holder = new ProfileSecretUi[1];
            onEdt(() -> {
                manager.ensureLoaded();
                holder[0] = new ProfileSecretUi<>(manager, prompter, JPanel::new);
                holder[0].bind(field, () -> manager.profiles().get("a"), "password");
                holder[0].save("a", new DatabaseProfile("a", "MySQL", "h", "1", "d", "u", "pw",
                        "x", "u", ""), null);
                holder[0].fill(field, manager.profiles().get("a"), "password");
                assertEquals("pw", new String(field.getPassword()));
                typedField.setText("typed-by-user");
            });

            vault.service().lock();
            onEdt(() -> {
                assertEquals("", new String(field.getPassword()), "vault-filled value dropped on lock");
                assertEquals("typed-by-user", new String(typedField.getPassword()));
                holder[0].ensureField(field, manager.profiles().get("a"), "password", connects::incrementAndGet);
                assertEquals("sess", new String(field.getPassword()));
                assertEquals(1, connects.get());
                assertEquals(1, prompter.asked.size());
                field.setText("");
            });

            vault.service().unlock("master".toCharArray()).get();
            onEdt(() -> assertEquals("pw", new String(field.getPassword()), "refilled on unlock"));
            assertEquals(SecretStore.Status.UNLOCKED, manager.status());
        }
    }

    private static void onEdt(ThrowingRunnable body) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] instanceof Exception) throw (Exception) failure[0];
        if (failure[0] instanceof Error) throw (Error) failure[0];
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
