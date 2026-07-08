package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * Kubernetes 服务器集群管理工具：
 * 支持多集群配置管理、Kubeconfig导入、命名空间切换，
 * 以及 Pods, Deployments, Services, ConfigMaps, Nodes 的列表展示、查看 YAML、查看日志、修改副本数、删除资源等。
 */
public class K8sManagerPanel extends ToolPanel {

    private JComboBox<String> profileCombo;
    private JButton saveProfileBtn;
    private JButton delProfileBtn;
    private JButton importKubeconfigBtn;

    private JTextField serverField;
    private JPasswordField tokenField;
    private JCheckBox skipTlsCheck;
    private JButton connBtn;

    private JComboBox<String> nsCombo;
    private JButton refreshNsBtn;
    private JButton applyYamlBtn;
    private JTextField searchField;

    private javax.swing.table.TableRowSorter<DefaultTableModel> podSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> deploySorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> svcSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> cmSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> nodeSorter;

    private JTabbedPane resourceTabs;
    
    // Tables and Models
    private JTable podTable;
    private DefaultTableModel podModel;
    private JTable deployTable;
    private DefaultTableModel deployModel;
    private JTable svcTable;
    private DefaultTableModel svcModel;
    private JTable cmTable;
    private DefaultTableModel cmModel;
    private JTable nodeTable;
    private DefaultTableModel nodeModel;

