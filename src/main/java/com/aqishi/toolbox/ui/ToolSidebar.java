package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.formdev.flatlaf.ui.FlatTreeUI;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.MouseInputAdapter;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.plaf.basic.BasicTreeUI;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeCellRenderer;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 基于 {@link JTree} 的工具分类侧栏。
 *
 * <p>组件及其过滤计时器必须在 EDT 上创建和操作。搜索仅重建导航树，不销毁
 * 已打开的工具视图；选择回调始终以稳定工具 ID 通知宿主，展开状态则由调用方
 * 持久化。</p>
 */
public final class ToolSidebar extends JPanel {

    private enum Kind { ROOT, GROUP, TOOL, EMPTY }

    private static final class NavNode extends DefaultMutableTreeNode {
        private final Kind kind;
        private final String id;
        private final String label;
        private final int toolCount;

        private NavNode(Kind kind, String id, String label) {
            this(kind, id, label, 0);
        }

        private NavNode(Kind kind, String id, String label, int toolCount) {
            super(label);
            this.kind = kind;
            this.id = id;
            this.label = label;
            this.toolCount = toolCount;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final ToolNavigationModel model;
    private final Consumer<String> selectionListener;
    private final JLabel brandIconLabel = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JLabel searchCaption = new JLabel();
    private final JLabel searchShortcut = new JLabel("Ctrl K");
    private final JButton collapseButton = new JButton();
    private final JTextField searchField = new JTextField();
    private final JTree tree = new NavigationTree();
    private JScrollPane treeScrollPane;
    private final Timer filterTimer;
    private final LinkedHashSet<String> expandedGroupIds = new LinkedHashSet<>();
    private boolean inputComposing;
    private boolean rebuilding;
    private boolean settingSelection;
    private String selectedToolId;
    private int hoverRow = -1;

    public ToolSidebar(
            ToolNavigationModel model,
            Consumer<String> selectionListener,
            Runnable collapseListener) {
        super(new BorderLayout(0, UIUtils.SPACE_SM));
        this.model = model;
        this.selectionListener = selectionListener;
        expandedGroupIds.addAll(model.getGroupIds());

        setOpaque(false);
        setBorder(javax.swing.BorderFactory.createCompoundBorder(
                com.aqishi.toolbox.ui.kit.KitBorders.line(0, 0, 0, 1),
                new javax.swing.border.EmptyBorder(
                        UIUtils.SPACE_MD, UIUtils.SPACE_MD,
                        UIUtils.SPACE_MD, UIUtils.SPACE_MD)));

        JPanel header = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
        header.setOpaque(false);
        brandIconLabel.setIcon(WorkbenchIcons.brand(24));
        brandIconLabel.setPreferredSize(new Dimension(24, 24));
        brandIconLabel.setVerticalAlignment(SwingConstants.CENTER);
        JPanel brandCopy = new JPanel();
        brandCopy.setOpaque(false);
        brandCopy.setLayout(new BoxLayout(brandCopy, BoxLayout.Y_AXIS));
        titleLabel.setFont(Tokens.fontTitle());
        titleLabel.setForeground(Tokens.foreground());
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        titleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        subtitleLabel.setFont(Tokens.fontCaption());
        subtitleLabel.setForeground(Tokens.mutedForeground());
        subtitleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        subtitleLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        brandCopy.add(titleLabel);
        brandCopy.add(subtitleLabel);
        collapseButton.setPreferredSize(
                new Dimension(Tokens.CONTROL_HEIGHT_SM, Tokens.CONTROL_HEIGHT_SM));
        collapseButton.setMargin(new Insets(2, 4, 2, 4));
        collapseButton.setMinimumSize(new Dimension(Tokens.CONTROL_HEIGHT_SM, Tokens.CONTROL_HEIGHT_SM));
        collapseButton.putClientProperty("JComponent.minimumWidth", 0);
        collapseButton.addActionListener(event -> collapseListener.run());
        collapseButton.setFocusPainted(true);
        collapseButton.setIcon(WorkbenchIcons.sidebar(false));
        collapseButton.putClientProperty("JButton.buttonType", "toolBarButton");
        header.add(brandIconLabel, BorderLayout.WEST);
        header.add(brandCopy, BorderLayout.CENTER);
        header.add(collapseButton, BorderLayout.EAST);

        searchCaption.setFont(Tokens.fontCaption());
        searchCaption.setForeground(Tokens.mutedForeground());
        searchShortcut.setFont(Tokens.fontCaption());
        searchShortcut.setForeground(Tokens.mutedForeground());
        searchShortcut.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Tokens.borderSubtle()),
                BorderFactory.createEmptyBorder(1, 5, 1, 5)));
        JPanel searchMeta = new JPanel(new BorderLayout());
        searchMeta.setOpaque(false);
        searchMeta.add(searchCaption, BorderLayout.WEST);
        searchMeta.add(searchShortcut, BorderLayout.EAST);

        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.putClientProperty("JTextField.leadingIcon", WorkbenchIcons.search());
        searchField.setFont(Tokens.fontBody());
        searchField.setPreferredSize(new Dimension(0, Tokens.CONTROL_HEIGHT));
        searchField.addInputMethodListener(new InputMethodListener() {
            @Override
            public void inputMethodTextChanged(InputMethodEvent event) {
                int textLength = event.getText() == null
                        ? 0
                        : event.getText().getEndIndex() - event.getText().getBeginIndex();
                inputComposing = textLength > event.getCommittedCharacterCount();
                if (!inputComposing) scheduleFilter();
            }

            @Override
            public void caretPositionChanged(InputMethodEvent event) {
            }
        });
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { scheduleFilter(); }
            @Override public void removeUpdate(DocumentEvent event) { scheduleFilter(); }
            @Override public void changedUpdate(DocumentEvent event) { scheduleFilter(); }
        });

        JPanel searchBox = new JPanel(new BorderLayout(0, UIUtils.SPACE_XS));
        searchBox.setOpaque(false);
        searchBox.add(searchMeta, BorderLayout.NORTH);
        searchBox.add(searchField, BorderLayout.SOUTH);

        JPanel top = new JPanel(new BorderLayout(0, UIUtils.SPACE_MD));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(searchBox, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UIUtils.NAV_ROW_HEIGHT);
        tree.setToggleClickCount(1);
        tree.setBorder(javax.swing.BorderFactory.createEmptyBorder());
        tree.putClientProperty("JTree.wideSelection", true);
        tree.putClientProperty("JTree.paintSelection", false);
        tree.setCellRenderer(new NavigationRenderer());
        tree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        ToolTipManager.sharedInstance().registerComponent(tree);

        tree.addTreeSelectionListener(event -> {
            NavNode node = selectedNode();
            if (node != null && node.kind == Kind.TOOL) {
                selectedToolId = node.id;
                if (!settingSelection) {
                    selectionListener.accept(node.id);
                }
            } else if (node != null && node.kind == Kind.EMPTY) {
                tree.clearSelection();
            }
            // A row background extends beyond the path label's normal repaint bounds.
            tree.repaint();
        });
        tree.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent event) { tree.repaint(); }
            @Override public void focusLost(FocusEvent event) { tree.repaint(); }
        });
        tree.addTreeExpansionListener(new TreeExpansionListener() {
            @Override
            public void treeExpanded(TreeExpansionEvent event) {
                rememberExpansion(event.getPath(), true);
            }

            @Override
            public void treeCollapsed(TreeExpansionEvent event) {
                rememberExpansion(event.getPath(), false);
            }
        });
        tree.addMouseMotionListener(new MouseInputAdapter() {
            @Override public void mouseMoved(java.awt.event.MouseEvent event) {
                int row = rowAt(event.getY());
                if (hoverRow != row) { hoverRow = row; tree.repaint(); }
            }
        });
        tree.addMouseListener(new MouseInputAdapter() {
            @Override public void mouseExited(java.awt.event.MouseEvent event) {
                if (hoverRow != -1) { hoverRow = -1; tree.repaint(); }
            }
        });
        treeScrollPane = new JScrollPane(tree);
        treeScrollPane.setBorder(BorderFactory.createEmptyBorder());
        treeScrollPane.getVerticalScrollBar().setUnitIncrement(16);
        treeScrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(treeScrollPane, BorderLayout.CENTER);

        applyNavigationColors();

        filterTimer = new Timer(120, event -> rebuildTree());
        filterTimer.setRepeats(false);
        installKeyboardActions();
        refreshLabels();
    }

    /**
     * 让树、视口与未选中行共用侧栏底色。
     *
     * <p>否则未选中的导航行会带着 LAF 默认的白色矩形背景，在侧栏底色上形成一格格色块。</p>
     */
    private void applyNavigationColors() {
        Color background = Tokens.navigationBackground();
        tree.setBackground(background);
        treeScrollPane.getViewport().setBackground(background);
        treeScrollPane.setBackground(background);
    }

    public void setSelectedTool(String toolId) {
        selectedToolId = toolId;
        TreePath path = findToolPath(toolId);
        if (path != null) {
            settingSelection = true;
            try {
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
            } finally {
                settingSelection = false;
            }
        }
    }

    public String getSelectedToolId() {
        return selectedToolId;
    }

    public void setExpandedGroupIds(Collection<String> groupIds) {
        expandedGroupIds.clear();
        for (String id : model.getGroupIds()) {
            if (groupIds.contains(id)) {
                expandedGroupIds.add(id);
            }
        }
        rebuildTree();
    }

    public Set<String> getExpandedGroupIds() {
        return new LinkedHashSet<>(expandedGroupIds);
    }

    /**
     * 侧栏底色比工作区略有区分，配合右侧细线形成导航区与内容区的分界。
     *
     * <p>颜色在绘制时推导，因此切换主题后不需要额外处理。</p>
     */
    @Override
    protected void paintComponent(java.awt.Graphics g) {
        g.setColor(Tokens.navigationBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(g);
    }

    /** 主题切换后重新取色并保留当前工具、过滤与分组状态。 */
    public void restyle() {
        titleLabel.setFont(Tokens.fontTitle());
        titleLabel.setForeground(Tokens.foreground());
        subtitleLabel.setFont(Tokens.fontCaption());
        subtitleLabel.setForeground(Tokens.mutedForeground());
        searchCaption.setFont(Tokens.fontCaption());
        searchCaption.setForeground(Tokens.mutedForeground());
        searchShortcut.setFont(Tokens.fontCaption());
        searchShortcut.setForeground(Tokens.mutedForeground());
        brandIconLabel.setIcon(WorkbenchIcons.brand(24));
        searchField.setFont(Tokens.fontBody());
        searchField.putClientProperty("JTextField.leadingIcon", WorkbenchIcons.search());
        searchShortcut.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Tokens.borderSubtle()),
                BorderFactory.createEmptyBorder(1, 5, 1, 5)));
        tree.setRowHeight(UIUtils.NAV_ROW_HEIGHT);
        applyNavigationColors();
        rebuildTree();
        repaint();
    }

    public void refreshLabels() {
        titleLabel.setText(I18n.get("top.title"));
        titleLabel.setToolTipText(I18n.get("top.title"));
        subtitleLabel.setText(localized("nav.brand.subtitle", "Developer workspace"));
        subtitleLabel.setToolTipText(subtitleLabel.getText());
        collapseButton.setText(null);
        collapseButton.setIcon(WorkbenchIcons.sidebar(false));
        collapseButton.setToolTipText(I18n.get("nav.collapse"));
        collapseButton.getAccessibleContext().setAccessibleName(I18n.get("nav.collapse"));
        searchCaption.setText(localized("nav.search.label", "Quick search"));
        searchField.putClientProperty(
                "JTextField.placeholderText", I18n.get("top.search.placeholder"));
        searchField.getAccessibleContext().setAccessibleName(
                I18n.get("nav.search.accessible"));
        tree.getAccessibleContext().setAccessibleName(I18n.get("nav.accessible"));
        rebuildTree();
    }

    public void focusSearch() {
        searchField.requestFocusInWindow();
        searchField.selectAll();
    }

    private void scheduleFilter() {
        if (!inputComposing && filterTimer != null) {
            filterTimer.restart();
        }
    }

    private void rebuildTree() {
        rebuilding = true;
        hoverRow = -1;
        try {
            String query = searchField.getText();
            List<ToolNavigationModel.Group> groups = model.filter(query);
            NavNode root = new NavNode(Kind.ROOT, null, "");
            if (groups.isEmpty()) {
                root.add(new NavNode(Kind.EMPTY, null, I18n.get("nav.empty")));
            } else {
                for (ToolNavigationModel.Group group : groups) {
                    NavNode groupNode = new NavNode(
                            Kind.GROUP, group.getId(), group.getLabel(), group.getTools().size());
                    for (ToolPanel tool : group.getTools()) {
                        groupNode.add(new NavNode(
                                Kind.TOOL, tool.getName(), tool.getLabel()));
                    }
                    root.add(groupNode);
                }
            }
            tree.setModel(new DefaultTreeModel(root));

            boolean filtering = query != null && !query.trim().isEmpty();
            for (int row = 0; row < tree.getRowCount(); row++) {
                TreePath path = tree.getPathForRow(row);
                NavNode node = node(path);
                if (node != null && node.kind == Kind.GROUP
                        && (filtering || expandedGroupIds.contains(node.id))) {
                    tree.expandPath(path);
                }
            }
            setSelectedTool(selectedToolId);
        } finally {
            rebuilding = false;
        }
    }

    private void installKeyboardActions() {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK),
                "nav.focusSearch");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                "nav.clearSearch");
        getActionMap().put("nav.focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                focusSearch();
            }
        });
        getActionMap().put("nav.clearSearch", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!searchField.getText().isEmpty()) searchField.setText("");
                else tree.requestFocusInWindow();
            }
        });
        getActionMap().put("nav.openSelection", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                activateSelectedNode();
            }
        });

        searchField.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "nav.firstResult");
        searchField.getActionMap().put("nav.firstResult", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                selectFirstTool();
            }
        });
        tree.getInputMap().put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "nav.openSelection");
        tree.getActionMap().put(
                "nav.openSelection", getActionMap().get("nav.openSelection"));
    }

    private void selectFirstTool() {
        for (int row = 0; row < tree.getRowCount(); row++) {
            TreePath path = tree.getPathForRow(row);
            NavNode node = node(path);
            if (node != null && node.kind == Kind.TOOL) {
                tree.setSelectionPath(path);
                tree.scrollPathToVisible(path);
                tree.requestFocusInWindow();
                return;
            }
        }
    }

    private void activateSelectedNode() {
        TreePath path = tree.getSelectionPath();
        NavNode node = node(path);
        if (node == null) return;
        if (node.kind == Kind.TOOL) {
            selectedToolId = node.id;
            selectionListener.accept(node.id);
        } else if (node.kind == Kind.GROUP) {
            if (tree.isExpanded(path)) tree.collapsePath(path);
            else tree.expandPath(path);
        }
    }

    private void rememberExpansion(TreePath path, boolean expanded) {
        if (rebuilding || !searchField.getText().trim().isEmpty()) return;
        NavNode node = node(path);
        if (node == null || node.kind != Kind.GROUP) return;
        if (expanded) expandedGroupIds.add(node.id);
        else expandedGroupIds.remove(node.id);
    }

    private TreePath findToolPath(String toolId) {
        if (toolId == null) return null;
        Object rootObject = tree.getModel().getRoot();
        if (!(rootObject instanceof NavNode)) return null;
        Enumeration<?> nodes = ((NavNode) rootObject).depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            Object value = nodes.nextElement();
            if (value instanceof NavNode) {
                NavNode node = (NavNode) value;
                if (node.kind == Kind.TOOL && toolId.equals(node.id)) {
                    return new TreePath(node.getPath());
                }
            }
        }
        return null;
    }

    private NavNode selectedNode() {
        return node(tree.getSelectionPath());
    }

    private static NavNode node(TreePath path) {
        if (path == null || !(path.getLastPathComponent() instanceof NavNode)) {
            return null;
        }
        return (NavNode) path.getLastPathComponent();
    }

    private static String localized(String key, String fallback) {
        String value = I18n.get(key);
        return value == null || value.equals(key) ? fallback : value;
    }

    private int rowAt(int y) {
        int row = tree.getClosestRowForLocation(0, y);
        Rectangle bounds = tree.getRowBounds(row);
        return bounds != null && y >= bounds.y && y < bounds.y + bounds.height ? row : -1;
    }

    /** Keep the standard tree keyboard/model behavior; only row painting is specialized. */
    private final class NavigationTree extends JTree {
        @Override public void updateUI() {
            // Avoid relying on FlatLaf-specific defaults in the system-LAF fallback.
            if (UIManager.getLookAndFeel() != null
                    && UIManager.getLookAndFeel().getClass().getName().startsWith("com.formdev.flatlaf")) {
                setUI(new NavigationTreeUI());
            } else {
                setUI(new BasicNavigationTreeUI());
            }
        }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public String getToolTipText(java.awt.event.MouseEvent event) {
            int row = rowAt(event.getY());
            NavNode item = row < 0 ? null : node(getPathForRow(row));
            return item == null ? null : item.label;
        }
    }

    /** FlatLaf still owns hit testing, expansion and navigation; rows share a full-width surface. */
    private final class NavigationTreeUI extends FlatTreeUI {
        @Override protected void installDefaults() {
            super.installDefaults();
            setLeftChildIndent(UIUtils.SPACE_SM);
            setRightChildIndent(UIUtils.SPACE_SM);
        }

        @Override protected void paintRow(
                Graphics graphics, Rectangle clipBounds, Insets insets, Rectangle bounds,
                TreePath path, int row, boolean expanded, boolean hasBeenExpanded, boolean leaf) {
            paintNavigationRow(graphics, bounds, path, row, expanded, leaf, rendererPane);
            // FlatTreeUI paints expansion controls after paintRow, keeping them above the fill.
        }
    }

    private final class BasicNavigationTreeUI extends BasicTreeUI {
        @Override protected void installDefaults() {
            super.installDefaults();
            setLeftChildIndent(UIUtils.SPACE_SM);
            setRightChildIndent(UIUtils.SPACE_SM);
        }

        @Override protected void paintRow(
                Graphics graphics, Rectangle clipBounds, Insets insets, Rectangle bounds,
                TreePath path, int row, boolean expanded, boolean hasBeenExpanded, boolean leaf) {
            paintNavigationRow(graphics, bounds, path, row, expanded, leaf, rendererPane);
            // BasicTreeUI paints controls first, so repaint this control above our background.
            if (shouldPaintExpandControl(path, row, expanded, hasBeenExpanded, leaf)) {
                paintExpandControl(graphics, clipBounds, insets, bounds, path,
                        row, expanded, hasBeenExpanded, leaf);
            }
        }

        @Override protected void paintHorizontalLine(Graphics g, JComponent c, int y, int left, int right) { }
        @Override protected void paintVerticalLine(Graphics g, JComponent c, int x, int top, int bottom) { }
    }

    private void paintNavigationRow(
            Graphics graphics, Rectangle bounds, TreePath path, int row,
            boolean expanded, boolean leaf, CellRendererPane pane) {
        NavNode item = node(path);
        boolean selected = tree.isRowSelected(row);
        boolean focused = tree.hasFocus() && row == tree.getLeadSelectionRow();
        boolean selectable = item != null && item.kind != Kind.EMPTY;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = UIUtils.SPACE_XS;
            int width = Math.max(0, tree.getWidth() - x * 2);
            int height = Math.max(0, bounds.height - UIUtils.SPACE_XS);
            int y = bounds.y + UIUtils.SPACE_XS / 2;
            if (selected || (selectable && row == hoverRow)) {
                g.setColor(selected ? Tokens.accentSoft() : Tokens.hoverBackground());
                g.fillRoundRect(x, y, width, height,
                        Tokens.RADIUS_CONTROL, Tokens.RADIUS_CONTROL);
            }
            if (selected && item != null && item.kind == Kind.TOOL) {
                // The persistent edge marker makes selection discernible without color alone.
                g.setColor(Tokens.accent());
                g.fillRoundRect(x, y + 7, 3, Math.max(4, height - 14), 3, 3);
            }
            if (focused) {
                g.setColor(Tokens.accent());
                g.drawRoundRect(x, y, Math.max(0, width - 1), Math.max(0, height - 1),
                        Tokens.RADIUS_CONTROL, Tokens.RADIUS_CONTROL);
            }
        } finally { g.dispose(); }

        Component component = tree.getCellRenderer().getTreeCellRendererComponent(
                tree, path.getLastPathComponent(), selected, expanded, leaf, row, focused);
        int x = Math.min(bounds.x, tree.getWidth());
        int width = Math.max(0, tree.getWidth() - x - UIUtils.SPACE_SM);
        pane.paintComponent(graphics, component, tree, x, bounds.y, width, bounds.height, true);
    }

    /** Rounded, full-width rows with vector category icons and count badges. */
    private final class NavigationRenderer extends JPanel implements TreeCellRenderer {
        private final JLabel icon = new JLabel();
        private final JLabel text = new JLabel();
        private final JLabel count = new JLabel() {
            @Override protected void paintComponent(Graphics graphics) {
                if (!getText().isEmpty()) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        g.setColor(Tokens.blend(Tokens.mutedForeground(), Tokens.navigationBackground(), 0.92f));
                        g.fillRoundRect(0, 7, getWidth(), Math.max(0, getHeight() - 14),
                                Tokens.RADIUS_CONTROL, Tokens.RADIUS_CONTROL);
                    } finally { g.dispose(); }
                }
                super.paintComponent(graphics);
            }
        };

        NavigationRenderer() {
            setOpaque(false);
            setLayout(new BorderLayout(UIUtils.SPACE_SM, 0));
            setBorder(BorderFactory.createEmptyBorder(0, UIUtils.SPACE_XS, 0, UIUtils.SPACE_XS));
            icon.setPreferredSize(new Dimension(18, 18));
            icon.setHorizontalAlignment(SwingConstants.CENTER);
            text.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, UIUtils.SPACE_XS));
            count.setFont(Tokens.fontCaption());
            count.setHorizontalAlignment(SwingConstants.RIGHT);
            count.setBorder(BorderFactory.createEmptyBorder(0, UIUtils.SPACE_XS, 0, UIUtils.SPACE_XS));
            add(icon, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
            add(count, BorderLayout.EAST);
        }

        @Override
        public Component getTreeCellRendererComponent(
                JTree source, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean focused) {
            NavNode node = value instanceof NavNode ? (NavNode) value : null;
            boolean group = node != null && node.kind == Kind.GROUP;
            boolean empty = node != null && node.kind == Kind.EMPTY;
            Color fg = selected ? Tokens.selectionForeground()
                    : empty ? Tokens.mutedForeground() : Tokens.foreground();
            Color muted = selected ? Tokens.selectionForeground() : Tokens.mutedForeground();
            text.setForeground(fg);
            count.setForeground(muted);
            text.setFont(group ? Tokens.fontBodyStrong() : Tokens.fontBody());
            count.setFont(Tokens.fontCaption());
            icon.setVisible(group);
            icon.setIcon(group ? WorkbenchIcons.category(node.id) : null);
            icon.setForeground(selected ? Tokens.selectionForeground() : Tokens.mutedForeground());
            text.setText(node == null ? "" : node.label);
            count.setText(group && node.toolCount > 0 ? Integer.toString(node.toolCount) : "");
            count.setVisible(group);
            setToolTipText(node == null ? null : node.label);
            setEnabled(!empty);
            return this;
        }
    }
}
