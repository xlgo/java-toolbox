package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kubernetes 部署文件生成面板。
 * <p>支持配置模板、Deployment / Service / Ingress / ConfigMap 生成。</p>
 */
public class K8sPanel extends ToolPanel {

    // ==================== 模板预设 ====================
    private static final Map<String, String[]> TEMPLATES = new LinkedHashMap<>();

    static {
        TEMPLATES.put("Web服务 (Nginx)", new String[]{"web-app", "default", "nginx:latest", "2", "80", "80", "ClusterIP", "100m", "500m", "128Mi", "256Mi", "", ""});
        TEMPLATES.put("Java / Spring Boot", new String[]{"java-app", "default", "openjdk:17-jdk-slim", "2", "8080", "8080", "ClusterIP", "500m", "1000m", "512Mi", "1Gi", "JAVA_OPTS=-Xmx512m\nSPRING_PROFILES_ACTIVE=prod", "liveness: /actuator/health\nreadiness: /actuator/health"});
        TEMPLATES.put("Node.js", new String[]{"node-app", "default", "node:18-alpine", "2", "3000", "3000", "ClusterIP", "200m", "500m", "256Mi", "512Mi", "NODE_ENV=production", ""});
        TEMPLATES.put("前端 (Nginx SPA)", new String[]{"frontend", "default", "nginx:alpine", "2", "80", "80", "ClusterIP", "50m", "200m", "64Mi", "128Mi", "", ""});
        TEMPLATES.put("MySQL", new String[]{"mysql", "default", "mysql:8.0", "1", "3306", "3306", "ClusterIP", "500m", "1000m", "512Mi", "1Gi", "MYSQL_ROOT_PASSWORD=changeme\nMYSQL_DATABASE=appdb", ""});
        TEMPLATES.put("Redis", new String[]{"redis", "default", "redis:7-alpine", "1", "6379", "6379", "ClusterIP", "200m", "500m", "128Mi", "256Mi", "", ""});
    }

    // ==================== 表单组件 ====================
    private JComboBox<String> templateCombo;
    private JTextField nameField, namespaceField, imageField, replicasField;
    private JTextField containerPortField, servicePortField;
    private JComboBox<String> serviceTypeCombo, imagePullCombo;
    private JTextField cpuRequestField, cpuLimitField, memRequestField, memLimitField;
    private JTextArea envArea, labelArea, probeArea, configMapArea;
    private JTextField ingressHostField;
    private JTextArea outputArea;

