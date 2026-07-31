package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.misc.ssh.model.RemoteEndpoint;
import com.aqishi.toolbox.misc.ssh.model.SshConfigStore;
import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.session.SshTunnelBridge;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** ZooKeeper node browser and editor with optional SSH local forwarding. */
public class ZooKeeperPanel extends ToolPanel {

    private JTextField serversField;
    private JSpinner timeoutSpinner;
    private JCheckBox useSshCheck;
    private JComboBox<SshConnectionConfig> sshCombo;
    private JButton connectBtn;
    private JLabel statusLabel;

    private JTree nodeTree;
    private DefaultTreeModel treeModel;
    private JTextField pathField;
    private JTextArea dataArea;
    private JLabel versionLabel;
    private JButton refreshBtn;
    private JButton createBtn;
    private JButton deleteBtn;
    private JButton saveDataBtn;

    private volatile ZooKeeper zooKeeper;
    private volatile boolean connected;
    private volatile List<SshTunnelBridge.BridgeResult> activeSshBridges = new ArrayList<>();

    public ZooKeeperPanel() {
        super("misc", "zookeeper.management",
                "ZooKeeper", "Zookeeper", "ZK", "节点", "分布式协调", "注册中心");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        root.add(buildConnectionCard(), BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(buildTreeCard(), buildDataCard(), 0.32), BorderLayout.CENTER);
        setConnectionInputsEnabled(true);
        return root;
    }

    private Card buildConnectionCard() {
        serversField = Fields.mono("127.0.0.1:2181");
        timeoutSpinner = Fields.spinner(15000, 2000, 60000, 1000);
        connectBtn = Buttons.primary("连接");
        connectBtn.addActionListener(e -> toggleConnection());

        useSshCheck = Fields.check("启用 SSH 隧道", false);
        List<SshConnectionConfig> configs = SshConfigStore.getInstance().getAll();
        sshCombo = Fields.combo(configs.toArray(new SshConnectionConfig[0]));
        sshCombo.setEnabled(false);
        useSshCheck.addActionListener(e -> {
            sshCombo.setEnabled(useSshCheck.isSelected() && !connected);
            if (!useSshCheck.isSelected()) releaseSshBridges();
        });
        SshConfigStore.getInstance().addChangeListener(this::refreshSshConfigs);

        JPanel sshRow = Layouts.box(Tokens.SPACE_MD, 0);
        sshRow.add(useSshCheck, BorderLayout.WEST);
        sshRow.add(sshCombo, BorderLayout.CENTER);

        FormGrid form = new FormGrid();
        form.row("连接地址:", serversField);
        form.row("会话超时:", Layouts.wrapRow(timeoutSpinner, Fields.caption("毫秒")));
        form.row("SSH 隧道:", sshRow);

        JPanel body = Layouts.box();
        body.add(form, BorderLayout.CENTER);
        Card card = Card.titled("ZooKeeper 连接");
        card.setContent(body);
        card.addHeaderAction(connectBtn);
        statusLabel = Fields.caption("未连接");
        card.setFooter(statusLabel);
        return card;
    }

    private Card buildTreeCard() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(new NodeRef("/"));
        treeModel = new DefaultTreeModel(root);
        nodeTree = new JTree(treeModel);
        nodeTree.setRootVisible(true);
        nodeTree.setShowsRootHandles(true);
        nodeTree.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        nodeTree.addTreeSelectionListener(this::onNodeSelected);

        refreshBtn = Buttons.secondary("刷新");
        refreshBtn.addActionListener(e -> refreshSelectedNode());
        createBtn = Buttons.primary("创建节点");
        createBtn.addActionListener(e -> createNode());
        deleteBtn = Buttons.danger("删除节点");
        deleteBtn.addActionListener(e -> deleteNode());

