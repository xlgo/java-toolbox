package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.IOException;

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

    // 每个资源页签的操作按钮：在 buildResourceTabs() 里创建，再交给 initActions() 挂监听
    private JButton refreshPodBtn, yamlPodBtn, logPodBtn, execPodBtn;
    private JButton downloadPodFileBtn, uploadPodFileBtn, delPodBtn;
    private JButton refreshDeployBtn, yamlDeployBtn, scaleDeployBtn, delDeployBtn;
    private JButton refreshStatefulSetBtn, yamlStatefulSetBtn, scaleStatefulSetBtn, delStatefulSetBtn;
    private JButton refreshDaemonSetBtn, yamlDaemonSetBtn, delDaemonSetBtn;
    private JButton refreshCronJobBtn, yamlCronJobBtn, delCronJobBtn;
    private JButton refreshSvcBtn, yamlSvcBtn, delSvcBtn;
    private JButton refreshCmBtn, yamlCmBtn, delCmBtn;
    private JButton refreshSecretBtn, yamlSecretBtn, delSecretBtn;
    private JButton refreshNodeBtn, yamlNodeBtn;
    
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
        JPanel root = Layouts.page();

        // 控制台三段式：集群连接放页首（高度自适应），资源浏览器吃掉全部剩余空间。
        root.add(buildConnectionCard(), BorderLayout.NORTH);
        root.add(buildResourceCard(), BorderLayout.CENTER);

        // Actions registration
        initActions(refreshPodBtn, yamlPodBtn, logPodBtn, delPodBtn, execPodBtn,
                refreshDeployBtn, yamlDeployBtn, scaleDeployBtn, delDeployBtn,
                refreshStatefulSetBtn, yamlStatefulSetBtn, scaleStatefulSetBtn, delStatefulSetBtn,
                refreshDaemonSetBtn, yamlDaemonSetBtn, delDaemonSetBtn,
                refreshCronJobBtn, yamlCronJobBtn, delCronJobBtn,
                refreshSvcBtn, yamlSvcBtn, delSvcBtn,
                refreshCmBtn, yamlCmBtn, delCmBtn,
                refreshSecretBtn, yamlSecretBtn, delSecretBtn,
                refreshNodeBtn, yamlNodeBtn,
                downloadPodFileBtn, uploadPodFileBtn);

        toggleState(false);
        loadProfilesFromPrefs();

        return root;
    }

    /**
     * 集群连接卡片：配置档案一行，API Server 与 Token 分两列，安全设置独占一行。
     *
     * <p>「连接集群 / 断开连接」是这张卡片唯一的主操作，放在标题右侧，
     * 表单下面就不会再多出一整行只有一个按钮的空行。</p>
     */
    private Card buildConnectionCard() {
        profileCombo = Fields.combo(new String[0], 180);
        saveProfileBtn = Buttons.secondary("保存配置");
        delProfileBtn = Buttons.danger("删除配置");
        importKubeconfigBtn = Buttons.secondary("导入 Kubeconfig");
        ActionBar profileActions = new ActionBar();
        profileActions.left(saveProfileBtn);
        profileActions.left(delProfileBtn);
        profileActions.left(importKubeconfigBtn);

        serverField = Fields.text("https://127.0.0.1:6443");
        tokenField = Fields.password();
        skipTlsCheck = Fields.check("跳过 TLS 证书验证 (推荐开发测试环境使用)", true);

        // 地址与凭据都是等长输入框，并成两列后卡片少占一行高度
        FormGrid endpoint = new FormGrid(Tokens.SPACE_MD, Tokens.SPACE_XS);
        endpoint.row("API Server", serverField);

        FormGrid credential = new FormGrid(Tokens.SPACE_MD, Tokens.SPACE_XS);
        credential.row("Token", tokenField);

        FormGrid form = new FormGrid(Tokens.SPACE_MD, Tokens.SPACE_XS);
        form.rowCompact("集群配置", profileCombo, profileActions);
        form.fullRow(Layouts.columns(Tokens.SPACE_XL, endpoint, credential));
        form.row("安全设置", skipTlsCheck);

        connBtn = Buttons.primary("连接集群");

        Card card = Card.titled("Kubernetes 集群配置");
        card.setContent(form);
        card.addHeaderAction(connBtn);
        return card;
    }

    /**
     * 资源浏览器卡片：命名空间与过滤条在上，九类资源用左置页签当导航。
     *
     * <p>页签仍是 {@code JTabbedPane}（索引与切换监听是刷新逻辑的入口），
     * 只把标签条挪到左侧，读起来就是一列资源类型导航。</p>
     */
    private Card buildResourceCard() {
        nsCombo = Fields.combo(new String[0], 180);
        refreshNsBtn = Buttons.secondary("刷新空间列表");
        applyYamlBtn = Buttons.primary("发布资源 (Apply YAML)");
        applyYamlBtn.addActionListener(e -> {
            if (!isConnected) {
                UIUtils.info(null, "请先连接 K8s 集群！");
                return;
            }
            showApplyYamlDialog();
        });

        searchField = Fields.text("", "过滤检索当前列表...");
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

        // 命名空间选择与检索框同属「当前视图范围」，并成一条工具栏；
        // 检索框放 CENTER 吃掉剩余宽度，窄窗口下先被压缩的是它而不是按钮
        JPanel scope = Layouts.box(Tokens.SPACE_SM, 0);
        ActionBar nsGroup = new ActionBar();
        nsGroup.left(Fields.label("命名空间 (Namespace)"));
        nsGroup.left(nsCombo);
        nsGroup.left(refreshNsBtn);
        nsGroup.left(Fields.label("检索 (Filter)"));
        scope.add(nsGroup, BorderLayout.WEST);
        scope.add(searchField, BorderLayout.CENTER);

        JPanel scopeBox = Layouts.box();
        scopeBox.setBorder(KitBorders.padding(
                Tokens.SPACE_MD, Tokens.CARD_PADDING, Tokens.SPACE_MD, Tokens.CARD_PADDING));
        scopeBox.add(scope, BorderLayout.CENTER);

        JPanel head = Layouts.box();
        head.add(scopeBox, BorderLayout.CENTER);
        head.add(new Card.Hairline(), BorderLayout.SOUTH);

        resourceTabs = new JTabbedPane(JTabbedPane.LEFT);
        resourceTabs.setBorder(null);
        buildResourceTabs();

        JPanel body = Layouts.box();
        body.add(head, BorderLayout.NORTH);
        body.add(resourceTabs, BorderLayout.CENTER);

        Card card = Card.flush("集群资源");
        card.addHeaderAction(applyYamlBtn);
        card.setContent(body);
        return card;
    }

    /** 九个资源页签的表格、排序器与操作按钮 */
    private void buildResourceTabs() {
        // Tab A: Pods
        podModel = readOnlyModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "状态 (Status)", "重启次数 (Restarts)", "Pod IP", "节点 (Node)", "存活时间 (Age)"});
        podTable = resourceTable(podModel);
        podSorter = new javax.swing.table.TableRowSorter<>(podModel);
        podTable.setRowSorter(podSorter);
        refreshPodBtn = Buttons.secondary("刷新");
        yamlPodBtn = Buttons.secondary("查看 YAML");
        logPodBtn = Buttons.secondary("查看日志");
        execPodBtn = Buttons.secondary("控制台 (Exec)");
        downloadPodFileBtn = Buttons.secondary("下载文件");
        uploadPodFileBtn = Buttons.secondary("上传文件");
        delPodBtn = Buttons.danger("删除 Pod");
        resourceTabs.addTab("Pods", resourceTab(podTable,
                refreshPodBtn, yamlPodBtn, logPodBtn, execPodBtn,
                downloadPodFileBtn, uploadPodFileBtn, delPodBtn));

        // Tab B: Deployments
        deployModel = readOnlyModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "就绪状态 (Ready)", "最新副本 (Up-to-date)", "可用副本 (Available)", "存活时间 (Age)"});
        deployTable = resourceTable(deployModel);
        deploySorter = new javax.swing.table.TableRowSorter<>(deployModel);
        deployTable.setRowSorter(deploySorter);
        refreshDeployBtn = Buttons.secondary("刷新");
        yamlDeployBtn = Buttons.secondary("查看 YAML");
        scaleDeployBtn = Buttons.secondary("修改副本数 (Scale)");
        delDeployBtn = Buttons.danger("删除 Deployment");
        resourceTabs.addTab("Deployments", resourceTab(deployTable,
                refreshDeployBtn, yamlDeployBtn, scaleDeployBtn, delDeployBtn));

        // Tab C: StatefulSets
        statefulSetModel = readOnlyModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "就绪状态 (Ready)", "当前副本 (Current)", "存活时间 (Age)"});
        statefulSetTable = resourceTable(statefulSetModel);
        statefulSetSorter = new javax.swing.table.TableRowSorter<>(statefulSetModel);
        statefulSetTable.setRowSorter(statefulSetSorter);
        refreshStatefulSetBtn = Buttons.secondary("刷新");
        yamlStatefulSetBtn = Buttons.secondary("查看 YAML");
        scaleStatefulSetBtn = Buttons.secondary("修改副本数 (Scale)");
        delStatefulSetBtn = Buttons.danger("删除 StatefulSet");
        resourceTabs.addTab("StatefulSets", resourceTab(statefulSetTable,
                refreshStatefulSetBtn, yamlStatefulSetBtn, scaleStatefulSetBtn, delStatefulSetBtn));

        // Tab D: DaemonSets
        daemonSetModel = readOnlyModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "期望副本 (Desired)", "当前副本 (Current)", "就绪副本 (Ready)", "最新副本 (Up-to-date)", "存活时间 (Age)"});
        daemonSetTable = resourceTable(daemonSetModel);
        daemonSetSorter = new javax.swing.table.TableRowSorter<>(daemonSetModel);
        daemonSetTable.setRowSorter(daemonSetSorter);
        refreshDaemonSetBtn = Buttons.secondary("刷新");
        yamlDaemonSetBtn = Buttons.secondary("查看 YAML");
        delDaemonSetBtn = Buttons.danger("删除 DaemonSet");
        resourceTabs.addTab("DaemonSets", resourceTab(daemonSetTable,
                refreshDaemonSetBtn, yamlDaemonSetBtn, delDaemonSetBtn));

        // Tab E: CronJobs
        cronJobModel = readOnlyModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "调度计划 (Schedule)", "暂停 (Suspend)", "活跃数 (Active)", "上次执行时间 (Last Schedule)", "存活时间 (Age)"});
        cronJobTable = resourceTable(cronJobModel);
        cronJobSorter = new javax.swing.table.TableRowSorter<>(cronJobModel);
        cronJobTable.setRowSorter(cronJobSorter);
        refreshCronJobBtn = Buttons.secondary("刷新");
        yamlCronJobBtn = Buttons.secondary("查看 YAML");
        delCronJobBtn = Buttons.danger("删除 CronJob");
        resourceTabs.addTab("CronJobs", resourceTab(cronJobTable,
                refreshCronJobBtn, yamlCronJobBtn, delCronJobBtn));

        // Tab F: Services
        svcModel = readOnlyModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "类型 (Type)", "集群 IP (Cluster-IP)", "外部 IP (External-IP)", "端口 (Ports)", "存活时间 (Age)"});
        svcTable = resourceTable(svcModel);
        svcSorter = new javax.swing.table.TableRowSorter<>(svcModel);
        svcTable.setRowSorter(svcSorter);
        refreshSvcBtn = Buttons.secondary("刷新");
        yamlSvcBtn = Buttons.secondary("查看 YAML");
        delSvcBtn = Buttons.danger("删除 Service");
        resourceTabs.addTab("Services", resourceTab(svcTable,
                refreshSvcBtn, yamlSvcBtn, delSvcBtn));

        // Tab G: ConfigMaps
        cmModel = readOnlyModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "键数量 (Keys)", "存活时间 (Age)"});
        cmTable = resourceTable(cmModel);
        cmSorter = new javax.swing.table.TableRowSorter<>(cmModel);
        cmTable.setRowSorter(cmSorter);
        refreshCmBtn = Buttons.secondary("刷新");
        yamlCmBtn = Buttons.secondary("查看 YAML");
        delCmBtn = Buttons.danger("删除 ConfigMap");
        resourceTabs.addTab("ConfigMaps", resourceTab(cmTable,
                refreshCmBtn, yamlCmBtn, delCmBtn));

        // Tab H: Secrets
        secretModel = readOnlyModel(new Object[]{"命名空间 (Namespace)", "名称 (Name)", "类型 (Type)", "数据键数 (Data)", "存活时间 (Age)"});
        secretTable = resourceTable(secretModel);
        secretSorter = new javax.swing.table.TableRowSorter<>(secretModel);
        secretTable.setRowSorter(secretSorter);
        refreshSecretBtn = Buttons.secondary("刷新");
        yamlSecretBtn = Buttons.secondary("查看 YAML");
        delSecretBtn = Buttons.danger("删除 Secret");
        resourceTabs.addTab("Secrets", resourceTab(secretTable,
                refreshSecretBtn, yamlSecretBtn, delSecretBtn));

        // Tab I: Nodes
        nodeModel = readOnlyModel(new Object[]{"节点名称 (Name)", "状态 (Status)", "角色 (Roles)", "版本 (Version)", "系统版本 (OS)", "运行时间 (Age)"});
        nodeTable = resourceTable(nodeModel);
        nodeSorter = new javax.swing.table.TableRowSorter<>(nodeModel);
        nodeTable.setRowSorter(nodeSorter);
        refreshNodeBtn = Buttons.secondary("刷新");
        yamlNodeBtn = Buttons.secondary("查看 YAML");
        resourceTabs.addTab("Nodes (集群节点)", resourceTab(nodeTable,
                refreshNodeBtn, yamlNodeBtn));
    }

    /** 资源列表统一为只读表格模型 */
    private static DefaultTableModel readOnlyModel(Object[] columns) {
        return new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    /** 资源列表表格：单选，行高与表头样式交给全局主题 */
    private static JTable resourceTable(DefaultTableModel model) {
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        return table;
    }

    /**
     * 单个资源页签：表格铺满，操作按钮排在底部。
     *
     * <p>按钮行用 {@link Layouts#wrapRow} 而不是 {@code ActionBar}：Pods 一行有七个按钮，
     * 放进不换行的 {@code ActionBar} 后窄窗口会把每个按钮压到最小宽度、文案被截断；
     * {@code wrapRow} 折行后的高度会计入首选高度，折下去的一排不会被裁掉。</p>
     */
    private static JPanel resourceTab(JTable table, Component... actions) {
        JPanel tab = Layouts.box(Tokens.SPACE_MD, Tokens.SPACE_MD);
        tab.setBorder(KitBorders.padding(Tokens.SPACE_MD));
        // 表格底色与卡片底色相同，放进有内边距的容器要用带细描边的滚动区才看得出边界
        tab.add(Fields.scrollBoxed(table), BorderLayout.CENTER);
        // 纵向间距取 0：wrapRow 只有一行时不会把 FlowLayout 的 vgap 计进首选高度，
        // 传非零值会让按钮底边被裁掉一截；行与行之间的呼吸由外层 BorderLayout 的 vgap 负责
        tab.add(Layouts.wrapRow(Tokens.SPACE_SM, 0, actions), BorderLayout.SOUTH);
        return tab;
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
                             JButton refreshNodeBtn, JButton yamlNodeBtn,
                             JButton downloadPodFileBtn, JButton uploadPodFileBtn) {

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
        downloadPodFileBtn.addActionListener(e -> startFileDownload());
        uploadPodFileBtn.addActionListener(e -> startFileUpload());
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

        JScrollPane treeScroll = Fields.scroll(tree);
        tabs.addTab("折叠树视图 (Collapsible Tree)", treeScroll);

        // Tab 2: 原始 YAML 文本
        JTextArea area = new JTextArea(yaml);
        area.setEditable(false);
        tabs.addTab("YAML 文本 (Raw YAML)", UIUtils.scrollText(area, "YAML / JSON 内容"));

        JButton copyBtn = Buttons.secondary("复制 YAML");
        copyBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(yaml);
            UIUtils.info(dialog, "已成功复制到剪贴板！");
        });
        JButton closeBtn = Buttons.ghost("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        ActionBar bottom = new ActionBar();
        bottom.right(copyBtn);
        bottom.right(closeBtn);

        tabs.setBorder(null);
        JPanel content = Layouts.page();
        content.add(tabs, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
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

    private void startFileDownload() {
        int row = podTable.getSelectedRow();
        if (row == -1) {
            UIUtils.info(null, "请先选择需要下载文件的 Pod");
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
                        promptAndDownloadFile(ns, name, containers.get(0));
                    } else {
                        String[] arr = containers.toArray(new String[0]);
                        String choice = (String) JOptionPane.showInputDialog(
                                null,
                                "Pod 中包含多个容器，请选择容器：",
                                "选择容器",
                                JOptionPane.QUESTION_MESSAGE,
                                null,
                                arr,
                                arr[0]
                        );
                        if (choice != null) {
                            promptAndDownloadFile(ns, name, choice);
                        }
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "获取 Pod 详情失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void startFileUpload() {
        int row = podTable.getSelectedRow();
        if (row == -1) {
            UIUtils.info(null, "请先选择需要上传文件的 Pod");
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
                        promptAndUploadFile(ns, name, containers.get(0));
                    } else {
                        String[] arr = containers.toArray(new String[0]);
                        String choice = (String) JOptionPane.showInputDialog(
                                null,
                                "Pod 中包含多个容器，请选择容器：",
                                "选择容器",
                                JOptionPane.QUESTION_MESSAGE,
                                null,
                                arr,
                                arr[0]
                        );
                        if (choice != null) {
                            promptAndUploadFile(ns, name, choice);
                        }
                    }
                } catch (Exception ex) {
                    UIUtils.error(null, "获取 Pod 详情失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void promptAndDownloadFile(String ns, String podName, String containerName) {
        String containerPath = UIUtils.input(null, "请输入容器内要下载的文件路径（绝对路径）：", "/etc/hosts");
        if (containerPath == null || containerPath.trim().isEmpty()) return;
        final String finalContainerPath = containerPath.trim();

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择保存的本地文件路径");
        String defaultFileName = new File(finalContainerPath).getName();
        chooser.setSelectedFile(new File(defaultFileName));
        int ret = chooser.showSaveDialog(this.getView());
        if (ret != JFileChooser.APPROVE_OPTION) return;
        File localFile = chooser.getSelectedFile();

        JDialog progressDialog = new JDialog((Frame) null, "正在下载文件", true);
        progressDialog.setSize(300, 100);
        progressDialog.setLocationRelativeTo(this.getView());
        progressDialog.setLayout(new BorderLayout(8, 8));
        JLabel statusLabel = new JLabel("正在连接 API Server...", SwingConstants.CENTER);
        progressDialog.add(statusLabel, BorderLayout.CENTER);

        new Thread(() -> {
            boolean success = false;
            String errorMsg = "";
            java.io.FileOutputStream fos = null;
            org.java_websocket.client.WebSocketClient client = null;
            try {
                fos = new java.io.FileOutputStream(localFile);
                final java.io.FileOutputStream finalFos = fos;
                final StringBuilder stderr = new StringBuilder();

                String wsUrl = activeServerUrl;
                if (wsUrl.startsWith("https://")) {
                    wsUrl = "wss://" + wsUrl.substring(8);
                } else if (wsUrl.startsWith("http://")) {
                    wsUrl = "ws://" + wsUrl.substring(7);
                }

                String fullPath = wsUrl + "/api/v1/namespaces/" + ns + "/pods/" + podName + "/exec"
                        + "?container=" + containerName
                        + "&stdin=false&stdout=true&stderr=true&tty=false"
                        + "&command=cat"
                        + "&command=" + java.net.URLEncoder.encode(finalContainerPath, "UTF-8");

                java.net.URI uri = new java.net.URI(fullPath);
                Map<String, String> headers = new HashMap<>();
                if (activeToken != null && !activeToken.isEmpty()) {
                    headers.put("Authorization", "Bearer " + activeToken);
                }
                headers.put("Sec-WebSocket-Protocol", "v4.channel.k8s.io");

                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

                client = new org.java_websocket.client.WebSocketClient(uri, headers) {
                    @Override
                    public void onOpen(org.java_websocket.handshake.ServerHandshake handshakedata) {
                        SwingUtilities.invokeLater(() -> statusLabel.setText("正在传输数据..."));
                    }

                    @Override
                    public void onMessage(String message) {}

                    @Override
                    public void onMessage(java.nio.ByteBuffer bytes) {
                        if (bytes.remaining() > 0) {
                            byte channel = bytes.get();
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            if (channel == 1) { // stdout
                                try {
                                    finalFos.write(data);
                                } catch (IOException e) {
                                    // ignore
                                }
                            } else if (channel == 2 || channel == 3) { // stderr/error
                                stderr.append(new String(data, StandardCharsets.UTF_8));
                            }
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Exception ex) {
                        stderr.append(ex.getMessage());
                        latch.countDown();
                    }
                };

                if (activeServerUrl.startsWith("https://") && activeSocketFactory != null) {
                    client.setSocketFactory(activeSocketFactory);
                } else if (activeSkipTls) {
                    client.setSocketFactory(getTrustAllSocketFactory());
                }

                client.connect();
                latch.await();

                if (stderr.length() > 0) {
                    errorMsg = stderr.toString();
                } else {
                    success = true;
                }
            } catch (Exception ex) {
                errorMsg = ex.getMessage();
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (Exception e) {}
                }
                if (client != null) {
                    try { client.close(); } catch (Exception e) {}
                }
            }

            final boolean finalSuccess = success;
            final String finalError = errorMsg;
            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                if (finalSuccess) {
                    UIUtils.info(null, "文件下载成功！");
                } else {
                    try { localFile.delete(); } catch (Exception e) {}
                    UIUtils.error(null, "文件下载失败: " + finalError);
                }
            });
        }).start();

        progressDialog.setVisible(true);
    }

    private void promptAndUploadFile(String ns, String podName, String containerName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要上传的本地文件");
        int ret = chooser.showOpenDialog(this.getView());
        if (ret != JFileChooser.APPROVE_OPTION) return;
        File localFile = chooser.getSelectedFile();

        String containerPath = UIUtils.input(null, "请输入要上传到容器的文件保存路径（绝对路径）：", "/tmp/" + localFile.getName());
        if (containerPath == null || containerPath.trim().isEmpty()) return;
        final String finalContainerPath = containerPath.trim();

        JDialog progressDialog = new JDialog((Frame) null, "正在上传文件", true);
        progressDialog.setSize(300, 100);
        progressDialog.setLocationRelativeTo(this.getView());
        progressDialog.setLayout(new BorderLayout(8, 8));
        JLabel statusLabel = new JLabel("正在连接 API Server...", SwingConstants.CENTER);
        progressDialog.add(statusLabel, BorderLayout.CENTER);

        new Thread(() -> {
            boolean success = false;
            String errorMsg = "";
            org.java_websocket.client.WebSocketClient client = null;
            try {
                final StringBuilder stderr = new StringBuilder();

                String wsUrl = activeServerUrl;
                if (wsUrl.startsWith("https://")) {
                    wsUrl = "wss://" + wsUrl.substring(8);
                } else if (wsUrl.startsWith("http://")) {
                    wsUrl = "ws://" + wsUrl.substring(7);
                }

                String escapedPath = finalContainerPath.replace("'", "'\\''");
                String commandStr = "cat > '" + escapedPath + "'";
                String fullPath = wsUrl + "/api/v1/namespaces/" + ns + "/pods/" + podName + "/exec"
                        + "?container=" + containerName
                        + "&stdin=true&stdout=true&stderr=true&tty=false"
                        + "&command=sh"
                        + "&command=-c"
                        + "&command=" + java.net.URLEncoder.encode(commandStr, "UTF-8");

                java.net.URI uri = new java.net.URI(fullPath);
                Map<String, String> headers = new HashMap<>();
                if (activeToken != null && !activeToken.isEmpty()) {
                    headers.put("Authorization", "Bearer " + activeToken);
                }
                headers.put("Sec-WebSocket-Protocol", "v4.channel.k8s.io");

                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

                client = new org.java_websocket.client.WebSocketClient(uri, headers) {
                    @Override
                    public void onOpen(org.java_websocket.handshake.ServerHandshake handshakedata) {
                        SwingUtilities.invokeLater(() -> statusLabel.setText("正在上传数据..."));
                        new Thread(() -> {
                            try (java.io.FileInputStream fis = new java.io.FileInputStream(localFile)) {
                                byte[] buffer = new byte[8192];
                                int read;
                                while ((read = fis.read(buffer)) != -1) {
                                    byte[] frame = new byte[read + 1];
                                    frame[0] = 0; // channel 0 (stdin)
                                    System.arraycopy(buffer, 0, frame, 1, read);
                                    send(frame);
                                }
                                Thread.sleep(800);
                            } catch (Exception e) {
                                stderr.append(e.getMessage());
                            } finally {
                                close();
                            }
                        }).start();
                    }

                    @Override
                    public void onMessage(String message) {}

                    @Override
                    public void onMessage(java.nio.ByteBuffer bytes) {
                        if (bytes.remaining() > 0) {
                            byte channel = bytes.get();
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            if (channel == 2 || channel == 3) { // stderr/error
                                stderr.append(new String(data, StandardCharsets.UTF_8));
                            }
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Exception ex) {
                        stderr.append(ex.getMessage());
                        latch.countDown();
                    }
                };

                if (activeServerUrl.startsWith("https://") && activeSocketFactory != null) {
                    client.setSocketFactory(activeSocketFactory);
                } else if (activeSkipTls) {
                    client.setSocketFactory(getTrustAllSocketFactory());
                }

                client.connect();
                latch.await();

                if (stderr.length() > 0) {
                    errorMsg = stderr.toString();
                } else {
                    success = true;
                }
            } catch (Exception ex) {
                errorMsg = ex.getMessage();
            }

            final boolean finalSuccess = success;
            final String finalError = errorMsg;
            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                if (finalSuccess) {
                    UIUtils.info(null, "文件上传成功！");
                } else {
                    UIUtils.error(null, "文件上传失败: " + finalError);
                }
            });
        }).start();

        progressDialog.setVisible(true);
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



    private void showTerminalDialog(String ns, String podName, String containerName) {
        Window ancestor = SwingUtilities.getWindowAncestor(podTable);
        JDialog dialog = new JDialog(ancestor instanceof Frame ? (Frame) ancestor : (Frame) null, "容器控制台 (Exec) - " + podName + " / " + containerName, true);
        dialog.setSize(850, 520);
        dialog.setLocationRelativeTo(ancestor);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel statusLabel = new JLabel("正在连接 API Server...");
        statusLabel.setBorder(new javax.swing.border.EmptyBorder(6, 10, 6, 10));

        java.util.concurrent.LinkedBlockingQueue<String> readQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        StringBuilder readBuffer = new StringBuilder();

        String wsUrl = activeServerUrl;
        if (wsUrl.startsWith("https://")) {
            wsUrl = "wss://" + wsUrl.substring(8);
        } else if (wsUrl.startsWith("http://")) {
            wsUrl = "ws://" + wsUrl.substring(7);
        }

        org.java_websocket.client.WebSocketClient[] clientHolder = new org.java_websocket.client.WebSocketClient[1];

        TtyConnector connector = new TtyConnector() {
            private boolean closed = false;

            @Override
            public String getName() {
                return "K8s Container Terminal";
            }

            @Override
            public boolean init(com.jediterm.terminal.Questioner q) {
                return true;
            }

            @Override
            public void write(byte[] bytes) throws IOException {
                org.java_websocket.client.WebSocketClient client = clientHolder[0];
                if (bytes != null && bytes.length > 0 && client != null && client.isOpen()) {
                    try {
                        byte[] frame = new byte[bytes.length + 1];
                        frame[0] = 0; // channel 0 (stdin)
                        System.arraycopy(bytes, 0, frame, 1, bytes.length);
                        client.send(frame);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            @Override
            public void write(String s) throws IOException {
                if (s != null) {
                    write(s.getBytes(StandardCharsets.UTF_8));
                }
            }

            @Override
            public int read(char[] buf, int offset, int len) throws IOException {
                if (len <= 0) return 0;
                synchronized (readBuffer) {
                    while (readBuffer.length() == 0) {
                        if (closed) return -1;
                        try {
                            String s = readQueue.take();
                            if (closed) return -1;
                            readBuffer.append(s);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return -1;
                        }
                    }
                    int count = Math.min(len, readBuffer.length());
                    readBuffer.getChars(0, count, buf, offset);
                    readBuffer.delete(0, count);
                    return count;
                }
            }

            @Override
            public void close() {
                closed = true;
                try {
                    if (clientHolder[0] != null) {
                        clientHolder[0].close();
                    }
                } catch (Exception e) {}
                readQueue.offer(""); // Unblock read thread if any
            }

            @Override
            public void resize(Dimension winSize) {
                org.java_websocket.client.WebSocketClient client = clientHolder[0];
                if (client != null && client.isOpen()) {
                    try {
                        String resizeJson = String.format("{\"Width\":%d,\"Height\":%d}", winSize.width, winSize.height);
                        byte[] data = resizeJson.getBytes(StandardCharsets.UTF_8);
                        byte[] frame = new byte[data.length + 1];
                        frame[0] = 4; // channel 4 (resize)
                        System.arraycopy(data, 0, frame, 1, data.length);
                        client.send(frame);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            @Override
            public void resize(Dimension winSize, Dimension pixelSize) {
                resize(winSize);
            }

            @Override
            public void resize(com.jediterm.core.util.TermSize termSize) {
                if (termSize != null) {
                    resize(new Dimension(termSize.getColumns(), termSize.getRows()));
                }
            }

            @Override
            public int waitFor() throws InterruptedException {
                return 0;
            }

            @Override
            public boolean isConnected() {
                return !closed;
            }

            @Override
            public boolean ready() {
                return !closed;
            }
        };

        DefaultSettingsProvider settingsProvider = new DefaultSettingsProvider() {
            @Override
            public Font getTerminalFont() {
                return new Font("Monospaced", Font.PLAIN, 14);
            }

            @Override
            public float getTerminalFontSize() {
                return 14.0f;
            }

            @Override
            public com.jediterm.terminal.HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
                return com.jediterm.terminal.HyperlinkStyle.HighlightMode.NEVER;
            }
        };

        JediTermWidget terminalWidget = new JediTermWidget(settingsProvider);
        terminalWidget.createTerminalSession(connector);
        terminalWidget.start();

        dialog.add(statusLabel, BorderLayout.NORTH);
        dialog.add(terminalWidget, BorderLayout.CENTER);

        try {
            String fullPath = wsUrl + "/api/v1/namespaces/" + ns + "/pods/" + podName + "/exec"
                    + "?container=" + containerName
                    + "&stdin=true&stdout=true&stderr=true&tty=true" 
                    + "&command=sh"
                    + "&command=-c"
                    + "&command=export%20LANG%3DC.UTF-8%20%7C%7C%20export%20LANG%3Den_US.UTF-8%3B%20if%20%5B%20-x%20%2Fbin%2Fbash%20%5D%20%7C%7C%20which%20bash%20%3E%2Fdev%2Fnull%202%3E%261%3B%20then%20exec%20bash%3B%20else%20exec%20sh%3B%20fi"; 

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
                        javax.swing.JComponent pref = terminalWidget.getPreferredFocusableComponent();
                        if (pref != null) {
                            pref.requestFocusInWindow();
                            pref.requestFocus();
                        } else {
                            terminalWidget.requestFocusInWindow();
                        }
                    });
                }

                @Override
                public void onMessage(String message) {
                    if (message != null) {
                        readQueue.offer(message);
                    }
                }

                @Override
                public void onMessage(java.nio.ByteBuffer bytes) {
                    if (bytes.remaining() > 0) {
                        byte channel = bytes.get(); 
                        if (channel == 1 || channel == 2) {
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            String text = new String(data, StandardCharsets.UTF_8);
                            readQueue.offer(text);
                        } else if (channel == 3) {
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            String err = new String(data, StandardCharsets.UTF_8);
                            if (err != null && !err.trim().startsWith("{")) {
                                readQueue.offer("\n[K8s 错误]: " + err + "\n");
                            }
                        }
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("连接已断开 (" + reason + ")");
                        readQueue.offer("\n=== 连接已断开 ===\n");
                        javax.swing.Timer timer = new javax.swing.Timer(1500, e -> dialog.dispose());
                        timer.setRepeats(false);
                        timer.start();
                    });
                }

                @Override
                public void onError(Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        readQueue.offer("\n[连接异常]: " + ex.getMessage() + "\n");
                    });
                }
            };

            clientHolder[0] = client;

            if (activeServerUrl.startsWith("https://") && activeSocketFactory != null) {
                client.setSocketFactory(activeSocketFactory);
            }

            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    connector.close();
                    terminalWidget.stop();
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

        // 容器选择、追踪开关与加载动作是同一条工具栏，窄窗口下按钮不换行、下拉不被拉宽
        ActionBar top = new ActionBar();
        JComboBox<String> containerCombo = Fields.combo(new String[0], 200);
        for (String c : containers) {
            containerCombo.addItem(c);
        }
        top.left(Fields.label("选择容器 (Container)"));
        top.left(containerCombo);

        JCheckBox followCheck = Fields.check("追踪更新 (Follow)", false);
        top.left(followCheck);

        JButton loadMoreBtn = Buttons.secondary("加载前500行");
        loadMoreBtn.setEnabled(false);
        top.left(loadMoreBtn);

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

        JButton refreshBtn = Buttons.secondary("刷新日志");
        refreshBtn.addActionListener(e -> {
            currentTailLines[0] = 1000;
            logFetcher.run();
        });
        top.left(refreshBtn);

        JButton copyBtn = Buttons.secondary("复制日志");
        copyBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(area.getText());
            UIUtils.info(dialog, "日志已复制！");
        });
        JButton closeBtn = Buttons.ghost("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        ActionBar bottom = new ActionBar();
        bottom.right(copyBtn);
        bottom.right(closeBtn);

        // 日志区放 CENTER 吃掉全部剩余高度，工具栏与动作行只占各自首选高度
        JPanel content = Layouts.page();
        content.add(top, BorderLayout.NORTH);
        content.add(sp, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);

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

        JButton importBtn = Buttons.primary("确认导入");
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

        JButton closeBtn = Buttons.ghost("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());

        ActionBar bottom = new ActionBar();
        bottom.right(importBtn);
        bottom.right(closeBtn);

        JPanel content = Layouts.page();
        content.add(sp, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
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

        JButton deployBtn = Buttons.primary("确认发布");
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

        JButton closeBtn = Buttons.ghost("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());

        ActionBar bottom = new ActionBar();
        bottom.right(deployBtn);
        bottom.right(closeBtn);

        JPanel content = Layouts.page();
        content.add(sp, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
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
