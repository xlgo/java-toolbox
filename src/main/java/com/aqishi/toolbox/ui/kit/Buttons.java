package com.aqishi.toolbox.ui.kit;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JToggleButton;
import java.awt.Dimension;

/**
 * 按钮语义工厂：主操作、次操作、文字操作、危险操作。
 *
 * <p>层级通过 FlatLaf 的 client property 与主题样式类表达，
 * 其它 LAF 会忽略这些属性并退回普通按钮，
 * 因此不需要硬编码颜色，也不会在非 FlatLaf 环境下画错。</p>
 */
public final class Buttons {

    private Buttons() {
    }

    /** 主操作：每个区域最多一个，用强调色填充 */
    public static JButton primary(String text) {
        JButton button = new JButton(text);
        button.putClientProperty("FlatLaf.styleClass", "primary");
        button.setFont(Tokens.fontBodyStrong());
        button.setFocusPainted(true);
        applyHeight(button);
        return button;
    }

    /** 次操作：常规描边按钮 */
    public static JButton secondary(String text) {
        return base(new JButton(text));
    }

    /** 文字按钮：低优先级操作，无边框 */
    public static JButton ghost(String text) {
        JButton button = base(new JButton(text));
        button.putClientProperty("JButton.buttonType", "borderless");
        return button;
    }

    /** 危险操作：删除、清空等不可逆动作 */
    public static JButton danger(String text) {
        JButton button = base(new JButton(text));
        button.putClientProperty("FlatLaf.styleClass", "danger");
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
        button.setFocusPainted(true);
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
        button.setFocusPainted(true);
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        // 收窄内边距：默认边距会让 32×32 里的字形被省略成「...」
        button.setMargin(new java.awt.Insets(2, 4, 2, 4));
        Dimension size = new Dimension(Tokens.CONTROL_HEIGHT, Tokens.CONTROL_HEIGHT);
        button.setPreferredSize(size);
        button.setMinimumSize(size);
        return button;
    }

    /**
     * 带图标的次操作按钮。
     *
     * <p>图标必须在计算尺寸之前设置，否则会沿用「只有文字」时钉死的首选尺寸，
     * 加上图标后文字被压成省略号。</p>
     */
    public static JButton secondary(String text, javax.swing.Icon icon) {
        JButton button = new JButton(text);
        button.setIcon(icon);
        button.setIconTextGap(Tokens.SPACE_XS + 2);
        return base(button);
    }

    /** 后加图标或改文案后重新按当前内容钉一次尺寸 */
    public static void refit(AbstractButton button) {
        applyHeight(button);
    }

    /** 切换按钮，用于分段控制 */
    public static JToggleButton toggle(String text, boolean selected) {
        JToggleButton button = new JToggleButton(text, selected);
        button.setFont(Tokens.fontBody());
        button.setFocusPainted(true);
        applyHeight(button);
        return button;
    }

    private static JButton base(JButton button) {
        button.setFont(Tokens.fontBody());
        button.setFocusPainted(true);
        applyHeight(button);
        return button;
    }

    /**
     * 统一按钮高度，宽度按文本自适应。
     *
     * <p>效果页的按钮是贴合文字的（padding 8/15），没有 84px 的最小宽度；
     * 一排 5–6 个操作在窄窗口下也能放下。最小宽度只留一个很小的下限，
     * 避免单字按钮被压成一个小方块。</p>
     */
    private static void applyHeight(AbstractButton button) {
        Dimension preferred = button.getPreferredSize();
        // 首选宽度贴合文字；最小宽度也不低于文字宽度——被压窄时宁可让整行换行，
        // 也不要出现「复制…」这种把操作名截断的按钮。
        int textWidth = preferred.width;
        int width = Math.max(textWidth, 64);
        button.setPreferredSize(new Dimension(width, Tokens.CONTROL_HEIGHT));
        button.setMinimumSize(new Dimension(textWidth, Tokens.CONTROL_HEIGHT));
    }
}
