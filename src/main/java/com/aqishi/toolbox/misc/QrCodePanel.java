package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import java.util.prefs.Preferences;
import com.aqishi.toolbox.ui.kit.Card;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TimerTask;
import java.util.Timer;
import java.awt.Dimension;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageConfig;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

/**
 * 二维码生成与解析工具 (QR Code Generator & Decoder)
 * 基于原生 Java 实现无第三方依赖的二维码矩阵绘制与展示。
 */
public class QrCodePanel extends ToolPanel {

    private JTextArea inputContentArea;
    private JSpinner sizeSpinner;
    private JButton fgColorBtn;
    private JButton bgColorBtn;
    private JLabel previewImageLabel;

    private Color fgColor = Color.BLACK;
    private Color bgColor = Color.WHITE;
    private BufferedImage currentQrImage;
    private File logoFile;
    private Preferences prefs = Preferences.userNodeForPackage(QrCodePanel.class);
    private JTextField promptField;
    private JTextField negativePromptField;
    private JPasswordField apiTokenField;
    private JButton generateAiBtn;

    public QrCodePanel() {
        super("misc", "qrcode", "qrcode", "qr", "barcode", "2dcode", "scan", "generate", "decode", "encode", "二维码", "条码");
    }

    @Override
    protected JComponent build() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 12));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("二维码生成器", buildGeneratorPanel());
        tabbedPane.addTab("图片与剪贴板识别", buildDecoderPanel());

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        // 初始生成默认二维码
        generateQrCode();

        return mainPanel;
    }

    private JPanel buildGeneratorPanel() {
        JPanel panel = new JPanel(new BorderLayout(16, 0));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // 左侧控制区
        Card leftCard = Card.plain();
        leftCard.setLayout(new BorderLayout(0, 12));
        leftCard.setBorder(new EmptyBorder(16, 16, 16, 16));
        leftCard.setPreferredSize(new Dimension(450, 0));

        // 公共文本区
        JPanel formPanel = new JPanel(new BorderLayout(0, 8));
        formPanel.add(new JLabel("文本或文本链接 (URL / Text):"), BorderLayout.NORTH);

        inputContentArea = new JTextArea("https://github.com/aqishi/java-toolbox");
        inputContentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputContentArea.setLineWrap(true);
        inputContentArea.setWrapStyleWord(true);
        formPanel.add(new JScrollPane(inputContentArea), BorderLayout.CENTER);
        leftCard.add(formPanel, BorderLayout.CENTER);

        JTabbedPane leftTabs = new JTabbedPane();
        leftTabs.addTab("普通二维码", buildNormalConfigPanel());
        leftTabs.addTab("AI 艺术二维码", buildAiConfigPanel());
        leftCard.add(leftTabs, BorderLayout.SOUTH);

        // 右侧预览与导出区
        Card rightCard = Card.plain();
        rightCard.setLayout(new BorderLayout(0, 12));
        rightCard.setBorder(new EmptyBorder(16, 16, 16, 16));

        previewImageLabel = new JLabel("", SwingConstants.CENTER);
        previewImageLabel.setHorizontalTextPosition(SwingConstants.CENTER);
        previewImageLabel.setVerticalTextPosition(SwingConstants.BOTTOM);
        rightCard.add(new JScrollPane(previewImageLabel), BorderLayout.CENTER);

        JPanel exportBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        JButton copyImgBtn = new JButton("复制图片到剪贴板");
        copyImgBtn.addActionListener(e -> copyImageToClipboard());

        JButton saveImgBtn = new JButton("保存为图片文件 (PNG)");
        saveImgBtn.addActionListener(e -> saveImageToFile());

        exportBar.add(copyImgBtn);
        exportBar.add(saveImgBtn);
        rightCard.add(exportBar, BorderLayout.SOUTH);

        panel.add(leftCard, BorderLayout.WEST);
        panel.add(rightCard, BorderLayout.CENTER);

        return panel;
    }

    private JPanel buildDecoderPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        Card topCard = Card.plain();
        topCard.setLayout(new FlowLayout(FlowLayout.LEFT, 12, 8));
        JButton loadFileBtn = new JButton("打开图片文件...");
        JButton pasteClipBtn = new JButton("从剪贴板读取图片");

        topCard.add(loadFileBtn);
        topCard.add(pasteClipBtn);

        Card centerCard = Card.plain();
        centerCard.setLayout(new BorderLayout(12, 12));
        centerCard.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel imageDisplay = new JLabel("拖拽图片到此处或从剪贴板读取", SwingConstants.CENTER);
        imageDisplay.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        imageDisplay.setBorder(BorderFactory.createDashedBorder(Color.GRAY));

        JTextArea decodedResultArea = new JTextArea();
        decodedResultArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        decodedResultArea.setEditable(false);
        decodedResultArea.setRows(5);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(imageDisplay), new JScrollPane(decodedResultArea));
        splitPane.setResizeWeight(0.6);

        centerCard.add(splitPane, BorderLayout.CENTER);

        loadFileBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
                File f = fc.getSelectedFile();
                try {
                    BufferedImage img = ImageIO.read(f);
                    if (img != null) {
                        decodeImage(img, decodedResultArea, imageDisplay);
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(getView(), "图片读取失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        pasteClipBtn.addActionListener(e -> {
            try {
                Transferable tr = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
                if (tr != null && tr.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                    Image img = (Image) tr.getTransferData(DataFlavor.imageFlavor);
                    BufferedImage bImg = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D bGr = bImg.createGraphics();
                    bGr.drawImage(img, 0, 0, null);
                    bGr.dispose();
                    decodeImage(bImg, decodedResultArea, imageDisplay);
                } else if (tr != null && tr.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    String str = (String) tr.getTransferData(DataFlavor.stringFlavor);
                    decodedResultArea.setText("剪贴板包含文本内容:\n" + str);
                } else {
                    JOptionPane.showMessageDialog(getView(), "剪贴板中未找到图片或文本数据", "提示", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(getView(), "读取剪贴板失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        });

        panel.add(topCard, BorderLayout.NORTH);
        panel.add(centerCard, BorderLayout.CENTER);
        return panel;
    }


    private JPanel buildNormalConfigPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));
        
        JPanel configGrid = new JPanel(new GridLayout(4, 2, 8, 8));
        configGrid.add(new JLabel("图片尺寸 (px):"));
        sizeSpinner = new JSpinner(new SpinnerNumberModel(260, 100, 800, 20));
        configGrid.add(sizeSpinner);

        configGrid.add(new JLabel("前景色 (前景色/点阵):"));
        fgColorBtn = new JButton("选择颜色");
        fgColorBtn.setBackground(fgColor);
        fgColorBtn.setForeground(Color.WHITE);
        fgColorBtn.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(getView(), "选择前景色", fgColor);
            if (chosen != null) {
                fgColor = chosen;
                fgColorBtn.setBackground(fgColor);
                generateQrCode();
            }
        });
        configGrid.add(fgColorBtn);

        configGrid.add(new JLabel("背景色 (Background):"));
        bgColorBtn = new JButton("选择颜色");
        bgColorBtn.setBackground(bgColor);
        bgColorBtn.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(getView(), "选择背景色", bgColor);
            if (chosen != null) {
                bgColor = chosen;
                bgColorBtn.setBackground(bgColor);
                generateQrCode();
            }
        });
        configGrid.add(bgColorBtn);

        configGrid.add(new JLabel("中心Logo (可选):"));
        JPanel logoBtnPanel = new JPanel(new BorderLayout());
        JButton logoBtn = new JButton("选择图片...");
        logoBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
                logoFile = fc.getSelectedFile();
                logoBtn.setText(logoFile.getName());
                generateQrCode();
            }
        });
        logoBtnPanel.add(logoBtn, BorderLayout.CENTER);
        JButton clearLogoBtn = new JButton("X");
        clearLogoBtn.setToolTipText("清除Logo");
        clearLogoBtn.addActionListener(e -> {
            logoFile = null;
            logoBtn.setText("选择图片...");
            generateQrCode();
        });
        logoBtnPanel.add(clearLogoBtn, BorderLayout.EAST);
        configGrid.add(logoBtnPanel);
        panel.add(configGrid, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton genBtn = new JButton("立即生成普通二维码");
        genBtn.setFont(genBtn.getFont().deriveFont(Font.BOLD));
        genBtn.addActionListener(e -> generateQrCode());
        btnPanel.add(genBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);
        
        return panel;
    }

    private JPanel buildAiConfigPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel formGrid = new JPanel(new GridLayout(6, 1, 4, 4));
        
        formGrid.add(new JLabel("Prompt (画面提示词):"));
        promptField = new JTextField("A sprawling isometric futuristic city, highly detailed, vibrant colors");
        formGrid.add(promptField);

        formGrid.add(new JLabel("Negative Prompt (反向提示词):"));
        negativePromptField = new JTextField("ugly, disfigured, low quality, blurry, nsfw");
        formGrid.add(negativePromptField);

        formGrid.add(new JLabel("Replicate API Token:"));
        apiTokenField = new JPasswordField(prefs.get("replicate_api_token", ""));
        formGrid.add(apiTokenField);

        panel.add(formGrid, BorderLayout.NORTH);
        
        JTextArea tipArea = new JTextArea("提示：此功能调用 Replicate z-uo/qrcode-controlnet 模型生成，需要输入您的个人 Replicate API Token。生成过程可能需要10-30秒，期间请勿频繁点击。");
        tipArea.setWrapStyleWord(true);
        tipArea.setLineWrap(true);
        tipArea.setEditable(false);
        tipArea.setBackground(panel.getBackground());
        tipArea.setForeground(Color.GRAY);
        tipArea.setFont(tipArea.getFont().deriveFont(12f));
        panel.add(tipArea, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        generateAiBtn = new JButton("生成 AI 艺术二维码");
        generateAiBtn.setFont(generateAiBtn.getFont().deriveFont(Font.BOLD));
        generateAiBtn.addActionListener(e -> generateAiQrCode());
        btnPanel.add(generateAiBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void generateAiQrCode() {
        String token = new String(apiTokenField.getPassword());
        if (token.trim().isEmpty()) {
            JOptionPane.showMessageDialog(getView(), "请提供 Replicate API Token", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        prefs.put("replicate_api_token", token);
        
        String text = inputContentArea.getText().trim();
        if (text.isEmpty()) {
            JOptionPane.showMessageDialog(getView(), "二维码文本内容不能为空", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String prompt = promptField.getText().trim();
        String negativePrompt = negativePromptField.getText().trim();

        generateAiBtn.setEnabled(false);
        generateAiBtn.setText("正在提交请求...");
        previewImageLabel.setIcon(null);
        previewImageLabel.setText("正在提交生图任务到云端...");

        new Thread(() -> {
            try {
                ObjectMapper mapper = new ObjectMapper();
                // Start Prediction
                URL url = new URL("https://api.replicate.com/v1/predictions");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                Map<String, Object> input = new HashMap<>();
                input.put("qr_code_content", text);
                input.put("prompt", prompt);
                input.put("negative_prompt", negativePrompt);

                Map<String, Object> body = new HashMap<>();
                // z-uo/qrcode-controlnet version
                body.put("version", "628e604e13fc636433fbe4d9c0e5a95efd58117a421b4700d11f9746e16694e8");
                body.put("input", input);

                try (OutputStream os = conn.getOutputStream()) {
                    mapper.writeValue(os, body);
                }

                if (conn.getResponseCode() >= 400) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                    String err = br.lines().reduce("", String::concat);
                    throw new Exception("API 请求失败: " + conn.getResponseCode() + " " + err);
                }

                JsonNode root = mapper.readTree(conn.getInputStream());
                String getUrl = root.path("urls").path("get").asText();

                SwingUtilities.invokeLater(() -> {
                    previewImageLabel.setText("任务已提交，正在等待云端渲染完成 (可能需要数十秒)...");
                });

                pollAiResult(getUrl, token, mapper);

            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    generateAiBtn.setEnabled(true);
                    generateAiBtn.setText("生成 AI 艺术二维码");
                    previewImageLabel.setText("");
                    JOptionPane.showMessageDialog(getView(), "AI 生成请求出错: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void pollAiResult(String getUrl, String token, ObjectMapper mapper) {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                try {
                    URL url = new URL(getUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("Authorization", "Bearer " + token);
                    
                    JsonNode root = mapper.readTree(conn.getInputStream());
                    String status = root.path("status").asText();
                    
                    if ("succeeded".equals(status)) {
                        String imageUrl = root.path("output").get(0).asText(); // For this model it returns an array of urls
                        BufferedImage img = ImageIO.read(new URL(imageUrl));
                        SwingUtilities.invokeLater(() -> {
                            currentQrImage = img;
                            // scale for preview if too large, but model generates 768x768 usually
                            Image scaled = img.getScaledInstance(350, 350, Image.SCALE_SMOOTH);
                            previewImageLabel.setIcon(new ImageIcon(scaled));
                            previewImageLabel.setText("AI 艺术二维码生成完毕");
                            generateAiBtn.setEnabled(true);
                            generateAiBtn.setText("生成 AI 艺术二维码");
                        });
                        timer.cancel();
                    } else if ("failed".equals(status) || "canceled".equals(status)) {
                        String error = root.path("error").asText();
                        SwingUtilities.invokeLater(() -> {
                            generateAiBtn.setEnabled(true);
                            generateAiBtn.setText("生成 AI 艺术二维码");
                            previewImageLabel.setText("");
                            JOptionPane.showMessageDialog(getView(), "AI 任务失败或被取消: " + error, "错误", JOptionPane.ERROR_MESSAGE);
                        });
                        timer.cancel();
                    }
                } catch (Exception ex) {
                    // Ignore transient network errors during polling
                }
            }
        }, 3000, 3000);
    }

    private void generateQrCode() {
        String text = inputContentArea.getText();
        if (text == null || text.trim().isEmpty()) text = "Java Toolbox";

        int size = (Integer) sizeSpinner.getValue();
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            if (logoFile != null && logoFile.exists()) {
                hints.put(EncodeHintType.ERROR_CORRECTION, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H);
            }
            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            MatrixToImageConfig config = new MatrixToImageConfig(fgColor.getRGB(), bgColor.getRGB());
            currentQrImage = MatrixToImageWriter.toBufferedImage(bitMatrix, config);
            
            if (logoFile != null && logoFile.exists()) {
                try {
                    BufferedImage logo = ImageIO.read(logoFile);
                    if (logo != null) {
                        Graphics2D g2 = currentQrImage.createGraphics();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        
                        int logoWidth = size / 5;
                        int logoHeight = (int) ((double) logo.getHeight() / logo.getWidth() * logoWidth);
                        int x = (size - logoWidth) / 2;
                        int y = (size - logoHeight) / 2;
                        
                        g2.setColor(bgColor);
                        g2.fillRoundRect(x - 4, y - 4, logoWidth + 8, logoHeight + 8, 12, 12);
                        
                        g2.drawImage(logo, x, y, logoWidth, logoHeight, null);
                        g2.dispose();
                    }
                } catch (Exception ignored) {
                }
            }
            
            previewImageLabel.setIcon(new ImageIcon(currentQrImage));
            previewImageLabel.setText("二维码生成完毕 (" + size + "x" + size + " px)");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(getView(), "二维码生成失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void decodeImage(BufferedImage img, JTextArea resultArea, JLabel display) {
        if (img == null) return;
        try {
            display.setIcon(new ImageIcon(img.getScaledInstance(200, 200, Image.SCALE_SMOOTH)));
            display.setText("");
            
            LuminanceSource source = new BufferedImageLuminanceSource(img);
            Map<DecodeHintType, Object> hints = new HashMap<>();
            hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

            Result result = null;
            
            try {
                result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
            } catch (NotFoundException e1) {
                try {
                    result = new MultiFormatReader().decode(new BinaryBitmap(new com.google.zxing.common.GlobalHistogramBinarizer(source)), hints);
                } catch (NotFoundException e2) {
                    result = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(source.invert())), hints);
                }
            }
            
            if (result != null) {
                resultArea.setText("解析成功！\n" + "格式: " + result.getBarcodeFormat() + "\n内容:\n" + result.getText());
            }
        } catch (NotFoundException e) {
            resultArea.setText("未能识别出二维码或条形码（尝试了多种对比度和反色策略均失败）。");
        } catch (Exception ex) {
            resultArea.setText("解析时发生错误: " + ex.getMessage());
        }
    }

    private void copyImageToClipboard() {
        if (currentQrImage == null) return;
        TransferableImage transferable = new TransferableImage(currentQrImage);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
        JOptionPane.showMessageDialog(getView(), "二维码图像已成功复制到系统剪贴板", "提示", JOptionPane.INFORMATION_MESSAGE);
    }

    private void saveImageToFile() {
        if (currentQrImage == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("qrcode.png"));
        if (fc.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            try {
                ImageIO.write(currentQrImage, "PNG", f);
                JOptionPane.showMessageDialog(getView(), "二维码图片已保存至:\n" + f.getAbsolutePath(), "成功", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(getView(), "保存图片失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static class TransferableImage implements Transferable {
        private final Image image;

        public TransferableImage(Image image) {
            this.image = image;
        }

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) {
            if (DataFlavor.imageFlavor.equals(flavor)) {
                return image;
            }
            return null;
        }
    }
}
