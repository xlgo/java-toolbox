package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

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
        super("dev", "string.tool",
                "String", "Length", "Delete", "Trim", "Uppercase", "Lowercase", "Regex",
                "字符串", "长度", "删除", "过滤", "大写", "小写", "统计");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(12, 12));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ================= 左侧工作区：输入框与输出框 =================
        JPanel leftPanel = new JPanel(new BorderLayout(8, 8));

        inputArea = new JTextArea();
        inputArea.setFont(UIUtils.monoFont());
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);

        outputArea = new JTextArea();
        outputArea.setFont(UIUtils.monoFont());
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);

        // 统计状态栏与编码选择
        JPanel statsPanel = new JPanel(new BorderLayout(6, 0));
        statsPanel.setBorder(new EmptyBorder(4, 4, 4, 4));
        
        statsLabel = new JLabel("字符数: 0 | 字节数: 0 | 行数: 0 | 单词数: 0 | 空白字符数: 0");
        statsLabel.setFont(UIUtils.plainFont());
        statsPanel.add(statsLabel, BorderLayout.CENTER);

        JPanel encPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        encPanel.add(new JLabel("编码:"));
        encodingCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "ISO-8859-1", "UTF-16"});
        encodingCombo.setPreferredSize(new Dimension(100, 24));
        encodingCombo.addActionListener(e -> updateStats());
        encPanel.add(encodingCombo);
        statsPanel.add(encPanel, BorderLayout.EAST);

        // 输入面板组装
        JPanel inputWrapper = new JPanel(new BorderLayout(4, 4));
        inputWrapper.add(UIUtils.scrollText(inputArea, "输入文本 (Input)"), BorderLayout.CENTER);
        inputWrapper.add(statsPanel, BorderLayout.SOUTH);

        // 拆分面板
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setTopComponent(inputWrapper);
        splitPane.setBottomComponent(UIUtils.scrollText(outputArea, "输出文本 (Output)"));
        splitPane.setDividerLocation(200);
        splitPane.setResizeWeight(0.5);

        leftPanel.add(splitPane, BorderLayout.CENTER);
        root.add(leftPanel, BorderLayout.CENTER);

        // ================= 右侧操作区：控制选项 =================
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setPreferredSize(new Dimension(250, 0));
        rightPanel.setBorder(BorderFactory.createTitledBorder(
                null, "操作与转换 (Operations)", TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION, UIUtils.titleFont()));

        // 分组 1: 字符/字符串删除
        JPanel deletePanel = new JPanel(new GridBagLayout());
        deletePanel.setBorder(BorderFactory.createTitledBorder(
                null, "字符过滤/删除", TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION, UIUtils.plainFont()));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 4, 4, 4);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0;
        deleteField = new JTextField();
        deleteField.putClientProperty("JTextField.placeholderText", "输入要过滤的字符/字串");
        deletePanel.add(deleteField, gbc);

        gbc.gridy = 1;
        deleteModeCombo = new JComboBox<>(new String[]{"匹配任一字符", "精确匹配子串", "正则表达式"});
        deletePanel.add(deleteModeCombo, gbc);

        gbc.gridy = 2;
        JButton deleteBtn = UIUtils.button("执行删除", 200);
        deleteBtn.addActionListener(e -> performDelete());
        deletePanel.add(deleteBtn, gbc);

        rightPanel.add(deletePanel);
        rightPanel.add(Box.createVerticalStrut(8));

        // 分组 2: 常见快捷过滤
        JPanel quickFilterPanel = new JPanel(new GridLayout(3, 2, 6, 6));
        quickFilterPanel.setBorder(BorderFactory.createTitledBorder(
                null, "快捷过滤", TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION, UIUtils.plainFont()));

        JButton btnNoSpace = new JButton("清除空白");
        btnNoSpace.addActionListener(e -> outputArea.setText(inputArea.getText().replaceAll("\\s+", "")));
        
        JButton btnNoNewline = new JButton("清除换行");
        btnNoNewline.addActionListener(e -> outputArea.setText(inputArea.getText().replaceAll("\\r?\\n", "")));
        
        JButton btnNoDigit = new JButton("清除数字");
        btnNoDigit.addActionListener(e -> outputArea.setText(inputArea.getText().replaceAll("\\d+", "")));
        
        JButton btnNoAlpha = new JButton("清除字母");
        btnNoAlpha.addActionListener(e -> outputArea.setText(inputArea.getText().replaceAll("[a-zA-Z]+", "")));
        
        JButton btnTrim = new JButton("首尾去空");
        btnTrim.addActionListener(e -> trimLines());
        
        JButton btnNoEmptyLines = new JButton("去除空行");
        btnNoEmptyLines.addActionListener(e -> removeEmptyLines());

        quickFilterPanel.add(btnNoSpace);
        quickFilterPanel.add(btnNoNewline);
        quickFilterPanel.add(btnNoDigit);
        quickFilterPanel.add(btnNoAlpha);
        quickFilterPanel.add(btnTrim);
        quickFilterPanel.add(btnNoEmptyLines);

        rightPanel.add(quickFilterPanel);
        rightPanel.add(Box.createVerticalStrut(8));

        // 分组 3: 文本转换
        JPanel convertPanel = new JPanel(new GridLayout(3, 1, 6, 6));
        convertPanel.setBorder(BorderFactory.createTitledBorder(
                null, "大小写与转换", TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION, UIUtils.plainFont()));

        JButton btnUpper = new JButton("转换为大写");
        btnUpper.addActionListener(e -> outputArea.setText(inputArea.getText().toUpperCase()));
        
        JButton btnLower = new JButton("转换为小写");
        btnLower.addActionListener(e -> outputArea.setText(inputArea.getText().toLowerCase()));
        
        JButton btnReverse = new JButton("反转文本");
        btnReverse.addActionListener(e -> outputArea.setText(new StringBuilder(inputArea.getText()).reverse().toString()));

        convertPanel.add(btnUpper);
        convertPanel.add(btnLower);
        convertPanel.add(btnReverse);

        rightPanel.add(convertPanel);
        rightPanel.add(Box.createVerticalGlue()); // 推至底部

        // 分组 4: 底部操作控制
        JPanel actionPanel = new JPanel(new GridLayout(1, 3, 6, 6));
        actionPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        JButton btnApply = new JButton("覆盖输入");
        btnApply.addActionListener(e -> inputArea.setText(outputArea.getText()));
        
        JButton btnCopy = new JButton("复制输出");
        btnCopy.addActionListener(e -> {
            UIUtils.copyToClipboard(outputArea.getText());
            UIUtils.info(getView(), "已复制输出文本至剪贴板。");
        });
        
        JButton btnClear = new JButton("清空");
        btnClear.addActionListener(e -> {
            inputArea.setText("");
            outputArea.setText("");
        });

        actionPanel.add(btnApply);
        actionPanel.add(btnCopy);
        actionPanel.add(btnClear);
        rightPanel.add(actionPanel);

        root.add(rightPanel, BorderLayout.EAST);

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
