package com.aqishi.toolbox.vault;

import com.aqishi.toolbox.util.ConfigManager;

import javax.swing.SwingUtilities;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Builds the one production vault session shared by both sensitive tools. */
public final class VaultBootstrap {
    private VaultBootstrap() {
    }

    public static Components createDefault() {
        try {
            ApplicationPaths paths = ApplicationPaths.systemDefault();
            paths.createPrivateDirectories();
            AtomicFiles files = new AtomicFiles();
            VaultCrypto crypto = new VaultCrypto();
            VaultFileLock fileLock = new VaultFileLock(paths.getLockFile());
            VaultRepository repository = new VaultRepository(
                    paths, files, crypto, fileLock);
            LegacyVaultMigrator migrator = new LegacyVaultMigrator(
                    paths, repository, files, crypto);
            ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "java-toolbox-vault-worker");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            VaultScheduler scheduler = VaultScheduler.daemon();
            int idleMinutes = validIdleMinutes(
                    ConfigManager.getInt("vault.idleMinutes", 5));
            VaultService service = new VaultService(
                    repository, migrator, worker, SwingUtilities::invokeLater,
                    VaultClock.system(), scheduler, idleMinutes);
            return new Components(service, new SecureClipboard(scheduler));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to initialize secure vault", error);
        }
    }

    private static int validIdleMinutes(int value) {
        return value == 1 || value == 5 || value == 10 || value == 30 ? value : 5;
    }

    public static final class Components {
        private final VaultService service;
        private final SecureClipboard clipboard;

        private Components(VaultService service, SecureClipboard clipboard) {
            this.service = service;
            this.clipboard = clipboard;
        }

        public VaultService getService() {
            return service;
        }

        public SecureClipboard getClipboard() {
            return clipboard;
        }
    }
}
