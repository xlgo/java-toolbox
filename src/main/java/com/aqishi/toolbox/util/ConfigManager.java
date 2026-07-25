package com.aqishi.toolbox.util;

import com.aqishi.toolbox.vault.ApplicationPaths;
import com.aqishi.toolbox.vault.AtomicFiles;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 非敏感本地配置门面。
 */
public final class ConfigManager {
    private static IOException lastSaveError;
    private static Initialization currentInitialization;

    private ConfigManager() {
    }

    static Initialization initialize(ApplicationPaths paths, AtomicFiles atomicFiles) {
        List<IOException> warnings = Collections.emptyList();
        IOException directoryError = null;
        try {
            warnings = paths.createPrivateDirectories();
        } catch (IOException error) {
            directoryError = error;
        }

        ConfigStore configStore = new ConfigStore(
                paths.getConfigFile(), paths.getLegacyConfigFile(), atomicFiles);
        try {
            configStore.load();
        } catch (IOException ignored) {
            // Keep empty preferences. Task 1 exposes write failures through save().
        }
        return new Initialization(configStore, warnings, directoryError);
    }

    private static Initialization initialization() {
        if (currentInitialization == null) {
            currentInitialization =
                    initialize(ApplicationPaths.systemDefault(), new AtomicFiles());
        }
        return currentInitialization;
    }

    static synchronized Initialization install(Initialization replacement) {
        Initialization previous = currentInitialization;
        currentInitialization = replacement;
        lastSaveError = null;
        return previous;
    }

    static synchronized void restore(Initialization previous) {
        currentInitialization = previous;
        lastSaveError = null;
    }

    static synchronized boolean hasInitialization() {
        return currentInitialization != null;
    }

    public static synchronized void load() {
        try {
            initialization().store.load();
        } catch (IOException ignored) {
            // Keep the last valid in-memory preferences. Saves report their errors explicitly.
        }
    }

    public static synchronized boolean save() {
        Initialization state = initialization();
        if (state.directoryError != null) {
            lastSaveError = state.directoryError;
            return false;
        }
        try {
            state.store.save();
            lastSaveError = null;
            return true;
        } catch (IOException error) {
            lastSaveError = error;
            return false;
        }
    }

    public static synchronized String get(String key, String def) {
        return initialization().store.get(key, def);
    }

    public static synchronized void set(String key, String val) {
        initialization().store.set(key, val);
    }

    public static synchronized int getInt(String key, int def) {
        return initialization().store.getInt(key, def);
    }

    public static synchronized void setInt(String key, int val) {
        initialization().store.setInt(key, val);
    }

    public static synchronized void remove(String key) {
        initialization().store.remove(key);
    }

    public static synchronized IOException getLastSaveError() {
        return lastSaveError;
    }

    public static synchronized List<IOException> getDirectoryWarnings() {
        return initialization().directoryWarnings;
    }

    public static synchronized IOException getDirectoryError() {
        return initialization().directoryError;
    }

    static final class Initialization {
        private final ConfigStore store;
        private final List<IOException> directoryWarnings;
        private final IOException directoryError;

        private Initialization(
                ConfigStore store, List<IOException> directoryWarnings,
                IOException directoryError) {
            this.store = store;
            this.directoryWarnings = Collections.unmodifiableList(
                    new ArrayList<>(directoryWarnings));
            this.directoryError = directoryError;
        }

        List<IOException> getDirectoryWarnings() {
            return directoryWarnings;
        }

        IOException getDirectoryError() {
            return directoryError;
        }
    }
}
