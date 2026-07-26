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

import javax.swing.*;
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

        Theme(String name, Class<? extends FlatLaf> lafClass, String lafClassName, boolean dark) {
            this.name = name;
            this.lafClass = lafClass;
            this.lafClassName = lafClassName;
            this.dark = dark;
        }
    }

    /** 内置主题列表：6 个核心 LAF + 48 个 IntelliJ 主题 */
    private static final List<Theme> THEMES = buildThemes();

    private static List<Theme> buildThemes() {
        List<Theme> list = new ArrayList<>();
        // 核心 LAF
        list.add(new Theme("Flat Light（浅色）", FlatLightLaf.class, null, false));
        list.add(new Theme("Flat Dark（深色）", FlatDarkLaf.class, null, true));
        list.add(new Theme("IntelliJ（默认）", FlatIntelliJLaf.class, null, false));
        list.add(new Theme("Darcula", FlatDarculaLaf.class, null, true));
        list.add(new Theme("macOS Light", FlatMacLightLaf.class, null, false));
        list.add(new Theme("macOS Dark", FlatMacDarkLaf.class, null, true));
        // flatlaf-intellij-themes 包中所有主题（每个主题是独立的 FlatLaf 子类）
        for (FlatAllIJThemes.FlatIJLookAndFeelInfo info : FlatAllIJThemes.INFOS) {
            list.add(new Theme(info.getName(), null, info.getClassName(), info.isDark()));
        }
        return Collections.unmodifiableList(list);
    }

    private static Theme current = THEMES.get(2); // 默认 IntelliJ

    private ThemeManager() {
    }

    public static Theme current() {
        return current;
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
            FlatLaf.setup(laf);
            applyCustomDefaults();
            current = t;
        } catch (Throwable e) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
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
            // 必须在 setup 之后写入：setLookAndFeel 会重建 defaults 表
            applyCustomDefaults();
            FlatLaf.updateUI();
            current = t;

            // 保存配置
            ConfigManager.set("theme", t.name);
            ConfigManager.save();
        } catch (Throwable e) {
            // 失败回退系统 LAF
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
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
    private static void applyCustomDefaults() {
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
        UIManager.put("Component.arc", arc);
        UIManager.put("TextComponent.arc", arc);
        UIManager.put("CheckBox.arc", 4);
        UIManager.put("ProgressBar.arc", arc);
        UIManager.put("ProgressBar.horizontalSize", new java.awt.Dimension(146, 6));

        // 焦点描边收细一点，减少高密度表单里的视觉噪音
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 1);

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

        // 表格：去掉纵向网格线，改用行高与细横线表达结构
        UIManager.put("Table.rowHeight", com.aqishi.toolbox.ui.kit.Tokens.TABLE_ROW_HEIGHT);
        UIManager.put("Table.showHorizontalLines", Boolean.TRUE);
        UIManager.put("Table.showVerticalLines", Boolean.FALSE);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 1));
        UIManager.put("TableHeader.height", 28);
        UIManager.put("TableHeader.separatorColor", com.aqishi.toolbox.ui.kit.Tokens.borderSubtle());

        // 标签页：下划线样式比方框样式更贴近现代桌面产品
        UIManager.put("TabbedPane.tabHeight", 34);
        UIManager.put("TabbedPane.tabType", "underlined");
        UIManager.put("TabbedPane.showTabSeparators", Boolean.FALSE);
        UIManager.put("TabbedPane.tabsPopupPolicy", "asNeeded");
        UIManager.put("TabbedPane.scrollButtonsPolicy", "asNeeded");
        UIManager.put("TabbedPane.tabAreaInsets", new javax.swing.plaf.InsetsUIResource(0, 4, 0, 0));

        // 分隔条：细线，仍可拖动
        UIManager.put("SplitPane.oneTouchButtonSize", 0);
        UIManager.put("SplitPaneDivider.gripDotCount", 0);

        // 弹出与工具提示
        UIManager.put("PopupMenu.borderInsets", new javax.swing.plaf.InsetsUIResource(4, 2, 4, 2));
        UIManager.put("ToolTip.border", new javax.swing.plaf.BorderUIResource(
                javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)));
    }
}
