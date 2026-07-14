package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
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
 * Mermaid 绘图面板：支持在线高清 PNG 渲染（带防噪白底抗锯齿）、手势拖拽平移、各类图表模板一键载入、本地导出 PNG。
 */
public class MermaidPanel extends ToolPanel {

    private JTextArea codeArea;
    private ImagePreviewPanel previewPanel;
    private JComboBox<String> templateCombo;
    private JButton renderBtn;
    private JButton exportBtn;
    private JProgressBar progressBar;

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

        // 2. 右侧图片展示区 (自定义高清双三次平滑抗锯齿画布 + Drag-to-Scroll 手势拖拽平移)
        previewPanel = new ImagePreviewPanel();
        
        JScrollPane previewScroll = new JScrollPane(previewPanel);
        previewScroll.setBorder(BorderFactory.createTitledBorder(
                null, "图表渲染预览 (按住鼠标左键可任意拖拽平移)", javax.swing.border.TitledBorder.DEFAULT_JUSTIFICATION,
                javax.swing.border.TitledBorder.DEFAULT_POSITION, UIUtils.plainFont(),
                UIManager.getColor("Component.accentColor")));

        JViewport viewport = previewScroll.getViewport();

        // 鼠标拖拽平移逻辑实现 (使用屏幕绝对坐标差值防抖动)
        MouseAdapter dragScrollAdapter = new MouseAdapter() {
            private Point originOnScreen;
            private Point viewPositionAtStart;

            @Override
            public void mousePressed(MouseEvent e) {
                if (previewPanel.getImage() != null && SwingUtilities.isLeftMouseButton(e)) {
                    originOnScreen = e.getLocationOnScreen();
                    viewPositionAtStart = viewport.getViewPosition();
                    previewPanel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (previewPanel.getImage() != null) {
                    previewPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    previewPanel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (originOnScreen != null && viewPositionAtStart != null && previewPanel.getImage() != null) {
                    Point currentScreen = e.getLocationOnScreen();
                    int dx = currentScreen.x - originOnScreen.x;
                    int dy = currentScreen.y - originOnScreen.y;
                    
                    int newX = viewPositionAtStart.x - dx;
                    int newY = viewPositionAtStart.y - dy;
                    
                    // 获取可滑动的最大边界
                    int maxX = Math.max(0, previewPanel.getWidth() - viewport.getWidth());
                    int maxY = Math.max(0, previewPanel.getHeight() - viewport.getHeight());
                    
                    if (newX < 0) newX = 0;
                    if (newX > maxX) newX = maxX;
                    if (newY < 0) newY = 0;
                    if (newY > maxY) newY = maxY;
                    
                    viewport.setViewPosition(new Point(newX, newY));
                }
            }
        };

        previewPanel.addMouseListener(dragScrollAdapter);
        previewPanel.addMouseMotionListener(dragScrollAdapter);

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
        previewPanel.setImage(null);
        previewPanel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

        new SwingWorker<BufferedImage, Void>() {
            private String errorMsg = null;

            @Override
            protected BufferedImage doInBackground() throws Exception {
                // 将代码进行 UTF-8 Base64 编码，生成 URL-safe 的请求字符串
                byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
                String base64Code = Base64.getUrlEncoder().withoutPadding().encodeToString(codeBytes);
                
                // 请求 PNG 格式，并强制指定白色背景（确保在任何暗色/明色主题下均能清晰阅读，对比度完美）
                String requestUrl = "https://mermaid.ink/img/" + base64Code + "?bgColor=white";
                
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
                    BufferedImage img = get();
                    if (img != null) {
                        previewPanel.setImage(img);
                        previewPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); // 有图展示时手型提示可拖拽
                        exportBtn.setEnabled(true);
                    } else {
                        previewPanel.setImage(null);
                        UIUtils.error(getView(), errorMsg != null ? errorMsg : "网络请求出现异常，无法渲染图表。");
                    }
                } catch (Exception ex) {
                    previewPanel.setImage(null);
                    UIUtils.error(getView(), "连接渲染服务异常: \n" + ex.getMessage() + "\n\n请检查您当前的互联网连接状态。");
                }
            }
        }.execute();
    }

    private void triggerExport() {
        BufferedImage image = previewPanel.getImage();
        if (image == null) {
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
                    ImageIO.write(image, "png", finalDest);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        UIUtils.info(getView(), "图表已成功导出为 PNG 文件！");
                    } catch (Exception ex) {
                        UIUtils.error(getView(), "导出失败:\n" + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    /**
     * 自定义图片预览画布，支持极致平滑的双三次插值（Bicubic）无锯齿抗锯齿渲染
     */
    private static class ImagePreviewPanel extends JPanel {
        private BufferedImage image;

        public ImagePreviewPanel() {
            setBackground(UIManager.getColor("Panel.background"));
            setOpaque(true);
        }

        public void setImage(BufferedImage img) {
            this.image = img;
            if (img != null) {
                setBackground(Color.WHITE); // 渲染成功后用白色纸张底色，完美展现图表
                setPreferredSize(new Dimension(img.getWidth() + 40, img.getHeight() + 40)); // 留有 20px 边距防拥挤
            } else {
                setBackground(UIManager.getColor("Panel.background")); // 没有图时跟随系统主题
                setPreferredSize(new Dimension(100, 100));
            }
            revalidate();
            repaint();
        }

        public BufferedImage getImage() {
            return image;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (image != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                
                // 开启极致的高保真抗锯齿与文字平滑，消除一切图像锯齿与发虚
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                
                // 居中绘制图表
                int x = Math.max(20, (getWidth() - image.getWidth()) / 2);
                int y = Math.max(20, (getHeight() - image.getHeight()) / 2);
                g2.drawImage(image, x, y, null);
                g2.dispose();
            } else {
                g.setColor(UIManager.getColor("Label.disabledForeground"));
                g.setFont(UIUtils.plainFont());
                String text = "暂未生成图表，请在左侧编辑代码后点击“渲染图表”。";
                FontMetrics fm = g.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(text)) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g.drawString(text, x, y);
            }
        }
    }
}
