package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.QueryResult;
import com.aqishi.toolbox.feature.data.application.QueryResultExporter.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryResultExporterTest {
    @TempDir Path temp;
    private QueryResult sample(){return QueryResult.typedRows(List.of("number","empty","missing","text","blob","day","bool"),
            List.of(Arrays.asList(new BigDecimal("123456789012345678.50"),"",null,"=SUM(A1)\r\nO'Reilly,\"x\"",new QueryResult.Binary("AP8="),java.time.LocalDate.of(2026,10,1),true)),0,null);}
    private Path write(QueryResult result,Format format) throws Exception{
        Path file=temp.resolve("result."+format);new QueryResultExporter().export(result,file,new Options(format,"app.order",Dialect.STANDARD,true));return file;
    }
    @Test void jsonPreservesNumbersNullsAndBase64() throws Exception{
        var node=new ObjectMapper().readTree(Files.readString(write(sample(),Format.JSON))).get(0);
        assertEquals("",node.get("empty").asText());assertTrue(node.get("missing").isNull());assertTrue(node.get("number").isNumber());
        assertEquals("AP8=",node.get("blob").asText());assertTrue(node.get("bool").isBoolean());assertEquals("2026-10-01",node.get("day").asText());
    }
    @Test void csvQuotesLineBreaksDistinguishesNullAndGuardsFormula() throws Exception{
        String text=Files.readString(write(sample(),Format.CSV));assertTrue(text.startsWith("\uFEFF"));
        assertTrue(text.contains(",\"\",\\N,"));assertTrue(text.contains("\"'=SUM(A1)\r\nO'Reilly,\"\"x\"\"\""));assertTrue(text.endsWith("\r\n"));
    }
    @Test void xlsxNeverCreatesFormulaAndKeepsLongNumberExact() throws Exception{
        try(var book=new XSSFWorkbook(Files.newInputStream(write(sample(),Format.XLSX)))){
            var row=book.getSheetAt(0).getRow(1);assertEquals("123456789012345678.50",row.getCell(0).getStringCellValue());
            assertEquals(org.apache.poi.ss.usermodel.CellType.BLANK,row.getCell(2).getCellType());
            assertEquals(org.apache.poi.ss.usermodel.CellType.STRING,row.getCell(3).getCellType());assertEquals("AP8=",row.getCell(4).getStringCellValue());
        }
    }
    @Test void sqlEscapesIdentifiersAndValuesAndUsesBinaryLiteral() throws Exception{
        String sql=Files.readString(write(sample(),Format.SQL));assertTrue(sql.contains("INSERT INTO \"app\".\"order\""));
        assertTrue(sql.contains("O''Reilly"));assertTrue(sql.contains("X'00ff'"));assertTrue(sql.contains(", NULL,"));
    }
    @Test void dialectSpecificBinaryAndBackslashText() throws Exception{
        var result=QueryResult.typedRows(List.of("v","b"),List.of(List.of("a\\b'",new QueryResult.Binary("AP8="))),0,null);
        for(var dialect:Dialect.values()){
            Path file=temp.resolve(dialect+".sql");new QueryResultExporter().export(result,file,new Options(Format.SQL,"t",dialect,false));
            String sql=Files.readString(file);
            switch(dialect){case MYSQL->assertTrue(sql.contains("CONVERT(X'"));case POSTGRESQL->assertTrue(sql.contains("decode('00ff'"));case SQLSERVER->assertTrue(sql.contains("0x00ff"));case ORACLE->assertTrue(sql.contains("hextoraw('00ff'"));default->assertTrue(sql.contains("X'00ff'"));}
        }
    }
    @Test void duplicateColumnsCannotBeSilentlyLostInJson() {
        var result=QueryResult.rows(List.of("a","a"),List.of(List.of("1","2")),0,null);
        assertThrows(IllegalArgumentException.class,()->write(result,Format.JSON));
    }
    @Test void failedExportPreservesExistingFileAndRemovesTemporary() throws Exception{
        Path target=temp.resolve("keep.xlsx");Files.writeString(target,"original");
        var result=QueryResult.rows(List.of("v"),List.of(List.of("x".repeat(32768))),0,null);
        assertThrows(Exception.class,()->new QueryResultExporter().export(result,target,new Options(Format.XLSX,"t",Dialect.STANDARD,false)));
        assertEquals("original",Files.readString(target));try(var files=Files.list(temp)){assertEquals(1,files.count());}
    }
    @Test void jdbcTypesSurviveResultSetClosure() throws Exception{
        try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite::memory:")){
            QueryResult result=new SqlExecutionService().execute(connection,"select 42 as N, null as M, X'00ff' as B, '2026-10-01' as D");
            assertInstanceOf(Number.class,result.getExportRows().get(0).get(0));assertInstanceOf(QueryResult.Binary.class,result.getExportRows().get(0).get(2));
            assertEquals("2026-10-01",result.getExportRows().get(0).get(3));
            assertEquals("[Binary: 2 bytes]",result.getRows().get(0).get(2));
        }
    }
}
