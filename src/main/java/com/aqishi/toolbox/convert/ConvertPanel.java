package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
        JPanel root = Layouts.page();

        // 两类转换差异较大，保留标签页；去掉 JTabbedPane 自身的描边，
        // 内边距统一交给每个标签页内部的 tabPage()，避免和 page() 的外边距叠加过厚。
        JTabbedPane tabs = new JTabbedPane();
        tabs.setBorder(null);
        tabs.addTab("进制转换", buildRadixTab());
        tabs.addTab("编码转换", buildEncodingTab());
        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    /** 标签页内容容器：只补一层比 page() 更薄的内边距，纵向留出卡片间距 */
    private static JPanel tabPage() {
        JPanel page = Layouts.box(0, Tokens.SPACE_LG);
        page.setBorder(KitBorders.padding(Tokens.SPACE_MD));
        return page;
    }

    /** 进制转换 */
    private JComponent buildRadixTab() {
        JPanel p = tabPage();

        JTextField dec = Fields.mono("");
        JTextField oct = Fields.mono("");
        JTextField hex = Fields.mono("");
        JTextField bin = Fields.mono("");

        // 四个进制既是输入也是输出，排成一张 FormGrid 让标签右对齐、输入框等宽等高
        FormGrid form = new FormGrid();
        form.row("十进制", dec);
        form.row("八进制", oct);
        form.row("十六进制", hex);
        form.row("二进制", bin);
        form.caption("在任一输入框按回车，其余三个立即同步");
        // 这个标签页没有独立的结果区，四行输入自己就是结果；
        // 用 glue 把行钉在卡片顶部，卡片再吃满剩余高度，避免整块背景空着
        form.glue();

        // 任一输入框回车即同步其它三个
        dec.addActionListener(e -> syncFrom(Long.parseLong(dec.getText().trim()), dec, oct, hex, bin));
        oct.addActionListener(e -> syncFrom(Long.parseLong(oct.getText().trim(), 8), dec, oct, hex, bin));
        hex.addActionListener(e -> syncFrom(Long.parseLong(hex.getText().trim(), 16), dec, oct, hex, bin));
        bin.addActionListener(e -> syncFrom(Long.parseLong(bin.getText().replace(" ", "").trim(), 2), dec, oct, hex, bin));

        JButton sync = Buttons.primary("转换");
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

        // 主操作挂在卡片标题右侧
        Card card = Card.titled("进制互转");
        card.setContent(form);
        card.addHeaderAction(sync);
        p.add(card, BorderLayout.CENTER);
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
        JPanel p = tabPage();

        JTextArea input = Fields.area(4, 30);
        JTextArea out = Fields.output(6, 30);

        // 五个操作互相平级，首个设为主操作，其余次操作；
        // 仍然放在同一个容器里，下面按 getText() 分发的事件绑定不用改
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_SM, 0));
        btns.setOpaque(false);
        String[] ops = {"UTF-8", "GBK", "ISO-8859-1", "URL", "反转义URL"};
        for (int i = 0; i < ops.length; i++) {
            JButton b = i == 0 ? Buttons.primary(ops[i]) : Buttons.secondary(ops[i]);
            btns.add(b);
        }

        Card config = Card.titled("编码方式", "前三个按所选字符集输出十六进制字节，后两个做 URL 编解码");
        config.setContent(btns);

        // 原文与结果左右并排，窗口拉宽时两侧同时变宽
        Card inputCard = Card.flush("原始文本");
        inputCard.setContent(Fields.scroll(input));
        Card outCard = Card.flush("结果");
        outCard.setContent(Fields.scroll(out));

        p.add(config, BorderLayout.NORTH);
        p.add(Layouts.splitHorizontal(inputCard, outCard, 0.5), BorderLayout.CENTER);

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
}
