package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mermaid 绘图面板：支持在线渲染、各类图表模板一键载入、本地导出 PNG。
 */
public class MermaidPanel extends ToolPanel {

    private JTextArea codeArea;
    private JLabel previewLabel;
    private JComboBox<String> templateCombo;
    private JButton renderBtn;
    private JButton exportBtn;
    private JProgressBar progressBar;
    
    // 缓存当前渲染成功的图片，以便导出
    private BufferedImage currentImage;

    // 预置模板列表
    private static final Map<String, String> TEMPLATES = new LinkedHashMap<>();
    static {
        TEMPLATES.put("流程图 (Flowchart)",
                "graph TD\n" +
                "    A[开始] --> B{是否继续?}\n" +
                "    B -- 是 --> C[运行程序]\n" +
                "    B -- 否 --> D[退出结束]\n" +
                "    C --> A");
                
        TEMPLATES.put("时序图 (Sequence)",
                "sequenceDiagram\n" +
                "    actor 客户端\n" +
                "    actor 服务端\n" +
                "    客户端->>服务端: 发送 HTTP 请求 (GET)\n" +
                "    服务端-->>客户端: 返回 JSON 数据 (200 OK)");
                
        TEMPLATES.put("类图 (Class)",
                "classDiagram\n" +
                "    class Animal {\n" +
                "        +int age\n" +
                "        +isMammal()\n" +
                "    }\n" +
                "    class Duck {\n" +
                "        +String beakColor\n" +
                "        +swim()\n" +
                "    }\n" +
                "    Animal <|-- Duck");
                
        TEMPLATES.put("状态图 (State)",
                "stateDiagram-v2\n" +
                "    [*] --> 空闲\n" +
                "    空闲 --> 运行中 : 启动\n" +
                "    运行中 --> 空闲 : 停止\n" +
                "    运行中 --> 错误 : 异常发生\n" +
                "    错误 --> [*]");
                
        TEMPLATES.put("甘特图 (Gantt)",
                "gantt\n" +
                "    title 项目进度开发规划\n" +
                "    dateFormat  YYYY-MM-DD\n" +
                "    section 需求与设计\n" +
                "    需求分析          :a1, 2026-07-10, 5d\n" +
                "    架构设计          :after a1, 3d\n" +
                "    section 编码与测试\n" +
                "    核心编码实现       :2026-07-18, 10d\n" +
                "    系统集成测试       : 5d");
                
        TEMPLATES.put("饼图 (Pie)",
                "pie title 2026年技术框架使用分布\n" +
                "    \"Spring Boot\" : 45\n" +
                "    \"Vue 3 / React\" : 30\n" +
                "    \"Python AI\" : 15\n" +
                "    \"Go / Rust\" : 10");
    }

    public MermaidPanel() {
        super("dev", "mermaid",
                "Mermaid", "绘图", "画图", "流程图",
                "时序图", "UML", "图表", "diagram");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 1. 左侧代码编辑与控制面板
        JPanel leftPanel = new JPanel(new BorderLayout(6, 6));
        leftPanel.setPreferredSize(new Dimension(360, 0));

        codeArea = new JTextArea();
        codeArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        codeArea.setText(TEMPLATES.get("流程图 (Flowchart)"));
        JScrollPane codeScroll = UIUtils.scrollText(codeArea, "Mermaid 源码编辑");
        leftPanel.add(codeScroll, BorderLayout.CENTER);

        // 按钮及模板控制区
        JPanel controlPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 0, 4, 0);
        gbc.weightx = 1.0;

        // 模板下拉行
        gbc.gridy = 0;
        JPanel templateRow = new JPanel(new BorderLayout(6, 0));
        templateRow.add(new JLabel("图表模板:"), BorderLayout.WEST);
        templateCombo = new JComboBox<>(TEMPLATES.keySet().toArray(new String[0]));
        templateRow.add(templateCombo, BorderLayout.CENTER);
        controlPanel.add(templateRow, gbc);

        // 按钮行
        gbc.gridy = 1;
        JPanel btnRow = new JPanel(new GridLayout(1, 2, 8, 0));
        renderBtn = UIUtils.button("渲染图表", 120);
        exportBtn = UIUtils.button("导出图片", 120);
        exportBtn.setEnabled(false); // 初始没有渲染出的图片时禁用
        btnRow.add(renderBtn);
        btnRow.add(exportBtn);
        controlPanel.add(btnRow, gbc);

        // 进度条行
        gbc.gridy = 2;
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        controlPanel.add(progressBar, gbc);

