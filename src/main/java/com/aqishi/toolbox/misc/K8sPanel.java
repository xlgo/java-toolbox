package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kubernetes 部署文件生成面板。左右分栏设计，支持实时生成与配置。
 */
public class K8sPanel extends ToolPanel {

    // ==================== 模板 ====================
    private static final Map<String, String[]> BUILTIN = new LinkedHashMap<>();
    private static final Map<String, String[]> CUSTOM = new LinkedHashMap<>();
    private static final String TEMPLATE_PREFIX = "k8s.template.";
    private static final String CUSTOM_TEMPLATE_NAMES_KEY = TEMPLATE_PREFIX + "custom.names";

    static {
        boolean changed = false;
        changed |= registerBuiltinTemplate("nginx", "Web服务 (Nginx)", a("web-app","nginx:latest","2","80","80","ClusterIP","100m","500m","128Mi","256Mi","","",""));
        changed |= registerBuiltinTemplate("java-spring-boot", "Java / Spring Boot", a("java-app","openjdk:17-jdk-slim","2","8080","8080","ClusterIP","500m","1000m","512Mi","1Gi","JAVA_OPTS=-Xmx512m\nSPRING_PROFILES_ACTIVE=prod","liveness: /actuator/health\nreadiness: /actuator/health",""));
        changed |= registerBuiltinTemplate("nodejs", "Node.js", a("node-app","node:18-alpine","2","3000","3000","ClusterIP","200m","500m","256Mi","512Mi","NODE_ENV=production","",""));
        changed |= registerBuiltinTemplate("frontend-nginx-spa", "前端 (Nginx SPA)", a("frontend","nginx:alpine","2","80","80","ClusterIP","50m","200m","64Mi","128Mi","","",""));
        changed |= registerBuiltinTemplate("mysql", "MySQL", a("mysql","mysql:8.0","1","3306","3306","ClusterIP","500m","1000m","512Mi","1Gi","MYSQL_ROOT_PASSWORD=changeme\nMYSQL_DATABASE=appdb","",""));
        changed |= registerBuiltinTemplate("redis", "Redis", a("redis","redis:7-alpine","1","6379","6379","ClusterIP","200m","500m","128Mi","256Mi","","",""));
        loadCustomTemplates();
        if (changed) {
            ConfigManager.save();
        }
    }

    private static String[] a(String... v) { return v; }

    private static boolean registerBuiltinTemplate(String id, String defaultName, String[] defaultValues) {
        String nameKey = TEMPLATE_PREFIX + "builtin." + id + ".name";
        String valuesKey = TEMPLATE_PREFIX + "builtin." + id + ".values";
        boolean changed = false;

        if (ConfigManager.get(nameKey, null) == null) {
            ConfigManager.set(nameKey, defaultName);
            changed = true;
        }
        if (ConfigManager.get(valuesKey, null) == null) {
            ConfigManager.set(valuesKey, encodeTemplate(defaultValues));
            changed = true;
        }

        String name = ConfigManager.get(nameKey, defaultName);
        String[] values = decodeTemplate(ConfigManager.get(valuesKey, encodeTemplate(defaultValues)), defaultValues);
        BUILTIN.put(name, values);
        return changed;
    }

    private static void loadCustomTemplates() {
        CUSTOM.clear();
        String names = ConfigManager.get(CUSTOM_TEMPLATE_NAMES_KEY, "");
        if (names.trim().isEmpty()) return;

        for (String encodedName : names.split(",")) {
            if (encodedName.trim().isEmpty()) continue;
            String name = decodeText(encodedName);
            String values = ConfigManager.get(TEMPLATE_PREFIX + "custom." + encodedName + ".values", null);
            if (name != null && values != null) {
                CUSTOM.put(name, decodeTemplate(values, a("", "", "", "", "", "ClusterIP", "", "", "", "", "", "", "")));
            }
        }
    }

    private static void saveCustomTemplates() {
        StringBuilder names = new StringBuilder();
        for (Map.Entry<String, String[]> entry : CUSTOM.entrySet()) {
            String encodedName = encodeText(entry.getKey());
            if (names.length() > 0) names.append(',');
            names.append(encodedName);
            ConfigManager.set(TEMPLATE_PREFIX + "custom." + encodedName + ".values", encodeTemplate(entry.getValue()));
        }
        ConfigManager.set(CUSTOM_TEMPLATE_NAMES_KEY, names.toString());
        ConfigManager.save();
    }

