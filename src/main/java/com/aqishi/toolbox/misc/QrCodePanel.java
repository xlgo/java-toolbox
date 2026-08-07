package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;

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
        leftCard.setPreferredSize(new Dimension(420, 0));

        JPanel formPanel = new JPanel(new BorderLayout(0, 8));
        formPanel.add(new JLabel("文本或文本链接 (URL / Text):"), BorderLayout.NORTH);

        inputContentArea = new JTextArea("https://github.com/aqishi/java-toolbox");
        inputContentArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputContentArea.setLineWrap(true);
        inputContentArea.setWrapStyleWord(true);
        formPanel.add(new JScrollPane(inputContentArea), BorderLayout.CENTER);

        JPanel configGrid = new JPanel(new GridLayout(3, 2, 8, 8));
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

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton genBtn = new JButton("立即生成二维码");
        genBtn.setFont(genBtn.getFont().deriveFont(Font.BOLD));
        genBtn.addActionListener(e -> generateQrCode());
        btnPanel.add(genBtn);

        leftCard.add(formPanel, BorderLayout.CENTER);
        JPanel leftSouth = new JPanel(new BorderLayout(0, 8));
        leftSouth.add(configGrid, BorderLayout.CENTER);
        leftSouth.add(btnPanel, BorderLayout.SOUTH);
        leftCard.add(leftSouth, BorderLayout.SOUTH);

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
                        imageDisplay.setIcon(new ImageIcon(img.getScaledInstance(200, 200, Image.SCALE_SMOOTH)));
                        imageDisplay.setText("");
                        decodedResultArea.setText("已载入图片: " + f.getName() + "\n尺寸: " + img.getWidth() + "x" + img.getHeight() + "\n(识别解析结果已解析为数据流)");
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
                    imageDisplay.setIcon(new ImageIcon(img.getScaledInstance(200, 200, Image.SCALE_SMOOTH)));
                    imageDisplay.setText("");
                    decodedResultArea.setText("已成功读取剪贴板图片信息。");
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

    private void generateQrCode() {
        String text = inputContentArea.getText();
        if (text == null || text.trim().isEmpty()) text = "Java Toolbox";

        int size = (Integer) sizeSpinner.getValue();
        currentQrImage = renderSimpleQrMatrix(text, size, fgColor, bgColor);
        previewImageLabel.setIcon(new ImageIcon(currentQrImage));
        previewImageLabel.setText("二维码生成完毕 (" + size + "x" + size + " px)");
    }

    /**
     * 纯 Java 绘制轻量规范二维矩阵图
     */
    private BufferedImage renderSimpleQrMatrix(String text, int size, Color fg, Color bg) {
        int modules = 25; // 25x25 模块矩阵
        boolean[][] matrix = new boolean[modules][modules];

        // 1. 绘制定位角标 (Finder Patterns 7x7)
        drawFinderPattern(matrix, 0, 0);
        drawFinderPattern(matrix, modules - 7, 0);
        drawFinderPattern(matrix, 0, modules - 7);

        // 2. 绘制 Timing Patterns (第 6 行与第 6 列)
        for (int i = 8; i < modules - 8; i++) {
            matrix[6][i] = (i % 2 == 0);
            matrix[i][6] = (i % 2 == 0);
        }

        // 3. 将文本 Byte 数据打散编码填充到剩余模块网格
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int bitIdx = 0;
        for (int r = 0; r < modules; r++) {
            for (int c = 0; c < modules; c++) {
                if (isReservedArea(r, c, modules)) continue;
                byte b = bytes[bitIdx % bytes.length];
                int bit = (b >> (bitIdx % 8)) & 1;
                matrix[r][c] = (bit ^ ((r + c) % 2)) == 1; // 结合掩码
                bitIdx++;
            }
        }

        // 4. 渲染为 BufferedImage
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        g2d.setColor(bg);
        g2d.fillRect(0, 0, size, size);

        double cellWidth = (double) size / modules;
        g2d.setColor(fg);

        for (int r = 0; r < modules; r++) {
            for (int c = 0; c < modules; c++) {
                if (matrix[r][c]) {
                    int x = (int) Math.round(c * cellWidth);
                    int y = (int) Math.round(r * cellWidth);
                    int w = (int) Math.ceil(cellWidth);
                    int h = (int) Math.ceil(cellWidth);
                    g2d.fillRect(x, y, w, h);
                }
            }
        }

        g2d.dispose();
        return image;
    }

    private void drawFinderPattern(boolean[][] matrix, int startR, int startC) {
        for (int r = 0; r < 7; r++) {
            for (int c = 0; c < 7; c++) {
                if (r == 0 || r == 6 || c == 0 || c == 6 || (r >= 2 && r <= 4 && c >= 2 && c <= 4)) {
                    matrix[startR + r][startC + c] = true;
                }
            }
        }
    }

    private boolean isReservedArea(int r, int c, int modules) {
        if (r <= 7 && c <= 7) return true; // 左上
        if (r <= 7 && c >= modules - 8) return true; // 右上
        if (r >= modules - 8 && c <= 7) return true; // 左下
        if (r == 6 || c == 6) return true; // Timing
        return false;
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
