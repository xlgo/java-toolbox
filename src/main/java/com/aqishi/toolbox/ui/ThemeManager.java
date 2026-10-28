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
 * 主题管理器：基于 FlatLaf 外观包，提供核心 LAF + IntelliJ 主题包切换。
 * <p>核心 LAF 直接用 Class 对象实例化；IntelliJ 主题通过反射加载
 * {@code FlatAllIJThemes.INFOS} 中的 LAF 类名（如
 * {@code com.formdev.flatlaf.intellijthemes.FlatArcIJTheme}），
 * 该类继承自 {@link FlatLaf}，实例化后 {@link FlatLaf#setup(FlatLaf)} 即可。</p>
 * <p>切换时使用 FlatAnimatedLafChange 平滑过渡，并刷新整个应用组件树。</p>
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

    /** 护眼主题名：浅色壳层 + 暖色色板。用英文名与其它内置主题保持一致，文案由皮肤菜单本地化 */
    public static final String EYE_CARE_THEME = "Eye Care";
    public static final String MIDNIGHT_THEME = "Midnight";
    public static final String FOREST_THEME = "Forest";
    public static final String PAPER_THEME = "Paper";

    /** 默认强调色：与效果页一致的石墨绿 */
    public static final int DEFAULT_ACCENT = 0x0F766E;

    /** 效果页给出的七个强调色，顺序与顶栏色点一致 */
    public static final int[] ACCENTS = {
            0x0F766E, 0x2563EB, 0xDC2626, 0xD97706, 0x7C3AED, 0xDB2777, 0x475569
    };

    private static int currentAccent = DEFAULT_ACCENT;

    /** 内置主题列表：6 个核心 LAF + 1 个护眼主题 + 48 个 IntelliJ 主题 */
    private static final List<Theme> THEMES = buildThemes();

    private static List<Theme> buildThemes() {
        List<Theme> list = new ArrayList<>();
        // 核心 LAF
        list.add(new Theme("Flat Light（浅色）", FlatLightLaf.class, null, false, Skin.LIGHT));
        list.add(new Theme("Flat Dark（深色）", FlatDarkLaf.class, null, true, Skin.DARK));
        list.add(new Theme("IntelliJ（默认）", FlatIntelliJLaf.class, null, false, Skin.LIGHT));
        list.add(new Theme("Darcula", FlatDarculaLaf.class, null, true, Skin.DARK));
        list.add(new Theme("macOS Light", FlatMacLightLaf.class, null, false, Skin.LIGHT));
        list.add(new Theme("macOS Dark", FlatMacDarkLaf.class, null, true, Skin.DARK));
        // 护眼：复用浅色 LAF，由色板换成暖米色；皮肤固定，不随明暗推导
        list.add(new Theme(EYE_CARE_THEME, FlatLightLaf.class, null, false, Skin.EYE_CARE));
        // 效果页的五档外观。午夜蓝与森绿共用深色 LAF，仅壳层色板不同；暖纸使用浅色 LAF。
        list.add(new Theme(MIDNIGHT_THEME, FlatDarkLaf.class, null, true, Skin.MIDNIGHT));
        list.add(new Theme(FOREST_THEME, FlatDarkLaf.class, null, true, Skin.FOREST));
        list.add(new Theme(PAPER_THEME, FlatLightLaf.class, null, false, Skin.PAPER));
        // flatlaf-intellij-themes 包中所有主题（每个主题是独立的 FlatLaf 子类）
        for (FlatAllIJThemes.FlatIJLookAndFeelInfo info : FlatAllIJThemes.INFOS) {
            list.add(new Theme(info.getName(), null, info.getClassName(), info.isDark()));
        }
        return Collections.unmodifiableList(list);
    }

    private static Theme current = THEMES.get(2); // 默认 IntelliJ

    /** 当前皮肤：卡片渐变与导航配色据此推导 */
    private static Skin currentSkin = Skin.LIGHT;

    private ThemeManager() {
    }

    public static Theme current() {
        return current;
    }

    /** 当前皮肤基调 */
    public static Skin skin() {
        return currentSkin;
    }

    /** 当前强调色，用于自绘控件保持与 LAF 主色一致 */
    public static Color accent() {
        return new Color(currentAccent);
    }

    public static int accentRgb() {
        return currentAccent;
    }

    /**
     * 独立切换强调色。效果页顶栏的色点只改变主色，不换整套 LAF；
     * 这里同步重写扁平外观里的主色、选中色与焦点色，再刷新组件树。
     */
    public static void applyAccent(int rgb) {
        currentAccent = rgb & 0xFFFFFF;
        ConfigManager.set("accent.color", String.format("#%06X", currentAccent));
        ConfigManager.save();
        applyCustomDefaults();
        FlatLaf.updateUI();
    }

    /**
     * 三档皮肤与内置主题一一对应，切换皮肤即切换整窗外观。
     *
     * <p>护眼模式是一个独立的主题名，因此换皮肤后再从下拉里选别的主题，
     * 皮肤会自动跟随新主题的明暗，不会残留暖色调。</p>
     */
    public static void applySkin(Skin skin) {
        if (skin == null) return;
        // 按皮肤查找内置主题：避免在这里重复写主题名称字面量
        for (Theme theme : THEMES) {
            if (theme.skin == skin) {
                apply(theme.name);
                return;
            }
        }
    }

    /** 一键深浅切换：暗黑 ↔ 多彩浅色，护眼视作浅色 */
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
            String savedTheme = ConfigManager.get("theme", "IntelliJ（默认）");
            currentAccent = parseAccent(ConfigManager.get("accent.color", "#0F766E"));
            Theme t = get(savedTheme);
            if (t == null) {
                t = THEMES.get(2); // 回退默认主题
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
                // 核心 LAF：直接实例化
                laf = t.lafClass.getDeclaredConstructor().newInstance();
            } else {
                // IntelliJ 主题：反射加载 LAF 类（继承自 FlatLaf）并实例化
                Class<?> clazz = Class.forName(t.lafClassName);
                laf = (FlatLaf) clazz.getDeclaredConstructor().newInstance();
            }
            FlatLaf.setup(laf);
            // 皮肤必须先于自定义默认值确定：色板要按皮肤决定冷暖
            currentSkin = resolveSkin(t);
            // 必须在 setup 之后写入：setLookAndFeel 会重建 defaults 表
            applyCustomDefaults();
            FlatLaf.updateUI();
            current = t;

            // 保存配置
            ConfigManager.set("theme", t.name);
            ConfigManager.save();
        } catch (Throwable e) {
            // 失败回退系统 LAF
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

        int control = com.aqishi.toolbox.ui.kit.Tokens.CONTROL_HEIGHT;
        int arc = com.aqishi.toolbox.ui.kit.Tokens.RADIUS_CONTROL;

        // 控件最小高度：让同一行的按钮、输入框、下拉框视觉等高
        UIManager.put("Button.minimumHeight", control);
        UIManager.put("TextField.minimumHeight", control);
        UIManager.put("PasswordField.minimumHeight", control);
        UIManager.put("FormattedTextField.minimumHeight", control);
        UIManager.put("ComboBox.minimumHeight", control);
        UIManager.put("Spinner.minimumHeight", control);

        // 圆角
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
        UIManager.put("Button.margin", new javax.swing.plaf.InsetsUIResource(3, 12, 3, 12));
        UIManager.put("ToggleButton.margin", new javax.swing.plaf.InsetsUIResource(3, 10, 3, 10));
        UIManager.put("ComboBox.padding", new javax.swing.plaf.InsetsUIResource(3, 9, 3, 9));
        UIManager.put("TextField.margin", new javax.swing.plaf.InsetsUIResource(3, 8, 3, 8));
        UIManager.put("PasswordField.margin", new javax.swing.plaf.InsetsUIResource(3, 8, 3, 8));

        // 滚动条：细、圆角、悬停才显轨道
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new javax.swing.plaf.InsetsUIResource(2, 2, 2, 2));
        UIManager.put("ScrollBar.trackArc", 999);
        UIManager.put("ScrollBar.showButtons", Boolean.FALSE);
        UIManager.put("ScrollPane.smoothScrolling", Boolean.TRUE);

        // 列表与树：更舒展的行高，导航更好点
        UIManager.put("Tree.rowHeight", com.aqishi.toolbox.ui.kit.Tokens.NAV_ROW_HEIGHT);
        UIManager.put("Tree.selectionArc", arc);
        UIManager.put("Tree.paintSelectionBackground", Boolean.TRUE);
        UIManager.put("List.selectionArc", arc);
        UIManager.put("List.selectionInsets", new javax.swing.plaf.InsetsUIResource(1, 4, 1, 4));
        UIManager.put("Tree.selectionInsets", new javax.swing.plaf.InsetsUIResource(1, 4, 1, 4));

        // 表格：去掉纵向网格线，改用行高与细横线表达结构
        UIManager.put("Table.rowHeight", com.aqishi.toolbox.ui.kit.Tokens.TABLE_ROW_HEIGHT);
        UIManager.put("Table.showHorizontalLines", Boolean.TRUE);
        UIManager.put("Table.showVerticalLines", Boolean.FALSE);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 1));
        UIManager.put("TableHeader.height", 32);
        UIManager.put("TableHeader.separatorColor", com.aqishi.toolbox.ui.kit.Tokens.borderSubtle());

        // 标签页：下划线样式比方框样式更贴近现代桌面产品
        UIManager.put("TabbedPane.tabHeight", 34);
        UIManager.put("TabbedPane.tabType", "underlined");
        UIManager.put("TabbedPane.tabSelectionHeight", 2);
        UIManager.put("TabbedPane.tabInsets", new javax.swing.plaf.InsetsUIResource(7, 14, 7, 14));
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
     * A quiet, slate-and-blue workbench for the built-in themes. Gallery themes
     * retain their own palette. Store colors in the LAF table, not persistent
     * UIManager overrides, so changing from a core theme to a gallery theme
     * cannot leak the previous palette into the new one.
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

        Skin skin = currentSkin;
        Palette p = palette(skin);
        Color workspace = p.workspace();
        Color card = p.panel();
        Color navigation = p.side();
        Color launcher = p.workspace();
        Color foreground = p.ink();
        Color secondary = p.ink2();
        Color muted = p.muted();
        Color disabled = p.muted();
        Color border = p.line();
        Color borderStrong = p.line2();
        Color separator = p.line();
        Color accent = accent();
        Color primary = accent;
        Color selection = Tokens.blend(card, accent, 0.14f);
        Color selectionText = foreground;
        Color field = p.field();
        Color code = p.code();
        Color hover = p.hover();
        Color active = p.active();
        UIDefaults defaults = UIManager.getLookAndFeelDefaults();

        putColors(defaults, workspace, "Panel.background", "control", "Window.background",
                "TabbedPane.background", "SplitPane.background", "ScrollBar.background");
        putColors(defaults, launcher, "Toolbox.launcherBackground");
        putColors(defaults, navigation, "Toolbox.navigationBackground", "Toolbox.chromeBackground");
        putColors(defaults, card, "TextField.background", "PasswordField.background",
                "FormattedTextField.background", "TextArea.background", "TextPane.background",
                "EditorPane.background", "Table.background", "List.background", "Tree.background",
                "ComboBox.background", "Spinner.background", "Viewport.background",
                "MenuBar.background", "PopupMenu.background", "ToolTip.background");
        putColors(defaults, field, "Toolbox.fieldBackground", "FormattedTextField.background");
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
        putColors(defaults, muted, "Toolbox.mutedForeground", "TextField.placeholderForeground",
                "PasswordField.placeholderForeground");
        putColors(defaults, disabled, "Label.disabledForeground", "Button.disabledText",
                "CheckBox.disabledText", "RadioButton.disabledText", "ComboBox.disabledForeground",
                "TextField.inactiveForeground", "textInactiveText");
        putColors(defaults, border, "Component.borderColor", "Button.borderColor",
                "ToggleButton.borderColor", "TextField.borderColor", "Separator.foreground");
        putColors(defaults, borderStrong, "Toolbox.borderStrongColor", "Component.focusedBorderColor",
                "Button.focusedBorderColor");
        putColors(defaults, separator, "Table.gridColor", "TableHeader.bottomSeparatorColor");
        putColors(defaults, accent, "Toolbox.accentColor", "Component.accentColor", "Component.focusColor",
                "TabbedPane.underlineColor", "ProgressBar.foreground",
                "CheckBox.icon.selectedBackground", "CheckBox.icon.selectedBorderColor",
                "RadioButton.icon.selectedBackground", "RadioButton.icon.selectedBorderColor");
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
        putColors(defaults, primary, "Button.default.background", "Button.default.startBackground",
                "Button.default.endBackground", "Button.default.borderColor",
                "Button.default.focusedBackground", "textHighlight");
        putColors(defaults, Color.WHITE, "Button.default.foreground", "textHighlightText",
                "CheckBox.icon.checkmarkColor", "RadioButton.icon.centerColor");
        putColors(defaults, Tokens.shift(primary, -0.08f), "Button.default.hoverBackground");
        putColors(defaults, Tokens.shift(primary, -0.17f), "Button.default.pressedBackground");
        putColors(defaults, Tokens.blend(border, card, 0.35f), "ScrollBar.thumb");
        putColors(defaults, border, "ScrollBar.hoverThumbColor");
        putColors(defaults, p.ok(), "Actions.Green", "Toolbox.successColor");
        putColors(defaults, p.warn(), "Actions.Yellow", "Toolbox.warningColor");
        putColors(defaults, p.danger(), "Actions.Red", "Component.error.borderColor",
                "Toolbox.dangerColor");
    }

    private static int parseAccent(String value) {
        try {
            String hex = value == null ? "" : value.trim();
            if (hex.startsWith("#")) hex = hex.substring(1);
            return hex.length() == 6 ? Integer.parseInt(hex, 16) : DEFAULT_ACCENT;
        } catch (NumberFormatException ignored) {
            return DEFAULT_ACCENT;
        }
    }

    /**
     * 每个皮肤一套完整表面色板，取自效果页的五套主题。
     *
     * <p>只换 LAF 明暗不足以得到午夜蓝、森绿或暖纸——它们的主背景、侧栏、
     * 卡片与边框都需要各自的冷暖关系。这里集中维护，避免 Tokens 里散落条件判断。</p>
     */
    private static Palette palette(Skin skin) {
        return switch (skin) {
            case LIGHT -> new Palette(0xE9EBE8, 0xF4F5F2, 0xFFFFFF, 0xF6F7F4, 0xECEEE9,
                    0x1B211D, 0x4C564F, 0x6B756E, 0xDDE1DA, 0xCDD3CA, 0xFFFFFF, 0xF2F4EF,
                    0xE8EBE5, 0xDDE8E2, 0xB42318, 0x067647, 0xB54708);
            case DARK -> new Palette(0x0E1013, 0x15181C, 0x1A1E23, 0x20252C, 0x262C34,
                    0xECEFF1, 0xAEB8BF, 0x8D979E, 0x2A3038, 0x39414B, 0x121519, 0x121519,
                    0x232830, 0x243732, 0xF97066, 0x32D583, 0xFDB022);
            case MIDNIGHT -> new Palette(0x070D1A, 0x0B1526, 0x101C33, 0x14233F, 0x1A2C4F,
                    0xE6EEFC, 0xA9BCDA, 0x8094B4, 0x1E3050, 0x2B4470, 0x0A1425, 0x0A1425,
                    0x16263F, 0x17352F, 0xFB7185, 0x34D399, 0xFBBF24);
            case FOREST -> new Palette(0x0A120D, 0x0F1A12, 0x152219, 0x1A2B1F, 0x213627,
                    0xE7F2E8, 0xAEC7B1, 0x86A08A, 0x26392A, 0x35523B, 0x0D1710, 0x0D1710,
                    0x1C2F22, 0x274435, 0xFDA4AF, 0x4ADE80, 0xFACC15);
            case PAPER -> new Palette(0xEFE6D4, 0xF7F0DE, 0xFFFAF0, 0xFBF4E3, 0xF1E7CF,
                    0x2D2314, 0x6B5B40, 0x7E6D50, 0xE3D5B8, 0xD3BF97, 0xFFFDF6, 0xF6EED9,
                    0xF3EAD3, 0xEEE0BD, 0xB42318, 0x067647, 0xB54708);
            case EYE_CARE -> new Palette(0xF5F0E4, 0xFAF6EA, 0xFFFDF6, 0xF8F2E2, 0xEEE5D2,
                    0x3A362D, 0x665D4D, 0x7D7360, 0xE0D7C3, 0xCFC3AC, 0xFFFEF8, 0xF7F0E1,
                    0xF1E9D7, 0xE8DEC5, 0xB4453F, 0x2E7D3E, 0xA9721C);
        };
    }

    private static final class Palette {
        private final int workspace;
        private final int side;
        private final int panel;
        private final int panel2;
        private final int panel3;
        private final int ink;
        private final int ink2;
        private final int muted;
        private final int line;
        private final int line2;
        private final int field;
        private final int code;
        private final int hover;
        private final int active;
        private final int danger;
        private final int ok;
        private final int warn;

        private Palette(int workspace, int side, int panel, int panel2, int panel3,
                        int ink, int ink2, int muted, int line, int line2,
                        int field, int code, int hover, int active,
                        int danger, int ok, int warn) {
            this.workspace = workspace;
            this.side = side;
            this.panel = panel;
            this.panel2 = panel2;
            this.panel3 = panel3;
            this.ink = ink;
            this.ink2 = ink2;
            this.muted = muted;
            this.line = line;
            this.line2 = line2;
            this.field = field;
            this.code = code;
            this.hover = hover;
            this.active = active;
            this.danger = danger;
            this.ok = ok;
            this.warn = warn;
        }

        Color workspace() { return new Color(workspace); }
        Color side() { return new Color(side); }
        Color panel() { return new Color(panel); }
        Color panel2() { return new Color(panel2); }
        Color panel3() { return new Color(panel3); }
        Color ink() { return new Color(ink); }
        Color ink2() { return new Color(ink2); }
        Color muted() { return new Color(muted); }
        Color line() { return new Color(line); }
        Color line2() { return new Color(line2); }
        Color field() { return new Color(field); }
        Color code() { return new Color(code); }
        Color hover() { return new Color(hover); }
        Color active() { return new Color(active); }
        Color danger() { return new Color(danger); }
        Color ok() { return new Color(ok); }
        Color warn() { return new Color(warn); }
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
                UIManager.put(key, com.aqishi.toolbox.ui.kit.Tokens.withCjkFallback(font));
            }
        }
        UIManager.put("defaultFont", com.aqishi.toolbox.ui.kit.Tokens.withCjkFallback(
                new java.awt.Font(java.awt.Font.DIALOG, java.awt.Font.PLAIN, 13)));
    }
}
