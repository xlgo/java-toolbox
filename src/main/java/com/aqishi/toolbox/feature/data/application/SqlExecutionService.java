package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.QueryResult;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDBC SQL 执行用例。
 *
 * <p>该服务不持有连接；调用方仍负责连接的打开、取消与关闭。这样可将 JDBC 结果
 * 转换为领域数据，同时避免把 {@link javax.swing.table.TableModel} 泄漏到应用层。</p>
 */
public final class SqlExecutionService {

    public static final int DEFAULT_MAX_ROWS = 5000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final int maxRows;
    private final int timeoutSeconds;

    public SqlExecutionService() {
        this(DEFAULT_MAX_ROWS);
    }

    public SqlExecutionService(int maxRows) {
        this(maxRows, DEFAULT_TIMEOUT_SECONDS);
    }

    public SqlExecutionService(int maxRows, int timeoutSeconds) {
        if (maxRows < 1 || maxRows == Integer.MAX_VALUE || timeoutSeconds < 1) {
            throw new IllegalArgumentException("row limit and timeout must be positive and row limit must leave room for a sentinel row");
        }
        this.maxRows = maxRows;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** One token per execution; request() is nonblocking and safe on the EDT. */
    public static final class Cancellation {
        private final AtomicBoolean requested = new AtomicBoolean();
        private final AtomicReference<Statement> active = new AtomicReference<>();
        public void request() { requested.set(true); }
        public boolean isRequested() { return requested.get(); }
        /** JDBC cancel may perform network I/O, so callers must run this off the EDT. */
        public synchronized void cancelStatement() throws SQLException {
            request();
            Statement statement = active.get();
            if (statement != null) statement.cancel();
        }
        private void check() {
            if (requested.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
    }

    /**
     * 执行一条 SQL，并在连接仍有效的同步调用范围内读取完所有结果。
     */
    public QueryResult execute(Connection connection, String sql) throws Exception {
        return execute(connection, sql, new Cancellation());
    }

    public QueryResult execute(Connection connection, String sql, Cancellation cancellation) throws Exception {
        cancellation.check();
        if (connection == null || connection.isClosed()) {
            throw new IllegalStateException("数据库连接已关闭");
        }
        if (sql == null || sql.trim().isEmpty()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }

        long startedAt = System.nanoTime();
        try (Statement statement = connection.createStatement()) {
            cancellation.active.set(statement);
            cancellation.check();
            // The extra row retains the distinction between exact limit and actual truncation.
            try { statement.setMaxRows(maxRows + 1); }
            catch (SQLFeatureNotSupportedException unsupported) { com.aqishi.toolbox.util.Errors.ignored("JDBC row cap unsupported; applying local cap", unsupported); }
            try { statement.setQueryTimeout(timeoutSeconds); }
            catch (SQLFeatureNotSupportedException unsupported) { com.aqishi.toolbox.util.Errors.ignored("JDBC query timeout unsupported", unsupported); }
            try { statement.setFetchSize(Math.min(256, maxRows + 1)); }
            catch (SQLFeatureNotSupportedException unsupported) { com.aqishi.toolbox.util.Errors.ignored("JDBC fetch size unsupported", unsupported); }
            cancellation.check();
            boolean producesRows = statement.execute(sql);
            cancellation.check();
            if (!producesRows) {
                return QueryResult.update(statement.getUpdateCount(), elapsed(startedAt));
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return readRows(resultSet, startedAt, cancellation);
            }
        } catch (SQLException error) {
            cancellation.check();
            throw error;
        } finally {
            synchronized (cancellation) { cancellation.active.set(null); }
        }
    }

    private static long elapsed(long started) { return (System.nanoTime() - started) / 1_000_000; }

    private QueryResult readRows(ResultSet resultSet, long started, Cancellation cancellation) throws Exception {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> columnNames = new ArrayList<>();
        for (int column = 1; column <= columnCount; column++) {
            columnNames.add(metadata.getColumnLabel(column));
        }

        List<List<Object>> rows = new ArrayList<>();
        String warning = null;
        while (true) {
            cancellation.check();
            if (!resultSet.next()) break;
            List<Object> row = new ArrayList<>();
            for (int column = 1; column <= columnCount; column++) {
                cancellation.check();
                Object value = resultSet.getObject(column);
                row.add(snapshotValue(value));
            }
            rows.add(row);
            if (rows.size() >= maxRows) {
                if (resultSet.next()) warning = "数据集超过 " + maxRows + " 行限制，已自动截断。";
                break;
            }
        }
        cancellation.check();
        return QueryResult.typedRows(columnNames, rows, elapsed(started), warning);
    }

    private static Object snapshotValue(Object value) throws Exception {
        if (value instanceof java.sql.Blob blob) {
            try {
                if (blob.length() > 1024 * 1024) throw new java.sql.SQLException(com.aqishi.toolbox.util.I18n.get("data.export.lobLimit"));
                return new QueryResult.Binary(java.util.Base64.getEncoder().encodeToString(blob.getBytes(1, (int) blob.length())));
            } finally { blob.free(); }
        }
        if (value instanceof java.sql.Clob clob) {
            try {
                if (clob.length() > 1024 * 1024) throw new java.sql.SQLException(com.aqishi.toolbox.util.I18n.get("data.export.lobLimit"));
                return clob.getSubString(1, (int) clob.length());
            } finally { clob.free(); }
        }
        if (value instanceof byte[] bytes) return new QueryResult.Binary(java.util.Base64.getEncoder().encodeToString(bytes));
        if (value instanceof java.sql.Timestamp t) return t.toLocalDateTime();
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof java.sql.Time t) return t.toLocalTime();
        if (value instanceof Number || value instanceof Boolean || value instanceof java.time.temporal.TemporalAccessor) return value;
        return value == null ? null : value.toString();
    }
}
