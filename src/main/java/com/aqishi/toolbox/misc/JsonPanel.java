package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * JSON 格式化 / 压缩面板。
 */
public class JsonPanel extends ToolPanel {

    public JsonPanel() {
        super("杂项", "JSON 格式化");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton pretty = UIUtils.button("美化", 80);
        JButton compact = UIUtils.button("压缩", 80);
        JButton copy = UIUtils.button("复制结果", 100);
        JButton clear = UIUtils.button("清空", 80);
        btns.add(pretty); btns.add(compact); btns.add(copy); btns.add(clear);
        root.add(btns, BorderLayout.NORTH);

        JTextArea input = new JTextArea(8, 40);
        input.setFont(UIUtils.monoFont());
        input.setText("{\"name\":\"工具箱\",\"version\":1.0,\"features\":[\"加密\",\"转换\",\"算法\",\"计算\"]}");
        JTextArea out = new JTextArea(10, 40);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(input, "输入 JSON"),
                UIUtils.scrollText(out, "输出"));
        split.setResizeWeight(0.4);
        root.add(split, BorderLayout.CENTER);

        pretty.addActionListener(e -> {
            try { out.setText(JsonFormatter.pretty(input.getText())); }
            catch (Exception ex) { UIUtils.error(root, ex.getMessage()); }
        });
        compact.addActionListener(e -> {
            try { out.setText(JsonFormatter.compact(input.getText())); }
            catch (Exception ex) { UIUtils.error(root, ex.getMessage()); }
        });
        copy.addActionListener(e -> UIUtils.copyToClipboard(out.getText()));
        clear.addActionListener(e -> { input.setText(""); out.setText(""); });
        pretty.doClick();

        return root;
    }
}
