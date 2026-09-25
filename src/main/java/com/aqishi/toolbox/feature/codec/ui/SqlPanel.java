package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.codec.domain.SqlFormatter;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;

/**
 * SQL 格式化 / 美化面板。
 */
public class SqlPanel extends AbstractFormatPanel {

    public SqlPanel() {
        super(ToolCatalog.SQL_FORMAT);
    }

    @Override
    protected JComponent build() {
        JTextArea out = Fields.output(10, 40);
        this.outputComponent = out;

        JComponent root = buildCommonFormatUI("SQL 工具", 
            "select id, name, age from users left join roles on users.role_id = roles.id where age > 18 and status = 'active' order by age desc limit 10", 
            bar -> {
                JButton pretty = Buttons.primary("格式化");
                JButton compress = Buttons.secondary("压缩 SQL");

                pretty.addActionListener(e -> {
                    out.setText(SqlFormatter.format(inputArea.getText()));
                });
                compress.addActionListener(e -> {
                    out.setText(SqlFormatter.compress(inputArea.getText()));
                });

                bar.right(compress);
                bar.right(pretty);
            });

        addOutputView("TEXT", out);
        
        // 默认触发一次格式化
        out.setText(SqlFormatter.format(inputArea.getText()));

        return root;
    }
}
