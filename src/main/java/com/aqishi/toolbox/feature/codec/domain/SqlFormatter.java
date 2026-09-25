package com.aqishi.toolbox.feature.codec.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 排版：关键字大写、主要子句另起一行、AND/OR 缩进；以及对应的压成一行。
 * 「SQL 格式化」工具与数据库客户端的编辑器共用这一份实现。
 *
 * <p>只调整 token 之间的空白，绝不改动 token 本身。早先的实现先把整段文本的空白压成一个
 * 空格再切词，于是 {@code 'a  b'} 被改成 {@code 'a b'}（查询语义变了），{@code --} 注释
 * 被并到同一行后吞掉了后面所有语句。这里直接在原文上切词：字符串、带引号的标识符、
 * 注释都是整体 token，行注释之后强制换行。</p>
 *
 * <p>这是面向阅读的排版，不是解析器；遇到不认识的语法只会原样保留。</p>
 */
public final class SqlFormatter {

    private static final Pattern TOKEN = Pattern.compile(
            "'(?:[^']|'')*'"          // 字符串字面量，'' 为转义的单引号
                    + "|\"(?:[^\"]|\"\")*\""  // 双引号标识符
                    + "|`[^`]*`"              // MySQL 反引号标识符
                    + "|--[^\\r\\n]*"         // 行注释
                    + "|/\\*.*?\\*/"          // 块注释
                    + "|<>|<=|>=|!=|\\|\\||::" // 多字符运算符：拆开会变成 "> ="，是语法错误
                    + "|\\w+"
                    + "|\\S",
            Pattern.DOTALL);

    private static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
            "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON", "GROUP", "BY", "ORDER", "HAVING", "LIMIT",
            "AND", "OR", "UNION", "ALL", "AS", "IN", "IS", "NOT", "NULL", "LIKE", "EXISTS", "BETWEEN",
            "CASE", "WHEN", "THEN", "ELSE", "END");

    /** 这些关键字另起一行。 */
    private static final Set<String> CLAUSE_STARTERS = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "JOIN", "GROUP", "ORDER", "SET",
            "VALUES", "UNION");

    /** 由两个词组成的子句，第一个词负责换行，第二个词跟在后面。 */
    private static final Set<String> JOIN_QUALIFIERS = Set.of("LEFT", "RIGHT", "INNER");

    private SqlFormatter() {
    }

    public static String format(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "";
        }
        List<String> tokens = tokenize(sql);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            String upper = token.toUpperCase(Locale.ROOT);
            boolean keyword = KEYWORDS.contains(upper);
            String display = keyword ? upper : token;

            if (keyword) {
                String next = i + 1 < tokens.size() ? tokens.get(i + 1).toUpperCase(Locale.ROOT) : "";
                String previous = i > 0 ? tokens.get(i - 1).toUpperCase(Locale.ROOT) : "";
                boolean firstOfPair = (("GROUP".equals(upper) || "ORDER".equals(upper)) && "BY".equals(next))
                        || (JOIN_QUALIFIERS.contains(upper) && "JOIN".equals(next));
                boolean secondOfPair = ("BY".equals(upper) && ("GROUP".equals(previous) || "ORDER".equals(previous)))
                        || ("JOIN".equals(upper) && JOIN_QUALIFIERS.contains(previous));

                if ((CLAUSE_STARTERS.contains(upper) && !secondOfPair) || firstOfPair) {
                    newline(out);
                } else if ("AND".equals(upper) || "OR".equals(upper)) {
                    newline(out);
                    out.append("  ");
                }
            }

            boolean afterDot = out.length() > 0 && out.charAt(out.length() - 1) == '.';
            if (out.length() > 0 && !endsWithWhitespace(out) && !afterDot
                    && !",".equals(display) && !")".equals(display) && !"(".equals(display)
                    && !".".equals(display)) {
                out.append(' ');
            }
            out.append(display);
            if (",".equals(display)) {
                out.append(' ');
            }
            if (token.startsWith("--")) {
                // 行注释必须独占到行尾，否则会把后面的 SQL 一起注释掉。
                out.append('\n');
            }
        }
        return out.toString().trim();
    }

    /**
     * 压成一行：token 之间只留一个空格，字面量与注释内部保持原样。
     *
     * <p>行注释会改写成等价的块注释——压成一行后 {@code --} 会把其后所有 SQL 一起注释掉。
     * 注释正文里若本身含有 {@code *}{@code /}，改写会提前闭合块注释，这种罕见情况下保留换行。</p>
     */
    public static String compress(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String token : tokenize(sql)) {
            boolean lineComment = token.startsWith("--");
            String text = token;
            if (lineComment && !token.contains("*/")) {
                text = "/*" + token.substring(2) + " */";
                lineComment = false;
            }
            boolean afterDot = out.length() > 0 && out.charAt(out.length() - 1) == '.';
            if (out.length() > 0 && !endsWithWhitespace(out) && !afterDot
                    && !",".equals(text) && !")".equals(text) && !"(".equals(text) && !".".equals(text)
                    && out.charAt(out.length() - 1) != '(') {
                out.append(' ');
            }
            out.append(text);
            if (lineComment) {
                out.append('\n');
            }
        }
        return out.toString().trim();
    }

    static List<String> tokenize(String sql) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(sql);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static void newline(StringBuilder out) {
        if (out.length() == 0) {
            return;
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
        if (out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
    }

    private static boolean endsWithWhitespace(StringBuilder out) {
        char last = out.charAt(out.length() - 1);
        return last == ' ' || last == '\n';
    }
}
