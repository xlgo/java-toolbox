package com.aqishi.toolbox.infra.secrets;

import com.aqishi.toolbox.vault.VaultUiTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultSecretStoreTest {
    @TempDir
    Path temp;

    @Test
    void putGetRemoveAndMoveAreNamespaced() throws Exception {
        try (VaultUiTestSupport vault = new VaultUiTestSupport(temp)) {
            VaultSecretStore store = new VaultSecretStore(vault.service());
            assertEquals(SecretStore.Status.NO_VAULT, store.status());
            vault.service().create("master".toCharArray()).get();
            assertEquals(SecretStore.Status.UNLOCKED, store.status());

            store.put("db", "a", "one").get();
            store.put("redis", "a", "other").get();
            assertEquals("one", store.get("db", "a"));
            assertEquals("other", store.get("redis", "a"));
            assertEquals(Collections.singletonList("a"), store.ids("db"));

            store.move("db", "a", "b").get();
            assertNull(store.get("db", "a"));
            assertEquals("one", store.get("db", "b"));
            assertEquals("other", store.get("redis", "a"), "other namespaces are untouched");

            store.remove("db", "b").get();
            assertNull(store.get("db", "b"));
            assertTrue(store.ids("db").isEmpty());

            Map<String, String> puts = new HashMap<>();
            puts.put("x", "1");
            puts.put("y", "2");
            store.apply("k8s", puts, null).get();
            store.apply("k8s", Collections.singletonMap("z", "3"), Arrays.asList("x", "y")).get();
            assertEquals(Collections.singletonList("z"), store.ids("k8s"));
        }
    }

    @Test
    void lockedStoreHidesValuesRejectsWritesAndNotifiesOncePerStatus() throws Exception {
        try (VaultUiTestSupport vault = new VaultUiTestSupport(temp)) {
            VaultSecretStore store = new VaultSecretStore(vault.service());
            List<SecretStore.Status> seen = new ArrayList<>();
            store.addListener(seen::add);
            vault.service().create("master".toCharArray()).get();
            store.put("db", "a", "one").get();
            vault.service().lock();

            assertEquals(SecretStore.Status.LOCKED, store.status());
            assertNull(store.get("db", "a"));
            assertThrows(ExecutionException.class, () -> store.put("db", "a", "x").get());

            char[] master = "master".toCharArray();
            store.unlock(master).get();
            assertArrayEquals(new char[master.length], master, "master password is wiped");
            assertEquals("one", store.get("db", "a"));
            // create -> UNLOCKED, lock -> LOCKED, unlock -> UNLOCKED; SAVING never surfaces.
            assertEquals(Arrays.asList(SecretStore.Status.UNLOCKED, SecretStore.Status.LOCKED,
                    SecretStore.Status.UNLOCKED), seen);
        }
    }

    @Test
    void wrongMasterPasswordFailsUnlock() throws Exception {
        try (VaultUiTestSupport vault = new VaultUiTestSupport(temp)) {
            VaultSecretStore store = new VaultSecretStore(vault.service());
            vault.service().create("master".toCharArray()).get();
            vault.service().lock();
            assertThrows(ExecutionException.class, () -> store.unlock("nope".toCharArray()).get());
            assertEquals(SecretStore.Status.LOCKED, store.status());
        }
    }

    @Test
    void rejectsMalformedKeysAndDisablesWithoutService() throws Exception {
        try (VaultUiTestSupport vault = new VaultUiTestSupport(temp)) {
            assertEquals(SecretStore.Status.NO_VAULT, VaultSecretStore.of(vault.service()).status());
            assertSame(SecretStore.disabled(), VaultSecretStore.of(null));
            assertThrows(IllegalArgumentException.class, () -> VaultSecretStore.key("a/b", "id"));
            assertThrows(IllegalArgumentException.class, () -> VaultSecretStore.key("db", ""));
        }
    }

    @Test
    void disabledStoreKeepsNothing() {
        SecretStore store = SecretStore.disabled();
        assertEquals(SecretStore.Status.DISABLED, store.status());
        assertNull(store.get("db", "a"));
        assertThrows(ExecutionException.class, () -> store.put("db", "a", "x").get());
        char[] master = "m".toCharArray();
        assertThrows(ExecutionException.class, () -> store.unlock(master).get());
        assertArrayEquals(new char[1], master);
    }

    @Test
    void secretFieldsCodecIgnoresEmptyValuesAndNeverThrowsOnGarbage() {
        Map<String, String> fields = new HashMap<>();
        fields.put("password", "p");
        fields.put("empty", "");
        fields.put("none", null);
        String encoded = SecretFields.encode(fields);
        assertEquals(Collections.singletonMap("password", "p"), SecretFields.decode(encoded));
        assertTrue(SecretFields.decode("not json").isEmpty());
        assertTrue(SecretFields.decode(null).isEmpty());
    }
}
