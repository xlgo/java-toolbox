package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kubernetes 部署文件生成面板。
 * <p>按资源类型分 4 个标签页：Deployment / Service / Ingress / ConfigMap，
 * 每个标签页独立配置和生成对应 YAML，支持一键合并所有资源。</p>
 */
public class K8sPanel extends ToolPanel {

    // ==================== 模板 ====================
    private static final Map<String, String[]> TEMPLATES = new LinkedHashMap<>();
    static {
        TEMPLATES.put("Web服务 (Nginx)", new String[]{"web-app","nginx:latest","2","80","80","ClusterIP","100m","500m","128Mi","256Mi","","",""});
        TEMPLATES.put("Java / Spring Boot", new String[]{"java-app","openjdk:17-jdk-slim","2","8080","8080","ClusterIP","500m","1000m","512Mi","1Gi","JAVA_OPTS=-Xmx512m\nSPRING_PROFILES_ACTIVE=prod","liveness: /actuator/health\nreadiness: /actuator/health",""});
        TEMPLATES.put("Node.js", new String[]{"node-app","node:18-alpine","2","3000","3000","ClusterIP","200m","500m","256Mi","512Mi","NODE_ENV=production","",""});
        TEMPLATES.put("前端 (Nginx SPA)", new String[]{"frontend","nginx:alpine","2","80","80","ClusterIP","50m","200m","64Mi","128Mi","","",""});
        TEMPLATES.put("MySQL", new String[]{"mysql","mysql:8.0","1","3306","3306","ClusterIP","500m","1000m","512Mi","1Gi","MYSQL_ROOT_PASSWORD=changeme\nMYSQL_DATABASE=appdb","",""});
        TEMPLATES.put("Redis", new String[]{"redis","redis:7-alpine","1","6379","6379","ClusterIP","200m","500m","128Mi","256Mi","","",""});
    }

    // ===== 共享参数 =====
    private JComboBox<String> templateCombo;
    private JTextField nameField, nsField;

    // ===== Deployment =====
    private JTextField depImgField, depReplicas, depPort, depCpuR, depCpuL, depMemR, depMemL;
    private JComboBox<String> depPullCombo;
    private JTextArea depEnvArea, depLabelArea, depProbeArea;

    // ===== Service =====
    private JTextField svcPortField, svcTargetField;
    private JComboBox<String> svcTypeCombo;

    // ===== Ingress =====
    private JTextField ingHostField;
    private JCheckBox ingTlsCheck;

    // ===== ConfigMap =====
    private JTextArea cmArea;

    // ===== 输出 =====
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

        // ===== 顶部：共享参数 =====
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        topBar.add(new JLabel("模板："));
        templateCombo = new JComboBox<>();
        templateCombo.addItem("— 自定义 —");
        for (String k : TEMPLATES.keySet()) templateCombo.addItem(k);
        topBar.add(templateCombo);
        topBar.add(new JLabel("  名称："));
        nameField = new JTextField(12);
        nameField.setText("my-app");
        topBar.add(nameField);
        topBar.add(new JLabel("命名空间："));
        nsField = new JTextField(8);
        nsField.setText("default");
        topBar.add(nsField);
        root.add(topBar, BorderLayout.NORTH);

        // ===== 中部：资源标签页 + 输出 =====
        JTabbedPane resourceTabs = new JTabbedPane();
        resourceTabs.addTab("Deployment", buildDeployTab());
        resourceTabs.addTab("Service", buildSvcTab());
        resourceTabs.addTab("Ingress", buildIngTab());
        resourceTabs.addTab("ConfigMap", buildCmTab());

        outputArea = new JTextArea();
        outputArea.setFont(UIUtils.monoFont());
        outputArea.setEditable(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, resourceTabs, new JScrollPane(outputArea));
        split.setResizeWeight(0.55);
        root.add(split, BorderLayout.CENTER);

