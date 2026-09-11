package com.aqishi.toolbox.feature.data.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 文本相关的纯逻辑：标识符校验与表名抽取。
 */
public final class SqlTextSupport {

    private static final Pattern TABLE_NAME_PATTERN =
            Pattern.compile("(?i)\\b(FROM|JOIN|UPDATE|INTO)\\s+([a-zA-Z0-9_]+)");

    private SqlTextSupport() {
    }

    /**
     * 校验可安全拼接进 SQL 的模式/表标识符（字母或下划线开头，
     * 只含字母数字与 {@code _$#}），非法输入直接拒绝以防止注入。
     */
    public static String safeIdentifier(String name) {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_$#]*")) {
            throw new IllegalArgumentException("非法的模式名: " + name);
        }
        return name;
    }

    /**
     * 从 SQL 文本抽取最后出现的 FROM/JOIN/UPDATE/INTO 表名；抽不到返回
     * {@code null}，由调用方决定回退策略。
     */
    public static String extractTableName(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher matcher = TABLE_NAME_PATTERN.matcher(sql);
        String table = null;
        while (matcher.find()) {
            table = matcher.group(2);
        }
        return table;
    }
}
