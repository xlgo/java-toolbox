package com.aqishi.toolbox.ui;

import com.formdev.flatlaf.FlatDarculaLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatIntelliJLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.extras.FlatAnimatedLafChange;
import com.formdev.flatlaf.intellijthemes.FlatAllIJThemes;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.ui.kit.Skin;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 主题管理器：效果页的五套主题（浅色 / 深色 / 午夜蓝 / 森林绿 / 奶油暖）为核心，
 * 另保留 FlatLaf 与 IntelliJ 主题库供习惯旧外观的用户选择。
 *
 * <p>五套主题各自带一整份表面色板（工作区、侧栏、面板、边框、三级文字与强调色），
 * 因此换主题不只是切明暗，而是整套冷暖关系一起变化。色板写入 LAF 的 defaults 表，
 * 由 {@link Tokens} 在绘制时读取，任何工具面板都不需要自己订阅换肤事件。</p>
 *
 * <p>强调色默认跟随主题（浅色蓝 #2456D6、午夜蓝青 #38BDF8…），用户也可以在
 * 「外观」菜单里固定一个强调色；固定后切换主题不再覆盖它。</p>
 */
public final class ThemeManager {

    /** 单个主题定义 */
    public static final class Theme {
        public final String name;
        public final Class<? extends FlatLaf> lafClass;  // 核心 LAF 非 null
        public final String lafClassName;                 // IntelliJ 主题非 null，LAF 类全名
        public final boolean dark;
        /** 固定皮肤；为 null 时按明暗自动落到 DARK / LIGHT */
        public final Skin skin;

        Theme(String name, Class<? extends FlatLaf> lafClass, String lafClassName, boolean dark) {
            this(name, lafClass, lafClassName, dark, null);
        }

        Theme(String name, Class<? extends FlatLaf> lafClass, String lafClassName,
              boolean dark, Skin skin) {
            this.name = name;
            this.lafClass = lafClass;
            this.lafClassName = lafClassName;
            this.dark = dark;
            this.skin = skin;
        }
    }

    /** 效果页五套主题的主题名（与皮肤一一对应） */
    public static final String LIGHT_THEME = "Light";
    public static final String DARK_THEME = "Dark";
    public static final String MIDNIGHT_THEME = "Midnight";
    public static final String FOREST_THEME = "Forest";
    public static final String CREAM_THEME = "Cream";
    /** 护眼主题：浅色壳层 + 更淡的暖色板 */
    public static final String EYE_CARE_THEME = "Eye Care";

    /** 效果页给出的强调色板（供「外观」菜单手动覆盖时使用） */
    public static final int[] ACCENTS = {
            0x2456D6, 0x0F766E, 0x7C3AED, 0xDB2777, 0xD92D20, 0xB26A00, 0x475569
    };

    private static final String ACCENT_FOLLOW_THEME = "";

    /** 内置主题列表：6 个核心 LAF + 6 个效果页主题 + 48 个 IntelliJ 主题 */
    private static final List<Theme> THEMES = buildThemes();

    private static List<Theme> buildThemes() {
        List<Theme> list = new ArrayList<>();
        // 效果页五套主题：默认项，决定卡片、侧栏与面板的全部配色
        list.add(new Theme(LIGHT_THEME, FlatLightLaf.class, null, false, Skin.LIGHT));
        list.add(new Theme(DARK_THEME, FlatDarkLaf.class, null, true, Skin.DARK));
        list.add(new Theme(MIDNIGHT_THEME, FlatDarkLaf.class, null, true, Skin.MIDNIGHT));
        list.add(new Theme(FOREST_THEME, FlatDarkLaf.class, null, true, Skin.FOREST));
        list.add(new Theme(CREAM_THEME, FlatLightLaf.class, null, false, Skin.PAPER));
        list.add(new Theme(EYE_CARE_THEME, FlatLightLaf.class, null, false, Skin.EYE_CARE));
        // 其它核心 LAF：保持原有入口，色板按明暗落到 LIGHT / DARK
        list.add(new Theme("Flat Light（浅色）", FlatLightLaf.class, null, false, Skin.LIGHT));
        list.add(new Theme("Flat Dark（深色）", FlatDarkLaf.class, null, true, Skin.DARK));
        list.add(new Theme("IntelliJ（默认）", FlatIntelliJLaf.class, null, false, Skin.LIGHT));
        list.add(new Theme("Darcula", FlatDarculaLaf.class, null, true, Skin.DARK));
        list.add(new Theme("macOS Light", FlatMacLightLaf.class, null, false, Skin.LIGHT));
        list.add(new Theme("macOS Dark", FlatMacDarkLaf.class, null, true, Skin.DARK));
        // flatlaf-intellij-themes 包中所有主题（每个主题是独立的 FlatLaf 子类）
        for (FlatAllIJThemes.FlatIJLookAndFeelInfo info : FlatAllIJThemes.INFOS) {
            list.add(new Theme(info.getName(), null, info.getClassName(), info.isDark()));
        }
        return Collections.unmodifiableList(list);
    }

