package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.DatabaseObjects;
import com.aqishi.toolbox.feature.data.domain.SqlTextSupport;
import com.aqishi.toolbox.util.Errors;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 数据库元数据编排：库/模式列表、对象清单、列清单与模式切换。
 * 面板只保留 Swing 装配；连接经 {@link Supplier} 惰性获取，
 * 因为 PostgreSQL 切库会物理重建连接。
 */
public final class DatabaseMetadataService {

    private final Supplier<Connection> connectionSupplier;
    private final Consumer<String> diagnostics;

    public DatabaseMetadataService(Supplier<Connection> connectionSupplier,
                                   Consumer<String> diagnostics) {
        if (connectionSupplier == null) {
            throw new NullPointerException("connectionSupplier");
        }
        this.connectionSupplier = connectionSupplier;
        this.diagnostics = diagnostics != null ? diagnostics : message -> { };
    }

    /**
     * 数据库（catalog）列表：PostgreSQL/MySQL 优先用各自的 SQL 查询，
     * 失败回退到标准 {@code getCatalogs}；仍为空回退当前 catalog，
     * 再退到连接表单里填的库名。结果排序去重。
     */
    public List<String> listDatabases(String dbType, String urlDatabaseFallback) throws SQLException {
        Connection connection = connectionSupplier.get();
        DatabaseMetaData meta = connection.getMetaData();
        List<String> databases = new ArrayList<>();

        if ("PostgreSQL".equals(dbType)) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT datname FROM pg_database WHERE datistemplate = false"
                                 + " AND datallowconn = true ORDER BY datname")) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            } catch (Throwable error) {
                databases.addAll(readCatalogs(meta));
            }
        } else if ("MySQL".equals(dbType)) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW DATABASES")) {
                while (rs.next()) {
                    databases.add(rs.getString(1));
                }
            } catch (Throwable error) {
                databases.addAll(readCatalogs(meta));
            }
        } else {
            databases.addAll(readCatalogs(meta));
        }

        if (databases.isEmpty()) {
            String current = activeCatalog();
            String fallback = urlDatabaseFallback == null ? "" : urlDatabaseFallback.trim();
            if (current != null && !current.isEmpty()) {
                databases.add(current);
            } else if (!fallback.isEmpty()) {
                databases.add(fallback);
            }
        }
        return DatabaseObjects.sortedUnique(databases);
    }

    /** 模式（schema）列表，排序。 */
    public List<String> listSchemas() throws SQLException {
        DatabaseMetaData meta = connectionSupplier.get().getMetaData();
        List<String> schemas = new ArrayList<>();
        try (ResultSet rs = meta.getSchemas()) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (schema != null && !schema.isEmpty()) {
                    schemas.add(schema);
                }
            }
        }
        return DatabaseObjects.sortedUnique(schemas);
    }

    /**
     * 表/视图/函数清单。{@code catalog}/{@code schema} 传 null 时按当前连接的
     * 活动 catalog/schema 解析；驱动不支持函数元数据时函数列表为空。
     */
    public DatabaseObjects loadObjects(String catalog, String schema) throws SQLException {
        Connection connection = connectionSupplier.get();
        DatabaseMetaData meta = connection.getMetaData();
        String resolvedCatalog = catalog != null ? catalog : activeCatalog();
        String resolvedSchema = schema != null ? schema : activeSchema();

        List<DatabaseObjects.Row> rows = new ArrayList<>();
        try (ResultSet rs = meta.getTables(resolvedCatalog, resolvedSchema, "%",
                new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) {
                rows.add(new DatabaseObjects.Row(rs.getString("TABLE_TYPE"),
                        rs.getString("TABLE_NAME")));
            }
        }

        List<String> functions = new ArrayList<>();
        try (ResultSet rs = meta.getFunctions(resolvedCatalog, resolvedSchema, "%")) {
            while (rs.next()) {
                String name = rs.getString("FUNCTION_NAME");
                if (name != null && !name.isEmpty()) {
                    functions.add(name);
                }
            }
        } catch (Throwable error) {
            Errors.ignored("驱动不支持函数元数据，函数补全列表留空", error);
        }
        return DatabaseObjects.fromRows(rows, functions);
    }

    /** 表的列名清单，catalog/schema 解析规则同 {@link #loadObjects}。 */
    public List<String> columnsOf(String catalog, String schema, String table) throws SQLException {
        Connection connection = connectionSupplier.get();
        DatabaseMetaData meta = connection.getMetaData();
        String resolvedCatalog = catalog != null ? catalog : activeCatalog();
        String resolvedSchema = schema != null ? schema : activeSchema();

        List<String> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(resolvedCatalog, resolvedSchema, table, "%")) {
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    /**
     * 切换当前模式。优先用标准 {@code setSchema}；驱动不支持时按方言降级：
     * Oracle 用 {@code ALTER SESSION SET CURRENT_SCHEMA}，PostgreSQL 用
     * {@code SET search_path}。模式名先过 {@link SqlTextSupport#safeIdentifier}
     * 白名单校验，非法输入在触碰连接前直接拒绝。
     */
    public void switchSchema(String dbType, String schemaName) throws Exception {
        String safeSchema = SqlTextSupport.safeIdentifier(schemaName);
        Connection connection = connectionSupplier.get();
        try {
            connection.setSchema(safeSchema);
        } catch (Throwable error) {
            if ("Oracle".equals(dbType)) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("ALTER SESSION SET CURRENT_SCHEMA = " + safeSchema);
                }
            } else if ("PostgreSQL".equals(dbType)) {
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("SET search_path TO " + safeSchema);
                }
            }
        }
    }

    /** 当前 catalog；驱动不支持时返回 {@code null} 而不是抛出。 */
    public String activeCatalog() {
        try {
            return connectionSupplier.get().getCatalog();
        } catch (Throwable error) {
            Errors.ignored("驱动不支持 getCatalog，跳过自动选中当前库", error);
            return null;
        }
    }

    /** 当前 schema；驱动不支持时返回 {@code null} 而不是抛出。 */
    public String activeSchema() {
        try {
            return connectionSupplier.get().getSchema();
        } catch (Throwable error) {
            Errors.ignored("驱动不支持 getSchema，改用默认模式查询元数据", error);
            return null;
        }
    }

    private static List<String> readCatalogs(DatabaseMetaData meta) throws SQLException {
        List<String> databases = new ArrayList<>();
        try (ResultSet rs = meta.getCatalogs()) {
            while (rs.next()) {
                String catalog = rs.getString("TABLE_CAT");
                if (catalog != null && !catalog.isEmpty()) {
                    databases.add(catalog);
                }
            }
        }
        return databases;
    }
}
