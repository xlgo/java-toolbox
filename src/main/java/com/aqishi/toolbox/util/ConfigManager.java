package com.aqishi.toolbox.util;

import java.io.*;
import java.util.Properties;

/**
 * 本地配置持久化管理器。
 * 将主题、语言、窗口大小及坐标保存到本地 `toolbox-config.properties` 文件中。
 */
public final class ConfigManager {

    private static final String CONFIG_FILE = "toolbox-config.properties";
    private static final Properties props = new Properties();

    static {
        load();
    }

    private ConfigManager() {
    }

    public static synchronized void load() {
        File file = new File(CONFIG_FILE);
        if (file.exists()) {
            try (InputStream is = new FileInputStream(file)) {
                props.load(is);
            } catch (IOException ignored) {
            }
        }
    }

    public static synchronized void save() {
        try (OutputStream os = new FileOutputStream(CONFIG_FILE)) {
            props.store(os, "Java Toolbox Configuration");
        } catch (IOException ignored) {
        }
    }

    public static synchronized String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public static synchronized void set(String key, String val) {
        props.setProperty(key, val);
    }

    public static synchronized int getInt(String key, int def) {
        try {
            String val = props.getProperty(key);
            if (val != null) {
                return Integer.parseInt(val);
            }
        } catch (NumberFormatException ignored) {
        }
        return def;
    }

    public static synchronized void setInt(String key, int val) {
        props.setProperty(key, String.valueOf(val));
    }
}
