package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * 侧边导航：效果页的三段结构——品牌、视图入口（全部工具 / 我的收藏 / 最近使用）、分类。
 *
 * <p>工具本身不再逐个列在侧栏里：80 个条目会把侧栏变成一条长滚动列表，
 * 而效果页的做法是让首页的卡片承担工具选择，侧栏只负责「我在看哪一组」。
 * 每个入口右侧带数量，用户不必点进去就知道收藏里有几个、某个分类有多少工具。</p>
 *
 * <p>组件与计数在 EDT 上构建；收藏/最近使用变化时由 {@link ToolShortcuts} 通知刷新，
 * 不重建任何工具视图。</p>
 */
public final class ToolSidebar extends JPanel {

    /** 视图入口：全部 / 收藏 / 最近，以及分类 ID 前缀 */
    public static final String ALL = "all";
    public static final String FAVORITES = "favorites";
    public static final String RECENT = "recent";

    private final ToolNavigationModel model;
    private final ToolShortcuts shortcuts;
    private final Consumer<String> categoryListener;
    private final Runnable collapseListener;

    private final JLabel brandTitle = new JLabel();
    private final JLabel brandSubtitle = new JLabel();
    private final JButton collapseButton = new JButton();
    private final JLabel groupCaption = new JLabel();
    private final JLabel footerLabel = new JLabel();
    private final JPanel items = new JPanel();
    private final JPanel brandMark = new BrandMark();
    private String activeCategory = ALL;
    private String currentToolId;
    private String currentToolCategory;

