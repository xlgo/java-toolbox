package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

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
import java.util.Set;
import java.util.function.Consumer;

/**
 * 一级分类导航：全部 / 各工具分类，底部是主题与外观设置。
 *
 * <p>效果图里侧边栏只承担「去哪一组」，具体工具交给主区的卡片网格。因此这里不再
 * 渲染工具树——树把 76 个工具全塞进侧栏，既挤掉了分组感，也让每一个工具都必须
 * 靠滚动才能找到。分类项显示该组的工具数量，选中时用左侧色条 + 柔和底色标记，
 * 不只靠颜色区分。</p>
 *
 * <p>选择回调以稳定分类 ID 通知宿主；{@link #setSelectedTool(String)} 只负责把
 * 高亮同步到工具所属分类，不会反向触发回调。</p>
 */
public final class ToolSidebar extends JPanel {

    /** 「全部工具」伪分类 ID */
    public static final String ALL = "all";

    private final ToolNavigationModel model;
    private final Consumer<String> selectionListener;
    private final Runnable collapseListener;

    private final JLabel brandIconLabel = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JButton collapseButton = new JButton();
    private final JPanel navList = new NavList();
    private final JPanel footer = new JPanel();
    private final List<NavItem> items = new ArrayList<>();

    private String selectedCategory = ALL;
    private String selectedToolId;
    private Runnable searchAction;

