package com.aqishi.toolbox.feature.codec.ui;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.*;

/**
 * 折叠树式格式化面板（JSON / XML）的共同骨架。
 * <p>统一「美化 = 可折叠代码树、压缩 = 单行文本」双视图输出，以及
 * 清空/复制结果/返回源工具等样板交互；子类只需提供文案、默认输入与
 * 具体的解析、格式化实现。</p>
 */
public abstract class AbstractTreeFormatPanel extends ToolPanel {

    protected JTextArea inputArea;
    protected JTree prettyTree;
    protected JTextPane compactPane;
    protected JButton prettyBtn;
    protected String lastResult = "";

    private CardLayout cardLayout;
    private JPanel outputCardPanel;
    private JButton returnBtn;
    private String returnToolId = null;

    protected AbstractTreeFormatPanel(String group, String name, String... searchKeywords) {
        super(group, name, searchKeywords);
    }

    // ===== 子类钩子 =====

    /** 顶部操作卡片标题 */
    protected abstract String configTitle();

    /** 操作条左侧说明文案 */
    protected abstract String caption();

    /** 输入区卡片标题 */
    protected abstract String inputCardTitle();

    /** 首次打开时的默认输入文本 */
    protected abstract String defaultInput();

    /** 折叠树根节点标签（也用于清空后的占位树） */
    protected abstract String treeRootLabel();

    /** 解析失败时错误弹窗前缀，如 “JSON 解析出错：” */
    protected abstract String parseErrorPrefix();

    /** 解析 source 并填充折叠树模型 */
    protected abstract void buildTree(String source) throws Exception;

    /** 返回美化后的纯文本结果 */
    protected abstract String prettyText(String source) throws Exception;

    /** 返回压缩后的单行文本结果 */
    protected abstract String compactText(String source) throws Exception;

    /** 子类在「复制结果」与「压缩」之间插入额外操作按钮 */
    protected void addExtraActions(ActionBar bar) {
    }

    @Override
    protected final JComponent build() {
        JPanel root = Layouts.page();

        // ===== 顶部操作卡片 =====
        prettyBtn = Buttons.primary("美化");
        JButton compact = Buttons.secondary("压缩");
        JButton copy = Buttons.ghost("复制结果");
        JButton clear = Buttons.ghost("清空");
        returnBtn = Buttons.ghost("⬅ 返回源工具");
        returnBtn.setVisible(false);

        returnBtn.addActionListener(e -> {
            if (returnToolId != null) {
                com.aqishi.toolbox.ui.MainFrame mainFrame = com.aqishi.toolbox.ui.MainFrame.getMainFrame(root);
                if (mainFrame != null) {
                    mainFrame.selectTool(returnToolId);
                }
                returnBtn.setVisible(false);
                returnToolId = null;
            }
        });

        ActionBar bar = new ActionBar();
        bar.left(Fields.caption(caption()));
        bar.right(returnBtn);
        bar.right(clear);
        bar.right(copy);
        addExtraActions(bar);
        bar.right(compact);
        bar.right(prettyBtn);
        Card config = Card.titled(configTitle());
        config.setContent(bar);

        inputArea = Fields.area(6, 40);
        inputArea.setText(defaultInput());

        // 输出区使用 CardLayout 在折叠树与压缩文本之间切换。
        // 两个视图各自套一张 flush 卡片，切换 card 时标题也跟着换，
        // 因此事件代码不需要额外维护标题文案。
        cardLayout = new CardLayout();
        outputCardPanel = new JPanel(cardLayout);
        outputCardPanel.setOpaque(false);

        // 1. 美化可折叠代码树卡片
        prettyTree = new JTree(new DefaultMutableTreeNode(treeRootLabel()));
        prettyTree.setFont(UIUtils.monoFont());
        prettyTree.putClientProperty("JTree.lineStyle", "None");
        prettyTree.setRootVisible(true);
        prettyTree.setShowsRootHandles(true);
        prettyTree.setRowHeight(20);
        prettyTree.setCellRenderer(new CodeTreeCellRenderer());

        Card treeCard = Card.flush("结果 (点击左侧三角箭头折叠/展开)");
        treeCard.setContent(Fields.scroll(prettyTree));
        outputCardPanel.add(treeCard, "PRETTY");

        // 2. 压缩视图卡片
        compactPane = new JTextPane();
        compactPane.setEditable(false);
        compactPane.setFont(UIUtils.monoFont());
        Card compactCard = Card.flush("结果 (压缩)");
        compactCard.setContent(Fields.scroll(compactPane));
        outputCardPanel.add(compactCard, "COMPACT");

        Card inputCard = Card.flush(inputCardTitle());
        inputCard.setContent(Fields.scroll(inputArea));

        // 左输入 / 右结果：横向分栏后拖窗口时两侧同时变宽，
        // 长行与深层嵌套的树节点都少一次横向滚动。
        root.add(config, BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(inputCard, outputCardPanel, 0.5), BorderLayout.CENTER);

        prettyBtn.addActionListener(e -> {
            String source = inputArea.getText().trim();
            if (source.isEmpty()) return;
            try {
                buildTree(source);
                lastResult = prettyText(source);
                cardLayout.show(outputCardPanel, "PRETTY");
            } catch (Exception ex) {
                UIUtils.error(root, parseErrorPrefix() + ex.getMessage());
            }
        });

        compact.addActionListener(e -> {
            String source = inputArea.getText().trim();
            if (source.isEmpty()) return;
            try {
                lastResult = compactText(source);
                compactPane.setText(lastResult);
                cardLayout.show(outputCardPanel, "COMPACT");
            } catch (Exception ex) {
                UIUtils.error(root, parseErrorPrefix() + ex.getMessage());
            }
        });

        copy.addActionListener(e -> {
            if (!lastResult.isEmpty()) {
                UIUtils.copyToClipboard(lastResult);
            }
        });

        clear.addActionListener(e -> {
            inputArea.setText("");
            compactPane.setText("");
            lastResult = "";
            prettyTree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode(treeRootLabel())));
        });

        prettyBtn.doClick();

        return root;
    }

    /**
     * 供其它工具跳转过来时直接填入文本并触发美化，
     * 同时记录来源工具以便展示「返回」按钮。
     */
    public final void formatTextWithReturn(String text, String sourceToolId, String sourceToolName) {
        getView();
        if (inputArea != null) {
            inputArea.setText(text != null ? text : "");
            if (prettyBtn != null) {
                prettyBtn.doClick();
            }
        }
        if (sourceToolId != null && sourceToolName != null) {
            this.returnToolId = sourceToolId;
            if (returnBtn != null) {
                returnBtn.setText("⬅ 返回 " + sourceToolName);
                returnBtn.setVisible(true);
            }
        }
    }

    /** HTML 文本转义，用于折叠树节点的行内着色 HTML */
    protected static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