    private static String encodeTemplate(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) sb.append('|');
            sb.append(encodeText(value == null ? "" : value));
        }
        return sb.toString();
    }

    private static String[] decodeTemplate(String data, String[] fallback) {
        try {
            String[] parts = data.split("\\|", -1);
            String[] values = new String[13];
            for (int i = 0; i < values.length; i++) {
                String value = i < parts.length ? decodeText(parts[i]) : "";
                values[i] = value == null ? "" : value;
            }
            return values;
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static String encodeText(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeText(String text) {
        try {
            return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return null;
        }
    }

    // ====== 共享 ======
    private JComboBox<String> templateCombo;
    private JTextField nameField, nsField;
    private boolean ignoreEvents = false;

    // ====== 资源启用勾选 ======
    private JCheckBox enableDeploymentCheck;
    private JCheckBox enableSvcCheck;
    private JCheckBox enableIngressCheck;
    private JCheckBox enableCmCheck;

    // ====== 各资源面板子容器（用于一键禁用/启用其下所有控件） ======
    private JPanel depInnerPanel;
    private JPanel svcInnerPanel;
    private JPanel ingInnerPanel;
    private JPanel cmInnerPanel;

    // ====== Deployment ======
    private JTextField depImgField, depReplicas, depPort, depCpuR, depCpuL, depMemR, depMemL;
    private JComboBox<String> depPullCombo;
    private JTextArea depEnvArea, depLabelArea;

    // ====== Probes (健康检查) ======
    private JCheckBox livenessEnabledCheck;
    private JComboBox<String> livenessTypeCombo;
    private JTextField livenessValueField;
    private JTextField livenessDelayField, livenessPeriodField, livenessTimeoutField;

    private JCheckBox readinessEnabledCheck;
    private JComboBox<String> readinessTypeCombo;
    private JTextField readinessValueField;
    private JTextField readinessDelayField, readinessPeriodField, readinessTimeoutField;

    private JCheckBox startupEnabledCheck;
    private JComboBox<String> startupTypeCombo;
    private JTextField startupValueField;
    private JTextField startupDelayField, startupPeriodField, startupTimeoutField;

    // ====== Service ======
    private JComboBox<String> svcTypeCombo;
    private JPanel svcPortsContainer;
    private final java.util.List<SvcPortRow> svcPortRows = new java.util.ArrayList<>();
    private JButton addPortBtn;

    // ====== Ingress ======
    private JTextField ingHostField;
    private JCheckBox ingTlsCheck;

    // ====== ConfigMap ======
    private JTextArea cmArea;

    // ====== 输出 ======
    private JTextArea outputArea;

    public K8sPanel() {
        super("dev", "k8s.deployment",
                "K8s", "Kubernetes", "部署", "YAML", "容器",
                "Deployment", "Service", "Ingress", "ConfigMap", "编排");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ==================== 左侧：配置区 ====================
        JPanel leftPanel = new JPanel(new BorderLayout(8, 8));

        // 顶部公共配置
        JPanel topConfig = new JPanel(new GridBagLayout());
        topConfig.setBorder(BorderFactory.createTitledBorder("基础与模板配置"));
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.HORIZONTAL;
        gc.insets = new Insets(4, 6, 4, 6);

        // 模板选择与管理
        gc.gridx = 0; gc.gridy = 0; gc.weightx = 0;
        topConfig.add(new JLabel("配置模板："), gc);

        JPanel tmplRow = new JPanel(new BorderLayout(4, 0));
        templateCombo = new JComboBox<>();
        refreshCombo();
        tmplRow.add(templateCombo, BorderLayout.CENTER);

        JPanel tmplBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        JButton saveTmplBtn = new JButton("保存");
        saveTmplBtn.setToolTipText("将当前输入保存为自定义模板");
        JButton delTmplBtn = new JButton("删除");
        delTmplBtn.setToolTipText("删除所选自定义模板");
        tmplBtns.add(saveTmplBtn);
        tmplBtns.add(delTmplBtn);
        tmplRow.add(tmplBtns, BorderLayout.EAST);

        gc.gridx = 1; gc.weightx = 1.0;
        topConfig.add(tmplRow, gc);

        // 应用名称与命名空间
        gc.gridx = 0; gc.gridy = 1; gc.weightx = 0;
        topConfig.add(new JLabel("应用名称："), gc);
        nameField = new JTextField("my-app");
        gc.gridx = 1; gc.weightx = 1.0;
        topConfig.add(nameField, gc);

        gc.gridx = 0; gc.gridy = 2; gc.weightx = 0;
        topConfig.add(new JLabel("命名空间："), gc);
        nsField = new JTextField("default");
        gc.gridx = 1; gc.weightx = 1.0;
        topConfig.add(nsField, gc);

        leftPanel.add(topConfig, BorderLayout.NORTH);

        // 各资源配置 Tab 页
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Deployment", buildDeployTab());
        tabs.addTab("Service", buildSvcTab());
        tabs.addTab("Ingress", buildIngTab());
        tabs.addTab("ConfigMap", buildCmTab());
        leftPanel.add(tabs, BorderLayout.CENTER);

        // ==================== 右侧：实时输出预览 ====================
        JPanel rightPanel = new JPanel(new BorderLayout(8, 8));
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        rightPanel.add(UIUtils.scrollText(outputArea, "实时生成的 Kubernetes YAML 预览"), BorderLayout.CENTER);

        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        JButton copyBtn = UIUtils.button("复制 YAML", 110);
        JButton resetBtn = UIUtils.button("重置", 80);
        rightBtns.add(resetBtn);
        rightBtns.add(copyBtn);
        rightPanel.add(rightBtns, BorderLayout.SOUTH);

        // ==================== 整体布局：左右分栏 ====================
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        mainSplit.setDividerLocation(460);
        mainSplit.setResizeWeight(0.45);
        root.add(mainSplit, BorderLayout.CENTER);

        // ==================== 事件监听与初次生成 ====================
        // 绑定资源启用复选框的级联状态控制
        enableDeploymentCheck.addActionListener(e -> {
            setContainerEnabled(depInnerPanel, enableDeploymentCheck.isSelected());
            triggerGenerate();
        });
        enableSvcCheck.addActionListener(e -> {
            setContainerEnabled(svcInnerPanel, enableSvcCheck.isSelected());
            updateNodePortVisibility();
            triggerGenerate();
        });
        enableIngressCheck.addActionListener(e -> {
            setContainerEnabled(ingInnerPanel, enableIngressCheck.isSelected());
            triggerGenerate();
        });
        enableCmCheck.addActionListener(e -> {
            setContainerEnabled(cmInnerPanel, enableCmCheck.isSelected());
            triggerGenerate();
        });

        // 模板管理事件
        templateCombo.addActionListener(e -> {
            if (!ignoreEvents) applyTemplate();
        });
        saveTmplBtn.addActionListener(e -> saveTemplate());
        delTmplBtn.addActionListener(e -> deleteTemplate());

        // 复制/重置按钮事件
        copyBtn.addActionListener(e -> {
            String text = outputArea.getText().trim();
            if (!text.isEmpty() && !text.startsWith("# 校验错误")) {
                UIUtils.copyToClipboard(text);
                UIUtils.info(root, "YAML 内容已成功复制到剪贴板！");
            } else {
                UIUtils.error(root, "当前没有有效的 YAML 内容可复制。");
            }
        });
        resetBtn.addActionListener(e -> {
            ignoreEvents = true;
            templateCombo.setSelectedIndex(0);
            nameField.setText("my-app");
            nsField.setText("default");
            enableDeploymentCheck.setSelected(true);
            enableSvcCheck.setSelected(true);
            enableIngressCheck.setSelected(false);
            enableCmCheck.setSelected(false);
            
            setContainerEnabled(depInnerPanel, true);
            setContainerEnabled(svcInnerPanel, true);
            setContainerEnabled(ingInnerPanel, false);
            setContainerEnabled(cmInnerPanel, false);

            depImgField.setText("nginx:latest");
            depPullCombo.setSelectedIndex(0);
            depReplicas.setText("1");
            depPort.setText("80");
            depCpuR.setText("100m");
            depCpuL.setText("500m");
            depMemR.setText("128Mi");
            depMemL.setText("256Mi");
            depLabelArea.setText("");
            depEnvArea.setText("");
            livenessEnabledCheck.setSelected(false);
            livenessTypeCombo.setSelectedIndex(0);
            livenessValueField.setText("/health");
            livenessDelayField.setText("10");
            livenessPeriodField.setText("15");
            livenessTimeoutField.setText("5");

            readinessEnabledCheck.setSelected(false);
            readinessTypeCombo.setSelectedIndex(0);
            readinessValueField.setText("/health");
            readinessDelayField.setText("10");
            readinessPeriodField.setText("15");
            readinessTimeoutField.setText("5");

            startupEnabledCheck.setSelected(false);
            startupTypeCombo.setSelectedIndex(0);
            startupValueField.setText("/health");
            startupDelayField.setText("10");
            startupPeriodField.setText("15");
            startupTimeoutField.setText("5");

            svcTypeCombo.setSelectedIndex(0);
            deserializeSvcPorts("TCP:http:80:80:");

            ingHostField.setText("app.example.com");
            ingTlsCheck.setSelected(false);

            cmArea.setText("");
            ignoreEvents = false;
            updateNodePortVisibility();
            triggerGenerate();
        });

        // 绑定所有输入项的实时更新监听
        bindRealtimeListeners();

        // 触发初始的默认生成
        triggerGenerate();

        return root;
    }

    // ==================== Deployment 标签页 ====================
    private JComponent buildDeployTab() {
        JPanel wrapper = new JPanel(new BorderLayout(4, 4));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        enableDeploymentCheck = new JCheckBox("启用 Deployment 部署配置", true);
        wrapper.add(enableDeploymentCheck, BorderLayout.NORTH);

        depInnerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 4, 2, 4);
        int row = 0;

        row = addF(depInnerPanel, c, row, "镜像地址：", depImgField = new JTextField("nginx:latest"));
        row = addF(depInnerPanel, c, row, "拉取策略：", depPullCombo = new JComboBox<>(new String[]{"IfNotPresent", "Always", "Never"}));
        row = addF(depInnerPanel, c, row, "副本数量：", depReplicas = new JTextField("1"));
        row = addF(depInnerPanel, c, row, "容器端口：", depPort = new JTextField("80"));
        row = addF(depInnerPanel, c, row, "CPU 请求值：", depCpuR = new JTextField("100m"));
        row = addF(depInnerPanel, c, row, "CPU 限制值：", depCpuL = new JTextField("500m"));
        row = addF(depInnerPanel, c, row, "内存请求值：", depMemR = new JTextField("128Mi"));
        row = addF(depInnerPanel, c, row, "内存限制值：", depMemL = new JTextField("256Mi"));

        row = addSection(depInnerPanel, c, row, "额外标签 (每行 key=value，可选)", depLabelArea = new JTextArea(2, 20));
        row = addSection(depInnerPanel, c, row, "环境变量 (每行 KEY=VALUE，如 TZ=Asia/Shanghai)", depEnvArea = new JTextArea(3, 20));

        // ===== 3 种健康检查探针配置 =====
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 0;
        JLabel probeLabel = new JLabel("健康检查探针配置 (Liveness, Readiness, Startup)");
        probeLabel.setFont(UIUtils.plainFont().deriveFont(Font.BOLD));
        probeLabel.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        depInnerPanel.add(probeLabel, c);
        row++;

        // 1. Liveness Probe
        livenessEnabledCheck = new JCheckBox("启用存活探针 (Liveness)", false);
        livenessTypeCombo = new JComboBox<>(new String[]{"HTTP GET", "TCP Socket", "Exec Command"});
        livenessValueField = new JTextField("/health");
        livenessDelayField = new JTextField("10"); livenessDelayField.setColumns(3);
        livenessPeriodField = new JTextField("15"); livenessPeriodField.setColumns(3);
        livenessTimeoutField = new JTextField("5"); livenessTimeoutField.setColumns(3);
        row = addProbeConfig(depInnerPanel, c, row, livenessEnabledCheck, livenessTypeCombo, livenessValueField,
                             livenessDelayField, livenessPeriodField, livenessTimeoutField);

        // 2. Readiness Probe
        readinessEnabledCheck = new JCheckBox("启用就绪探针 (Readiness)", false);
        readinessTypeCombo = new JComboBox<>(new String[]{"HTTP GET", "TCP Socket", "Exec Command"});
        readinessValueField = new JTextField("/health");
        readinessDelayField = new JTextField("10"); readinessDelayField.setColumns(3);
        readinessPeriodField = new JTextField("15"); readinessPeriodField.setColumns(3);
        readinessTimeoutField = new JTextField("5"); readinessTimeoutField.setColumns(3);
        row = addProbeConfig(depInnerPanel, c, row, readinessEnabledCheck, readinessTypeCombo, readinessValueField,
                             readinessDelayField, readinessPeriodField, readinessTimeoutField);

        // 3. Startup Probe
        startupEnabledCheck = new JCheckBox("启用启动探针 (Startup)", false);
        startupTypeCombo = new JComboBox<>(new String[]{"HTTP GET", "TCP Socket", "Exec Command"});
        startupValueField = new JTextField("/health");
        startupDelayField = new JTextField("10"); startupDelayField.setColumns(3);
        startupPeriodField = new JTextField("15"); startupPeriodField.setColumns(3);
        startupTimeoutField = new JTextField("5"); startupTimeoutField.setColumns(3);
        row = addProbeConfig(depInnerPanel, c, row, startupEnabledCheck, startupTypeCombo, startupValueField,
                             startupDelayField, startupPeriodField, startupTimeoutField);

        wrapper.add(new JScrollPane(depInnerPanel), BorderLayout.CENTER);
        return wrapper;
    }

    private int addProbeConfig(JPanel p, GridBagConstraints c, int row,
                               JCheckBox enabledCheck, JComboBox<String> typeCombo, JTextField valField,
                               JTextField delayField, JTextField periodField, JTextField timeoutField) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        p.add(enabledCheck, c);
        
        c.gridx = 1; c.weightx = 1.0;
        JPanel row1 = new JPanel(new BorderLayout(4, 0));
        row1.add(typeCombo, BorderLayout.WEST);
        row1.add(valField, BorderLayout.CENTER);
        p.add(row1, c);
        
        row++;
        
        c.gridx = 1; c.gridy = row; c.weightx = 1.0;
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.add(new JLabel("延迟:"));
        row2.add(delayField);
        row2.add(new JLabel("s 间隔:"));
        row2.add(periodField);
        row2.add(new JLabel("s 超时:"));
        row2.add(timeoutField);
        row2.add(new JLabel("s"));
        p.add(row2, c);
        
        return row + 1;
    }

    // ==================== Service 标签页 ====================
    private JComponent buildSvcTab() {
        JPanel wrapper = new JPanel(new BorderLayout(4, 4));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        enableSvcCheck = new JCheckBox("启用 Service 服务配置", true);
        wrapper.add(enableSvcCheck, BorderLayout.NORTH);

        svcInnerPanel = new JPanel(new BorderLayout(4, 4));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        topRow.add(new JLabel("服务类型 (Type)："));
        svcTypeCombo = new JComboBox<>(new String[]{"ClusterIP", "NodePort", "LoadBalancer"});
        topRow.add(svcTypeCombo);
        
        addPortBtn = new JButton("添加端口组");
        addPortBtn.addActionListener(e -> {
            addPortRow("TCP", "http-" + (svcPortRows.size() + 1), "", "", "");
            updateNodePortVisibility();
            svcPortsContainer.revalidate();
            svcPortsContainer.repaint();
            triggerGenerate();
        });
        topRow.add(addPortBtn);
        
        svcInnerPanel.add(topRow, BorderLayout.NORTH);

        svcPortsContainer = new JPanel();
        svcPortsContainer.setLayout(new BoxLayout(svcPortsContainer, BoxLayout.Y_AXIS));
        
        // 初始注入第一组端口配置
        addPortRow("TCP", "http", "80", "80", "");
        
        svcInnerPanel.add(new JScrollPane(svcPortsContainer), BorderLayout.CENTER);

        wrapper.add(svcInnerPanel, BorderLayout.CENTER);
        return wrapper;
    }

    private void addPortRow(String protocol, String name, String port, String targetPort, String nodePort) {
        SvcPortRow row = new SvcPortRow(protocol, name, port, targetPort, nodePort);
        svcPortRows.add(row);
        svcPortsContainer.add(row.panel);
    }

    private class SvcPortRow {
        JPanel panel;
        JComboBox<String> protocolCombo;
        JTextField nameField;
        JTextField portField;
        JTextField targetPortField;
        JTextField nodePortField;
        JButton deleteBtn;
        
        SvcPortRow(String protocol, String name, String port, String targetPort, String nodePort) {
            panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            protocolCombo = new JComboBox<>(new String[]{"TCP", "UDP"});
            protocolCombo.setSelectedItem(protocol);
            
            nameField = new JTextField(name, 5);
            nameField.setToolTipText("端口名称");
            
            portField = new JTextField(port, 4);
            portField.setToolTipText("服务端口 (port)");
            
            targetPortField = new JTextField(targetPort, 4);
            targetPortField.setToolTipText("后端端口 (targetPort)");
            
            nodePortField = new JTextField(nodePort, 5);
            nodePortField.setToolTipText("NodePort (可选)");
            nodePortField.setEnabled(svcTypeCombo != null && "NodePort".equals(svcTypeCombo.getSelectedItem()));
            
            deleteBtn = new JButton("×");
            deleteBtn.setMargin(new Insets(2, 4, 2, 4));
            deleteBtn.setToolTipText("删除此端口组");
            deleteBtn.addActionListener(e -> {
                if (svcPortRows.size() > 1) {
                    svcPortRows.remove(this);
                    svcPortsContainer.remove(panel);
                    svcPortsContainer.revalidate();
                    svcPortsContainer.repaint();
                    triggerGenerate();
                } else {
                    UIUtils.info(deleteBtn, "至少保留一组端口配置。");
                }
            });
            
            // 实时变化监听
            DocumentListener dl = new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { triggerGenerate(); }
                public void removeUpdate(DocumentEvent e) { triggerGenerate(); }
                public void changedUpdate(DocumentEvent e) { triggerGenerate(); }
            };
            nameField.getDocument().addDocumentListener(dl);
            portField.getDocument().addDocumentListener(dl);
            targetPortField.getDocument().addDocumentListener(dl);
            nodePortField.getDocument().addDocumentListener(dl);
            protocolCombo.addActionListener(e -> triggerGenerate());

            panel.add(new JLabel("名称:"));
            panel.add(nameField);
            panel.add(protocolCombo);
            panel.add(new JLabel("Port:"));
            panel.add(portField);
            panel.add(new JLabel("Target:"));
            panel.add(targetPortField);
            panel.add(new JLabel("NodePort:"));
            panel.add(nodePortField);
            panel.add(deleteBtn);
        }
    }

    // ==================== Ingress 标签页 ====================
    private JComponent buildIngTab() {
        JPanel wrapper = new JPanel(new BorderLayout(4, 4));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        enableIngressCheck = new JCheckBox("启用 Ingress 路由配置", false);
        wrapper.add(enableIngressCheck, BorderLayout.NORTH);

        ingInnerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 6, 4, 6);

        addF(ingInnerPanel, c, 0, "绑定域名：", ingHostField = new JTextField("app.example.com"));

        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        ingInnerPanel.add(new JLabel("安全加密："), c);
        c.gridx = 1; c.weightx = 1.0;
        ingTlsCheck = new JCheckBox("启用 TLS (自动创建证书 Secret 关联)");
        ingInnerPanel.add(ingTlsCheck, c);

        // 默认未启用，禁用内层组件
        setContainerEnabled(ingInnerPanel, false);

        wrapper.add(ingInnerPanel, BorderLayout.CENTER);
        return wrapper;
    }

    // ==================== ConfigMap 标签页 ====================
    private JComponent buildCmTab() {
        JPanel wrapper = new JPanel(new BorderLayout(4, 4));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        enableCmCheck = new JCheckBox("启用 ConfigMap 配置项", false);
        wrapper.add(enableCmCheck, BorderLayout.NORTH);

        cmInnerPanel = new JPanel(new BorderLayout(6, 6));
        cmInnerPanel.add(new JLabel("配置数据项 (每行 key=value)："), BorderLayout.NORTH);
        cmArea = new JTextArea(10, 40);
        cmArea.setFont(UIUtils.monoFont());
        cmInnerPanel.add(new JScrollPane(cmArea), BorderLayout.CENTER);

        // 默认未启用，禁用内层组件
        setContainerEnabled(cmInnerPanel, false);

        wrapper.add(cmInnerPanel, BorderLayout.CENTER);
        return wrapper;
    }

    // ==================== 辅助 UI 组装 ====================
    private int addF(JPanel p, GridBagConstraints c, int row, String label, JComponent comp) {
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.gridwidth = 1;
        p.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1.0;
        p.add(comp, c);
        return row + 1;
    }

    private int addSection(JPanel p, GridBagConstraints c, int row, String label, JTextArea area) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 0;
        p.add(new JLabel(label), c);
        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1.0;
        area.setFont(UIUtils.monoFont());
        p.add(new JScrollPane(area), c);
        return row + 1;
    }

    /** 递归设置容器内所有组件的启用状态 */
    private void setContainerEnabled(Container container, boolean enabled) {
        if (container == null) return;
        for (Component c : container.getComponents()) {
            if (c instanceof Container) {
                setContainerEnabled((Container) c, enabled);
            }
            c.setEnabled(enabled);
        }
    }

    // ==================== 模板操作 ====================
    private void refreshCombo() {
        ignoreEvents = true;
        templateCombo.removeAllItems();
        templateCombo.addItem("— 自定义 —");
        for (String k : BUILTIN.keySet()) templateCombo.addItem(k);
        for (String k : CUSTOM.keySet()) templateCombo.addItem(k);
        templateCombo.setSelectedIndex(0);
        ignoreEvents = false;
    }

    private void applyTemplate() {
        String sel = (String) templateCombo.getSelectedItem();
        String[] v = BUILTIN.get(sel);
        if (v == null) v = CUSTOM.get(sel);
        if (v == null) return;

        ignoreEvents = true;
        nameField.setText(v[0]);
        nsField.setText("default");
        depImgField.setText(v[1]);
        depReplicas.setText(v[2]);
        depPort.setText(v[3]);
        deserializeSvcPorts(v[4]);
        svcTypeCombo.setSelectedItem(v[5]);
        depCpuR.setText(v[6]);
        depCpuL.setText(v[7]);
        depMemR.setText(v[8]);
        depMemL.setText(v[9]);
        depEnvArea.setText(v[10]);
        deserializeProbes(v[11]);
        cmArea.setText(v[12]);

        // 自动勾选并激活对应的资源
        enableDeploymentCheck.setSelected(true);
        setContainerEnabled(depInnerPanel, true);

        enableSvcCheck.setSelected(true);
        setContainerEnabled(svcInnerPanel, true);

        boolean hasIng = false; // 模板无域名，一般不默认启用 Ingress
        enableIngressCheck.setSelected(hasIng);
        setContainerEnabled(ingInnerPanel, hasIng);

        boolean hasCm = !v[12].trim().isEmpty();
        enableCmCheck.setSelected(hasCm);
        setContainerEnabled(cmInnerPanel, hasCm);

        ignoreEvents = false;
        updateNodePortVisibility();
        triggerGenerate();
    }

    private void saveTemplate() {
        String key = UIUtils.input(templateCombo, "请输入自定义模板名称：", nameField.getText().trim() + " (自定义)");
        if (key == null || key.trim().isEmpty()) return;
        key = key.trim();

        if (BUILTIN.containsKey(key)) {
            UIUtils.error(templateCombo, "无法覆盖内置模板，请尝试使用其他名称。");
            return;
        }
        CUSTOM.put(key, collectForm());
        saveCustomTemplates();
        refreshCombo();
        templateCombo.setSelectedItem(key);
    }

    private void deleteTemplate() {
        String sel = (String) templateCombo.getSelectedItem();
        if (sel == null || BUILTIN.containsKey(sel) || sel.equals("— 自定义 —")) {
            UIUtils.info(templateCombo, "只能删除自定义的模版。");
            return;
        }
        CUSTOM.remove(sel);
        saveCustomTemplates();
        refreshCombo();
    }

    private String[] collectForm() {
        return a(val(nameField), val(depImgField), val(depReplicas), val(depPort),
                serializeSvcPorts(), (String) svcTypeCombo.getSelectedItem(),
                val(depCpuR), val(depCpuL), val(depMemR), val(depMemL),
                depEnvArea.getText().trim(), serializeProbes(), cmArea.getText().trim());
    }

    private String serializeSvcPorts() {
        StringBuilder sb = new StringBuilder();
        for (SvcPortRow row : svcPortRows) {
            if (sb.length() > 0) sb.append(",");
            sb.append(row.protocolCombo.getSelectedItem()).append(":")
              .append(row.nameField.getText().trim().isEmpty() ? "port" : row.nameField.getText().trim()).append(":")
              .append(row.portField.getText().trim()).append(":")
              .append(row.targetPortField.getText().trim()).append(":")
              .append(row.nodePortField.getText().trim());
        }
        return sb.toString();
    }

    private void deserializeSvcPorts(String data) {
        svcPortRows.clear();
        svcPortsContainer.removeAll();

        if (data == null || data.trim().isEmpty()) {
            addPortRow("TCP", "http", "80", "80", "");
            return;
        }

        String[] items = data.split(",");
        for (String item : items) {
            String[] parts = item.split(":", 5);
            if (parts.length < 3) {
                // 向后兼容旧格式（单数字端口，例如 "80" 或 "3306"）
                String port = item.trim();
                if (port.matches("\\d+")) {
                    addPortRow("TCP", "http", port, port, "");
                }
                continue;
            }
            String protocol = parts[0];
            String name = parts[1];
            String port = parts[2];
            String targetPort = parts.length > 3 ? parts[3] : port;
            String nodePort = parts.length > 4 ? parts[4] : "";
            addPortRow(protocol, name, port, targetPort, nodePort);
        }

        if (svcPortRows.isEmpty()) {
            addPortRow("TCP", "http", "80", "80", "");
        }
        svcPortsContainer.revalidate();
        svcPortsContainer.repaint();
    }

    private String serializeProbes() {
        StringBuilder sb = new StringBuilder();
        if (livenessEnabledCheck.isSelected()) {
            sb.append("liveness:").append(livenessTypeCombo.getSelectedItem()).append(":")
              .append(livenessValueField.getText().trim()).append(":")
              .append(livenessDelayField.getText().trim()).append(":")
              .append(livenessPeriodField.getText().trim()).append(":")
              .append(livenessTimeoutField.getText().trim()).append("\n");
        }
        if (readinessEnabledCheck.isSelected()) {
            sb.append("readiness:").append(readinessTypeCombo.getSelectedItem()).append(":")
              .append(readinessValueField.getText().trim()).append(":")
              .append(readinessDelayField.getText().trim()).append(":")
              .append(readinessPeriodField.getText().trim()).append(":")
              .append(readinessTimeoutField.getText().trim()).append("\n");
        }
        if (startupEnabledCheck.isSelected()) {
            sb.append("startup:").append(startupTypeCombo.getSelectedItem()).append(":")
              .append(startupValueField.getText().trim()).append(":")
              .append(startupDelayField.getText().trim()).append(":")
              .append(startupPeriodField.getText().trim()).append(":")
              .append(startupTimeoutField.getText().trim()).append("\n");
        }
        return sb.toString().trim();
    }

    private void deserializeProbes(String probeStr) {
        livenessEnabledCheck.setSelected(false);
        readinessEnabledCheck.setSelected(false);
        startupEnabledCheck.setSelected(false);
        livenessValueField.setText("");
        readinessValueField.setText("");
        startupValueField.setText("");

        if (probeStr == null || probeStr.isEmpty()) return;
        for (String line : probeStr.split("\n")) {
            String[] parts = line.split(":", 6);
            if (parts.length < 3) {
                // 向后兼容旧格式
                if (line.contains("liveness")) {
                    livenessEnabledCheck.setSelected(true);
                    livenessTypeCombo.setSelectedItem("HTTP GET");
                    livenessValueField.setText(afterColon(line));
                } else if (line.contains("readiness")) {
                    readinessEnabledCheck.setSelected(true);
                    readinessTypeCombo.setSelectedItem("HTTP GET");
                    readinessValueField.setText(afterColon(line));
                }
                continue;
            }
            String probeType = parts[0].trim();
            String actionType = parts[1].trim();
            String value = parts[2].trim();
            String delay = parts.length > 3 ? parts[3].trim() : "10";
            String period = parts.length > 4 ? parts[4].trim() : "15";
            String timeout = parts.length > 5 ? parts[5].trim() : "5";

            if ("liveness".equals(probeType)) {
                livenessEnabledCheck.setSelected(true);
                livenessTypeCombo.setSelectedItem(actionType);
                livenessValueField.setText(value);
                livenessDelayField.setText(delay);
                livenessPeriodField.setText(period);
                livenessTimeoutField.setText(timeout);
            } else if ("readiness".equals(probeType)) {
                readinessEnabledCheck.setSelected(true);
                readinessTypeCombo.setSelectedItem(actionType);
                readinessValueField.setText(value);
                readinessDelayField.setText(delay);
                readinessPeriodField.setText(period);
                readinessTimeoutField.setText(timeout);
            } else if ("startup".equals(probeType)) {
                startupEnabledCheck.setSelected(true);
                startupTypeCombo.setSelectedItem(actionType);
                startupValueField.setText(value);
                startupDelayField.setText(delay);
                startupPeriodField.setText(period);
                startupTimeoutField.setText(timeout);
            }
        }
    }

    // ==================== 实时监听绑定 ====================
    private void bindRealtimeListeners() {
        // DocumentListeners
        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { triggerGenerate(); }
            public void removeUpdate(DocumentEvent e) { triggerGenerate(); }
            public void changedUpdate(DocumentEvent e) { triggerGenerate(); }
        };

        nameField.getDocument().addDocumentListener(dl);
        nsField.getDocument().addDocumentListener(dl);
        depImgField.getDocument().addDocumentListener(dl);
        depReplicas.getDocument().addDocumentListener(dl);
        depPort.getDocument().addDocumentListener(dl);
        depCpuR.getDocument().addDocumentListener(dl);
        depCpuL.getDocument().addDocumentListener(dl);
        depMemR.getDocument().addDocumentListener(dl);
        depMemL.getDocument().addDocumentListener(dl);
        depEnvArea.getDocument().addDocumentListener(dl);
        depLabelArea.getDocument().addDocumentListener(dl);
        livenessValueField.getDocument().addDocumentListener(dl);
        livenessDelayField.getDocument().addDocumentListener(dl);
        livenessPeriodField.getDocument().addDocumentListener(dl);
        livenessTimeoutField.getDocument().addDocumentListener(dl);
        
        readinessValueField.getDocument().addDocumentListener(dl);
        readinessDelayField.getDocument().addDocumentListener(dl);
        readinessPeriodField.getDocument().addDocumentListener(dl);
        readinessTimeoutField.getDocument().addDocumentListener(dl);

        startupValueField.getDocument().addDocumentListener(dl);
        startupDelayField.getDocument().addDocumentListener(dl);
        startupPeriodField.getDocument().addDocumentListener(dl);
        startupTimeoutField.getDocument().addDocumentListener(dl);

        ingHostField.getDocument().addDocumentListener(dl);
        cmArea.getDocument().addDocumentListener(dl);

        // ActionListeners
        depPullCombo.addActionListener(e -> triggerGenerate());
        livenessEnabledCheck.addActionListener(e -> triggerGenerate());
        livenessTypeCombo.addActionListener(e -> triggerGenerate());
        readinessEnabledCheck.addActionListener(e -> triggerGenerate());
        readinessTypeCombo.addActionListener(e -> triggerGenerate());
        startupEnabledCheck.addActionListener(e -> triggerGenerate());
        startupTypeCombo.addActionListener(e -> triggerGenerate());
        svcTypeCombo.addActionListener(e -> {
            updateNodePortVisibility();
            triggerGenerate();
        });
        ingTlsCheck.addActionListener(e -> triggerGenerate());
    }

    private void triggerGenerate() {
        if (ignoreEvents) return;
        doGenerate();
    }

    // ==================== YAML 生成与验证 ====================
    private void doGenerate() {
        String name = val(nameField);
        String ns = val(nsField);

        // 实时非阻塞错误收集
        java.util.List<String> errors = new java.util.ArrayList<>();

        if (name.isEmpty()) {
            errors.add("应用名称不能为空");
        }
        if (ns.isEmpty()) {
            errors.add("命名空间不能为空");
        }

        // 仅在相应模块启用时进行校验
        if (enableDeploymentCheck.isSelected()) {
            try {
                int r = Integer.parseInt(val(depReplicas));
                if (r < 0) errors.add("副本数必须大于或等于 0");
            } catch (NumberFormatException ex) {
                errors.add("副本数必须是有效整数");
            }
            try {
                int p = Integer.parseInt(val(depPort));
                if (p <= 0 || p > 65535) errors.add("容器端口范围必须在 1 ~ 65535 之间");
            } catch (NumberFormatException ex) {
                errors.add("容器端口必须是有效整数");
            }
        }

        if (enableSvcCheck.isSelected()) {
            for (SvcPortRow row : svcPortRows) {
                String portStr = row.portField.getText().trim();
                String targetPortStr = row.targetPortField.getText().trim();
                String nodePortStr = row.nodePortField.getText().trim();

                if (portStr.isEmpty()) {
                    errors.add("Service 端口不能为空");
                } else {
                    try {
                        int p = Integer.parseInt(portStr);
                        if (p <= 0 || p > 65535) errors.add("Service 端口 " + portStr + " 范围必须在 1 ~ 65535 之间");
                    } catch (NumberFormatException ex) {
                        errors.add("Service 端口必须是有效整数: " + portStr);
                    }
                }

                if (!targetPortStr.isEmpty()) {
                    try {
                        int p = Integer.parseInt(targetPortStr);
                        if (p <= 0 || p > 65535) errors.add("后端目标端口 " + targetPortStr + " 范围必须在 1 ~ 65535 之间");
                    } catch (NumberFormatException ex) {
                        errors.add("后端目标端口必须是有效整数: " + targetPortStr);
                    }
                }

                if ("NodePort".equals(svcTypeCombo.getSelectedItem()) && !nodePortStr.isEmpty()) {
                    try {
                        int p = Integer.parseInt(nodePortStr);
                        if (p <= 0 || p > 65535) errors.add("NodePort 端口 " + nodePortStr + " 范围必须在 1 ~ 65535 之间");
                    } catch (NumberFormatException ex) {
                        errors.add("NodePort 端口必须是有效整数: " + nodePortStr);
                    }
                }
            }
        }

        // 如果存在校验错误，以优雅注释形式显示在预览区，而不弹框中断操作
        if (!errors.isEmpty()) {
            StringBuilder errorText = new StringBuilder();
            errorText.append("# ==========================================\n");
            errorText.append("# ⚠️ 校验错误 (请修正配置以生成正确的 YAML)：\n");
            for (String err : errors) {
                errorText.append("#   - ").append(err).append("\n");
            }
            errorText.append("# ==========================================\n");
            outputArea.setText(errorText.toString());
            return;
        }

        // 开始拼接 YAML
        StringBuilder y = new StringBuilder();
        y.append("# Generated by Java-Toolbox K8s Generator\n");
        y.append("# ------------------------------------------\n\n");

        boolean hasContent = false;

        // 1. ConfigMap
        if (enableCmCheck.isSelected()) {
            String cm = buildConfigMap(name, ns);
            if (!cm.isEmpty()) {
                appendSep(y, cm);
                hasContent = true;
            }
        }

        // 2. Deployment
        if (enableDeploymentCheck.isSelected()) {
            String dep = buildDeploy(name, ns);
            if (!dep.isEmpty()) {
                appendSep(y, dep);
                hasContent = true;
            }
        }

        // 3. Service
        if (enableSvcCheck.isSelected()) {
            String svc = buildService(name, ns);
            if (!svc.isEmpty()) {
                appendSep(y, svc);
                hasContent = true;
            }
        }

        // 4. Ingress
        if (enableIngressCheck.isSelected()) {
            String ing = buildIngress(name, ns);
            if (!ing.isEmpty()) {
                appendSep(y, ing);
                hasContent = true;
            }
        }

        if (!hasContent) {
            outputArea.setText("# 没有启用任何资源或配置项为空");
        } else {
            outputArea.setText(y.toString());
        }
    }

    private void appendSep(StringBuilder y, String content) {
        if (content == null || content.isEmpty()) return;
        if (y.length() > 0 && !y.toString().endsWith("\n\n")) {
            if (y.toString().endsWith("\n")) y.append("\n");
            else y.append("\n\n");
        }
        y.append(content);
    }

    private String buildConfigMap(String name, String ns) {
        if (cmArea.getText().trim().isEmpty()) return "";
        StringBuilder y = new StringBuilder();
        y.append("apiVersion: v1\nkind: ConfigMap\nmetadata:\n");
        y.append("  name: ").append(name).append("-config\n  namespace: ").append(ns).append("\ndata:\n");
        for (String ln : cmArea.getText().trim().split("\n")) {
            if (ln.trim().isEmpty()) continue;
            int eq = ln.indexOf('=');
            if (eq > 0) {
                y.append("  ").append(ln.substring(0, eq).trim())
                        .append(": \"").append(ln.substring(eq + 1).trim()).append("\"\n");
            } else {
                y.append("  ").append(ln.trim()).append(": \"\"\n");
            }
        }
        return y.toString();
    }

    private String buildDeploy(String name, String ns) {
        StringBuilder y = new StringBuilder();
        y.append("apiVersion: apps/v1\nkind: Deployment\nmetadata:\n");
        y.append("  name: ").append(name).append("\n  namespace: ").append(ns).append("\n");
        y.append("  labels:\n    app: ").append(name).append("\n");
        for (String ln : lines(depLabelArea)) addLabel(y, ln, "    ");
        y.append("spec:\n  replicas: ").append(val(depReplicas)).append("\n");
        y.append("  selector:\n    matchLabels:\n      app: ").append(name).append("\n");
        y.append("  template:\n    metadata:\n      labels:\n        app: ").append(name).append("\n");
        for (String ln : lines(depLabelArea)) addLabel(y, ln, "        ");
        y.append("    spec:\n      containers:\n      - name: ").append(name).append("\n");
        y.append("        image: ").append(val(depImgField)).append("\n");
        y.append("        imagePullPolicy: ").append(depPullCombo.getSelectedItem()).append("\n");
        y.append("        ports:\n        - containerPort: ").append(val(depPort)).append("\n");

        // 健康检查
        appendProbeYaml(y, "livenessProbe", livenessEnabledCheck, livenessTypeCombo, livenessValueField, livenessDelayField, livenessPeriodField, livenessTimeoutField);
        appendProbeYaml(y, "readinessProbe", readinessEnabledCheck, readinessTypeCombo, readinessValueField, readinessDelayField, readinessPeriodField, readinessTimeoutField);
        appendProbeYaml(y, "startupProbe", startupEnabledCheck, startupTypeCombo, startupValueField, startupDelayField, startupPeriodField, startupTimeoutField);

        // 资源限制
        if (notEmpty(val(depCpuR)) || notEmpty(val(depCpuL)) || notEmpty(val(depMemR)) || notEmpty(val(depMemL))) {
            y.append("        resources:\n          requests:\n");
            if (notEmpty(val(depCpuR))) y.append("            cpu: ").append(val(depCpuR)).append("\n");
            if (notEmpty(val(depMemR))) y.append("            memory: ").append(val(depMemR)).append("\n");
            y.append("          limits:\n");
            if (notEmpty(val(depCpuL))) y.append("            cpu: ").append(val(depCpuL)).append("\n");
            if (notEmpty(val(depMemL))) y.append("            memory: ").append(val(depMemL)).append("\n");
        }

        // 环境变量
        String envTxt = depEnvArea.getText().trim();
        if (!envTxt.isEmpty()) {
            y.append("        env:\n");
            for (String ln : envTxt.split("\n")) {
                if (ln.trim().isEmpty()) continue;
                int eq = ln.indexOf('=');
                if (eq > 0) {
                    y.append("        - name: ").append(ln.substring(0, eq).trim())
                            .append("\n          value: ").append(yamlQuote(ln.substring(eq + 1).trim())).append("\n");
                }
            }
        }
        return y.toString();
    }

    private String buildService(String name, String ns) {
        StringBuilder y = new StringBuilder();
        y.append("apiVersion: v1\nkind: Service\nmetadata:\n");
        y.append("  name: ").append(name).append("-svc\n  namespace: ").append(ns).append("\n");
        y.append("  labels:\n    app: ").append(name).append("\n");
        y.append("spec:\n  type: ").append(svcTypeCombo.getSelectedItem()).append("\n");
        y.append("  selector:\n    app: ").append(name).append("\n");
        y.append("  ports:\n");
        for (SvcPortRow row : svcPortRows) {
            String pName = row.nameField.getText().trim();
            String port = row.portField.getText().trim();
            String target = row.targetPortField.getText().trim();
            String node = row.nodePortField.getText().trim();
            String proto = (String) row.protocolCombo.getSelectedItem();
            
            y.append("  - name: ").append(pName.isEmpty() ? "port" : pName).append("\n");
            y.append("    port: ").append(port).append("\n");
            y.append("    targetPort: ").append(target.isEmpty() ? port : target).append("\n");
            if ("NodePort".equals(svcTypeCombo.getSelectedItem()) && !node.isEmpty()) {
                y.append("    nodePort: ").append(node).append("\n");
            }
            y.append("    protocol: ").append(proto).append("\n");
        }
        return y.toString();
    }

    private void updateNodePortVisibility() {
        boolean isNodePort = svcTypeCombo != null && "NodePort".equals(svcTypeCombo.getSelectedItem());
        boolean parentEnabled = enableSvcCheck == null || enableSvcCheck.isSelected();
        if (svcPortRows != null) {
            for (SvcPortRow row : svcPortRows) {
                row.nodePortField.setEnabled(parentEnabled && isNodePort);
            }
        }
    }

    private String buildIngress(String name, String ns) {
        String host = val(ingHostField);
        if (host.isEmpty()) return "";
        StringBuilder y = new StringBuilder();
        y.append("apiVersion: networking.k8s.io/v1\nkind: Ingress\nmetadata:\n");
        y.append("  name: ").append(name).append("-ingress\n  namespace: ").append(ns).append("\n");
        y.append("spec:\n");
        if (ingTlsCheck.isSelected()) {
            y.append("  tls:\n  - hosts:\n    - ").append(host).append("\n");
            y.append("    secretName: ").append(name).append("-tls\n");
        }
        y.append("  rules:\n  - host: ").append(host).append("\n");
        y.append("    http:\n      paths:\n      - path: /\n        pathType: Prefix\n");
        y.append("        backend:\n          service:\n            name: ").append(name).append("-svc\n");
        y.append("            port:\n              number: ").append(svcPortRows.isEmpty() ? "80" : svcPortRows.get(0).portField.getText().trim()).append("\n");
        return y.toString();
    }

    // ==================== 辅助 ====================
    private static String afterColon(String ln) {
        int idx = ln.indexOf(':');
        return idx >= 0 ? ln.substring(idx + 1).trim() : ln.trim();
    }

    private static void addLabel(StringBuilder y, String ln, String indent) {
        String[] kv = ln.split("=", 2);
        if (kv.length == 2) {
            y.append(indent).append(kv[0].trim()).append(": ").append(kv[1].trim()).append("\n");
        }
    }

    private void appendProbeYaml(StringBuilder y, String probeName, JCheckBox enabledCheck, JComboBox<String> typeCombo, JTextField valField,
                                 JTextField delayField, JTextField periodField, JTextField timeoutField) {
        if (!enabledCheck.isSelected()) return;
        y.append("        ").append(probeName).append(":\n");
        String actionType = (String) typeCombo.getSelectedItem();
        String val = valField.getText().trim();
        String port = val(depPort);

        if ("HTTP GET".equals(actionType)) {
            y.append("          httpGet:\n");
            y.append("            path: ").append(val.isEmpty() ? "/health" : val).append("\n");
            y.append("            port: ").append(port).append("\n");
        } else if ("TCP Socket".equals(actionType)) {
            y.append("          tcpSocket:\n");
            y.append("            port: ").append(val.isEmpty() ? port : val).append("\n");
        } else if ("Exec Command".equals(actionType)) {
            y.append("          exec:\n");
            y.append("            command:\n");
            if (val.isEmpty()) {
                y.append("            - cat\n            - /tmp/healthy\n");
            } else {
                for (String arg : val.split("\\s+")) {
                    y.append("            - ").append(arg).append("\n");
                }
            }
        }

        if (notEmpty(val(delayField))) y.append("          initialDelaySeconds: ").append(val(delayField)).append("\n");
        if (notEmpty(val(periodField))) y.append("          periodSeconds: ").append(val(periodField)).append("\n");
        if (notEmpty(val(timeoutField))) y.append("          timeoutSeconds: ").append(val(timeoutField)).append("\n");
    }

    private static String yamlQuote(String val) {
        return val.matches(".*[\\{\\}\\[\\],&\\*\\?#|\\-<>=!%@:`].*") ? "\"" + val + "\"" : val;
    }

    private static String val(JTextField f) { return f.getText().trim(); }
    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }
    private static String[] lines(JTextArea a) {
        String t = a.getText();
        return (t == null || t.trim().isEmpty()) ? new String[0] : t.split("\n");
    }
}