    public K8sPanel() {
        super("开发工具", "K8s 部署生成",
                "K8s", "Kubernetes", "部署", "YAML", "容器",
                "Deployment", "Service", "Ingress", "ConfigMap",
                "Pod", "k8s", "编排", "模板");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ========== 左侧：表单 ==========
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("配置参数"));
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 6, 2, 6);

        int row = 0;

        // ---- 模板 ----
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.gridwidth = 1;
        form.add(new JLabel("配置模板："), c);
        c.gridx = 1; c.weightx = 1.0;
        templateCombo = new JComboBox<>(TEMPLATES.keySet().toArray(new String[0]));
        templateCombo.insertItemAt("— 自定义 —", 0);
        templateCombo.setSelectedIndex(0);
        form.add(templateCombo, c);

        row++;
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.gridwidth = 1;
        form.add(new JLabel("应用名称："), c);
        c.gridx = 1; c.weightx = 1.0;
        nameField = new JTextField("my-app");
        form.add(nameField, c);

        row++;
        form.add(new JLabel("命名空间："), c);
        c.gridx = 1;
        namespaceField = new JTextField("default");
        form.add(namespaceField, c);

        row++;
        c.gridx = 0;
        form.add(new JLabel("镜像："), c);
        c.gridx = 1;
        imageField = new JTextField("nginx:latest");
        form.add(imageField, c);

        row++;
        c.gridx = 0;
        form.add(new JLabel("镜像拉取策略："), c);
        c.gridx = 1;
        imagePullCombo = new JComboBox<>(new String[]{"IfNotPresent", "Always", "Never"});
        form.add(imagePullCombo, c);

        row++;
        c.gridx = 0;
        form.add(new JLabel("副本数："), c);
        c.gridx = 1;
        replicasField = new JTextField("1");
        form.add(replicasField, c);

        row++;
        c.gridx = 0;
        form.add(new JLabel("容器端口："), c);
        c.gridx = 1;
        containerPortField = new JTextField("80");
        form.add(containerPortField, c);

        row++;
        form.add(new JLabel("Service 端口："), c);
        c.gridx = 1;
        servicePortField = new JTextField("80");
        form.add(servicePortField, c);

        row++;
        c.gridx = 0;
        form.add(new JLabel("Service 类型："), c);
        c.gridx = 1;
        serviceTypeCombo = new JComboBox<>(new String[]{"ClusterIP", "NodePort", "LoadBalancer"});
        form.add(serviceTypeCombo, c);

        // ---- 资源限制 ----
        row++;
        c.gridx = 0; c.gridwidth = 2;
        form.add(new JLabel("— 资源限制 (可选) —"), c);
        row++; c.gridwidth = 1; c.gridx = 0;
        form.add(new JLabel("CPU 请求："), c);
        c.gridx = 1;
        cpuRequestField = new JTextField("100m");
        form.add(cpuRequestField, c);

        row++; c.gridx = 0;
        form.add(new JLabel("CPU 上限："), c);
        c.gridx = 1;
        cpuLimitField = new JTextField("500m");
        form.add(cpuLimitField, c);

        row++; c.gridx = 0;
        form.add(new JLabel("内存请求："), c);
        c.gridx = 1;
        memRequestField = new JTextField("128Mi");
        form.add(memRequestField, c);

        row++; c.gridx = 0;
        form.add(new JLabel("内存上限："), c);
        c.gridx = 1;
        memLimitField = new JTextField("256Mi");
        form.add(memLimitField, c);

        // ---- 标签 ----
        row++;
        c.gridx = 0; c.gridwidth = 2;
        form.add(new JLabel("— 额外标签 (每行 key=value, 可选) —"), c);
        row++; c.gridwidth = 1; c.weightx = 1.0; c.gridwidth = 2;
        labelArea = new JTextArea(2, 20);
        labelArea.setFont(UIUtils.monoFont());
        form.add(new JScrollPane(labelArea), c);

        // ---- 环境变量 ----
        row++;
        c.gridx = 0; c.gridwidth = 2;
        form.add(new JLabel("— 环境变量 (每行 KEY=VALUE) —"), c);
        row++; c.gridwidth = 1; c.weightx = 1.0; c.gridwidth = 2;
        envArea = new JTextArea(3, 20);
        envArea.setFont(UIUtils.monoFont());
        form.add(new JScrollPane(envArea), c);

        // ---- 健康检查 ----
        row++;
        c.gridx = 0; c.gridwidth = 2;
        form.add(new JLabel("— 健康检查探针 (可选) —"), c);
        row++; c.gridwidth = 1; c.weightx = 1.0; c.gridwidth = 2;
        JLabel hint = new JLabel("格式: liveness: /path 或 readiness: /path 或 exec: command");
        hint.setFont(UIUtils.plainFont().deriveFont(11f));
        form.add(hint, c);
        row++;
        probeArea = new JTextArea(2, 20);
        probeArea.setFont(UIUtils.monoFont());
        form.add(new JScrollPane(probeArea), c);

        // ---- Ingress ----
        row++;
        c.gridx = 0; c.gridwidth = 2;
        form.add(new JLabel("— Ingress (可选) —"), c);
        row++; c.gridwidth = 1; c.gridx = 0;
        form.add(new JLabel("域名 (Host)："), c);
        c.gridx = 1;
        ingressHostField = new JTextField();
        form.add(ingressHostField, c);

        // ---- ConfigMap ----
        row++;
        c.gridx = 0; c.gridwidth = 2;
        form.add(new JLabel("— ConfigMap (每行 key=value, 可选) —"), c);
        row++; c.gridwidth = 1; c.weightx = 1.0; c.gridwidth = 2;
        configMapArea = new JTextArea(3, 20);
        configMapArea.setFont(UIUtils.monoFont());
        form.add(new JScrollPane(configMapArea), c);

        // ---- 操作按钮 ----
        row++;
        c.gridx = 0; c.gridy = row; c.gridwidth = 2;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton genBtn = UIUtils.button("生成 YAML", 100);
        JButton copyBtn = UIUtils.button("复制 YAML", 100);
        btnPanel.add(genBtn);
        btnPanel.add(copyBtn);
        form.add(btnPanel, c);

        JScrollPane formScroll = new JScrollPane(form);
        formScroll.setBorder(null);

        // ========== 右侧：输出 ==========
        outputArea = new JTextArea();
        outputArea.setFont(UIUtils.monoFont());
        outputArea.setEditable(false);
        JScrollPane outScroll = UIUtils.scrollText(outputArea, "生成的 Kubernetes YAML");

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, formScroll, outScroll);
        split.setResizeWeight(0.4);
        root.add(split, BorderLayout.CENTER);

        // ========== 事件 ==========
        templateCombo.addActionListener(e -> applyTemplate());
        genBtn.addActionListener(e -> doGenerate());
        copyBtn.addActionListener(e -> {
            String t = outputArea.getText();
            if (!t.isEmpty()) {
                UIUtils.copyToClipboard(t);
                UIUtils.info(root, "YAML 已复制到剪贴板。");
            }
        });

        doGenerate();
        return root;
    }

    /** 应用模板预设 */
    private void applyTemplate() {
        String sel = (String) templateCombo.getSelectedItem();
        if (sel == null || sel.startsWith("—")) return;
        String[] v = TEMPLATES.get(sel);
        if (v == null) return;
        nameField.setText(v[0]);
        namespaceField.setText(v[1]);
        imageField.setText(v[2]);
        replicasField.setText(v[3]);
        containerPortField.setText(v[4]);
        servicePortField.setText(v[5]);
        serviceTypeCombo.setSelectedItem(v[6]);
        cpuRequestField.setText(v[7]);
        cpuLimitField.setText(v[8]);
        memRequestField.setText(v[9]);
        memLimitField.setText(v[10]);
        envArea.setText(v[11]);
        probeArea.setText(v[12]);
        doGenerate();
    }

    /** 生成 YAML */
    private void doGenerate() {
        String name = val(nameField);
        String ns = val(namespaceField);
        String image = val(imageField);
        String replicas = val(replicasField);
        String svcPort = val(servicePortField);
        String containerPort = val(containerPortField);
        String svcType = (String) serviceTypeCombo.getSelectedItem();
        String pullPolicy = (String) imagePullCombo.getSelectedItem();
        String ingressHost = val(ingressHostField);

        StringBuilder y = new StringBuilder();

        // ========== ConfigMap ==========
        String cmText = configMapArea.getText().trim();
        if (!cmText.isEmpty()) {
            y.append("apiVersion: v1\nkind: ConfigMap\nmetadata:\n");
            y.append("  name: ").append(name).append("-config\n");
            y.append("  namespace: ").append(ns).append("\ndata:\n");
            for (String line : cmText.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    y.append("  ").append(key).append(": \"").append(val).append("\"\n");
                }
            }
            y.append("---\n");
        }

        // ========== Deployment ==========
        y.append("apiVersion: apps/v1\nkind: Deployment\nmetadata:\n");
        y.append("  name: ").append(name).append("\n  namespace: ").append(ns).append("\n");
        y.append("  labels:\n    app: ").append(name).append("\n");
        // 额外标签
        for (String ln : lines(labelArea)) {
            String[] kv = ln.split("=", 2);
            if (kv.length == 2) y.append("    ").append(kv[0].trim()).append(": ").append(kv[1].trim()).append("\n");
        }
        y.append("spec:\n  replicas: ").append(replicas).append("\n");
        y.append("  selector:\n    matchLabels:\n      app: ").append(name).append("\n");
        y.append("  template:\n    metadata:\n      labels:\n        app: ").append(name).append("\n");
        for (String ln : lines(labelArea)) {
            String[] kv = ln.split("=", 2);
            if (kv.length == 2) y.append("        ").append(kv[0].trim()).append(": ").append(kv[1].trim()).append("\n");
        }
        y.append("    spec:\n      containers:\n      - name: ").append(name).append("\n");
        y.append("        image: ").append(image).append("\n");
        y.append("        imagePullPolicy: ").append(pullPolicy).append("\n");
        y.append("        ports:\n        - containerPort: ").append(containerPort).append("\n");

        // 健康检查
        String probes = probeArea.getText().trim();
        if (!probes.isEmpty()) {
            for (String ln : probes.split("\n")) {
                ln = ln.trim();
                if (ln.isEmpty()) continue;
                if (ln.startsWith("liveness:") || ln.startsWith("liveness：")) {
                    String p = ln.substring(ln.indexOf(':') + 1).trim();
                    y.append("        livenessProbe:\n");
                    appendProbe(y, p, containerPort);
                } else if (ln.startsWith("readiness:") || ln.startsWith("readiness：")) {
                    String p = ln.substring(ln.indexOf(':') + 1).trim();
                    y.append("        readinessProbe:\n");
                    appendProbe(y, p, containerPort);
                } else if (ln.startsWith("exec:") || ln.startsWith("exec：")) {
                    String cmd = ln.substring(ln.indexOf(':') + 1).trim();
                    y.append("        livenessProbe:\n          exec:\n            command:\n");
                    for (String part : cmd.split("\\s+")) {
                        y.append("            - ").append(part).append("\n");
                    }
                    y.append("          initialDelaySeconds: 5\n          periodSeconds: 10\n");
                }
            }
        }

        // 资源限制
        String cpuR = val(cpuRequestField), cpuL = val(cpuLimitField);
        String memR = val(memRequestField), memL = val(memLimitField);
        if (notEmpty(cpuR) || notEmpty(cpuL) || notEmpty(memR) || notEmpty(memL)) {
            y.append("        resources:\n          requests:\n");
            if (notEmpty(cpuR)) y.append("            cpu: ").append(cpuR).append("\n");
            if (notEmpty(memR)) y.append("            memory: ").append(memR).append("\n");
            y.append("          limits:\n");
            if (notEmpty(cpuL)) y.append("            cpu: ").append(cpuL).append("\n");
            if (notEmpty(memL)) y.append("            memory: ").append(memL).append("\n");
        }

        // 环境变量
        String envText = envArea.getText().trim();
        boolean hasCm = !cmText.isEmpty();
        if (!envText.isEmpty() || hasCm) {
            y.append("        env:\n");
            for (String ln : envText.split("\n")) {
                ln = ln.trim();
                if (ln.isEmpty()) continue;
                int eq = ln.indexOf('=');
                if (eq > 0) {
                    y.append("        - name: ").append(ln.substring(0, eq).trim()).append("\n");
                    y.append("          value: \"").append(ln.substring(eq + 1).trim()).append("\"\n");
                }
            }
            if (hasCm) {
                y.append("        - name: CONFIG_FILE\n          valueFrom:\n            configMapKeyRef:\n");
                y.append("              name: ").append(name).append("-config\n              key: config.yaml\n");
            }
        }

        // ========== Service ==========
        y.append("---\napiVersion: v1\nkind: Service\nmetadata:\n");
        y.append("  name: ").append(name).append("-svc\n  namespace: ").append(ns).append("\n");
        y.append("  labels:\n    app: ").append(name).append("\n");
        y.append("spec:\n  type: ").append(svcType).append("\n  selector:\n    app: ").append(name).append("\n");
        y.append("  ports:\n  - port: ").append(svcPort).append("\n    targetPort: ").append(containerPort).append("\n");
        y.append("    protocol: TCP\n    name: http\n");

        // ========== Ingress ==========
        if (!ingressHost.isEmpty()) {
            y.append("---\napiVersion: networking.k8s.io/v1\nkind: Ingress\nmetadata:\n");
            y.append("  name: ").append(name).append("-ingress\n  namespace: ").append(ns).append("\n");
            y.append("spec:\n  rules:\n  - host: ").append(ingressHost).append("\n");
            y.append("    http:\n      paths:\n      - path: /\n        pathType: Prefix\n");
            y.append("        backend:\n          service:\n            name: ").append(name).append("-svc\n");
            y.append("            port:\n              number: ").append(svcPort).append("\n");
        }

        outputArea.setText(y.toString());
    }

    // ==================== 辅助方法 ====================

    private static void appendProbe(StringBuilder y, String path, String port) {
        if (path.startsWith("/")) {
            y.append("          httpGet:\n            path: ").append(path).append("\n");
            y.append("            port: ").append(port).append("\n");
            y.append("          initialDelaySeconds: 10\n          periodSeconds: 15\n");
        } else {
            y.append("          tcpSocket:\n            port: ").append(port).append("\n");
            y.append("          initialDelaySeconds: 10\n          periodSeconds: 15\n");
        }
    }

    private static String val(JTextField f) {
        return f.getText().trim();
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    private static String[] lines(JTextArea area) {
        String t = area.getText();
        if (t == null || t.trim().isEmpty()) return new String[0];
        return t.split("\n");
    }
}
