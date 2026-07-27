package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.vault.ApplicationPaths;
import com.aqishi.toolbox.vault.AtomicFiles;
import com.aqishi.toolbox.vault.LegacyVaultMigrator;
import com.aqishi.toolbox.vault.VaultClock;
import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultFileLock;
import com.aqishi.toolbox.vault.VaultRepository;
import com.aqishi.toolbox.vault.VaultScheduler;
import com.aqishi.toolbox.vault.VaultService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VaultAccessPanelTest {
    @TempDir
    Path temp;

    @Test
    void followsTheSharedVaultState() throws Exception {
        Components components = components();
        try {
            VaultAccessPanel[] panel = new VaultAccessPanel[1];
            SwingUtilities.invokeAndWait(() ->
                    panel[0] = new VaultAccessPanel(components.service, new JPanel()));
            assertEquals("SETUP", panel[0].getVisibleCardName());

            components.service.create("master".toCharArray()).get();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals("CONTENT", panel[0].getVisibleCardName());

            components.service.lock();
            SwingUtilities.invokeAndWait(() -> { });
            assertEquals("UNLOCK", panel[0].getVisibleCardName());
            panel[0].dispose();
        } finally {
            components.service.close();
        }
    }

    private Components components() throws Exception {
        Path absTemp = temp.toAbsolutePath();
        Map<String, String> environment = new HashMap<>();
        environment.put("APPDATA", absTemp.resolve("data").toString());
        environment.put("XDG_DATA_HOME", absTemp.resolve("data").toString());
        environment.put("XDG_CONFIG_HOME", absTemp.resolve("config").toString());
        ApplicationPaths paths = ApplicationPaths.resolve(
                System.getProperty("os.name"), absTemp.toString(), environment, absTemp.resolve("legacy"));
        paths.createPrivateDirectories();
        AtomicFiles files = new AtomicFiles();
        VaultCrypto crypto = new VaultCrypto();
        VaultFileLock lock = new VaultFileLock(paths.getLockFile());
        VaultRepository repository = new VaultRepository(paths, files, crypto, lock);
        LegacyVaultMigrator migrator = new LegacyVaultMigrator(
                paths, repository, files, crypto);
        VaultScheduler scheduler = VaultScheduler.daemon();
        VaultService service = new VaultService(
                repository, migrator, Runnable::run, Runnable::run,
                VaultClock.system(), scheduler, 5);
        return new Components(service);
    }

    private static final class Components {
        private final VaultService service;

        private Components(VaultService service) {
            this.service = service;
        }
    }
}
