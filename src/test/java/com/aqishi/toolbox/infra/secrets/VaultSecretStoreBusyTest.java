package com.aqishi.toolbox.infra.secrets;

import com.aqishi.toolbox.vault.ApplicationPaths;
import com.aqishi.toolbox.vault.AtomicFiles;
import com.aqishi.toolbox.vault.LegacyVaultMigrator;
import com.aqishi.toolbox.vault.PasswordAccount;
import com.aqishi.toolbox.vault.VaultClock;
import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultFileLock;
import com.aqishi.toolbox.vault.VaultRepository;
import com.aqishi.toolbox.vault.VaultScheduler;
import com.aqishi.toolbox.vault.VaultService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** A write that collides with another vault operation is retried, not failed with BUSY. */
class VaultSecretStoreBusyTest {
    @TempDir
    Path temp;

    @Test
    void retriesWhileAnotherVaultOperationRuns() throws Exception {
        Path abs = temp.toAbsolutePath();
        Map<String, String> env = new HashMap<>();
        env.put("APPDATA", abs.resolve("data").toString());
        env.put("XDG_DATA_HOME", abs.resolve("data").toString());
        env.put("XDG_CONFIG_HOME", abs.resolve("config").toString());
        ApplicationPaths paths = ApplicationPaths.resolve(
                System.getProperty("os.name"), abs.toString(), env, abs.resolve("legacy"));
        paths.createPrivateDirectories();
        AtomicFiles files = new AtomicFiles();
        VaultCrypto crypto = new VaultCrypto();
        VaultRepository repository = new VaultRepository(
                paths, files, crypto, new VaultFileLock(paths.getLockFile()));
        ExecutorService worker = Executors.newSingleThreadExecutor();
        VaultService service = new VaultService(repository,
                new LegacyVaultMigrator(paths, repository, files, crypto),
                worker, Runnable::run, VaultClock.system(), VaultScheduler.daemon(), 5);
        try {
            service.create("master".toCharArray()).get(30, TimeUnit.SECONDS);
            CountDownLatch gate = new CountDownLatch(1);
            worker.execute(() -> {
                try {
                    gate.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            // Occupies the vault: queued behind the gate, so the next write sees BUSY.
            CompletableFuture<Void> other = service.replacePasswordAccounts(
                    Collections.singletonList(new PasswordAccount("n", "u", "p", "url")));
            VaultSecretStore store = new VaultSecretStore(service, 20, 200);

            CompletableFuture<Void> put = store.put("db", "a", "value");
            assertFalse(put.isDone());
            gate.countDown();

            other.get(10, TimeUnit.SECONDS);
            put.get(10, TimeUnit.SECONDS);
            assertEquals("value", store.get("db", "a"));
            assertEquals(1, service.getPasswordAccounts().size());
        } finally {
            service.close();
        }
    }
}
