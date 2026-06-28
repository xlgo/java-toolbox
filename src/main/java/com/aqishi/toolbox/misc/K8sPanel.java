package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kubernetes 部署文件生成面板。按资源类型分 4 个标签页独立配置，
 * 支持模板编辑（新增/覆盖/删除自定义模板）。
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
    private boolean ignoreTemplateEvent;

    // ====== Deployment ======
    private JTextField depImgField, depReplicas, depPort, depCpuR, depCpuL, depMemR, depMemL;
    private JComboBox<String> depPullCombo;
    private JTextArea depEnvArea, depLabelArea, depProbeArea;

    // ====== Service ======
    private JTextField svcPortField, svcTargetField;
    private JComboBox<String> svcTypeCombo;

    // ====== Ingress ======
    private JTextField ingHostField;
    private JCheckBox ingTlsCheck;

    // ====== ConfigMap ======
    private JTextArea cmArea;

    // ====== 输出 ======
    private JTextArea outputArea;

    public K8sPanel() {
        super("开发工具", "K8s 部署生成",
                "K8s","Kubernetes","部署","YAML","容器",
                "Deployment","Service","Ingress","ConfigMap","编排");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：模板 + 共享参数 =====
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        topBar.add(new JLabel("模板："));
        templateCombo = new JComboBox<>();
        refreshCombo();
        topBar.add(templateCombo);
        JButton saveTmplBtn = new JButton("保存"); saveTmplBtn.setToolTipText("保存为模板");
        JButton delTmplBtn = new JButton("删除"); delTmplBtn.setToolTipText("删除自定义模板");
        topBar.add(saveTmplBtn); topBar.add(delTmplBtn);
        topBar.add(new JLabel("  名称："));
        nameField = new JTextField(10); nameField.setText("my-app"); topBar.add(nameField);
        topBar.add(new JLabel("命名空间："));
        nsField = new JTextField(8); nsField.setText("default"); topBar.add(nsField);
        root.add(topBar, BorderLayout.NORTH);

        // ===== 中部 =====
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Deployment", buildDeployTab());
        tabs.addTab("Service", buildSvcTab());
        tabs.addTab("Ingress", buildIngTab());
        tabs.addTab("ConfigMap", buildCmTab());

        outputArea = new JTextArea();
        outputArea.setFont(UIUtils.monoFont());
        outputArea.setEditable(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, new JScrollPane(outputArea));
        split.setResizeWeight(0.55);
        root.add(split, BorderLayout.CENTER);

        // ===== 底部 =====
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton genBtn = new JButton("生成当前资源"); JButton genAllBtn = new JButton("生成全部资源");
        JButton copyBtn = new JButton("复制 YAML");
        btnRow.add(genBtn); btnRow.add(genAllBtn); btnRow.add(copyBtn);
        root.add(btnRow, BorderLayout.SOUTH);

        // ===== 事件 =====
        templateCombo.addActionListener(e -> { if (!ignoreTemplateEvent) applyTemplate(); });
        saveTmplBtn.addActionListener(e -> saveTemplate());
        delTmplBtn.addActionListener(e -> deleteTemplate());
        genBtn.addActionListener(e -> doGenerate(tabs.getSelectedIndex()));
        genAllBtn.addActionListener(e -> doGenerate(-1));
        copyBtn.addActionListener(e -> {
            String t = outputArea.getText();
            if (!t.isEmpty()) { UIUtils.copyToClipboard(t); UIUtils.info(root, "YAML 已复制。"); }
        });

        doGenerate(0);
        return root;
    }

    // ==================== Deployment 标签页 ====================
    private JComponent buildDeployTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(2, 4, 2, 4);
        int row = 0;

        row = addF(p, c, row, "镜像：", depImgField = new JTextField("nginx:latest"));
        row = addF(p, c, row, "拉取策略：", depPullCombo = new JComboBox<>(new String[]{"IfNotPresent","Always","Never"}));
        row = addF(p, c, row, "副本数：", depReplicas = new JTextField("1"));
        row = addF(p, c, row, "容器端口：", depPort = new JTextField("80"));
        row = addF(p, c, row, "CPU 请求：", depCpuR = new JTextField("100m"));
        row = addF(p, c, row, "CPU 上限：", depCpuL = new JTextField("500m"));
        row = addF(p, c, row, "内存请求：", depMemR = new JTextField("128Mi"));
        row = addF(p, c, row, "内存上限：", depMemL = new JTextField("256Mi"));

        row = addSection(p, c, row, "标签 (每行 key=value, 可选)", depLabelArea = new JTextArea(2, 20));
        row = addSection(p, c, row, "环境变量 (每行 KEY=VALUE)", depEnvArea = new JTextArea(3, 20));
        row = addSection(p, c, row, "健康检查 (liveness:/health 或 tcp:$PORT)", depProbeArea = new JTextArea(2, 20));

        return new JScrollPane(p);
    }

    private int addF(JPanel p, GridBagConstraints c, int row, String label, JComponent comp) {
        c.gridx=0; c.gridy=row; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel(label), c);
        c.gridx=1; c.weightx=1.0;
        p.add(comp, c);
        return row + 1;
    }

    private int addSection(JPanel p, GridBagConstraints c, int row, String label, JTextArea area) {
        c.gridx=0; c.gridy=row; c.gridwidth=2; c.weightx=0;
        p.add(new JLabel(label), c);
        row++; c.gridwidth=2; c.weightx=1.0;
        area.setFont(UIUtils.monoFont());
        p.add(new JScrollPane(area), c);
        return row + 1;
    }

    // ==================== Service ====================
    private JComponent buildSvcTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(4, 6, 4, 6);
        addF(p, c, 0, "Service 端口：", svcPortField = new JTextField("80"));
        addF(p, c, 1, "目标端口：", svcTargetField = new JTextField("80"));
        addF(p, c, 2, "Service 类型：", svcTypeCombo = new JComboBox<>(new String[]{"ClusterIP","NodePort","LoadBalancer"}));
        return p;
    }

    // ==================== Ingress ====================
    private JComponent buildIngTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(4, 6, 4, 6);
        addF(p, c, 0, "域名：", ingHostField = new JTextField("app.example.com"));
        c.gridx=0; c.gridy=1; c.weightx=0;
        p.add(new JLabel("TLS："), c); c.gridx=1; c.weightx=1.0;
        ingTlsCheck = new JCheckBox("启用 TLS (自动创建 secretName)"); p.add(ingTlsCheck, c);
        return p;
    }

    // ==================== ConfigMap ====================
    private JComponent buildCmTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        p.add(new JLabel("每行 key=value，自动生成 ConfigMap："), BorderLayout.NORTH);
        cmArea = new JTextArea(10, 40); cmArea.setFont(UIUtils.monoFont());
        p.add(new JScrollPane(cmArea), BorderLayout.CENTER);
        return p;
    }

    // ==================== 模板操作 ====================
    private void refreshCombo() {
        ignoreTemplateEvent = true;
        templateCombo.removeAllItems();
        templateCombo.addItem("— 自定义 —");
        for (String k : BUILTIN.keySet()) templateCombo.addItem(k);
        for (String k : CUSTOM.keySet()) templateCombo.addItem(k);
        templateCombo.setSelectedIndex(0);
        ignoreTemplateEvent = false;
    }

    private void applyTemplate() {
        String sel = (String) templateCombo.getSelectedItem();
        String[] v = BUILTIN.get(sel);
        if (v == null) v = CUSTOM.get(sel);
        if (v == null) return;
        nameField.setText(v[0]); nsField.setText("default");
        depImgField.setText(v[1]); depReplicas.setText(v[2]);
        depPort.setText(v[3]); svcPortField.setText(v[4]); svcTargetField.setText(v[4]);
        svcTypeCombo.setSelectedItem(v[5]);
        depCpuR.setText(v[6]); depCpuL.setText(v[7]);
        depMemR.setText(v[8]); depMemL.setText(v[9]);
        depEnvArea.setText(v[10]); depProbeArea.setText(v[11]); cmArea.setText(v[12]);
        doGenerate(0);
    }

    private void saveTemplate() {
        String key = UIUtils.input(templateCombo, "输入模板名称：", nameField.getText().trim() + " (自定义)");
        if (key == null || key.trim().isEmpty()) return;
        key = key.trim();

        // 不允许覆盖内置模板
        if (BUILTIN.containsKey(key)) {
            UIUtils.error(templateCombo, "模板名称与内置模板冲突，请换一个名字。");
            return;
        }
        CUSTOM.put(key, collectForm());
        refreshCombo();
        templateCombo.setSelectedItem(key);
    }

    private void deleteTemplate() {
        String sel = (String) templateCombo.getSelectedItem();
        if (sel == null || BUILTIN.containsKey(sel)) {
            UIUtils.info(templateCombo, "只能删除自定义模板。");
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

    // ==================== 生成 YAML ====================
    private void doGenerate(int type) {
        String name = val(nameField);
        String ns = val(nsField);
        if (name.isEmpty()) { outputArea.setText("请填写应用名称"); return; }

        // 校验数值字段
        try { int r = Integer.parseInt(val(depReplicas)); if (r <= 0) throw new NumberFormatException(); }
        catch (NumberFormatException ex) { outputArea.setText("副本数必须为正整数"); return; }
        try { int p = Integer.parseInt(val(depPort)); if (p <= 0 || p > 65535) throw new NumberFormatException(); }
        catch (NumberFormatException ex) { outputArea.setText("容器端口必须为 1~65535 的整数"); return; }
        try { int p = Integer.parseInt(val(svcPortField)); if (p <= 0 || p > 65535) throw new NumberFormatException(); }
        catch (NumberFormatException ex) { outputArea.setText("Service 端口必须为 1~65535 的整数"); return; }
        try { int p = Integer.parseInt(val(svcTargetField)); if (p <= 0 || p > 65535) throw new NumberFormatException(); }
        catch (NumberFormatException ex) { outputArea.setText("目标端口必须为 1~65535 的整数"); return; }

        StringBuilder y = new StringBuilder();
        boolean all = type == -1;

        if (all || type == 3) { String s = buildConfigMap(name, ns); if (!s.isEmpty()) appendSep(y, "# --- ConfigMap ---\n" + s); }
        if (all || type == 0) { String s = buildDeploy(name, ns);   if (!s.isEmpty()) appendSep(y, "# --- Deployment ---\n" + s); }
        if (all || type == 1) { String s = buildService(name, ns);  if (!s.isEmpty()) appendSep(y, "# --- Service ---\n" + s); }
        if (all || type == 2) { String s = buildIngress(name, ns);  if (!s.isEmpty()) appendSep(y, "# --- Ingress ---\n" + s); }

        outputArea.setText(y.toString());
    }

    /** 如果 content 非空则追加到 y，前面有内容时加 --- 分隔 */
    private void appendSep(StringBuilder y, String content) {
        if (content == null || content.isEmpty()) return;
        if (y.length() > 0) y.append("---\n");
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
            if (eq > 0) y.append("  ").append(ln.substring(0, eq).trim())
                    .append(": \"").append(ln.substring(eq + 1).trim()).append("\"\n");
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
            if (ln.startsWith("liveness:")||ln.startsWith("liveness：")) {
                y.append("        livenessProbe:\n"); appendProbe(y, afterColon(ln), val(depPort));
            } else if (ln.startsWith("readiness:")||ln.startsWith("readiness：")) {
                y.append("        readinessProbe:\n"); appendProbe(y, afterColon(ln), val(depPort));
            }
        }

        // 资源限制
        if (notEmpty(val(depCpuR))||notEmpty(val(depCpuL))||notEmpty(val(depMemR))||notEmpty(val(depMemL))) {
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
                if (eq > 0) y.append("        - name: ").append(ln.substring(0, eq).trim())
                        .append("\n          value: ").append(yamlQuote(ln.substring(eq + 1).trim())).append("\n");
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
        y.append("    protocol: TCP\n    name: http\n");
        return y.toString();
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
        if (kv.length == 2) y.append(indent).append(kv[0].trim()).append(": ").append(kv[1].trim()).append("\n");
    }

    private static void appendProbe(StringBuilder y, String path, String port) {
        if (path.startsWith("tcp")||path.startsWith("TCP")) {
            y.append("          tcpSocket:\n            port: ").append(port).append("\n");
        } else {
            y.append("          httpGet:\n            path: ").append(path).append("\n            port: ").append(port).append("\n");
        }
        y.append("          initialDelaySeconds: 10\n          periodSeconds: 15\n");
    }

    /** 智能引号：包含特殊字符的值加引号 */
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
