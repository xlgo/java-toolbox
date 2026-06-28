package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * Kubernetes 部署文件生成面板。
 * <p>通过表单快速生成 Deployment + Service 的 YAML 配置。</p>
 */
public class K8sPanel extends ToolPanel {

    // ==================== 通用 ====================
    private JTextField nameField;
    private JTextField namespaceField;
    private JTextField imageField;
    private JTextField replicasField;
    private JTextField containerPortField;
    private JTextField servicePortField;
    private JComboBox<String> serviceTypeCombo;
    private JTextField cpuLimitField;
    private JTextField memLimitField;
    private JTextField cpuRequestField;
    private JTextField memRequestField;
    private JTextArea envArea;
    private JTextArea outputArea;

    public K8sPanel() {
        super("开发工具", "K8s 部署生成",
                "K8s", "Kubernetes", "部署", "YAML", "容器",
                "Deployment", "Service", "Pod", "k8s", "编排");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ---- 左侧：配置区 ----
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(BorderFactory.createTitledBorder("配置参数"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(3, 6, 3, 6);

        int row = 0;

        // 应用名称
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0; gbc.gridwidth = 1;
        formPanel.add(new JLabel("应用名称："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        nameField = new JTextField("my-app");
        formPanel.add(nameField, gbc);

        // 命名空间
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("命名空间："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        namespaceField = new JTextField("default");
        formPanel.add(namespaceField, gbc);

        // 镜像
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("镜像："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        imageField = new JTextField("nginx:latest");
        formPanel.add(imageField, gbc);

        // 副本数
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("副本数："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        replicasField = new JTextField("1");
        formPanel.add(replicasField, gbc);

        // 容器端口
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("容器端口："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        containerPortField = new JTextField("80");
        formPanel.add(containerPortField, gbc);

        // Service 端口
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("Service 端口："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        servicePortField = new JTextField("80");
        formPanel.add(servicePortField, gbc);

        // Service 类型
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("Service 类型："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        serviceTypeCombo = new JComboBox<>(new String[]{"ClusterIP", "NodePort", "LoadBalancer"});
        formPanel.add(serviceTypeCombo, gbc);

        // ---- 资源限制 ----
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        formPanel.add(new JLabel("— 资源限制 (可选) —"), gbc);

        row++; gbc.gridwidth = 1;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("CPU 请求："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        cpuRequestField = new JTextField("100m");
        formPanel.add(cpuRequestField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("CPU 上限："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        cpuLimitField = new JTextField("500m");
        formPanel.add(cpuLimitField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("内存请求："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        memRequestField = new JTextField("128Mi");
        formPanel.add(memRequestField, gbc);

        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        formPanel.add(new JLabel("内存上限："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        memLimitField = new JTextField("256Mi");
        formPanel.add(memLimitField, gbc);

        // ---- 环境变量 ----
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        formPanel.add(new JLabel("— 环境变量 (每行 KEY=VALUE) —"), gbc);

        row++; gbc.gridwidth = 1; gbc.weightx = 1.0; gbc.gridwidth = 2;
        envArea = new JTextArea(3, 20);
        envArea.setFont(UIUtils.monoFont());
        formPanel.add(new JScrollPane(envArea), gbc);

        // ---- 按钮 ----
        row++;
        gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2;
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton genBtn = UIUtils.button("生成 YAML", 100);
        JButton copyBtn = UIUtils.button("复制 YAML", 100);
        btnPanel.add(genBtn);
        btnPanel.add(copyBtn);
        formPanel.add(btnPanel, gbc);

        JScrollPane formScroll = new JScrollPane(formPanel);
        formScroll.setBorder(null);

        // ---- 右侧：输出区 ----
        outputArea = new JTextArea();
        outputArea.setFont(UIUtils.monoFont());
        outputArea.setEditable(false);
        JScrollPane outScroll = UIUtils.scrollText(outputArea, "生成的 Kubernetes YAML");

        // ---- 左右分割 ----
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, formScroll, outScroll);
        split.setResizeWeight(0.4);
        root.add(split, BorderLayout.CENTER);

        // ---- 事件 ----
        genBtn.addActionListener(e -> doGenerate());
        copyBtn.addActionListener(e -> {
            String text = outputArea.getText();
            if (!text.isEmpty()) {
                UIUtils.copyToClipboard(text);
                UIUtils.info(root, "YAML 已复制到剪贴板。");
            }
        });

        // 默认生成一次
        doGenerate();

        return root;
    }

    private void doGenerate() {
        String name = nameField.getText().trim();
        String namespace = namespaceField.getText().trim();
        String image = imageField.getText().trim();
        String replicas = replicasField.getText().trim();
        String containerPort = containerPortField.getText().trim();
        String servicePort = servicePortField.getText().trim();
        String serviceType = (String) serviceTypeCombo.getSelectedItem();

        StringBuilder yaml = new StringBuilder();

        // ===== Deployment =====
        yaml.append("apiVersion: apps/v1\n");
        yaml.append("kind: Deployment\n");
        yaml.append("metadata:\n");
        yaml.append("  name: ").append(name).append("\n");
        yaml.append("  namespace: ").append(namespace).append("\n");
        yaml.append("  labels:\n");
        yaml.append("    app: ").append(name).append("\n");
        yaml.append("spec:\n");
        yaml.append("  replicas: ").append(replicas).append("\n");
        yaml.append("  selector:\n");
        yaml.append("    matchLabels:\n");
        yaml.append("      app: ").append(name).append("\n");
        yaml.append("  template:\n");
        yaml.append("    metadata:\n");
        yaml.append("      labels:\n");
        yaml.append("        app: ").append(name).append("\n");
        yaml.append("    spec:\n");
        yaml.append("      containers:\n");
        yaml.append("      - name: ").append(name).append("\n");
        yaml.append("        image: ").append(image).append("\n");
        yaml.append("        ports:\n");
        yaml.append("        - containerPort: ").append(containerPort).append("\n");

        // 资源限制
        String cpuReq = cpuRequestField.getText().trim();
        String cpuLim = cpuLimitField.getText().trim();
        String memReq = memRequestField.getText().trim();
        String memLim = memLimitField.getText().trim();
        if (!cpuReq.isEmpty() || !cpuLim.isEmpty() || !memReq.isEmpty() || !memLim.isEmpty()) {
            yaml.append("        resources:\n");
            yaml.append("          requests:\n");
            if (!cpuReq.isEmpty()) yaml.append("            cpu: ").append(cpuReq).append("\n");
            if (!memReq.isEmpty()) yaml.append("            memory: ").append(memReq).append("\n");
            yaml.append("          limits:\n");
            if (!cpuLim.isEmpty()) yaml.append("            cpu: ").append(cpuLim).append("\n");
            if (!memLim.isEmpty()) yaml.append("            memory: ").append(memLim).append("\n");
        }

        // 环境变量
        String envText = envArea.getText().trim();
        if (!envText.isEmpty()) {
            yaml.append("        env:\n");
            for (String line : envText.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim();
                    yaml.append("        - name: ").append(key).append("\n");
                    yaml.append("          value: \"").append(val).append("\"\n");
                }
            }
        }

        // ===== Service =====
        yaml.append("---\n");
        yaml.append("apiVersion: v1\n");
        yaml.append("kind: Service\n");
        yaml.append("metadata:\n");
        yaml.append("  name: ").append(name).append("-svc\n");
        yaml.append("  namespace: ").append(namespace).append("\n");
        yaml.append("  labels:\n");
        yaml.append("    app: ").append(name).append("\n");
        yaml.append("spec:\n");
        yaml.append("  type: ").append(serviceType).append("\n");
        yaml.append("  selector:\n");
        yaml.append("    app: ").append(name).append("\n");
        yaml.append("  ports:\n");
        yaml.append("  - port: ").append(servicePort).append("\n");
        yaml.append("    targetPort: ").append(containerPort).append("\n");
        yaml.append("    protocol: TCP\n");

        outputArea.setText(yaml.toString());
    }
}
