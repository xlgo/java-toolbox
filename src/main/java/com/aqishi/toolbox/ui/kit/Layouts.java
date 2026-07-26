package com.aqishi.toolbox.ui.kit;

import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;

/**
 * 页面骨架工具：统一的内容区边距、卡片纵向堆叠、等宽分栏与分隔条。
 *
 * <p>所有工具面板都用 {@link #page()} 作为根容器，保证工具之间的外边距一致。</p>
 */
public final class Layouts {

    private Layouts() {
    }

    /** 工具页根容器：统一外边距 + BorderLayout */
    public static JPanel page() {
        JPanel panel = new JPanel(new BorderLayout(Tokens.SPACE_LG, Tokens.SPACE_LG));
        panel.setOpaque(false);
        panel.setBorder(new EmptyBorder(
                Tokens.SPACE_LG, Tokens.SPACE_LG, Tokens.SPACE_LG, Tokens.SPACE_LG));
        return panel;
    }

    /** 无外边距的透明 BorderLayout 容器 */
    public static JPanel box() {
        return box(0, 0);
    }

    /** 无外边距的透明 BorderLayout 容器，可指定间距 */
    public static JPanel box(int horizontalGap, int verticalGap) {
        JPanel panel = new JPanel(new BorderLayout(horizontalGap, verticalGap));
        panel.setOpaque(false);
        return panel;
    }

