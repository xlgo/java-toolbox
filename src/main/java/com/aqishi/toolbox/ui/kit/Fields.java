package com.aqishi.toolbox.ui.kit;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPasswordField;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import java.awt.Dimension;

/**
 * 输入控件工厂：统一高度、字体与占位提示，避免每个面板各自 {@code setPreferredSize}。
 */
public final class Fields {

    private Fields() {
    }

    /** 单行文本框 */
    public static JTextField text(String value) {
        JTextField field = new JTextField(value == null ? "" : value);
        field.setFont(Tokens.fontBody());
        stretchable(field);
        return field;
    }

    /** 单行文本框，带占位提示 */
    public static JTextField text(String value, String placeholder) {
        JTextField field = text(value);
        if (placeholder != null) {
            field.putClientProperty("JTextField.placeholderText", placeholder);
        }
        return field;
    }

    /** 等宽单行输入，用于密钥、哈希、IP 等 */
    public static JTextField mono(String value) {
        JTextField field = text(value);
        field.setFont(Tokens.fontMono());
        return field;
    }

    /** 密码框 */
    public static JPasswordField password() {
        JPasswordField field = new JPasswordField();
        field.setFont(Tokens.fontBody());
        stretchable(field);
        return field;
    }

    /** 下拉框 */
    public static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> combo = new JComboBox<T>(items);
        combo.setFont(Tokens.fontBody());
        stretchable(combo);
        return combo;
    }

    /** 固定宽度的下拉框，用于工具条 */
    public static <T> JComboBox<T> combo(T[] items, int width) {
        JComboBox<T> combo = combo(items);
        combo.setPreferredSize(new Dimension(width, Tokens.CONTROL_HEIGHT));
        combo.setMinimumSize(new Dimension(Math.min(width, 80), Tokens.CONTROL_HEIGHT));
        return combo;
    }

    /** 数字微调器 */
    public static JSpinner spinner(int value, int min, int max, int step) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        spinner.setFont(Tokens.fontBody());
        Dimension size = new Dimension(96, Tokens.CONTROL_HEIGHT);
        spinner.setPreferredSize(size);
        spinner.setMinimumSize(size);
        return spinner;
    }

    /** 复选框 */
    public static JCheckBox check(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setFont(Tokens.fontBody());
        box.setFocusPainted(false);
        box.setOpaque(false);
        return box;
    }

    /** 单选框 */
    public static JRadioButton radio(String text, boolean selected) {
        JRadioButton button = new JRadioButton(text, selected);
        button.setFont(Tokens.fontBody());
        button.setFocusPainted(false);
        button.setOpaque(false);
        return button;
    }

    /** 表单标签（右对齐，与输入框基线对齐） */
    public static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Tokens.fontBody());
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    /** 说明文字（单行） */
    public static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Tokens.fontCaption());
        label.setForeground(Tokens.mutedForeground());
        return label;
    }

    /**
     * 会自动换行的说明文字。
     *
     * <p>{@link #caption(String)} 是单行的，长说明在窄容器里会被裁掉；这里按当前宽度
     * 重新计算所需高度，容器变窄时改为占更多行而不是被截断。</p>
     */
    public static JLabel note(String text) {
        WrappingNote label = new WrappingNote(text);
        label.setFont(Tokens.fontCaption());
        label.setForeground(Tokens.mutedForeground());
        return label;
    }

    /** 高度随宽度变化的说明标签 */
    private static final class WrappingNote extends JLabel {

        private final String plainText;

        WrappingNote(String text) {
            this.plainText = text == null ? "" : text;
            setVerticalAlignment(javax.swing.SwingConstants.TOP);
            setText(html(Integer.MAX_VALUE));
        }

        private String html(int width) {
            String escaped = plainText
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
            if (width == Integer.MAX_VALUE) {
                return "<html>" + escaped + "</html>";
            }
            return "<html><div style='width:" + Math.max(width, 40) + "px'>" + escaped + "</div></html>";
        }

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth();
            if (width <= 0) {
                return super.getPreferredSize();
            }
            java.awt.Insets insets = getInsets();
            setText(html(width - insets.left - insets.right));
            Dimension preferred = super.getPreferredSize();
            return new Dimension(width, preferred.height);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(40, getPreferredSize().height);
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            boolean widthChanged = width != getWidth();
            super.setBounds(x, y, width, height);
            if (widthChanged) {
                revalidate();
            }
        }
    }

    /** 多行文本域，等宽字体、自动换行 */
    public static JTextArea area(int rows, int columns) {
        JTextArea area = new JTextArea(rows, columns);
        area.setFont(Tokens.fontMono());
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(new EmptyBorder(Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM, Tokens.SPACE_SM));
        return area;
    }

    /** 只读输出文本域 */
    public static JTextArea output(int rows, int columns) {
        JTextArea area = area(rows, columns);
        area.setEditable(false);
        return area;
    }

    /** 无边框滚动容器：边框交给外层卡片，避免双层描边 */
    public static JScrollPane scroll(java.awt.Component view) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        scroll.setViewportBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        return scroll;
    }

    /**
     * 只锁定高度，宽度交给布局管理器。
     *
     * <p>保留一个温和的首选宽度，这样即便被放进 {@code FlowLayout} 也不会被压成 0；
     * 最小宽度取小值，配合 {@code fill=HORIZONTAL} 才能在窄窗口下继续收缩。</p>
     */
    /**
     * 带描边的滚动容器，用于放进「有内边距的卡片」里的文本域 / 表格。
     *
     * <p>卡片底色与文本域底色相同，直接嵌一个无边框滚动区会看不出边界；
     * 铺满型卡片（{@code Card.flush}）请继续用 {@link #scroll(java.awt.Component)}。</p>
     */
    public static JScrollPane scrollBoxed(java.awt.Component view) {
        JScrollPane scroll = scroll(view);
        scroll.setBorder(KitBorders.lineSubtle(1, 1, 1, 1));
        return scroll;
    }

    /**
     * 透明滚动容器，用于承载非文本视图（图片预览、自绘画布）。
     *
     * <p>默认视口是不透明的，会在卡片上露出一块 LAF 控件底色。</p>
     */
    public static JScrollPane scrollTransparent(java.awt.Component view) {
        JScrollPane scroll = scroll(view);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        return scroll;
    }

    /**
     * 只纵向滚动的容器：内容宽度跟随视口，不出现横向滚动条。
     *
     * <p>用于承载表单卡片堆叠、会换行的控件行这类「宽度应当自适应、只有高度会溢出」的内容。
     * 直接用 {@link #scroll(java.awt.Component)} 的话，视图会拿到自己的首选宽度并横向滚动。</p>
     */
    public static JScrollPane scrollVertical(java.awt.Component view) {
        JScrollPane scroll = scroll(new WidthTrackingBody(view));
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        return scroll;
    }

    /** 宽度跟随视口、高度按内容伸展的包装容器 */
    private static final class WidthTrackingBody extends javax.swing.JPanel
            implements javax.swing.Scrollable {

        WidthTrackingBody(java.awt.Component view) {
            super(new java.awt.BorderLayout());
            setOpaque(false);
            add(view, java.awt.BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL
                    ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static void stretchable(javax.swing.JComponent component) {
        Dimension preferred = component.getPreferredSize();
        int width = Math.max(preferred == null ? 0 : preferred.width, 200);
        // 上限很重要：初值很长的输入框（例如一整条 JDBC URL）会把整个表单的首选宽度推爆，
        // GridBagLayout 随后退化到最小尺寸排版，同一行的按钮就会被压到只剩省略号。
        component.setPreferredSize(new Dimension(Math.min(width, 320), Tokens.CONTROL_HEIGHT));
        component.setMinimumSize(new Dimension(56, Tokens.CONTROL_HEIGHT));
    }
}
