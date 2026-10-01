package com.aqishi.toolbox.feature.data.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.data.application.MyBatisLogSql;
import com.aqishi.toolbox.feature.data.application.QueryResultExporter.Dialect;

import org.junit.jupiter.api.Test;

class MyBatisLogSqlTest {
    private String restore(String sql, String params) {
        return new MyBatisLogSql()
                .restore(
                        "2026 DEBUG ==> Preparing: "
                                + sql
                                + "\n2026 DEBUG ==> Parameters: "
                                + params,
                        Dialect.POSTGRESQL);
    }

    @Test
    void handlesNumbersNullBooleanAndQuotedValues() {
        assertEquals(
                "SELECT 'O''Reilly', 18, TRUE, NULL",
                restore("SELECT ?, ?, ?, ?", "O'Reilly(String), 18(Integer), true(Boolean), null"));
    }

    @Test
    void doesNotReplaceQuestionMarksInsideQuotesCommentsOrDollarStrings() {
        assertEquals(
                "SELECT '?', \"?\", $$?$$, 7 /* ? */ -- ?",
                restore("SELECT '?', \"?\", $$?$$, ? /* ? */ -- ?", "7(Integer)"));
    }

    @Test
    void stringCommasAndParenthesesArePreserved() {
        assertEquals("SELECT 'hello, world (x)'", restore("SELECT ?", "hello, world (x)(String)"));
    }

    @Test
    void handlesEmptyStringAndNoParameters() {
        assertEquals("SELECT ''", restore("SELECT ?", "(String)"));
        assertEquals("SELECT 1", restore("SELECT 1", ""));
    }

    @Test
    void dateAndTimestampAreQuoted() {
        assertTrue(
                restore("SELECT ?, ?", "2024-02-29(Date), 2026-10-01 01:02:03.123456(Timestamp)")
                        .contains("'2026-10-01T01:02:03.123456'"));
    }

    @Test
    void mismatchedCountsUnknownTypesAndIncompletePairsAreRejected() {
        assertThrows(Exception.class, () -> restore("SELECT ?,?", "1(Integer)"));
        assertThrows(Exception.class, () -> restore("SELECT 1", "1(Integer)"));
        assertThrows(Exception.class, () -> restore("SELECT ?", "[B@123(byte[])"));
        assertThrows(
                Exception.class,
                () -> new MyBatisLogSql().restore("Preparing: SELECT ?", Dialect.STANDARD));
    }

    @Test
    void refusesMultipleOrInterleavedStatements() {
        assertThrows(
                Exception.class,
                () ->
                        new MyBatisLogSql()
                                .restore(
                                        "Preparing: SELECT ?\n"
                                            + "Preparing: SELECT ?\n"
                                            + "Parameters: 1(Integer)",
                                        Dialect.STANDARD));
    }
}
