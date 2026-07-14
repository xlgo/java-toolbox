package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import com.formdev.flatlaf.extras.FlatSVGIcon;

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
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mermaid 绘图面板：支持在线矢量 SVG 渲染、手势拖拽平移、各类图表模板一键载入、导出高清 PNG 或矢量 SVG 图表。
 */
public class MermaidPanel extends ToolPanel {

    private JTextArea codeArea;
    private JLabel previewLabel;
    private JComboBox<String> templateCombo;
    private JButton renderBtn;
    private JButton exportBtn;
    private JProgressBar progressBar;
    
    // 缓存渲染结果
    private byte[] currentSvgBytes;
    private FlatSVGIcon currentIcon;

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

        // 2. 右侧图片展示区 (带 Drag-to-Scroll 手势拖拽平移)
        previewLabel = new JLabel("暂未生成图表，请在左侧编辑代码后点击“渲染图表”。", SwingConstants.CENTER);
        previewLabel.setFont(UIUtils.plainFont());
        previewLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        
        JScrollPane previewScroll = new JScrollPane(previewLabel);
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
                if (previewLabel.getIcon() != null && SwingUtilities.isLeftMouseButton(e)) {
                    originOnScreen = e.getLocationOnScreen();
                    viewPositionAtStart = viewport.getViewPosition();
                    previewLabel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (previewLabel.getIcon() != null) {
                    previewLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    previewLabel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (originOnScreen != null && viewPositionAtStart != null && previewLabel.getIcon() != null) {
                    Point currentScreen = e.getLocationOnScreen();
                    int dx = currentScreen.x - originOnScreen.x;
                    int dy = currentScreen.y - originOnScreen.y;
                    
                    int newX = viewPositionAtStart.x - dx;
                    int newY = viewPositionAtStart.y - dy;
                    
                    // 获取可滑动的最大边界
                    int maxX = Math.max(0, previewLabel.getWidth() - viewport.getWidth());
                    int maxY = Math.max(0, previewLabel.getHeight() - viewport.getHeight());
                    
                    if (newX < 0) newX = 0;
                    if (newX > maxX) newX = maxX;
                    if (newY < 0) newY = 0;
                    if (newY > maxY) newY = maxY;
                    
                    viewport.setViewPosition(new Point(newX, newY));
                }
            }
        };

        previewLabel.addMouseListener(dragScrollAdapter);
        previewLabel.addMouseMotionListener(dragScrollAdapter);

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
        previewLabel.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));

        new SwingWorker<byte[], Void>() {
            private String errorMsg = null;

            @Override
            protected byte[] doInBackground() throws Exception {
                // 将代码进行 UTF-8 Base64 编码，生成 URL-safe 的请求字符串
                byte[] codeBytes = code.getBytes(StandardCharsets.UTF_8);
                String base64Code = Base64.getUrlEncoder().withoutPadding().encodeToString(codeBytes);
                
                // 请求矢量 SVG 格式渲染源，确保无限清晰
                String requestUrl = "https://mermaid.ink/svg/" + base64Code;
                
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
                        return baos.toByteArray();
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
                    byte[] svgBytes = get();
                    if (svgBytes != null && svgBytes.length > 0) {
                        currentSvgBytes = svgBytes;
                        
                        // 使用 flatlaf-extras 中的 FlatSVGIcon (底层利用 jsvg 解析渲染，支持无限缩放)
                        currentIcon = new FlatSVGIcon(new ByteArrayInputStream(svgBytes));
                        
                        previewLabel.setText("");
                        previewLabel.setIcon(currentIcon);
                        previewLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); // 有图展示时手型提示可拖拽
                        exportBtn.setEnabled(true);
                    } else {
                        currentSvgBytes = null;
                        currentIcon = null;
                        previewLabel.setIcon(null);
                        previewLabel.setText(errorMsg != null ? errorMsg : "网络请求出现异常，无法渲染图表。");
                        previewLabel.setForeground(Color.RED);
                    }
                } catch (Exception ex) {
                    currentSvgBytes = null;
                    currentIcon = null;
                    previewLabel.setIcon(null);
                    previewLabel.setText("连接渲染服务异常: \n" + ex.getMessage() + "\n\n请检查您当前的互联网连接状态。");
                    previewLabel.setForeground(Color.RED);
                }
            }
        }.execute();
    }

    private void triggerExport() {
        if (currentSvgBytes == null || currentIcon == null) {
            UIUtils.error(getView(), "当前没有可导出的图表！");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("mermaid_chart.png"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("PNG 图片 (*.png)", "png"));
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("SVG 矢量图 (*.svg)", "svg"));
        chooser.setFileFilter(chooser.getChoosableFileFilters()[0]); // 默认使用 PNG
        
        if (chooser.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File dest = chooser.getSelectedFile();
            javax.swing.filechooser.FileFilter activeFilter = chooser.getFileFilter();
            
            String ext = "png";
            if (activeFilter instanceof FileNameExtensionFilter) {
                String[] exts = ((FileNameExtensionFilter) activeFilter).getExtensions();
                if (exts.length > 0) {
                    ext = exts[0].toLowerCase();
                }
            }
            
            // 如果用户写的文件名没有对应后缀，强行加上
            if (!dest.getName().toLowerCase().endsWith("." + ext)) {
                dest = new File(dest.getParentFile(), dest.getName() + "." + ext);
            }

            final File finalDest = dest;
            final String finalExt = ext;

            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    if ("svg".equals(finalExt)) {
                        // 导出为 SVG 矢量图
                        Files.write(finalDest.toPath(), currentSvgBytes);
                    } else {
                        // 导出为 PNG 高清图
                        BufferedImage bi = convertIconToImage(currentIcon);
                        ImageIO.write(bi, "png", finalDest);
                    }
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        UIUtils.info(getView(), "图表已成功导出为 " + finalExt.toUpperCase() + " 文件！");
                    } catch (Exception ex) {
                        UIUtils.error(getView(), "导出失败:\n" + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    /**
     * 将 FlatSVGIcon 渲染为高分辨率的 BufferedImage 以便导出 PNG 图片
     */
    private BufferedImage convertIconToImage(FlatSVGIcon icon) {
        int w = icon.getIconWidth();
        int h = icon.getIconHeight();
        // 渲染比例倍数：这里使用 2 倍高分比率，保证在超大分辨率或打印时依然无限清晰
        int scale = 2; 
        BufferedImage bi = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = bi.createGraphics();
        
        // 开启最高品质的抗锯齿与文本渲染平滑配置
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        
        g2.scale(scale, scale);
        icon.paintIcon(null, g2, 0, 0);
        g2.dispose();
        return bi;
    }
}
