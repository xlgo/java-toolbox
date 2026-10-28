package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.ConfigManager;
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
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 工具侧栏：按分组展示完整工具清单，并提供收藏与实时筛选。
 *
 * <p>与效果页一致，侧栏不是「分类入口」而是真正的工具目录：每个工具一行，
 * 左侧是语义线框图标，右侧在悬停时露出收藏星标。收藏组固定置顶，但仍保留在
 * 各自分类中，方便用户既按常用也按领域查找。</p>
 *
 * <p>组件不持有任何工具视图，只发出稳定的工具 ID；因此在这里反复筛选、收藏、
 * 切换语言都不会影响已经挂载的工具草稿。</p>
 */
public final class ToolSidebar extends JPanel {

    /** 兼容旧导航接口：当前侧栏没有分类过滤，该值仅作为「全部」语义保留。 */
    public static final String ALL = "all";

    private static final String FAVORITES_KEY = "nav.favorites";

    private final ToolNavigationModel model;
    private final Consumer<String> selectionListener;
    private final Runnable collapseListener;
    private final JPanel navList = new NavList();
    private final JLabel countLabel = new JLabel();
    private final JLabel privacyLabel = new JLabel();
    private final JLabel searchHint = new JLabel();
    private final JLabel switchHint = new JLabel();
    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<NavItem> items = new ArrayList<>();

    private String query = "";
    private String selectedToolId;
    private Consumer<String> favoriteListener = ignored -> { };
    private Runnable searchAction;

