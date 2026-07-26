package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Iterator;

/**
 * Base64 图片互转面板。
 * <p>所有耗时操作（文件读取、Base64编解码、图片解码）均在后台线程执行。</p>
 */
public class Base64ImagePanel extends ToolPanel {

    private static final int DISPLAY_LIMIT = 5000;

    // 图片转 Base64
    private JLabel uploadPreview;
    private JTextArea base64Output;
    private byte[] loadedImageBytes;
    private String loadedImageFormat = "png";
    private String fullBase64Output = "";

    // Base64 转图片
    private JTextArea base64Input;
    private JLabel downloadPreview;
    private byte[] decodedImageBytes;

    public Base64ImagePanel() {
        super("convert", "base64.image",
                "Base64", "图片", "Image", "DataURI", "Data URI",
                "图片编码", "图片解码", "图片转换");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 左半部：图片 -> Base64 =====
        // 预览标签不设 opaque，直接透出卡片底色；JLabel 只会居中摆放图标而不会缩放它，
        // 所以小图不会被拉伸变形；scaleImagePreview 已把大图压到 180px 以内，
        // 再包一层滚动区兜底——窗口被拖到比图还窄时可以滚动查看而不是被裁掉。
        uploadPreview = new JLabel("未加载图片", SwingConstants.CENTER);
        uploadPreview.setPreferredSize(new Dimension(200, 200));

        base64Output = Fields.output(8, 20);
        base64Output.setLineWrap(false);

        JButton selectImgBtn = Buttons.primary("选择图片...");
        JButton copyBase64Btn = Buttons.ghost("复制 Base64");

        Card uploadPreviewCard = new Card("预览", "图片 转 Base64").setFlush(true);
        uploadPreviewCard.setContent(transparentScroll(uploadPreview));
        uploadPreviewCard.addHeaderAction(selectImgBtn);

        Card base64OutCard = Card.flush("输出 Base64 String");
        base64OutCard.setContent(Fields.scroll(base64Output));
        base64OutCard.addHeaderAction(copyBase64Btn);

        // 预览高度自适应放 NORTH，Base64 文本放 CENTER 吃掉剩余高度
        JPanel leftPanel = Layouts.box(0, Tokens.SPACE_LG);
        leftPanel.add(uploadPreviewCard, BorderLayout.NORTH);
        leftPanel.add(base64OutCard, BorderLayout.CENTER);

        // ===== 右半部：Base64 -> 图片 =====
        downloadPreview = new JLabel("输入 Base64 并点击解析后在此预览", SwingConstants.CENTER);
        downloadPreview.setPreferredSize(new Dimension(200, 200));

        base64Input = Fields.area(8, 20);
        base64Input.setLineWrap(false);

        JButton parseBtn = Buttons.primary("解析并预览");
        JButton saveBtn = Buttons.secondary("保存图片...");
        JButton clearRightBtn = Buttons.ghost("清空");

        Card downloadPreviewCard = new Card("预览", "Base64 转 图片").setFlush(true);
        downloadPreviewCard.setContent(transparentScroll(downloadPreview));
        downloadPreviewCard.addHeaderAction(saveBtn);

        Card base64InCard = Card.flush("输入 Base64 String");
        base64InCard.setContent(Fields.scroll(base64Input));
        base64InCard.addHeaderAction(clearRightBtn);
        base64InCard.addHeaderAction(parseBtn);

        JPanel rightPanel = Layouts.box(0, Tokens.SPACE_LG);
        rightPanel.add(downloadPreviewCard, BorderLayout.NORTH);
        rightPanel.add(base64InCard, BorderLayout.CENTER);

        // 两个方向是彼此独立的转换，用可拖动的分栏而不是等分 GridLayout，
        // 需要贴长 Base64 时可以把分隔条往左推
        root.add(Layouts.splitHorizontal(leftPanel, rightPanel, 0.5), BorderLayout.CENTER);

        // ===== 事件：选择图片 =====
        selectImgBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("图片文件 (*.png, *.jpg, *.jpeg, *.gif, *.bmp)",
                    "png", "jpg", "jpeg", "gif", "bmp"));
            if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;

            File file = chooser.getSelectedFile();
            selectImgBtn.setEnabled(false);
            selectImgBtn.setText("加载中...");
            uploadPreview.setIcon(null);
            uploadPreview.setText("正在处理...");