    private static Theme current = THEMES.get(0); // 默认效果页浅色

    /** 当前皮肤：卡片、导航与外壳配色据此推导 */
    private static Skin currentSkin = Skin.LIGHT;

    /** 手动指定的强调色；{@code null} 表示跟随主题 */
    private static Integer accentOverride;

    private ThemeManager() {
    }

    public static Theme current() {
        return current;
    }

    /** 当前皮肤基调 */
    public static Skin skin() {
        return currentSkin;
    }

    /** 当前强调色：优先用户固定值，否则取主题自带的强调色 */
    public static Color accent() {
        if (accentOverride != null) {
            return new Color(accentOverride);
        }
        return palette(currentSkin).accent();
    }

    public static int accentRgb() {
        return accent().getRGB() & 0xFFFFFF;
    }

    /** 是否使用了自定义强调色（而非跟随主题） */
    public static boolean hasCustomAccent() {
        return accentOverride != null;
    }

    /**
     * 固定一个强调色。只重写扁平外观里的主色、选中色与焦点色，不换整套 LAF。
     */
    public static void applyAccent(int rgb) {
        accentOverride = rgb & 0xFFFFFF;
        ConfigManager.set("accent.color", String.format("#%06X", accentOverride));
        ConfigManager.save();
        applyCustomDefaults();
        FlatLaf.updateUI();
    }

    /** 取消自定义强调色，回到「跟随主题」。 */
    public static void clearAccent() {
        accentOverride = null;
        ConfigManager.set("accent.color", ACCENT_FOLLOW_THEME);
        ConfigManager.save();
        applyCustomDefaults();
        FlatLaf.updateUI();
    }

    /** 切换皮肤：五套效果页主题一一对应。 */
    public static void applySkin(Skin skin) {
        if (skin == null) return;
        for (Theme theme : THEMES) {
            if (theme.skin == skin) {
                apply(theme.name);
                return;
            }
        }
    }

    /** 一键深浅切换：暗黑 ↔ 浅色，暖色系视作浅色 */
    public static void toggleDarkLight() {
        applySkin(currentSkin.isDark() ? Skin.LIGHT : Skin.DARK);
    }

    private static Skin resolveSkin(Theme theme) {
        if (theme.skin != null) return theme.skin;
        return theme.dark ? Skin.DARK : Skin.LIGHT;
    }

