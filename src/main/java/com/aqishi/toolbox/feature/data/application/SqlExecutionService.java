package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.QueryResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC SQL 执行用例。
 *
 * <p>该服务不持有连接；调用方仍负责连接的打开、取消与关闭。这样可将 JDBC 结果
 * 转换为领域数据，同时避免把 {@link javax.swing.table.TableModel} 泄漏到应用层。</p>
 */
public final class SqlExecutionService {

    public static final int DEFAULT_MAX_ROWS = 5000;

    private final int maxRows;

    public SqlExecutionService() {
        this(DEFAULT_MAX_ROWS);
    }

    public SqlExecutionService(int maxRows) {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        this.maxRows = maxRows;
    }

    /**
     * 执行一条 SQL，并在连接仍有效的同步调用范围内读取完所有结果。
     */
    public QueryResult execute(Connection connection, String sql) throws Exception {
        if (connection == null || connection.isClosed()) {
            throw new IllegalStateException("数据库连接已关闭");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }

        long startedAt = System.currentTimeMillis();
        try (Statement statement = connection.createStatement()) {
            boolean producesRows = statement.execute(sql);
            long durationMillis = System.currentTimeMillis() - startedAt;
            if (!producesRows) {
                return QueryResult.update(statement.getUpdateCount(), durationMillis);
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return readRows(resultSet, durationMillis);
            }
        }
    }

    private QueryResult readRows(ResultSet resultSet, long durationMillis) throws Exception {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columnNames = new ArrayList<>();
        for (int column = 1; column <= columnCount; column++) {
            columnNames.add(metadata.getColumnLabel(column));
        }

        List<List<Object>> rows = new ArrayList<>();
        String warning = null;
        while (resultSet.next()) {
            List<Object> row = new ArrayList<>();
            for (int column = 1; column <= columnCount; column++) {
                Object value = resultSet.getObject(column);
                row.add(displayValue(value));
            }
            rows.add(row);
            if (rows.size() >= maxRows) {
                warning = "数据集超过 " + maxRows + " 行限制，已自动截断。";
                break;
            }
        }
        return QueryResult.rows(columnNames, rows, durationMillis, warning);
    }

    private static Object displayValue(Object value) {
        if (value instanceof byte[]) {
            return "[Binary: " + ((byte[]) value).length + " bytes]";
        }
        return value == null ? null : value.toString();
    }
}
