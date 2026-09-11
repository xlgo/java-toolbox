package com.aqishi.toolbox.feature.data.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseObjectsTest {

    @Test
    void fromRowsSplitsViewsFromTablesAndSorts() {
        DatabaseObjects objects = DatabaseObjects.fromRows(List.of(
                new DatabaseObjects.Row("TABLE", "zeta"),
                new DatabaseObjects.Row("VIEW", "v2"),
                new DatabaseObjects.Row("TABLE", "alpha"),
                new DatabaseObjects.Row("VIEW", "v1")), List.of("fn_b", "fn_a"));

        assertEquals(List.of("alpha", "zeta"), objects.tables());
        assertEquals(List.of("v1", "v2"), objects.views());
        assertEquals(List.of("fn_a", "fn_b"), objects.functions());
    }

    @Test
    void sortedUniqueSortsThenDeduplicates() {
        assertEquals(List.of("a", "b", "c"),
                DatabaseObjects.sortedUnique(List.of("c", "a", "b", "a")));
        assertTrue(DatabaseObjects.sortedUnique(List.of()).isEmpty());
    }
}
