package com.aqishi.toolbox.util;

import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

/**
 * UI 通用辅助方法：组件构建、间距、剪贴板、提示框。
 *
 * <p>尺寸与颜色全部委托给 {@link Tokens}，颜色跟随当前 LAF（外观包），不自定义颜色。
 * 保留这里的方法签名是为了让尚未逐个重构的工具面板也能直接得到新的卡片样式。</p>
 */
public final class UIUtils {

    private UIUtils() {
    }

    /** 统一内容区边距 */
    public static final EmptyBorder CONTENT_PADDING = new EmptyBorder(
            Tokens.SPACE_LG, Tokens.SPACE_LG, Tokens.SPACE_LG, Tokens.SPACE_LG);

    /** 工作台共享间距与侧栏尺寸 */
    public static final int SPACE_XS = Tokens.SPACE_XS;
    public static final int SPACE_SM = Tokens.SPACE_SM;
    public static final int SPACE_MD = Tokens.SPACE_MD;
    public static final int SPACE_LG = Tokens.SPACE_LG;
    public static final int SIDEBAR_MIN_WIDTH = 200;
    public static final int SIDEBAR_DEFAULT_WIDTH = 240;
    public static final int SIDEBAR_MAX_WIDTH = 340;
    public static final int NAV_ROW_HEIGHT = Tokens.NAV_ROW_HEIGHT;
    public static final int WORKBENCH_DIVIDER_SIZE = 1;

    /**
     * 创建带标题的滚动文本区。
     *
     * <p>标题不再使用 {@code TitledBorder} 的凹槽描边，而是画成卡片顶部的标题带，
     * 内外背景同色，因此文本域与卡片之间不会出现双层边框。</p>
     */
    public static JScrollPane scrollText(JTextArea area, String title) {
        area.setFont(monoFont());
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(new EmptyBorder(
                Tokens.SPACE_SM, Tokens.SPACE_MD, Tokens.SPACE_SM, Tokens.SPACE_SM));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(KitBorders.card(title));
        scroll.setViewportBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** 创建无标题的滚动文本区 */
    public static JScrollPane scrollText(JTextArea area) {
        return scrollText(area, null);
    }

    /** 等宽字体 */
    public static Font monoFont() {
        return Tokens.fontMono();
    }

    /** 普通字体 */
    public static Font plainFont() {
        return Tokens.fontBody();
    }

    /** 标题字体 */
    public static Font titleFont() {
        return Tokens.fontTitle();
    }

    /** 说明字体 */
    public static Font captionFont() {
        return Tokens.fontCaption();
    }

    /** 次要文字颜色 */
    public static Color mutedColor() {
        return Tokens.mutedForeground();
    }

    /**
     * 按钮，统一高度。
     *
     * <p>{@code width} 作为首选宽度的下限使用，文本更长时按钮会自动加宽，
     * 避免中英文切换后文字被截断。</p>
     */
    public static JButton button(String text, int width) {
        return sized(Buttons.secondary(text), width);
    }

    /** 主操作按钮 */
    public static JButton primaryButton(String text, int width) {
        return sized(Buttons.primary(text), width);
    }

    private static JButton sized(JButton button, int width) {
        Dimension preferred = button.getPreferredSize();
        int finalWidth = Math.max(width, preferred.width);
        button.setPreferredSize(new Dimension(finalWidth, Tokens.CONTROL_HEIGHT));
        return button;
    }

    /** 把字符串写入系统剪贴板 */
    public static void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text == null ? "" : text), null);
    }

    /** 弹出信息提示 */
    public static void info(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, text("dialog.info", "提示"),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** 弹出错误提示 */
    public static void error(Component parent, String msg) {
        JOptionPane.showMessageDialog(parent, msg, text("dialog.error", "错误"),
                JOptionPane.ERROR_MESSAGE);
    }

    /** 弹出输入框，返回 null 表示取消 */
    public static String input(Component parent, String msg, String def) {
        return (String) JOptionPane.showInputDialog(parent, msg, text("dialog.input", "输入"),
                JOptionPane.PLAIN_MESSAGE, null, null, def);
    }

    /** 取本地化文案，缺失时回退到内置默认值 */
    private static String text(String key, String fallback) {
        String value = I18n.get(key);
        return key.equals(value) ? fallback : value;
    }

    /**
     * 校验窗口位置 (x, y, width, height) 是否落在当前已连接的任意显示屏可见区域内。
     * 解决多显示器/副屏断开后，旧窗口坐标越界导致窗口不可见的致命问题。
     */
    public static boolean isWindowPositionVisible(int x, int y, int width, int height) {
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice[] screens = ge.getScreenDevices();
            Rectangle winBounds = new Rectangle(x, y, width, height);
            Rectangle topBarBounds = new Rectangle(x, y, Math.min(100, width), Math.min(30, height));

            for (GraphicsDevice screen : screens) {
                Rectangle screenBounds = screen.getDefaultConfiguration().getBounds();
                Rectangle intersection = screenBounds.intersection(winBounds);
                Rectangle topIntersection = screenBounds.intersection(topBarBounds);
                if (topIntersection.width >= 30 && topIntersection.height >= 20 
                        && intersection.width >= 100 && intersection.height >= 100) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