        leftPanel.add(controlPanel, BorderLayout.SOUTH);

        // 2. 右侧图片展示区
        previewLabel = new JLabel("暂未生成图表，请在左侧编辑代码后点击“渲染图表”。", SwingConstants.CENTER);
        previewLabel.setFont(UIUtils.plainFont());
        previewLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        
        JScrollPane previewScroll = new JScrollPane(previewLabel);
        previewScroll.setBorder(BorderFactory.createTitledBorder(
                null, "图表渲染预览", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION, UIUtils.plainFont(),
                UIManager.getColor("Component.accentColor")));

        // 3. 将左右面板装入 JSplitPane 容器
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, previewScroll);
        splitPane.setDividerLocation(380);
        splitPane.setResizeWeight(0.0);
        root.add(splitPane, BorderLayout.CENTER);

        // 4. 事件响应绑定
        templateCombo.addActionListener(e -> {
            String selectedKey = (String) templateCombo.getSelectedItem();
            if (selectedKey != null && TEMPLATES.containsKey(selectedKey)) {
                codeArea.setText(TEMPLATES.get(selectedKey));
            }
        });

        renderBtn.addActionListener(e -> triggerRender());
        exportBtn.addActionListener(e -> triggerExport());

        return root;
    }

    private void triggerRender() {
        String code = codeArea.getText().trim();
        if (code.isEmpty()) {
            UIUtils.error(getView(), "请输入有效的 Mermaid 代码！");
            return;
        }

        renderBtn.setEnabled(false);
        exportBtn.setEnabled(false);
        progressBar.setVisible(true);
        previewLabel.setIcon(null);
        previewLabel.setText("正在连接云端服务渲染中，请稍候...");
        previewLabel.setForeground(UIManager.getColor("Label.foreground"));

        new SwingWorker<BufferedImage, Void>() {
            private String errorMsg = null;

            @Override
            protected BufferedImage doInBackground() throws Exception {
                // 将代码进行 UTF-8 Base64 编码，生成 URL-safe 的请求字符串
                byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
                String base64Code = Base64.getUrlEncoder().withoutPadding().encodeToString(codeBytes);
                
                // Kroki 或 Mermaid.ink PNG 渲染源 (这里使用稳定和极速的 Mermaid.ink)
                String requestUrl = "https://mermaid.ink/img/" + base64Code;
                
                URL url = new URL(requestUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (InputStream is = conn.getInputStream();
                         ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        byte[] imageBytes = baos.toByteArray();
                        try (ByteArrayInputStream bais = new ByteArrayInputStream(imageBytes)) {
                            return ImageIO.read(bais);
                        }
                    }
                } else {
                    errorMsg = "云端渲染失败 (HTTP " + responseCode + ")！\n请检查您的 Mermaid 语法是否正确。";
                    return null;
                }
            }

            @Override
            protected void done() {
                renderBtn.setEnabled(true);
                progressBar.setVisible(false);
                try {
                    BufferedImage image = get();
                    if (image != null) {
                        currentImage = image;
                        previewLabel.setText("");
                        previewLabel.setIcon(new ImageIcon(image));
                        exportBtn.setEnabled(true);
                    } else {
                        currentImage = null;
                        previewLabel.setIcon(null);
                        previewLabel.setText(errorMsg != null ? errorMsg : "网络请求出现异常，无法渲染图表。");
                        previewLabel.setForeground(Color.RED);
                    }
                } catch (Exception ex) {
                    currentImage = null;
                    previewLabel.setIcon(null);
                    previewLabel.setText("连接渲染服务异常: \n" + ex.getMessage() + "\n\n请检查您当前的互联网连接状态。");
                    previewLabel.setForeground(Color.RED);
                }
            }
        }.execute();
    }

    private void triggerExport() {
        if (currentImage == null) {
            UIUtils.error(getView(), "当前没有可导出的图表！");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("mermaid_chart.png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG 图片 (*.png)", "png"));
        
        if (chooser.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File dest = chooser.getSelectedFile();
            if (!dest.getName().toLowerCase().endsWith(".png")) {
                dest = new File(dest.getParentFile(), dest.getName() + ".png");
            }

            final File finalDest = dest;
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    ImageIO.write(currentImage, "png", finalDest);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        UIUtils.info(getView(), "图表已成功导出为图片！");
                    } catch (Exception ex) {
                        UIUtils.error(getView(), "导出失败:\n" + ex.getMessage());
                    }
                }
            }.execute();
        }
    }
}
