package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 进制转换 + 编码转换面板。
 * <ul>
 *   <li>二/八/十/十六进制互转</li>
 *   <li>UTF-8 / GBK / ISO-8859-1 / URL 编码互转</li>
 * </ul>
 */
public class ConvertPanel extends ToolPanel {

    public ConvertPanel() {
        super("convert", "radix.encoding",
                "二进制", "八进制", "十进制", "十六进制", "Hex",
                "UTF-8", "UTF8", "GBK", "ISO-8859-1", "URL编码", "URL解码",
                "进制转换", "编码转换", "字符编码");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 10));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("进制转换", buildRadixTab());
        tabs.addTab("编码转换", buildEncodingTab());
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    /** 进制转换 */
    private JComponent buildRadixTab() {
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        JTextField dec = field(), oct = field(), hex = field(), bin = field();

        c.gridx = 0; c.gridy = 0; p.add(label("十进制"), c);
        c.gridx = 1; c.weightx = 1; p.add(dec, c);
        c.gridx = 0; c.gridy = 1; c.weightx = 0; p.add(label("八进制"), c);
        c.gridx = 1; c.weightx = 1; p.add(oct, c);
        c.gridx = 0; c.gridy = 2; c.weightx = 0; p.add(label("十六进制"), c);
        c.gridx = 1; c.weightx = 1; p.add(hex, c);
        c.gridx = 0; c.gridy = 3; c.weightx = 0; p.add(label("二进制"), c);
        c.gridx = 1; c.weightx = 1; p.add(bin, c);

        // 任一输入框回车即同步其它三个
        dec.addActionListener(e -> syncFrom(Long.parseLong(dec.getText().trim()), dec, oct, hex, bin));
        oct.addActionListener(e -> syncFrom(Long.parseLong(oct.getText().trim(), 8), dec, oct, hex, bin));
        hex.addActionListener(e -> syncFrom(Long.parseLong(hex.getText().trim(), 16), dec, oct, hex, bin));
        bin.addActionListener(e -> syncFrom(Long.parseLong(bin.getText().replace(" ", "").trim(), 2), dec, oct, hex, bin));

        JButton sync = UIUtils.button("转换", 90);
        sync.addActionListener(e -> {
            try {
                if (!dec.getText().trim().isEmpty())
                    syncFrom(Long.parseLong(dec.getText().trim()), dec, oct, hex, bin);
                else if (!hex.getText().trim().isEmpty())
                    syncFrom(Long.parseLong(hex.getText().trim(), 16), dec, oct, hex, bin);
                else if (!oct.getText().trim().isEmpty())
                    syncFrom(Long.parseLong(oct.getText().trim(), 8), dec, oct, hex, bin);
                else if (!bin.getText().trim().isEmpty())
                    syncFrom(Long.parseLong(bin.getText().replace(" ", "").trim(), 2), dec, oct, hex, bin);
            } catch (Exception ex) {
                UIUtils.error(p, "解析失败：" + ex.getMessage());
            }
        });

        c.gridx = 0; c.gridy = 4; c.gridwidth = 2; p.add(sync, c);
        return p;
    }

    private void syncFrom(long v, JTextField dec, JTextField oct, JTextField hex, JTextField bin) {
        dec.setText(String.valueOf(v));
        oct.setText(Long.toOctalString(v));
        hex.setText(Long.toHexString(v));
        bin.setText(formatBinary(Long.toBinaryString(v)));
    }

    private static String formatBinary(String binStr) {
        String clean = binStr.replace(" ", "");
        StringBuilder sb = new StringBuilder();
        int len = clean.length();
        for (int i = 0; i < len; i++) {
            sb.append(clean.charAt(i));
            int distToRight = len - 1 - i;
            if (distToRight > 0 && distToRight % 4 == 0) {
                sb.append(" ");
            }
        }
        return sb.toString();
    }

    /** 编码转换 */
    private JComponent buildEncodingTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        JTextArea input = new JTextArea(4, 30);
        input.setFont(UIUtils.monoFont());
        p.add(UIUtils.scrollText(input, "原始文本"), BorderLayout.NORTH);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        String[] ops = {"UTF-8", "GBK", "ISO-8859-1", "URL", "反转义URL"};
        for (String op : ops) {
            JButton b = UIUtils.button(op, 100);
            btns.add(b);
        }
        p.add(btns, BorderLayout.CENTER);

        JTextArea out = new JTextArea(6, 30);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);
        p.add(UIUtils.scrollText(out, "结果"), BorderLayout.SOUTH);

        // 绑定事件
        for (Component comp : btns.getComponents()) {
            if (comp instanceof JButton) {
                JButton b = (JButton) comp;
                b.addActionListener(e -> {
                    String text = input.getText();
                    String op = b.getText();
                    try {
                        switch (op) {
                            case "UTF-8":
                                out.setText(hexString(text.getBytes(StandardCharsets.UTF_8)));
                                break;
                            case "GBK":
                                out.setText(hexString(text.getBytes("GBK")));
                                break;
                            case "ISO-8859-1":
                                out.setText(hexString(text.getBytes(StandardCharsets.ISO_8859_1)));
                                break;
                            case "URL":
                                out.setText(URLEncoder.encode(text, StandardCharsets.UTF_8.name()));
                                break;
                            case "反转义URL":
                                out.setText(URLDecoder.decode(text, StandardCharsets.UTF_8.name()));
                                break;
                        }
                    } catch (Exception ex) {
                        UIUtils.error(p, ex.getMessage());
                    }
                });
            }
        }
        return p;
    }

    private static String hexString(byte[] bytes) throws UnsupportedEncodingException {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xff));
        }
        return sb.toString().trim();
    }

    private static JLabel label(String t) {
        JLabel l = new JLabel(t);
        l.setFont(UIUtils.plainFont());
        return l;
    }

    private static JTextField field() {
        JTextField f = new JTextField();
        f.setFont(UIUtils.monoFont());
        return f;
    }
}
