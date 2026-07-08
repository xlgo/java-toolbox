package com.aqishi.toolbox.util;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;

/**
 * 国际化（i18n）管理器。
 * 直接基于 ClassLoader 读取 properties 字符流，避免 JAR 包环境下的 ResourceBundle 跨类加载器读取失败问题。
 */
public final class I18n {

    private static final Properties props = new Properties();
    private static Locale currentLocale;

    static {
        init();
    }

    private I18n() {
    }

    public static synchronized void init() {
        String lang = ConfigManager.get("locale", "zh_CN");
        if ("en_US".equals(lang)) {
            currentLocale = new Locale("en", "US");
        } else {
            currentLocale = new Locale("zh", "CN");
        }
        Locale.setDefault(currentLocale);

        props.clear();
        String resourceName = "messages_" + lang + ".properties";
        
        // 尝试加载语言对应的资源文件
        try (InputStream is = I18n.class.getResourceAsStream(resourceName)) {
            if (is != null) {
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
            } else {
                // 回退到 messages.properties
                try (InputStream defIs = I18n.class.getResourceAsStream("messages.properties")) {
                    if (defIs != null) {
                        props.load(new InputStreamReader(defIs, StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to load i18n resources: " + e.getMessage());
        }
    }

    public static String get(String key) {
        String val = props.getProperty(key);
        return val != null ? val : key;
    }

    public static String get(String key, Object... args) {
        String val = props.getProperty(key);
        if (val == null) {
            return key;
        }
        try {
            return MessageFormat.format(val, args);
        } catch (Exception e) {
            return val;
        }
    }

    public static Locale getLocale() {
        return currentLocale;
    }
}
