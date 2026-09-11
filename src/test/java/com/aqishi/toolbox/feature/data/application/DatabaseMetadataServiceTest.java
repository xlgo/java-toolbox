package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.DatabaseObjects;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMetadataServiceTest {

    private static DatabaseMetadataService service(FakeJdbc.Db db) {
        return new DatabaseMetadataService(db::connection, message -> { });
    }

    // --- listDatabases ---

    @Test
    void postgreSqlDatabasesComeFromPgDatabaseQuery() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.queryResults.put("SELECT datname FROM pg_database",
                List.of(FakeJdbc.Row.of("datname", "orders"), FakeJdbc.Row.of("datname", "app")));

        List<String> databases = service(db).listDatabases("PostgreSQL", "fallback");
        assertEquals(List.of("app", "orders"), databases);
    }

    @Test
    void postgreSqlQueryFailureFallsBackToCatalogs() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.failingQueries.add("SELECT datname");
        db.catalogs = List.of(FakeJdbc.Row.of("TABLE_CAT", "cat1"));

        assertEquals(List.of("cat1"), service(db).listDatabases("PostgreSQL", "fallback"));
    }

    @Test
    void mySqlDatabasesComeFromShowDatabases() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.queryResults.put("SHOW DATABASES",
                List.of(FakeJdbc.Row.of("Database", "mysql"), FakeJdbc.Row.of("Database", "app")));

        assertEquals(List.of("app", "mysql"), service(db).listDatabases("MySQL", "fallback"));
    }

    @Test
    void otherTypesUseStandardCatalogs() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.catalogs = List.of(FakeJdbc.Row.of("TABLE_CAT", "B"), FakeJdbc.Row.of("TABLE_CAT", "A"),
                FakeJdbc.Row.of("TABLE_CAT", "A"));

        // 排序且去重
        assertEquals(List.of("A", "B"), service(db).listDatabases("Oracle", "fallback"));
    }

    @Test
    void emptyDatabaseListFallsBackToActiveCatalogThenFormValue() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.currentCatalog = "active_db";
        assertEquals(List.of("active_db"), service(db).listDatabases("Oracle", "form_db"));

        FakeJdbc.Db noCatalog = new FakeJdbc.Db();
        noCatalog.failGetCatalog = true;
        assertEquals(List.of("form_db"), service(noCatalog).listDatabases("Oracle", "form_db"));

        FakeJdbc.Db empty = new FakeJdbc.Db();
        empty.failGetCatalog = true;
        assertTrue(service(empty).listDatabases("Oracle", "  ").isEmpty());
    }

    // --- listSchemas ---

    @Test
    void schemasAreSortedAndBlankEntriesDropped() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.schemas = List.of(FakeJdbc.Row.of("TABLE_SCHEM", "public"),
                FakeJdbc.Row.of("TABLE_SCHEM", ""),
                FakeJdbc.Row.of("TABLE_SCHEM", "app"));

        assertEquals(List.of("app", "public"), service(db).listSchemas());
    }

    // --- loadObjects ---

    @Test
    void objectsSplitIntoTablesViewsAndFunctions() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.tables = List.of(
                FakeJdbc.Row.of(Map.of("TABLE_TYPE", "VIEW", "TABLE_NAME", "v_summary")),
                FakeJdbc.Row.of(Map.of("TABLE_TYPE", "TABLE", "TABLE_NAME", "orders")),
                FakeJdbc.Row.of(Map.of("TABLE_TYPE", "TABLE", "TABLE_NAME", "app_config")));
        db.functions = List.of(FakeJdbc.Row.of("FUNCTION_NAME", "fn_hash"));

        DatabaseObjects objects = service(db).loadObjects(null, null);
        assertEquals(List.of("app_config", "orders"), objects.tables());
        assertEquals(List.of("v_summary"), objects.views());
        assertEquals(List.of("fn_hash"), objects.functions());
    }

    @Test
    void unsupportedFunctionMetadataLeavesEmptyFunctionList() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.tables = List.of(FakeJdbc.Row.of(Map.of("TABLE_TYPE", "TABLE", "TABLE_NAME", "t")));
        db.failFunctions = true;

        DatabaseObjects objects = service(db).loadObjects(null, null);
        assertEquals(List.of("t"), objects.tables());
        assertTrue(objects.functions().isEmpty());
    }

    // --- columnsOf ---

    @Test
    void columnsAreReadFromMetadata() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.columns = List.of(FakeJdbc.Row.of("COLUMN_NAME", "id"),
                FakeJdbc.Row.of("COLUMN_NAME", "name"));

        assertEquals(List.of("id", "name"), service(db).columnsOf(null, null, "t"));
    }

    // --- switchSchema ---

    @Test
    void standardSetSchemaIsPreferred() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        service(db).switchSchema("MySQL", "app");
        assertEquals("app", db.setSchemaArg);
        assertTrue(db.executedSql.isEmpty());
    }

    @Test
    void oracleFallsBackToAlterSession() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.failSetSchema = true;
        service(db).switchSchema("Oracle", "app");
        assertEquals(List.of("ALTER SESSION SET CURRENT_SCHEMA = app"), db.executedSql);
    }

    @Test
    void postgreSqlFallsBackToSearchPath() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.failSetSchema = true;
        service(db).switchSchema("PostgreSQL", "tenant_a");
        assertEquals(List.of("SET search_path TO tenant_a"), db.executedSql);
    }

    @Test
    void unsupportedSetSchemaOnOtherTypesIsSilentlyIgnored() throws Exception {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.failSetSchema = true;
        service(db).switchSchema("MySQL", "app");
        assertTrue(db.executedSql.isEmpty());
    }

    @Test
    void hostileSchemaNameIsRejectedBeforeTouchingConnection() {
        FakeJdbc.Db db = new FakeJdbc.Db();
        assertThrows(IllegalArgumentException.class,
                () -> service(db).switchSchema("Oracle", "x\"; DROP TABLE users; --"));
        assertTrue(db.executedSql.isEmpty());
        assertNull(db.setSchemaArg);
    }

    // --- activeCatalog / activeSchema ---

    @Test
    void activeCatalogAndSchemaReturnNullWhenDriverDoesNotSupportThem() {
        FakeJdbc.Db db = new FakeJdbc.Db();
        db.failGetCatalog = true;
        db.failGetSchema = true;
        DatabaseMetadataService service = service(db);
        assertNull(service.activeCatalog());
        assertNull(service.activeSchema());
    }
}
