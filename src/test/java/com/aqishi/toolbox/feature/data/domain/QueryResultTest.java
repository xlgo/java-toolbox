package com.aqishi.toolbox.feature.data.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryResultTest {

    @Test
    void rowsFactoryMarksResultAsQueryWithCopiedData() {
        List<String> columns = Arrays.asList("id", "name");
        List<List<Object>> rows = new java.util.ArrayList<>();
        rows.add(new java.util.ArrayList<>(Arrays.asList(1, "alice")));
        QueryResult result = QueryResult.rows(columns, rows, 12, null);

        assertFalse(result.isUpdate());
        assertEquals(-1, result.getUpdateCount());
        assertEquals(12, result.getDurationMillis());
        assertNull(result.getWarning());
        assertEquals(columns, result.getColumnNames());
        assertEquals(rows, result.getRows());
    }

    @Test
    void rowsAreDefensivelyCopiedAndImmutable() {
        List<List<Object>> rows = new java.util.ArrayList<>();
        List<Object> row = new java.util.ArrayList<>(Arrays.asList(1, "alice"));
        rows.add(row);
        QueryResult result = QueryResult.rows(Arrays.asList("id", "name"), rows, 1, null);

        // 外部再改动源数据不影响已构造的结果
        row.set(0, 999);
        rows.clear();
        assertEquals(Arrays.asList(1, "alice"), result.getRows().get(0));
        assertEquals(1, result.getRows().size());

        assertThrows(UnsupportedOperationException.class,
                () -> result.getRows().add(Collections.singletonList(2)));
        assertThrows(UnsupportedOperationException.class,
                () -> result.getColumnNames().add("extra"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.getRows().get(0).set(0, 2));
    }

    @Test
    void updateFactoryMarksResultAsUpdate() {
        QueryResult result = QueryResult.update(3, 5);
        assertTrue(result.isUpdate());
        assertEquals(3, result.getUpdateCount());
        assertEquals(0, result.getColumnNames().size());
        assertEquals(0, result.getRows().size());
    }

    @Test
    void updateCountOfZeroStillCountsAsUpdate() {
        // DDL/无影响行数的 DML 更新计数可为 0，不应误判为查询
        assertTrue(QueryResult.update(0, 1).isUpdate());
    }
}
