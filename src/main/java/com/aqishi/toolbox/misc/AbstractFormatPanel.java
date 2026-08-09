package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * 格式化/美化面板的基础抽象类。
 * 统一双栏布局与 ActionBar 配置，并抽离了清理、复制等通用逻辑。
 */
public abstract class AbstractFormatPanel extends ToolPanel {

    protected JTextArea inputArea;
    protected ActionBar actionBar;
    protected JPanel outputCardPanel;
    protected CardLayout outputCardLayout;
    protected JComponent outputComponent;

    public AbstractFormatPanel(String group, String name, String... tags) {
        super(group, name, tags);
    }

    /**
     * 构建包含标准输入输出双栏卡片的界面。
     *
     * @param configTitle    配置区域标题
     * @param defaultInput   默认输入文本
     * @param actionBarSetup actionBar初始化回调（用于添加美化/压缩等功能按钮）
     * @return 完整的根面板
     */
    protected JComponent buildCommonFormatUI(String configTitle, String defaultInput, Consumer<ActionBar> actionBarSetup) {
        JPanel root = Layouts.page();

        // 1. ActionBar 区域
        actionBar = new ActionBar();
        // 添加通用的清空、复制结果功能
        JButton clearBtn = com.aqishi.toolbox.ui.kit.Buttons.ghost("清空");
        JButton copyBtn = com.aqishi.toolbox.ui.kit.Buttons.ghost("复制结果");
        
        clearBtn.addActionListener(e -> {
            inputArea.setText("");
            if (outputComponent instanceof JTextArea) {
                ((JTextArea) outputComponent).setText("");
            } else if (outputComponent instanceof JTextPane) {
                ((JTextPane) outputComponent).setText("");
            }
        });
        
        copyBtn.addActionListener(e -> {
            String txt = "";
            if (outputComponent instanceof JTextArea) {
                txt = ((JTextArea) outputComponent).getText();
            } else if (outputComponent instanceof JTextPane) {
                txt = ((JTextPane) outputComponent).getText();
            }
            UIUtils.copyToClipboard(txt);
        });

        // 让子类在清空和复制的前面添加自己的动作按钮
        actionBarSetup.accept(actionBar);
        actionBar.right(clearBtn);
        actionBar.right(copyBtn);

        Card configCard = Card.titled(configTitle);
        configCard.setContent(actionBar);

        // 2. 输入区
        inputArea = Fields.area(6, 40);
        inputArea.setText(defaultInput);
        Card inputCard = Card.flush("输入");
        inputCard.setContent(Fields.scroll(inputArea));

        // 3. 输出区 (CardLayout 支持在多视图间切换)
        outputCardLayout = new CardLayout();
        outputCardPanel = new JPanel(outputCardLayout);
        outputCardPanel.setOpaque(false);

        // 组装双栏或上下分隔
        JSplitPane split = Layouts.splitVertical(inputCard, outputCardPanel, 0.4);
        root.add(configCard, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);

        return root;
    }

    /**
     * 向输出区添加一种视图。
     * @param name 视图标识 (用于 CardLayout)
     * @param comp 组件
     */
    protected void addOutputView(String name, JComponent comp) {
        Card outCard = Card.flush(name);
        if (comp instanceof JTree || comp instanceof JTextArea || comp instanceof JTextPane) {
            outCard.setContent(Fields.scroll(comp));
        } else {
            outCard.setContent(comp);
        }
        outputCardPanel.add(outCard, name);
    }
}
