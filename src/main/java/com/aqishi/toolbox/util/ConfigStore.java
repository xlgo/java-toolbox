package com.aqishi.toolbox.util;

import com.aqishi.toolbox.vault.AtomicFiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/** Path-aware persistence for non-sensitive application preferences. */
public final class ConfigStore {
    private static final String SENSITIVE_TOTP_KEY = "totp.accounts";

    private final Path configFile;
    private final Path legacyConfigFile;
    private final AtomicFiles atomicFiles;
    private final Properties properties = new Properties();

    public ConfigStore(Path configFile, Path legacyConfigFile, AtomicFiles atomicFiles) {
        this.configFile = Objects.requireNonNull(configFile, "configFile");
        this.legacyConfigFile = Objects.requireNonNull(legacyConfigFile, "legacyConfigFile");
        this.atomicFiles = Objects.requireNonNull(atomicFiles, "atomicFiles");
    }

    public synchronized void load() throws IOException {
        Properties loaded = new Properties();
        Path source = Files.exists(configFile) ? configFile : legacyConfigFile;
        if (Files.exists(source)) {
            try (InputStream input = Files.newInputStream(source)) {
                loaded.load(input);
            }
        }
        loaded.remove(SENSITIVE_TOTP_KEY);
        properties.clear();
        properties.putAll(loaded);
    }

    public synchronized void save() throws IOException {
        properties.remove(SENSITIVE_TOTP_KEY);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.store(output, "Java Toolbox Configuration");
        atomicFiles.write(configFile, output.toByteArray());
    }

    public synchronized String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public synchronized void set(String key, String value) {
        if (SENSITIVE_TOTP_KEY.equals(key)) {
            properties.remove(key);
            return;
        }
        properties.setProperty(key, value);
    }

    public synchronized int getInt(String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public synchronized void setInt(String key, int value) {
        properties.setProperty(key, String.valueOf(value));
    }

    public synchronized void remove(String key) {
        properties.remove(key);
    }
}
