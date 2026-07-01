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
            applyCustomDefaults();
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
            current = t;
        } catch (Throwable e) {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
        }
    }

    /** 切换到指定主题，整窗带动画刷新 */
    public static void apply(String name) {
        Theme t = get(name);
        if (t == null) return;
        try {
            FlatAnimatedLafChange.showSnapshot();
            applyCustomDefaults();

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

    private static void applyCustomDefaults() {
        UIManager.put("Button.minimumHeight", 32);
        UIManager.put("TextField.minimumHeight", 32);
        UIManager.put("ComboBox.minimumHeight", 32);
    }
}
