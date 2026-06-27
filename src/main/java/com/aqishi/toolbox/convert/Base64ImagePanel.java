package com.aqishi.toolbox.convert;

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
import java.nio.file.Files;
import java.util.Base64;

/**
 * Base64 图片互转面板。
 * <p>提供本地图片编码为 Base64 String，以及解析 Base64 并预览、保存图片的功能。</p>
 */
public class Base64ImagePanel extends ToolPanel {

    // 图片转 Base64 组件
    private JLabel uploadPreview;
    private JTextArea base64Output;
    private byte[] loadedImageBytes;
    private String loadedImageFormat = "png";

    // Base64 转图片组件
    private JTextArea base64Input;
    private JLabel downloadPreview;
    private byte[] decodedImageBytes;

    public Base64ImagePanel() {
        super("转换", "Base64 图片转换",
                "Base64", "图片", "Image", "DataURI", "Data URI",
                "图片编码", "图片解码", "图片转换");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new GridLayout(1, 2, 10, 0));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 左半部：图片 -> Base64 =====
        JPanel leftPanel = new JPanel(new BorderLayout(8, 8));
        leftPanel.setBorder(BorderFactory.createTitledBorder("图片 转 Base64"));

        uploadPreview = new JLabel("未加载图片", SwingConstants.CENTER);
        uploadPreview.setOpaque(true);
        uploadPreview.setBackground(UIManager.getColor("Panel.background"));
        uploadPreview.setBorder(BorderFactory.createDashedBorder(null, 2, 4, 2, false));
        uploadPreview.setPreferredSize(new Dimension(200, 200));

        base64Output = new JTextArea(8, 20);
        base64Output.setFont(UIUtils.monoFont());
        base64Output.setLineWrap(true);
        base64Output.setEditable(false);

        JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton selectImgBtn = UIUtils.button("选择图片...", 100);
        JButton copyBase64Btn = UIUtils.button("复制 Base64", 100);
        leftActions.add(selectImgBtn);
        leftActions.add(copyBase64Btn);

        leftPanel.add(uploadPreview, BorderLayout.NORTH);
        leftPanel.add(UIUtils.scrollText(base64Output, "输出 Base64 String"), BorderLayout.CENTER);
        leftPanel.add(leftActions, BorderLayout.SOUTH);

        // ===== 右半部：Base64 -> 图片 =====
        JPanel rightPanel = new JPanel(new BorderLayout(8, 8));
        rightPanel.setBorder(BorderFactory.createTitledBorder("Base64 转 图片"));

        downloadPreview = new JLabel("输入 Base64 并点击解析后在此预览", SwingConstants.CENTER);
        downloadPreview.setOpaque(true);
        downloadPreview.setBackground(UIManager.getColor("Panel.background"));
        downloadPreview.setBorder(BorderFactory.createDashedBorder(null, 2, 4, 2, false));
        downloadPreview.setPreferredSize(new Dimension(200, 200));

        base64Input = new JTextArea(8, 20);
        base64Input.setFont(UIUtils.monoFont());
        base64Input.setLineWrap(true);

        JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton parseBtn = UIUtils.button("解析并预览", 100);
        JButton saveBtn = UIUtils.button("保存图片...", 100);
        JButton clearRightBtn = UIUtils.button("清空", 70);
        rightActions.add(parseBtn);
        rightActions.add(saveBtn);
        rightActions.add(clearRightBtn);

        rightPanel.add(downloadPreview, BorderLayout.NORTH);
        rightPanel.add(UIUtils.scrollText(base64Input, "输入 Base64 String"), BorderLayout.CENTER);
        rightPanel.add(rightActions, BorderLayout.SOUTH);

        // ===== 拼装主面板 =====
        root.add(leftPanel);
        root.add(rightPanel);

