package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kubernetes 部署文件生成面板。左右分栏设计，支持实时生成与配置。
 */
public class K8sPanel extends ToolPanel {

    // ==================== 模板 ====================
    private static final Map<String, String[]> BUILTIN = new LinkedHashMap<>();
    private static final Map<String, String[]> CUSTOM = new LinkedHashMap<>();

    static {
        BUILTIN.put("Web服务 (Nginx)",     a("web-app","nginx:latest","2","80","80","ClusterIP","100m","500m","128Mi","256Mi","","",""));
        BUILTIN.put("Java / Spring Boot",  a("java-app","openjdk:17-jdk-slim","2","8080","8080","ClusterIP","500m","1000m","512Mi","1Gi","JAVA_OPTS=-Xmx512m\nSPRING_PROFILES_ACTIVE=prod","liveness: /actuator/health\nreadiness: /actuator/health",""));
        BUILTIN.put("Node.js",             a("node-app","node:18-alpine","2","3000","3000","ClusterIP","200m","500m","256Mi","512Mi","NODE_ENV=production","",""));
        BUILTIN.put("前端 (Nginx SPA)",    a("frontend","nginx:alpine","2","80","80","ClusterIP","50m","200m","64Mi","128Mi","","",""));
        BUILTIN.put("MySQL",               a("mysql","mysql:8.0","1","3306","3306","ClusterIP","500m","1000m","512Mi","1Gi","MYSQL_ROOT_PASSWORD=changeme\nMYSQL_DATABASE=appdb","",""));
        BUILTIN.put("Redis",               a("redis","redis:7-alpine","1","6379","6379","ClusterIP","200m","500m","128Mi","256Mi","","",""));
    }

    private static String[] a(String... v) { return v; }

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
    private JTextArea depEnvArea, depLabelArea, depProbeArea;

    // ====== Service ======
    private JTextField svcPortField, svcTargetField, svcNodePortField;
    private JComboBox<String> svcTypeCombo;
    private JLabel svcNodePortLabel;

    // ====== Ingress ======
    private JTextField ingHostField;
    private JCheckBox ingTlsCheck;

    // ====== ConfigMap ======
    private JTextArea cmArea;

    // ====== 输出 ======
    private JTextArea outputArea;

    public K8sPanel() {
        super("开发工具", "K8s 部署生成",
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
            depProbeArea.setText("");

            svcPortField.setText("80");
            svcTargetField.setText("80");
            svcNodePortField.setText("");
            svcTypeCombo.setSelectedIndex(0);

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
        row = addSection(depInnerPanel, c, row, "健康检查 (liveness:/health 或 tcp:$PORT)", depProbeArea = new JTextArea(2, 20));

        wrapper.add(new JScrollPane(depInnerPanel), BorderLayout.CENTER);
        return wrapper;
    }

    // ==================== Service 标签页 ====================
    private JComponent buildSvcTab() {
        JPanel wrapper = new JPanel(new BorderLayout(4, 4));
        wrapper.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        enableSvcCheck = new JCheckBox("启用 Service 服务配置", true);
        wrapper.add(enableSvcCheck, BorderLayout.NORTH);

        svcInnerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 6, 4, 6);

        addF(svcInnerPanel, c, 0, "Service 端口：", svcPortField = new JTextField("80"));
        addF(svcInnerPanel, c, 1, "后端目标端口：", svcTargetField = new JTextField("80"));
        addF(svcInnerPanel, c, 2, "服务类型 (Type)：", svcTypeCombo = new JComboBox<>(new String[]{"ClusterIP", "NodePort", "LoadBalancer"}));

        svcNodePortLabel = new JLabel("NodePort 端口 (可选)：");
        svcNodePortField = new JTextField("");
        c.gridx = 0; c.gridy = 3; c.weightx = 0; c.gridwidth = 1;
        svcInnerPanel.add(svcNodePortLabel, c);
        c.gridx = 1; c.weightx = 1.0;
        svcInnerPanel.add(svcNodePortField, c);

        updateNodePortVisibility();

        wrapper.add(svcInnerPanel, BorderLayout.CENTER);
        return wrapper;
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
        c.gridwidth = 2; c.weightx = 1.0;
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
        svcPortField.setText(v[4]);
        svcTargetField.setText(v[4]);
        svcTypeCombo.setSelectedItem(v[5]);
        depCpuR.setText(v[6]);
        depCpuL.setText(v[7]);
        depMemR.setText(v[8]);
        depMemL.setText(v[9]);
        depEnvArea.setText(v[10]);
        depProbeArea.setText(v[11]);
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

        svcNodePortField.setText("");
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
        refreshCombo();
    }

