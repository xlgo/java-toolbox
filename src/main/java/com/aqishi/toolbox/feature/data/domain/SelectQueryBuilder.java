package com.aqishi.toolbox.feature.data.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 可视化查询页的 SQL 生成器：{@code SELECT * FROM 表 [WHERE 条件 AND ...] + 行数限制}。
 *
 * <p>列名和表名来自元数据下拉框、原样拼接（不同数据库的引号与大小写规则不同，
 * 贸然加引号会让 Oracle 的大写标识符失配）；取值则一律按字面量处理。</p>
 */
public final class SelectQueryBuilder {

    /** 条件行里允许的运算符；与界面下拉框保持一致。 */
    public static final List<String> OPERATORS =
            List.of("=", "!=", ">", ">=", "<", "<=", "LIKE", "IS NULL", "IS NOT NULL");

    private static final Set<String> UNARY_OPERATORS = Set.of("IS NULL", "IS NOT NULL");

    /**
     * 只有规范写法的数字才不加引号。{@code 007} 这类带前导零的串通常是编码而不是数值，
     * 不加引号时 MySQL 会把字符串列隐式转成数字比较，{@code '007'} 与 {@code '7'} 被当成相等。
     */
    private static final Pattern CANONICAL_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?");

    /** 一行筛选条件。列名为空的行视为未填写，生成时跳过。 */
    public record Condition(String column, String operator, String value) {
        public Condition {
            operator = operator == null ? "=" : operator;
            value = value == null ? "" : value.trim();
        }

        boolean isBlank() {
            return column == null || column.trim().isEmpty();
        }
    }

    private SelectQueryBuilder() {
    }

    /**
     * @param table        表名，不能为空
     * @param conditions   条件行，按 AND 连接；空列名的行被跳过
     * @param oracleRownum true 时用 {@code ROWNUM <= limit}（Oracle 11g 及以前没有 LIMIT），否则追加 {@code LIMIT}
     * @param limit        最多返回的行数
     */
    public static String build(String table, List<Condition> conditions, boolean oracleRownum, int limit) {
        if (table == null || table.trim().isEmpty()) {
            throw new IllegalArgumentException("table is required");
        }
        List<String> predicates = new ArrayList<>();
        for (Condition condition : conditions) {
            if (!condition.isBlank()) {
                predicates.add(predicate(condition));
            }
        }
        if (oracleRownum) {
            predicates.add("ROWNUM <= " + limit);
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM ").append(table.trim());
        if (!predicates.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        if (!oracleRownum) {
            sql.append(" LIMIT ").append(limit);
        }
        return sql.toString();
    }

    private static String predicate(Condition condition) {
        String operator = condition.operator();
        if (!OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("Unsupported operator: " + operator);
        }
        String column = Objects.requireNonNull(condition.column()).trim();
        if (UNARY_OPERATORS.contains(operator)) {
            return column + " " + operator;
        }
        return column + " " + operator + " " + literal(condition.value());
    }

    private static String literal(String value) {
        if (CANONICAL_NUMBER.matcher(value).matches()) {
            return value;
        }
        return "'" + value.replace("'", "''") + "'";
    }
}
