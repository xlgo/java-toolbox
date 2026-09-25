package com.aqishi.toolbox.feature.data.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SelectQueryBuilderTest {

    private static SelectQueryBuilder.Condition condition(String column, String operator, String value) {
        return new SelectQueryBuilder.Condition(column, operator, value);
    }

    @Test
    void buildsWithoutConditions() {
        assertEquals("SELECT * FROM users LIMIT 100",
                SelectQueryBuilder.build("users", List.of(), false, 100));
    }

    @Test
    void joinsConditionsWithAnd() {
        assertEquals("SELECT * FROM users WHERE age > 18 AND name = 'bob' LIMIT 100",
                SelectQueryBuilder.build("users", List.of(
                        condition("age", ">", "18"),
                        condition("name", "=", "bob")), false, 100));
    }

    /** 回归：第一行未填列名时，旧实现生成 "WHERE  AND ..."。 */
    @Test
    void skipsBlankLeadingRowWithoutDanglingAnd() {
        assertEquals("SELECT * FROM users WHERE age > 18 LIMIT 100",
                SelectQueryBuilder.build("users", List.of(
                        condition(null, "=", "x"),
                        condition("age", ">", "18")), false, 100));
    }

    /** 回归：所有行都未填时，旧实现生成 "WHERE  LIMIT 100"。 */
    @Test
    void omitsWhereWhenEveryRowIsBlank() {
        assertEquals("SELECT * FROM users LIMIT 100",
                SelectQueryBuilder.build("users", List.of(condition(" ", "=", "x")), false, 100));
    }

    @Test
    void oracleUsesRownumInsteadOfLimit() {
        assertEquals("SELECT * FROM USERS WHERE ROWNUM <= 100",
                SelectQueryBuilder.build("USERS", List.of(), true, 100));
        assertEquals("SELECT * FROM USERS WHERE AGE > 18 AND ROWNUM <= 100",
                SelectQueryBuilder.build("USERS", List.of(condition("AGE", ">", "18")), true, 100));
        assertEquals("SELECT * FROM USERS WHERE ROWNUM <= 100",
                SelectQueryBuilder.build("USERS", List.of(condition("", "=", "x")), true, 100));
    }

    @Test
    void unaryOperatorsTakeNoValue() {
        assertEquals("SELECT * FROM t WHERE deleted_at IS NULL LIMIT 10",
                SelectQueryBuilder.build("t", List.of(condition("deleted_at", "IS NULL", "ignored")), false, 10));
    }

    @Test
    void escapesSingleQuotes() {
        assertEquals("SELECT * FROM t WHERE name = 'O''Brien' LIMIT 10",
                SelectQueryBuilder.build("t", List.of(condition("name", "=", "O'Brien")), false, 10));
    }

    /** 带前导零的编号必须按字符串比较，否则 MySQL 会把 '007' 和 '7' 当成相等。 */
    @Test
    void quotesNonCanonicalNumbers() {
        assertEquals("SELECT * FROM t WHERE code = '007' LIMIT 10",
                SelectQueryBuilder.build("t", List.of(condition("code", "=", "007")), false, 10));
        assertEquals("SELECT * FROM t WHERE price >= -1.5 LIMIT 10",
                SelectQueryBuilder.build("t", List.of(condition("price", ">=", "-1.5")), false, 10));
        assertEquals("SELECT * FROM t WHERE qty = 0 LIMIT 10",
                SelectQueryBuilder.build("t", List.of(condition("qty", "=", "0")), false, 10));
    }

    @Test
    void rejectsUnknownOperator() {
        assertThrows(IllegalArgumentException.class, () -> SelectQueryBuilder.build("t",
                List.of(condition("a", "; DROP TABLE t; --", "1")), false, 10));
    }

    @Test
    void requiresTable() {
        assertThrows(IllegalArgumentException.class,
                () -> SelectQueryBuilder.build(" ", List.of(), false, 10));
    }
}