    private String[] collectForm() {
        return a(val(nameField), val(depImgField), val(depReplicas), val(depPort),
                val(svcPortField), (String) svcTypeCombo.getSelectedItem(),
                val(depCpuR), val(depCpuL), val(depMemR), val(depMemL),
                depEnvArea.getText().trim(), depProbeArea.getText().trim(), cmArea.getText().trim());
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
        depProbeArea.getDocument().addDocumentListener(dl);
        svcPortField.getDocument().addDocumentListener(dl);
        svcTargetField.getDocument().addDocumentListener(dl);
        svcNodePortField.getDocument().addDocumentListener(dl);
        ingHostField.getDocument().addDocumentListener(dl);
        cmArea.getDocument().addDocumentListener(dl);

        // ActionListeners
        depPullCombo.addActionListener(e -> triggerGenerate());
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
            try {
                int p = Integer.parseInt(val(svcPortField));
                if (p <= 0 || p > 65535) errors.add("Service 端口范围必须在 1 ~ 65535 之间");
            } catch (NumberFormatException ex) {
                errors.add("Service 端口必须是有效整数");
            }
            try {
                int p = Integer.parseInt(val(svcTargetField));
                if (p <= 0 || p > 65535) errors.add("后端目标端口范围必须在 1 ~ 65535 之间");
            } catch (NumberFormatException ex) {
                errors.add("后端目标端口必须是有效整数");
            }
            if ("NodePort".equals(svcTypeCombo.getSelectedItem()) && !val(svcNodePortField).isEmpty()) {
                try {
                    int p = Integer.parseInt(val(svcNodePortField));
                    if (p <= 0 || p > 65535) errors.add("NodePort 端口范围必须在 1 ~ 65535 之间");
                } catch (NumberFormatException ex) {
                    errors.add("NodePort 端口必须是有效整数");
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
        for (String ln : lines(depProbeArea)) {
            if (ln.startsWith("liveness:") || ln.startsWith("liveness：")) {
                y.append("        livenessProbe:\n");
                appendProbe(y, afterColon(ln), val(depPort));
            } else if (ln.startsWith("readiness:") || ln.startsWith("readiness：")) {
                y.append("        readinessProbe:\n");
                appendProbe(y, afterColon(ln), val(depPort));
            }
        }

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
        y.append("  ports:\n  - port: ").append(val(svcPortField)).append("\n");
        y.append("    targetPort: ").append(val(svcTargetField)).append("\n");
        if ("NodePort".equals(svcTypeCombo.getSelectedItem()) && !val(svcNodePortField).isEmpty()) {
            y.append("    nodePort: ").append(val(svcNodePortField)).append("\n");
        }
        y.append("    protocol: TCP\n    name: http\n");
        return y.toString();
    }

    private void updateNodePortVisibility() {
        boolean isNodePort = svcTypeCombo != null && "NodePort".equals(svcTypeCombo.getSelectedItem());
        boolean parentEnabled = enableSvcCheck == null || enableSvcCheck.isSelected();
        if (svcNodePortLabel != null) svcNodePortLabel.setEnabled(parentEnabled && isNodePort);
        if (svcNodePortField != null) svcNodePortField.setEnabled(parentEnabled && isNodePort);
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
        y.append("            port:\n              number: ").append(val(svcPortField)).append("\n");
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

    private static void appendProbe(StringBuilder y, String path, String port) {
        if (path.startsWith("tcp") || path.startsWith("TCP")) {
            y.append("          tcpSocket:\n            port: ").append(port).append("\n");
        } else {
            y.append("          httpGet:\n            path: ").append(path).append("\n            port: ").append(port).append("\n");
        }
        y.append("          initialDelaySeconds: 10\n          periodSeconds: 15\n");
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
