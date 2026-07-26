package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.awt.event.KeyEvent;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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
    private final JLabel titleLabel = new JLabel();
    private final JButton collapseButton = new JButton();
    private final JTextField searchField = new JTextField();
    private final JTree tree = new JTree();
    private JScrollPane treeScrollPane;
    private final Timer filterTimer;
    private final LinkedHashSet<String> expandedGroupIds = new LinkedHashSet<>();
    private boolean inputComposing;
    private boolean rebuilding;
    private boolean settingSelection;
    private String selectedToolId;

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
        titleLabel.setFont(Tokens.fontTitle().deriveFont(16f));
        titleLabel.setForeground(Tokens.foreground());
        collapseButton.setPreferredSize(
                new Dimension(Tokens.CONTROL_HEIGHT, Tokens.CONTROL_HEIGHT));
        collapseButton.addActionListener(event -> collapseListener.run());
        collapseButton.setFocusPainted(false);
        collapseButton.putClientProperty("JButton.buttonType", "toolBarButton");
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(collapseButton, BorderLayout.EAST);

        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.putClientProperty("JTextField.leadingIcon", null);
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

        JPanel top = new JPanel(new BorderLayout(0, UIUtils.SPACE_MD));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(searchField, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UIUtils.NAV_ROW_HEIGHT);
        tree.setToggleClickCount(1);
        tree.setBorder(javax.swing.BorderFactory.createEmptyBorder());
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

    /** 侧栏底色 */
    private static java.awt.Color navigationBackground() {
        return Tokens.shift(Tokens.surface(), Tokens.isDark() ? 0.03f : -0.018f);
    }

    /**
     * 让树、视口与未选中行共用侧栏底色。
     *
     * <p>否则未选中的导航行会带着 LAF 默认的白色矩形背景，在侧栏底色上形成一格格色块。</p>
     */
    private void applyNavigationColors() {
        java.awt.Color background = navigationBackground();
        tree.setBackground(background);
        treeScrollPane.getViewport().setBackground(background);
        treeScrollPane.setBackground(background);
        javax.swing.tree.TreeCellRenderer renderer = tree.getCellRenderer();
        if (renderer instanceof DefaultTreeCellRenderer) {
            ((DefaultTreeCellRenderer) renderer).setBackgroundNonSelectionColor(background);
        }
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
        g.setColor(navigationBackground());
        g.fillRect(0, 0, getWidth(), getHeight());
        super.paintComponent(g);
    }

    /** 主题切换后重新取色并重建导航行（分组计数使用了内联颜色） */
    public void restyle() {
        titleLabel.setFont(Tokens.fontTitle().deriveFont(16f));
        titleLabel.setForeground(Tokens.foreground());
        searchField.setFont(Tokens.fontBody());
        tree.setRowHeight(UIUtils.NAV_ROW_HEIGHT);
        applyNavigationColors();
        rebuildTree();
        repaint();
    }

    public void refreshLabels() {
        titleLabel.setText(I18n.get("top.title"));
        titleLabel.setToolTipText(I18n.get("top.title"));
        collapseButton.setText("‹");
        collapseButton.setToolTipText(I18n.get("nav.collapse"));
        collapseButton.getAccessibleContext().setAccessibleName(I18n.get("nav.collapse"));
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

    /**
     * 导航行渲染：分组用粗体并在右侧附带工具数量，工具用常规字重。
     *
     * <p>数量用内联 HTML 着色，颜色取自 {@link Tokens#mutedForeground()}，
     * 因此不会在深色主题下变成不可读的浅灰。</p>
     */
    private final class NavigationRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(
                JTree source, Object value, boolean selected, boolean expanded,
                boolean leaf, int row, boolean focused) {
            JLabel label = (JLabel) super.getTreeCellRendererComponent(
                    source, value, selected, expanded, leaf, row, focused);
            setLeafIcon(null);
            setOpenIcon(null);
            setClosedIcon(null);
            NavNode node = value instanceof NavNode ? (NavNode) value : null;
            if (node != null && node.kind == Kind.GROUP) {
                label.setFont(Tokens.fontBody());
                label.setText(groupMarkup(node, selected));
            } else {
                label.setFont(Tokens.fontBody());
            }
            label.setEnabled(node == null || node.kind != Kind.EMPTY);
            label.setToolTipText(node == null ? null : node.label);
            return label;
        }

        private String groupMarkup(NavNode node, boolean selected) {
            String name = escape(node.label);
            if (node.toolCount <= 0) {
                return "<html><b>" + name + "</b></html>";
            }
            String countColor = toHex(selected
                    ? Tokens.foreground()
                    : Tokens.mutedForeground());
            return "<html><b>" + name + "</b>&#160;&#160;<font color='" + countColor + "'>"
                    + node.toolCount + "</font></html>";
        }

        private String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        private String toHex(java.awt.Color color) {
            return String.format("#%02X%02X%02X",
                    color.getRed(), color.getGreen(), color.getBlue());
        }
    }
}
