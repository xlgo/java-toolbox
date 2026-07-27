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
        Path absTemp = temp.toAbsolutePath();
        Map<String, String> environment = new HashMap<>();
        environment.put("APPDATA", absTemp.resolve("data").toString());
        environment.put("XDG_DATA_HOME", absTemp.resolve("data").toString());
        environment.put("XDG_CONFIG_HOME", absTemp.resolve("config").toString());
        paths = ApplicationPaths.resolve(
                System.getProperty("os.name"), absTemp.toString(), environment, absTemp.resolve("legacy"));
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