        JPanel toolbar = Layouts.wrapRow(refreshBtn, createBtn, deleteBtn);
        Card card = Card.flush("节点树");
        JPanel content = Layouts.box(0, Tokens.SPACE_SM);
        content.add(toolbar, BorderLayout.NORTH);
        content.add(Fields.scroll(nodeTree), BorderLayout.CENTER);
        card.setContent(content);
        return card;
    }

    private Card buildDataCard() {
        pathField = Fields.mono("");
        pathField.setEditable(false);
        versionLabel = Fields.caption("未选择节点");
        dataArea = Fields.area(16, 60);
        saveDataBtn = Buttons.primary("保存数据");
        saveDataBtn.addActionListener(e -> saveNodeData());

        JPanel pathRow = Layouts.box(Tokens.SPACE_MD, 0);
        pathRow.add(pathField, BorderLayout.CENTER);
        pathRow.add(versionLabel, BorderLayout.EAST);

        JPanel content = Layouts.box(0, Tokens.SPACE_SM);
        content.add(pathRow, BorderLayout.NORTH);
        content.add(Fields.scrollBoxed(dataArea), BorderLayout.CENTER);

        Card card = Card.titled("节点数据");
        card.setContent(content);
        card.addHeaderAction(saveDataBtn);
        return card;
    }

    private void toggleConnection() {
        if (connected) {
            disconnect();
        } else {
            connect();
        }
    }

    private void connect() {
        final String rawServers = serversField.getText().trim();
        final int sessionTimeout = ((Number) timeoutSpinner.getValue()).intValue();
        final boolean sshEnabled = useSshCheck.isSelected();
        final SshConnectionConfig sshConfig = (SshConnectionConfig) sshCombo.getSelectedItem();
        if (rawServers.isEmpty()) {
            UIUtils.error(getView(), "请输入 ZooKeeper 连接地址");
            return;
        }
        if (sshEnabled && sshConfig == null) {
            UIUtils.error(getView(), "请选择用于隧道的 SSH 服务器配置");
            return;
        }

        closeConnection();
        connectBtn.setEnabled(false);
        statusLabel.setText("正在连接...");

        new SwingWorker<ConnectionResult, Void>() {
            @Override
            protected ConnectionResult doInBackground() throws Exception {
                List<RemoteEndpoint> endpoints = RemoteEndpoint.parseList(rawServers, 2181);
                List<SshTunnelBridge.BridgeResult> bridges = new ArrayList<>();
                ZooKeeper client = null;
                try {
                    StringBuilder connectString = new StringBuilder();
                    for (RemoteEndpoint endpoint : endpoints) {
                        String host = endpoint.getHost();
                        int port = endpoint.getPort();
                        if (sshEnabled) {
                            SshTunnelBridge.BridgeResult bridge = SshTunnelBridge.bridge(
                                    sshConfig.getId(), host, port);
                            bridges.add(bridge);
                            host = bridge.getLocalHost();
                            port = bridge.getLocalPort();
                        }
                        if (connectString.length() > 0) connectString.append(',');
                        connectString.append(host).append(':').append(port);
                    }

                    final CountDownLatch connectedLatch = new CountDownLatch(1);
                    final String[] state = new String[]{""};
                    client = new ZooKeeper(connectString.toString(), sessionTimeout, event -> {
                        state[0] = event.getState().name();
                        if (event.getState() == Watcher.Event.KeeperState.SyncConnected
                                || event.getState() == Watcher.Event.KeeperState.Expired) {
                            connectedLatch.countDown();
                        }
                        SwingUtilities.invokeLater(() -> updateWatcherStatus(event));
                    });
                    if (!connectedLatch.await(Math.max(15000L, sessionTimeout + 5000L), TimeUnit.MILLISECONDS)) {
                        throw new IllegalStateException("ZooKeeper 连接超时");
                    }
                    if (!Watcher.Event.KeeperState.SyncConnected.name().equals(state[0])) {
                        throw new IllegalStateException("ZooKeeper 会话未建立: " + state[0]);
                    }
                    return new ConnectionResult(client, bridges);
                } catch (Exception error) {
                    closeQuietly(client);
                    closeBridges(bridges);
                    throw error;
                }
            }

            @Override
            protected void done() {
                try {
                    ConnectionResult result = get();
                    zooKeeper = result.client;
                    activeSshBridges = result.bridges;
                    connected = true;
                    setConnectionInputsEnabled(false);
                    connectBtn.setText("断开");
                    connectBtn.setEnabled(true);
                    statusLabel.setText(sshEnabled ? "已连接（SSH 隧道）" : "已连接");
                    refreshRoot();
                } catch (Exception error) {
                    connected = false;
                    connectBtn.setText("连接");
                    connectBtn.setEnabled(true);
                    setConnectionInputsEnabled(true);
                    statusLabel.setText("连接失败");
                    UIUtils.error(getView(), "连接 ZooKeeper 失败:\n" + safeMessage(error));
                }
            }
        }.execute();
    }

    private void updateWatcherStatus(WatchedEvent event) {
        if (connected) {
            statusLabel.setText("会话状态: " + event.getState());
        }
    }

    private void disconnect() {
        closeConnection();
        connected = false;
        setConnectionInputsEnabled(true);
        connectBtn.setText("连接");
        connectBtn.setEnabled(true);
        statusLabel.setText("未连接");
        resetTree();
        pathField.setText("");
        versionLabel.setText("未选择节点");
        dataArea.setText("");
    }

    private void closeConnection() {
        ZooKeeper client = zooKeeper;
        zooKeeper = null;
        connected = false;
        closeQuietly(client);
        releaseSshBridges();
    }

    /** Called by the main window before the shared SSH bridge manager shuts down. */
    public void closeResources() {
        closeConnection();
    }

    private void setConnectionInputsEnabled(boolean enabled) {
        if (serversField != null) serversField.setEnabled(enabled);
        if (timeoutSpinner != null) timeoutSpinner.setEnabled(enabled);
        if (useSshCheck != null) useSshCheck.setEnabled(enabled);
        if (sshCombo != null) sshCombo.setEnabled(enabled && useSshCheck != null && useSshCheck.isSelected());
        if (refreshBtn != null) refreshBtn.setEnabled(!enabled);
        if (createBtn != null) createBtn.setEnabled(!enabled);
        if (deleteBtn != null) deleteBtn.setEnabled(!enabled);
        if (saveDataBtn != null) saveDataBtn.setEnabled(!enabled);
    }

    private void refreshRoot() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        refreshChildren(root, false);
        nodeTree.setSelectionPath(new javax.swing.tree.TreePath(root.getPath()));
    }

    private void refreshSelectedNode() {
        DefaultMutableTreeNode selected = selectedTreeNode();
        if (selected == null) selected = (DefaultMutableTreeNode) treeModel.getRoot();
        refreshChildren(selected, true);
    }

    private void refreshChildren(DefaultMutableTreeNode treeNode, boolean showStatus) {
        ZooKeeper client = zooKeeper;
        NodeRef ref = nodeRef(treeNode);
        if (!connected || client == null || ref == null) return;
        if (showStatus) statusLabel.setText("正在刷新 " + ref.path + " ...");
        final DefaultMutableTreeNode targetNode = treeNode;
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                return client.getChildren(ref.path, false);
            }

            @Override
            protected void done() {
                try {
                    List<String> children = get();
                    Collections.sort(children);
                    targetNode.removeAllChildren();
                    for (String child : children) {
                        targetNode.add(new DefaultMutableTreeNode(new NodeRef(childPath(ref.path, child))));
                    }
                    treeModel.reload(targetNode);
                    if (showStatus) statusLabel.setText("已刷新 " + ref.path + "，共 " + children.size() + " 个子节点");
                } catch (Exception error) {
                    statusLabel.setText("刷新失败: " + safeMessage(error));
                }
            }
        }.execute();
    }

    private void onNodeSelected(TreeSelectionEvent event) {
        DefaultMutableTreeNode selected = selectedTreeNode();
        NodeRef ref = nodeRef(selected);
        if (ref == null) return;
        pathField.setText(ref.path);
        loadNodeData(ref.path);
        refreshChildren(selected, false);
    }

    private void loadNodeData(String path) {
        ZooKeeper client = zooKeeper;
        if (!connected || client == null) return;
        new SwingWorker<NodeData, Void>() {
            @Override
            protected NodeData doInBackground() throws Exception {
                Stat stat = new Stat();
                byte[] data = client.getData(path, false, stat);
                return new NodeData(data, stat.getVersion());
            }

            @Override
            protected void done() {
                try {
                    NodeData nodeData = get();
                    if (path.equals(pathField.getText())) {
                        dataArea.setText(new String(nodeData.data, StandardCharsets.UTF_8));
                        versionLabel.setText("version: " + nodeData.version);
                    }
                } catch (Exception error) {
                    if (path.equals(pathField.getText())) {
                        dataArea.setText("");
                        versionLabel.setText("读取失败");
                    }
                }
            }
        }.execute();
    }

    private void createNode() {
        DefaultMutableTreeNode selected = selectedTreeNode();
        NodeRef parent = nodeRef(selected);
        if (parent == null || !connected) return;
        String name = UIUtils.input(getView(), "请输入子节点名称（不能包含 /）:", "");
        if (name == null) return;
        name = name.trim();
        if (name.isEmpty() || name.contains("/")) {
            UIUtils.error(getView(), "子节点名称不能为空且不能包含 / ");
            return;
        }
        String data = UIUtils.input(getView(), "请输入节点数据（可留空）:", "");
        if (data == null) return;
        String path = childPath(parent.path, name);
        ZooKeeper client = zooKeeper;
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return client.create(path, data.getBytes(StandardCharsets.UTF_8),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            }

            @Override
            protected void done() {
                try {
                    get();
                    statusLabel.setText("节点已创建: " + path);
                    refreshChildren(selected, true);
                } catch (Exception error) {
                    UIUtils.error(getView(), "创建节点失败:\n" + safeMessage(error));
                }
            }
        }.execute();
    }

    private void saveNodeData() {
        String path = pathField.getText().trim();
        ZooKeeper client = zooKeeper;
        if (!connected || client == null || path.isEmpty()) return;
        String data = dataArea.getText();
        saveDataBtn.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                client.setData(path, data.getBytes(StandardCharsets.UTF_8), -1);
                return null;
            }

            @Override
            protected void done() {
                saveDataBtn.setEnabled(true);
                try {
                    get();
                    statusLabel.setText("节点数据已保存: " + path);
                    loadNodeData(path);
                } catch (Exception error) {
                    UIUtils.error(getView(), "保存节点数据失败:\n" + safeMessage(error));
                }
            }
        }.execute();
    }

    private void deleteNode() {
        DefaultMutableTreeNode selected = selectedTreeNode();
        NodeRef ref = nodeRef(selected);
        ZooKeeper client = zooKeeper;
        if (!connected || client == null || ref == null || "/".equals(ref.path)) {
            UIUtils.info(getView(), "请选择要删除的非根节点");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(getView(), "确认删除节点 " + ref.path + "？", "确认删除",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                List<String> children = client.getChildren(ref.path, false);
                if (!children.isEmpty()) throw new IllegalStateException("节点包含子节点，请先删除子节点");
                client.delete(ref.path, -1);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    statusLabel.setText("节点已删除: " + ref.path);
                    DefaultMutableTreeNode parent = (DefaultMutableTreeNode) selected.getParent();
                    if (parent != null) refreshChildren(parent, true);
                    pathField.setText("");
                    dataArea.setText("");
                    versionLabel.setText("未选择节点");
                } catch (Exception error) {
                    UIUtils.error(getView(), "删除节点失败:\n" + safeMessage(error));
                }
            }
        }.execute();
    }

    private void resetTree() {
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
        root.removeAllChildren();
        treeModel.reload();
    }

    private DefaultMutableTreeNode selectedTreeNode() {
        Object selected = nodeTree.getLastSelectedPathComponent();
        return selected instanceof DefaultMutableTreeNode ? (DefaultMutableTreeNode) selected : null;
    }

    private static NodeRef nodeRef(DefaultMutableTreeNode node) {
        if (node == null || !(node.getUserObject() instanceof NodeRef)) return null;
        return (NodeRef) node.getUserObject();
    }

    private void refreshSshConfigs() {
        Runnable refresh = () -> {
            if (sshCombo == null) return;
            String selectedId = null;
            SshConnectionConfig selected = (SshConnectionConfig) sshCombo.getSelectedItem();
            if (selected != null) selectedId = selected.getId();
            sshCombo.removeAllItems();
            for (SshConnectionConfig config : SshConfigStore.getInstance().getAll()) sshCombo.addItem(config);
            if (selectedId != null) {
                for (int i = 0; i < sshCombo.getItemCount(); i++) {
                    if (selectedId.equals(sshCombo.getItemAt(i).getId())) {
                        sshCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
            sshCombo.setEnabled(!connected && useSshCheck != null && useSshCheck.isSelected());
        };
        if (SwingUtilities.isEventDispatchThread()) refresh.run();
        else SwingUtilities.invokeLater(refresh);
    }

    private void releaseSshBridges() {
        List<SshTunnelBridge.BridgeResult> bridges = activeSshBridges;
        activeSshBridges = new ArrayList<>();
        closeBridges(bridges);
    }

    private static void closeBridges(List<SshTunnelBridge.BridgeResult> bridges) {
        if (bridges == null) return;
        for (SshTunnelBridge.BridgeResult bridge : bridges) {
            if (bridge != null) bridge.close();
        }
    }

    private static void closeQuietly(ZooKeeper client) {
        if (client == null) return;
        try {
            client.close();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static String childPath(String parent, String child) {
        return "/".equals(parent) ? "/" + child : parent + "/" + child;
    }

    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().trim().isEmpty())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty()) message = current.getClass().getSimpleName();
        return message;
    }

    private static final class NodeRef {
        private final String path;

        private NodeRef(String path) {
            this.path = path;
        }

        @Override
        public String toString() {
            if ("/".equals(path)) return "/";
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }

    private static final class NodeData {
        private final byte[] data;
        private final int version;

        private NodeData(byte[] data, int version) {
            this.data = data == null ? new byte[0] : data;
            this.version = version;
        }
    }

    private static final class ConnectionResult {
        private final ZooKeeper client;
        private final List<SshTunnelBridge.BridgeResult> bridges;

        private ConnectionResult(ZooKeeper client, List<SshTunnelBridge.BridgeResult> bridges) {
            this.client = client;
            this.bridges = bridges;
        }
    }
}
