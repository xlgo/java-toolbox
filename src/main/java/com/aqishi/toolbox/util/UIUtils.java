package com.aqishi.toolbox.util;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * UI 通用辅助方法：组件构建、间距、剪贴板、提示框。
 * <p>颜色完全跟随当前 LAF（外观包），不再自定义颜色。</p>
 */
public final class UIUtils {

    private UIUtils() {
    }

    /** 统一内容区边距 */
    public static final EmptyBorder CONTENT_PADDING = new EmptyBorder(14, 16, 14, 16);

    /** 工作台共享间距与侧栏尺寸 */
    public static final int SPACE_XS = 4;
    public static final int SPACE_SM = 8;
    public static final int SPACE_MD = 12;
    public static final int SPACE_LG = 16;
    public static final int SIDEBAR_MIN_WIDTH = 190;
    public static final int SIDEBAR_DEFAULT_WIDTH = 228;
    public static final int SIDEBAR_MAX_WIDTH = 320;
    public static final int NAV_ROW_HEIGHT = 32;
    public static final int WORKBENCH_DIVIDER_SIZE = 5;

    /** 创建带标题的滚动文本区 */
    public static JScrollPane scrollText(JTextArea area, String title) {
        area.setFont(monoFont());
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        JScrollPane sp = new JScrollPane(area);
        sp.setBorder(BorderFactory.createTitledBorder(
                null, title, TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION, plainFont(),
                UIManager.getColor("Component.accentColor")));
        return sp;
    }

    /** 等宽字体 */
    public static Font monoFont() {
        Font f = new Font("Microsoft YaHei", Font.PLAIN, 13);
        if ("Dialog".equals(f.getFamily())) {
            return new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        return f;
    }

    /** 普通字体 */
    public static Font plainFont() {
        Font f = UIManager.getFont("Label.font");
        return f == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 13) : f.deriveFont(13f);
    }

    /** 标题字体 */
    public static Font titleFont() {
        return plainFont().deriveFont(Font.BOLD, 15f);
    }

    /** 按钮，统一尺寸 */
    public static JButton button(String text, int width) {
        JButton b = new JButton(text);
        int height = Math.max(32, b.getPreferredSize().height);
        b.setPreferredSize(new Dimension(width, height));
        b.setFocusPainted(false);
        b.setFont(plainFont());
        return b;
    }

    /** 把字符串写入系统剪贴板 */
    public static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text == null ? "" : text), null);
    }

    /** 弹出信息提示 */
    public static void info(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 弹出错误提示 */
    public static void error(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, "错误", JOptionPane.ERROR_MESSAGE);
    }

    /** 弹出输入框，返回 null 表示取消 */
    public static String input(Component parent, String msg, String def) {
        return (String) JOptionPane.showInputDialog(parent, msg, "输入",
                JOptionPane.PLAIN_MESSAGE, null, null, def);
    }
}
