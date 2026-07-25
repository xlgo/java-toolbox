package com.aqishi.toolbox.util;

import com.aqishi.toolbox.vault.ApplicationPaths;
import com.aqishi.toolbox.vault.AtomicFiles;

/** Keeps UI tests isolated from the real user profile. */
public final class ConfigManagerTestSupport {
    private ConfigManagerTestSupport() {
    }

    public static AutoCloseable install(ApplicationPaths paths) {
        ConfigManager.Initialization replacement =
                ConfigManager.initialize(paths, new AtomicFiles());
        ConfigManager.Initialization previous = ConfigManager.install(replacement);
        return () -> ConfigManager.restore(previous);
    }

    public static boolean hasInitialization() {
        return ConfigManager.hasInitialization();
    }
}
