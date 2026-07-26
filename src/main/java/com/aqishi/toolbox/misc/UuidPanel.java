package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
        JPanel root = Layouts.page();

        JSpinner count = Fields.spinner(5, 1, 1000, 1);
        JCheckBox noDash = Fields.check("去横线", false);
        JCheckBox upper = Fields.check("大写", false);

        // 微调器自身宽度固定，用 BorderLayout.WEST 兜住，避免被表单列拉成整行宽
        JPanel countWrap = Layouts.box();
        countWrap.add(count, BorderLayout.WEST);

        JPanel switches = new JPanel(new FlowLayout(FlowLayout.LEFT, Tokens.SPACE_MD, 0));
        switches.setOpaque(false);
        switches.add(noDash);
        switches.add(upper);

        FormGrid form = new FormGrid();
        form.row("数量", countWrap);
        form.fullRow(switches);

        // 主操作提到卡片标题栏：配置只有两行，单独留一条按钮行会把结果区无谓地往下推
        JButton gen = Buttons.primary("生成");
        Card config = Card.titled("生成选项");
        config.setContent(form);
        config.addHeaderAction(gen);

        // 行数只作为首选高度的下限，取小值让窄窗口下短列表不会平白出现滚动条
        JTextArea out = Fields.output(8, 36);
        JButton copyAll = Buttons.ghost("全部复制");
        Card result = Card.flush("UUID 列表");
        result.setContent(Fields.scroll(out));
        result.addHeaderAction(copyAll);

        // 配置固定高度贴顶，列表吸收剩余高度：批量 1000 条时才有足够的展开空间
        root.add(config, BorderLayout.NORTH);
        root.add(result, BorderLayout.CENTER);

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
