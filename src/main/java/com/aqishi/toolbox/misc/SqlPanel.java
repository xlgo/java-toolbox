package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 格式化 / 美化面板。
 */
public class SqlPanel extends ToolPanel {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
            "AND", "OR", "UNION", "ALL", "AS", "IN", "IS", "NOT", "NULL", "LIKE", "EXISTS", "BETWEEN", "CASE", "WHEN", "THEN", "ELSE", "END"
    ));

    private static final Set<String> NEWLINE_KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "GROUP", "ORDER", "SET", "VALUES", "UNION"
    ));

    public SqlPanel() {
        super("杂项", "SQL 格式化");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JPanel btns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        JButton pretty = UIUtils.button("格式化", 80);
        JButton copy = UIUtils.button("复制结果", 100);
        JButton clear = UIUtils.button("清空", 80);
        btns.add(pretty); btns.add(copy); btns.add(clear);
        root.add(btns, BorderLayout.NORTH);

        JTextArea input = new JTextArea(8, 40);
        input.setFont(UIUtils.monoFont());
        input.setText("select id, name, age from users left join roles on users.role_id = roles.id where age > 18 and status = 'active' order by age desc limit 10");
        JTextArea out = new JTextArea(10, 40);
        out.setFont(UIUtils.monoFont());
        out.setEditable(false);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                UIUtils.scrollText(input, "输入 SQL"),
                UIUtils.scrollText(out, "输出"));
        split.setResizeWeight(0.4);
        root.add(split, BorderLayout.CENTER);

        pretty.addActionListener(e -> {
            out.setText(formatSql(input.getText()));
        });
        copy.addActionListener(e -> UIUtils.copyToClipboard(out.getText()));
        clear.addActionListener(e -> { input.setText(""); out.setText(""); });
        
        pretty.doClick();

        return root;
    }

    private String formatSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "";

        // 清理换行和连续空格
        sql = sql.replaceAll("\\s+", " ").trim();

        // 简单分词正则，支持单引号字符串，双引号字符串，反引号字符串，单词及其他字符
        Pattern pattern = Pattern.compile("'[^']*'|\"[^\"]*\"|`[^`]*`|\\w+|\\S");
        Matcher matcher = pattern.matcher(sql);

        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String upperToken = token.toUpperCase();

            boolean isKeyword = KEYWORDS.contains(upperToken);
            String displayToken = isKeyword ? upperToken : token;

            if (isKeyword) {
                String nextToken = (i + 1 < tokens.size()) ? tokens.get(i + 1).toUpperCase() : "";
                String prevToken = (i - 1 >= 0) ? tokens.get(i - 1).toUpperCase() : "";

                boolean isFirstOfMultiWord = false;
                if (upperToken.equals("GROUP") && nextToken.equals("BY")) isFirstOfMultiWord = true;
                if (upperToken.equals("ORDER") && nextToken.equals("BY")) isFirstOfMultiWord = true;
                if ((upperToken.equals("LEFT") || upperToken.equals("RIGHT") || upperToken.equals("INNER")) && nextToken.equals("JOIN")) isFirstOfMultiWord = true;

                boolean isSecondOfMultiWord = false;
                if (upperToken.equals("BY") && (prevToken.equals("GROUP") || prevToken.equals("ORDER"))) isSecondOfMultiWord = true;
                if (upperToken.equals("JOIN") && (prevToken.equals("LEFT") || prevToken.equals("RIGHT") || prevToken.equals("INNER"))) isSecondOfMultiWord = true;

                if ((NEWLINE_KEYWORDS.contains(upperToken) && !isSecondOfMultiWord) || isFirstOfMultiWord) {
                    if (sb.length() > 0) {
                        sb.append("\n");
                    }
                } else if (upperToken.equals("AND") || upperToken.equals("OR")) {
                    sb.append("\n  ");
                }
            }

            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n' && sb.charAt(sb.length() - 1) != ' ') {
                if (!displayToken.equals(",") && !displayToken.equals(")") && !displayToken.equals("(")) {
                    sb.append(" ");
                }
            }

            sb.append(displayToken);

            if (displayToken.equals(",")) {
                sb.append(" ");
            }
        }

        return sb.toString().trim();
    }
}
