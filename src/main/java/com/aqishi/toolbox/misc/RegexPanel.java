package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则表达式测试面板：实时匹配高亮、分组捕获、匹配计数。
 */
public class RegexPanel extends ToolPanel {

    public RegexPanel() {
        super("杂项", "正则测试");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 正则输入行
        JPanel top = new JPanel(new BorderLayout(6, 0));
        JTextField pattern = new JTextField("\\d+");
        pattern.setFont(UIUtils.monoFont());
        JCheckBox global = new JCheckBox("全局", true);
        JCheckBox multiline = new JCheckBox("多行", false);
        JCheckBox caseIns = new JCheckBox("忽略大小写", false);
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        flags.add(global); flags.add(multiline); flags.add(caseIns);
        top.add(new JLabel("正则  "), BorderLayout.WEST);
        top.add(pattern, BorderLayout.CENTER);
        top.add(flags, BorderLayout.EAST);
        root.add(top, BorderLayout.NORTH);

        // 文本区
        JTextArea input = new JTextArea(6, 40);
        input.setFont(UIUtils.monoFont());
        input.setText("订单A123，金额45.6元，日期2024-01-01；订单B456，金额78.9元。");
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(input, "待匹配文本"),
                UIUtils.scrollText(new JTextArea(), "匹配结果（高亮用【】标记）"));
        split.setResizeWeight(0.4);
        root.add(split, BorderLayout.CENTER);

        JTextArea out = (JTextArea) ((JScrollPane) split.getBottomComponent()).getViewport().getView();
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);

        Runnable doMatch = () -> {
            String p = pattern.getText();
            String text = input.getText();
            try {
                int opts = 0;
                if (multiline.isSelected()) opts |= Pattern.MULTILINE;
                if (caseIns.isSelected()) opts |= Pattern.CASE_INSENSITIVE;
                Pattern compiled = Pattern.compile(p, opts);
                Matcher m = compiled.matcher(text);

                StringBuilder sb = new StringBuilder();
                int count = 0;
                int last = 0;
                while (m.find()) {
                    sb.append(text, last, m.start());
                    sb.append('【').append(m.group()).append('】');
                    last = m.end();
                    count++;
                    if (!global.isSelected()) break;
                }
                sb.append(text, last, text.length());
                sb.append("\n\n匹配数: ").append(count);
                if (count > 0) {
                    sb.append("\n分组详情:");
                    m.reset();
                    int gi = 0;
                    while (m.find()) {
                        sb.append("\n  #").append(gi++).append(" => ").append(m.group());
                        for (int g = 1; g <= m.groupCount(); g++) {
                            sb.append("  $").append(g).append("=").append(m.group(g));
                        }
                        if (!global.isSelected()) break;
                    }
                }
                out.setText(sb.toString());
            } catch (Exception ex) {
                out.setText("正则错误：" + ex.getMessage());
            }
        };

        pattern.addActionListener(e -> doMatch.run());
        for (Component c : flags.getComponents()) ((AbstractButton) c).addItemListener(e -> doMatch.run());
        input.getDocument().addDocumentListener(new SimpleDocListener(doMatch));
        pattern.getDocument().addDocumentListener(new SimpleDocListener(doMatch));
        doMatch.run();

        return root;
    }

    /** 文档变化即触发，简易适配器 */
    private static class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable r;
        SimpleDocListener(Runnable r) { this.r = r; }
        public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
}
