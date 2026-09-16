package com.aqishi.toolbox.catalog;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录与本地化资源的契约：每个分类和工具都必须在三份资源里有名字。
 *
 * <p>缺键不会让程序崩，{@code I18n.get} 会把键名原样显示出来——导航栏里就会出现
 * {@code tool.xpath.tool} 这种东西，而且只在切到某个语言时才看得见。
 * 新增工具忘记补键是最容易漏的一步，所以在这里卡住。</p>
 */
class ToolCatalogLabelsTest {

    private static final String[] RESOURCES = {
            "messages.properties", "messages_zh_CN.properties", "messages_en_US.properties"};

    /** 目录与工厂一一对应；不匹配时 {@code createDefault()} 会直接抛异常。 */
    @Test
    void defaultRegistryPairsEveryDescriptorWithAFactory() {
        ToolRegistry registry = ToolRegistry.createDefault();

        for (ToolDescriptor descriptor : ToolCatalog.descriptors()) {
            assertNotNull(registry.find(descriptor.getId()), descriptor.getId());
        }
    }

    @Test
    void everyToolBelongsToADeclaredCategory() {
        List<String> categoryIds = new ArrayList<>();
        for (ToolCategory category : ToolCatalog.categories()) {
            categoryIds.add(category.getId());
        }

        for (ToolDescriptor descriptor : ToolCatalog.descriptors()) {
            assertTrue(categoryIds.contains(descriptor.getCategoryId()),
                    descriptor.getId() + " 指向了未声明的分类 " + descriptor.getCategoryId());
        }
    }

    @Test
    void everyCategoryAndToolHasALabelInEveryResource() throws Exception {
        List<String> missing = new ArrayList<>();
        for (String resource : RESOURCES) {
            Properties properties = readProperties(resource);
            for (ToolCategory category : ToolCatalog.categories()) {
                if (isBlank(properties.getProperty(category.getLabelKey()))) {
                    missing.add(resource + ": " + category.getLabelKey());
                }
            }
            for (ToolDescriptor descriptor : ToolCatalog.descriptors()) {
                if (isBlank(properties.getProperty(descriptor.getLabelKey()))) {
                    missing.add(resource + ": " + descriptor.getLabelKey());
                }
            }
        }
        assertTrue(missing.isEmpty(), "缺少工具或分类标签：" + missing);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static Properties readProperties(String resourceName) throws Exception {
        String path = "com/aqishi/toolbox/util/" + resourceName;
        Properties properties = new Properties();
        try (InputStream stream = ToolCatalogLabelsTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