    public ToolSidebar(
            ToolNavigationModel model,
            ToolShortcuts shortcuts,
            Consumer<String> categoryListener,
            Runnable collapseListener) {
        super(new BorderLayout(0, 0));
        this.model = model;
        this.shortcuts = shortcuts == null ? ToolShortcuts.load() : shortcuts;
        this.categoryListener = categoryListener == null ? ignored -> { } : categoryListener;
        this.collapseListener = collapseListener == null ? () -> { } : collapseListener;

        setOpaque(false);
        setName("workbench.sidebar");
        setPreferredSize(new Dimension(Tokens.SIDEBAR_WIDTH, 0));
        setBorder(KitBorders.line(0, 0, 0, 1));

        items.setOpaque(false);
        items.setLayout(new BoxLayout(items, BoxLayout.Y_AXIS));
        items.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SPACE_XS, Tokens.SPACE_SM, Tokens.SPACE_MD, Tokens.SPACE_SM));

        JScrollPane scroll = new JScrollPane(items);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        add(buildHeader(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        this.shortcuts.addListener(ignored -> refresh());
        refresh();
    }

    // ---------------------------------------------------------------- 结构

    private JComponent buildHeader() {
        brandTitle.setFont(Tokens.fontTitle());
        brandTitle.setForeground(Tokens.foreground());
        brandSubtitle.setFont(Tokens.fontCaption());
        brandSubtitle.setForeground(Tokens.mutedForeground());

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        brandTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        brandSubtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        brandTitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        brandSubtitle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        copy.add(brandTitle);
        copy.add(Box.createVerticalStrut(1));
        copy.add(brandSubtitle);

        brandMark.setPreferredSize(new Dimension(34, 34));
        brandMark.setMinimumSize(new Dimension(34, 34));
        brandMark.setMaximumSize(new Dimension(34, 34));

        collapseButton.setName("workbench.sidebarCollapse");
        collapseButton.setText(null);
        collapseButton.setIcon(WorkbenchIcons.sidebar(false));
        collapseButton.setFocusPainted(false);
        collapseButton.setContentAreaFilled(false);
        collapseButton.setBorderPainted(false);
        collapseButton.setMargin(new Insets(2, 4, 2, 4));
        collapseButton.setPreferredSize(new Dimension(26, 26));
        collapseButton.setMaximumSize(new Dimension(26, 26));
        collapseButton.putClientProperty("JComponent.minimumWidth", 0);
        collapseButton.addActionListener(event -> this.collapseListener.run());

        JPanel left = new JPanel(new BorderLayout(Tokens.SPACE_SM + 2, 0));
        left.setOpaque(false);
        left.add(brandMark, BorderLayout.WEST);
        left.add(copy, BorderLayout.CENTER);

        JPanel brand = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        brand.setOpaque(false);
        brand.add(left, BorderLayout.CENTER);
        brand.add(collapseButton, BorderLayout.EAST);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(18, 18, 14, 14));
        header.add(brand, BorderLayout.CENTER);
        return header;
    }

    private JComponent buildFooter() {
        footerLabel.setFont(Tokens.fontCaption());
        footerLabel.setForeground(Tokens.mutedForeground());
        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createCompoundBorder(
                KitBorders.line(1, 0, 0, 0),
                BorderFactory.createEmptyBorder(10, 18, 12, 18)));
        footer.add(footerLabel, BorderLayout.CENTER);
        return footer;
    }

    // ---------------------------------------------------------------- 刷新

    /** 重新构建导航项与计数；收藏/最近使用变化后会调用 */
    public void refresh() {
        items.removeAll();
        items.add(navItem("nav.category.all", ALL, I18n.get("nav.all"),
                WorkbenchIcons.category("all"), countOf(ALL)));
        items.add(Box.createVerticalStrut(2));
        items.add(navItem("nav.category.favorites", FAVORITES, I18n.get("launcher.favorites"),
                WorkbenchIcons.star(false, 16), countOf(FAVORITES)));
        items.add(Box.createVerticalStrut(2));
        items.add(navItem("nav.category.recent", RECENT, I18n.get("nav.recent"),
                WorkbenchIcons.history(16), countOf(RECENT)));

        items.add(Box.createVerticalStrut(Tokens.SPACE_MD));
        items.add(new Separator());
        items.add(Box.createVerticalStrut(Tokens.SPACE_SM));

        groupCaption.setText(I18n.get("nav.categories"));
        groupCaption.setFont(Tokens.fontCaption().deriveFont(Font.BOLD, 11.5f));
        groupCaption.setForeground(Tokens.mutedForeground());
        groupCaption.setBorder(BorderFactory.createEmptyBorder(0, 10, 6, 0));
        groupCaption.setAlignmentX(Component.LEFT_ALIGNMENT);
        items.add(groupCaption);

        for (ToolNavigationModel.Group group : model.getGroups()) {
            items.add(navItem("nav.category." + group.getId(), group.getId(), group.getLabel(),
                    WorkbenchIcons.category(group.getId()), group.getTools().size()));
            items.add(Box.createVerticalStrut(2));
        }
        items.add(Box.createVerticalGlue());

        brandTitle.setText(I18n.get("app.name"));
        brandSubtitle.setText(I18n.get("nav.brand.subtitle"));
        brandTitle.setToolTipText(brandTitle.getText());
        collapseButton.setToolTipText(I18n.get("nav.collapse"));
        footerLabel.setText(java.text.MessageFormat.format(
                I18n.get("sidebar.count.short"), model.getToolIds().size()));

        items.revalidate();
        items.repaint();
        revalidate();
        repaint();
    }

    public void restyle() {
        brandTitle.setFont(Tokens.fontTitle());
        brandTitle.setForeground(Tokens.foreground());
        brandSubtitle.setFont(Tokens.fontCaption());
        brandSubtitle.setForeground(Tokens.mutedForeground());
        groupCaption.setFont(Tokens.fontCaption().deriveFont(Font.BOLD, 11.5f));
        groupCaption.setForeground(Tokens.mutedForeground());
        footerLabel.setFont(Tokens.fontCaption());
        footerLabel.setForeground(Tokens.mutedForeground());
        collapseButton.setIcon(WorkbenchIcons.sidebar(false));
        for (Component component : items.getComponents()) {
            if (component instanceof NavItem item) {
                item.restyle();
            }
        }
        refresh();
    }

    /** 语言切换后重建文案 */
    public void refreshLabels() {
        refresh();
    }

    private int countOf(String category) {
        return switch (category) {
            case ALL -> model.getToolIds().size();
            case FAVORITES -> shortcuts.favoriteCount();
            case RECENT -> shortcuts.recentCount();
            default -> {
                for (ToolNavigationModel.Group group : model.getGroups()) {
                    if (group.getId().equals(category)) {
                        yield group.getTools().size();
                    }
                }
                yield 0;
            }
        };
    }

    // ---------------------------------------------------------------- 状态

    /** 首页当前展示的分组 */
    public void setActiveCategory(String category) {
        activeCategory = category == null || category.isEmpty() ? ALL : category;
        repaintItems();
    }

    public String getActiveCategory() {
        return activeCategory;
    }

    /** 打开工具时同步高亮它所属的分类；返回首页时清空 */
    public void setCurrentTool(String toolId, String categoryId) {
        currentToolId = toolId;
        currentToolCategory = categoryId;
        repaintItems();
    }

    public String getCurrentToolId() {
        return currentToolId;
    }

    /** 收起时的图标方向 */
    public void setCollapsed(boolean value) {
        collapseButton.setIcon(WorkbenchIcons.sidebar(value));
        collapseButton.setToolTipText(I18n.get(value ? "nav.expand" : "nav.collapse"));
    }

    private void repaintItems() {
        for (Component component : items.getComponents()) {
            if (component instanceof NavItem item) {
                item.updateSelection();
            }
        }
    }

    private NavItem navItem(String name, String category, String text,
                            javax.swing.Icon icon, int count) {
        NavItem item = new NavItem(name, category, text, icon, count);
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, Tokens.RAIL_ROW_HEIGHT));
        item.addActionListener(event -> {
            setActiveCategory(category);
            this.categoryListener.accept(category);
        });
        return item;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void paintComponent(Graphics graphics) {
        graphics.setColor(Tokens.navigationBackground());
        graphics.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(graphics);
    }

    /** 品牌标记：强调色圆角方块 + 白色扳手，对应效果页左侧的方形 logo */
    private static final class BrandMark extends JPanel {
        BrandMark() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.setColor(Tokens.accent());
                g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 9, 9));
                g.setColor(Tokens.accentText());
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.translate(getWidth() / 2f - 7f, getHeight() / 2f - 7f);
                g.scale(14f / 16f, 14f / 16f);
                // 扳手：斜柄 + 开口环
                g.draw(new java.awt.geom.Line2D.Float(4.2f, 11.8f, 9.4f, 6.6f));
                g.draw(new java.awt.geom.Arc2D.Float(8.8f, 2.4f, 6.4f, 6.4f, 42, 252,
                        java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Line2D.Float(2.6f, 13.4f, 4.6f, 11.4f));
            } finally {
                g.dispose();
            }
        }
    }

    private static final class Separator extends JComponent {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 1);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 1);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            graphics.setColor(Tokens.border());
            graphics.fillRect(0, 0, getWidth(), 1);
        }
    }

    /** 导航行：圆角底 + 图标 + 文本 + 计数徽标 */
    private final class NavItem extends JButton {

        private final String category;
        private final JLabel text = new JLabel();
        private final JLabel count = new CountBadge();

        NavItem(String name, String category, String label,
                javax.swing.Icon icon, int badge) {
            super();
            this.category = category;
            setName(name);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setLayout(new BorderLayout(Tokens.SPACE_SM + 2, 0));
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 8));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setRolloverEnabled(true);
            putClientProperty("JComponent.minimumWidth", 0);

            JLabel glyph = new JLabel(icon);
            glyph.setPreferredSize(new Dimension(18, 18));
            glyph.setHorizontalAlignment(SwingConstants.CENTER);

            text.setText(label);
            text.setFont(Tokens.fontBody());
            text.setForeground(Tokens.foreground());

            count.setText(badge > 0 ? Integer.toString(badge) : "");
            count.setFont(Tokens.fontCaption());
            count.setHorizontalAlignment(SwingConstants.RIGHT);

            setToolTipText(label);
            getAccessibleContext().setAccessibleName(label);

            add(glyph, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(count, BorderLayout.EAST);

            setPreferredSize(new Dimension(0, Tokens.RAIL_ROW_HEIGHT));
            setMinimumSize(new Dimension(0, Tokens.RAIL_ROW_HEIGHT));
            updateSelection();
        }

        void restyle() {
            text.setFont(Tokens.fontBody());
            count.setFont(Tokens.fontCaption());
            updateSelection();
        }

        void updateSelection() {
            boolean active = category.equals(activeCategory);
            putClientProperty("active", active);
            text.setFont(active ? Tokens.fontBodyStrong() : Tokens.fontBody());
            text.setForeground(active ? Tokens.selectionForeground() : Tokens.foreground());
            count.setForeground(active ? Tokens.selectionForeground() : Tokens.mutedForeground());
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                boolean active = Boolean.TRUE.equals(getClientProperty("active"));
                if (active) {
                    g.setColor(Tokens.activeSurface());
                    g.fill(new RoundRectangle2D.Float(
                            0, 0, getWidth(), getHeight(), Tokens.RADIUS_CONTROL, Tokens.RADIUS_CONTROL));
                } else if (getModel().isRollover()) {
                    g.setColor(Tokens.hoverSurface());
                    g.fill(new RoundRectangle2D.Float(
                            0, 0, getWidth(), getHeight(), Tokens.RADIUS_CONTROL, Tokens.RADIUS_CONTROL));
                }
            } finally {
                g.dispose();
            }
            super.paintComponent(graphics);
        }
    }

    /** 计数徽标：浅底圆角，选中时改用卡片底色以保持对比 */
    private final class CountBadge extends JLabel {
        @Override
        protected void paintComponent(Graphics graphics) {
            if (!getText().isEmpty()) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color parent = getParent() != null && getParent().getParent() instanceof NavItem item
                            && Boolean.TRUE.equals(item.getClientProperty("active"))
                            ? Tokens.cardBackground()
                            : Tokens.panelAlt();
                    g.setColor(parent);
                    int height = Math.min(18, Math.max(14, getHeight() - 6));
                    g.fill(new RoundRectangle2D.Float(0, (getHeight() - height) / 2f,
                            getWidth(), height, height, height));
                } finally {
                    g.dispose();
                }
            }
            super.paintComponent(graphics);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            if (getText().isEmpty()) {
                return new Dimension(0, size.height);
            }
            FontMetrics metrics = getFontMetrics(getFont());
            return new Dimension(metrics.stringWidth(getText()) + 14, Math.max(18, size.height));
        }
    }

}
