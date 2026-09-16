package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.intellijthemes.FlatArcIJTheme;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThemeManagerAppearanceTest {

    @Test
    void coreThemesUseLayeredSurfacesAndReadableText() throws Exception {
        onEdt(() -> {
            for (FlatLaf laf : new FlatLaf[]{new FlatIntelliJLaf(), new FlatDarkLaf()}) {
                install(laf);
                assertEquals(laf.isDark(), Tokens.isDark());
                assertNotEquals(Tokens.surface(), Tokens.cardBackground());
                assertNotEquals(Tokens.surface(), Tokens.navigationBackground());
                assertTrue(contrast(Tokens.foreground(), Tokens.cardBackground()) > 7.0);
                assertTrue(contrast(Tokens.mutedForeground(), Tokens.cardBackground()) > 4.5);
                assertTrue(contrast(Tokens.selectionForeground(), Tokens.accentSoft()) > 4.5);
                assertTrue(contrast(UIManager.getColor("Button.default.foreground"),
                        UIManager.getColor("Button.default.background")) >= 4.5);
            }
        });
    }

    @Test
    void primaryAndGhostButtonsHaveRealFlatLafStylesAndKeyboardFocus() throws Exception {
        onEdt(() -> {
            install(new FlatIntelliJLaf());
            JButton primary = Buttons.primary("生成");
            JButton ghost = Buttons.ghost("复制");
            assertEquals("primary", primary.getClientProperty("FlatLaf.styleClass"));
            assertEquals(UIManager.getColor("Button.default.background"), primary.getBackground());
            assertEquals(UIManager.getColor("Button.default.foreground"), primary.getForeground());
            assertEquals("borderless", ghost.getClientProperty("JButton.buttonType"));
            assertTrue(primary.isFocusPainted());
            assertTrue(ghost.isFocusPainted());
            assertTrue(Fields.check("启用", true).isFocusPainted());
        });
    }

    @Test
    void galleryThemeKeepsItsOwnColorsAfterLeavingCoreTheme() throws Exception {
        onEdt(() -> {
            FlatLaf.setup(new FlatArcIJTheme());
            Color galleryBackground = UIManager.getColor("Panel.background");
            Color galleryField = UIManager.getColor("TextField.background");
            install(new FlatDarkLaf());
            install(new FlatArcIJTheme());
            assertEquals(galleryBackground, Tokens.surface());
            assertEquals(galleryField, UIManager.getColor("TextField.background"));
            assertFalse(Tokens.isDark());
        });
    }

    @Test
    void existingCaptionsAndPrimaryButtonsFollowThemeSwitches() throws Exception {
        onEdt(() -> {
            install(new FlatIntelliJLaf());
            JLabel caption = Fields.caption("操作说明");
            JButton primary = Buttons.primary("运行");
            Color lightCaption = caption.getForeground();
            install(new FlatDarkLaf());
            SwingUtilities.updateComponentTreeUI(caption);
            SwingUtilities.updateComponentTreeUI(primary);
            assertNotEquals(lightCaption, caption.getForeground());
            assertEquals(Tokens.mutedForeground(), caption.getForeground());
            assertEquals("primary", primary.getClientProperty("FlatLaf.styleClass"));
            assertEquals(UIManager.getColor("Button.default.background"), primary.getBackground());
        });
    }

    private static void install(FlatLaf laf) {
        FlatLaf.setup(laf);
        ThemeManager.applyCustomDefaults();
    }

    private static void onEdt(Runnable checks) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LookAndFeel original = UIManager.getLookAndFeel();
            try {
                checks.run();
            } finally {
                try {
                    UIManager.setLookAndFeel(original);
                    ThemeManager.applyCustomDefaults();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            }
        });
    }

    private static double contrast(Color first, Color second) {
        double firstLuminance = luminance(first);
        double secondLuminance = luminance(second);
        return (Math.max(firstLuminance, secondLuminance) + 0.05)
                / (Math.min(firstLuminance, secondLuminance) + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed())
                + 0.7152 * linear(color.getGreen())
                + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