    /**
     * 让皮肤跟随实际生效的 LAF。
     *
     * <p>色板要按皮肤决定冷暖，而 LAF 也可能被外部直接 setup（测试、插件）。
     * 如果皮肤的明暗与 LAF 冲突，就地校正；护眼皮肤是浅色 LAF，明暗一致，
     * 因此不会被误改成普通浅色。</p>
     */
    private static void syncSkinWithLaf() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (!(laf instanceof FlatLaf flat)) return;
        if (currentSkin.isDark() != flat.isDark()) {
            currentSkin = flat.isDark() ? Skin.DARK : Skin.LIGHT;
        }
    }

    public static String[] names() {
        String[] arr = new String[THEMES.size()];
        for (int i = 0; i < THEMES.size(); i++) arr[i] = THEMES.get(i).name;
        return arr;
    }

    public static Theme get(String name) {
        for (Theme t : THEMES) if (t.name.equals(name)) return t;
        return null;
    }

    /** 初始化默认主题（main 启动时调用一次） */
    public static void setupDefault() {
        try {
            String savedTheme = ConfigManager.get("theme", LIGHT_THEME);
            accentOverride = parseAccent(ConfigManager.get("accent.color", ACCENT_FOLLOW_THEME));
            Theme t = get(savedTheme);
            if (t == null) {
                t = THEMES.get(0); // 回退效果页浅色
            }
            FlatLaf laf;
            if (t.lafClass != null) {
                laf = t.lafClass.getDeclaredConstructor().newInstance();
            } else {
                Class<?> clazz = Class.forName(t.lafClassName);
                laf = (FlatLaf) clazz.getDeclaredConstructor().newInstance();
            }
            // 皮肤必须先于色板确定：这一步决定了文字、卡片、导航的冷暖与明暗方向
            currentSkin = resolveSkin(t);
            FlatLaf.setup(laf);
            applyCustomDefaults();
            current = t;
        } catch (Throwable e) {
            Errors.log("加载已保存的主题失败，回退系统外观", e);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception fallbackError) {
                Errors.log("回退系统外观也失败，界面将使用 Swing 默认外观", fallbackError);
            }
            applyCustomDefaults();
        }
    }

    /** 切换到指定主题，整窗带动画刷新 */
    public static void apply(String name) {
        Theme t = get(name);
        if (t == null) return;
        try {
            FlatAnimatedLafChange.showSnapshot();

            FlatLaf laf;
            if (t.lafClass != null) {
                laf = t.lafClass.getDeclaredConstructor().newInstance();
            } else {
                Class<?> clazz = Class.forName(t.lafClassName);
                laf = (FlatLaf) clazz.getDeclaredConstructor().newInstance();
            }
            FlatLaf.setup(laf);
            // 皮肤必须先于自定义默认值确定：色板要按皮肤决定冷暖
            currentSkin = resolveSkin(t);
            applyCustomDefaults();
            FlatLaf.updateUI();
            current = t;

            ConfigManager.set("theme", t.name);
            ConfigManager.save();
        } catch (Throwable e) {
            Errors.log("切换主题到 " + t.name + " 失败，回退系统外观", e);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception fallbackError) {
                Errors.log("回退系统外观也失败，界面将使用 Swing 默认外观", fallbackError);
            }
        } finally {
            FlatAnimatedLafChange.hideSnapshotWithAnimation();
        }
    }

    /**
     * 全局外观默认值：圆角、控件高度、滚动条、表格、标签页。
     *
     * <p>这些键都是 FlatLaf 专有键，其它 LAF 会直接忽略，因此不会破坏兜底外观。
     * 在这里统一设置，可以让尚未逐个重构的工具面板也获得一致的控件观感。</p>
     */
    static void applyCustomDefaults() {
        syncSkinWithLaf();
        applyWorkbenchPalette();
        applyCjkFontDefaults();

        int control = Tokens.CONTROL_HEIGHT;
        int arc = Tokens.RADIUS_CONTROL;

        // 控件最小高度：让同一行的按钮、输入框、下拉框视觉等高
        UIManager.put("Button.minimumHeight", control);
        UIManager.put("TextField.minimumHeight", control);
        UIManager.put("PasswordField.minimumHeight", control);
        UIManager.put("FormattedTextField.minimumHeight", control);
        UIManager.put("ComboBox.minimumHeight", control);
        UIManager.put("Spinner.minimumHeight", control);

        // 圆角：效果页的输入框与按钮都是 8px
        UIManager.put("Button.arc", arc);
        UIManager.put("ToggleButton.arc", arc);
        UIManager.put("Component.arc", arc);
        UIManager.put("TextComponent.arc", arc);
        UIManager.put("CheckBox.arc", 4);
        UIManager.put("ProgressBar.arc", arc);
        UIManager.put("ProgressBar.horizontalSize", new java.awt.Dimension(146, 6));

        // 焦点描边收细一点，减少高密度表单里的视觉噪音
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 1);
        UIManager.put("Button.paintShadow", Boolean.FALSE);
        UIManager.put("Button.default.boldText", Boolean.FALSE);
        UIManager.put("Button.margin", new javax.swing.plaf.InsetsUIResource(4, 14, 4, 14));
        UIManager.put("ToggleButton.margin", new javax.swing.plaf.InsetsUIResource(4, 10, 4, 10));
        UIManager.put("ComboBox.padding", new javax.swing.plaf.InsetsUIResource(3, 9, 3, 9));
        UIManager.put("TextField.margin", new javax.swing.plaf.InsetsUIResource(4, 10, 4, 10));
        UIManager.put("PasswordField.margin", new javax.swing.plaf.InsetsUIResource(4, 10, 4, 10));

        // 滚动条：细、圆角、悬停才显轨道
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new javax.swing.plaf.InsetsUIResource(2, 2, 2, 2));
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("ScrollBar.showButtons", Boolean.FALSE);
        UIManager.put("ScrollPane.smoothScrolling", Boolean.TRUE);

        // 列表与树：更舒展的行高，导航更好点
        UIManager.put("Tree.rowHeight", Tokens.NAV_ROW_HEIGHT);
        UIManager.put("Tree.selectionArc", arc);
        UIManager.put("Tree.paintSelectionBackground", Boolean.TRUE);
        UIManager.put("List.selectionArc", arc);
        UIManager.put("List.selectionInsets", new javax.swing.plaf.InsetsUIResource(1, 4, 1, 4));
        UIManager.put("Tree.selectionInsets", new javax.swing.plaf.InsetsUIResource(1, 4, 1, 4));

        // 表格：去掉纵向网格线，改用行高与细横线表达结构
        UIManager.put("Table.rowHeight", Tokens.TABLE_ROW_HEIGHT);
        UIManager.put("Table.showHorizontalLines", Boolean.TRUE);
        UIManager.put("Table.showVerticalLines", Boolean.FALSE);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 1));
        UIManager.put("TableHeader.height", 32);
        UIManager.put("TableHeader.separatorColor", Tokens.borderSubtle());

        // 标签页：下划线样式比方框样式更贴近现代桌面产品
        UIManager.put("TabbedPane.tabHeight", 34);
        UIManager.put("TabbedPane.tabType", "underlined");
        UIManager.put("TabbedPane.tabSelectionHeight", 2);
        UIManager.put("TabbedPane.tabInsets", new javax.swing.plaf.InsetsUIResource(8, 14, 8, 14));
        UIManager.put("TabbedPane.showTabSeparators", Boolean.FALSE);
        UIManager.put("TabbedPane.tabsPopupPolicy", "asNeeded");
        UIManager.put("TabbedPane.scrollButtonsPolicy", "asNeeded");
        UIManager.put("TabbedPane.tabAreaInsets", new javax.swing.plaf.InsetsUIResource(0, 4, 0, 0));

        // 分隔条：细线，仍可拖动
        UIManager.put("SplitPane.oneTouchButtonSize", 0);
        UIManager.put("SplitPaneDivider.gripDotCount", 0);

        // 弹出与工具提示
        UIManager.put("PopupMenu.borderInsets", new javax.swing.plaf.InsetsUIResource(4, 2, 4, 2));
        UIManager.put("PopupMenu.borderCornerRadius", 10);
        UIManager.put("ToolTip.border", new javax.swing.plaf.BorderUIResource(
                javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)));

        // Style classes express button intent without assigning a root-pane
        // default button (which would also change Enter-key behavior).
        UIManager.getLookAndFeelDefaults().put("[style]Button.primary",
                "background: $Button.default.background; foreground: $Button.default.foreground; "
                        + "borderColor: $Button.default.borderColor; "
                        + "hoverBackground: $Button.default.hoverBackground; "
                        + "pressedBackground: $Button.default.pressedBackground; "
                        + "focusedBackground: $Button.default.focusedBackground; "
                        + "focusedBorderColor: $Component.focusColor");
        UIManager.getLookAndFeelDefaults().put("[style]Button.danger",
                "foreground: $Actions.Red; hoverForeground: $Actions.Red; "
                        + "pressedForeground: $Actions.Red");
    }

    /**
     * 把当前主题的色板写入 UIManager。只处理效果页主题与几个核心 LAF；
     * 主题库里其它外观保留自身配色，靠 {@link Tokens} 的推导保持可读。
     */
    private static void applyWorkbenchPalette() {
        LookAndFeel laf = UIManager.getLookAndFeel();
        if (laf == null) return;
        Class<?> type = laf.getClass();
        if (type != FlatLightLaf.class && type != FlatDarkLaf.class
                && type != FlatIntelliJLaf.class && type != FlatDarculaLaf.class
                && type != FlatMacLightLaf.class && type != FlatMacDarkLaf.class) {
            return;
        }

        Palette p = palette(currentSkin);
        Color workspace = p.workspace();
        Color card = p.panel();
        Color navigation = p.side();
        Color foreground = p.ink();
        Color secondary = p.ink2();
        Color muted = p.muted();
        Color disabled = p.muted();
        Color border = p.line();
        Color borderStrong = p.line2();
        Color separator = p.line();
        Color accent = accent();
        Color selection = Tokens.blend(card, accent, p.isDark() ? 0.18f : 0.10f);
        Color selectionText = foreground;
        Color field = p.field();
        Color code = p.code();
        Color hover = p.hover();
        Color active = p.active();
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        putColors(defaults, workspace, "Panel.background", "control", "Window.background",
                "TabbedPane.background", "SplitPane.background", "ScrollBar.background");
        putColors(defaults, workspace, "Toolbox.launcherBackground");
        putColors(defaults, navigation, "Toolbox.navigationBackground");
        putColors(defaults, card, "Toolbox.chromeBackground", "Toolbox.cardBackground");
        putColors(defaults, card, "TextField.background", "PasswordField.background",
                "FormattedTextField.background", "TextArea.background", "TextPane.background",
                "EditorPane.background", "Table.background", "List.background", "Tree.background",
                "ComboBox.background", "Spinner.background", "Viewport.background",
                "MenuBar.background", "PopupMenu.background", "ToolTip.background");
        putColors(defaults, field, "Toolbox.fieldBackground");
        putColors(defaults, code, "Toolbox.codeBackground");
        putColors(defaults, p.panel2(), "Button.background", "Button.startBackground",
                "Button.endBackground", "ToggleButton.background", "TableHeader.background");
        putColors(defaults, p.panel3(), "Toolbox.panelAltBackground");
        putColors(defaults, foreground, "Label.foreground", "Button.foreground", "ToggleButton.foreground",
                "CheckBox.foreground", "RadioButton.foreground", "ComboBox.foreground",
                "TextField.foreground", "PasswordField.foreground", "FormattedTextField.foreground",
                "TextArea.foreground", "TextPane.foreground", "EditorPane.foreground", "Table.foreground",
                "TableHeader.foreground", "List.foreground", "Tree.foreground", "Spinner.foreground",
                "TabbedPane.foreground", "ToolTip.foreground", "Menu.foreground", "MenuItem.foreground",
                "textText");
        putColors(defaults, secondary, "Toolbox.secondaryForeground");
        putColors(defaults, muted, "Toolbox.mutedForeground");
        putColors(defaults, p.faint(), "Toolbox.faintForeground", "TextField.placeholderForeground",
                "PasswordField.placeholderForeground");
        putColors(defaults, disabled, "Label.disabledForeground", "Button.disabledText",
                "CheckBox.disabledText", "RadioButton.disabledText", "ComboBox.disabledForeground",
                "TextField.inactiveForeground", "textInactiveText");
        putColors(defaults, border, "Toolbox.borderColor", "Component.borderColor", "Button.borderColor",
                "ToggleButton.borderColor", "TextField.borderColor", "Separator.foreground");
        putColors(defaults, borderStrong, "Toolbox.borderStrongColor", "Component.focusedBorderColor",
                "Button.focusedBorderColor");
        putColors(defaults, separator, "Table.gridColor", "TableHeader.bottomSeparatorColor");
        putColors(defaults, accent, "Toolbox.accentColor", "Component.accentColor", "Component.focusColor",
                "TabbedPane.underlineColor", "ProgressBar.foreground",
                "CheckBox.icon.selectedBackground", "CheckBox.icon.selectedBorderColor",
                "RadioButton.icon.selectedBackground", "RadioButton.icon.selectedBorderColor");
        putColors(defaults, p.accentText(), "Toolbox.accentText");
        putColors(defaults, selection, "Toolbox.selectionBackground", "List.selectionBackground",
                "Tree.selectionBackground", "Table.selectionBackground", "ComboBox.selectionBackground",
                "TabbedPane.hoverColor", "Button.hoverBackground", "ToggleButton.selectedBackground",
                "MenuItem.selectionBackground", "TextField.selectionBackground",
                "PasswordField.selectionBackground", "FormattedTextField.selectionBackground",
                "TextArea.selectionBackground", "TextPane.selectionBackground",
                "EditorPane.selectionBackground");
        putColors(defaults, active, "Toolbox.activeBackground");
        putColors(defaults, hover, "Toolbox.hoverBackground");
        putColors(defaults, selectionText, "Toolbox.selectionForeground", "List.selectionForeground",
                "Tree.selectionForeground", "Table.selectionForeground", "ComboBox.selectionForeground",
                "ToggleButton.selectedForeground", "MenuItem.selectionForeground",
                "TextField.selectionForeground", "PasswordField.selectionForeground",
                "FormattedTextField.selectionForeground", "TextArea.selectionForeground",
                "TextPane.selectionForeground", "EditorPane.selectionForeground");
        putColors(defaults, accent, "Button.default.background", "Button.default.startBackground",
                "Button.default.endBackground", "Button.default.borderColor",
                "Button.default.focusedBackground", "textHighlight");
        putColors(defaults, p.accentText(), "Button.default.foreground", "textHighlightText");
        putColors(defaults, p.isDark() ? Tokens.shift(accent, 0.10f) : Tokens.shift(accent, -0.10f),
                "Button.default.hoverBackground");
        putColors(defaults, p.isDark() ? Tokens.shift(accent, 0.02f) : Tokens.shift(accent, -0.20f),
                "Button.default.pressedBackground");
        putColors(defaults, Tokens.blend(border, card, 0.35f), "ScrollBar.thumb");
        putColors(defaults, border, "ScrollBar.hoverThumbColor");
        putColors(defaults, p.ok(), "Actions.Green", "Toolbox.successColor",
                "CheckBox.icon.checkmarkColor");
        putColors(defaults, p.warn(), "Actions.Yellow", "Toolbox.warningColor");
        putColors(defaults, p.danger(), "Actions.Red", "Component.error.borderColor",
                "Toolbox.dangerColor");
        putColors(defaults, p.accentText(), "RadioButton.icon.centerColor");
    }

    private static Integer parseAccent(String value) {
        String hex = value == null ? "" : value.trim();
        if (hex.isEmpty()) {
            return null;
        }
        try {
            if (hex.startsWith("#")) hex = hex.substring(1);
            return hex.length() == 6 ? Integer.parseInt(hex, 16) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 五套主题的完整表面色板，数值直接取自效果页的 CSS 变量。
     *
     * <p>顺序固定为：工作区、侧栏、面板、次级面板、三级面板、主文字、次文字、弱文字、
     * 描边、强描边、输入底色、代码底色、悬停、选中、危险、成功、警告、强调色、强调色文字。</p>
     */
    static Palette palette(Skin skin) {
        return switch (skin) {
            //             bg        side      panel     panel2    panel3    ink       ink2      muted
            case LIGHT -> new Palette(0xEEF0F3, 0xF7F8FA, 0xFFFFFF, 0xF2F4F7, 0xF2F4F7,
                    0x191D26, 0x59637A, 0x67728A, 0x98A1B5,
                    0xDDE1E8, 0xC9CFDA, 0xFFFFFF, 0xF6F7F9, 0xEEF1F6, 0xE4EBFD,
                    0xD92D20, 0x12805C, 0xB26A00, 0x2456D6, 0xFFFFFF);
            case DARK -> new Palette(0x131519, 0x191C21, 0x1E2128, 0x262A33, 0x262A33,
                    0xECEEF2, 0xA6ADBD, 0x8B93A6, 0x6D7587,
                    0x2E333F, 0x454C5E, 0x1E2128, 0x171A1F, 0x23262E, 0x24334F,
                    0xFF8A7A, 0x4CC38A, 0xE7BA6A, 0x6EA8FE, 0x0D1524);
            case MIDNIGHT -> new Palette(0x0A1120, 0x0E1830, 0x13203C, 0x182A4D, 0x182A4D,
                    0xE2EBFF, 0x9DB1D9, 0x7E93BF, 0x647BA8,
                    0x22345C, 0x33497A, 0x13203C, 0x0B1730, 0x16294A, 0x12324E,
                    0xFB9A8A, 0x4CC38A, 0xFBBF24, 0x38BDF8, 0x06263A);
            case FOREST -> new Palette(0x0F1D15, 0x14261C, 0x1A2F22, 0x21392A, 0x21392A,
                    0xE7F2E9, 0xA9C3AE, 0x8AA890, 0x6F8A74,
                    0x2C4A37, 0x40634D, 0x1A2F22, 0x12241A, 0x1F3527, 0x274A26,
                    0xFFA08D, 0x52D6A4, 0xFACC15, 0x7AC74F, 0x12240A);
            case PAPER -> new Palette(0xF3ECDC, 0xFAF4E6, 0xFFFDF6, 0xF6EFDD, 0xF6EFDD,
                    0x3A2F21, 0x7A6C55, 0x827055, 0xAB9C80,
                    0xE6DCC4, 0xD3C49E, 0xFFFDF6, 0xFAF5E9, 0xF4ECD9, 0xFBE9D9,
                    0xC62F1D, 0x2E7D4F, 0xA9721C, 0xBC4C12, 0xFFFFFF);
            case EYE_CARE -> new Palette(0xF3EFE4, 0xF9F5EA, 0xFFFEF8, 0xF7F1E3, 0xF5EEDD,
                    0x38332A, 0x6B6151, 0x7A7060, 0x9A8F7C,
                    0xE4DAC6, 0xD2C5A9, 0xFFFEF8, 0xF8F2E3, 0xF1EADB, 0xE8DEC5,
                    0xB4453F, 0x2E7D3E, 0xA9721C, 0x0F766E, 0xFFFFFF);
        };
    }

    /** 一套主题的表面色板。 */
    static final class Palette {
        private final int workspace;
        private final int side;
        private final int panel;
        private final int panel2;
        private final int panel3;
        private final int ink;
        private final int ink2;
        private final int muted;
        private final int faint;
        private final int line;
        private final int line2;
        private final int field;
        private final int code;
        private final int hover;
        private final int active;
        private final int danger;
        private final int ok;
        private final int warn;
        private final int accent;
        private final int accentText;

        private Palette(int workspace, int side, int panel, int panel2, int panel3,
                        int ink, int ink2, int muted, int faint, int line, int line2,
                        int field, int code, int hover, int active,
                        int danger, int ok, int warn, int accent, int accentText) {
            this.workspace = workspace;
            this.side = side;
            this.panel = panel;
            this.panel2 = panel2;
            this.panel3 = panel3;
            this.ink = ink;
            this.ink2 = ink2;
            this.muted = muted;
            this.faint = faint;
            this.line = line;
            this.line2 = line2;
            this.field = field;
            this.code = code;
            this.hover = hover;
            this.active = active;
            this.danger = danger;
            this.ok = ok;
            this.warn = warn;
            this.accent = accent;
            this.accentText = accentText;
        }

        Color workspace() { return new Color(workspace); }
        Color side() { return new Color(side); }
        Color panel() { return new Color(panel); }
        Color panel2() { return new Color(panel2); }
        Color panel3() { return new Color(panel3); }
        Color ink() { return new Color(ink); }
        Color ink2() { return new Color(ink2); }
        Color muted() { return new Color(muted); }
        Color faint() { return new Color(faint); }
        Color line() { return new Color(line); }
        Color line2() { return new Color(line2); }
        Color field() { return new Color(field); }
        Color code() { return new Color(code); }
        Color hover() { return new Color(hover); }
        Color active() { return new Color(active); }
        Color danger() { return new Color(danger); }
        Color ok() { return new Color(ok); }
        Color warn() { return new Color(warn); }
        Color accent() { return new Color(accent); }
        Color accentText() { return new Color(accentText); }

        /** 工作区是否为深色：强调色选择、投影等需要知道底色方向 */
        boolean isDark() {
            return workspace().getRed() < 128;
        }
    }

    private static void putColors(UIDefaults defaults, Color color, String... keys) {
        ColorUIResource resource = new ColorUIResource(color);
        for (String key : keys) {
            defaults.put(key, resource);
        }
    }

    /** Ensure legacy panels using UI defaults get the same CJK fallback. */
    private static void applyCjkFontDefaults() {
        String[] keys = {
                "Label.font", "Button.font", "ToggleButton.font", "CheckBox.font",
                "RadioButton.font", "ComboBox.font", "TextField.font", "PasswordField.font",
                "TextArea.font", "TextPane.font", "EditorPane.font", "List.font",
                "Table.font", "TableHeader.font", "Tree.font", "TabbedPane.font",
                "Spinner.font", "Menu.font", "MenuItem.font", "PopupMenu.font",
                "ToolTip.font", "OptionPane.messageFont", "OptionPane.buttonFont",
                "TitledBorder.font"
        };
        for (String key : keys) {
            java.awt.Font font = UIManager.getFont(key);
            if (font != null) {
                UIManager.put(key, Tokens.withCjkFallback(font));
            }
        }
        UIManager.put("defaultFont", Tokens.withCjkFallback(
                new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 13)));
    }
}
