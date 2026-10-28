package com.aqishi.toolbox.ui.kit;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * 设计令牌：间距、圆角、控件尺寸、字体与语义颜色。
 *
 * <p>数值来自新版效果页（tool-v2）：工作区 28/32px 留白、卡片 12px 圆角、20px 内边距、
 * 控件 34px 高。颜色不在这里写死，而是从当前主题的 {@link UIManager} 表读取，
 * 由 {@code ThemeManager} 按五套主题（浅色 / 深色 / 午夜蓝 / 森林绿 / 奶油暖）写入。
 * 这样任何一套主题切换后，卡片、边框与次要文字都会随之变化，不需要重建组件。</p>
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

    /** 页面左右留白：效果页的 32px */
    public static final int PAGE_PADDING_X = 32;
    /** 页面上留白：效果页的 28px */
    public static final int PAGE_PADDING_TOP = 28;
    /** 页面下留白：效果页的 48px，保证最后一行卡片不贴底 */
    public static final int PAGE_PADDING_BOTTOM = 48;
    /** 正文最大宽度：超宽屏下内容居中，阅读行长稳定 */
    public static final int PAGE_MAX_WIDTH = 1180;

    // ---------------------------------------------------------------- 圆角

    /** 卡片 / 面板圆角 */
    public static final int RADIUS_CARD = 12;
    /** 控件圆角 */
    public static final int RADIUS_CONTROL = 8;
    /** 工具磁贴圆角：与面板同档，效果页不再使用更大的圆角 */
    public static final int RADIUS_TILE = 12;

    // ---------------------------------------------------------------- 尺寸

    /** 常规控件高度 */
    public static final int CONTROL_HEIGHT = 34;
    /** 紧凑控件高度（工具条内的图标按钮等） */
    public static final int CONTROL_HEIGHT_SM = 28;
    /** 导航行高 */
    public static final int NAV_ROW_HEIGHT = 32;
    /** 表格行高 */
    public static final int TABLE_ROW_HEIGHT = 26;
    /** 卡片 / 面板内边距 */
    public static final int CARD_PADDING = 20;

    // ---------------------------------------------------------------- 首页

    /** 工具磁贴最小宽度（效果页 248px） */
    public static final int TILE_WIDTH = 248;
    /** 工具磁贴高度 */
    public static final int TILE_HEIGHT = 186;
    /** 磁贴内图标底块的边长 */
    public static final int TILE_ICON = 40;
    /** 磁贴之间的间距 */
    public static final int TILE_GAP = 14;
    /** 分类导航条目高度 */
    public static final int RAIL_ROW_HEIGHT = 32;
    /** 顶栏高度 */
    public static final int TOPBAR_HEIGHT = 54;
    /** 侧栏宽度 */
    public static final int SIDEBAR_WIDTH = 232;
    /** 状态栏高度 */
    public static final int STATUSBAR_HEIGHT = 30;

    // ---------------------------------------------------------------- 字体

    private static final String CJK_PROBE = "中文统计结果终端命令输入框";

    /**
     * Returns a font that can render the probe CJK text. FlatLaf themes may
     * use a physical font such as Segoe UI, which has no CJK glyphs on some
     * Windows installations. A logical font lets the JDK select its CJK
     * fallback instead of painting tofu/question-mark boxes.
     */
    public static Font withCjkFallback(Font reference) {
        Font candidate = reference == null
                ? new Font(Font.DIALOG, Font.PLAIN, 13)
                : reference;
        String name = candidate.getName();
        boolean logical = Font.DIALOG.equals(name)
                || Font.DIALOG_INPUT.equals(name)
                || Font.MONOSPACED.equals(name)
                || Font.SANS_SERIF.equals(name)
                || Font.SERIF.equals(name);
        if (logical && candidate.canDisplayUpTo(CJK_PROBE) < 0) {
            return candidate;
        }
        return new Font(Font.DIALOG, candidate.getStyle(), Math.max(1, candidate.getSize()));
    }

    private static Font base() {
        Font font = UIManager.getFont("Label.font");
        return withCjkFallback(font == null
                ? new Font(Font.SANS_SERIF, Font.PLAIN, 13)
                : font);
    }

    /** 正文字体 */
    public static Font fontBody() {
        return base().deriveFont(Font.PLAIN, 13f);
    }

    /** 强调正文（例如表单里的关键取值） */
    public static Font fontBodyStrong() {
        return base().deriveFont(Font.BOLD, 13f);
    }

    /** 卡片内的次级标题（效果页的 .panel h4：13px 加粗、次要色） */
    public static Font fontSectionTitle() {
        return base().deriveFont(Font.BOLD, 13f);
    }

    /** 侧栏应用名、区块标题 */
    public static Font fontTitle() {
        return base().deriveFont(Font.BOLD, 17f);
    }

    /** 工具页标题：效果页 21px 粗体 */
    public static Font fontPageTitle() {
        return base().deriveFont(Font.BOLD, 21f);
    }

    /** 首页标题：效果页 24px 粗体 */
    public static Font fontHomeTitle() {
        return base().deriveFont(Font.BOLD, 24f);
    }

    /** 辅助说明、状态栏 */
    public static Font fontCaption() {
        return base().deriveFont(Font.PLAIN, 12f);
    }

    /** 等宽字体，用于代码、密钥、日志等内容 */
    public static Font fontMono() {
        // Monospaced is a logical font. Unlike hard-coded Consolas it keeps a
        // CJK fallback while preserving fixed-width Latin text.
        return new Font(Font.MONOSPACED, Font.PLAIN, 13);
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

    /** 工作区底色：效果页的 --bg */
    public static Color surface() {
        Color color = first("Panel.background", "control");
        return color == null ? new Color(0xEEF0F3) : color;
    }

    /** 侧边导航底色：效果页的 --sidebar */
    public static Color navigationBackground() {
        Color color = first("Toolbox.navigationBackground");
        return color == null ? new Color(0xF7F8FA) : color;
    }

    /** 顶栏、状态栏等应用外壳区域的底色 */
    public static Color chromeBackground() {
        Color color = first("Toolbox.chromeBackground");
        return color == null ? cardBackground() : color;
    }

    /** 卡片 / 面板背景：效果页的 --surface */
    public static Color cardBackground() {
        Color content = first("Toolbox.cardBackground", "TextField.background", "Table.background");
        Color surface = surface();
        if (content == null) {
            return surface;
        }
        if (colorDistance(content, surface) < 6) {
            return shift(surface, isDark() ? 0.06f : -0.02f);
        }
        return content;
    }

    /** 卡片头部底色：效果页面板标题与内容同底色，不再单独铺色 */
    public static Color cardHeaderBackground() {
        return cardBackground();
    }

    /** 描边色 */
    public static Color border() {
        Color color = first("Toolbox.borderColor", "Component.borderColor", "Separator.foreground");
        return color == null ? new Color(0xDDE1E8) : color;
    }

    /** 更明显的描边，用于输入框与次级按钮 */
    public static Color borderStrong() {
        Color color = first("Toolbox.borderStrongColor");
        return color == null ? new Color(0xC9CFDA) : color;
    }

    /** 代码块与等宽文本底色 */
    public static Color codeBackground() {
        Color color = first("Toolbox.codeBackground");
        return color == null ? surface() : color;
    }

    /** 表单字段底色 */
    public static Color fieldBackground() {
        Color color = first("Toolbox.fieldBackground", "TextField.background");
        return color == null ? cardBackground() : color;
    }

    /** 次级面板底色（统计块、标签底、表头） */
    public static Color panelAlt() {
        Color color = first("Toolbox.panelAltBackground");
        return color == null ? surface() : color;
    }

    /** 悬停底色 */
    public static Color hoverSurface() {
        Color color = first("Toolbox.hoverBackground");
        return color == null ? hoverBackground() : color;
    }

    /** 选中底色 */
    public static Color activeSurface() {
        Color color = first("Toolbox.activeBackground");
        return color == null ? accentSoft() : color;
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
        return color == null ? new Color(0x191D26) : color;
    }

    /** 次要 / 说明文字色 */
    public static Color mutedForeground() {
        Color color = first("Toolbox.mutedForeground", "Label.disabledForeground", "textInactiveText");
        if (color == null) {
            color = blend(foreground(), cardBackground(), 0.45f);
        }
        return color;
    }

    /** 装饰性弱文字：卡片标签、占位提示。对比度低于正文，只用于不影响理解的信息 */
    public static Color faintForeground() {
        Color color = first("Toolbox.faintForeground");
        return color == null ? blend(mutedForeground(), cardBackground(), 0.35f) : color;
    }

    /** 比 muted 更实一档的正文说明色，用于工具描述与副标题 */
    public static Color secondaryForeground() {
        Color color = first("Toolbox.secondaryForeground");
        return color == null ? blend(foreground(), mutedForeground(), 0.35f) : color;
    }

    /** 强调色，用于主操作、选中态与关键数值 */
    public static Color accent() {
        Color color = first("Toolbox.accentColor", "Component.accentColor", "Component.focusColor",
                "ProgressBar.foreground", "textHighlight");
        return color == null ? new Color(0x2456D6) : color;
    }

    /** 强调色上的文字色：浅色主题为白，深色主题的亮青/亮紫则转深色 */
    public static Color accentText() {
        Color color = first("Toolbox.accentText");
        if (color != null) {
            return color;
        }
        return luminance(accent()) > 0.62 ? new Color(0x0D1524) : Color.WHITE;
    }

    /** 成功态 */
    public static Color success() {
        Color color = first("Toolbox.successColor", "Actions.Green");
        return color == null ? (isDark() ? new Color(0x4CC38A) : new Color(0x12805C)) : color;
    }

    /** 警告态 */
    public static Color warning() {
        Color color = first("Toolbox.warningColor", "Actions.Yellow");
        return color == null ? (isDark() ? new Color(0xE7BA6A) : new Color(0xB26A00)) : color;
    }

    /** 错误态 */
    public static Color danger() {
        Color color = first("Toolbox.dangerColor", "Component.error.borderColor", "Actions.Red");
        return color == null ? (isDark() ? new Color(0xFF8A7A) : new Color(0xD92D20)) : color;
    }

    /** 强调色的浅底，用于标签、徽章、选中行 */
    public static Color accentSoft() {
        Color color = first("Toolbox.selectionBackground");
        return color == null ? blend(accent(), cardBackground(), isDark() ? 0.78f : 0.91f) : color;
    }

    /** 强调色的中等透明层，用于工具标题图标的描边 */
    public static Color accentMid() {
        return blend(accentSoft(), accent(), isDark() ? 0.40f : 0.26f);
    }

    /** 轻量悬停底色，不与明确的选中状态混淆。 */
    public static Color hoverBackground() {
        return blend(navigationBackground(), accent(), isDark() ? 0.09f : 0.045f);
    }

    /** 柔和选中底色上的高对比文字色。 */
    public static Color selectionForeground() {
        Color color = first("Toolbox.selectionForeground");
        return color == null ? foreground() : color;
    }

    /** 卡片投影色：深色主题纯黑，浅色主题冷灰 */
    public static Color shadow() {
        return isDark() ? new Color(0, 0, 0) : new Color(0x192346);
    }

    /** 首页底色：与工作区同色，卡片靠描边而不是换底色区分 */
    public static Color launcherBackground() {
        Color color = first("Toolbox.launcherBackground");
        return color == null ? surface() : color;
    }

    /** 收藏星标的选中色 */
    public static Color favorite() {
        return new Color(0xE8A13A);
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

    /** 带透明度的颜色（用于投影） */
    public static Color alpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(255, alpha)));
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
