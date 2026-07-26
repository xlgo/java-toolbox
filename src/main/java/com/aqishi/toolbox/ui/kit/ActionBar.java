package com.aqishi.toolbox.ui.kit;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;

/**
 * 一行操作条：左侧放说明或过滤控件，右侧放按钮。
 *
 * <p>用 {@code BoxLayout} 而不是 {@code FlowLayout}，这样窗口变窄时右侧按钮不会换行到下一行，
 * 而是由中间的弹性空间先被压缩。</p>
 */
public class ActionBar extends JPanel {

    private final Component glue = Box.createHorizontalGlue();
    private boolean glueAdded;

    public ActionBar() {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
    }

    /** 左侧添加控件 */
    public ActionBar left(Component component) {
        if (glueAdded) {
            throw new IllegalStateException("left() must be called before right()");
        }
        if (getComponentCount() > 0) {
            add(Box.createHorizontalStrut(Tokens.SPACE_SM));
        }
        add(cap(component));
        return this;
    }

    /** 右侧添加控件，按调用顺序从左到右排列 */
    public ActionBar right(Component component) {
        if (!glueAdded) {
            add(glue);
            // 左右两组之间保底留一段间距，窄窗口下 glue 被压到 0 时文字才不会贴在一起
            add(Box.createHorizontalStrut(Tokens.SPACE_MD));
            glueAdded = true;
        } else {
            add(Box.createHorizontalStrut(Tokens.SPACE_SM));
        }
        add(cap(component));
        return this;
    }

    /**
     * 把子组件的最大宽度收到首选宽度。
     *
     * <p>{@code JComboBox}、{@code JTextField} 的默认最大宽度是无限大，直接放进
     * {@code BoxLayout} 会被拉成几倍宽，{@code Fields.combo(items, width)} 指定的宽度就失效了。
     * 文本输入框是例外：它本来就该吃掉多余空间。</p>
     */
    private static Component cap(Component component) {
        if (component instanceof javax.swing.text.JTextComponent
                || component instanceof javax.swing.JLabel) {
            return component;
        }
        if (component instanceof javax.swing.JComponent) {
            javax.swing.JComponent target = (javax.swing.JComponent) component;
            Dimension preferred = target.getPreferredSize();
            target.setMaximumSize(new Dimension(preferred.width, Integer.MAX_VALUE));
        }
        return component;
    }

    /** 插入一段固定间隔 */
    public ActionBar gap(int width) {
        add(Box.createHorizontalStrut(width));
        return this;
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, preferred.height);
    }
}