    public ToolSidebar(
            ToolNavigationModel model,
            Consumer<String> selectionListener,
            Runnable collapseListener) {
        super(new BorderLayout(0, Tokens.SPACE_MD));
        this.model = model;
        this.selectionListener = selectionListener;
        this.collapseListener = collapseListener;

        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                KitBorders.line(0, 0, 0, 1),
                new javax.swing.border.EmptyBorder(
                        Tokens.SPACE_MD, Tokens.SPACE_MD, Tokens.SPACE_MD, Tokens.SPACE_MD)));

        add(buildHeader(), BorderLayout.NORTH);
        add(buildNav(), BorderLayout.CENTER);
        add(buildFooter(), BorderLayout.SOUTH);

        rebuildItems();
        refreshLabels();
    }

    // ---------------------------------------------------------------- 布局

    private JComponent buildHeader() {
        JPanel header = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        header.setOpaque(false);

        brandIconLabel.setIcon(WorkbenchIcons.brand(26));
        brandIconLabel.setPreferredSize(new Dimension(26, 26));
        brandIconLabel.setVerticalAlignment(SwingConstants.CENTER);

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        titleLabel.setFont(Tokens.fontTitle());
        titleLabel.setForeground(Tokens.foreground());
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitleLabel.setFont(Tokens.fontCaption().deriveFont(10.5f));
        subtitleLabel.setForeground(Tokens.mutedForeground());
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        copy.add(titleLabel);
        copy.add(Box.createVerticalStrut(1));
        copy.add(subtitleLabel);

        collapseButton.setPreferredSize(
                new Dimension(Tokens.CONTROL_HEIGHT_SM, Tokens.CONTROL_HEIGHT_SM));
        collapseButton.setMinimumSize(
                new Dimension(Tokens.CONTROL_HEIGHT_SM, Tokens.CONTROL_HEIGHT_SM));
        collapseButton.setMargin(new Insets(2, 4, 2, 4));
        collapseButton.putClientProperty("JComponent.minimumWidth", 0);
        collapseButton.putClientProperty("JButton.buttonType", "toolBarButton");
        collapseButton.setFocusPainted(true);
        collapseButton.setIcon(WorkbenchIcons.sidebar(false));
        collapseButton.addActionListener(event -> collapseListener.run());

        header.add(brandIconLabel, BorderLayout.WEST);
        header.add(copy, BorderLayout.CENTER);
        header.add(collapseButton, BorderLayout.EAST);
        return header;
    }

    private JComponent buildNav() {
        navList.setOpaque(false);
        navList.setLayout(new BoxLayout(navList, BoxLayout.Y_AXIS));
        navList.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_XS, 0, 0, 0));

        JScrollPane scroll = new JScrollPane(navList);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private JComponent buildFooter() {
        footer.setOpaque(false);
        footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
        footer.setBorder(BorderFactory.createEmptyBorder(Tokens.SPACE_SM, 0, 0, 0));
        return footer;
    }

    /**
     * 由主窗口注入底部设置区（主题设置 / 暗黑模式）。
     *
     * <p>侧栏本身不依赖主题管理器，保持它只负责「导航」这一件事。</p>
     */
    public void setFooter(JComponent component) {
        footer.removeAll();
        if (component != null) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            footer.add(component);
        }
        footer.revalidate();
        footer.repaint();
    }

    // ---------------------------------------------------------------- 导航项

    private void rebuildItems() {
        navList.removeAll();
        items.clear();

        addItem(ALL, I18n.get("nav.all"), null);
        for (ToolNavigationModel.Group group : model.getGroups()) {
            addItem(group.getId(), group.getLabel(), group);
        }
        navList.add(Box.createVerticalGlue());
        navList.revalidate();
        navList.repaint();
    }

    private void addItem(String id, String label, ToolNavigationModel.Group group) {
        NavItem item = new NavItem(id, label, group == null ? 0 : group.getTools().size());
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        item.setSelected(id.equals(selectedCategory));
        items.add(item);
        navList.add(item);
        navList.add(Box.createVerticalStrut(2));
    }

    /** 点击分类：先更新高亮，再通知宿主 */
    private void selectCategory(String id, boolean notify) {
        selectedCategory = id;
        for (NavItem item : items) {
            item.setSelected(item.categoryId.equals(id));
            item.repaint();
        }
        if (notify) {
            // 即使重复点击同一分类也要通知：用户可能正停在某个工具页，想回到列表
            selectionListener.accept(id);
        }
    }

    public String getSelectedCategory() {
        return selectedCategory;
    }

    public void setSelectedCategory(String categoryId) {
        selectCategory(categoryId == null ? ALL : categoryId, false);
    }

    /**
     * 兼容旧调用：把高亮同步到该工具所属分类。
     *
     * <p>主窗口从卡片打开工具时会调用它，让侧栏停在正确的分组上。</p>
     */
    public void setSelectedTool(String toolId) {
        selectedToolId = toolId;
        ToolPanel tool = model.findTool(toolId);
        if (tool != null) {
            selectCategory(tool.getGroup(), false);
        }
    }

    public String getSelectedToolId() {
        return selectedToolId;
    }

    /**
     * 兼容旧接口：扁平导航没有「展开」的概念，保留空实现以免调用方编译失败。
     */
    public void setExpandedGroupIds(Collection<String> groupIds) {
        // 一级导航不需要展开状态
    }

    public Set<String> getExpandedGroupIds() {
        return new LinkedHashSet<>();
    }

    /** 由主窗口注入搜索聚焦行为：搜索框已移到顶栏 */
    public void setSearchAction(Runnable action) {
        this.searchAction = action;
    }

    public void focusSearch() {
        if (searchAction != null) {
            searchAction.run();
        }
    }

    // ---------------------------------------------------------------- 主题与文案

    public void restyle() {
        titleLabel.setFont(Tokens.fontTitle());
        titleLabel.setForeground(Tokens.foreground());
        subtitleLabel.setFont(Tokens.fontCaption().deriveFont(10.5f));
        subtitleLabel.setForeground(Tokens.mutedForeground());
        brandIconLabel.setIcon(WorkbenchIcons.brand(26));
        collapseButton.setIcon(WorkbenchIcons.sidebar(false));
        for (NavItem item : items) {
            item.applyTheme();
        }
        repaint();
    }

    public void refreshLabels() {
        titleLabel.setText(I18n.get("app.name"));
        titleLabel.setToolTipText(titleLabel.getText());
        subtitleLabel.setText(I18n.get("app.name.en"));
        subtitleLabel.setToolTipText(subtitleLabel.getText());
        collapseButton.setText(null);
        collapseButton.setIcon(WorkbenchIcons.sidebar(false));
        collapseButton.setToolTipText(I18n.get("nav.collapse"));
        collapseButton.getAccessibleContext().setAccessibleName(I18n.get("nav.collapse"));
        for (NavItem item : items) {
            if (ALL.equals(item.categoryId)) {
                item.setItemLabel(I18n.get("nav.all"));
            }
        }
        rebuildItems();
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Tokens.navigationBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(g);
    }

    /** 导航列表容器：跟随皮肤重绘背景 */
    private static final class NavList extends JPanel {
        NavList() {
            // 必须关闭默认不透明：否则 super.paintComponent 会用 LAF 的浅色底
            // 盖掉上面绘制的深色导航底色
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Tokens.navigationBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(g);
        }
    }

    /**
     * 单个分类条目。
     *
     * <p>用按钮而不是列表项，可以直接复用 Swing 的焦点、回车触发与无障碍语义；
     * 绘制则完全接管，才能画出效果图里的圆角高亮与左侧色条。</p>
     */
    private final class NavItem extends JButton {

        private final String categoryId;
        private boolean hover;
        private Font labelFont;

        NavItem(String categoryId, String label, int count) {
            this.categoryId = categoryId;
            setText(label);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setBorder(BorderFactory.createEmptyBorder());
            setHorizontalAlignment(SwingConstants.LEFT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Tokens.RAIL_ROW_HEIGHT));
            setPreferredSize(new Dimension(UIUtils.SIDEBAR_DEFAULT_WIDTH - Tokens.SPACE_MD * 2,
                    Tokens.RAIL_ROW_HEIGHT));
            setName("nav.category." + categoryId);
            getAccessibleContext().setAccessibleName(label);
            setToolTipText(count > 0 ? label + " · " + count : label);

            addActionListener(event -> selectCategory(categoryId, true));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }
            });
            applyTheme();
        }

        void setItemLabel(String value) {
            setText(value);
            getAccessibleContext().setAccessibleName(value);
        }

        void applyTheme() {
            labelFont = Tokens.fontBody();
            repaint();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            labelFont = Tokens.fontBody();
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                boolean selected = categoryId.equals(selectedCategory);
                int width = getWidth();
                int height = getHeight() - 2;
                int arc = Tokens.RADIUS_CONTROL + 2;

                if (selected || hover || isFocusOwner()) {
                    g.setColor(selected ? Tokens.accentSoft() : Tokens.hoverBackground());
                    g.fill(new RoundRectangle2D.Float(0, 0, width, height, arc, arc));
                }
                if (selected) {
                    // 左侧色条：不依赖颜色也能看出当前选择
                    g.setColor(Tokens.accent());
                    g.fill(new RoundRectangle2D.Float(0, 9, 3, Math.max(6, height - 18), 3, 3));
                }
                if (isFocusOwner()) {
                    g.setStroke(new BasicStroke(1f));
                    g.setColor(Tokens.accent());
                    g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc));
                }

                // 图标：复用分类字形，选中时换成强调色
                Color foreground = selected ? Tokens.accent() : Tokens.mutedForeground();
                WorkbenchIcons.category(categoryId, foreground).paintIcon(this, g, 10, (height - 16) / 2);

                g.setFont(labelFont == null ? Tokens.fontBody() : labelFont);
                g.setColor(selected ? Tokens.accent() : Tokens.foreground());
                int baseline = (height + g.getFontMetrics().getAscent()
                        - g.getFontMetrics().getDescent()) / 2;
                g.drawString(getText(), 34, baseline);
            } finally {
                g.dispose();
            }
        }
    }
}
