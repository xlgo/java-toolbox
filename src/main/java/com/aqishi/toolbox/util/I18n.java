package com.aqishi.toolbox.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * 国际化（i18n）管理器。
 * 根据 `ConfigManager` 中保存的语言环境加载对应的 `messages` 资源文件。
 */
public final class I18n {

    private static ResourceBundle bundle;
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
        // 使用 UTF-8 Control 或者是默认加载机制。由于 JDK 8 的 ResourceBundle 默认用 ISO-8859-1 读取 properties，
        // 我们通过转码或 UTF-8 控制来读取，这里使用标准的 ResourceBundle.Control 支持 UTF-8 读取
        bundle = ResourceBundle.getBundle("messages", currentLocale, new ResourceBundle.Control() {
            @Override
            public List<String> getFormats(String baseName) {
                return FORMAT_PROPERTIES;
            }

            @Override
            public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload)
                    throws IllegalAccessException, InstantiationException, IOException {
                String bundleName = toBundleName(baseName, locale);
                String resourceName = toResourceName(bundleName, "properties");
                ResourceBundle bundle = null;
                InputStream stream = null;
                if (reload) {
                    URL url = loader.getResource(resourceName);
                    if (url != null) {
                        URLConnection connection = url.openConnection();
                        if (connection != null) {
                            connection.setUseCaches(false);
                            stream = connection.getInputStream();
                        }
                    }
                } else {
                    stream = loader.getResourceAsStream(resourceName);
                }
                if (stream != null) {
                    try {
                        // 以 UTF-8 编码读取字符流
                        bundle = new PropertyResourceBundle(new InputStreamReader(stream, StandardCharsets.UTF_8));
                    } finally {
                        stream.close();
                    }
                }
                return bundle;
            }
        });
    }

    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (Exception e) {
            return key;
        }
    }

    public static String get(String key, Object... args) {
        try {
            String val = bundle.getString(key);
            return MessageFormat.format(val, args);
        } catch (Exception e) {
            return key;
        }
    }

    public static Locale getLocale() {
        return currentLocale;
    }
}
