package com.aqishi.toolbox.ui.kit;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * 自动换行的横向布局，高度按当前可用宽度计算。
 *
 * <p>相比 {@link java.awt.FlowLayout}：首选高度会计入折行后的行数，
 * 因此放进 {@code BorderLayout.NORTH} 或纵向堆叠时不会被裁掉第二行；
 * 最小高度按单行计算，布局退化时不会把整行撑成「每行一个控件」。
 * 首尾不留额外空隙，整行能与表单列左边缘对齐。</p>
 */
public final class WrapRowLayout implements LayoutManager {

    /** 行内对齐方式：左侧说明左对齐，操作按钮右对齐 */
    public enum Align { LEFT, RIGHT }

    private final int horizontalGap;
    private final int verticalGap;
    private final Align align;

    public WrapRowLayout(int horizontalGap, int verticalGap) {
        this(horizontalGap, verticalGap, Align.LEFT);
    }

    public WrapRowLayout(int horizontalGap, int verticalGap, Align align) {
        this.horizontalGap = horizontalGap;
        this.verticalGap = verticalGap;
        this.align = align == null ? Align.LEFT : align;
    }

    @Override
    public void addLayoutComponent(String name, Component component) {
    }

    @Override
    public void removeLayoutComponent(Component component) {
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return measure(target, availableWidth(target));
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Insets insets = target.getInsets();
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
        return new Dimension(widest + insets.left + insets.right,
                tallest + insets.top + insets.bottom);
    }

    /** 容器自身还没有宽度时（首次布局），向上找一个已经有宽度的祖先 */
    private int availableWidth(Container target) {
        int width = target.getWidth();
        Container cursor = target;
        while (width <= 0 && cursor.getParent() != null) {
            cursor = cursor.getParent();
            width = cursor.getWidth();
        }
        return width <= 0 ? Integer.MAX_VALUE : width;
    }

    /** 在给定宽度下需要的高度 */
    public int heightFor(Container target, int width) {
        return measure(target, width).height;
    }

    private Dimension measure(Container target, int width) {
        Insets insets = target.getInsets();
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
        return new Dimension(totalWidth + insets.left + insets.right,
                totalHeight + insets.top + insets.bottom);
    }

    @Override
    public void layoutContainer(Container target) {
        Insets insets = target.getInsets();
        int available = Math.max(1, target.getWidth() - insets.left - insets.right);
        // 右对齐：卡片标题行的动作按钮贴着卡片右边缘
        int y = insets.top;
        int rowHeight = 0;
        java.util.List<Component> row = new java.util.ArrayList<>();
        java.util.List<java.awt.Rectangle> rowBounds = new java.util.ArrayList<>();
        int rowWidth = 0;

        for (Component child : target.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            Dimension size = child.getPreferredSize();
            int advance = row.isEmpty() ? size.width : size.width + horizontalGap;
            if (!row.isEmpty() && rowWidth + advance > available) {
                placeRow(target, row, rowBounds, rowWidth, y, insets, available);
                y += rowHeight + verticalGap;
                row.clear();
                rowBounds.clear();
                rowWidth = 0;
                rowHeight = 0;
            }
            if (!row.isEmpty()) {
                rowWidth += horizontalGap;
            }
            row.add(child);
            rowBounds.add(new java.awt.Rectangle(rowWidth, 0, size.width, size.height));
            rowWidth += size.width;
            rowHeight = Math.max(rowHeight, size.height);
        }
        if (!row.isEmpty()) {
            placeRow(target, row, rowBounds, rowWidth, y, insets, available);
        }
    }

    private void placeRow(Container target, java.util.List<Component> row,
                          java.util.List<java.awt.Rectangle> bounds, int rowWidth, int y,
                          Insets insets, int available) {
        int x = align == Align.RIGHT
                ? insets.left + Math.max(0, available - rowWidth)
                : insets.left;
        for (int i = 0; i < row.size(); i++) {
            java.awt.Rectangle rectangle = bounds.get(i);
            row.get(i).setBounds(x + rectangle.x, y, rectangle.width, rectangle.height);
        }
    }
}
