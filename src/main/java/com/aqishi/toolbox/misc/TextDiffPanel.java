package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
        super("dev", "text.diff",
                "Diff", "差异", "对比", "文本差异",
                "差异比较", "LCS", "比较");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 上方：两份原文左右并排，等宽分隔 =====
        JTextArea leftArea = Fields.area(8, 20);
        leftArea.setText("Hello World\nJava is great\nWe love programming\nGood morning!");
        Card leftCard = Card.flush("原文本 (A)");
        leftCard.setContent(Fields.scroll(leftArea));

        JTextArea rightArea = Fields.area(8, 20);
        rightArea.setText("Hello World\nKotlin is great\nWe love programming\nGood evening!\nHave a nice day!");
        Card rightCard = Card.flush("修改后文本 (B)");
        rightCard.setContent(Fields.scroll(rightArea));

        JSplitPane inputSplit = Layouts.splitHorizontal(leftCard, rightCard, 0.5);

        // ===== 下方：对比结果 =====
        JTextPane diffPane = new JTextPane();
        diffPane.setFont(Tokens.fontMono());
        diffPane.setEditable(false);
        // JTextPane 自身没有内边距，补一层与 Fields.area 一致的留白，免得文字贴着卡片描边
        diffPane.setBorder(KitBorders.padding(Tokens.SPACE_SM));

        JButton compare = Buttons.primary("对比差异");
        JButton clear = Buttons.danger("清空");
        Card resultCard = Card.flush("对比结果 ( + 新增, - 删除 )");
        resultCard.setContent(Fields.scroll(diffPane));
        // 两个动作都作用于「产出这份结果」，挂在结果卡标题栏上，
        // 页面里就不再需要一条独立的按钮行去挤压文本区高度
        resultCard.addHeaderAction(clear);
        resultCard.addHeaderAction(compare);

        JSplitPane mainSplit = Layouts.splitVertical(inputSplit, resultCard, 0.35);
        root.add(mainSplit, BorderLayout.CENTER);

        // 按钮事件
        compare.addActionListener(e -> {
            try {
                diffPane.setText("");
                StyledDocument doc = diffPane.getStyledDocument();
                
                // 定义样式
                Style style = diffPane.addStyle("diff", null);
                // 差异色改走 Tokens，切换深浅主题时跟着变，不再写死 RGB
                Style deleteStyle = diffPane.addStyle("delete", style);
                StyleConstants.setForeground(deleteStyle, Tokens.danger());
                StyleConstants.setFontFamily(deleteStyle, Tokens.fontMono().getFamily());

                Style insertStyle = diffPane.addStyle("insert", style);
                StyleConstants.setForeground(insertStyle, Tokens.success());
                StyleConstants.setFontFamily(insertStyle, Tokens.fontMono().getFamily());

                Style equalStyle = diffPane.addStyle("equal", style);
                StyleConstants.setForeground(equalStyle, Tokens.foreground());
                StyleConstants.setFontFamily(equalStyle, Tokens.fontMono().getFamily());

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
