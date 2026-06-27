package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 文本对比工具面板。
 */
public class TextDiffPanel extends ToolPanel {

    enum DiffType {
        EQUAL, INSERT, DELETE
    }

    static class DiffEntry {
        DiffType type;
        String line;
        DiffEntry(DiffType type, String line) {
            this.type = type;
            this.line = line;
        }
    }

    public TextDiffPanel() {
        super("开发工具", "文本对比",
                "Diff", "差异", "对比", "文本差异",
                "差异比较", "LCS", "比较");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：操作按钮 =====
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton compare = UIUtils.button("对比差异", 90);
        JButton clear = UIUtils.button("清空", 80);
        btns.add(compare); btns.add(clear);
        root.add(btns, BorderLayout.NORTH);

        // ===== 中间：输入区域 (左右分栏) =====
        JTextArea leftArea = new JTextArea(8, 20);
        leftArea.setFont(UIUtils.monoFont());
        leftArea.setText("Hello World\nJava is great\nWe love programming\nGood morning!");

        JTextArea rightArea = new JTextArea(8, 20);
        rightArea.setFont(UIUtils.monoFont());
        rightArea.setText("Hello World\nKotlin is great\nWe love programming\nGood evening!\nHave a nice day!");

        JSplitPane inputSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                UIUtils.scrollText(leftArea, "原文本 (A)"),
                UIUtils.scrollText(rightArea, "修改后文本 (B)"));
        inputSplit.setResizeWeight(0.5);

        // ===== 下方：对比结果展示 =====
        JTextPane diffPane = new JTextPane();
        diffPane.setFont(UIUtils.monoFont());
        diffPane.setEditable(false);
        JScrollPane resultScroll = new JScrollPane(diffPane);
        resultScroll.setBorder(BorderFactory.createTitledBorder("对比结果 ( + 新增, - 删除 )"));

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, inputSplit, resultScroll);
        mainSplit.setResizeWeight(0.35);
        root.add(mainSplit, BorderLayout.CENTER);

        // 按钮事件
        compare.addActionListener(e -> {
            try {
                diffPane.setText("");
                StyledDocument doc = diffPane.getStyledDocument();
                
                // 定义样式
                Style style = diffPane.addStyle("diff", null);
                Style deleteStyle = diffPane.addStyle("delete", style);
                StyleConstants.setForeground(deleteStyle, new Color(220, 50, 50));
                StyleConstants.setFontFamily(deleteStyle, UIUtils.monoFont().getFamily());
                
                Style insertStyle = diffPane.addStyle("insert", style);
                StyleConstants.setForeground(insertStyle, new Color(38, 162, 76));
                StyleConstants.setFontFamily(insertStyle, UIUtils.monoFont().getFamily());
                
                Style equalStyle = diffPane.addStyle("equal", style);
                Color fg = UIManager.getColor("Label.foreground");
                StyleConstants.setForeground(equalStyle, fg == null ? Color.BLACK : fg);
                StyleConstants.setFontFamily(equalStyle, UIUtils.monoFont().getFamily());

                List<DiffEntry> diffs = computeDiff(leftArea.getText(), rightArea.getText());
                for (DiffEntry entry : diffs) {
                    if (entry.type == DiffType.DELETE) {
                        doc.insertString(doc.getLength(), "- " + entry.line + "\n", deleteStyle);
                    } else if (entry.type == DiffType.INSERT) {
                        doc.insertString(doc.getLength(), "+ " + entry.line + "\n", insertStyle);
                    } else {
                        doc.insertString(doc.getLength(), "  " + entry.line + "\n", equalStyle);
                    }
                }
            } catch (Exception ex) {
                UIUtils.error(root, "比对失败: " + ex.getMessage());
            }
        });

        clear.addActionListener(e -> {
            leftArea.setText("");
            rightArea.setText("");
            diffPane.setText("");
        });

        // 默认触发比对
        compare.doClick();

        return root;
    }

    private List<DiffEntry> computeDiff(String text1, String text2) {
        List<String> a = Arrays.asList(text1.split("\n", -1));
        List<String> b = Arrays.asList(text2.split("\n", -1));

        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        int i = a.size(), j = b.size();
        List<DiffEntry> diff = new ArrayList<>();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
                diff.add(new DiffEntry(DiffType.EQUAL, a.get(i - 1)));
                i--; j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                diff.add(new DiffEntry(DiffType.INSERT, b.get(j - 1)));
                j--;
            } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                diff.add(new DiffEntry(DiffType.DELETE, a.get(i - 1)));
                i--;
            }
        }
        Collections.reverse(diff);
        return diff;
    }
}
