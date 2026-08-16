package com.aqishi.toolbox.util;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class I18nResourceTest {

    @Test
    void definesPinGameLabelInEverySupportedResource() throws Exception {
        assertPinGameLabel("messages.properties", "见缝插针");
        assertPinGameLabel("messages_zh_CN.properties", "见缝插针");
        assertPinGameLabel("messages_en_US.properties", "Pin Game");
    }

    private static void assertPinGameLabel(String resourceName, String expected) throws Exception {
        String resourcePath = "com/aqishi/toolbox/util/" + resourceName;
        Properties properties = new Properties();
        try (InputStream stream = I18nResourceTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(stream, resourcePath);
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }

        assertEquals(expected, properties.getProperty("tool.pingame"), resourceName);
    }
}
