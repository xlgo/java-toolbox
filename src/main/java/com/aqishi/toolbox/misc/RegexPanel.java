package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import java.awt.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 正则表达式测试面板：实时匹配高亮、分组捕获、匹配计数。
 */
public class RegexPanel extends ToolPanel {

    public RegexPanel() {
        super("dev", "regex.tester",
                "Regex", "正则表达式", "匹配", "正则",
                "正则匹配", "正则测试", "Pattern");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 配置卡片：正则与开关 =====
        // 本工具是输入即匹配，没有「执行」按钮，所以配置卡不设标题栏动作
        JTextField pattern = Fields.mono("\\d+");

        JCheckBox global = Fields.check("全局", true);
        JCheckBox multiline = Fields.check("多行", false);
        JCheckBox caseIns = Fields.check("忽略大小写", false);
        // flags 只装 AbstractButton：下方按 getComponents() 统一挂监听依赖这一点
        JPanel flags = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_MD, 0));
        flags.setOpaque(false);
        flags.add(global);
        flags.add(multiline);
        flags.add(caseIns);

        // 开关是正则本身的修饰符，作为行尾控件跟输入框同行：
        // 配置区因此只占一行，把省下的高度全部让给下方的文本区
        FormGrid form = new FormGrid();
        form.row("正则", pattern, flags);

        Card config = Card.titled("正则表达式");
        config.setContent(form);

        // ===== 文本区：上下分隔，两侧都铺满各自的卡片 =====
        // 行数只作为首选高度的下限；取小值可让窄窗口下的短样本文本不出现多余滚动条
        JTextArea input = Fields.area(3, 40);
        input.setText("订单A123，金额45.6元，日期2024-01-01；订单B456，金额78.9元。");
        Card inputCard = Card.flush("待匹配文本");
        inputCard.setContent(Fields.scroll(input));

        JTextArea out = Fields.output(6, 40);
        Card outCard = Card.flush("匹配结果（高亮用【】标记）");
        outCard.setContent(Fields.scroll(out));

        // 结果通常比原文长（附带分组明细），把多余空间偏向下半区
        JSplitPane split = Layouts.splitVertical(inputCard, outCard, 0.4);

        root.add(config, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);

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
