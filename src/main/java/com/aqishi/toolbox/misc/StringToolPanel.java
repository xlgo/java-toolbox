package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.charset.Charset;

/**
 * 字符串工具面板：支持实时字符串长度与统计计算，以及字符/子串/正则删除和一键快捷转换。
 */
public class StringToolPanel extends ToolPanel {

    private JTextArea inputArea;
    private JTextArea outputArea;
    private JLabel statsLabel;
    private JComboBox<String> encodingCombo;

    private JTextField deleteField;
    private JComboBox<String> deleteModeCombo;

    public StringToolPanel() {
        super("format", "string.tool",
                "String", "Length", "Delete", "Trim", "Uppercase", "Lowercase", "Regex",
                "字符串", "长度", "删除", "过滤", "大写", "小写", "统计");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ================= 左侧工作区：输入 / 输出上下分栏 =================
        inputArea = Fields.area(8, 40);
        outputArea = Fields.area(8, 40);

        // 统计条挂在输入卡片底部：它描述的是输入内容，跟着输入框走比单独占一块更好读
        statsLabel = Fields.caption("字符数: 0 | 字节数: 0 | 行数: 0 | 单词数: 0 | 空白字符数: 0");
        encodingCombo = Fields.combo(new String[]{"UTF-8", "GBK", "ISO-8859-1", "UTF-16"}, 110);
        encodingCombo.addActionListener(e -> updateStats());

        ActionBar statsBar = new ActionBar();
        statsBar.left(statsLabel);
        statsBar.gap(Tokens.SPACE_MD); // 统计文字很长，留一段最小间距免得窄窗口下顶到「编码」上
        statsBar.right(Fields.label("编码:"));
        statsBar.right(encodingCombo);
        JPanel statsWrapper = Layouts.box();
        statsWrapper.add(statsBar, BorderLayout.CENTER);
        statsWrapper.setBorder(BorderFactory.createCompoundBorder(
                KitBorders.lineSubtle(1, 0, 0, 0),
                KitBorders.padding(Tokens.SPACE_SM, Tokens.CARD_PADDING,
                        Tokens.SPACE_SM, Tokens.CARD_PADDING)));

        Card inputCard = Card.flush("输入文本 (Input)");
        JPanel inputBody = Layouts.box();
        inputBody.add(Fields.scroll(inputArea), BorderLayout.CENTER);
        inputBody.add(statsWrapper, BorderLayout.SOUTH);
        inputCard.setContent(inputBody);

        Card outputCard = Card.flush("输出文本 (Output)");
        outputCard.setContent(Fields.scroll(outputArea));

        // 针对输出内容的三个动作放输出卡片标题右侧，省掉原来吊在右栏底部的一排按钮
        JButton btnApply = Buttons.secondary("覆盖输入");
        btnApply.addActionListener(e -> inputArea.setText(outputArea.getText()));

        JButton btnCopy = Buttons.secondary("复制输出");
        btnCopy.addActionListener(e -> {
            UIUtils.copyToClipboard(outputArea.getText());
            UIUtils.info(getView(), "已复制输出文本至剪贴板。");
        });

        JButton btnClear = Buttons.danger("清空");
        btnClear.addActionListener(e -> {
            inputArea.setText("");
            outputArea.setText("");
        });

        outputCard.addHeaderAction(btnApply);
        outputCard.addHeaderAction(btnCopy);
        outputCard.addHeaderAction(btnClear);

        JSplitPane splitPane = Layouts.splitVertical(inputCard, outputCard, 0.5);
        root.add(splitPane, BorderLayout.CENTER);

        // ================= 右侧操作区：按语义分成三张卡片 =================
        // 分组 1: 字符/字符串删除
        deleteField = Fields.text("", "输入要过滤的字符/字串");
        deleteModeCombo = Fields.combo(new String[]{"匹配任一字符", "精确匹配子串", "正则表达式"});
        JButton deleteBtn = Buttons.primary("执行删除");
        deleteBtn.addActionListener(e -> performDelete());

        Card filterCard = Card.titled("字符过滤/删除");
        filterCard.setContent(Layouts.stack(Tokens.SPACE_SM, deleteField, deleteModeCombo, deleteBtn));

        // 分组 2: 常见快捷过滤（两列六格，按钮等宽）
        JButton btnNoSpace = Buttons.secondary("清除空白");
        btnNoSpace.addActionListener(e -> outputArea.setText(inputArea.getText().replaceAll("\\s+", "")));

        JButton btnNoNewline = Buttons.secondary("清除换行");
        btnNoNewline.addActionListener(e -> outputArea.setText(inputArea.getText().replaceAll("\\r?\\n", "")));

        JButton btnNoDigit = Buttons.secondary("清除数字");
        btnNoDigit.addActionListener(e -> outputArea.setText(inputArea.getText().replaceAll("\\d+", "")));

        JButton btnNoAlpha = Buttons.secondary("清除字母");
        btnNoAlpha.addActionListener(e -> outputArea.setText(inputArea.getText().replaceAll("[a-zA-Z]+", "")));

        JButton btnTrim = Buttons.secondary("首尾去空");
        btnTrim.addActionListener(e -> trimLines());

        JButton btnNoEmptyLines = Buttons.secondary("去除空行");
        btnNoEmptyLines.addActionListener(e -> removeEmptyLines());

        JPanel quickGrid = new JPanel(new GridLayout(3, 2, Tokens.SPACE_SM, Tokens.SPACE_SM));
        quickGrid.setOpaque(false);
        quickGrid.add(btnNoSpace);
        quickGrid.add(btnNoNewline);
        quickGrid.add(btnNoDigit);
        quickGrid.add(btnNoAlpha);
        quickGrid.add(btnTrim);
        quickGrid.add(btnNoEmptyLines);

        Card quickCard = Card.titled("快捷过滤");
        quickCard.setContent(quickGrid);

        // 分组 3: 文本转换
        JButton btnUpper = Buttons.secondary("转换为大写");
        btnUpper.addActionListener(e -> outputArea.setText(inputArea.getText().toUpperCase()));

        JButton btnLower = Buttons.secondary("转换为小写");
        btnLower.addActionListener(e -> outputArea.setText(inputArea.getText().toLowerCase()));

        JButton btnReverse = Buttons.secondary("反转文本");
        btnReverse.addActionListener(e -> outputArea.setText(new StringBuilder(inputArea.getText()).reverse().toString()));

        Card convertCard = Card.titled("大小写与转换");
        convertCard.setContent(Layouts.rows(Tokens.SPACE_SM, btnUpper, btnLower, btnReverse));

        // 三张卡片放进滚动容器：窗口压到最小高度时右栏可以滚动，而不是把最后一张卡片切掉
        JScrollPane railScroll = Fields.scroll(
                Layouts.stack(Tokens.SPACE_LG, filterCard, quickCard, convertCard));
        railScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        // 侧栏是固定宽度的操作区，宽度不参与主区伸缩
        railScroll.setPreferredSize(new Dimension(270, 0));
        root.add(railScroll, BorderLayout.EAST);

        // ================= 监听与绑定 =================
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { updateStats(); }
            @Override
            public void removeUpdate(DocumentEvent e) { updateStats(); }
            @Override
            public void changedUpdate(DocumentEvent e) { updateStats(); }
        });

        return root;
    }

    private void updateStats() {
        String text = inputArea.getText();
        if (text == null) text = "";

        int charCount = text.length();

        // 字节数计算
        int byteCount = 0;
        try {
            String charsetName = (String) encodingCombo.getSelectedItem();
            byteCount = text.getBytes(Charset.forName(charsetName)).length;
        } catch (Exception ignored) {}

        // 行数计算
        int lineCount = text.isEmpty() ? 0 : text.split("\\r?\\n", -1).length;

        // 单词数计算
        String trimmed = text.trim();
        int wordCount = trimmed.isEmpty() ? 0 : trimmed.split("\\s+").length;

        // 空白字符数计算
        int whitespaceCount = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                whitespaceCount++;
            }
        }

        statsLabel.setText(String.format("字符数: %d | 字节数: %d | 行数: %d | 单词数: %d | 空白字符数: %d",
                charCount, byteCount, lineCount, wordCount, whitespaceCount));
    }

    private void performDelete() {
        String input = inputArea.getText();
        String target = deleteField.getText();
        int mode = deleteModeCombo.getSelectedIndex();

        if (input == null || input.isEmpty()) {
            outputArea.setText("");
            return;
        }

        if (target == null || target.isEmpty()) {
            outputArea.setText(input);
            return;
        }

        if (mode == 0) { // 匹配任一字符
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (target.indexOf(c) < 0) {
                    sb.append(c);
                }
            }
            outputArea.setText(sb.toString());
        } else if (mode == 1) { // 精确匹配子串
            outputArea.setText(input.replace(target, ""));
        } else if (mode == 2) { // 正则表达式
            try {
                outputArea.setText(input.replaceAll(target, ""));
            } catch (Exception ex) {
                UIUtils.error(getView(), "正则表达式语法错误:\n" + ex.getMessage());
            }
        }
    }

    private void trimLines() {
        String input = inputArea.getText();
        if (input == null || input.isEmpty()) {
            outputArea.setText("");
            return;
        }
        String[] lines = input.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i].trim());
            if (i < lines.length - 1) {
                sb.append("\n");
            }
        }
        outputArea.setText(sb.toString());
    }

    private void removeEmptyLines() {
        String input = inputArea.getText();
        if (input == null || input.isEmpty()) {
            outputArea.setText("");
            return;
        }
        String[] lines = input.split("\\r?\\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                if (!first) {
                    sb.append("\n");
                }
                sb.append(line);
                first = false;
            }
        }
        outputArea.setText(sb.toString());
    }
}
