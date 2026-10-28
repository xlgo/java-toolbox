package com.aqishi.toolbox.ui.kit;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * 一行操作条：左侧放说明或过滤控件，右侧放按钮。
 *
 * <p>效果页的操作行在窗口变窄时不会把按钮挤出卡片：右侧按钮先整体折到第二行，
 * 仍然放不下就在第二行内继续换行，左侧说明占据第一行。因此这里不用
 * {@code BoxLayout}（它会把超出宽度的按钮直接裁掉），而是用两段可换行的容器
 * 加一个按宽度决策的布局。</p>
 */
public class ActionBar extends JPanel {

    private static final int GAP = Tokens.SPACE_MD;

    private final JPanel leftBox = new JPanel(
            new WrapRowLayout(Tokens.SPACE_SM, Tokens.SPACE_SM, WrapRowLayout.Align.LEFT));
    private final JPanel rightBox = new JPanel(
            new WrapRowLayout(Tokens.SPACE_SM, Tokens.SPACE_SM, WrapRowLayout.Align.RIGHT));

    public ActionBar() {
        setOpaque(false);
        setLayout(new BarLayout());
        leftBox.setOpaque(false);
        rightBox.setOpaque(false);
        add(leftBox);
        add(rightBox);
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                revalidate();
            }
        });
    }

    /** 左侧添加控件 */
    public ActionBar left(Component component) {
        leftBox.add(cap(component));
        return this;
    }

    /** 右侧添加控件，按调用顺序从左到右排列 */
    public ActionBar right(Component component) {
        rightBox.add(cap(component));
        return this;
    }

    /** 插入一段固定间隔 */
    public ActionBar gap(int width) {
        leftBox.add(javax.swing.Box.createHorizontalStrut(width));
        return this;
    }

    /**
     * 把子组件的最大宽度收到首选宽度。
     *
     * <p>{@code JComboBox}、{@code JTextField} 的默认最大宽度是无限大，直接放进
     * 横向布局会被拉成几倍宽，{@code Fields.combo(items, width)} 指定的宽度就失效了。
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

    @Override
    public Dimension getMaximumSize() {
        Dimension preferred = getPreferredSize();
        return new Dimension(Integer.MAX_VALUE, preferred.height);
    }

    /** 左说明 + 右按钮；放不下时按钮整体折到下一行，并在行内继续换行 */
    private final class BarLayout implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) { }

        @Override
        public void removeLayoutComponent(Component component) { }

        private int available() {
            Insets insets = getInsets();
            int width = getWidth();
            java.awt.Container cursor = ActionBar.this;
            while (width <= 0 && cursor.getParent() != null) {
                cursor = cursor.getParent();
                width = cursor.getWidth();
            }
            return width <= 0 ? Integer.MAX_VALUE : Math.max(1, width - insets.left - insets.right);
        }

        private int leftWidth() {
            return leftBox.getComponentCount() == 0 ? 0 : leftBox.getPreferredSize().width;
        }

        private int rightWidth() {
            return rightBox.getComponentCount() == 0 ? 0 : rightBox.getPreferredSize().width;
        }

        private boolean stacked(int available) {
            int right = rightWidth();
            if (right == 0) return false;
            int left = leftWidth();
            return left + (left > 0 ? GAP : 0) + right > available;
        }

        private int wrappedRightHeight(int available) {
            return rightBox.getLayout() instanceof WrapRowLayout wrap
                    ? wrap.heightFor(rightBox, available) : rightBox.getPreferredSize().height;
        }

        private int wrappedLeftHeight(int available) {
            return leftBox.getLayout() instanceof WrapRowLayout wrap
                    ? wrap.heightFor(leftBox, available) : leftBox.getPreferredSize().height;
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            Insets insets = parent.getInsets();
            int available = available();
            int left = leftWidth();
            int right = rightWidth();
            int leftHeight = left == 0 ? 0 : wrappedLeftHeight(Math.max(available, left));
            int rightHeight = right == 0 ? 0 : rightBox.getPreferredSize().height;
            int height;
            if (stacked(available)) {
                height = leftHeight + (rightHeight > 0 ? Tokens.SPACE_SM + rightHeight : 0);
            } else {
                height = Math.max(leftHeight, rightHeight);
            }
            int width = left + (right > 0 && left > 0 ? GAP : 0) + right;
            return new Dimension(width + insets.left + insets.right,
                    height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            Insets insets = parent.getInsets();
            int available = available();
            int left = leftWidth();
            int right = rightWidth();
            boolean stack = stacked(available);

            if (left == 0 && right == 0) {
                return;
            }
            if (stack) {
                int leftHeight = left == 0 ? 0 : wrappedLeftHeight(available);
                if (left > 0) {
                    leftBox.setBounds(insets.left, insets.top, available, leftHeight);
                }
                int y = insets.top + leftHeight + (left > 0 ? Tokens.SPACE_SM : 0);
                int rightHeight = wrappedRightHeight(available);
                rightBox.setBounds(insets.left, y, available, rightHeight);
                return;
            }

            int leftHeight = left == 0 ? 0 : leftBox.getPreferredSize().height;
            int rightHeight = right == 0 ? 0 : rightBox.getPreferredSize().height;
            int height = Math.max(leftHeight, rightHeight);
            if (left > 0) {
                leftBox.setBounds(insets.left, insets.top + (height - leftHeight) / 2, left, leftHeight);
            }
            if (right > 0) {
                rightBox.setBounds(insets.left + available - right,
                        insets.top + (height - rightHeight) / 2, right, rightHeight);
            }
        }
    }
}
