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
    private javax.swing.table.TableRowSorter<DefaultTableModel> statefulSetSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> daemonSetSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> cronJobSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> svcSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> cmSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> secretSorter;
    private javax.swing.table.TableRowSorter<DefaultTableModel> nodeSorter;

    private JTabbedPane resourceTabs;
    
    // Tables and Models
    private JTable podTable;
    private DefaultTableModel podModel;
    private JTable deployTable;
    private DefaultTableModel deployModel;
    private JTable statefulSetTable;
    private DefaultTableModel statefulSetModel;
    private JTable daemonSetTable;
    private DefaultTableModel daemonSetModel;
    private JTable cronJobTable;
    private DefaultTableModel cronJobModel;
    private JTable svcTable;
    private DefaultTableModel svcModel;
    private JTable cmTable;
    private DefaultTableModel cmModel;
    private JTable secretTable;
    private DefaultTableModel secretModel;
    private JTable nodeTable;
    private DefaultTableModel nodeModel;

    // State
    private boolean isConnected = false;
    private String activeServerUrl = "";
    private String activeToken = "";
    private boolean activeSkipTls = true;
    private String activeClientCert = null;
    private String activeClientKey = null;
    private javax.net.ssl.SSLSocketFactory activeSocketFactory = null;
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
                if (statefulSetSorter != null) statefulSetSorter.setRowFilter(filter);
                if (daemonSetSorter != null) daemonSetSorter.setRowFilter(filter);
                if (cronJobSorter != null) cronJobSorter.setRowFilter(filter);
                if (svcSorter != null) svcSorter.setRowFilter(filter);
                if (cmSorter != null) cmSorter.setRowFilter(filter);
                if (secretSorter != null) secretSorter.setRowFilter(filter);
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
        JButton execPodBtn = new JButton("控制台 (Exec)");
        JButton delPodBtn = new JButton("删除 Pod");
        podBtnRow.add(refreshPodBtn);
        podBtnRow.add(yamlPodBtn);
        podBtnRow.add(logPodBtn);
        podBtnRow.add(execPodBtn);
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

        // Tab C: StatefulSets
        JPanel statefulSetTab = new JPanel(new BorderLayout(6, 6));
        statefulSetModel = new DefaultTableModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "就绪状态 (Ready)", "当前副本 (Current)", "存活时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        statefulSetTable = new JTable(statefulSetModel);
        statefulSetTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        statefulSetSorter = new javax.swing.table.TableRowSorter<>(statefulSetModel);
        statefulSetTable.setRowSorter(statefulSetSorter);
        statefulSetTab.add(new JScrollPane(statefulSetTable), BorderLayout.CENTER);
        JPanel statefulSetBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshStatefulSetBtn = new JButton("刷新");
        JButton yamlStatefulSetBtn = new JButton("查看 YAML");
        JButton scaleStatefulSetBtn = new JButton("修改副本数 (Scale)");
        JButton delStatefulSetBtn = new JButton("删除 StatefulSet");
        statefulSetBtnRow.add(refreshStatefulSetBtn);
        statefulSetBtnRow.add(yamlStatefulSetBtn);
        statefulSetBtnRow.add(scaleStatefulSetBtn);
        statefulSetBtnRow.add(delStatefulSetBtn);
        statefulSetTab.add(statefulSetBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("StatefulSets", statefulSetTab);

        // Tab D: DaemonSets
        JPanel daemonSetTab = new JPanel(new BorderLayout(6, 6));
        daemonSetModel = new DefaultTableModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "期望副本 (Desired)", "当前副本 (Current)", "就绪副本 (Ready)", "最新副本 (Up-to-date)", "存活时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        daemonSetTable = new JTable(daemonSetModel);
        daemonSetTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        daemonSetSorter = new javax.swing.table.TableRowSorter<>(daemonSetModel);
        daemonSetTable.setRowSorter(daemonSetSorter);
        daemonSetTab.add(new JScrollPane(daemonSetTable), BorderLayout.CENTER);
        JPanel daemonSetBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshDaemonSetBtn = new JButton("刷新");
        JButton yamlDaemonSetBtn = new JButton("查看 YAML");
        JButton delDaemonSetBtn = new JButton("删除 DaemonSet");
        daemonSetBtnRow.add(refreshDaemonSetBtn);
        daemonSetBtnRow.add(yamlDaemonSetBtn);
        daemonSetBtnRow.add(delDaemonSetBtn);
        daemonSetTab.add(daemonSetBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("DaemonSets", daemonSetTab);

        // Tab E: CronJobs
        JPanel cronJobTab = new JPanel(new BorderLayout(6, 6));
        cronJobModel = new DefaultTableModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "调度计划 (Schedule)", "暂停 (Suspend)", "活跃数 (Active)", "上次执行时间 (Last Schedule)", "存活时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        cronJobTable = new JTable(cronJobModel);
        cronJobTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        cronJobSorter = new javax.swing.table.TableRowSorter<>(cronJobModel);
        cronJobTable.setRowSorter(cronJobSorter);
        cronJobTab.add(new JScrollPane(cronJobTable), BorderLayout.CENTER);
        JPanel cronJobBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshCronJobBtn = new JButton("刷新");
        JButton yamlCronJobBtn = new JButton("查看 YAML");
        JButton delCronJobBtn = new JButton("删除 CronJob");
        cronJobBtnRow.add(refreshCronJobBtn);
        cronJobBtnRow.add(yamlCronJobBtn);
        cronJobBtnRow.add(delCronJobBtn);
        cronJobTab.add(cronJobBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("CronJobs", cronJobTab);

        // Tab F: Services
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

        // Tab G: ConfigMaps
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

        // Tab H: Secrets
        JPanel secretTab = new JPanel(new BorderLayout(6, 6));
        secretModel = new DefaultTableModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "类型 (Type)", "数据键数 (Data)", "存活时间 (Age)"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
        secretTable = new JTable(secretModel);
        secretTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        secretSorter = new javax.swing.table.TableRowSorter<>(secretModel);
        secretTable.setRowSorter(secretSorter);
        secretTab.add(new JScrollPane(secretTable), BorderLayout.CENTER);
        JPanel secretBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton refreshSecretBtn = new JButton("刷新");
        JButton yamlSecretBtn = new JButton("查看 YAML");
        JButton delSecretBtn = new JButton("删除 Secret");
        secretBtnRow.add(refreshSecretBtn);
        secretBtnRow.add(yamlSecretBtn);
        secretBtnRow.add(delSecretBtn);
        secretTab.add(secretBtnRow, BorderLayout.SOUTH);
        resourceTabs.addTab("Secrets", secretTab);

        // Tab I: Nodes
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
        initActions(refreshPodBtn, yamlPodBtn, logPodBtn, delPodBtn, execPodBtn,
                refreshDeployBtn, yamlDeployBtn, scaleDeployBtn, delDeployBtn,
                refreshStatefulSetBtn, yamlStatefulSetBtn, scaleStatefulSetBtn, delStatefulSetBtn,
                refreshDaemonSetBtn, yamlDaemonSetBtn, delDaemonSetBtn,
                refreshCronJobBtn, yamlCronJobBtn, delCronJobBtn,
                refreshSvcBtn, yamlSvcBtn, delSvcBtn,
                refreshCmBtn, yamlCmBtn, delCmBtn,
                refreshSecretBtn, yamlSecretBtn, delSecretBtn,
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
            activeSocketFactory = null;
        }
    }

    private void clearAllTables() {
        podModel.setRowCount(0);
        deployModel.setRowCount(0);
        statefulSetModel.setRowCount(0);
        daemonSetModel.setRowCount(0);
        cronJobModel.setRowCount(0);
        svcModel.setRowCount(0);
        cmModel.setRowCount(0);
        secretModel.setRowCount(0);
        nodeModel.setRowCount(0);
    }

    private void initActions(JButton refreshPodBtn, JButton yamlPodBtn, JButton logPodBtn, JButton delPodBtn, JButton execPodBtn,
                             JButton refreshDeployBtn, JButton yamlDeployBtn, JButton scaleDeployBtn, JButton delDeployBtn,
                             JButton refreshStatefulSetBtn, JButton yamlStatefulSetBtn, JButton scaleStatefulSetBtn, JButton delStatefulSetBtn,
                             JButton refreshDaemonSetBtn, JButton yamlDaemonSetBtn, JButton delDaemonSetBtn,
                             JButton refreshCronJobBtn, JButton yamlCronJobBtn, JButton delCronJobBtn,
                             JButton refreshSvcBtn, JButton yamlSvcBtn, JButton delSvcBtn,
                             JButton refreshCmBtn, JButton yamlCmBtn, JButton delCmBtn,
                             JButton refreshSecretBtn, JButton yamlSecretBtn, JButton delSecretBtn,
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
                activeClientCert = p.clientCertData;
                activeClientKey = p.clientKeyData;
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
                    skipTlsCheck.isSelected(),
                    activeClientCert,
                    activeClientKey
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
        execPodBtn.addActionListener(e -> execPod());
        delPodBtn.addActionListener(e -> deleteResource("pods", podTable, () -> loadPods()));

        // Deployment Buttons
        refreshDeployBtn.addActionListener(e -> loadDeployments());
        yamlDeployBtn.addActionListener(e -> viewResourceYaml("deployments", deployTable));
        scaleDeployBtn.addActionListener(e -> scaleDeployment("deployments", deployTable));
        delDeployBtn.addActionListener(e -> deleteResource("deployments", deployTable, () -> loadDeployments()));

        // StatefulSet Buttons
        refreshStatefulSetBtn.addActionListener(e -> loadStatefulSets());
        yamlStatefulSetBtn.addActionListener(e -> viewResourceYaml("statefulsets", statefulSetTable));
        scaleStatefulSetBtn.addActionListener(e -> scaleDeployment("statefulsets", statefulSetTable));
        delStatefulSetBtn.addActionListener(e -> deleteResource("statefulsets", statefulSetTable, () -> loadStatefulSets()));

        // DaemonSet Buttons
        refreshDaemonSetBtn.addActionListener(e -> loadDaemonSets());
        yamlDaemonSetBtn.addActionListener(e -> viewResourceYaml("daemonsets", daemonSetTable));
        delDaemonSetBtn.addActionListener(e -> deleteResource("daemonsets", daemonSetTable, () -> loadDaemonSets()));

        // CronJob Buttons
        refreshCronJobBtn.addActionListener(e -> loadCronJobs());
        yamlCronJobBtn.addActionListener(e -> viewResourceYaml("cronjobs", cronJobTable));
        delCronJobBtn.addActionListener(e -> deleteResource("cronjobs", cronJobTable, () -> loadCronJobs()));

        // Service Buttons
        refreshSvcBtn.addActionListener(e -> loadServices());
        yamlSvcBtn.addActionListener(e -> viewResourceYaml("services", svcTable));
        delSvcBtn.addActionListener(e -> deleteResource("services", svcTable, () -> loadServices()));

        // ConfigMap Buttons
        refreshCmBtn.addActionListener(e -> loadConfigMaps());
        yamlCmBtn.addActionListener(e -> viewResourceYaml("configmaps", cmTable));
        delCmBtn.addActionListener(e -> deleteResource("configmaps", cmTable, () -> loadConfigMaps()));

        // Secret Buttons
        refreshSecretBtn.addActionListener(e -> loadSecrets());
        yamlSecretBtn.addActionListener(e -> viewResourceYaml("secrets", secretTable));
        delSecretBtn.addActionListener(e -> deleteResource("secrets", secretTable, () -> loadSecrets()));

        // Node Buttons
        refreshNodeBtn.addActionListener(e -> loadNodes());
        yamlNodeBtn.addActionListener(e -> viewResourceYaml("nodes", nodeTable));
    }

    private void refreshActiveTab() {
        int idx = resourceTabs.getSelectedIndex();
        switch (idx) {
            case 0: loadPods(); break;
            case 1: loadDeployments(); break;
            case 2: loadStatefulSets(); break;
            case 3: loadDaemonSets(); break;
            case 4: loadCronJobs(); break;
            case 5: loadServices(); break;
            case 6: loadConfigMaps(); break;
            case 7: loadSecrets(); break;
            case 8: loadNodes(); break;
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
                activeSocketFactory = buildSSLSocketFactory(activeSkipTls, activeClientCert, activeClientKey);
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
                    activeSocketFactory = null;
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

    // Load StatefulSets
    private void loadStatefulSets() {
        String ns = getSelectedNamespace();
        String path = ns.equals("all") ? "/apis/apps/v1/statefulsets" : "/apis/apps/v1/namespaces/" + ns + "/statefulsets";

        statefulSetModel.setRowCount(0);
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
                        int currentReplicas = item.path("status").path("currentReplicas").asInt(0);
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());

                        rows.add(new Object[]{namespace, name, ready, currentReplicas, age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        statefulSetModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 StatefulSets 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // Load DaemonSets
    private void loadDaemonSets() {
        String ns = getSelectedNamespace();
        String path = ns.equals("all") ? "/apis/apps/v1/daemonsets" : "/apis/apps/v1/namespaces/" + ns + "/daemonsets";

        daemonSetModel.setRowCount(0);
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
                        int desired = item.path("status").path("desiredNumberScheduled").asInt(0);
                        int current = item.path("status").path("currentNumberScheduled").asInt(0);
                        int ready = item.path("status").path("numberReady").asInt(0);
                        int updated = item.path("status").path("updatedNumberScheduled").asInt(0);
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());

                        rows.add(new Object[]{namespace, name, desired, current, ready, updated, age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        daemonSetModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 DaemonSets 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // Load CronJobs
    private void loadCronJobs() {
        String ns = getSelectedNamespace();
        String path = ns.equals("all") ? "/apis/batch/v1/cronjobs" : "/apis/batch/v1/namespaces/" + ns + "/cronjobs";

        cronJobModel.setRowCount(0);
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
                        String schedule = item.path("spec").path("schedule").asText("-");
                        boolean suspend = item.path("spec").path("suspend").asBoolean(false);
                        int active = item.path("status").path("active").size();
                        String lastSchedule = item.path("status").path("lastScheduleTime").asText("-");
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());

                        rows.add(new Object[]{namespace, name, schedule, suspend, active, lastSchedule, age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        cronJobModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 CronJobs 失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // Load Secrets
    private void loadSecrets() {
        String ns = getSelectedNamespace();
        String path = ns.equals("all") ? "/api/v1/secrets" : "/api/v1/namespaces/" + ns + "/secrets";

        secretModel.setRowCount(0);
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
                        String type = item.path("type").asText();
                        int keys = item.path("data").size();
                        String age = formatAge(item.path("metadata").path("creationTimestamp").asText());

                        rows.add(new Object[]{namespace, name, type, keys, age});
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                try {
                    for (Object[] r : get()) {
                        secretModel.addRow(r);
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "加载 Secrets 失败: " + ex.getMessage());
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
        if (resourceType.equals("deployments") || resourceType.equals("statefulsets") || resourceType.equals("daemonsets")) {
            path = "/apis/apps/v1/namespaces/" + ns + "/" + resourceType + "/" + name;
        } else if (resourceType.equals("cronjobs")) {
            path = "/apis/batch/v1/namespaces/" + ns + "/cronjobs/" + name;
        } else if (resourceType.equals("nodes")) {
            path = "/api/v1/nodes/" + name;
        } else {
            // pods, services, configmaps, secrets
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

    private void execPod() {
        int row = podTable.getSelectedRow();
        if (row == -1) {
            UIUtils.info(null, "请先选择需要打开控制台的 Pod");
            return;
        }
        String ns = podTable.getValueAt(row, 0).toString();
        String name = podTable.getValueAt(row, 1).toString();

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
                    if (containers.size() == 1) {
                        showTerminalDialog(ns, name, containers.get(0));
                    } else {
                        String[] arr = containers.toArray(new String[0]);
                        String choice = (String) JOptionPane.showInputDialog(
                                null,
                                "Pod 中包含多个容器，请选择要进入的容器：",
                                "选择容器",
                                JOptionPane.QUESTION_MESSAGE,
                                null,
                                arr,
                                arr[0]
                        );
                        if (choice != null) {
                            showTerminalDialog(ns, name, choice);
                        }
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "获取 Pod 详情失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void sendStdinToContainer(org.java_websocket.client.WebSocketClient client, String text) {
        try {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            byte[] frame = new byte[data.length + 1];
            frame[0] = 0; 
            System.arraycopy(data, 0, frame, 1, data.length);
            client.send(frame);
        } catch (Exception ex) {
            // ignore
        }
    }

    /**
     * VT100 屏幕缓冲区 — 用二维字符网格模拟真实终端屏幕，
     * 支持绝对光标定位、行覆盖重绘、区域清除和自动滚动，
     * 使 top、vi 等全屏终端程序能够正常显示。
     */
    private static class ScreenBuffer {
        final int rows, cols;
        final char[][] screen;
        int cursorRow, cursorCol;

        ScreenBuffer(int rows, int cols) {
            this.rows = rows;
            this.cols = cols;
            this.screen = new char[rows][cols];
            clearScreen();
        }

        /** 清空整个屏幕 */
        void clearScreen() {
            for (int r = 0; r < rows; r++) {
                java.util.Arrays.fill(screen[r], ' ');
            }
            cursorRow = 0;
            cursorCol = 0;
        }

        /** 清除光标到行尾 */
        void clearToEndOfLine() {
            for (int c = cursorCol; c < cols; c++) {
                screen[cursorRow][c] = ' ';
            }
        }

        /** 清除光标到屏幕末尾 */
        void clearToEndOfScreen() {
            clearToEndOfLine();
            for (int r = cursorRow + 1; r < rows; r++) {
                java.util.Arrays.fill(screen[r], ' ');
            }
        }

        /** 整屏上滚一行 */
        void scrollUp() {
            System.arraycopy(screen, 1, screen, 0, rows - 1);
            java.util.Arrays.fill(screen[rows - 1], ' ');
        }

        /** 在当前光标位置写入一个字符 */
        void writeChar(char c) {
            if (cursorRow >= rows) {
                cursorRow = rows - 1;
                scrollUp();
            }
            if (cursorCol >= cols) {
                cursorCol = 0;
                cursorRow++;
                if (cursorRow >= rows) {
                    cursorRow = rows - 1;
                    scrollUp();
                }
            }
            screen[cursorRow][cursorCol] = c;
            cursorCol++;
        }

        /** 解析输入流中的字符和 ANSI 转义序列 */
        void processText(String text) {
            int len = text.length();
            for (int i = 0; i < len; i++) {
                char c = text.charAt(i);

                // ESC 转义序列
                if (c == 27) {
                    if (i + 1 < len && text.charAt(i + 1) == '[') {
                        // CSI 序列: ESC [ params finalByte
                        int j = i + 2;
                        while (j < len) {
                            char ch = text.charAt(j);
                            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')) {
                                String params = text.substring(i + 2, j);
                                handleCsi(ch, params);
                                i = j;
                                break;
                            }
                            j++;
                        }
                        if (j >= len) {
                            i = len; // 不完整序列，跳过
                        }
                    } else if (i + 1 < len && text.charAt(i + 1) == '(') {
                        i += 2; // 跳过字符集切换序列 ESC ( X
                    }
                    // 跳过其他未知 ESC 序列
                    continue;
                }

                // 换行
                if (c == '\n') {
                    cursorRow++;
                    if (cursorRow >= rows) {
                        cursorRow = rows - 1;
                        scrollUp();
                    }
                    continue;
                }

                // 回车：光标回到行首
                if (c == '\r') {
                    cursorCol = 0;
                    continue;
                }

                // 退格
                if (c == '\b') {
                    if (cursorCol > 0) cursorCol--;
                    continue;
                }

                // Tab
                if (c == '\t') {
                    int next = (cursorCol / 8 + 1) * 8;
                    cursorCol = Math.min(next, cols - 1);
                    continue;
                }

                // 过滤不可打印控制字符
                if (c < 32) {
                    continue;
                }

                // DEL
                if (c == 127) {
                    if (cursorCol > 0) cursorCol--;
                    continue;
                }

                // 普通可打印字符
                writeChar(c);
            }
        }

        /** 处理 CSI 序列 */
        private void handleCsi(char finalByte, String params) {
            switch (finalByte) {
                case 'H': // 光标绝对定位 ESC[row;colH
                case 'f': // 同 H
                    moveCursorTo(params);
                    break;
                case 'A': // 光标上移
                    cursorRow -= parseParam(params, 1);
                    if (cursorRow < 0) cursorRow = 0;
                    break;
                case 'B': // 光标下移
                    cursorRow += parseParam(params, 1);
                    if (cursorRow >= rows) cursorRow = rows - 1;
                    break;
                case 'C': // 光标右移
                    cursorCol += parseParam(params, 1);
                    if (cursorCol >= cols) cursorCol = cols - 1;
                    break;
                case 'D': // 光标左移
                    cursorCol -= parseParam(params, 1);
                    if (cursorCol < 0) cursorCol = 0;
                    break;
                case 'J': // 清除屏幕
                    handleEraseDisplay(parseParam(params, 0));
                    break;
                case 'K': // 清除行
                    handleEraseLine(parseParam(params, 0));
                    break;
                case 'm': // SGR 颜色/样式 — 安全忽略
                    break;
                case 'r': // 设置滚动区域 — 简化忽略
                    break;
                case 'h': // 设置模式 — 忽略
                case 'l': // 重置模式 — 忽略
                    break;
                case 'G': // 光标移到指定列
                    cursorCol = parseParam(params, 1) - 1;
                    if (cursorCol < 0) cursorCol = 0;
                    if (cursorCol >= cols) cursorCol = cols - 1;
                    break;
                case 'd': // 光标移到指定行
                    cursorRow = parseParam(params, 1) - 1;
                    if (cursorRow < 0) cursorRow = 0;
                    if (cursorRow >= rows) cursorRow = rows - 1;
                    break;
                case 'P': // 删除字符
                    int delCount = parseParam(params, 1);
                    for (int c = cursorCol; c < cols; c++) {
                        screen[cursorRow][c] = (c + delCount < cols) ? screen[cursorRow][c + delCount] : ' ';
                    }
                    break;
                case 'X': // 擦除字符
                    int eraseCount = parseParam(params, 1);
                    for (int c = cursorCol; c < Math.min(cursorCol + eraseCount, cols); c++) {
                        screen[cursorRow][c] = ' ';
                    }
                    break;
                case 'L': // 插入行
                    int insertLines = parseParam(params, 1);
                    for (int n = 0; n < insertLines && cursorRow + n < rows; n++) {
                        // 向下推移
                        for (int r = rows - 1; r > cursorRow + n; r--) {
                            System.arraycopy(screen[r - 1], 0, screen[r], 0, cols);
                        }
                        java.util.Arrays.fill(screen[cursorRow + n], ' ');
                    }
                    break;
                case 'M': // 删除行
                    int deleteLines = parseParam(params, 1);
                    for (int n = 0; n < deleteLines; n++) {
                        for (int r = cursorRow; r < rows - 1; r++) {
                            System.arraycopy(screen[r + 1], 0, screen[r], 0, cols);
                        }
                        java.util.Arrays.fill(screen[rows - 1], ' ');
                    }
                    break;
                default:
                    // 未知序列，安全忽略
                    break;
            }
        }

        /** 解析光标定位参数 */
        private void moveCursorTo(String params) {
            if (params.isEmpty()) {
                cursorRow = 0;
                cursorCol = 0;
                return;
            }
            String[] parts = params.split(";");
            int row = 0, col = 0;
            try {
                if (parts.length >= 1 && !parts[0].isEmpty()) row = Integer.parseInt(parts[0]) - 1;
                if (parts.length >= 2 && !parts[1].isEmpty()) col = Integer.parseInt(parts[1]) - 1;
            } catch (NumberFormatException e) {
                // 解析失败归零
            }
            cursorRow = Math.max(0, Math.min(row, rows - 1));
            cursorCol = Math.max(0, Math.min(col, cols - 1));
        }

        /** 解析单个数值参数（默认值 defaultVal） */
        private int parseParam(String params, int defaultVal) {
            if (params.isEmpty()) return defaultVal;
            try {
                return Integer.parseInt(params.split(";")[0]);
            } catch (NumberFormatException e) {
                return defaultVal;
            }
        }

        /** 处理 ESC[nJ 清屏 */
        private void handleEraseDisplay(int mode) {
            switch (mode) {
                case 0: // 清除光标到屏幕末尾
                    clearToEndOfScreen();
                    break;
                case 1: // 清除屏幕开头到光标
                    for (int r = 0; r < cursorRow; r++) {
                        java.util.Arrays.fill(screen[r], ' ');
                    }
                    for (int c = 0; c <= cursorCol && c < cols; c++) {
                        screen[cursorRow][c] = ' ';
                    }
                    break;
                case 2: // 清除整个屏幕
                case 3:
                    for (int r = 0; r < rows; r++) {
                        java.util.Arrays.fill(screen[r], ' ');
                    }
                    break;
            }
        }

        /** 处理 ESC[nK 清行 */
        private void handleEraseLine(int mode) {
            switch (mode) {
                case 0: // 清除光标到行尾
                    clearToEndOfLine();
                    break;
                case 1: // 清除行首到光标
                    for (int c = 0; c <= cursorCol && c < cols; c++) {
                        screen[cursorRow][c] = ' ';
                    }
                    break;
                case 2: // 清除整行
                    java.util.Arrays.fill(screen[cursorRow], ' ');
                    break;
            }
        }

        /** 将屏幕网格渲染为纯文本，去除尾部空行和每行尾部空白 */
        String render() {
            // 先找到最后一个有内容的行（含光标所在行）
            int lastRow = Math.max(cursorRow, 0);
            for (int r = rows - 1; r > lastRow; r--) {
                boolean empty = true;
                for (int c = 0; c < cols; c++) {
                    if (screen[r][c] != ' ') { empty = false; break; }
                }
                if (!empty) { lastRow = r; break; }
            }

            StringBuilder sb = new StringBuilder((lastRow + 1) * (cols + 1));
            for (int r = 0; r <= lastRow; r++) {
                // 找到每行最后一个非空白字符
                int lastNonSpace = cols - 1;
                while (lastNonSpace >= 0 && screen[r][lastNonSpace] == ' ') {
                    lastNonSpace--;
                }
                sb.append(screen[r], 0, lastNonSpace + 1);
                if (r < lastRow) sb.append('\n');
            }
            return sb.toString();
        }
    }

    /** 将终端数据流通过 VT100 屏幕缓冲区解析并渲染到 JTextArea */
    private void renderTerminal(ScreenBuffer buffer, JTextArea area, String text) {
        SwingUtilities.invokeLater(() -> {
            buffer.processText(text);
            area.setText(buffer.render());
            area.setCaretPosition(area.getDocument().getLength());
            area.requestFocusInWindow();
            area.getCaret().setVisible(true);
        });
    }

    private void showTerminalDialog(String ns, String podName, String containerName) {
        Window ancestor = SwingUtilities.getWindowAncestor(podTable);
        JDialog dialog = new JDialog(ancestor instanceof Frame ? (Frame) ancestor : (Frame) null, "容器控制台 (Exec) - " + podName + " / " + containerName, true);
        dialog.setSize(800, 500);
        dialog.setLocationRelativeTo(ancestor);

        JLabel statusLabel = new JLabel("正在连接 API Server...");
        statusLabel.setBorder(new javax.swing.border.EmptyBorder(6, 10, 6, 10));

        // 初始化 VT100 屏幕缓冲区（24行×80列）
        ScreenBuffer screenBuffer = new ScreenBuffer(24, 80);

        JTextArea terminalArea = new JTextArea();
        terminalArea.setEditable(false);
        terminalArea.setBackground(new Color(25, 25, 25));
        terminalArea.setForeground(new Color(64, 224, 208)); 
        terminalArea.setFont(new Font("Consolas", Font.PLAIN, 14));
        terminalArea.setLineWrap(false);
        terminalArea.setMargin(new Insets(8, 8, 8, 8));
        JScrollPane sp = new JScrollPane(terminalArea);

        // 禁用 Tab 切换焦点
        terminalArea.setFocusTraversalKeysEnabled(false);

        // 强行显示闪烁光标，还原 Terminal 视感
        terminalArea.getCaret().setVisible(true);
        terminalArea.getCaret().setBlinkRate(500);
        terminalArea.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                terminalArea.getCaret().setVisible(true);
            }
        });

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        bottom.setBorder(new javax.swing.border.EmptyBorder(4, 10, 6, 10));
        JButton clearBtn = new JButton("清屏");
        bottom.add(clearBtn);
        
        dialog.add(statusLabel, BorderLayout.NORTH);
        dialog.add(sp, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);

        String wsUrl = activeServerUrl;
        if (wsUrl.startsWith("https://")) {
            wsUrl = "wss://" + wsUrl.substring(8);
        } else if (wsUrl.startsWith("http://")) {
            wsUrl = "ws://" + wsUrl.substring(7);
        }
        
        try {
            // 启用 tty=true
            String fullPath = wsUrl + "/api/v1/namespaces/" + ns + "/pods/" + podName + "/exec"
                    + "?container=" + containerName
                    + "&stdin=true&stdout=true&stderr=true&tty=true" 
                    + "&command=sh"; 

            java.net.URI uri = new java.net.URI(fullPath);
            
            Map<String, String> headers = new HashMap<>();
            if (activeToken != null && !activeToken.isEmpty()) {
                headers.put("Authorization", "Bearer " + activeToken);
            }
            headers.put("Sec-WebSocket-Protocol", "v4.channel.k8s.io");

            org.java_websocket.client.WebSocketClient client = new org.java_websocket.client.WebSocketClient(uri, headers) {
                @Override
                public void onOpen(org.java_websocket.handshake.ServerHandshake handshakedata) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("连接成功 (容器: " + containerName + ")");
                        terminalArea.requestFocusInWindow();
                        terminalArea.getCaret().setVisible(true);
                        terminalArea.getCaret().setBlinkRate(500);
                        terminalArea.setText(""); // 连上后清空状态文案，直接迎接容器的回显
                    });
                }

                @Override
                public void onMessage(String message) {
                    renderTerminal(screenBuffer, terminalArea, message);
                }

                @Override
                public void onMessage(java.nio.ByteBuffer bytes) {
                    if (bytes.remaining() > 0) {
                        byte channel = bytes.get(); 
                        if (channel == 1 || channel == 2) {
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            String text = new String(data, StandardCharsets.UTF_8);
                            renderTerminal(screenBuffer, terminalArea, text);
                        } else if (channel == 3) {
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            String err = new String(data, StandardCharsets.UTF_8);
                            renderTerminal(screenBuffer, terminalArea, "\n[K8s 错误]: " + err + "\n");
                        }
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("连接已断开 (" + reason + ")");
                        terminalArea.append("\n=== 连接已断开 ===\n");
                    });
                }

                @Override
                public void onError(Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        terminalArea.append("\n[连接异常]: " + ex.getMessage() + "\n");
                    });
                }
            };

            if (activeServerUrl.startsWith("https://") && activeSocketFactory != null) {
                client.setSocketFactory(activeSocketFactory);
            }

            // 接管 terminalArea 的原生键盘输入
            terminalArea.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyTyped(java.awt.event.KeyEvent e) {
                    char c = e.getKeyChar();
                    // 忽略控制按键，这些在 keyPressed 中接管发送
                    if (c != java.awt.event.KeyEvent.CHAR_UNDEFINED && c != '\n' && c != '\r' && c != '\t' && c != '\b' && c != 127) {
                        if (!e.isControlDown()) {
                            sendStdinToContainer(client, String.valueOf(c));
                        }
                    }
                    e.consume(); // 拦截 Swing 默认字符上屏行为，交由容器终端回显
                }

                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    int code = e.getKeyCode();
                    
                    if (e.isControlDown()) {
                        if (code == java.awt.event.KeyEvent.VK_C) {
                            sendStdinToContainer(client, "\u0003"); // Ctrl+C
                            e.consume();
                            return;
                        }
                        if (code == java.awt.event.KeyEvent.VK_R) {
                            sendStdinToContainer(client, "\u0012"); // Ctrl+R
                            e.consume();
                            return;
                        }
                        if (code == java.awt.event.KeyEvent.VK_D) {
                            sendStdinToContainer(client, "\u0004"); // Ctrl+D
                            e.consume();
                            return;
                        }
                        if (code == java.awt.event.KeyEvent.VK_L) {
                            screenBuffer.clearScreen();
                            terminalArea.setText("");
                            sendStdinToContainer(client, "\u000c"); // Ctrl+L (清屏)
                            e.consume();
                            return;
                        }
                    }

                    switch (code) {
                        case java.awt.event.KeyEvent.VK_ENTER:
                            sendStdinToContainer(client, "\r"); 
                            e.consume();
                            break;
                        case java.awt.event.KeyEvent.VK_BACK_SPACE:
                            sendStdinToContainer(client, "\u007f"); 
                            e.consume();
                            break;
                        case java.awt.event.KeyEvent.VK_TAB:
                            sendStdinToContainer(client, "\t"); 
                            e.consume();
                            break;
                        case java.awt.event.KeyEvent.VK_UP:
                            sendStdinToContainer(client, "\u001b[A"); 
                            e.consume();
                            break;
                        case java.awt.event.KeyEvent.VK_DOWN:
                            sendStdinToContainer(client, "\u001b[B"); 
                            e.consume();
                            break;
                        case java.awt.event.KeyEvent.VK_LEFT:
                            sendStdinToContainer(client, "\u001b[D"); 
                            e.consume();
                            break;
                        case java.awt.event.KeyEvent.VK_RIGHT:
                            sendStdinToContainer(client, "\u001b[C"); 
                            e.consume();
                            break;
                    }
                }
            });

            clearBtn.addActionListener(e -> {
                screenBuffer.clearScreen();
                terminalArea.setText("");
                terminalArea.requestFocusInWindow();
            });

            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    client.close();
                }
            });

            client.connect();

        } catch (Exception ex) {
            UIUtils.error(null, "建立控制台连接失败: " + ex.getMessage());
            dialog.dispose();
            return;
        }

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

                    if (conn instanceof HttpsURLConnection) {
                        HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
                        if (activeSocketFactory != null) {
                            httpsConn.setSSLSocketFactory(activeSocketFactory);
                        } else if (activeSkipTls) {
                            httpsConn.setSSLSocketFactory(getTrustAllSocketFactory());
                        }
                        if (activeSkipTls) {
                            httpsConn.setHostnameVerifier((h, s) -> true);
                        }
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

    private void scaleDeployment(String resourceType, JTable table) {
        int row = table.getSelectedRow();
        if (row == -1) {
            UIUtils.info(null, "请先选择需要修改副本数的 " + resourceType);
            return;
        }
        String ns = table.getValueAt(row, 0).toString();
        String name = table.getValueAt(row, 1).toString();

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
                String path = "/apis/apps/v1/namespaces/" + ns + "/" + resourceType + "/" + name + "/scale";
                String scaleJson = String.format("{\"metadata\":{\"name\":\"%s\",\"namespace\":\"%s\"},\"spec\":{\"replicas\":%d}}", name, ns, targetReplicas);
                executeRequest("PUT", path, scaleJson, activeSkipTls);
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    UIUtils.info(null, "副本数已成功更新为 " + targetReplicas);
                    if (resourceType.equals("deployments")) {
                        loadDeployments();
                    } else if (resourceType.equals("statefulsets")) {
                        loadStatefulSets();
                    }
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
                    if (resourceType.equals("deployments") || resourceType.equals("statefulsets") || resourceType.equals("daemonsets")) {
                        path = "/apis/apps/v1/namespaces/" + ns + "/" + resourceType + "/" + name;
                    } else if (resourceType.equals("cronjobs")) {
                        path = "/apis/batch/v1/namespaces/" + ns + "/cronjobs/" + name;
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
        String[] options = {"从文件导入", "粘贴文本导入", "取消"};
        int choice = JOptionPane.showOptionDialog(
                null,
                "请选择导入 Kubeconfig 的方式：",
                "导入 Kubeconfig",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == 0) {
            importKubeconfigFromFile();
        } else if (choice == 1) {
            importKubeconfigFromText();
        }
    }

    private void handleImportSuccess(K8sProfile p) {
        serverField.setText(p.serverUrl);
        tokenField.setText(p.token);
        skipTlsCheck.setSelected(p.skipTls);
        
        activeClientCert = p.clientCertData;
        activeClientKey = p.clientKeyData;
        
        String baseName = p.name;
        String finalName = baseName;
        int count = 1;
        while (profiles.containsKey(finalName)) {
            finalName = baseName + "_" + count;
            count++;
        }
        p.name = finalName;
        profiles.put(p.name, p);
        saveProfilesToPrefs();
        refreshProfilesCombo(p.name);
    }

    private K8sProfile parseKubeconfig(String yamlText, File baseDir, String sourceName) throws Exception {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        JsonNode root = yamlMapper.readTree(yamlText);
        
        // 1. 获取当前 Context
        String currentContext = root.path("current-context").asText();
        
        // 2. 解析 clusters 和 users
        String serverUrl = "https://127.0.0.1:6443";
        String token = "";
        String clientCertData = null;
        String clientKeyData = null;
        
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

        // 获取集群 API 地址
        JsonNode clusters = root.path("clusters");
        if (clusters.isArray()) {
            for (JsonNode c : clusters) {
                if (c.path("name").asText().equals(clusterName) || clusters.size() == 1) {
                    serverUrl = c.path("cluster").path("server").asText();
                    break;
                }
            }
        }

        // 获取用户信息（Token 或是客户端证书）
        JsonNode users = root.path("users");
        if (users.isArray()) {
            for (JsonNode u : users) {
                if (u.path("name").asText().equals(userName) || users.size() == 1) {
                    token = u.path("user").path("token").asText("");
                    
                    // 解析 inline 的 Base64 证书或文件路径引用的证书
                    if (u.path("user").has("client-certificate-data")) {
                        byte[] bytes = Base64.getDecoder().decode(u.path("user").path("client-certificate-data").asText().trim());
                        clientCertData = new String(bytes, StandardCharsets.UTF_8);
                    } else if (u.path("user").has("client-certificate") && baseDir != null) {
                        String pathStr = u.path("user").path("client-certificate").asText();
                        File f = resolveFile(baseDir, pathStr);
                        if (f != null && f.exists()) {
                            clientCertData = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                        }
                    }
                    
                    // 解析 inline 的 Base64 私钥或文件路径引用的私钥
                    if (u.path("user").has("client-key-data")) {
                        byte[] bytes = Base64.getDecoder().decode(u.path("user").path("client-key-data").asText().trim());
                        clientKeyData = new String(bytes, StandardCharsets.UTF_8);
                    } else if (u.path("user").has("client-key") && baseDir != null) {
                        String pathStr = u.path("user").path("client-key").asText();
                        File f = resolveFile(baseDir, pathStr);
                        if (f != null && f.exists()) {
                            clientKeyData = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                        }
                    }
                    break;
                }
            }
        }

        if (serverUrl.isEmpty()) {
            throw new Exception("在 Kubeconfig 中无法解析出 API Server 地址。");
        }

        return new K8sProfile(serverUrl, serverUrl, token, true, clientCertData, clientKeyData);
    }

    private void importKubeconfigFromFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 Kubeconfig 配置文件");
        
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
                    String content = new String(java.nio.file.Files.readAllBytes(selectedFile.toPath()), StandardCharsets.UTF_8);
                    return parseKubeconfig(content, selectedFile.getParentFile(), selectedFile.getName());
                }

                @Override
                protected void done() {
                    try {
                        K8sProfile p = get();
                        handleImportSuccess(p);
                        UIUtils.info(null, "解析并导入 Kubeconfig 成功！配置已自动保存并选中。");
                    } catch (Exception ex) {
                        UIUtils.error(null, "解析 Kubeconfig 失败: " + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    private void importKubeconfigFromText() {
        JDialog dialog = new JDialog((Frame) null, "粘贴 Kubeconfig 配置文本", true);
        dialog.setSize(600, 450);
        dialog.setLocationRelativeTo(null);

        JTextArea area = new JTextArea();
        area.setFont(UIUtils.monoFont());
        JScrollPane sp = UIUtils.scrollText(area, "请在此处粘贴 Kubeconfig 的 YAML 文本内容");

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton importBtn = new JButton("确认导入");
        importBtn.addActionListener(e -> {
            String text = area.getText().trim();
            if (text.isEmpty()) {
                UIUtils.info(dialog, "配置内容不能为空！");
                return;
            }
            importBtn.setEnabled(false);
            new SwingWorker<K8sProfile, Void>() {
                @Override
                protected K8sProfile doInBackground() throws Exception {
                    String timeStr = String.valueOf(System.currentTimeMillis() % 100000);
                    return parseKubeconfig(text, null, "Text_" + timeStr);
                }

                @Override
                protected void done() {
                    try {
                        K8sProfile p = get();
                        handleImportSuccess(p);
                        UIUtils.info(dialog, "解析并导入 Kubeconfig 成功！配置已自动保存。");
                        dialog.dispose();
                    } catch (Exception ex) {
                        importBtn.setEnabled(true);
                        UIUtils.error(dialog, "解析 Kubeconfig 失败: " + ex.getMessage());
                    }
                }
            }.execute();
        });

        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());

        bottom.add(importBtn);
        bottom.add(closeBtn);

        dialog.add(sp, BorderLayout.CENTER);
        dialog.add(bottom, BorderLayout.SOUTH);
        dialog.setVisible(true);
    }

    private File resolveFile(File baseDir, String pathStr) {
        File f = new File(pathStr);
        if (f.isAbsolute()) {
            return f;
        }
        return new File(baseDir, pathStr);
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
            K8sProfile p = profiles.get(selectName);
            if (p != null) {
                activeClientCert = p.clientCertData;
                activeClientKey = p.clientKeyData;
            }
        } else if (profileCombo.getItemCount() > 0) {
            profileCombo.setSelectedIndex(0);
            String first = profileCombo.getItemAt(0);
            K8sProfile p = profiles.get(first);
            if (p != null) {
                serverField.setText(p.serverUrl);
                tokenField.setText(p.token);
                skipTlsCheck.setSelected(p.skipTls);
                activeClientCert = p.clientCertData;
                activeClientKey = p.clientKeyData;
            }
        } else {
            activeClientCert = null;
            activeClientKey = null;
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

    private javax.net.ssl.SSLSocketFactory buildSSLSocketFactory(boolean skipTls, String certPem, String keyPem) throws Exception {
        javax.net.ssl.KeyManager[] keyManagers = null;
        if (certPem != null && !certPem.trim().isEmpty() && keyPem != null && !keyPem.trim().isEmpty()) {
            java.security.cert.X509Certificate cert = parseCertificate(certPem);
            java.security.PrivateKey privateKey = parsePrivateKey(keyPem);
            
            char[] password = "changeit".toCharArray();
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
            keyStore.load(null, null);
            keyStore.setKeyEntry("client", privateKey, password, new java.security.cert.Certificate[]{cert});
            
            javax.net.ssl.KeyManagerFactory kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            keyManagers = kmf.getKeyManagers();
        }

        javax.net.ssl.TrustManager[] trustManagers = null;
        if (skipTls) {
            trustManagers = new javax.net.ssl.TrustManager[] {
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };
        }

        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
        sc.init(keyManagers, trustManagers, new java.security.SecureRandom());
        return sc.getSocketFactory();
    }

    private java.security.cert.X509Certificate parseCertificate(String pemStr) throws Exception {
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(pemStr.getBytes(StandardCharsets.UTF_8))) {
            return (java.security.cert.X509Certificate) cf.generateCertificate(bis);
        }
    }

    private java.security.PrivateKey parsePrivateKey(String pemStr) throws Exception {
        try (org.bouncycastle.openssl.PEMParser pemParser = new org.bouncycastle.openssl.PEMParser(new java.io.StringReader(pemStr))) {
            Object object = pemParser.readObject();
            org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter converter = new org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter();
            if (object instanceof org.bouncycastle.openssl.PEMKeyPair) {
                java.security.KeyPair kp = converter.getKeyPair((org.bouncycastle.openssl.PEMKeyPair) object);
                return kp.getPrivate();
            } else if (object instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo) {
                return converter.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo) object);
            } else if (object instanceof org.bouncycastle.openssl.PEMEncryptedKeyPair) {
                throw new Exception("不支持加密的私钥文件，请使用未加密的私钥。");
            } else {
                throw new Exception("无法解析的私钥格式: " + (object == null ? "null" : object.getClass().getName()));
            }
        }
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

        if (conn instanceof HttpsURLConnection) {
            HttpsURLConnection httpsConn = (HttpsURLConnection) conn;
            if (activeSocketFactory != null) {
                httpsConn.setSSLSocketFactory(activeSocketFactory);
            } else if (skipTls) {
                httpsConn.setSSLSocketFactory(getTrustAllSocketFactory());
            }
            if (skipTls) {
                httpsConn.setHostnameVerifier((h, s) -> true);
            }
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
        public String clientCertData;
        public String clientKeyData;

        public K8sProfile() {}

        public K8sProfile(String name, String serverUrl, String token, boolean skipTls) {
            this(name, serverUrl, token, skipTls, null, null);
        }

        public K8sProfile(String name, String serverUrl, String token, boolean skipTls, String clientCertData, String clientKeyData) {
            this.name = name;
            this.serverUrl = serverUrl;
            this.token = token;
            this.skipTls = skipTls;
            this.clientCertData = clientCertData;
            this.clientKeyData = clientKeyData;
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
