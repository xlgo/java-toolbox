package com.aqishi.toolbox.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class I18nResourceTest {
    private static final String[] CALLBACK_RULE_KEYS = {
            "callback.mock.rules", "callback.mock.add", "callback.mock.edit",
            "callback.mock.delete", "callback.mock.moveUp", "callback.mock.moveDown",
            "callback.mock.fallback", "callback.mock.method", "callback.mock.path",
            "callback.mock.pathMode", "callback.mock.condition",
            "callback.mock.conditionAnd",
            "callback.mock.source.query", "callback.mock.source.header",
            "callback.mock.source.form", "callback.mock.source.json",
            "callback.mock.operator.equals", "callback.mock.operator.exists",
            "callback.mock.operator.contains", "callback.mock.operator.regex",
            "callback.mock.pathMode.exact", "callback.mock.pathMode.prefix",
            "callback.mock.pathMode.regex", "callback.mock.save", "callback.mock.cancel",
            "callback.mock.loadWarning", "callback.mock.saveFailed"};

    @Test
    void definesPinGameLabelInEverySupportedResource() throws Exception {
        assertPinGameLabel("messages.properties", "见缝插针");
        assertPinGameLabel("messages_zh_CN.properties", "见缝插针");
        assertPinGameLabel("messages_en_US.properties", "Pin Game");
    }

    @Test
    void definesCallbackMockRuleLabelsInEverySupportedResource()
            throws Exception {
        assertProperty("messages.properties", "callback.mock.rules", "响应规则");
        assertProperty("messages_zh_CN.properties",
                "callback.mock.operator.contains", "包含");
        assertProperty("messages_en_US.properties",
                "callback.mock.operator.contains", "Contains");
        assertKeys("messages.properties");
        assertKeys("messages_zh_CN.properties");
        assertKeys("messages_en_US.properties");
    }

    @Test
    void keepsAllSupportedResourceKeySetsInSync() throws Exception {
        Set<Object> baseline = readProperties("messages.properties").keySet();
        for (String resource : new String[]{"messages_zh_CN.properties", "messages_en_US.properties"}) {
            assertEquals(new HashSet<>(baseline),
                    new HashSet<>(readProperties(resource).keySet()), resource);
        }
    }

    private static void assertPinGameLabel(String resourceName, String expected) throws Exception {
        assertProperty(resourceName, "tool.pingame", expected);
    }

    private static void assertProperty(String resourceName, String key,
                                       String expected) throws Exception {
        Properties properties = readProperties(resourceName);
        assertEquals(expected, properties.getProperty(key), resourceName);
    }

    private static void assertKeys(String resourceName) throws Exception {
        Properties properties = readProperties(resourceName);
        for (String key : CALLBACK_RULE_KEYS) {
            assertNotNull(properties.getProperty(key), resourceName + ": " + key);
        }
    }

    private static Properties readProperties(String resourceName) throws Exception {
        String resourcePath = "com/aqishi/toolbox/util/" + resourceName;
        Properties properties = new Properties();
        try (InputStream stream = I18nResourceTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(stream, resourcePath);
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
