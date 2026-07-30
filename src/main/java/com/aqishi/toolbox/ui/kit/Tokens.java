package com.aqishi.toolbox.ui.kit;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * 设计令牌：间距、圆角、控件尺寸、字体与语义颜色。
 *
 * <p>所有颜色都从当前 LAF 的 {@link UIManager} 推导，不硬编码浅色或深色壳层颜色，
 * 因此切换 FlatLaf 任意主题后卡片、边框和次要文字都会随之变化。</p>
 */
public final class Tokens {

    private Tokens() {
    }

    // ---------------------------------------------------------------- 间距

    /** 4px：图标与文字、紧邻控件之间 */
    public static final int SPACE_XS = 4;
    /** 8px：同一行内的控件间距 */
    public static final int SPACE_SM = 8;
    /** 12px：卡片内的行间距 */
    public static final int SPACE_MD = 12;
    /** 16px：卡片之间、内容区边距 */
    public static final int SPACE_LG = 16;
    /** 24px：大分区之间 */
    public static final int SPACE_XL = 24;

    // ---------------------------------------------------------------- 圆角

    /** 卡片圆角 */
    public static final int RADIUS_CARD = 10;
    /** 控件圆角 */
    public static final int RADIUS_CONTROL = 8;

    // ---------------------------------------------------------------- 尺寸

    /** 常规控件最小高度 */
    public static final int CONTROL_HEIGHT = 32;
    /** 紧凑控件高度（工具条内的图标按钮等） */
    public static final int CONTROL_HEIGHT_SM = 28;
    /** 导航行高 */
    public static final int NAV_ROW_HEIGHT = 32;
    /** 表格行高 */
    public static final int TABLE_ROW_HEIGHT = 26;
    /** 卡片内边距 */
    public static final int CARD_PADDING = 14;

    // ---------------------------------------------------------------- 字体

