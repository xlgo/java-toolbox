package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApplicationPathsTest {
    @TempDir
    Path temp;

    @Test
    void resolvesWindowsAppDataAndLegacyWorkingFiles() {
        Map<String, String> env = new HashMap<>();
        env.put("APPDATA", "C:\\Users\\dev\\AppData\\Roaming");

        ApplicationPaths paths = ApplicationPaths.resolve(
                "Windows 11", "C:\\Users\\dev", env, Paths.get("D:\\portable"));

        assertEquals("C:/Users/dev/AppData/Roaming/JavaToolbox",
                portable(paths.getDataDirectory()));
        assertEquals("D:/portable/toolbox-passwords.enc",
                portable(paths.getLegacyPasswordFile()));
        assertEquals("D:/portable/toolbox-config.properties",
                portable(paths.getLegacyConfigFile()));
    }

    @Test
    void resolvesWindowsHomeFallbackAndAllManagedFiles() {
        ApplicationPaths paths = ApplicationPaths.resolve(
                "Windows 10", "C:\\Users\\dev", Collections.<String, String>emptyMap(),
                Paths.get("D:\\portable"));

        assertEquals("C:/Users/dev/.java-toolbox", portable(paths.getDataDirectory()));
        assertEquals("C:/Users/dev/.java-toolbox/toolbox-config.properties",
                portable(paths.getConfigFile()));
        assertEquals("C:/Users/dev/.java-toolbox/backups",
                portable(paths.getBackupDirectory()));
        assertEquals("C:/Users/dev/.java-toolbox/toolbox-vault.json.enc",
                portable(paths.getVaultFile()));
        assertEquals("C:/Users/dev/.java-toolbox/toolbox-vault.lock",
                portable(paths.getLockFile()));
    }

    @Test
    void resolvesMacApplicationSupportDirectory() {
        ApplicationPaths paths = ApplicationPaths.resolve(
                "Mac OS X", "/Users/dev", Collections.<String, String>emptyMap(),
                Paths.get("/Applications/JavaToolbox"));

        Path data = Paths.get("/Users/dev/Library/Application Support/JavaToolbox");
        assertEquals(data, paths.getDataDirectory());
        assertEquals(data.resolve("toolbox-config.properties"), paths.getConfigFile());
    }

    @Test
    void resolvesLinuxXdgDirectories() {
        Map<String, String> env = new HashMap<>();
        env.put("XDG_DATA_HOME", "/tmp/data");
        env.put("XDG_CONFIG_HOME", "/tmp/config");

        ApplicationPaths paths = ApplicationPaths.resolve(
                "Linux", "/home/dev", env, Paths.get("/opt/toolbox"));

        assertEquals(Paths.get("/tmp/data/java-toolbox"), paths.getDataDirectory());
        assertEquals(Paths.get("/tmp/config/java-toolbox/toolbox-config.properties"),
                paths.getConfigFile());
        assertEquals(Paths.get("/tmp/data/java-toolbox/toolbox-vault.json.enc"),
                paths.getVaultFile());
    }

    @Test
    void resolvesUnixHomeFallbackDirectories() {
        ApplicationPaths paths = ApplicationPaths.resolve(
                "FreeBSD", "/home/dev", Collections.<String, String>emptyMap(),
                Paths.get("/opt/toolbox"));

        assertEquals(Paths.get("/home/dev/.local/share/java-toolbox"),
                paths.getDataDirectory());
        assertEquals(Paths.get("/home/dev/.config/java-toolbox/toolbox-config.properties"),
                paths.getConfigFile());
    }

    @Test
    void relativeEnvironmentRootsFallBackToAbsoluteHome() {
        Map<String, String> windowsEnv = new HashMap<>();
        windowsEnv.put("APPDATA", "relative/appdata");
        ApplicationPaths windowsPaths = ApplicationPaths.resolve(
                "Windows 11", "C:\\Users\\dev", windowsEnv, Paths.get("D:\\portable"));
        assertEquals("C:/Users/dev/.java-toolbox",
                portable(windowsPaths.getDataDirectory()));

        Map<String, String> linuxEnv = new HashMap<>();
        linuxEnv.put("XDG_DATA_HOME", "relative/data");
        linuxEnv.put("XDG_CONFIG_HOME", "relative/config");
        ApplicationPaths linuxPaths = ApplicationPaths.resolve(
                "Linux", "/home/dev", linuxEnv, Paths.get("/opt/toolbox"));
        assertEquals("/home/dev/.local/share/java-toolbox",
                portable(linuxPaths.getDataDirectory()));
        assertEquals("/home/dev/.config/java-toolbox/toolbox-config.properties",
                portable(linuxPaths.getConfigFile()));
    }

    @Test
    void rejectsResolutionWhenNoAbsoluteRootIsAvailable() {
        Map<String, String> environment = new HashMap<>();
        environment.put("XDG_DATA_HOME", "relative/data");
        environment.put("XDG_CONFIG_HOME", "relative/config");

        assertThrows(IllegalArgumentException.class, () -> ApplicationPaths.resolve(
                "Linux", "", environment, temp));
        assertThrows(IllegalArgumentException.class, () -> ApplicationPaths.resolve(
                "Windows 11", "relative/home",
                Collections.singletonMap("APPDATA", "relative/appdata"), temp));
    }

    @Test
    void systemDefaultUsesOnlyAnAbsoluteConfiguredTestRoot() {
        String previous = System.getProperty(ApplicationPaths.CONFIG_ROOT_PROPERTY);
        Path isolatedRoot = temp.resolve("isolated-profile").toAbsolutePath();
        try {
            System.setProperty(ApplicationPaths.CONFIG_ROOT_PROPERTY, isolatedRoot.toString());
            ApplicationPaths paths = ApplicationPaths.systemDefault();
            assertEquals(isolatedRoot, paths.getDataDirectory());
            assertEquals(isolatedRoot.resolve("toolbox-config.properties"),
                    paths.getConfigFile());
            assertEquals(isolatedRoot.resolve("toolbox-config.properties"),
                    paths.getLegacyConfigFile());
            assertEquals(isolatedRoot.resolve("toolbox-passwords.enc"),
                    paths.getLegacyPasswordFile());

            System.setProperty(ApplicationPaths.CONFIG_ROOT_PROPERTY, "relative-profile");
            assertThrows(IllegalArgumentException.class, ApplicationPaths::systemDefault);
        } finally {
            if (previous == null) {
                System.clearProperty(ApplicationPaths.CONFIG_ROOT_PROPERTY);
            } else {
                System.setProperty(ApplicationPaths.CONFIG_ROOT_PROPERTY, previous);
            }
        }
    }

    @Test
    void createsPrivateDirectoriesWherePosixPermissionsAreSupported() throws Exception {
        ApplicationPaths paths = ApplicationPaths.resolve(
                System.getProperty("os.name"), temp.toString(),
                Collections.<String, String>emptyMap(), temp);
        paths.createPrivateDirectories();

        if (Files.getFileStore(paths.getDataDirectory())
                .supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(paths.getDataDirectory()));
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(paths.getConfigFile().getParent()));
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(paths.getBackupDirectory()));
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
