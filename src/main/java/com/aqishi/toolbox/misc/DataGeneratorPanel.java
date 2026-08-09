package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.KitBorders;

import javax.swing.*;
import java.awt.*;

/**
 * 统一的数据生成器面板。
 * 将原有的密码生成、UUID生成、以及测试假数据生成合并为多标签视图。
 */
public class DataGeneratorPanel extends ToolPanel {

    public DataGeneratorPanel() {
        super("generate", "data.generator", "生成", "Generator", "数据", "密码", "UUID", "假数据", "Mock");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();
        
        // 消除默认 Page 的大 Padding，让 Tab 自己填满，内部自带 Padding
        root.setBorder(KitBorders.padding(4));

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Tokens.fontBody());
        tabs.setBorder(null);

        // 将之前独立面板的 build() 作为 Tab 加入
        tabs.addTab("密码生成", new PasswordPanel().build());
        tabs.addTab("UUID 生成", new UuidPanel().build());
        tabs.addTab("假数据生成", new RandomNumberPanel().build());

        root.add(tabs, BorderLayout.CENTER);
        return root;
    }
}
