package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.util.UIUtils;

import com.aqishi.toolbox.feature.network.ssh.infra.SshConfigStore;
import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.ui.SshConfigDialog;
import com.aqishi.toolbox.feature.network.ssh.ui.SshSessionTabPanel;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.ToolGlyph;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;

/**
 * SSH 客户端工具面板：支持服务器连接管理、分组树展示、多会话交互终端与 SFTP 文件传输
 */
public class SshClientPanel extends ToolPanel implements ManagedResourceOwner {

    private final SshConfigStore configStore;

    private JTextField searchField;
    private JTree serverTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;

    private JTabbedPane sessionTabbedPane;
    private JPanel welcomeTabPanel;

    public SshClientPanel() {
        super(ToolCatalog.SSH);
        this.configStore = SshConfigStore.getInstance();
    }

    /** Closes all interactive SSH sessions when the main window exits. */
    public void closeSessions() {
        if (sessionTabbedPane == null) return;
        for (int i = 0; i < sessionTabbedPane.getTabCount(); i++) {
            Component component = sessionTabbedPane.getComponentAt(i);
            if (component instanceof SshSessionTabPanel) {
                ((SshSessionTabPanel) component).closeSession();
            }
        }
    }

    @Override
    public void closeResources() {
        closeSessions();
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setBorder(null);
        mainSplit.setOpaque(false);
        mainSplit.setDividerSize(Tokens.SPACE_SM);
        mainSplit.setDividerLocation(272);
        mainSplit.setContinuousLayout(true);

        // 1. 左侧：服务器连接列表与分组树
        JPanel leftPanel = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        leftPanel.setOpaque(false);

        // 搜索框
        JPanel searchBox = new JPanel(new BorderLayout(4, 4));
        searchField = Fields.text("", "搜索服务器名称、Host...");
        JButton searchBtn = Buttons.secondary("搜索");
        searchBtn.addActionListener(e -> refreshServerTree());
        searchBox.add(searchField, BorderLayout.CENTER);
        searchBox.add(searchBtn, BorderLayout.EAST);

        // 绑定按键回车即搜索
        searchField.addActionListener(e -> refreshServerTree());

        // 服务器 JTree
        rootNode = new DefaultMutableTreeNode("服务器配置");
        treeModel = new DefaultTreeModel(rootNode);
        serverTree = new JTree(treeModel);
        serverTree.setFont(Tokens.fontBody());
        serverTree.setRowHeight(24);
        serverTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        serverTree.setShowsRootHandles(true);

        // 展开根节点
        serverTree.setRootVisible(false);

        // 双击连接节点触发连接
        serverTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int row = serverTree.getClosestRowForLocation(e.getX(), e.getY());
                    if (row >= 0) serverTree.setSelectionRow(row);
                }
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    connectSelectedServer();
                }
            }
        });

        // 树节点右键菜单
        serverTree.setComponentPopupMenu(createTreePopupMenu());

        JScrollPane treeScroll = Fields.scroll(serverTree);
        treeScroll.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);

        // 底部工具栏按钮：放不下时自动折行，不会把最后一颗按钮裁掉
        JPanel treeToolBar = Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_SM);
        JButton addBtn = Buttons.primary("+ 新增");
        addBtn.addActionListener(e -> openConfigDialog(null));

        JButton editBtn = Buttons.secondary("修改");
        editBtn.addActionListener(e -> editSelectedServer());

        JButton delBtn = Buttons.secondary("删除");
        delBtn.addActionListener(e -> deleteSelectedServer());

        JButton connBtn = Buttons.primary("⚡ 发起连接");
        connBtn.addActionListener(e -> connectSelectedServer());

        treeToolBar.add(addBtn);
        treeToolBar.add(editBtn);
        treeToolBar.add(delBtn);
        treeToolBar.add(connBtn);

        Card treeCard = Card.plain();
        treeCard.setFlush(true);
        treeCard.setContent(treeScroll);

        leftPanel.add(searchBox, BorderLayout.NORTH);
        leftPanel.add(treeCard, BorderLayout.CENTER);
        leftPanel.add(treeToolBar, BorderLayout.SOUTH);

        mainSplit.setLeftComponent(leftPanel);

        // 2. 右侧：多会话页签区 JTabbedPane
        sessionTabbedPane = new JTabbedPane();
        sessionTabbedPane.setFont(Tokens.fontSectionTitle());

        // 创建默认引导页
        welcomeTabPanel = createWelcomePanel();
        sessionTabbedPane.addTab("起始页", welcomeTabPanel);

        Card sessionCard = Card.plain();
        sessionCard.setFlush(true);
        sessionCard.setContent(sessionTabbedPane);
        mainSplit.setRightComponent(sessionCard);

        // 加载服务器数据
        refreshServerTree();

        root.add(mainSplit, BorderLayout.CENTER);
        return root;
    }

    private JPopupMenu createTreePopupMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem connItem = new JMenuItem("⚡ 发起 SSH 连接");
        connItem.addActionListener(e -> connectSelectedServer());

        JMenuItem editItem = new JMenuItem("修改配置...");
        editItem.addActionListener(e -> editSelectedServer());

        JMenuItem copyItem = new JMenuItem("复制/克隆配置");
        copyItem.addActionListener(e -> cloneSelectedServer());

        JMenuItem delItem = new JMenuItem("删除服务器");
        delItem.addActionListener(e -> deleteSelectedServer());

        menu.add(connItem);
        menu.addSeparator();
        menu.add(editItem);
        menu.add(copyItem);
        menu.add(delItem);
        return menu;
    }

    private void refreshServerTree() {
        rootNode.removeAllChildren();

        String filter = searchField != null ? searchField.getText().trim().toLowerCase() : "";
        Map<String, List<SshConnectionConfig>> grouped = configStore.getGroupedConfigs();

        for (Map.Entry<String, List<SshConnectionConfig>> entry : grouped.entrySet()) {
            String groupName = entry.getKey();
            DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(groupName);

            for (SshConnectionConfig cfg : entry.getValue()) {
                if (filter.isEmpty() || matchesFilter(cfg, filter)) {
                    DefaultMutableTreeNode itemNode = new DefaultMutableTreeNode(cfg);
                    groupNode.add(itemNode);
                }
            }

            if (groupNode.getChildCount() > 0) {
                rootNode.add(groupNode);
            }
        }

        treeModel.reload();

        // 展开所有分组节点
        for (int i = 0; i < serverTree.getRowCount(); i++) {
            serverTree.expandRow(i);
        }
    }

    private boolean matchesFilter(SshConnectionConfig cfg, String filter) {
        if (cfg.getName() != null && cfg.getName().toLowerCase().contains(filter)) return true;
        if (cfg.getHost() != null && cfg.getHost().toLowerCase().contains(filter)) return true;
        if (cfg.getUsername() != null && cfg.getUsername().toLowerCase().contains(filter)) return true;
        if (cfg.getGroup() != null && cfg.getGroup().toLowerCase().contains(filter)) return true;
        return false;
    }

    private SshConnectionConfig getSelectedConfig() {
        TreePath path = serverTree.getSelectionPath();
        if (path == null) return null;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node != null && node.getUserObject() instanceof SshConnectionConfig) {
            return (SshConnectionConfig) node.getUserObject();
        }
        return null;
    }

    private void openConfigDialog(SshConnectionConfig existing) {
        Window owner = SwingUtilities.getWindowAncestor(serverTree);
        SshConfigDialog dialog = new SshConfigDialog(owner, existing);
        dialog.setVisible(true);

        if (dialog.isSaved()) {
            configStore.addOrUpdate(dialog.getConfig());
            refreshServerTree();
        }
    }

    private void editSelectedServer() {
        SshConnectionConfig cfg = getSelectedConfig();
        if (cfg != null) {
            openConfigDialog(cfg);
        } else {
            UIUtils.info(serverTree, "请先在左侧树中选择要修改的服务器配置");
        }
    }

    private void cloneSelectedServer() {
        SshConnectionConfig cfg = getSelectedConfig();
        if (cfg != null) {
            SshConnectionConfig cloned = cfg.duplicate();
            cloned.setName(cfg.getName() + " (副本)");
            openConfigDialog(cloned);
        }
    }

    private void deleteSelectedServer() {
        SshConnectionConfig cfg = getSelectedConfig();
        if (cfg != null) {
            boolean confirm = UIUtils.confirm(serverTree, "确定删除服务器配置 \"" + cfg.getName() + "\" ?", "确认删除");
            if (confirm) {
                configStore.delete(cfg.getId());
                refreshServerTree();
            }
        } else {
            UIUtils.info(serverTree, "请先在左侧树中选择要删除的服务器配置");
        }
    }

    /**
     * 打开并建立新的 SSH 会话 Tab 页
     */
    private void connectSelectedServer() {
        SshConnectionConfig cfg = getSelectedConfig();
        if (cfg == null) {
            UIUtils.info(serverTree, "请先在左侧选择要连接的服务器");
            return;
        }

        // 建立 SessionTabPanel
        SshSessionTabPanel sessionTab = new SshSessionTabPanel(cfg);

        // 添加到 JTabbedPane
        String tabTitle = cfg.getName() != null && !cfg.getName().isEmpty() ? cfg.getName() : cfg.getHost();
        sessionTabbedPane.addTab(tabTitle, sessionTab);

        int tabIndex = sessionTabbedPane.getTabCount() - 1;
        sessionTabbedPane.setTabComponentAt(tabIndex, createTabHeader(tabTitle, sessionTab));
        sessionTabbedPane.setSelectedIndex(tabIndex);
    }

    /**
     * 自定义带关闭图标的 Tab 标题头
     */
    private JPanel createTabHeader(String title, SshSessionTabPanel sessionTab) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        header.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(Tokens.fontSectionTitle());

        JButton closeBtn = new JButton("×");
        closeBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        closeBtn.setMargin(new Insets(0, 4, 0, 4));
        closeBtn.setFocusable(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setContentAreaFilled(false);
        closeBtn.setToolTipText("关闭此会话");

        closeBtn.addActionListener(e -> {
            int idx = sessionTabbedPane.indexOfComponent(sessionTab);
            if (idx >= 0) {
                sessionTab.closeSession();
                sessionTabbedPane.remove(idx);
            }
        });

        header.add(titleLabel);
        header.add(closeBtn);
        return header;
    }

    private JPanel createWelcomePanel() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        box.setOpaque(false);

        JLabel icon = new JLabel(ToolGlyph.of("ssh", "network", 56));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel title = new JLabel(I18n.get("tool.ssh.welcome.title"));
        title.setFont(Tokens.fontPageTitle());
        title.setForeground(Tokens.foreground());
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel hint = new JLabel(I18n.get("tool.ssh.welcome.hint"));
        hint.setFont(Tokens.fontBody());
        hint.setForeground(Tokens.secondaryForeground());
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton newConnBtn = Buttons.primary("+ 新增服务器配置");
        newConnBtn.addActionListener(e -> openConfigDialog(null));
        newConnBtn.setAlignmentX(Component.CENTER_ALIGNMENT);

        box.add(icon);
        box.add(Box.createVerticalStrut(Tokens.SPACE_LG));
        box.add(title);
        box.add(Box.createVerticalStrut(Tokens.SPACE_XS + 2));
        box.add(hint);
        box.add(Box.createVerticalStrut(Tokens.SPACE_LG + 4));
        box.add(newConnBtn);

        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);
        wrap.add(box);
        return wrap;
    }
}