        // ===== 事件绑定 =====
        selectImgBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("图片文件 (*.png, *.jpg, *.jpeg, *.gif, *.bmp)",
                    "png", "jpg", "jpeg", "gif", "bmp"));
            if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                try {
                    File file = chooser.getSelectedFile();
                    String name = file.getName().toLowerCase();
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) loadedImageFormat = "jpeg";
                    else if (name.endsWith(".gif")) loadedImageFormat = "gif";
                    else if (name.endsWith(".bmp")) loadedImageFormat = "bmp";
                    else loadedImageFormat = "png";

                    loadedImageBytes = Files.readAllBytes(file.toPath());
                    ImageIcon icon = scaleImage(loadedImageBytes, 180, 180);
                    if (icon != null) {
                        uploadPreview.setText("");
                        uploadPreview.setIcon(icon);
                    } else {
                        uploadPreview.setText("加载图片失败");
                        uploadPreview.setIcon(null);
                    }

                    // 转换为 Data URI Scheme 格式
                    String base64Str = Base64.getEncoder().encodeToString(loadedImageBytes);
                    base64Output.setText("data:image/" + loadedImageFormat + ";base64," + base64Str);
                } catch (Exception ex) {
                    UIUtils.error(root, "图片转换失败: " + ex.getMessage());
                }
            }
        });

        copyBase64Btn.addActionListener(e -> {
            String text = base64Output.getText();
            if (!text.isEmpty()) {
                UIUtils.copyToClipboard(text);
                UIUtils.info(root, "Base64 数据已成功复制到剪贴板。");
            }
        });

        parseBtn.addActionListener(e -> {
            String rawInput = base64Input.getText().trim();
            if (rawInput.isEmpty()) {
                UIUtils.error(root, "请输入 Base64 字符串");
                return;
            }
            try {
                // 剔除 "data:image/xxx;base64," 的前缀（如果包含）
                if (rawInput.contains("base64,")) {
                    rawInput = rawInput.substring(rawInput.indexOf("base64,") + 7);
                }

                decodedImageBytes = Base64.getDecoder().decode(rawInput);
                ImageIcon icon = scaleImage(decodedImageBytes, 180, 180);
                if (icon != null) {
                    downloadPreview.setText("");
                    downloadPreview.setIcon(icon);
                } else {
                    downloadPreview.setText("无法解析该图片，可能数据不完整");
                    downloadPreview.setIcon(null);
                }
            } catch (Exception ex) {
                UIUtils.error(root, "解析 Base64 失败：" + ex.getMessage());
            }
        });

        saveBtn.addActionListener(e -> {
            if (decodedImageBytes == null) {
                UIUtils.error(root, "请先输入并成功解析图片");
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("decoded_image.png"));
            if (chooser.showSaveDialog(root) == JFileChooser.APPROVE_OPTION) {
                try {
                    File file = chooser.getSelectedFile();
                    Files.write(file.toPath(), decodedImageBytes);
                    UIUtils.info(root, "保存成功！\n文件路径: " + file.getAbsolutePath());
                } catch (Exception ex) {
                    UIUtils.error(root, "保存失败: " + ex.getMessage());
                }
            }
        });

        clearRightBtn.addActionListener(e -> {
            base64Input.setText("");
            downloadPreview.setIcon(null);
            downloadPreview.setText("输入 Base64 并点击解析后在此预览");
            decodedImageBytes = null;
        });

        return root;
    }

    private ImageIcon scaleImage(byte[] bytes, int maxW, int maxH) {
        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
            BufferedImage img = ImageIO.read(bis);
            if (img == null) return null;
            int iw = img.getWidth();
            int ih = img.getHeight();
            double ratio = Math.min((double) maxW / iw, (double) maxH / ih);
            if (ratio < 1.0) {
                int w = (int) (iw * ratio);
                int h = (int) (ih * ratio);
                Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
                return new ImageIcon(scaled);
            }
            return new ImageIcon(img);
        } catch (Exception e) {
            return null;
        }
    }
}
