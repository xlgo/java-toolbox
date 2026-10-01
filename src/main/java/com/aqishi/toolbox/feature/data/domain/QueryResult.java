package com.aqishi.toolbox.feature.data.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 与 Swing 无关的 SQL 执行结果。
 *
 * <p>结果数据在应用层被转换为不可变列表，界面层可按自己的表格模型、导出格式或
 * 日志格式消费它，而无需依赖 JDBC 的 {@code ResultSet} 生命周期。</p>
 */
public final class QueryResult {

    private final List<String> columnNames;
    private final List<List<Object>> rows;
    private final int updateCount;
    private final long durationMillis;
    private final String warning;
    private List<List<Object>> exportRows;
    /** Immutable binary snapshot, independent of the JDBC connection. */
    public record Binary(String base64) { }

    private QueryResult(List<String> columnNames, List<List<Object>> rows,
                        int updateCount, long durationMillis, String warning) {
        this.columnNames = Collections.unmodifiableList(new ArrayList<>(columnNames));
        List<List<Object>> copiedRows = new ArrayList<>();
        for (List<Object> row : rows) {
            copiedRows.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        this.rows = Collections.unmodifiableList(copiedRows);
        this.updateCount = updateCount;
        this.durationMillis = durationMillis;
        this.warning = warning;
        this.exportRows = this.rows;
    }

    public static QueryResult rows(List<String> columnNames, List<List<Object>> rows,
                                   long durationMillis, String warning) {
        return new QueryResult(columnNames, rows, -1, durationMillis, warning);
    }

    public static QueryResult typedRows(List<String> names, List<List<Object>> typed, long millis, String warning) {
        List<List<Object>> display = new ArrayList<>();
        for (List<Object> row : typed) {
            List<Object> values = new ArrayList<>();
            for (Object value : row) values.add(value instanceof Binary b
                    ? "[Binary: " + java.util.Base64.getDecoder().decode(b.base64()).length + " bytes]"
                    : value == null ? null : value.toString());
            display.add(values);
        }
        QueryResult result = new QueryResult(names, display, -1, millis, warning);
        List<List<Object>> copy = new ArrayList<>();
        for (List<Object> row : typed) copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        result.exportRows = Collections.unmodifiableList(copy);
        return result;
    }

    public List<List<Object>> getExportRows() { return exportRows; }

    public static QueryResult update(int updateCount, long durationMillis) {
        return new QueryResult(Collections.<String>emptyList(),
                Collections.<List<Object>>emptyList(), updateCount, durationMillis, null);
    }

    public List<String> getColumnNames() {
        return columnNames;
    }

    public List<List<Object>> getRows() {
        return rows;
    }

    public int getUpdateCount() {
        return updateCount;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public String getWarning() {
        return warning;
    }

    public boolean isUpdate() {
        return updateCount >= 0;
    }
}
