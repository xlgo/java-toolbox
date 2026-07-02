package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * 颜色转换面板：HEX / RGB / HSL 互转 + 实时预览。
 */
public class ColorPanel extends ToolPanel {

    public ColorPanel() {
        super("dev", "color.convert",
                "HEX", "RGB", "HSL", "调色板", "Color",
                "颜色", "颜色选择", "色值");
    }

    private JTextField hexF, rgbF, hslF;
    private JLabel preview;

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 12));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(6, 6, 6, 6);
        c.fill = GridBagConstraints.HORIZONTAL;

        hexF = field(); rgbF = field(); hslF = field();

        JButton copyHexBtn = UIUtils.button("复制", 60);
        JButton copyRgbBtn = UIUtils.button("复制", 60);
        JButton copyHslBtn = UIUtils.button("复制", 60);

        copyHexBtn.addActionListener(e -> UIUtils.copyToClipboard(hexF.getText()));
        copyRgbBtn.addActionListener(e -> UIUtils.copyToClipboard(rgbF.getText()));
        copyHslBtn.addActionListener(e -> UIUtils.copyToClipboard(hslF.getText()));

        c.gridx = 0; c.gridy = 0; c.weightx = 0; form.add(label("HEX (#RRGGBB)"), c);
        c.gridx = 1; c.weightx = 1; form.add(hexF, c);
        c.gridx = 2; c.weightx = 0; form.add(copyHexBtn, c);

        c.gridx = 0; c.gridy = 1; c.weightx = 0; form.add(label("RGB (r,g,b)"), c);
        c.gridx = 1; c.weightx = 1; form.add(rgbF, c);
        c.gridx = 2; c.weightx = 0; form.add(copyRgbBtn, c);

        c.gridx = 0; c.gridy = 2; c.weightx = 0; form.add(label("HSL (h,s%,l%)"), c);
        c.gridx = 1; c.weightx = 1; form.add(hslF, c);
        c.gridx = 2; c.weightx = 0; form.add(copyHslBtn, c);

        preview = new JLabel("  预览 (点击选择)  ");
        preview.setOpaque(true);
        preview.setPreferredSize(new Dimension(150, 40));
        preview.setHorizontalAlignment(SwingConstants.CENTER);
        preview.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        preview.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                chooseColor();
            }
        });

        JButton chooseBtn = UIUtils.button("选择颜色...", 120);
        chooseBtn.addActionListener(e -> chooseColor());

        c.gridx = 0; c.gridy = 3; c.gridwidth = 1; c.weightx = 0.5;
        form.add(preview, c);
        c.gridx = 1; c.gridy = 3; c.gridwidth = 2; c.weightx = 0.5;
        form.add(chooseBtn, c);

        hexF.addActionListener(e -> fromHex(hexF.getText()));
        rgbF.addActionListener(e -> fromRgb(rgbF.getText()));
        hslF.addActionListener(e -> fromHsl(hslF.getText()));

        hexF.setText("#4285F4");
        fromHex("#4285F4");

        root.add(form, BorderLayout.NORTH);

        // 调色板：常用色快速选取
        JPanel palette = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        String[] paletteColors = {"#000000", "#FFFFFF", "#FF0000", "#00FF00", "#0000FF",
                "#FFFF00", "#FF00FF", "#00FFFF", "#4285F4", "#34A853", "#FBBC05", "#EA4335"};
        for (String pc : paletteColors) {
            JButton b = new JButton();
            b.setBackground(Color.decode(pc));
            b.setPreferredSize(new Dimension(40, 28));
            b.setToolTipText(pc);
            b.addActionListener(e -> fromHex(pc));
            palette.add(b);
        }
        root.add(palette, BorderLayout.SOUTH);

        return root;
    }

    private void chooseColor() {
        Color initialColor = preview.getBackground();
        Color chosen = JColorChooser.showDialog(preview, "选择颜色", initialColor);
        if (chosen != null) {
            apply(chosen);
        }
    }

    private void fromHex(String text) {
        try {
            String t = text.trim();
            if (!t.startsWith("#")) t = "#" + t;
            Color c = Color.decode(t);
            apply(c);
        } catch (Exception ex) {
            UIUtils.error(hexF, "HEX 解析失败");
        }
    }

    private void fromRgb(String text) {
        try {
            String[] p = text.split("[,\\s]+");
            apply(new Color(clamp(Integer.parseInt(p[0].trim())),
                    clamp(Integer.parseInt(p[1].trim())),
                    clamp(Integer.parseInt(p[2].trim()))));
        } catch (Exception ex) {
            UIUtils.error(rgbF, "RGB 解析失败");
        }
    }

    private void fromHsl(String text) {
        try {
            String[] p = text.split("[,\\s%]+");
            float h = Float.parseFloat(p[0]) / 360f;
            float s = Float.parseFloat(p[1]) / 100f;
            float l = Float.parseFloat(p[2]) / 100f;
            apply(hslToRgb(h, s, l));
        } catch (Exception ex) {
            UIUtils.error(hslF, "HSL 解析失败");
        }
    }

    private void apply(Color c) {
        hexF.setText(String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue()));
        rgbF.setText(c.getRed() + "," + c.getGreen() + "," + c.getBlue());
        float[] hsl = rgbToHsl(c);
        hslF.setText(String.format("%.0f,%.0f%%,%.0f%%", hsl[0] * 360, hsl[1] * 100, hsl[2] * 100));
        preview.setBackground(c);
        // 自动选择对比色作为文字色
        double yiq = (c.getRed() * 299 + c.getGreen() * 587 + c.getBlue() * 114) / 1000.0;
        preview.setForeground(yiq >= 128 ? Color.BLACK : Color.WHITE);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    private static float[] rgbToHsl(Color c) {
        float r = c.getRed() / 255f, g = c.getGreen() / 255f, b = c.getBlue() / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float h, s, l = (max + min) / 2;
        if (max == min) { h = s = 0; }
        else {
            float d = max - min;
            s = l > 0.5f ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new float[]{h, s, l};
    }

    private static Color hslToRgb(float h, float s, float l) {
        float r, g, b;
        if (s == 0) { r = g = b = l; }
        else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hue(p, q, h + 1f / 3);
            g = hue(p, q, h);
            b = hue(p, q, h - 1f / 3);
        }
        return new Color(clamp((int) (r * 255)), clamp((int) (g * 255)), clamp((int) (b * 255)));
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 1f / 2) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }

    private static JLabel label(String t) { return new JLabel(t); }
    private static JTextField field() {
        JTextField f = new JTextField();
        f.setFont(UIUtils.monoFont());
        return f;
    }
}