        // ===== 底部：按钮 =====
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton genBtn = UIUtils.button("生成当前资源", 120);
        JButton genAllBtn = UIUtils.button("生成全部资源", 120);
        JButton copyBtn = UIUtils.button("复制 YAML", 90);
        btnRow.add(genBtn);
        btnRow.add(genAllBtn);
        btnRow.add(copyBtn);
        root.add(btnRow, BorderLayout.SOUTH);

        // ===== 事件 =====
        templateCombo.addActionListener(e -> applyTemplate());
        genBtn.addActionListener(e -> doGenerate(resourceTabs.getSelectedIndex()));
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
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 6, 2, 6);
        int row = 0;

        c.gridx=0; c.gridy=row; c.weightx=0; c.gridwidth=1;
        p.add(new JLabel("镜像："), c);
        c.gridx=1; c.weightx=1.0;
        depImgField = new JTextField("nginx:latest");
        p.add(depImgField, c);

        row++; c.gridx=0; c.gridy=row;
        p.add(new JLabel("拉取策略："), c);
        c.gridx=1;
        depPullCombo = new JComboBox<>(new String[]{"IfNotPresent","Always","Never"});
        p.add(depPullCombo, c);

        row++; c.gridx=0; c.gridy=row;
        p.add(new JLabel("副本数："), c);
        c.gridx=1;
        depReplicas = new JTextField("1");
        p.add(depReplicas, c);

        row++; c.gridx=0; c.gridy=row;
        p.add(new JLabel("容器端口："), c);
        c.gridx=1;
        depPort = new JTextField("80");
        p.add(depPort, c);

        row++; c.gridx=0; c.gridy=row;
        p.add(new JLabel("CPU 请求/上限："), c);
        c.gridx=1;
        JPanel cpuRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cpuRow.add(depCpuR = new JTextField("100m", 8));
        cpuRow.add(new JLabel(" / "));
        cpuRow.add(depCpuL = new JTextField("500m", 8));
        p.add(cpuRow, c);

        row++; c.gridx=0; c.gridy=row;
        p.add(new JLabel("内存 请求/上限："), c);
        c.gridx=1;
        JPanel memRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        memRow.add(depMemR = new JTextField("128Mi", 8));
        memRow.add(new JLabel(" / "));
        memRow.add(depMemL = new JTextField("256Mi", 8));
        p.add(memRow, c);

        row++; c.gridx=0; c.gridy=row; c.gridwidth=2;
        p.add(new JLabel("标签 (每行 key=value)", SwingConstants.LEFT), c);
        row++; c.gridwidth=1; c.weightx=1.0; c.gridwidth=2;
        depLabelArea = new JTextArea(2, 20);
        depLabelArea.setFont(UIUtils.monoFont());
        p.add(new JScrollPane(depLabelArea), c);

        row++; c.gridx=0; c.gridy=row; c.gridwidth=2;
        p.add(new JLabel("环境变量 (每行 KEY=VALUE)", SwingConstants.LEFT), c);
        row++; c.gridwidth=1; c.weightx=1.0; c.gridwidth=2;
        depEnvArea = new JTextArea(3, 20);
        depEnvArea.setFont(UIUtils.monoFont());
        p.add(new JScrollPane(depEnvArea), c);

        row++; c.gridx=0; c.gridy=row; c.gridwidth=2;
        p.add(new JLabel("健康检查 (liveness: /path / readiness: /path / exec: cmd)", SwingConstants.LEFT), c);
        row++; c.gridwidth=1; c.weightx=1.0; c.gridwidth=2;
        depProbeArea = new JTextArea(2, 20);
        depProbeArea.setFont(UIUtils.monoFont());
        p.add(new JScrollPane(depProbeArea), c);

        return new JScrollPane(p);
    }

    // ==================== Service 标签页 ====================
    private JComponent buildSvcTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 6, 4, 6);

        c.gridx=0; c.gridy=0; c.weightx=0;
        p.add(new JLabel("Service 端口："), c);
        c.gridx=1; c.weightx=1.0;
        svcPortField = new JTextField("80");
        p.add(svcPortField, c);

        c.gridx=2; c.weightx=0;
        p.add(new JLabel("目标端口："), c);
        c.gridx=3; c.weightx=1.0;
        svcTargetField = new JTextField("80");
        p.add(svcTargetField, c);

        c.gridx=0; c.gridy=1; c.weightx=0;
        p.add(new JLabel("Service 类型："), c);
        c.gridx=1; c.gridwidth=3; c.weightx=1.0;
        svcTypeCombo = new JComboBox<>(new String[]{"ClusterIP","NodePort","LoadBalancer"});
        p.add(svcTypeCombo, c);

        return p;
    }

    // ==================== Ingress 标签页 ====================
    private JComponent buildIngTab() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 6, 4, 6);

        c.gridx=0; c.gridy=0; c.weightx=0;
        p.add(new JLabel("域名 (Host)："), c);
        c.gridx=1; c.weightx=1.0;
        ingHostField = new JTextField("app.example.com");
        p.add(ingHostField, c);

        c.gridx=0; c.gridy=1;
        p.add(new JLabel("TLS："), c);
        c.gridx=1;
        ingTlsCheck = new JCheckBox("启用 TLS (默认证书)");
        p.add(ingTlsCheck, c);

        return p;
    }

    // ==================== ConfigMap 标签页 ====================
    private JComponent buildCmTab() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        p.add(new JLabel("每行 key=value，自动生成 ConfigMap 资源："), BorderLayout.NORTH);
        cmArea = new JTextArea(10, 40);
        cmArea.setFont(UIUtils.monoFont());
        p.add(new JScrollPane(cmArea), BorderLayout.CENTER);
        return p;
    }

    // ==================== 模板 ====================
    private void applyTemplate() {
        String sel = (String) templateCombo.getSelectedItem();
        if (sel == null || sel.startsWith("—")) return;
        String[] v = TEMPLATES.get(sel);
        if (v == null) return;
        nameField.setText(v[0]);
        depImgField.setText(v[1]);
        depReplicas.setText(v[2]);
        depPort.setText(v[3]);
        svcPortField.setText(v[4]);
        svcTargetField.setText(v[4]);
        svcTypeCombo.setSelectedItem(v[5]);
        depCpuR.setText(v[6]); depCpuL.setText(v[7]);
        depMemR.setText(v[8]); depMemL.setText(v[9]);
        depEnvArea.setText(v[10]);
        depProbeArea.setText(v[11]);
        cmArea.setText(v[12]);
        doGenerate(0);
    }

    // ==================== 生成 YAML ====================
    /** type: 0=Deploy, 1=Svc, 2=Ingress, 3=ConfigMap, -1=全部 */
    private void doGenerate(int type) {
        String name = nameField.getText().trim();
        String ns = nsField.getText().trim();
        if (name.isEmpty()) { outputArea.setText("请填写应用名称"); return; }

        StringBuilder y = new StringBuilder();
        boolean all = type == -1;

        if (all || type == 3) y.append(buildConfigMap(name, ns));
        if (all || type == 0) {
            if (y.length() > 0) y.append("---\n");
            y.append(buildDeploy(name, ns));
        }
        if (all || type == 1) {
            if (y.length() > 0) y.append("---\n");
            y.append(buildService(name, ns));
        }
        if (all || type == 2) {
            if (y.length() > 0) y.append("---\n");
            y.append(buildIngress(name, ns));
        }

        outputArea.setText(y.toString());
    }

    private String buildConfigMap(String name, String ns) {
        String txt = cmArea.getText().trim();
        if (txt.isEmpty()) return "";
        StringBuilder y = new StringBuilder();
        y.append("apiVersion: v1\nkind: ConfigMap\nmetadata:\n");
        y.append("  name: ").append(name).append("-config\n  namespace: ").append(ns).append("\ndata:\n");
        for (String ln : txt.split("\n")) {
            ln = ln.trim();
            if (ln.isEmpty()) continue;
            int eq = ln.indexOf('=');
            if (eq > 0) y.append("  ").append(ln.substring(0, eq).trim()).append(": \"")
                    .append(ln.substring(eq + 1).trim()).append("\"\n");
        }
        return y.toString();
    }

    private String buildDeploy(String name, String ns) {
        StringBuilder y = new StringBuilder();
        y.append("apiVersion: apps/v1\nkind: Deployment\nmetadata:\n");
        y.append("  name: ").append(name).append("\n  namespace: ").append(ns).append("\n");
        y.append("  labels:\n    app: ").append(name).append("\n");
        for (String ln : lines(depLabelArea)) {
            String[] kv = ln.split("=", 2);
            if (kv.length == 2) y.append("    ").append(kv[0].trim()).append(": ").append(kv[1].trim()).append("\n");
        }
        y.append("spec:\n  replicas: ").append(val(depReplicas)).append("\n");
        y.append("  selector:\n    matchLabels:\n      app: ").append(name).append("\n");
        y.append("  template:\n    metadata:\n      labels:\n        app: ").append(name).append("\n");
        for (String ln : lines(depLabelArea)) {
            String[] kv = ln.split("=", 2);
            if (kv.length == 2) y.append("        ").append(kv[0].trim()).append(": ").append(kv[1].trim()).append("\n");
        }
        y.append("    spec:\n      containers:\n      - name: ").append(name).append("\n");
        y.append("        image: ").append(val(depImgField)).append("\n");
        y.append("        imagePullPolicy: ").append(depPullCombo.getSelectedItem()).append("\n");
        y.append("        ports:\n        - containerPort: ").append(val(depPort)).append("\n");

        // probes
        for (String ln : lines(depProbeArea)) {
            ln = ln.trim();
            if (ln.startsWith("liveness:") || ln.startsWith("liveness：")) {
                String p = ln.substring(ln.indexOf(':') + 1).trim();
                y.append("        livenessProbe:\n");
                appendProbe(y, p, val(depPort));
            } else if (ln.startsWith("readiness:") || ln.startsWith("readiness：")) {
                String p = ln.substring(ln.indexOf(':') + 1).trim();
                y.append("        readinessProbe:\n");
                appendProbe(y, p, val(depPort));
            }
        }

        // resources
        if (notEmpty(val(depCpuR))||notEmpty(val(depCpuL))||notEmpty(val(depMemR))||notEmpty(val(depMemL))) {
            y.append("        resources:\n          requests:\n");
            if (notEmpty(val(depCpuR))) y.append("            cpu: ").append(val(depCpuR)).append("\n");
            if (notEmpty(val(depMemR))) y.append("            memory: ").append(val(depMemR)).append("\n");
            y.append("          limits:\n");
            if (notEmpty(val(depCpuL))) y.append("            cpu: ").append(val(depCpuL)).append("\n");
            if (notEmpty(val(depMemL))) y.append("            memory: ").append(val(depMemL)).append("\n");
        }

        // env
        String envTxt = depEnvArea.getText().trim();
        if (!envTxt.isEmpty()) {
            y.append("        env:\n");
            for (String ln : envTxt.split("\n")) {
                ln = ln.trim();
                if (ln.isEmpty()) continue;
                int eq = ln.indexOf('=');
                if (eq > 0) y.append("        - name: ").append(ln.substring(0, eq).trim())
                        .append("\n          value: \"").append(ln.substring(eq + 1).trim()).append("\"\n");
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
        String host = ingHostField.getText().trim();
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
    private static void appendProbe(StringBuilder y, String path, String port) {
        if (path.startsWith("/")) {
            y.append("          httpGet:\n            path: ").append(path).append("\n            port: ").append(port).append("\n");
        } else {
            y.append("          tcpSocket:\n            port: ").append(port).append("\n");
        }
        y.append("          initialDelaySeconds: 10\n          periodSeconds: 15\n");
    }

    private static String val(JTextField f) { return f.getText().trim(); }
    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }
    private static String[] lines(JTextArea a) {
        String t = a.getText();
        return (t == null || t.trim().isEmpty()) ? new String[0] : t.split("\n");
    }
}
