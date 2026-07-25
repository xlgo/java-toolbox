package com.aqishi.toolbox.vault;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resolves all application-owned files independently of the launch directory.
 * Legacy paths deliberately remain rooted in that directory for migration only.
 */
public final class ApplicationPaths {
    public static final String CONFIG_ROOT_PROPERTY = "java.toolbox.configRoot";
    private static final String CONFIG_FILE_NAME = "toolbox-config.properties";
    private static final String VAULT_FILE_NAME = "toolbox-vault.json.enc";
    private static final String LOCK_FILE_NAME = "toolbox-vault.lock";
    private static final String LEGACY_PASSWORD_FILE_NAME = "toolbox-passwords.enc";

    private final Path dataDirectory;
    private final Path configDirectory;
    private final Path workingDirectory;

    private ApplicationPaths(Path dataDirectory, Path configDirectory, Path workingDirectory) {
        this.dataDirectory = dataDirectory;
        this.configDirectory = configDirectory;
        this.workingDirectory = workingDirectory;
    }

    public static ApplicationPaths systemDefault() {
        Path workingDirectory = Paths.get("").toAbsolutePath();
        String configuredRoot = nonBlank(System.getProperty(CONFIG_ROOT_PROPERTY));
        if (configuredRoot != null) {
            Path root = Paths.get(configuredRoot);
            if (!root.isAbsolute()) {
                throw new IllegalArgumentException(
                        CONFIG_ROOT_PROPERTY + " must be an absolute path");
            }
            return new ApplicationPaths(root, root, root);
        }
        return resolve(
                System.getProperty("os.name", ""),
                System.getProperty("user.home", ""),
                System.getenv(),
                workingDirectory);
    }

    public static ApplicationPaths resolve(
            String osName, String userHome, Map<String, String> environment,
            Path workingDirectory) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(userHome, "userHome");
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(workingDirectory, "workingDirectory");

        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        boolean windows = normalizedOs.contains("win");
        Path home = absoluteRoot(userHome, windows);
        Path data;
        Path config;

        if (windows) {
            Path appData = absoluteRoot(environment.get("APPDATA"), true);
            data = appData == null
                    ? requireHome(home, "APPDATA").resolve(".java-toolbox")
                    : appData.resolve("JavaToolbox");
            config = data;
        } else if (normalizedOs.contains("mac")) {
            data = requireHome(home, "user.home")
                    .resolve("Library").resolve("Application Support").resolve("JavaToolbox");
            config = data;
        } else {
            Path xdgData = absoluteRoot(environment.get("XDG_DATA_HOME"), false);
            Path xdgConfig = absoluteRoot(environment.get("XDG_CONFIG_HOME"), false);
            data = (xdgData == null
                    ? requireHome(home, "XDG_DATA_HOME").resolve(".local").resolve("share")
                    : xdgData).resolve("java-toolbox");
            config = (xdgConfig == null
                    ? requireHome(home, "XDG_CONFIG_HOME").resolve(".config")
                    : xdgConfig).resolve("java-toolbox");
        }

        return new ApplicationPaths(data, config, workingDirectory);
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static Path absoluteRoot(String value, boolean windows) {
        String candidate = nonBlank(value);
        if (candidate == null) {
            return null;
        }
        boolean absolute;
        if (windows) {
            absolute = candidate.length() >= 3
                    && Character.isLetter(candidate.charAt(0))
                    && candidate.charAt(1) == ':'
                    && (candidate.charAt(2) == '\\' || candidate.charAt(2) == '/')
                    || candidate.startsWith("\\\\")
                    || candidate.startsWith("//");
        } else {
            absolute = candidate.startsWith("/");
        }
        return absolute ? Paths.get(candidate) : null;
    }

    private static Path requireHome(Path home, String unavailableRoot) {
        if (home == null) {
            throw new IllegalArgumentException(
                    unavailableRoot + " is not absolute and no absolute user.home is available");
        }
        return home;
    }

    public Path getConfigFile() {
        return configDirectory.resolve(CONFIG_FILE_NAME);
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public Path getBackupDirectory() {
        return dataDirectory.resolve("backups");
    }

    public Path getVaultFile() {
        return dataDirectory.resolve(VAULT_FILE_NAME);
    }

    public Path getLockFile() {
        return dataDirectory.resolve(LOCK_FILE_NAME);
    }

    public Path getLegacyConfigFile() {
        return workingDirectory.resolve(CONFIG_FILE_NAME);
    }

    public Path getLegacyPasswordFile() {
        return workingDirectory.resolve(LEGACY_PASSWORD_FILE_NAME);
    }

    /**
     * Creates application directories and tightens POSIX permissions when supported.
     * Directory creation failures are fatal; permission-tightening failures are warnings.
     */
    public List<IOException> createPrivateDirectories() throws IOException {
        Set<Path> directories = new LinkedHashSet<>();
        directories.add(dataDirectory);
        directories.add(configDirectory);
        directories.add(getBackupDirectory());

        for (Path directory : directories) {
            Files.createDirectories(directory);
        }

        List<IOException> warnings = new ArrayList<>();
        for (Path directory : directories) {
            try {
                FileStore store = Files.getFileStore(directory);
                if (store.supportsFileAttributeView("posix")) {
                    Files.setPosixFilePermissions(
                            directory, PosixFilePermissions.fromString("rwx------"));
                }
            } catch (IOException error) {
                warnings.add(error);
            } catch (SecurityException error) {
                warnings.add(new IOException(
                        "Unable to restrict permissions for " + directory, error));
            }
        }
        return Collections.unmodifiableList(warnings);
    }
}
