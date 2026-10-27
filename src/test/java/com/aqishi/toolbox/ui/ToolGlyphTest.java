package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具图标覆盖测试。
 *
 * <p>76 个工具都必须能拿到图标——新增工具时如果忘了登记字形，也应回退到通用图标
 * 而不是空白。这里同时保证字形足够多样，不至于一屏磁贴全是同一个符号。</p>
 */
class ToolGlyphTest {

    @Test
    void everyCatalogToolGetsARenderableIcon() {
        int checked = 0;
        for (ToolDescriptor descriptor : ToolCatalog.descriptors()) {
            Icon icon = ToolGlyph.of(descriptor.getId(), descriptor.getCategoryId(), 48);
            assertNotNull(icon, descriptor.getId());
            assertEquals(48, icon.getIconWidth());
            assertEquals(48, icon.getIconHeight());
            assertTrue(paintsSomething(icon), "图标没有画出任何像素: " + descriptor.getId());
            checked++;
        }
        assertTrue(checked >= 70, "目录规模异常，仅检查了 " + checked + " 个工具");
    }

    @Test
    void glyphsAreDiverseEnough() {
        Set<String> names = new HashSet<>();
        for (ToolDescriptor descriptor : ToolCatalog.descriptors()) {
            names.add(ToolGlyph.glyphNameOf(descriptor.getId()));
        }
        assertTrue(names.size() >= 20, "字形种类过少，磁贴会看起来千篇一律: " + names.size());
    }

    @Test
    void unknownToolFallsBackInsteadOfReturningNull() {
        assertNotNull(ToolGlyph.of("brand.new.tool", "codec", 32));
        assertTrue(paintsSomething(ToolGlyph.of("brand.new.tool", "codec", 32)));
    }

    @Test
    void sameCategoryToolsUseDifferentGlyphs() {
        assertEquals("BRACES", ToolGlyph.glyphNameOf("json.format"));
        assertEquals("REGEX", ToolGlyph.glyphNameOf("regex.tester"));
        assertEquals("TEXT", ToolGlyph.glyphNameOf("string.tool"));
    }

    private static boolean paintsSomething(Icon icon) {
        BufferedImage image = new BufferedImage(
                icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        try {
            icon.paintIcon(null, graphics, 0, 0);
        } finally {
            graphics.dispose();
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                // 图标底块是半透明以上的填充，任何非全透明像素都算已绘制
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    return true;
                }
            }
        }
        return false;
    }
}
