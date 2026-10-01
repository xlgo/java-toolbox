package com.aqishi.toolbox.feature.data.application;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class TableSqlServiceTest {
    @TempDir Path temp;
    private final TableSqlService service = new TableSqlService();

    @Test
    void csvPreservesLeadingZerosMultilineAndEmpty() throws Exception {
        var table =
                service.csv(
                        "\uFEFFid,name,note\r\n00123,\"O'Reilly\",\"a\nb\"\r\n00002,,\\N\r\n", ',');
        var maps =
                List.of(
                        new TableSqlService.Mapping(0, "id", TableSqlService.Type.TEXT, true),
                        new TableSqlService.Mapping(1, "name", TableSqlService.Type.TEXT, true),
                        new TableSqlService.Mapping(2, "note", TableSqlService.Type.TEXT, true));
        String sql =
                service.generate(
                        table,
                        maps,
                        "\\N",
                        false,
                        "app.users",
                        QueryResultExporter.Dialect.POSTGRESQL);
        assertTrue(sql.contains("'00123'"));
        assertTrue(sql.contains("'O''Reilly'"));
        assertTrue(sql.contains("'a\nb'"));
        assertTrue(sql.contains("'00002', '', NULL"));
    }

    @Test
    void explicitMappingConvertsTypesAndOmitsColumns() throws Exception {
        var table = service.csv("id,n,enabled,day\n0002,12.30,true,2024-02-29", ',');
        var map =
                List.of(
                        new TableSqlService.Mapping(0, "id", TableSqlService.Type.INTEGER, true),
                        new TableSqlService.Mapping(1, "n", TableSqlService.Type.DECIMAL, true),
                        new TableSqlService.Mapping(
                                2, "enabled", TableSqlService.Type.BOOLEAN, false),
                        new TableSqlService.Mapping(3, "day", TableSqlService.Type.DATE, true));
        String sql =
                service.generate(table, map, "", false, "t", QueryResultExporter.Dialect.ORACLE);
        assertTrue(sql.contains("VALUES (2, 12.30, DATE '2024-02-29')"));
        assertFalse(sql.contains("enabled"));
    }

    @Test
    void malformedCsvAndInvalidTypesDoNotProducePartialSql() {
        assertThrows(Exception.class, () -> service.csv("a\n\"bad", ','));
        assertThrows(Exception.class, () -> service.csv("a\n1,2", ','));
        var table = service.csv("n\nNaN", ',');
        assertThrows(
                Exception.class,
                () ->
                        service.generate(
                                table,
                                List.of(
                                        new TableSqlService.Mapping(
                                                0, "n", TableSqlService.Type.DECIMAL, true)),
                                "",
                                false,
                                "t",
                                QueryResultExporter.Dialect.STANDARD));
    }

    @Test
    void excelSheetsAndDisplayedIdentifiersArePreserved() throws Exception {
        Path file = temp.resolve("sample.xlsx");
        try (var book = new XSSFWorkbook()) {
            book.createSheet("other");
            var sheet = book.createSheet("data");
            sheet.createRow(0).createCell(0).setCellValue("code");
            var cell = sheet.createRow(1).createCell(0);
            cell.setCellValue(123);
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("00000"));
            cell.setCellStyle(style);
            try (var out = Files.newOutputStream(file)) {
                book.write(out);
            }
        }
        assertEquals(List.of("other", "data"), service.sheets(file));
        assertEquals("00123", service.excel(file, "data").rows().get(0).get(0));
    }
}
