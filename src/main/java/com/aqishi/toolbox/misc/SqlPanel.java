package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
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
public class SqlPanel extends AbstractFormatPanel {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
            "AND", "OR", "UNION", "ALL", "AS", "IN", "IS", "NOT", "NULL", "LIKE", "EXISTS", "BETWEEN", "CASE", "WHEN", "THEN", "ELSE", "END"
    ));

    private static final Set<String> NEWLINE_KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "GROUP", "ORDER", "SET", "VALUES", "UNION"
    ));

    public SqlPanel() {
        super("format", "sql.format",
                "SQL", "美化", "格式化",
                "Sql美化", "SQL美化", "关键字大写");
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
                    out.setText(formatSql(inputArea.getText()));
                });
                compress.addActionListener(e -> {
                    out.setText(compressSql(inputArea.getText()));
                });

                bar.right(compress);
                bar.right(pretty);
            });

        addOutputView("TEXT", out);
        
        // 默认触发一次格式化
        out.setText(formatSql(inputArea.getText()));

        return root;
    }

    private String compressSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) return "";
        return sql.replaceAll("\\s+", " ").trim();
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