            new Thread(() -> {
                try {
                    String name = file.getName().toLowerCase();
                    String fmt;
                    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) fmt = "jpeg";
                    else if (name.endsWith(".gif")) fmt = "gif";
                    else if (name.endsWith(".bmp")) fmt = "bmp";
                    else fmt = "png";

                    byte[] raw = Files.readAllBytes(file.toPath());
                    ImageIcon icon = scaleImagePreview(raw, 180, 180);
                    String b64 = Base64.getEncoder().encodeToString(raw);
                    String full = "data:image/" + fmt + ";base64," + b64;

                    SwingUtilities.invokeLater(() -> {
                        loadedImageFormat = fmt;
                        loadedImageBytes = raw;
                        fullBase64Output = full;

                        if (icon != null) {
                            uploadPreview.setText("");
                            uploadPreview.setIcon(icon);
                        } else {
                            uploadPreview.setText("加载图片失败");
                        }

                        if (full.length() > DISPLAY_LIMIT) {
                            base64Output.setText(full.substring(0, DISPLAY_LIMIT)
                                    + "\n\n... (已截断，共 " + full.length()
                                    + " 字符，点击「复制 Base64」获取完整内容)");
                        } else {
                            base64Output.setText(full);
                        }
                        selectImgBtn.setEnabled(true);
                        selectImgBtn.setText("选择图片...");
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        uploadPreview.setText("处理失败");
                        UIUtils.error(root, "图片转换失败: " + ex.getMessage());
                        selectImgBtn.setEnabled(true);
                        selectImgBtn.setText("选择图片...");
                    });
                }
            }).start();
        });

        copyBase64Btn.addActionListener(e -> {
            if (!fullBase64Output.isEmpty()) {
                UIUtils.copyToClipboard(fullBase64Output);
                UIUtils.info(root, "Base64 数据已成功复制到剪贴板。\n共 " + fullBase64Output.length() + " 字符。");
            }
        });

        // ===== 事件：解析 Base64 =====
        parseBtn.addActionListener(e -> {
            String rawInput = base64Input.getText().trim();
            if (rawInput.isEmpty()) {
                UIUtils.error(root, "请输入 Base64 字符串");
                return;
            }
            if (rawInput.contains("base64,")) {
                rawInput = rawInput.substring(rawInput.indexOf("base64,") + 7);
            }
            final String data = rawInput;

            parseBtn.setEnabled(false);
            parseBtn.setText("解析中...");
            downloadPreview.setIcon(null);
            downloadPreview.setText("正在解码...");

            new Thread(() -> {
                try {
                    byte[] raw = Base64.getDecoder().decode(data);
                    ImageIcon icon = scaleImagePreview(raw, 180, 180);

                    SwingUtilities.invokeLater(() -> {
                        decodedImageBytes = raw;
                        if (icon != null) {
                            downloadPreview.setText("");
                            downloadPreview.setIcon(icon);
                        } else {
                            downloadPreview.setText("无法解析该图片，可能数据不完整");
                        }
                        parseBtn.setEnabled(true);
                        parseBtn.setText("解析并预览");
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        downloadPreview.setText("解析失败");
                        UIUtils.error(root, "解析 Base64 失败：" + ex.getMessage());
                        parseBtn.setEnabled(true);
                        parseBtn.setText("解析并预览");
                    });
                }
            }).start();
        });

        // ===== 事件：保存 / 清空 =====
        saveBtn.addActionListener(e -> {
            if (decodedImageBytes == null) {
                UIUtils.error(root, "请先输入并成功解析图片");
                return;
            }
            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("decoded_image.png"));
            if (chooser.showSaveDialog(root) == JFileChooser.APPROVE_OPTION) {
                try {
                    Files.write(chooser.getSelectedFile().toPath(), decodedImageBytes);
                    UIUtils.info(root, "保存成功！");
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

    /**
     * 预览用的滚动区：视口设为透明，让卡片底色直接透出来，
     * 否则视口会用 LAF 的控件底色画一块灰底，跟旁边的文本卡片对不上。
     */
    private static JScrollPane transparentScroll(Component view) {
        JScrollPane scroll = Fields.scroll(view);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        return scroll;
    }

    private ImageIcon scaleImagePreview(byte[] bytes, int maxW, int maxH) {
        ImageInputStream iis = null;
        ImageReader reader = null;
        try {
            iis = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes));
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) return null;

            reader = readers.next();
            reader.setInput(iis, false, false);

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);

            int subsample = 1;
            while (width / subsample > maxW * 2 || height / subsample > maxH * 2) {
                subsample *= 2;
            }

            ImageReadParam param = reader.getDefaultReadParam();
            if (subsample > 1) {
                param.setSourceSubsampling(subsample, subsample, 0, 0);
            }

            BufferedImage img = reader.read(0, param);
            if (img == null) return null;

            int iw = img.getWidth();
            int ih = img.getHeight();
            double ratio = Math.min((double) maxW / iw, (double) maxH / ih);
            if (ratio < 1.0) {
                int w = (int) (iw * ratio);
                int h = (int) (ih * ratio);
                Image scaled = img.getScaledInstance(w, h, Image.SCALE_SMOOTH);
                img.flush();
                return new ImageIcon(scaled);
            }
            return new ImageIcon(img);

        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) reader.dispose();
            if (iis != null) try { iis.close(); } catch (Exception ignored) {}
        }
    }
}