    // State
    private boolean isConnected = false;
    private String activeServerUrl = "";
    private String activeToken = "";
    private boolean activeSkipTls = true;
    private final Map<String, K8sProfile> profiles = new LinkedHashMap<>();
    private final Preferences prefs = Preferences.userNodeForPackage(K8sManagerPanel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private boolean ignoreProfileEvents = false;

    public K8sManagerPanel() {
        super("dev", "k8s.manager",
                "k8s", "kubernetes", "容器", "集群", "运维", "kubeconfig", "docker", "pod", "deployment");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 1. Top Panel: Connection Config
        JPanel connPanel = new JPanel(new GridBagLayout());
        connPanel.setBorder(BorderFactory.createTitledBorder("Kubernetes 集群配置"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Line 1: Profiles & Import
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        connPanel.add(new JLabel("集群配置:"), gbc);
        
        profileCombo = new JComboBox<>();
        profileCombo.setPreferredSize(new Dimension(150, 30));
        gbc.gridx = 1; gbc.weightx = 0.3;
        connPanel.add(profileCombo, gbc);

        JPanel profileBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        saveProfileBtn = new JButton("保存配置");
        delProfileBtn = new JButton("删除配置");
        importKubeconfigBtn = new JButton("导入 Kubeconfig");
        profileBtnRow.add(saveProfileBtn);
        profileBtnRow.add(delProfileBtn);
        profileBtnRow.add(importKubeconfigBtn);
        gbc.gridx = 2; gbc.gridwidth = 2; gbc.weightx = 0.7;
        connPanel.add(profileBtnRow, gbc);

        // Line 2: Server Address & Token & Skip TLS
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
        connPanel.add(new JLabel("API Server:"), gbc);

        serverField = new JTextField("https://127.0.0.1:6443", 25);
        gbc.gridx = 1; gbc.weightx = 0.4;
        connPanel.add(serverField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        connPanel.add(new JLabel("Token:"), gbc);

        tokenField = new JPasswordField(20);
        gbc.gridx = 3; gbc.weightx = 0.5;
        connPanel.add(tokenField, gbc);

        // Line 3: TLS and Connect Button
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0;
        connPanel.add(new JLabel("安全设置:"), gbc);

        skipTlsCheck = new JCheckBox("跳过 TLS 证书验证 (推荐开发测试环境使用)", true);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 0.7;
        connPanel.add(skipTlsCheck, gbc);

        connBtn = UIUtils.button("连接集群", 100);
        gbc.gridx = 3; gbc.gridwidth = 1; gbc.weightx = 0.3;
        connPanel.add(connBtn, gbc);

        root.add(connPanel, BorderLayout.NORTH);

        // 2. Middle Panel: Namespace Selector & Resource Explorer
        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));

        JPanel nsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        nsCombo = new JComboBox<>();
        nsCombo.setPreferredSize(new Dimension(180, 30));
        refreshNsBtn = new JButton("刷新空间列表");
        applyYamlBtn = new JButton("发布资源 (Apply YAML)");
        applyYamlBtn.addActionListener(e -> {
            if (!isConnected) {
                UIUtils.info(null, "请先连接 K8s 集群！");
                return;
            }
            showApplyYamlDialog();
        });

        searchField = new JTextField();
        searchField.setPreferredSize(new Dimension(180, 30));
        searchField.putClientProperty("JTextField.placeholderText", "过滤检索当前列表...");
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            private void filter() {
                String text = searchField.getText().trim();
                javax.swing.RowFilter<Object, Object> filter = null;
                if (!text.isEmpty()) {
                    filter = javax.swing.RowFilter.regexFilter("(?i)" + text);
                }
                if (podSorter != null) podSorter.setRowFilter(filter);
                if (deploySorter != null) deploySorter.setRowFilter(filter);
                if (svcSorter != null) svcSorter.setRowFilter(filter);
                if (cmSorter != null) cmSorter.setRowFilter(filter);
                if (nodeSorter != null) nodeSorter.setRowFilter(filter);
            }
        });

        nsRow.add(new JLabel("命名空间 (Namespace):"));
        nsRow.add(nsCombo);
        nsRow.add(refreshNsBtn);
        nsRow.add(applyYamlBtn);
        nsRow.add(new JLabel("  |  "));
        nsRow.add(new JLabel("检索 (Filter):"));
        nsRow.add(searchField);
        centerPanel.add(nsRow, BorderLayout.NORTH);

        resourceTabs = new JTabbedPane();
        
        // Tab A: Pods
        JPanel podTab = new JPanel(new BorderLayout(6, 6));
        podModel = new DefaultTableModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "状态 (Status)", "重启次数 (Restarts)", "Pod IP", "节点 (Node)", "存活时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        podTable = new JTable(podModel);
        podTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        podSorter = new javax.swing.table.TableRowSorter<>(podModel);
        podTable.setRowSorter(podSorter);
        podTab.add(new JScrollPane(podTable), BorderLayout.CENTER);
        JPanel podBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshPodBtn = new JButton("刷新");
        JButton yamlPodBtn = new JButton("查看 YAML");
        JButton logPodBtn = new JButton("查看日志");
        JButton delPodBtn = new JButton("删除 Pod");
        podBtnRow.add(refreshPodBtn);
        podBtnRow.add(yamlPodBtn);
        podBtnRow.add(logPodBtn);
        podBtnRow.add(delPodBtn);
        podTab.add(podBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("Pods", podTab);

        // Tab B: Deployments
        JPanel deployTab = new JPanel(new BorderLayout(6, 6));
        deployModel = new DefaultTableModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "就绪状态 (Ready)", "最新副本 (Up-to-date)", "可用副本 (Available)", "存活时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        deployTable = new JTable(deployModel);
        deployTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        deploySorter = new javax.swing.table.TableRowSorter<>(deployModel);
        deployTable.setRowSorter(deploySorter);
        deployTab.add(new JScrollPane(deployTable), BorderLayout.CENTER);
        JPanel deployBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshDeployBtn = new JButton("刷新");
        JButton yamlDeployBtn = new JButton("查看 YAML");
        JButton scaleDeployBtn = new JButton("修改副本数 (Scale)");
        JButton delDeployBtn = new JButton("删除 Deployment");
        deployBtnRow.add(refreshDeployBtn);
        deployBtnRow.add(yamlDeployBtn);
        deployBtnRow.add(scaleDeployBtn);
        deployBtnRow.add(delDeployBtn);
        deployTab.add(deployBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("Deployments", deployTab);

        // Tab C: Services
        JPanel svcTab = new JPanel(new BorderLayout(6, 6));
        svcModel = new DefaultTableModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "类型 (Type)", "集群 IP (Cluster-IP)", "外部 IP (External-IP)", "端口 (Ports)", "存活时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        svcTable = new JTable(svcModel);
        svcTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        svcSorter = new javax.swing.table.TableRowSorter<>(svcModel);
        svcTable.setRowSorter(svcSorter);
        svcTab.add(new JScrollPane(svcTable), BorderLayout.CENTER);
        JPanel svcBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshSvcBtn = new JButton("刷新");
        JButton yamlSvcBtn = new JButton("查看 YAML");
        JButton delSvcBtn = new JButton("删除 Service");
        svcBtnRow.add(refreshSvcBtn);
        svcBtnRow.add(yamlSvcBtn);
        svcBtnRow.add(delSvcBtn);
        svcTab.add(svcBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("Services", svcTab);

        // Tab D: ConfigMaps
        JPanel cmTab = new JPanel(new BorderLayout(6, 6));
        cmModel = new DefaultTableModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "键数量 (Keys)", "存活时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        cmTable = new JTable(cmModel);
        cmTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cmSorter = new javax.swing.table.TableRowSorter<>(cmModel);
        cmTable.setRowSorter(cmSorter);
        cmTab.add(new JScrollPane(cmTable), BorderLayout.CENTER);
        JPanel cmBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshCmBtn = new JButton("刷新");
        JButton yamlCmBtn = new JButton("查看 YAML");
        JButton delCmBtn = new JButton("删除 ConfigMap");
        cmBtnRow.add(refreshCmBtn);
        cmBtnRow.add(yamlCmBtn);
        cmBtnRow.add(delCmBtn);
        cmTab.add(cmBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("ConfigMaps", cmTab);

        // Tab E: Nodes
        JPanel nodeTab = new JPanel(new BorderLayout(6, 6));
        nodeModel = new DefaultTableModel(new Object[]{"节点名称 (Name)", "状态 (Status)", "角色 (Roles)", "版本 (Version)", "系统版本 (OS)", "运行时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        nodeTable = new JTable(nodeModel);
        nodeTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nodeSorter = new javax.swing.table.TableRowSorter<>(nodeModel);
        nodeTable.setRowSorter(nodeSorter);
        nodeTab.add(new JScrollPane(nodeTable), BorderLayout.CENTER);
        JPanel nodeBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshNodeBtn = new JButton("刷新");
        JButton yamlNodeBtn = new JButton("查看 YAML");
        nodeBtnRow.add(refreshNodeBtn);
        nodeBtnRow.add(yamlNodeBtn);
        nodeTab.add(nodeBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("Nodes (集群节点)", nodeTab);

        centerPanel.add(resourceTabs, BorderLayout.CENTER);
        root.add(centerPanel, BorderLayout.CENTER);

        // Actions registration
        initActions(refreshPodBtn, yamlPodBtn, logPodBtn, delPodBtn,
                refreshDeployBtn, yamlDeployBtn, scaleDeployBtn, delDeployBtn,
                refreshSvcBtn, yamlSvcBtn, delSvcBtn,
                refreshCmBtn, yamlCmBtn, delCmBtn,
                refreshNodeBtn, yamlNodeBtn);

        toggleState(false);
        loadProfilesFromPrefs();

        return root;
    }

    private void toggleState(boolean connected) {
        this.isConnected = connected;
        connBtn.setText(connected ? "断开连接" : "连接集群");
        connBtn.setEnabled(true);

        profileCombo.setEnabled(!connected);
        saveProfileBtn.setEnabled(!connected);
        delProfileBtn.setEnabled(!connected);
        importKubeconfigBtn.setEnabled(!connected);

        serverField.setEnabled(!connected);
        tokenField.setEnabled(!connected);
        skipTlsCheck.setEnabled(!connected);

        nsCombo.setEnabled(connected);
        refreshNsBtn.setEnabled(connected);
        applyYamlBtn.setEnabled(connected);
        searchField.setEnabled(connected);
        resourceTabs.setEnabled(connected);

        if (!connected) {
            nsCombo.removeAllItems();
            clearAllTables();
        }
    }

    private void clearAllTables() {
        podModel.setRowCount(0);
        deployModel.setRowCount(0);
        svcModel.setRowCount(0);
        cmModel.setRowCount(0);
        nodeModel.setRowCount(0);
    }

    private void initActions(JButton refreshPodBtn, JButton yamlPodBtn, JButton logPodBtn, JButton delPodBtn,
                             JButton refreshDeployBtn, JButton yamlDeployBtn, JButton scaleDeployBtn, JButton delDeployBtn,
                             JButton refreshSvcBtn, JButton yamlSvcBtn, JButton delSvcBtn,
                             JButton refreshCmBtn, JButton yamlCmBtn, JButton delCmBtn,
                             JButton refreshNodeBtn, JButton yamlNodeBtn) {

        // Profile Selection
        profileCombo.addActionListener(e -> {
            if (ignoreProfileEvents) return;
            String selected = (String) profileCombo.getSelectedItem();
            if (selected != null && profiles.containsKey(selected)) {
                K8sProfile p = profiles.get(selected);
                serverField.setText(p.serverUrl);
                tokenField.setText(p.token);
                skipTlsCheck.setSelected(p.skipTls);
            }
        });

        // Profile Save
        saveProfileBtn.addActionListener(e -> {
            String name = UIUtils.input(null, "请输入集群配置名称:", "我的K8s服务器");
            if (name == null || name.trim().isEmpty()) return;
            name = name.trim();
            K8sProfile p = new K8sProfile(
                    name,
                    serverField.getText().trim(),
                    new String(tokenField.getPassword()),
                    skipTlsCheck.isSelected()
            );
            profiles.put(name, p);
            saveProfilesToPrefs();
            refreshProfilesCombo(name);
        });

        // Profile Delete
        delProfileBtn.addActionListener(e -> {
            String selected = (String) profileCombo.getSelectedItem();
            if (selected == null || !profiles.containsKey(selected)) {
                UIUtils.info(null, "请选择要删除的配置");
                return;
            }
            int opt = JOptionPane.showConfirmDialog(null, "确认删除配置 \"" + selected + "\"?", "提示", JOptionPane.YES_NO_OPTION);
            if (opt == JOptionPane.YES_OPTION) {
                profiles.remove(selected);
                saveProfilesToPrefs();
                refreshProfilesCombo(null);
            }
        });

        // Kubeconfig Import
        importKubeconfigBtn.addActionListener(e -> importKubeconfig());

        // Connect
        connBtn.addActionListener(e -> {
            if (isConnected) {
                toggleState(false);
            } else {
                connectCluster();
            }
        });

        // Namespace switch
        nsCombo.addActionListener(e -> {
            if (isConnected && nsCombo.getSelectedItem() != null) {
                refreshActiveTab();
            }
        });

        refreshNsBtn.addActionListener(e -> loadNamespaces());

        // Resource Tab Listeners
        resourceTabs.addChangeListener(e -> {
            if (isConnected) {
                refreshActiveTab();
            }
        });

        // Pod Buttons
        refreshPodBtn.addActionListener(e -> loadPods());
        yamlPodBtn.addActionListener(e -> viewResourceYaml("pods", podTable));
        logPodBtn.addActionListener(e -> viewPodLogs());
        delPodBtn.addActionListener(e -> deleteResource("pods", podTable, () -> loadPods()));

        // Deployment Buttons
        refreshDeployBtn.addActionListener(e -> loadDeployments());
        yamlDeployBtn.addActionListener(e -> viewResourceYaml("deployments", deployTable));
        scaleDeployBtn.addActionListener(e -> scaleDeployment());
        delDeployBtn.addActionListener(e -> deleteResource("deployments", deployTable, () -> loadDeployments()));

        // Service Buttons
        refreshSvcBtn.addActionListener(e -> loadServices());
        yamlSvcBtn.addActionListener(e -> viewResourceYaml("services", svcTable));
        delSvcBtn.addActionListener(e -> deleteResource("services", svcTable, () -> loadServices()));

        // ConfigMap Buttons
        refreshCmBtn.addActionListener(e -> loadConfigMaps());
        yamlCmBtn.addActionListener(e -> viewResourceYaml("configmaps", cmTable));
        delCmBtn.addActionListener(e -> deleteResource("configmaps", cmTable, () -> loadConfigMaps()));

        // Node Buttons
        refreshNodeBtn.addActionListener(e -> loadNodes());
        yamlNodeBtn.addActionListener(e -> viewResourceYaml("nodes", nodeTable));
    }

    private void refreshActiveTab() {
        int idx = resourceTabs.getSelectedIndex();
        switch (idx) {
            case 0: loadPods(); break;
            case 1: loadDeployments(); break;
            case 2: loadServices(); break;
            case 3: loadConfigMaps(); break;
            case 4: loadNodes(); break;
        }
    }

    private void connectCluster() {
        activeServerUrl = serverField.getText().trim();
        activeToken = new String(tokenField.getPassword());
        activeSkipTls = skipTlsCheck.isSelected();

        if (activeServerUrl.isEmpty()) {
            UIUtils.error(null, "API Server 地址不能为空！");
            return;
        }

        connBtn.setEnabled(false);
        connBtn.setText("连接中...");

        new SwingWorker<Boolean, Void>() {
            @Override
            protected Boolean doInBackground() throws Exception {
                if (activeSkipTls) {
                    try {
                        HttpsURLConnection.setDefaultSSLSocketFactory(getTrustAllSocketFactory());
                        HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
                    } catch (Exception ignored) {}
                }
                // Test connectivity by querying API version info or namespaces
                executeRequest("GET", "/api/v1/namespaces", null, activeSkipTls);
                return true;
            }

            @Override
            protected void done() {
                try {
                    get();
                    toggleState(true);
                    loadNamespaces();
                } catch (Exception ex) {
                    toggleState(false);
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    UIUtils.error(connBtn, "连接失败: " + cause.getMessage());
                }
            }
        }.execute();
    }

    private void loadNamespaces() {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                String resp = executeRequest("GET", "/api/v1/namespaces", null, activeSkipTls);
                JsonNode root = mapper.readTree(resp);
                List<String> list = new ArrayList<>();
                list.add("全部命名空间 (All)");
                JsonNode items = root.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        list.add(item.path("metadata").path("name").asText());
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                try {
                    List<String> list = get();
                    nsCombo.removeAllItems();
                    for (String ns : list) {
                        nsCombo.addItem(ns);
                    }
                    if (list.size() > 1) {
                        nsCombo.setSelectedIndex(0);
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }.execute();
    }

    private String getSelectedNamespace() {
        Object item = nsCombo.getSelectedItem();
        if (item == null) return "default";
        String s = item.toString();
        if (s.startsWith("全部命名空间")) {
            return "all";
        }
        return s;
    }

    private String formatAge(String creationTimestamp) {
        try {
            Instant created = Instant.parse(creationTimestamp);
            Duration d = Duration.between(created, Instant.now());
            long days = d.toDays();
            if (days > 0) return days + "d";
            long hours = d.toHours();
            if (hours > 0) return hours + "h";
            long mins = d.toMinutes();
            if (mins > 0) return mins + "m";
            return d.getSeconds() + "s";
        } catch (Exception e) {
            return "-";
        }
    }

    // Load Pods
    private void loadPods() {
        String ns = getSelectedNamespace();
        String path = ns.equals("all") ? "/api/v1/pods" : "/api/v1/namespaces/" + ns + "/pods";

        podModel.setRowCount(0);
        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                String resp = executeRequest("GET", path, null, activeSkipTls);
                JsonNode root = mapper.readTree(resp);
                List<Object[]> rows = new ArrayList<>();
                JsonNode items = root.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String namespace = item.path("metadata").path("namespace").asText();
                        String name = item.path("metadata").path("name").asText();
                        String status = item.path("status").path("phase").asText();
                        
                        int restarts = 0;
                        JsonNode statuses = item.path("status").path("containerStatuses");
                        if (statuses.isArray()) {
                            for (JsonNode cs : statuses) {
                                restarts += cs.path("restartCount").asInt();
                            }
                        }
                        
                        String ip = item.path("status").path("podIP").asText("-");
                        String node = item.path("spec").path("nodeName").asText("-");
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());
                        
                        rows.add(new Object[]{namespace, name, status, restarts, ip, node, age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        podModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 Pods 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // Load Deployments
    private void loadDeployments() {
        String ns = getSelectedNamespace();
        String path = ns.equals("all") ? "/apis/apps/v1/deployments" : "/apis/apps/v1/namespaces/" + ns + "/deployments";

        deployModel.setRowCount(0);
        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                String resp = executeRequest("GET", path, null, activeSkipTls);
                JsonNode root = mapper.readTree(resp);
                List<Object[]> rows = new ArrayList<>();
                JsonNode items = root.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String namespace = item.path("metadata").path("namespace").asText();
                        String name = item.path("metadata").path("name").asText();
                        int specReplicas = item.path("spec").path("replicas").asInt(0);
                        int readyReplicas = item.path("status").path("readyReplicas").asInt(0);
                        String ready = readyReplicas + "/" + specReplicas;
                        
                        int updated = item.path("status").path("updatedReplicas").asInt(0);
                        int available = item.path("status").path("availableReplicas").asInt(0);
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());

                        rows.add(new Object[]{namespace, name, ready, updated, available, age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        deployModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 Deployments 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // Load Services
    private void loadServices() {
        String ns = getSelectedNamespace();
        String path = ns.equals("all") ? "/api/v1/services" : "/api/v1/namespaces/" + ns + "/services";

        svcModel.setRowCount(0);
        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                String resp = executeRequest("GET", path, null, activeSkipTls);
                JsonNode root = mapper.readTree(resp);
                List<Object[]> rows = new ArrayList<>();
                JsonNode items = root.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String namespace = item.path("metadata").path("namespace").asText();
                        String name = item.path("metadata").path("name").asText();
                        String type = item.path("spec").path("type").asText();
                        String clusterIp = item.path("spec").path("clusterIP").asText();
                        
                        StringBuilder extIp = new StringBuilder();
                        JsonNode ingresses = item.path("status").path("loadBalancer").path("ingress");
                        if (ingresses.isArray()) {
                            for (JsonNode ing : ingresses) {
                                if (extIp.length() > 0) extIp.append(",");
                                if (ing.has("ip")) extIp.append(ing.path("ip").asText());
                                else if (ing.has("hostname")) extIp.append(ing.path("hostname").asText());
                            }
                        }
                        if (extIp.length() == 0) {
                            extIp.append("<none>");
                        }

                        StringBuilder ports = new StringBuilder();
                        JsonNode pNode = item.path("spec").path("ports");
                        if (pNode.isArray()) {
                            for (JsonNode p : pNode) {
                                if (ports.length() > 0) ports.append(",");
                                ports.append(p.path("port").asInt()).append("/").append(p.path("protocol").asText());
                            }
                        }
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());

                        rows.add(new Object[]{namespace, name, type, clusterIp, extIp.toString(), ports.toString(), age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        svcModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 Services 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // Load ConfigMaps
    private void loadConfigMaps() {
        String ns = getSelectedNamespace();
        String path = ns.equals("all") ? "/api/v1/configmaps" : "/api/v1/namespaces/" + ns + "/configmaps";

        cmModel.setRowCount(0);
        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                String resp = executeRequest("GET", path, null, activeSkipTls);
                JsonNode root = mapper.readTree(resp);
                List<Object[]> rows = new ArrayList<>();
                JsonNode items = root.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String namespace = item.path("metadata").path("namespace").asText();
                        String name = item.path("metadata").path("name").asText();
                        int keys = item.path("data").size();
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());

                        rows.add(new Object[]{namespace, name, keys, age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        cmModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 ConfigMaps 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // Load Nodes
    private void loadNodes() {
        nodeModel.setRowCount(0);
        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                String resp = executeRequest("GET", "/api/v1/nodes", null, activeSkipTls);
                JsonNode root = mapper.readTree(resp);
                List<Object[]> rows = new ArrayList<>();
                JsonNode items = root.path("items");
                if (items.isArray()) {
                    for (JsonNode item : items) {
                        String name = item.path("metadata").path("name").asText();
                        
                        String status = "NotReady";
                        JsonNode conditions = item.path("status").path("conditions");
                        if (conditions.isArray()) {
                            for (JsonNode cond : conditions) {
                                if ("Ready".equals(cond.path("type").asText())) {
                                    if ("True".equals(cond.path("status").asText())) {
                                        status = "Ready";
                                    }
                                    break;
                                }
                            }
                        }

                        StringBuilder roles = new StringBuilder();
                        JsonNode labels = item.path("metadata").path("labels");
                        if (labels.isObject()) {
                            Iterator<Map.Entry<String, JsonNode>> fields = labels.fields();
                            while (fields.hasNext()) {
                                Map.Entry<String, JsonNode> entry = fields.next();
                                if (entry.getKey().startsWith("node-role.kubernetes.io/")) {
                                    if (roles.length() > 0) roles.append(",");
                                    roles.append(entry.getKey().substring("node-role.kubernetes.io/".length()));
                                }
                            }
                        }
                        if (roles.length() == 0) {
                            roles.append("<none>");
                        }

                        String version = item.path("status").path("nodeInfo").path("kubeletVersion").asText();
                        String os = item.path("status").path("nodeInfo").path("osImage").asText();
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());

                        rows.add(new Object[]{name, status, roles.toString(), version, os, age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        nodeModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 Nodes 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void viewResourceYaml(String resourceType, JTable table) {
        int row = table.getSelectedRow();
        if (row == -1) {
            UIUtils.info(null, "请先选择需要查看的一行。");
            return;
        }
        
        String name;
        String ns;
        if (resourceType.equals("nodes")) {
            name = table.getValueAt(row, 0).toString();
            ns = "all";
        } else {
            ns = table.getValueAt(row, 0).toString();
            name = table.getValueAt(row, 1).toString();
        }

        String path = "";
        if (resourceType.equals("deployments")) {
            path = "/apis/apps/v1/namespaces/" + ns + "/deployments/" + name;
        } else if (resourceType.equals("nodes")) {
            path = "/api/v1/nodes/" + name;
        } else {
            // pods, services, configmaps
            path = "/api/v1/namespaces/" + ns + "/" + resourceType + "/" + name;
        }

        final String reqPath = path;
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() throws Exception {
                String resp = executeRequest("GET", reqPath, null, activeSkipTls);
                String yaml = convertJsonToYaml(resp);
                JsonNode jsonNode = mapper.readTree(resp);
                return new Object[]{yaml, jsonNode};
            }

            @Override
            protected void done() {
                try {
                    Object[] res = get();
                    String yaml = (String) res[0];
                    JsonNode jsonNode = (JsonNode) res[1];
                    showYamlDialog(name, yaml, jsonNode);
                } catch (Exception ex) {
                    UIUtils.error(null, "获取 YAML 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void showYamlDialog(String resourceName, String yaml, JsonNode jsonNode) {
        JDialog dialog = new JDialog((Frame) null, "查看: " + resourceName, true);
        dialog.setSize(750, 580);
        dialog.setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();

        // Tab 1: 折叠树视图
        JTree tree = new JTree(new javax.swing.tree.DefaultMutableTreeNode("Resource"));
        tree.setFont(UIUtils.monoFont());
        tree.putClientProperty("JTree.lineStyle", "None");
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setRowHeight(20);

        javax.swing.tree.DefaultTreeCellRenderer renderer = new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value, boolean sel,
                                                          boolean expanded, boolean leaf, int r, boolean hasFocus) {
                super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, r, hasFocus);
                if (value instanceof javax.swing.tree.DefaultMutableTreeNode) {
                    Object userObj = ((javax.swing.tree.DefaultMutableTreeNode) value).getUserObject();
                    if (userObj instanceof YamlFolderNode) {
                        YamlFolderNode node = (YamlFolderNode) userObj;
                        if (expanded) {
                            setText(node.openText);
                        } else {
                            setText(node.closeText);
                        }
                    }
                }
                if (sel) {
                    setBackground(UIManager.getColor("List.selectionBackground"));
                    setForeground(UIManager.getColor("List.selectionForeground"));
                } else {
                    setBackground(null);
                    setForeground(null);
                }
                return this;
            }
        };
        renderer.setOpenIcon(null);
        renderer.setClosedIcon(null);
        renderer.setLeafIcon(null);
        tree.setCellRenderer(renderer);

        if (jsonNode != null) {
            javax.swing.tree.DefaultMutableTreeNode rootTreeNode = convertJsonNodeToTreeNode(jsonNode, resourceName, 0, true);
            tree.setModel(new javax.swing.tree.DefaultTreeModel(rootTreeNode));
            // 默认展开前几层
            for (int i = 0; i < Math.min(tree.getRowCount(), 25); i++) {
                tree.expandRow(i);
            }
        }

        JScrollPane treeScroll = new JScrollPane(tree);
        tabs.addTab("折叠树视图 (Collapsible Tree)", treeScroll);

        // Tab 2: 原始 YAML 文本
        JTextArea area = new JTextArea(yaml);
        area.setEditable(false);
        tabs.addTab("YAML 文本 (Raw YAML)", UIUtils.scrollText(area, "YAML / JSON 内容"));

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton copyBtn = new JButton("复制 YAML");
        copyBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(yaml);
            UIUtils.info(dialog, "已成功复制到剪贴板！");
        });
        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        bottom.add(copyBtn);
        bottom.add(closeBtn);

        dialog.add(tabs, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void viewPodLogs() {
        int row = podTable.getSelectedRow();
        if (row == -1) {
            UIUtils.info(null, "请先选择需要查看日志的 Pod");
            return;
        }
        String ns = podTable.getValueAt(row, 0).toString();
        String name = podTable.getValueAt(row, 1).toString();

        // We need to retrieve container names for this pod
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                String path = "/api/v1/namespaces/" + ns + "/pods/" + name;
                String resp = executeRequest("GET", path, null, activeSkipTls);
                JsonNode root = mapper.readTree(resp);
                List<String> list = new ArrayList<>();
                JsonNode specs = root.path("spec").path("containers");
                if (specs.isArray()) {
                    for (JsonNode c : specs) {
                        list.add(c.path("name").asText());
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                try {
                    List<String> containers = get();
                    if (containers.isEmpty()) {
                        UIUtils.error(null, "找不到容器配置！");
                        return;
                    }
                    showLogDialog(ns, name, containers);
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 Pod 详情失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void showLogDialog(String ns, String podName, List<String> containers) {
        JDialog dialog = new JDialog((Frame) null, "Pod 日志: " + podName, false);
        dialog.setSize(800, 550);
        dialog.setLocationRelativeTo(null);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JComboBox<String> containerCombo = new JComboBox<>();
        for (String c : containers) {
            containerCombo.addItem(c);
        }
        top.add(new JLabel("选择容器 (Container):"));
        top.add(containerCombo);

        JCheckBox followCheck = new JCheckBox("追踪更新 (Follow)", false);
        top.add(followCheck);

        JButton loadMoreBtn = new JButton("加载前500行");
        loadMoreBtn.setEnabled(false);
        top.add(loadMoreBtn);

        JTextArea area = new JTextArea();
        area.setEditable(false);
        JScrollPane sp = UIUtils.scrollText(area, "日志输出");

        final int[] currentTailLines = {1000};
        final HttpURLConnection[] activeConn = new HttpURLConnection[1];
        final Thread[] activeThread = new Thread[1];

        JScrollBar verticalBar = sp.getVerticalScrollBar();
        verticalBar.addAdjustmentListener(e -> {
            boolean atTop = (verticalBar.getValue() == 0 && area.getDocument().getLength() > 0);
            loadMoreBtn.setEnabled(atTop && !followCheck.isSelected());
        });

        Runnable stopFollowing = () -> {
            final Thread t = activeThread[0];
            final HttpURLConnection conn = activeConn[0];
            activeThread[0] = null;
            activeConn[0] = null;

            if (t != null || conn != null) {
                new Thread(() -> {
                    if (t != null) {
                        t.interrupt();
                    }
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ex) {}
                    }
                }).start();
            }
        };

        Runnable startFollowing = () -> {
            String c = (String) containerCombo.getSelectedItem();
            if (c == null) return;
            area.setText("正在开启追踪日志...\n");
            Thread t = new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    String path = "/api/v1/namespaces/" + ns + "/pods/" + podName + "/log?container=" + c + "&follow=true&tailLines=200";
                    URL url = new URL(activeServerUrl.replaceAll("/+$", "") + path);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(0); // Infinite read timeout
                    conn.setRequestMethod("GET");
                    if (activeToken != null && !activeToken.trim().isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + activeToken);
                    }
                    conn.setRequestProperty("Accept", "application/json");

                    if (conn instanceof HttpsURLConnection && activeSkipTls) {
                        HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                        httpsConn.setSSLSocketFactory(getTrustAllSocketFactory());
                        httpsConn.setHostnameVerifier((h, s) -> true);
                    }

                    activeConn[0] = conn;
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        SwingUtilities.invokeLater(() -> area.setText(""));
                        try (InputStream is = conn.getInputStream();
                             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                                final String finalLine = line;
                                SwingUtilities.invokeLater(() -> {
                                    area.append(finalLine + "\n");
                                    area.setCaretPosition(area.getDocument().getLength());
                                });
                            }
                        }
                    } else {
                        try (InputStream es = conn.getErrorStream();
                             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                            String err = "";
                            if (es != null) {
                                byte[] buf = new byte[4096];
                                int len;
                                while ((len = es.read(buf)) != -1) {
                                    bos.write(buf, 0, len);
                                }
                                err = bos.toString("UTF-8");
                            }
                            final String errMsg = "HTTP " + code + (err.isEmpty() ? "" : ": " + err);
                            SwingUtilities.invokeLater(() -> area.setText("无法追踪日志: " + errMsg));
                        }
                    }
                } catch (Exception ex) {
                    if (!Thread.currentThread().isInterrupted()) {
                        SwingUtilities.invokeLater(() -> area.append("\n[追踪日志断开]: " + ex.getMessage() + "\n"));
                    }
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            });
            activeThread[0] = t;
            t.setDaemon(true);
            t.start();
        };

        Runnable logFetcher = () -> {
            String c = (String) containerCombo.getSelectedItem();
            if (c == null) return;
            
            stopFollowing.run();
            if (followCheck.isSelected()) {
                startFollowing.run();
            } else {
                area.setText("正在加载日志，请稍候...");
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() throws Exception {
                        String path = "/api/v1/namespaces/" + ns + "/pods/" + podName + "/log?container=" + c + "&tailLines=" + currentTailLines[0];
                        return executeRequest("GET", path, null, activeSkipTls);
                    }

                    @Override
                    protected void done() {
                        try {
                            area.setText(get());
                        } catch (Exception ex) {
                            area.setText("加载日志失败: " + ex.getMessage());
                        }
                    }
                }.execute();
            }
        };

        loadMoreBtn.addActionListener(e -> {
            String c = (String) containerCombo.getSelectedItem();
            if (c == null) return;

            loadMoreBtn.setEnabled(false);
            currentTailLines[0] += 500;
            area.insert("正在加载历史日志...\n", 0);

            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    String path = "/api/v1/namespaces/" + ns + "/pods/" + podName + "/log?container=" + c + "&tailLines=" + currentTailLines[0];
                    return executeRequest("GET", path, null, activeSkipTls);
                }

                @Override
                protected void done() {
                    try {
                        String logs = get();
                        int oldLineCount = area.getLineCount();
                        area.setText(logs);
                        int newLineCount = area.getLineCount();
                        int addedLines = newLineCount - oldLineCount;
                        if (addedLines > 0) {
                            try {
                                int offset = area.getLineStartOffset(addedLines);
                                area.setCaretPosition(offset);
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ex) {
                        UIUtils.error(dialog, "加载更多日志失败: " + ex.getMessage());
                    }
                }
            }.execute();
        });

        containerCombo.addActionListener(e -> {
            currentTailLines[0] = 1000;
            logFetcher.run();
        });
        followCheck.addActionListener(e -> {
            currentTailLines[0] = 1000;
            logFetcher.run();
        });

        JButton refreshBtn = new JButton("刷新日志");
        refreshBtn.addActionListener(e -> {
            currentTailLines[0] = 1000;
            logFetcher.run();
        });
        top.add(refreshBtn);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton copyBtn = new JButton("复制日志");
        copyBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(area.getText());
            UIUtils.info(dialog, "日志已复制！");
        });
        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        bottom.add(copyBtn);
        bottom.add(closeBtn);

        dialog.add(top, BorderLayout.NORTH);
        dialog.add(sp, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);

        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopFollowing.run();
            }
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                stopFollowing.run();
            }
        });

        // Fetch logs initially
        logFetcher.run();

        dialog.setVisible(true);
    }

    private void scaleDeployment() {
        int row = deployTable.getSelectedRow();
        if (row == -1) {
            UIUtils.info(null, "请先选择需要修改副本数的 Deployment");
            return;
        }
        String ns = deployTable.getValueAt(row, 0).toString();
        String name = deployTable.getValueAt(row, 1).toString();

        String input = UIUtils.input(null, "请输入目标 Replicas 副本数：", "2");
        if (input == null || input.trim().isEmpty()) return;
        
        int replicas = 0;
        try {
            replicas = Integer.parseInt(input.trim());
        } catch (NumberFormatException ex) {
            UIUtils.error(null, "请输入正确的整数！");
            return;
        }

        final int targetReplicas = replicas;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                // We use standard PUT on the scale subresource
                String path = "/apis/apps/v1/namespaces/" + ns + "/deployments/" + name + "/scale";
                String scaleJson = String.format("{\"metadata\":{\"name\":\"%s\",\"namespace\":\"%s\"},\"spec\":{\"replicas\":%d}}", name, ns, targetReplicas);
                executeRequest("PUT", path, scaleJson, activeSkipTls);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    UIUtils.info(null, "副本数已成功更新为 " + targetReplicas);
                    loadDeployments();
                } catch (Exception ex) {
                    UIUtils.error(null, "更新副本数失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void deleteResource(String resourceType, JTable table, Runnable onFinished) {
        int row = table.getSelectedRow();
        if (row == -1) {
            UIUtils.info(null, "请选择要删除的资源");
            return;
        }
        String ns = table.getValueAt(row, 0).toString();
        String name = table.getValueAt(row, 1).toString();

        int opt = JOptionPane.showConfirmDialog(null, "确认要从集群中删除 " + resourceType + " \"" + name + "\"? 此操作无法撤销！", "安全警告", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (opt == JOptionPane.YES_OPTION) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    String path = "";
                    if (resourceType.equals("deployments")) {
                        path = "/apis/apps/v1/namespaces/" + ns + "/deployments/" + name;
                    } else {
                        path = "/api/v1/namespaces/" + ns + "/" + resourceType + "/" + name;
                    }
                    executeRequest("DELETE", path, null, activeSkipTls);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        UIUtils.info(null, "已成功发送删除请求！");
                        onFinished.run();
                    } catch (Exception ex) {
                        UIUtils.error(null, "删除失败: " + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    private void importKubeconfig() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 Kubeconfig 配置文件");
        
        // Default to ~/.kube
        String userHome = System.getProperty("user.home");
        if (userHome != null) {
            File kubeDir = new File(userHome, ".kube");
            if (kubeDir.exists()) {
                chooser.setCurrentDirectory(kubeDir);
            }
        }

        int ret = chooser.showOpenDialog(null);
        if (ret == JFileChooser.APPROVE_OPTION) {
            File selectedFile = chooser.getSelectedFile();
            new SwingWorker<K8sProfile, Void>() {
                @Override
                protected K8sProfile doInBackground() throws Exception {
                    ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
                    JsonNode root = yamlMapper.readTree(selectedFile);
                    
                    // 1. Get current-context
                    String currentContext = root.path("current-context").asText();
                    
                    // 2. Locate clusters and users
                    String serverUrl = "https://127.0.0.1:6443";
                    String token = "";
                    
                    // If currentContext is empty, pick the first one
                    JsonNode contexts = root.path("contexts");
                    String clusterName = "";
                    String userName = "";
                    
                    if (contexts.isArray() && contexts.size() > 0) {
                        for (JsonNode ctx : contexts) {
                            String cName = ctx.path("name").asText();
                            if (cName.equals(currentContext) || clusterName.isEmpty()) {
                                clusterName = ctx.path("context").path("cluster").asText();
                                userName = ctx.path("context").path("user").asText();
                            }
                        }
                    }

                    // Get cluster server
                    JsonNode clusters = root.path("clusters");
                    if (clusters.isArray()) {
                        for (JsonNode c : clusters) {
                            if (c.path("name").asText().equals(clusterName) || clusters.size() == 1) {
                                serverUrl = c.path("cluster").path("server").asText();
                                break;
                            }
                        }
                    }

                    // Get user token
                    JsonNode users = root.path("users");
                    if (users.isArray()) {
                        for (JsonNode u : users) {
                            if (u.path("name").asText().equals(userName) || users.size() == 1) {
                                token = u.path("user").path("token").asText();
                                break;
                            }
                        }
                    }

                    if (serverUrl.isEmpty()) {
                        throw new Exception("在 Kubeconfig 中无法解析出 API Server 地址。");
                    }

                    return new K8sProfile("Kubeconfig_" + selectedFile.getName(), serverUrl, token, true);
                }

                @Override
                protected void done() {
                    try {
                        K8sProfile p = get();
                        serverField.setText(p.serverUrl);
                        tokenField.setText(p.token);
                        skipTlsCheck.setSelected(p.skipTls);
                        UIUtils.info(null, "解析 Kubeconfig 成功！您可以点击“保存配置”以存入已存列表。");
                    } catch (Exception ex) {
                        UIUtils.error(null, "解析 Kubeconfig 失败: " + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    private void saveProfilesToPrefs() {
        try {
            String json = mapper.writeValueAsString(profiles);
            prefs.put("k8s_manager_profiles", json);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void loadProfilesFromPrefs() {
        try {
            String json = prefs.get("k8s_manager_profiles", null);
            if (json != null && !json.trim().isEmpty()) {
                Map<String, K8sProfile> loaded = mapper.readValue(json, new TypeReference<LinkedHashMap<String, K8sProfile>>(){});
                profiles.clear();
                profiles.putAll(loaded);
            }
            refreshProfilesCombo(null);
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void refreshProfilesCombo(String selectName) {
        ignoreProfileEvents = true;
        profileCombo.removeAllItems();
        for (String name : profiles.keySet()) {
            profileCombo.addItem(name);
        }
        if (selectName != null) {
            profileCombo.setSelectedItem(selectName);
        } else if (profileCombo.getItemCount() > 0) {
            profileCombo.setSelectedIndex(0);
            String first = profileCombo.getItemAt(0);
            K8sProfile p = profiles.get(first);
            serverField.setText(p.serverUrl);
            tokenField.setText(p.token);
            skipTlsCheck.setSelected(p.skipTls);
        }
        ignoreProfileEvents = false;
    }

    private String convertJsonToYaml(String json) {
        try {
            ObjectMapper jsonMapper = new ObjectMapper();
            Object obj = jsonMapper.readValue(json, Object.class);
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            return yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (Exception e) {
            return json;
        }
    }

    private static javax.net.ssl.SSLSocketFactory trustAllSocketFactory;
    private static synchronized javax.net.ssl.SSLSocketFactory getTrustAllSocketFactory() throws Exception {
        if (trustAllSocketFactory == null) {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            trustAllSocketFactory = sc.getSocketFactory();
        }
        return trustAllSocketFactory;
    }

    private String executeRequest(String method, String apiPath, String body, boolean skipTls) throws Exception {
        URL url = new URL(activeServerUrl.replaceAll("/+$", "") + apiPath);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod(method);
        if (activeToken != null && !activeToken.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + activeToken);
        }
        conn.setRequestProperty("Accept", "application/json");

        if (body != null) {
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (conn instanceof HttpsURLConnection && skipTls) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            httpsConn.setSSLSocketFactory(getTrustAllSocketFactory());
            httpsConn.setHostnameVerifier((h, s) -> true);
        }

        int code = conn.getResponseCode();
        if (code >= 200 && code < 300) {
            try (InputStream is = conn.getInputStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = is.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                return bos.toString("UTF-8");
            }
        } else {
            try (InputStream es = conn.getErrorStream();
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                if (es != null) {
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = es.read(buf)) != -1) {
                        bos.write(buf, 0, len);
                    }
                    throw new Exception("HTTP " + code + ": " + bos.toString("UTF-8"));
                }
                throw new Exception("HTTP " + code);
            }
        }
    }

    public static class K8sProfile {
        public String name;
        public String serverUrl;
        public String token;
        public boolean skipTls;

        public K8sProfile() {}

        public K8sProfile(String name, String serverUrl, String token, boolean skipTls) {
            this.name = name;
            this.serverUrl = serverUrl;
            this.token = token;
            this.skipTls = skipTls;
        }
    }

    static class YamlFolderNode {
        String openText;
        String closeText;

        YamlFolderNode(String openText, String closeText) {
            this.openText = openText;
            this.closeText = closeText;
        }

        @Override
        public String toString() {
            return openText;
        }
    }

    private static final String[] BRACKET_COLORS = {
            "#C768DB", "#2D9CDB", "#F2C94C", "#6FCF97"
    };

    private javax.swing.tree.DefaultMutableTreeNode convertJsonNodeToTreeNode(JsonNode node, String keyName, int depth, boolean isLast) {
        String keyHtml = keyName.isEmpty() ? "" : "<span style='color:#e06c75'>\"" + keyName + "\"</span>: ";
        String comma = isLast ? "" : "<span style='color:#abb2bf'>,</span>";
        String color = BRACKET_COLORS[depth % BRACKET_COLORS.length];

        if (node.isObject()) {
            String open = "<html>" + keyHtml + "<span style='color:" + color + "'><b>{</b></span></html>";
            String close = "<html>" + keyHtml + "<span style='color:" + color + "'><b>{ ... }</b></span>" + comma + "</html>";
            
            javax.swing.tree.DefaultMutableTreeNode container = new javax.swing.tree.DefaultMutableTreeNode(new YamlFolderNode(open, close));
            
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = node.fields();
            java.util.List<java.util.Map.Entry<String, JsonNode>> list = new java.util.ArrayList<>();
            while (fields.hasNext()) {
                list.add(fields.next());
            }
            
            for (int i = 0; i < list.size(); i++) {
                java.util.Map.Entry<String, JsonNode> field = list.get(i);
                boolean lastField = (i == list.size() - 1);
                container.add(convertJsonNodeToTreeNode(field.getValue(), field.getKey(), depth + 1, lastField));
            }
            
            String endText = "<html><span style='color:" + color + "'><b>}</b></span>" + comma + "</html>";
            container.add(new javax.swing.tree.DefaultMutableTreeNode(new YamlFolderNode(endText, endText)));
            return container;
            
        } else if (node.isArray()) {
            String open = "<html>" + keyHtml + "<span style='color:" + color + "'><b>[</b></span></html>";
            String close = "<html>" + keyHtml + "<span style='color:" + color + "'><b>[ ... ]</b></span>" + comma + "</html>";
            
            javax.swing.tree.DefaultMutableTreeNode container = new javax.swing.tree.DefaultMutableTreeNode(new YamlFolderNode(open, close));
            
            for (int i = 0; i < node.size(); i++) {
                boolean lastField = (i == node.size() - 1);
                container.add(convertJsonNodeToTreeNode(node.get(i), "", depth + 1, lastField));
            }
            
            String endText = "<html><span style='color:" + color + "'><b>]</b></span>" + comma + "</html>";
            container.add(new javax.swing.tree.DefaultMutableTreeNode(new YamlFolderNode(endText, endText)));
            return container;
        } else {
            String valHtml = "";
            if (node.isTextual()) {
                valHtml = "<span style='color:#98c311'>\"" + escapeHtmlForTree(node.asText()) + "\"</span>";
            } else if (node.isNumber()) {
                valHtml = "<span style='color:#d19a66'>" + node.toString() + "</span>";
            } else if (node.isBoolean()) {
                valHtml = "<span style='color:#d19a66'><b>" + node.toString() + "</b></span>";
            } else {
                valHtml = "<span style='color:#abb2bf'>null</span>";
            }
            
            String text = "<html>" + keyHtml + valHtml + comma + "</html>";
            return new javax.swing.tree.DefaultMutableTreeNode(new YamlFolderNode(text, text));
        }
    }

    private String escapeHtmlForTree(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void showApplyYamlDialog() {
        JDialog dialog = new JDialog((Frame) null, "发布 K8s 资源 (Apply YAML)", true);
        dialog.setSize(680, 520);
        dialog.setLocationRelativeTo(null);

        JTextArea area = new JTextArea();
        area.setFont(UIUtils.monoFont());
        JScrollPane sp = UIUtils.scrollText(area, "请在此处粘贴 YAML 配置文件内容");

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton deployBtn = new JButton("确认发布");
        deployBtn.addActionListener(e -> {
            String yaml = area.getText().trim();
            if (yaml.isEmpty()) {
                UIUtils.info(dialog, "内容不能为空！");
                return;
            }
            deployBtn.setEnabled(false);
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    applyResourceYaml(yaml);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        UIUtils.info(dialog, "发布成功！已在集群中应用该资源。");
                        dialog.dispose();
                        // 刷新当前列表
                        loadPods();
                        loadDeployments();
                        loadServices();
                        loadConfigMaps();
                    } catch (Exception ex) {
                        deployBtn.setEnabled(true);
                        UIUtils.error(dialog, "发布失败: " + ex.getMessage());
                    }
                }
            }.execute();
        });

        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());

        bottom.add(deployBtn);
        bottom.add(closeBtn);

        dialog.add(sp, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private void applyResourceYaml(String yamlText) throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        JsonNode node = yamlMapper.readTree(yamlText);
        String kind = node.path("kind").asText();
        String name = node.path("metadata").path("name").asText();
        if (name.isEmpty() || kind.isEmpty()) {
            throw new Exception("YAML 格式错误：未找到 kind 或 metadata.name");
        }
        
        String namespace = node.path("metadata").path("namespace").asText();
        if (namespace.isEmpty()) {
            String selNs = getSelectedNamespace();
            namespace = selNs.equals("all") ? "default" : selNs;
        }

        String plural = "";
        String groupPrefix = "";
        boolean isNamespaced = true;

        switch (kind) {
            case "Pod":
                plural = "pods"; groupPrefix = "/api/v1"; break;
            case "Service":
                plural = "services"; groupPrefix = "/api/v1"; break;
            case "ConfigMap":
                plural = "configmaps"; groupPrefix = "/api/v1"; break;
            case "Secret":
                plural = "secrets"; groupPrefix = "/api/v1"; break;
            case "Namespace":
                plural = "namespaces"; groupPrefix = "/api/v1"; isNamespaced = false; break;
            case "Node":
                plural = "nodes"; groupPrefix = "/api/v1"; isNamespaced = false; break;
            case "Deployment":
                plural = "deployments"; groupPrefix = "/apis/apps/v1"; break;
            case "StatefulSet":
                plural = "statefulsets"; groupPrefix = "/apis/apps/v1"; break;
            case "DaemonSet":
                plural = "daemonsets"; groupPrefix = "/apis/apps/v1"; break;
            case "Ingress":
                plural = "ingresses"; groupPrefix = "/apis/networking.k8s.io/v1"; break;
            default:
                String apiVersion = node.path("apiVersion").asText();
                if (apiVersion.contains("/")) {
                    groupPrefix = "/apis/" + apiVersion;
                } else {
                    groupPrefix = "/api/" + apiVersion;
                }
                plural = kind.toLowerCase() + "s";
                break;
        }

        ObjectMapper jsonMapper = new ObjectMapper();
        String jsonBody = jsonMapper.writeValueAsString(node);

        String collectionPath = isNamespaced 
            ? groupPrefix + "/namespaces/" + namespace + "/" + plural
            : groupPrefix + "/" + plural;
            
        String resourcePath = collectionPath + "/" + name;

        boolean exists = false;
        try {
            executeRequest("GET", resourcePath, null, activeSkipTls);
            exists = true;
        } catch (Exception ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("HTTP 404")) {
                exists = false;
            } else {
                throw ex;
            }
        }

        if (exists) {
            String existingJson = executeRequest("GET", resourcePath, null, activeSkipTls);
            JsonNode existingNode = mapper.readTree(existingJson);
            String resourceVersion = existingNode.path("metadata").path("resourceVersion").asText();
            
            ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("metadata")).put("resourceVersion", resourceVersion);
            String putBody = jsonMapper.writeValueAsString(node);
            
            executeRequest("PUT", resourcePath, putBody, activeSkipTls);
        } else {
            executeRequest("POST", collectionPath, jsonBody, activeSkipTls);
        }
    }
}
