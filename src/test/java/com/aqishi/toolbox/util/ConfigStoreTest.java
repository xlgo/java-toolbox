package com.aqishi.toolbox.util;

import com.aqishi.toolbox.vault.AtomicFiles;
import com.aqishi.toolbox.vault.ApplicationPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {
    @TempDir
    Path temp;

    @Test
    void migratesOnlyNonSensitiveLegacyProperties() throws Exception {
        Path legacy = temp.resolve("legacy.properties");
        Path current = temp.resolve("config/toolbox-config.properties");
        Files.write(legacy, (
                "theme=Arc\n" +
                "locale=zh_CN\n" +
                "totp.accounts=[{secret:DO_NOT_COPY}]\n").getBytes(StandardCharsets.ISO_8859_1));

        ConfigStore store = new ConfigStore(current, legacy, new AtomicFiles());
        store.load();

        assertEquals("Arc", store.get("theme", ""));
        assertNull(store.get("totp.accounts", null));
        store.save();

        Properties written = new Properties();
        try (InputStream input = Files.newInputStream(current)) {
            written.load(input);
        }
        assertEquals("Arc", written.getProperty("theme"));
        assertFalse(written.containsKey("totp.accounts"));
        assertTrue(Files.exists(legacy), "ordinary config save must not delete migration source");
    }

    @Test
    void prefersCurrentConfigAndRemovesSensitivePropertyFromMemory() throws Exception {
        Path legacy = temp.resolve("legacy.properties");
        Path current = temp.resolve("config/toolbox-config.properties");
        Files.createDirectories(current.getParent());
        Files.write(legacy, "theme=Legacy\n".getBytes(StandardCharsets.ISO_8859_1));
        Files.write(current, (
                "theme=Current\n" +
                "totp.accounts=SECRET\n").getBytes(StandardCharsets.ISO_8859_1));

        ConfigStore store = new ConfigStore(current, legacy, new AtomicFiles());
        store.load();

        assertEquals("Current", store.get("theme", ""));
        assertNull(store.get("totp.accounts", null));
    }

    @Test
    void supportsTypedPreferencesAndRemoval() {
        ConfigStore store = new ConfigStore(
                temp.resolve("config.properties"), temp.resolve("legacy.properties"),
                new AtomicFiles());

        store.set("theme", "Arc");
        store.setInt("sidebar.width", 228);
        assertEquals("Arc", store.get("theme", ""));
        assertEquals(228, store.getInt("sidebar.width", 190));

        store.set("sidebar.width", "not-a-number");
        assertEquals(190, store.getInt("sidebar.width", 190));
        store.remove("theme");
        assertNull(store.get("theme", null));
    }

    @Test
    void rejectsSensitivePropertyWrites() throws Exception {
        Path current = temp.resolve("config.properties");
        ConfigStore store = new ConfigStore(
                current, temp.resolve("legacy.properties"), new AtomicFiles());

        store.set("theme", "Arc");
        store.set("totp.accounts", "DO_NOT_WRITE");
        store.save();

        Properties written = new Properties();
        try (InputStream input = Files.newInputStream(current)) {
            written.load(input);
        }
        assertEquals("Arc", written.getProperty("theme"));
        assertFalse(written.containsKey("totp.accounts"));
    }

    @Test
    void propagatesAtomicWriteFailure() {
        IOException failure = new IOException("simulated write failure");
        AtomicFiles failingFiles = new AtomicFiles() {
            @Override
            public void write(Path target, byte[] bytes) throws IOException {
                throw failure;
            }
        };
        ConfigStore store = new ConfigStore(
                temp.resolve("config.properties"), temp.resolve("legacy.properties"), failingFiles);

        IOException thrown = assertThrows(IOException.class, store::save);

        assertEquals(failure, thrown);
        assertFalse(Files.exists(temp.resolve("config.properties")));
    }

    @Test
    void configManagerInitializationCreatesPrivateDirectories() throws Exception {
        ApplicationPaths paths = ApplicationPaths.resolve(
                System.getProperty("os.name"), temp.toString(),
                Collections.<String, String>emptyMap(), temp);

        ConfigManager.Initialization initialization =
                ConfigManager.initialize(paths, new AtomicFiles());

        assertNull(initialization.getDirectoryError());
        assertTrue(initialization.getDirectoryWarnings().isEmpty());
        assertTrue(Files.isDirectory(paths.getDataDirectory()));
        assertTrue(Files.isDirectory(paths.getConfigFile().getParent()));
        assertTrue(Files.isDirectory(paths.getBackupDirectory()));
    }

    @Test
    void configManagerInitializationExposesDirectoryCreationFailure() throws Exception {
        Path blockedDataHome = temp.resolve("blocked-data-home");
        Files.createFile(blockedDataHome);
        String osName = System.getProperty("os.name");
        java.util.Map<String, String> environment = new java.util.HashMap<>();
        String home = blockedDataHome.toString();
        if (osName.toLowerCase(java.util.Locale.ROOT).contains("win")) {
            environment.put("APPDATA", blockedDataHome.toString());
            home = temp.toString();
        } else if (!osName.toLowerCase(java.util.Locale.ROOT).contains("mac")) {
            environment.put("XDG_DATA_HOME", blockedDataHome.toString());
            environment.put("XDG_CONFIG_HOME", temp.resolve("config-home").toString());
            home = temp.toString();
        }
        ApplicationPaths paths = ApplicationPaths.resolve(osName, home, environment, temp);

        ConfigManager.Initialization initialization =
                ConfigManager.initialize(paths, new AtomicFiles());

        assertTrue(initialization.getDirectoryError() instanceof IOException);
        assertTrue(initialization.getDirectoryWarnings().isEmpty());
    }

    @Test
    void temporarilyInstallsAndRestoresInitialization() throws Exception {
        ApplicationPaths paths = ApplicationPaths.resolve(
                System.getProperty("os.name"), temp.toString(),
                Collections.<String, String>emptyMap(), temp);
        boolean hadPreviousInitialization = ConfigManager.hasInitialization();
        ConfigManager.Initialization replacement =
                ConfigManager.initialize(paths, new AtomicFiles());
        ConfigManager.Initialization previous = ConfigManager.install(replacement);
        try {
            ConfigManager.set("test.scope", "temporary");
            assertTrue(ConfigManager.save());
            assertTrue(Files.exists(paths.getConfigFile()));
        } finally {
            ConfigManager.restore(previous);
        }

        assertEquals(hadPreviousInitialization, ConfigManager.hasInitialization());
    }

    @Test
    void configManagerReportsSaveFailureAndClearsItAfterSuccess() throws Exception {
        ApplicationPaths paths = ApplicationPaths.resolve(
                System.getProperty("os.name"), temp.toString(),
                Collections.<String, String>emptyMap(), temp);
        IOException failure = new IOException("simulated first save failure");
        AtomicFiles failOnce = new AtomicFiles() {
            private boolean first = true;

            @Override
            public void write(Path target, byte[] bytes) throws IOException {
                if (first) {
                    first = false;
                    throw failure;
                }
                super.write(target, bytes);
            }
        };
        ConfigManager.Initialization replacement = ConfigManager.initialize(paths, failOnce);
        ConfigManager.Initialization previous = ConfigManager.install(replacement);
        try {
            ConfigManager.set("theme", "Arc");
            assertFalse(ConfigManager.save());
            assertSame(failure, ConfigManager.getLastSaveError());

            assertTrue(ConfigManager.save());
            assertNull(ConfigManager.getLastSaveError());
        } finally {
            ConfigManager.restore(previous);
        }
    }
}
