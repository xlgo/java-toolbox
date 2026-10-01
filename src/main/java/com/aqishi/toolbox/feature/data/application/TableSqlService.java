package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.QueryResult;
import com.aqishi.toolbox.util.I18n;

import org.apache.poi.ss.usermodel.*;

import java.math.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Bounded offline table import. Text is the default; only explicit column mappings perform type
 * conversion.
 */
public final class TableSqlService {
    public static final int MAX_ROWS = 10000, MAX_COLUMNS = 256;

    public record Table(List<String> columns, List<List<String>> rows) {
        public Table {
            columns = List.copyOf(columns);
            rows =
                    rows.stream()
                            .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
                            .toList();
        }
    }

    public enum Type {
        TEXT,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        DATE,
        DATETIME
    }

    public record Mapping(int source, String target, Type type, boolean include) {}

    public List<String> sheets(Path file) throws Exception {
        validateFile(file);
        try (Workbook book = WorkbookFactory.create(file.toFile(), null, true)) {
            List<String> result = new ArrayList<>();
            for (Sheet sheet : book) result.add(sheet.getSheetName());
            return result;
        }
    }

    public Table excel(Path file, String sheetName) throws Exception {
        validateFile(file);
        try (Workbook book = WorkbookFactory.create(file.toFile(), null, true)) {
            Sheet sheet = book.getSheet(sheetName);
            if (sheet == null)
                throw new IllegalArgumentException(I18n.get("tablesql.sheetMissing"));
            if (sheet.getLastRowNum() - sheet.getFirstRowNum() > MAX_ROWS) throw limit();
            DataFormatter format = new DataFormatter(Locale.ROOT);
            format.setUseCachedValuesForFormulaCells(true);
            List<List<String>> rows = new ArrayList<>();
            for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
                check();
                Row row = sheet.getRow(r);
                if (row == null) continue;
                if (row.getLastCellNum() > MAX_COLUMNS) throw limit();
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : format.formatCellValue(cell));
                }
                if (cells.stream().allMatch(String::isEmpty)) continue;
                rows.add(cells);
            }
            return table(rows);
        }
    }

    public Table csv(String input, char delimiter) {
        if (input.length() > 8_000_000) throw limit();
        if (delimiter == '"' || delimiter == '\r' || delimiter == '\n')
            throw new IllegalArgumentException("delimiter");
        if (input.startsWith("\uFEFF")) input = input.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quote = false, ended = false;
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (quote) {
                if (ch == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quote = false;
                        ended = true;
                    }
                } else cell.append(ch);
            } else if (ch == '"') {
                if (cell.length() > 0 || ended) throw syntax();
                quote = true;
            } else if (ch == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
                ended = false;
            } else if (ch == '\r' || ch == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                ended = false;
                rows.add(row);
                row = new ArrayList<>();
                if (ch == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
            } else {
                if (ended) throw syntax();
                cell.append(ch);
            }
            if (row.size() > MAX_COLUMNS || rows.size() > MAX_ROWS + 1) throw limit();
            check();
        }
        if (quote) throw syntax();
        if (cell.length() > 0 || ended || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return table(rows);
    }

    private static Table table(List<List<String>> rows) {
        if (rows.isEmpty() || rows.get(0).isEmpty())
            throw new IllegalArgumentException(I18n.get("devtools.inputRequired"));
        List<String> names = rows.get(0);
        if (names.size() > MAX_COLUMNS || rows.size() - 1 > MAX_ROWS) throw limit();
        List<List<String>> data = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            var row = new ArrayList<>(rows.get(i));
            if (row.size() > names.size())
                throw new IllegalArgumentException(I18n.get("tablesql.columnsMismatch", i + 1));
            while (row.size() < names.size()) row.add("");
            data.add(row);
        }
        return new Table(names, data);
    }

    public String generate(
            Table table,
            List<Mapping> mappings,
            String nullToken,
            boolean emptyNull,
            String target,
            QueryResultExporter.Dialect dialect)
            throws Exception {
        List<Mapping> selected = mappings.stream().filter(Mapping::include).toList();
        if (selected.isEmpty())
            throw new IllegalArgumentException(I18n.get("tablesql.selectColumn"));
        Set<String> unique = new HashSet<>();
        for (Mapping m : selected) {
            if (m.source() < 0
                    || m.source() >= table.columns().size()
                    || m.target().isBlank()
                    || !unique.add(m.target()))
                throw new IllegalArgumentException(I18n.get("tablesql.mapping"));
        }
        List<List<Object>> output = new ArrayList<>();
        for (int r = 0; r < table.rows().size(); r++) {
            check();
            List<Object> row = new ArrayList<>();
            for (Mapping m : selected) {
                String text = table.rows().get(r).get(m.source());
                try {
                    row.add(
                            text == null
                                            || (emptyNull && text.isEmpty())
                                            || (!nullToken.isEmpty() && text.equals(nullToken))
                                    ? null
                                    : convert(text, m.type()));
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException(
                            I18n.get("tablesql.typeError", r + 2, m.target(), m.type()));
                }
            }
            output.add(row);
        }
        return new QueryResultExporter()
                .sqlText(
                        QueryResult.typedRows(
                                selected.stream().map(Mapping::target).toList(), output, 0, null),
                        target,
                        dialect);
    }

    private static Object convert(String value, Type type) {
        return switch (type) {
            case TEXT -> value;
            case INTEGER -> new BigInteger(value.trim());
            case DECIMAL -> new BigDecimal(value.trim());
            case BOOLEAN -> {
                if (value.equalsIgnoreCase("true") || value.equals("1")) yield true;
                if (value.equalsIgnoreCase("false") || value.equals("0")) yield false;
                throw new IllegalArgumentException();
            }
            case DATE -> LocalDate.parse(value.trim());
            case DATETIME -> LocalDateTime.parse(value.trim().replace(' ', 'T'));
        };
    }

    private static void validateFile(Path path) throws Exception {
        if (Files.size(path) > 32L * 1024 * 1024) throw limit();
    }

    private static IllegalArgumentException syntax() {
        return new IllegalArgumentException(I18n.get("tablesql.csvInvalid"));
    }

    private static IllegalArgumentException limit() {
        return new IllegalArgumentException(I18n.get("tablesql.limit"));
    }

    private static void check() {
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
    }
}
