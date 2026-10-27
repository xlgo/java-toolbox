package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 启动器：按分类分区展示全部工具磁贴，并承担搜索与收藏两种视图。
 *
 * <p>这是效果图的主内容区。它不销毁任何 {@link ToolPanel}——点击磁贴只是把工具 ID
 * 交给主窗口，由 {@link ToolContentHost} 惰性挂载。因此来回切分类不会丢失工具页里的草稿。</p>
 *
 * <p>视图状态（分类、搜索词、仅看收藏）都保存在本类字段里，重建时只重排组件，
 * 不改变用户的选择。</p>
 */
public final class LauncherPanel extends JPanel {

    /** 收藏的持久化键：逗号分隔的工具 ID */
    private static final String FAVORITES_KEY = "nav.favorites";

    private final ToolNavigationModel model;
    private final Consumer<String> openListener;
    private final JPanel content = new BackdropPanel();
    private final JScrollPane scrollPane;
    private final Set<String> favorites = new LinkedHashSet<>();

    /** null 表示「全部工具」，否则为分类 ID */
    private String categoryFilter;
    private String query = "";
    private boolean favoriteOnly;

    public LauncherPanel(ToolNavigationModel model, Consumer<String> openListener) {
        super(new BorderLayout());
        setOpaque(false);
        this.model = model;
        this.openListener = openListener;
        loadFavorites();

        content.setLayout(new javax.swing.BoxLayout(content, javax.swing.BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(
                Tokens.SPACE_XL, Tokens.SPACE_XL, Tokens.SPACE_XL, Tokens.SPACE_XL));

        scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        add(scrollPane, BorderLayout.CENTER);

        rebuild();
    }

    // ---------------------------------------------------------------- 视图控制

    /** 只看某个分类；传 null 显示全部分区 */
    public void setCategoryFilter(String categoryId) {
        this.categoryFilter = categoryId;
        this.favoriteOnly = false;
        rebuild();
        scrollToTop();
    }

    public String getCategoryFilter() {
        return categoryFilter;
    }

    /** 搜索词；非空时搜索结果覆盖分类与收藏视图 */
    public void setQuery(String value) {
        this.query = value == null ? "" : value.trim();
        rebuild();
        scrollToTop();
    }

    public String getQuery() {
        return query;
    }

    /** 仅显示收藏 */
    public void setFavoriteOnly(boolean value) {
        if (favoriteOnly == value) {
            return;
        }
        favoriteOnly = value;
        if (value) {
            categoryFilter = null;
        }
        rebuild();
        scrollToTop();
    }

    public boolean isFavoriteOnly() {
        return favoriteOnly;
    }

    public boolean isFavorite(String toolId) {
        return favorites.contains(toolId);
    }

    /**
     * 把键盘焦点交给第一张磁贴。
     *
     * <p>顶栏搜索框按「下」时调用：搜索原本支持键盘直达结果，搜索框搬到顶栏后
     * 这条路径要接得上，否则纯键盘用户只能靠 Tab 逐个挪。</p>
     */
    public boolean focusFirstTile() {
        LauncherCard first = firstTile(content);
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

    /** 语言或主题切换后刷新文案；卡片本身按皮肤自绘，无需重建颜色 */
    public void refresh() {
        rebuild();
    }

    private void scrollToTop() {
        SwingUtilities.invokeLater(() -> scrollPane.getVerticalScrollBar().setValue(0));
    }

    // ---------------------------------------------------------------- 构建

    private void rebuild() {
        content.removeAll();

        List<Section> sections = sections();
        if (sections.isEmpty()) {
            content.add(emptyState());
        } else {
            boolean first = true;
            for (Section section : sections) {
                if (!first) {
                    content.add(javax.swing.Box.createVerticalStrut(Tokens.SPACE_LG));
                }
                first = false;
                if (section.title != null) {
                    content.add(sectionHeader(section.title, section.tools.size()));
                    content.add(javax.swing.Box.createVerticalStrut(Tokens.SPACE_MD));
                }
                content.add(tileGrid(section.tools));
            }
        }
        content.revalidate();
        content.repaint();
    }

    /** 一次构建要展示的分区：搜索 > 收藏 > 分类 > 全部 */
    private List<Section> sections() {
        if (!query.isEmpty()) {
            List<ToolPanel> matches = new ArrayList<>();
            String needle = query.toLowerCase(Locale.ROOT);
            for (ToolNavigationModel.Group group : model.getGroups()) {
                for (ToolPanel tool : group.getTools()) {
                    if (tool.matchesSearch(needle)) {
                        matches.add(tool);
                    }
                }
            }
            // 没有命中时返回空分区而不是「0 个结果」的空标题，
            // 让 rebuild() 走空态提示，用户才知道是没搜到而不是界面没反应
            return matches.isEmpty()
                    ? List.of()
                    : List.of(new Section(I18n.get("launcher.searchResults"), matches));
        }

        if (favoriteOnly) {
            List<ToolPanel> marked = new ArrayList<>();
            for (ToolNavigationModel.Group group : model.getGroups()) {
                for (ToolPanel tool : group.getTools()) {
                    if (favorites.contains(tool.getName())) {
                        marked.add(tool);
                    }
                }
            }
            return marked.isEmpty()
                    ? List.of()
                    : List.of(new Section(I18n.get("launcher.favorites"), marked));
        }

        if (categoryFilter != null) {
            for (ToolNavigationModel.Group group : model.getGroups()) {
                if (group.getId().equals(categoryFilter)) {
                    // 单分类视图不写标题：侧栏已经高亮了当前分类，再写一遍是噪音
                    return List.of(new Section(null, group.getTools()));
                }
            }
            return List.of();
        }

        List<Section> all = new ArrayList<>();
        for (ToolNavigationModel.Group group : model.getGroups()) {
            all.add(new Section(group.getLabel(), group.getTools()));
        }
        return all;
    }

    private JPanel tileGrid(List<ToolPanel> tools) {
        JPanel grid = new JPanel(new TileFlowLayout());
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        for (ToolPanel tool : tools) {
            grid.add(createCard(tool));
        }
        return grid;
    }

    private LauncherCard createCard(ToolPanel tool) {
        String id = tool.getName();
        String category = tool.getGroup();
        String title = tool.getLabel();
        String subtitle = localizedAlternate("tool." + id);

        LauncherCard card = new LauncherCard(id, category, title, subtitle);
        card.setFavorite(favorites.contains(id), false);
        card.addActionListener(event -> openListener.accept(id));
        card.addPropertyChangeListener("favorite", event -> {
            if (Boolean.TRUE.equals(event.getNewValue())) {
                favorites.add(id);
            } else {
                favorites.remove(id);
            }
            persistFavorites();
            // 收藏视图下取消收藏，磁贴应立即消失
            if (favoriteOnly) {
                rebuild();
            }
        });
        return card;
    }

    /**
     * 磁贴副标题：界面中文时给英文名，界面英文时给中文名。
     * 两者都不重复，卡片信息量最大。
     */
    private String localizedAlternate(String key) {
        boolean chineseUi = !"en_US".equals(ConfigManager.get("locale", "zh_CN"));
        return chineseUi ? I18n.getEnglish(key) : I18n.getChinese(key);
    }

    private JPanel sectionHeader(String title, int count) {
        return new SectionHeader(title, count);
    }

    private JPanel emptyState() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_XL * 2, 0, 0, 0));
        JLabel label = new JLabel(I18n.get("launcher.empty"));
        label.setFont(Tokens.fontBody());
        label.setForeground(Tokens.mutedForeground());
        panel.add(label, BorderLayout.NORTH);
        return panel;
    }

    // ---------------------------------------------------------------- 收藏持久化

    private void loadFavorites() {
        String stored = ConfigManager.get(FAVORITES_KEY, "");
        if (stored == null || stored.isEmpty()) {
            return;
        }
        for (String id : stored.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                favorites.add(trimmed);
            }
        }
    }

    private void persistFavorites() {
        ConfigManager.set(FAVORITES_KEY, String.join(",", favorites));
        ConfigManager.save();
    }

    // ---------------------------------------------------------------- 内部类型

    private record Section(String title, List<ToolPanel> tools) {
    }

    /** 分区标题：标题在左，数量在右 */
    private static final class SectionHeader extends JPanel {

        SectionHeader(String title, int count) {
            super(new BorderLayout());
            setOpaque(false);
            setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel name = new JLabel(title);
            name.setFont(Tokens.fontSectionTitle().deriveFont(13.5f));
            name.setForeground(Tokens.foreground());
            JLabel badge = new JLabel(Integer.toString(count));
            badge.setFont(Tokens.fontCaption());
            badge.setForeground(Tokens.mutedForeground());
            add(name, BorderLayout.WEST);
            add(badge, BorderLayout.EAST);
        }
    }

    /** 启动器页面底色，绘制时取色以便跟随皮肤 */
    private static final class BackdropPanel extends JPanel {
        BackdropPanel() {
            // 关闭默认不透明，避免浅色 LAF 底色盖住深色皮肤
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Tokens.launcherBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(g);
        }
    }

    /**
     * 磁贴流式布局：按可用宽度换行，行内左对齐。
     *
     * <p>与通用 {@code WrapLayout} 的区别是这里需要把首选高度算准——启动器整体在
     * 滚动视图里，高度算少了会裁掉最后一行磁贴。</p>
     */
    private static final class TileFlowLayout implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container target) {
            return measure(target, availableWidth(target));
        }

        @Override
        public Dimension minimumLayoutSize(Container target) {
            Insets insets = target.getInsets();
            return new Dimension(
                    Tokens.TILE_WIDTH + insets.left + insets.right,
                    Tokens.TILE_HEIGHT + insets.top + insets.bottom);
        }

        private int availableWidth(Container target) {
            int width = target.getWidth();
            Container cursor = target;
            while (width <= 0 && cursor.getParent() != null) {
                cursor = cursor.getParent();
                width = cursor.getWidth();
            }
            // 滚动视图首次布局时祖先可能还没有宽度，按一个磁贴兜底而不是 Integer.MAX_VALUE，
            // 否则会在首帧把 76 张磁贴排成一行
            return width <= 0 ? Tokens.TILE_WIDTH : width;
        }

        private Dimension measure(Container target, int width) {
            Insets insets = target.getInsets();
            int available = Math.max(Tokens.TILE_WIDTH, width - insets.left - insets.right);
            int rowWidth = 0;
            int rowHeight = 0;
            int totalHeight = 0;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) continue;
                Dimension size = child.getPreferredSize();
                int advance = rowWidth == 0 ? size.width : size.width + Tokens.TILE_GAP;
                if (rowWidth > 0 && rowWidth + advance > available) {
                    totalHeight += rowHeight + Tokens.TILE_GAP;
                    rowWidth = size.width;
                    rowHeight = size.height;
                } else {
                    rowWidth += advance;
                    rowHeight = Math.max(rowHeight, size.height);
                }
            }
            totalHeight += rowHeight;
            return new Dimension(
                    available + insets.left + insets.right,
                    totalHeight + insets.top + insets.bottom);
        }

        @Override
        public void layoutContainer(Container target) {
            Insets insets = target.getInsets();
            int available = Math.max(Tokens.TILE_WIDTH,
                    target.getWidth() - insets.left - insets.right);
            int x = insets.left;
            int y = insets.top;
            int rowHeight = 0;
            boolean firstInRow = true;
            for (Component child : target.getComponents()) {
                if (!child.isVisible()) continue;
                Dimension size = child.getPreferredSize();
                if (!firstInRow && (x - insets.left) + size.width > available) {
                    x = insets.left;
                    y += rowHeight + Tokens.TILE_GAP;
                    rowHeight = 0;
                    firstInRow = true;
                }
                child.setBounds(x, y, size.width, size.height);
                x += size.width + Tokens.TILE_GAP;
                rowHeight = Math.max(rowHeight, size.height);
                firstInRow = false;
            }
        }
    }
}
