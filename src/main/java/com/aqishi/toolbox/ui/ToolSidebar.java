package com.aqishi.toolbox.ui;

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

        private NavNode(Kind kind, String id, String label) {
            super(label);
            this.kind = kind;
            this.id = id;
            this.label = label;
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

        setBorder(new javax.swing.border.EmptyBorder(
                UIUtils.SPACE_MD, UIUtils.SPACE_MD,
                UIUtils.SPACE_MD, UIUtils.SPACE_MD));

        JPanel header = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
        titleLabel.setFont(UIUtils.titleFont().deriveFont(16f));
        collapseButton.addActionListener(event -> collapseListener.run());
        collapseButton.setFocusPainted(false);
        header.add(titleLabel, BorderLayout.CENTER);
        header.add(collapseButton, BorderLayout.EAST);

        searchField.putClientProperty("JTextField.showClearButton", true);
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

        JPanel top = new JPanel(new BorderLayout(0, UIUtils.SPACE_SM));
        top.add(header, BorderLayout.NORTH);
        top.add(searchField, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(UIUtils.NAV_ROW_HEIGHT);
        tree.setToggleClickCount(1);
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
        JScrollPane scrollPane = new JScrollPane(tree);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        add(scrollPane, BorderLayout.CENTER);

        filterTimer = new Timer(120, event -> rebuildTree());
        filterTimer.setRepeats(false);
        installKeyboardActions();
        refreshLabels();
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

    public void refreshLabels() {
        titleLabel.setText(I18n.get("top.title"));
        collapseButton.setText(I18n.get("nav.collapse"));
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
                    NavNode groupNode =
                            new NavNode(Kind.GROUP, group.getId(), group.getLabel());
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
            label.setFont(node != null && node.kind == Kind.GROUP
                    ? UIUtils.plainFont().deriveFont(Font.BOLD)
                    : UIUtils.plainFont());
            label.setEnabled(node == null || node.kind != Kind.EMPTY);
            label.setToolTipText(node == null ? null : node.label);
            return label;
        }
    }
}
