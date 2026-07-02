package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.UUID;

/**
 * UUID 生成面板：批量生成、可选去横线、可选大写、一键复制。
 */
public class UuidPanel extends ToolPanel {

    public UuidPanel() {
        super("generate", "uuid.generator",
                "UUID", "GUID", "唯一标识", "随机数",
                "批量生成");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("数量"));
        JSpinner count = new JSpinner(new SpinnerNumberModel(5, 1, 1000, 1));
        top.add(count);
        JCheckBox noDash = new JCheckBox("去横线");
        JCheckBox upper = new JCheckBox("大写");
        top.add(noDash); top.add(upper);
        JButton gen = UIUtils.button("生成", 80);
        JButton copyAll = UIUtils.button("全部复制", 100);
        top.add(gen); top.add(copyAll);
        root.add(top, BorderLayout.NORTH);

        JTextArea out = new JTextArea(16, 36);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);
        root.add(UIUtils.scrollText(out, "UUID 列表"), BorderLayout.CENTER);

        Runnable generate = () -> {
            int n = (Integer) count.getValue();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                String u = UUID.randomUUID().toString();
                if (noDash.isSelected()) u = u.replace("-", "");
                if (upper.isSelected()) u = u.toUpperCase();
                sb.append(u).append('\n');
            }
            out.setText(sb.toString());
        };
        gen.addActionListener(e -> generate.run());
        generate.run();

        copyAll.addActionListener(e -> {
            StringSelection sel = new StringSelection(out.getText());
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
            UIUtils.info(root, "已复制到剪贴板");
        });

        return root;
    }
}