    /**
     * 纵向堆叠：子组件按顺序自上而下排列，宽度撑满、高度取各自首选高度。
     *
     * <p>用一层包装容器动态回报最大高度，而不是在构造时把 {@code maximumSize} 锁死——
     * 否则内容高度随宽度变化的子组件（例如会换行的复选框行）在窗口变窄后会被切掉。</p>
     */
    public static JPanel stack(int gap, Component... children) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (int i = 0; i < children.length; i++) {
            if (i > 0 && gap > 0) {
                panel.add(javax.swing.Box.createVerticalStrut(gap));
            }
            Component child = children[i];
            if (child instanceof JComponent) {
                ((JComponent) child).setAlignmentX(Component.LEFT_ALIGNMENT);
            }
            StackItem item = new StackItem(child);
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(item);
        }
        return panel;
    }

    /** 只把高度限制在子组件当前首选高度，宽度放开 */
    private static final class StackItem extends JPanel {
        StackItem(Component child) {
            super(new BorderLayout());
            setOpaque(false);
            add(child, BorderLayout.CENTER);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /**
     * 会换行的横向流式行，并且换行后的高度会计入首选高度。
     *
     * <p>{@link java.awt.FlowLayout} 折行后首选高度仍按一行计算，放进 {@code GridBagLayout}
     * 会把折下去的那几行整排裁掉；这里按当前宽度重新计算所需高度，并把最小宽度压到单个子组件，
     * 这样窄窗口下才会真的换行而不是把行尾控件挤没。</p>
     */
    public static JPanel wrapRow(int horizontalGap, int verticalGap, Component... children) {
        WrapRow panel = new WrapRow(horizontalGap, verticalGap);
        for (Component child : children) {
            panel.add(child);
        }
        return panel;
    }

    /** 会换行的横向流式行，使用默认间距 */
    public static JPanel wrapRow(Component... children) {
        return wrapRow(Tokens.SPACE_MD, Tokens.SPACE_XS, children);
    }

    private static final class WrapRow extends JPanel {

        WrapRow(int horizontalGap, int verticalGap) {
            super(new WrapLayout(horizontalGap, verticalGap));
            setOpaque(false);
            // 宽度变化会改变行数，因而改变所需高度：必须让上层重新做一次布局
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    revalidate();
                }
            });
        }
    }

    /**
     * 自动换行的行布局。
     *
     * <p>相比 {@link java.awt.FlowLayout}：首选高度按**当前可用宽度**计算，所以折行后的高度会
     * 被上层正确分配；首尾不留额外空隙，整行能与表单输入列左边缘对齐；最小高度按单行计算，
     * 避免 {@code GridBagLayout} 退化到最小尺寸时把这一行撑成「每行一个控件」的巨块。</p>
     */
    private static final class WrapLayout implements java.awt.LayoutManager {

        private final int horizontalGap;
        private final int verticalGap;

        WrapLayout(int horizontalGap, int verticalGap) {
            this.horizontalGap = horizontalGap;
            this.verticalGap = verticalGap;
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container target) {
            return measure(target, availableWidth(target));
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container target) {
            // 最小尺寸只保证「一行放得下最宽的那个控件」，高度按单行算：
            // 真正需要的高度由 preferredLayoutSize 在已知宽度后给出。
            java.awt.Insets insets = target.getInsets();
            int widest = 0;
            int tallest = 0;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                Dimension size = child.getPreferredSize();
                widest = Math.max(widest, size.width);
                tallest = Math.max(tallest, size.height);
            }
            return new Dimension(
                    widest + insets.left + insets.right,
                    tallest + insets.top + insets.bottom);
        }

        /** 容器自身还没有宽度时（首次布局），向上找一个已经有宽度的祖先 */
        private int availableWidth(java.awt.Container target) {
            int width = target.getWidth();
            java.awt.Container cursor = target;
            while (width <= 0 && cursor.getParent() != null) {
                cursor = cursor.getParent();
                width = cursor.getWidth();
            }
            return width <= 0 ? Integer.MAX_VALUE : width;
        }

        private Dimension measure(java.awt.Container target, int width) {
            java.awt.Insets insets = target.getInsets();
            int available = Math.max(1, width - insets.left - insets.right);
            int rowWidth = 0;
            int rowHeight = 0;
            int totalWidth = 0;
            int totalHeight = 0;
            boolean firstRow = true;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                Dimension size = child.getPreferredSize();
                int advance = rowWidth == 0 ? size.width : size.width + horizontalGap;
                if (rowWidth > 0 && rowWidth + advance > available) {
                    totalWidth = Math.max(totalWidth, rowWidth);
                    totalHeight += rowHeight + (firstRow ? 0 : verticalGap);
                    firstRow = false;
                    rowWidth = size.width;
                    rowHeight = size.height;
                } else {
                    rowWidth += advance;
                    rowHeight = Math.max(rowHeight, size.height);
                }
            }
            totalWidth = Math.max(totalWidth, rowWidth);
            totalHeight += rowHeight + (firstRow ? 0 : verticalGap);
            return new Dimension(
                    totalWidth + insets.left + insets.right,
                    totalHeight + insets.top + insets.bottom);
        }

        @Override
        public void layoutContainer(java.awt.Container target) {
            java.awt.Insets insets = target.getInsets();
            int available = Math.max(1, target.getWidth() - insets.left - insets.right);
            int x = insets.left;
            int y = insets.top;
            int rowHeight = 0;
            boolean firstInRow = true;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                Dimension size = child.getPreferredSize();
                int advance = firstInRow ? size.width : size.width + horizontalGap;
                if (!firstInRow && (x - insets.left) + advance > available) {
                    x = insets.left;
                    y += rowHeight + verticalGap;
                    rowHeight = 0;
                    firstInRow = true;
                    advance = size.width;
                }
                if (!firstInRow) {
                    x += horizontalGap;
                }
                child.setBounds(x, y, size.width, size.height);
                x += size.width;
                rowHeight = Math.max(rowHeight, size.height);
                firstInRow = false;
            }
        }
    }

    /** 纵向堆叠，默认卡片间距 */
    public static JPanel stack(Component... children) {
        return stack(Tokens.SPACE_LG, children);
    }

    /** 等宽分栏 */
    public static JPanel columns(int gap, Component... children) {
        JPanel panel = new JPanel(new GridLayout(1, children.length, gap, 0));
        panel.setOpaque(false);
        for (Component child : children) {
            panel.add(child);
        }
        return panel;
    }

    /** 等高分行 */
    public static JPanel rows(int gap, Component... children) {
        JPanel panel = new JPanel(new GridLayout(children.length, 1, 0, gap));
        panel.setOpaque(false);
        for (Component child : children) {
            panel.add(child);
        }
        return panel;
    }

    /** 无边框分隔面板，分隔条细而可拖动 */
    public static JSplitPane split(int orientation, Component first, Component second, double weight) {
        JSplitPane split = new JSplitPane(orientation, first, second);
        split.setBorder(null);
        split.setOpaque(false);
        split.setContinuousLayout(true);
        split.setDividerSize(Tokens.SPACE_SM);
        split.setResizeWeight(weight);
        return split;
    }

    /** 左右分隔 */
    public static JSplitPane splitHorizontal(Component left, Component right, double weight) {
        return split(JSplitPane.HORIZONTAL_SPLIT, left, right, weight);
    }

    /** 上下分隔 */
    public static JSplitPane splitVertical(Component top, Component bottom, double weight) {
        return split(JSplitPane.VERTICAL_SPLIT, top, bottom, weight);
    }

    /**
     * 左右分隔，并按比例设置初始分隔位置。
     *
     * <p>{@code resizeWeight} 只影响「窗口变化时多余空间怎么分」，初始位置仍取决于子组件首选宽度，
     * 常常会让一侧一上来就只剩一条缝。这里在第一次拿到实际尺寸时按比例摆一次分隔条。</p>
     *
     * @param initialRatio 左侧初始占比，0 到 1
     */
    public static JSplitPane splitHorizontal(
            Component left, Component right, double weight, double initialRatio) {
        return placeDivider(split(JSplitPane.HORIZONTAL_SPLIT, left, right, weight), initialRatio);
    }

    /** 上下分隔，并按比例设置初始分隔位置 */
    public static JSplitPane splitVertical(
            Component top, Component bottom, double weight, double initialRatio) {
        return placeDivider(split(JSplitPane.VERTICAL_SPLIT, top, bottom, weight), initialRatio);
    }

    /**
     * 在拿到实际尺寸后按比例摆一次分隔条，并用两侧子组件的最小尺寸夹住结果。
     *
     * <p>两点容易踩坑：</p>
     * <ul>
     *   <li>嵌套分隔面板时，外层 {@code setDividerLocation} 要到下一轮布局才生效。若在
     *       {@code componentResized} 里立即读取内层宽度，拿到的还是旧值，比例就会算错——
     *       所以这里推迟到下一次事件循环再摆放，让外层先落位。</li>
     *   <li>{@code setDividerLocation} 只在拖动时受最小尺寸约束，程序设置不受约束，
     *       比例可能把一侧压到内容装不下。这里显式夹一次。</li>
     * </ul>
     */
    private static JSplitPane placeDivider(final JSplitPane split, final double ratio) {
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean placed;

            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                if (placed) {
                    return;
                }
                if (extent(split) <= 0) {
                    return;
                }
                placed = true;
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        applyRatio(split, ratio);
                    }
                });
            }
        });
        return split;
    }

    private static int extent(JSplitPane split) {
        return split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                ? split.getWidth()
                : split.getHeight();
    }

    private static void applyRatio(JSplitPane split, double ratio) {
        int total = extent(split);
        if (total <= 0) {
            return;
        }
        boolean horizontal = split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT;
        int usable = total - split.getDividerSize();
        int location = (int) Math.round(usable * ratio);
        int firstMin = minimumExtent(split.getLeftComponent(), horizontal);
        int secondMin = minimumExtent(split.getRightComponent(), horizontal);
        location = Math.max(location, firstMin);
        location = Math.min(location, usable - secondMin);
        if (location > 0 && location < usable) {
            split.setDividerLocation(location);
        }
    }

    private static int minimumExtent(Component component, boolean horizontal) {
        if (component == null) {
            return 0;
        }
        Dimension minimum = component.getMinimumSize();
        return horizontal ? minimum.width : minimum.height;
    }
}
