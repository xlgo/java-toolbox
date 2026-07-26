package com.aqishi.toolbox.ui.kit;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JToggleButton;
import java.awt.Dimension;

/**
 * 按钮语义工厂：主操作、次操作、文字操作、危险操作。
 *
 * <p>层级通过 FlatLaf 的 client property 表达（{@code JButton.buttonType}、
 * {@code JButton.borderless}），其它 LAF 会忽略这些属性并退回普通按钮，
 * 因此不需要硬编码颜色，也不会在非 FlatLaf 环境下画错。</p>
 */
public final class Buttons {

    private Buttons() {
    }

    /** 主操作：每个区域最多一个，用强调色填充 */
    public static JButton primary(String text) {
        JButton button = base(new JButton(text));
        button.putClientProperty("JButton.buttonType", "default");
        return button;
    }

    /** 次操作：常规描边按钮 */
    public static JButton secondary(String text) {
        return base(new JButton(text));
    }

    /** 文字按钮：低优先级操作，无边框 */
    public static JButton ghost(String text) {
        JButton button = base(new JButton(text));
        button.putClientProperty("JButton.borderless", Boolean.TRUE);
        return button;
    }

    /** 危险操作：删除、清空等不可逆动作 */
    public static JButton danger(String text) {
        JButton button = base(new JButton(text));
        button.setForeground(Tokens.danger());
        return button;
    }

    /**
     * 紧凑按钮：保留完整文字，但不套用 84px 的最小宽度。
     *
     * <p>用于密集按钮组（缩放档位、表单行尾的「自定义」之类），
     * 那些地方统一最小宽度会把整列撑宽。</p>
     */
    public static JButton snug(String text) {
        JButton button = new JButton(text);
        button.setFont(Tokens.fontBody());
        button.setFocusPainted(false);
        button.setMargin(new java.awt.Insets(2, Tokens.SPACE_SM, 2, Tokens.SPACE_SM));
        Dimension preferred = button.getPreferredSize();
        Dimension size = new Dimension(preferred.width, Tokens.CONTROL_HEIGHT);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        return button;
    }

    /** 图标 / 短文本方形按钮，用于工具条 */
    public static JButton compact(String text) {
        JButton button = new JButton(text);
        button.setFont(Tokens.fontBody());
        button.setFocusPainted(false);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        // 收窄内边距：默认边距会让 32×32 里的字形被省略成「...」
        button.setMargin(new java.awt.Insets(2, 4, 2, 4));
        Dimension size = new Dimension(Tokens.CONTROL_HEIGHT, Tokens.CONTROL_HEIGHT);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        return button;
    }

    /** 切换按钮，用于分段控制 */
    public static JToggleButton toggle(String text, boolean selected) {
        JToggleButton button = new JToggleButton(text, selected);
        button.setFont(Tokens.fontBody());
        button.setFocusPainted(false);
        applyHeight(button);
        return button;
    }

    private static JButton base(JButton button) {
        button.setFont(Tokens.fontBody());
        button.setFocusPainted(false);
        applyHeight(button);
        return button;
    }

    /**
     * 统一按钮高度，宽度按文本自适应。
     *
     * <p>最小宽度等于首选宽度：布局退化到最小尺寸时，宁可布局紧一点，也不要把
     * 「测试连接」压成「测…」——按钮文字被截断是比拥挤更严重的可用性问题。</p>
     */
    private static void applyHeight(AbstractButton button) {
        Dimension preferred = button.getPreferredSize();
        int width = Math.max(preferred.width + Tokens.SPACE_MD, 84);
        button.setPreferredSize(new Dimension(width, Tokens.CONTROL_HEIGHT));
        button.setMinimumSize(new Dimension(width, Tokens.CONTROL_HEIGHT));
    }
}