    private static Font base() {
        Font font = UIManager.getFont("Label.font");
        if (font == null) {
            font = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return font;
    }

    /** 正文字体 */
    public static Font fontBody() {
        return base().deriveFont(Font.PLAIN, 13f);
    }

    /** 强调正文（例如表单里的关键取值） */
    public static Font fontBodyStrong() {
        return base().deriveFont(Font.BOLD, 13f);
    }

    /** 卡片 / 分区标题 */
    public static Font fontSectionTitle() {
        return base().deriveFont(Font.BOLD, 13f);
    }

    /** 页面级标题（当前工具名、侧栏应用名） */
    public static Font fontTitle() {
        return base().deriveFont(Font.BOLD, 15f);
    }

    /** 辅助说明、状态栏 */
    public static Font fontCaption() {
        return base().deriveFont(Font.PLAIN, 12f);
    }

    /** 等宽字体，用于代码、密钥、日志等内容 */
    public static Font fontMono() {
        Font font = new Font("Consolas", Font.PLAIN, 13);
        if (!"Consolas".equals(font.getFamily()) && !"Consolas".equals(font.getFontName())) {
            return new Font(Font.MONOSPACED, Font.PLAIN, 13);
        }
        return font;
    }

    // ---------------------------------------------------------------- 颜色

    private static Color first(String... keys) {
        for (String key : keys) {
            Color color = UIManager.getColor(key);
            if (color != null) {
                return color;
            }
        }
        return null;
    }

    /** 是否为深色主题：以内容背景亮度判断，避免维护主题白名单 */
    public static boolean isDark() {
        Color background = first("Panel.background", "control");
        if (background == null) {
            return false;
        }
        return luminance(background) < 0.5;
    }

    private static double luminance(Color color) {
        return (0.2126 * color.getRed() + 0.7152 * color.getGreen() + 0.0722 * color.getBlue()) / 255.0;
    }

    /** 工作区底色 */
    public static Color surface() {
        Color color = first("Panel.background", "control");
        return color == null ? new Color(0xF2F2F2) : color;
    }

    /**
     * 卡片背景：使用内容类控件的背景（浅色主题下接近白色，深色主题下接近编辑器底色），
     * 叠在 {@link #surface()} 之上自然形成一层轻微的高低差。
     */
    public static Color cardBackground() {
        Color content = first("TextField.background", "Table.background", "TextArea.background");
        Color surface = surface();
        if (content == null) {
            return surface;
        }
        // 与工作区底色差异过小时，向亮度反方向再推一点，确保卡片可辨识
        if (colorDistance(content, surface) < 6) {
            return shift(surface, isDark() ? 0.06f : -0.02f);
        }
        return content;
    }

    /** 卡片头部底色：比卡片本体略微收敛，用于强化标题带 */
    public static Color cardHeaderBackground() {
        return shift(cardBackground(), isDark() ? 0.05f : -0.025f);
    }

    /** 描边色 */
    public static Color border() {
        Color color = first("Component.borderColor", "Separator.foreground", "controlShadow");
        return color == null ? new Color(0xC8C8C8) : color;
    }

    /** 更弱的分隔线，用于卡片内部分隔 */
    public static Color borderSubtle() {
        Color border = border();
        Color card = cardBackground();
        return blend(border, card, 0.55f);
    }

    /** 主文字色 */
    public static Color foreground() {
        Color color = first("Label.foreground", "textText");
        return color == null ? Color.DARK_GRAY : color;
    }

    /** 次要 / 说明文字色 */
    public static Color mutedForeground() {
        Color color = first("Label.disabledForeground", "textInactiveText");
        if (color == null) {
            color = blend(foreground(), cardBackground(), 0.45f);
        }
        return color;
    }

    /** 强调色，用于主操作、选中态与关键数值 */
    public static Color accent() {
        Color color = first("Component.accentColor", "Component.focusColor",
                "ProgressBar.foreground", "textHighlight");
        return color == null ? new Color(0x2F6FEB) : color;
    }

    /** 成功态 */
    public static Color success() {
        Color color = first("Actions.Green");
        return color == null ? (isDark() ? new Color(0x5FA85F) : new Color(0x2E7D32)) : color;
    }

    /** 警告态 */
    public static Color warning() {
        Color color = first("Actions.Yellow");
        return color == null ? (isDark() ? new Color(0xC9A227) : new Color(0xB26A00)) : color;
    }

    /** 错误态 */
    public static Color danger() {
        Color color = first("Component.error.borderColor", "Actions.Red");
        return color == null ? (isDark() ? new Color(0xD05B5B) : new Color(0xC62828)) : color;
    }

    /** 强调色的浅底，用于标签、徽章 */
    public static Color accentSoft() {
        return blend(accent(), cardBackground(), isDark() ? 0.78f : 0.86f);
    }

    // ---------------------------------------------------------------- 颜色工具

    /** 按比例混合：{@code ratio} 越大越靠近 {@code towards} */
    public static Color blend(Color color, Color towards, float ratio) {
        float clamped = Math.max(0f, Math.min(1f, ratio));
        int red = Math.round(color.getRed() + (towards.getRed() - color.getRed()) * clamped);
        int green = Math.round(color.getGreen() + (towards.getGreen() - color.getGreen()) * clamped);
        int blue = Math.round(color.getBlue() + (towards.getBlue() - color.getBlue()) * clamped);
        return new Color(clamp(red), clamp(green), clamp(blue));
    }

    /** 提亮（正值）或压暗（负值） */
    public static Color shift(Color color, float amount) {
        Color target = amount >= 0 ? Color.WHITE : Color.BLACK;
        return blend(color, target, Math.abs(amount));
    }

    private static int colorDistance(Color a, Color b) {
        return Math.abs(a.getRed() - b.getRed())
                + Math.abs(a.getGreen() - b.getGreen())
                + Math.abs(a.getBlue() - b.getBlue());
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }
}