    public ToolSidebar(
            ToolNavigationModel model,
            Consumer<String> selectionListener,
            Runnable collapseListener) {
        super(new BorderLayout());
        this.model = model;
        this.selectionListener = selectionListener;
        this.collapseListener = collapseListener;
        setOpaque(false);
        setName("workbench.sidebar");
        setBorder(KitBorders.line(0, 0, 0, 1));
        setPreferredSize(new Dimension(252, 0));
        setMinimumSize(new Dimension(252, 0));

        loadFavorites();
        add(buildNav(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);
        rebuild();
        refreshLabels();
    }

    // ---------------------------------------------------------------- 布局

    private JComponent buildNav() {
        navList.setOpaque(false);
        navList.setLayout(new BoxLayout(navList, BoxLayout.Y_AXIS));
        navList.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JScrollPane scroll = new JScrollPane(navList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        return scroll;
    }

    private JComponent buildFooter() {
        JPanel footer = new JPanel();
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createCompoundBorder(
                KitBorders.line(1, 0, 0, 0),
                new javax.swing.border.EmptyBorder(10, 14, 10, 14)));

        for (JLabel label : new JLabel[]{countLabel, privacyLabel}) {
            label.setFont(Tokens.fontCaption().deriveFont(11.5f));
            label.setForeground(Tokens.mutedForeground());
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            footer.add(label);
        }
        footer.add(Box.createVerticalStrut(7));

        JPanel keys = new JPanel();
        keys.setOpaque(false);
        keys.setLayout(new BoxLayout(keys, BoxLayout.X_AXIS));
        keys.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (JLabel hint : new JLabel[]{searchHint, switchHint}) {
            hint.setFont(Tokens.fontMono().deriveFont(10.5f));
            hint.setForeground(Tokens.mutedForeground());
            hint.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Tokens.borderStrong()),
                    new javax.swing.border.EmptyBorder(0, 4, 0, 4)));
            keys.add(hint);
            keys.add(Box.createHorizontalStrut(6));
        }
        keys.add(Box.createHorizontalGlue());
        footer.add(keys);
        return footer;
    }

    // ---------------------------------------------------------------- 重建

    private void rebuild() {
        navList.removeAll();
        items.clear();

        List<ToolPanel> favoritesMatched = new ArrayList<>();
        if (!query.isEmpty()) {
            for (ToolPanel tool : allTools()) {
                if (favorites.contains(tool.getName()) && matches(tool)) {
                    favoritesMatched.add(tool);
                }
            }
        } else {
            for (ToolPanel tool : allTools()) {
                if (favorites.contains(tool.getName())) {
                    favoritesMatched.add(tool);
                }
            }
        }

        if (!favoritesMatched.isEmpty()) {
            navList.add(groupLabel("★ " + I18n.get("launcher.favorites")));
            for (ToolPanel tool : favoritesMatched) {
                addItem(tool);
            }
        }

        for (ToolNavigationModel.Group group : model.getGroups()) {
            List<ToolPanel> matched = new ArrayList<>();
            for (ToolPanel tool : group.getTools()) {
                if (matches(tool)) {
                    matched.add(tool);
                }
            }
            if (matched.isEmpty()) {
                continue;
            }
            navList.add(groupLabel(group.getLabel()));
            for (ToolPanel tool : matched) {
                addItem(tool);
            }
        }

        if (navList.getComponentCount() == 0) {
            JLabel empty = new JLabel(I18n.get("nav.empty"));
            empty.setFont(Tokens.fontBody());
            empty.setForeground(Tokens.mutedForeground());
            empty.setBorder(new javax.swing.border.EmptyBorder(10, 10, 10, 10));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            navList.add(empty);
        }

        navList.add(Box.createVerticalGlue());
        navList.revalidate();
        navList.repaint();
        scrollToSelected();
    }

    private JLabel groupLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Tokens.fontCaption().deriveFont(Font.BOLD, 11f));
        label.setForeground(Tokens.mutedForeground());
        label.setBorder(new javax.swing.border.EmptyBorder(13, 10, 5, 10));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        return label;
    }

    private void addItem(ToolPanel tool) {
        NavItem item = new NavItem(tool);
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.setSelected(tool.getName().equals(selectedToolId));
        items.add(item);
        navList.add(item);
    }

    private List<ToolPanel> allTools() {
        List<ToolPanel> tools = new ArrayList<>();
        for (ToolNavigationModel.Group group : model.getGroups()) {
            tools.addAll(group.getTools());
        }
        return tools;
    }

    private boolean matches(ToolPanel tool) {
        return query.isEmpty() || tool.matchesSearch(query.toLowerCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- 状态

    public void setQuery(String value) {
        query = value == null ? "" : value.trim();
        rebuild();
    }

    public String getQuery() {
        return query;
    }

    public void setSelectedTool(String toolId) {
        selectedToolId = toolId;
        for (NavItem item : items) {
            item.setSelected(item.tool.getName().equals(toolId));
            item.repaint();
        }
        scrollToSelected();
    }

    public String getSelectedToolId() {
        return selectedToolId;
    }

    public int getToolCount() {
        return model.getToolIds().size();
    }

    /** 窗口首次显示后再次把选中项滚入可视区：构造阶段组件尚未布局，单次调用会落空。 */
    public void revealSelected() {
        scrollToSelected();
    }

    /** 兼容旧调用：侧栏已按工具平铺，没有分类高亮，仅同步到工具所属分组。 */
    public String getSelectedCategory() {
        ToolPanel tool = selectedToolId == null ? null : model.findTool(selectedToolId);
        return tool == null ? ALL : tool.getGroup();
    }

    /** 兼容旧调用：传分类 ID 时定位到该分类的第一个工具。 */
    public void setSelectedCategory(String categoryId) {
        if (categoryId == null || ALL.equals(categoryId)) return;
        for (ToolNavigationModel.Group group : model.getGroups()) {
            if (group.getId().equals(categoryId) && !group.getTools().isEmpty()) {
                setSelectedTool(group.getTools().get(0).getName());
                return;
            }
        }
    }

    public boolean isFavorite(String toolId) {
        return favorites.contains(toolId);
    }

    public void setFavorite(String toolId, boolean value) {
        if (toolId == null) return;
        if (value) favorites.add(toolId);
        else favorites.remove(toolId);
        persistFavorites();
        rebuild();
        favoriteListener.accept(toolId);
    }

    public void toggleFavorite(String toolId) {
        setFavorite(toolId, !favorites.contains(toolId));
    }

    public Set<String> getFavoriteIds() {
        return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(favorites));
    }

    public void setFavoriteListener(Consumer<String> listener) {
        favoriteListener = listener == null ? ignored -> { } : listener;
    }

    public void setSearchAction(Runnable action) {
        this.searchAction = action;
    }

    public void focusSearch() {
        if (searchAction != null) searchAction.run();
    }

    /** 搜索框按下方向键：把焦点交给第一个可见工具项。 */
    public boolean focusFirstVisibleTool() {
        if (items.isEmpty()) return false;
        items.get(0).requestFocusInWindow();
        return true;
    }

    public void setExpandedGroupIds(Collection<String> groupIds) {
        // 平铺目录没有展开状态
    }

    public Set<String> getExpandedGroupIds() {
        return new LinkedHashSet<>();
    }

    public void refresh() {
        refreshLabels();
        rebuild();
    }

    public void refreshLabels() {
        countLabel.setText(java.text.MessageFormat.format(
                I18n.get("sidebar.count"), model.getToolIds().size(), I18n.get("launcher.favorites")));
        countLabel.setToolTipText(countLabel.getText());
        privacyLabel.setText(I18n.get("sidebar.privacy"));
        privacyLabel.setToolTipText(privacyLabel.getText());
        searchHint.setText(I18n.get("sidebar.shortcut.search"));
        switchHint.setText(I18n.get("sidebar.shortcut.switch"));
        for (NavItem item : items) {
            item.refreshLabel();
        }
        revalidate();
        repaint();
    }

    public void restyle() {
        countLabel.setFont(Tokens.fontCaption().deriveFont(11.5f));
        countLabel.setForeground(Tokens.mutedForeground());
        privacyLabel.setFont(Tokens.fontCaption().deriveFont(11.5f));
        privacyLabel.setForeground(Tokens.mutedForeground());
        for (JLabel hint : new JLabel[]{searchHint, switchHint}) {
            hint.setFont(Tokens.fontMono().deriveFont(10.5f));
            hint.setForeground(Tokens.mutedForeground());
            hint.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Tokens.borderStrong()),
                    new javax.swing.border.EmptyBorder(0, 4, 0, 4)));
        }
        // 分组标题的颜色在构造时固化，重建列表才能拿到新主题的次要文字色。
        rebuild();
        repaint();
    }

    private void scrollToSelected() {
        if (selectedToolId == null) return;
        SwingUtilities.invokeLater(() -> {
            for (NavItem item : items) {
                if (item.tool.getName().equals(selectedToolId)) {
                    item.scrollRectToVisible(new java.awt.Rectangle(0, 0, item.getWidth(), item.getHeight()));
                    return;
                }
            }
        });
    }

    // ---------------------------------------------------------------- 收藏持久化

    private void loadFavorites() {
        String stored = ConfigManager.get(FAVORITES_KEY, "");
        if (stored == null || stored.isEmpty()) return;
        for (String id : stored.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) favorites.add(trimmed);
        }
    }

    private void persistFavorites() {
        ConfigManager.set(FAVORITES_KEY, String.join(",", favorites));
        ConfigManager.save();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        graphics.setColor(Tokens.navigationBackground());
        graphics.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(graphics);
    }

    // ---------------------------------------------------------------- 导航项

    private final class NavItem extends JButton {

        private final ToolPanel tool;
        private final JButton star = new StarButton();
        private boolean hover;
        private Font labelFont;

        private NavItem(ToolPanel tool) {
            this.tool = tool;
            setText(null);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder());
            setLayout(null);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setName("nav.tool." + tool.getName());
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            setPreferredSize(new Dimension(230, 34));
            setToolTipText(tool.getLabel());
            getAccessibleContext().setAccessibleName(tool.getLabel());
            getAccessibleContext().setAccessibleDescription(tool.getGroupLabel());

            star.setName("nav.favorite." + tool.getName());
            star.addActionListener(event -> toggleFavorite(tool.getName()));
            add(star);

            addActionListener(event -> selectionListener.accept(tool.getName()));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent event) {
                    hover = true;
                    updateStar();
                    repaint();
                }
                @Override public void mouseExited(MouseEvent event) {
                    if (!contains(event.getPoint())) {
                        hover = false;
                        updateStar();
                        repaint();
                    }
                }
            });
            applyTheme();
            updateStar();
        }

        private void refreshLabel() {
            setToolTipText(tool.getLabel());
            getAccessibleContext().setAccessibleName(tool.getLabel());
            getAccessibleContext().setAccessibleDescription(tool.getGroupLabel());
            updateStar();
            repaint();
        }

        private void applyTheme() {
            labelFont = Tokens.fontBody();
            updateStar();
        }

        private void updateStar() {
            boolean faved = favorites.contains(tool.getName());
            star.setVisible(hover || faved);
            star.setIcon(WorkbenchIcons.star(faved, 13));
            star.setToolTipText(I18n.get(faved
                    ? "launcher.favorite.remove"
                    : "launcher.favorite.add"));
        }

        @Override
        public void updateUI() {
            super.updateUI();
            labelFont = Tokens.fontBody();
            repaint();
        }

        @Override
        public void doLayout() {
            int size = 22;
            star.setBounds(getWidth() - size - 6, (getHeight() - size) / 2, size, size);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int height = getHeight() - 2;
                int arc = 8;
                boolean selected = tool.getName().equals(selectedToolId);

                if (selected) {
                    g.setColor(Tokens.activeSurface());
                    g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), height, arc, arc));
                    g.setColor(Tokens.accent());
                    g.fill(new RoundRectangle2D.Float(0, 7, 3, Math.max(6, height - 14), 3, 3));
                } else if (hover || isFocusOwner()) {
                    g.setColor(Tokens.hoverSurface());
                    g.fill(new RoundRectangle2D.Float(0, 0, getWidth(), height, arc, arc));
                }
                if (isFocusOwner()) {
                    g.setColor(Tokens.accent());
                    g.setStroke(new BasicStroke(1f));
                    g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1f, height - 1f, arc, arc));
                }

                Color ink = selected ? Tokens.foreground() : Tokens.secondaryForeground();
                ToolGlyph.outline(tool.getName(), tool.getGroup(), 17,
                        selected ? Tokens.accent() : Tokens.mutedForeground())
                        .paintIcon(this, g, 10, (height - 17) / 2);

                g.setFont(labelFont == null ? Tokens.fontBody() : labelFont);
                g.setColor(ink);
                FontMetrics metrics = g.getFontMetrics();
                int textWidth = getWidth() - 38 - 28;
                String shown = ellipsize(tool.getLabel(), metrics, Math.max(20, textWidth));
                int baseline = (height + metrics.getAscent() - metrics.getDescent()) / 2;
                g.drawString(shown, 35, baseline);
            } finally {
                g.dispose();
            }
        }
    }

    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || metrics.stringWidth(text) <= maxWidth) return text == null ? "" : text;
        String suffix = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return text.substring(0, Math.max(0, end)) + suffix;
    }

    private final class StarButton extends JButton {
        private StarButton() {
            setText(null);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder());
            setMargin(new Insets(0, 0, 0, 0));
            setHorizontalAlignment(SwingConstants.CENTER);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
    }

    private static final class NavList extends JPanel {
        NavList() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            graphics.setColor(Tokens.navigationBackground());
            graphics.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(graphics);
        }
    }
}
