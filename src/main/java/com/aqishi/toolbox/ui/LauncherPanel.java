package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 首页：效果页的 {@code #viewHome}——页面标题、一句话说明与工具卡片网格。
 *
 * <p>它承担「全部工具 / 我的收藏 / 最近使用 / 单个分类 / 搜索结果」五种视图，
 * 只有标题、说明与卡片集合不同。点击卡片只是把工具 ID 交给主窗口，
 * 由 {@link ToolContentHost} 惰性挂载，因此来回切换不会丢失工具页里的草稿。</p>
 *
 * <p>网格按可用宽度自动决定列数并把卡片等分拉伸（效果页的
 * {@code repeat(auto-fill, minmax(248px, 1fr))}），窗口变窄时列数减少而不是出现横向滚动。</p>
 */
public final class LauncherPanel extends JPanel {

    private final ToolNavigationModel model;
    private final ToolShortcuts shortcuts;
    private final Consumer<String> openListener;

    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JPanel gridHost = new JPanel(new BorderLayout());
    private final JScrollPane scrollPane;
    private final ViewportBody viewportBody = new ViewportBody();

    private String category = ToolSidebar.ALL;
    private String query = "";

    public LauncherPanel(ToolNavigationModel model, ToolShortcuts shortcuts,
                         Consumer<String> openListener) {
        super(new BorderLayout());
        setOpaque(false);
        this.model = model;
        this.shortcuts = shortcuts == null ? ToolShortcuts.load() : shortcuts;
        this.openListener = openListener == null ? ignored -> { } : openListener;

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(
                Tokens.PAGE_PADDING_TOP, Tokens.PAGE_PADDING_X,
                Tokens.PAGE_PADDING_BOTTOM, Tokens.PAGE_PADDING_X));

        titleLabel.setName("home.title");
        titleLabel.setFont(Tokens.fontHomeTitle());
        titleLabel.setForeground(Tokens.foreground());
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitleLabel.setName("home.subtitle");
        subtitleLabel.setFont(Tokens.fontBody());
        subtitleLabel.setForeground(Tokens.secondaryForeground());
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new javax.swing.BoxLayout(head, javax.swing.BoxLayout.Y_AXIS));
        head.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(titleLabel);
        head.add(javax.swing.Box.createVerticalStrut(Tokens.SPACE_XS));
        head.add(subtitleLabel);

        gridHost.setOpaque(false);
        gridHost.setAlignmentX(Component.LEFT_ALIGNMENT);

        content.add(head);
        content.add(javax.swing.Box.createVerticalStrut(Tokens.SPACE_XL - 4));
        content.add(gridHost);

        viewportBody.setLayout(new BorderLayout());
        viewportBody.setOpaque(false);
        viewportBody.add(content, BorderLayout.CENTER);

        scrollPane = new JScrollPane(viewportBody);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        add(scrollPane, BorderLayout.CENTER);

        setName("workbench.home");
        this.shortcuts.addListener(ignored -> refresh());
        refresh();
    }

    // ---------------------------------------------------------------- 状态

    /** 切换视图：{@code all} / {@code favorites} / {@code recent} / 分类 ID */
    public void setCategory(String categoryId) {
        this.category = categoryId == null || categoryId.isEmpty()
                ? ToolSidebar.ALL : categoryId;
        refresh();
        scrollToTop();
    }

    public String getCategory() {
        return category;
    }

    /** 搜索词；非空时结果覆盖当前视图 */
    public void setQuery(String value) {
        this.query = value == null ? "" : value.trim();
        refresh();
        scrollToTop();
    }

    public String getQuery() {
        return query;
    }

    /** 收藏 / 最近使用变化或语言切换后重建卡片 */
    public void refresh() {
        List<ToolPanel> tools = visibleTools();
        titleLabel.setText(viewTitle(tools.size()));
        subtitleLabel.setText(viewSubtitle(tools.size()));
        subtitleLabel.setToolTipText(subtitleLabel.getText());

        gridHost.removeAll();
        if (tools.isEmpty()) {
            gridHost.add(emptyState(), BorderLayout.CENTER);
        } else {
            CardGrid grid = new CardGrid();
            for (ToolPanel tool : tools) {
                grid.add(createCard(tool));
            }
            gridHost.add(grid, BorderLayout.NORTH);
        }
        gridHost.revalidate();
        gridHost.repaint();
        titleLabel.revalidate();
        revalidate();
        repaint();
    }

    public void restyle() {
        titleLabel.setName("home.title");
        titleLabel.setFont(Tokens.fontHomeTitle());
        titleLabel.setForeground(Tokens.foreground());
        subtitleLabel.setName("home.subtitle");
        subtitleLabel.setFont(Tokens.fontBody());
        subtitleLabel.setForeground(Tokens.secondaryForeground());
        refresh();
    }

    /** 键盘从搜索框按「下」时，把焦点交给第一张卡片 */
    public boolean focusFirstTile() {
        LauncherCard first = firstTile(gridHost);
        if (first == null) {
            return false;
        }
        first.requestFocusInWindow();
        return true;
    }

    private static LauncherCard firstTile(Container root) {
        for (Component child : root.getComponents()) {
            if (!child.isVisible()) continue;
            if (child instanceof LauncherCard card) {
                return card;
            }
            if (child instanceof Container container) {
                LauncherCard nested = firstTile(container);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private void scrollToTop() {
        SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));
    }

    // ---------------------------------------------------------------- 数据

    private List<ToolPanel> visibleTools() {
        List<ToolPanel> list = new ArrayList<>();
        if (!query.isEmpty()) {
            String needle = query.toLowerCase(Locale.ROOT);
            for (ToolNavigationModel.Group group : model.getGroups()) {
                for (ToolPanel tool : group.getTools()) {
                    if (tool.matchesSearch(needle)) {
                        list.add(tool);
                    }
                }
            }
            return list;
        }
        if (ToolSidebar.FAVORITES.equals(category)) {
            for (ToolNavigationModel.Group group : model.getGroups()) {
                for (ToolPanel tool : group.getTools()) {
                    if (shortcuts.isFavorite(tool.getName())) {
                        list.add(tool);
                    }
                }
            }
            return list;
        }
        if (ToolSidebar.RECENT.equals(category)) {
            for (String id : shortcuts.getRecent()) {
                ToolPanel tool = model.findTool(id);
                if (tool != null) {
                    list.add(tool);
                }
            }
            return list;
        }
        for (ToolNavigationModel.Group group : model.getGroups()) {
            if (ToolSidebar.ALL.equals(category) || group.getId().equals(category)) {
                list.addAll(group.getTools());
            }
        }
        return list;
    }

    private String viewTitle(int count) {
        if (!query.isEmpty()) {
            return java.text.MessageFormat.format(I18n.get("home.search"), query);
        }
        if (ToolSidebar.FAVORITES.equals(category)) {
            return I18n.get("launcher.favorites");
        }
        if (ToolSidebar.RECENT.equals(category)) {
            return I18n.get("nav.recent");
        }
        if (ToolSidebar.ALL.equals(category)) {
            return I18n.get("nav.all");
        }
        String groupLabel = category;
        for (ToolNavigationModel.Group group : model.getGroups()) {
            if (group.getId().equals(category)) {
                groupLabel = group.getLabel();
                break;
            }
        }
        return groupLabel;
    }

    private String viewSubtitle(int count) {
        if (!query.isEmpty()) {
            return java.text.MessageFormat.format(I18n.get("home.search.count"), count);
        }
        if (ToolSidebar.FAVORITES.equals(category)) {
            return I18n.get("home.favorites.hint");
        }
        if (ToolSidebar.RECENT.equals(category)) {
            return I18n.get("home.recent.hint");
        }
        return java.text.MessageFormat.format(I18n.get("home.subtitle"), count);
    }

    // ---------------------------------------------------------------- 卡片

    private LauncherCard createCard(ToolPanel tool) {
        String id = tool.getName();
        String subtitle = summary(tool);
        LauncherCard card = new LauncherCard(id, tool.getGroup(), tool.getLabel(), subtitle,
                tool.getGroupLabel());
        card.setFavorite(shortcuts.isFavorite(id), false);
        card.addActionListener(event -> openListener.accept(id));
        card.addPropertyChangeListener("favorite", event -> {
            shortcuts.setFavorite(id, Boolean.TRUE.equals(event.getNewValue()));
        });
        return card;
    }

    /** 工具说明：优先取专用文案，缺省时用通用一句话 */
    private static String summary(ToolPanel tool) {
        String key = "tool." + tool.getName() + ".summary";
        String value = I18n.get(key);
        return key.equals(value) ? I18n.get("tool.summary.generic") : value;
    }

    private JComponent emptyState() {
        JPanel panel = new EmptyState();
        String message;
        if (!query.isEmpty()) {
            message = I18n.get("home.empty.search");
        } else if (ToolSidebar.FAVORITES.equals(category)) {
            message = I18n.get("home.empty.favorites");
        } else if (ToolSidebar.RECENT.equals(category)) {
            message = I18n.get("home.empty.recent");
        } else {
            message = I18n.get("home.empty.category");
        }
        JLabel strong = new JLabel(I18n.get("home.empty.title"));
        strong.setFont(Tokens.fontTitle().deriveFont(15f));
        strong.setForeground(Tokens.foreground());
        strong.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel body = new JLabel(message);
        body.setFont(Tokens.fontBody());
        body.setForeground(Tokens.secondaryForeground());
        body.setAlignmentX(Component.CENTER_ALIGNMENT);

        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(44, 20, 44, 20));
        panel.add(strong);
        panel.add(javax.swing.Box.createVerticalStrut(6));
        panel.add(body);
        return panel;
    }

    // ---------------------------------------------------------------- 内部组件

    /** 虚线空状态框，对应效果页的 {@code .empty} */
    private static final class EmptyState extends JPanel {
        EmptyState() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Tokens.borderStrong());
                g.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 5f}, 0f));
                g.draw(new java.awt.geom.RoundRectangle2D.Float(
                        0.75f, 0.75f, getWidth() - 1.5f, getHeight() - 1.5f,
                        Tokens.RADIUS_CARD, Tokens.RADIUS_CARD));
            } finally {
                g.dispose();
            }
        }
    }

    /** 视口内容容器：宽度跟随视口，高度按内容伸展，横向不留空 */
    private static final class ViewportBody extends JPanel implements javax.swing.Scrollable {

        ViewportBody() {
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL ? visible.height : visible.width;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }

        @Override
        public void doLayout() {
            super.doLayout();
            // 内容列最大 1180px：超宽屏下保持阅读行长，与效果页的 .main-inner 一致
            Component child = getComponentCount() > 0 ? getComponent(0) : null;
            if (child == null) return;
            Insets insets = getInsets();
            int available = Math.max(1, getWidth() - insets.left - insets.right);
            int max = Tokens.PAGE_MAX_WIDTH + Tokens.PAGE_PADDING_X * 2;
            if (available <= max) return;
            int width = max;
            int x = insets.left + (available - width) / 2;
            child.setBounds(x, insets.top, width, child.getHeight());
        }
    }

    /**
     * 卡片网格：按可用宽度决定列数，卡片等分拉伸。
     *
     * <p>效果页用的是 CSS Grid 的 {@code minmax(248px, 1fr)}：列数随宽度变化，
     * 每列宽度均分剩余空间。这里用同样的规则计算，最后一行不足列数时左对齐，
     * 卡片宽度与上一行保持一致，不会出现半宽卡片。</p>
     */
    private static final class CardGrid extends JPanel implements LayoutManager {

        private int columns = 1;
        private int cardWidth = Tokens.TILE_WIDTH;

        CardGrid() {
            setOpaque(false);
            setLayout(this);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    revalidate();
                }
            });
        }

        private int computeColumns(int available) {
            int min = Tokens.TILE_WIDTH + Tokens.TILE_GAP;
            return Math.max(1, (available + Tokens.TILE_GAP) / min);
        }

        @Override
        public void addLayoutComponent(String name, Component comp) { }

        @Override
        public void removeLayoutComponent(Component comp) { }

        private int availableWidth(Container target) {
            int width = target.getWidth();
            Container cursor = target;
            while (width <= 0 && cursor.getParent() != null) {
                cursor = cursor.getParent();
                width = cursor.getWidth();
            }
            // 首帧祖先还没有宽度时按 4 列兜底，避免把 80 张卡片排成一行
            return width <= 0 ? Tokens.TILE_WIDTH * 4 + Tokens.TILE_GAP * 3 : width;
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            Insets insets = target.getInsets();
            int available = Math.max(Tokens.TILE_WIDTH,
                    availableWidth(target) - insets.left - insets.right);
            int cols = computeColumns(available);
            int rows = Math.max(1, (target.getComponentCount() + cols - 1) / cols);
            int height = rows * Tokens.TILE_HEIGHT + (rows - 1) * Tokens.TILE_GAP;
            return new Dimension(
                    available + insets.left + insets.right,
                    height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            return new Dimension(Tokens.TILE_WIDTH, Tokens.TILE_HEIGHT);
        }

        @Override
        public void layoutContainer(Container target) {
            Insets insets = target.getInsets();
            int available = Math.max(Tokens.TILE_WIDTH,
                    target.getWidth() - insets.left - insets.right);
            columns = computeColumns(available);
            cardWidth = Math.max(Tokens.TILE_WIDTH,
                    (available - (columns - 1) * Tokens.TILE_GAP) / columns);

            int x = insets.left;
            int y = insets.top;
            int column = 0;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) continue;
                if (column == columns) {
                    column = 0;
                    x = insets.left;
                    y += Tokens.TILE_HEIGHT + Tokens.TILE_GAP;
                }
                child.setBounds(x, y, cardWidth, Tokens.TILE_HEIGHT);
                x += cardWidth + Tokens.TILE_GAP;
                column++;
            }
        }
    }
}
