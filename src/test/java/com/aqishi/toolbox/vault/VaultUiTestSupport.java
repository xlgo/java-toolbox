package com.aqishi.toolbox.vault;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Temporary production-like vault graph for Swing integration tests. */
public final class VaultUiTestSupport implements AutoCloseable {
    private final ApplicationPaths paths;
    private final VaultService service;
    private final SecureClipboard clipboard;

    public VaultUiTestSupport(Path temp) throws Exception {
        Map<String, String> environment = new HashMap<>();
        environment.put("APPDATA", temp.resolve("data").toString());
        paths = ApplicationPaths.resolve(
                "Windows 11", temp.toString(), environment, temp.resolve("legacy"));
        paths.createPrivateDirectories();
        AtomicFiles files = new AtomicFiles();
        VaultCrypto crypto = new VaultCrypto();
        VaultFileLock lock = new VaultFileLock(paths.getLockFile());
        VaultRepository repository = new VaultRepository(paths, files, crypto, lock);
        VaultScheduler scheduler = VaultScheduler.daemon();
        service = new VaultService(repository,
                new LegacyVaultMigrator(paths, repository, files, crypto),
                Runnable::run, Runnable::run, VaultClock.system(), scheduler, 5);
        clipboard = new SecureClipboard(scheduler);
    }

    public ApplicationPaths paths() {
        return paths;
    }

    public VaultService service() {
        return service;
    }

    public SecureClipboard clipboard() {
        return clipboard;
    }

    @Override
    public void close() {
        service.close();
    }
}
