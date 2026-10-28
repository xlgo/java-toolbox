package com.aqishi.toolbox.ui.kit;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 皮肤配色规则测试。
 *
 * <p>三档皮肤的差别必须体现在「卡片底色如何从分类品牌色推导」上：浅色提亮、
 * 暗黑压暗、护眼转暖。这些是纯函数，可以直接断言，不需要渲染窗口。</p>
 */
class SkinTest {

    @Test
    void everySkinHasBilingualLabels() {
        com.aqishi.toolbox.util.I18n.init();
        for (Skin skin : Skin.values()) {
            assertNotNull(skin.labelKey());
            assertTrue(!skin.label().isEmpty());
            assertTrue(!skin.labelEn().isEmpty());
            assertTrue(!skin.label().equals(skin.labelKey()), "皮肤名必须有本地化文案");
        }
        assertEquals(6, Skin.values().length, "效果页五档主题，外加护眼模式");
    }

    @Test
    void categoriesKeepDistinctHues() {
        String[] categories = {
                "security", "codec", "network", "data", "cloud",
                "system", "generation", "compute", "diagram", "monitor"
        };
        Set<Integer> hues = new HashSet<>();
        for (String category : categories) {
            Color seed = Skin.seed(category);
            hues.add(Math.round(hue(seed) * 360));
        }
        assertEquals(categories.length, hues.size(), "每个分类应有独立色相");
    }

    @Test
    void toolsInsideOneCategoryShiftHueButStayClose() {
        float base = hue(Skin.seed("codec"));
        Set<Integer> variants = new HashSet<>();
        for (String tool : new String[]{"json.format", "xml.format", "sql.format", "text.diff"}) {
            float hue = hue(Skin.seed("codec", tool));
            variants.add(Math.round(hue * 360));
            float distance = Math.abs(hue - base);
            distance = Math.min(distance, 1f - distance);
            assertTrue(distance <= 0.075f,
                    "工具色相偏移应控制在 ±27° 内，实际 " + (distance * 360));
        }
        assertTrue(variants.size() > 1, "同组磁贴不应完全同色");
    }

    @Test
    void lightTilesAreLighterThanDarkTiles() {
        Color seed = Skin.seed("security");
        Color light = Skin.LIGHT.tileFrom(seed);
        Color dark = Skin.DARK.tileFrom(seed);
        assertTrue(luminance(light) > 0.7, "浅色皮肤卡片应接近白");
        assertTrue(luminance(dark) < 0.25, "暗黑皮肤卡片应接近黑");
    }

    @Test
    void darkTilesKeepAHintOfTheCategoryColor() {
        Color seed = Skin.seed("generation");
        Color dark = Skin.DARK.tileFrom(seed);
        // 纯黑会失去分类感：至少一个通道要明显高于最低通道
        int spread = Math.max(dark.getRed(), Math.max(dark.getGreen(), dark.getBlue()))
                - Math.min(dark.getRed(), Math.min(dark.getGreen(), dark.getBlue()));
        assertTrue(spread >= 3, "暗色磁贴仍应带一点分类色相，spread=" + spread);
    }

    @Test
    void eyeCareSuppressesBlueComparedToLight() {
        Color seed = Skin.seed("codec");
        Color light = Skin.LIGHT.tileFrom(seed);
        Color warm = Skin.EYE_CARE.tileFrom(seed);
        assertTrue(warm.getBlue() <= light.getBlue(),
                "护眼卡片应压低蓝色: light=" + light + " warm=" + warm);
        assertTrue(warm.getRed() >= warm.getBlue(), "护眼卡片应偏暖");
        assertTrue(saturation(warm) < saturation(light) + 0.35f, "护眼卡片不应更刺眼");
    }

    @Test
    void glyphIsMoreSaturatedThanTheCardItSitsOn() {
        Color seed = Skin.seed("network");
        for (Skin skin : Skin.values()) {
            assertNotEquals(skin.tileFrom(seed), skin.glyphFrom(seed));
            assertTrue(luminance(skin.tileFrom(seed)) > luminance(skin.glyphFrom(seed))
                            || skin.isDark(),
                    skin + " 的图标应比磁贴底更深，形成视觉锚点");
        }
    }

    // ---------------------------------------------------------------- 色彩工具

    private static float hue(Color color) {
        return Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null)[0];
    }

    private static float saturation(Color color) {
        return Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null)[1];
    }

    private static double luminance(Color color) {
        return (0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue()) / 255.0;
    }
}
