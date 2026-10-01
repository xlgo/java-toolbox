package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.QueryResult;
import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.core.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Exports the last query snapshot without re-executing SQL. Output is installed only after a complete write. */
public final class QueryResultExporter {
    /** Offline generators reuse the same dialect rules as query-result export. */
    public String sqlText(QueryResult result, String table, Dialect dialect) throws IOException {
        if(new HashSet<>(result.getColumnNames()).size()!=result.getColumnNames().size())throw new IllegalArgumentException(I18n.get("data.export.duplicateColumns"));
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        sql(result,output,new Options(Format.SQL,table,dialect,false));
        return output.toString(StandardCharsets.UTF_8);
    }
    public enum Format { CSV, XLSX, JSON, SQL }
    public enum Dialect { STANDARD, MYSQL, POSTGRESQL, SQLSERVER, ORACLE }
    public record Options(Format format, String table, Dialect dialect, boolean csvFormulaGuard) { }

    public void export(QueryResult result, Path target, Options options) throws Exception {
        if (result.isUpdate()) throw new IllegalArgumentException(I18n.get("data.export.noResult"));
        if (options.format() == Format.JSON || options.format() == Format.SQL) {
            if (new HashSet<>(result.getColumnNames()).size() != result.getColumnNames().size())
                throw new IllegalArgumentException(I18n.get("data.export.duplicateColumns"));
        }
        Path absolute = target.toAbsolutePath();
        Path temp = Files.createTempFile(absolute.getParent(), ".query-export-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                switch (options.format()) {
                    case CSV -> csv(result, out, options.csvFormulaGuard());
                    case JSON -> json(result, out);
                    case SQL -> sql(result, out, options);
                    case XLSX -> xlsx(result, out);
                }
            }
            new com.aqishi.toolbox.vault.AtomicFiles().replace(temp, absolute);
        } finally { Files.deleteIfExists(temp); }
    }
    private static String text(Object value) { return value instanceof QueryResult.Binary b ? b.base64() : value.toString(); }
    private static void csv(QueryResult result, OutputStream out, boolean guard) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write('\uFEFF');
        csvRow(writer, new ArrayList<>(result.getColumnNames()), guard);
        for (var row : result.getExportRows()) { checkCancelled(); csvRow(writer, row, guard); }
        writer.flush();
    }
    private static void csvRow(Writer writer, List<?> row, boolean guard) throws IOException {
        for (int i=0;i<row.size();i++) {
            if (i>0) writer.write(',');
            Object value=row.get(i);
            if (value==null) { writer.write("\\N"); continue; }
            String text=text(value);
            if (guard && value instanceof String && text.matches("(?s)^[\\s]*[=+@-].*")) text="'"+text;
            writer.write('"'); writer.write(text.replace("\"","\"\"")); writer.write('"');
        }
        writer.write("\r\n");
    }
    private static void json(QueryResult result, OutputStream out) throws IOException {
        try (JsonGenerator gen=new JsonFactory().createGenerator(out)) {
            gen.useDefaultPrettyPrinter(); gen.writeStartArray();
            for(var row:result.getExportRows()) {
                checkCancelled(); gen.writeStartObject();
                for(int i=0;i<row.size();i++) {
                    gen.writeFieldName(result.getColumnNames().get(i)); Object value=row.get(i);
                    if(value==null) gen.writeNull();
                    else if(value instanceof Boolean b) gen.writeBoolean(b);
                    else if(value instanceof Number n && finite(n)) gen.writeNumber(n.toString());
                    else gen.writeString(text(value));
                }
                gen.writeEndObject();
            }
            gen.writeEndArray();
        }
    }
    private static boolean finite(Number n) {
        return !(n instanceof Double d && !Double.isFinite(d)) && !(n instanceof Float f && !Float.isFinite(f));
    }
    private static void xlsx(QueryResult result, OutputStream out) throws IOException {
        SXSSFWorkbook workbook=new SXSSFWorkbook(100);
        try {
            var sheet=workbook.createSheet("Query"); int index=0;
            var header=sheet.createRow(index++);
            for(int i=0;i<result.getColumnNames().size();i++) header.createCell(i).setCellValue(result.getColumnNames().get(i));
            for(var values:result.getExportRows()) {
                checkCancelled(); var row=sheet.createRow(index++);
                for(int i=0;i<values.size();i++) {
                    Object value=values.get(i); var cell=row.createCell(i);
                    if(value==null) continue;
                    if(value instanceof Boolean b) cell.setCellValue(b);
                    else if(value instanceof Number n && finite(n) && new java.math.BigDecimal(n.toString()).precision()<=15) cell.setCellValue(n.doubleValue());
                    else {
                        String text=text(value);
                        if(text.length()>32767) throw new IOException(I18n.get("data.export.excelLimit"));
                        cell.setCellValue(text); // Always text, never formula.
                    }
                }
            }
            sheet.createFreezePane(0,1); workbook.write(out);
        } finally { try { workbook.close(); } finally { workbook.dispose(); } }
    }
    private static void sql(QueryResult result, OutputStream out, Options options) throws IOException {
        String table=Objects.requireNonNullElse(options.table(), "").trim();
        if(table.isEmpty()) throw new IllegalArgumentException(I18n.get("data.export.tableRequired"));
        StringJoiner qualified=new StringJoiner(".");
        for(String part:table.split("\\.",-1)) {
            if(part.isBlank()) throw new IllegalArgumentException(I18n.get("data.export.tableRequired"));
            qualified.add(identifier(part.trim(),options.dialect()));
        }
        StringJoiner columns=new StringJoiner(", ");
        for(String name:result.getColumnNames()) columns.add(identifier(name,options.dialect()));
        Writer writer=new OutputStreamWriter(out,StandardCharsets.UTF_8);
        for(var row:result.getExportRows()) {
            checkCancelled(); StringJoiner values=new StringJoiner(", ");
            for(Object value:row) values.add(literal(value,options.dialect()));
            writer.write("INSERT INTO "+qualified+" ("+columns+") VALUES ("+values+");\n");
        }
        writer.flush();
    }
    private static String identifier(String name, Dialect dialect) {
        if(name.indexOf('\0')>=0) throw new IllegalArgumentException(I18n.get("data.export.identifier"));
        return switch(dialect) {
            case MYSQL -> "`"+name.replace("`","``")+"`";
            case SQLSERVER -> "["+name.replace("]","]]")+"]";
            default -> "\""+name.replace("\"","\"\"")+"\"";
        };
    }
    public static String literal(Object value,Dialect dialect) {
        if(value==null) return "NULL";
        if(value instanceof Boolean b) return dialect==Dialect.POSTGRESQL || dialect==Dialect.STANDARD ? (b?"TRUE":"FALSE") : (b?"1":"0");
        if(value instanceof Number n && finite(n)) return n.toString();
        if(value instanceof QueryResult.Binary b) {
            String hex=HexFormat.of().formatHex(Base64.getDecoder().decode(b.base64()));
            return switch(dialect) {
                case POSTGRESQL -> "decode('"+hex+"','hex')";
                case SQLSERVER -> "0x"+hex;
                case ORACLE -> "hextoraw('"+hex+"')";
                default -> "X'"+hex+"'";
            };
        }
        String text=text(value);
        if(dialect==Dialect.MYSQL && (text.contains("\\") || text.contains("\0")))
            return "CONVERT(X'"+HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8))+"' USING utf8mb4)";
        if(text.contains("\0")) throw new IllegalArgumentException(I18n.get("data.export.nul"));
        String quoted="'"+text.replace("'","''")+"'";
        if(dialect==Dialect.POSTGRESQL && text.contains("\\")) quoted="E'"+text.replace("\\","\\\\").replace("'","''")+"'";
        if(dialect==Dialect.SQLSERVER) return "N"+quoted;
        if(dialect==Dialect.ORACLE) {
            if(value instanceof java.time.LocalDate) return "DATE "+quoted;
            if(value instanceof java.time.LocalDateTime) return "TIMESTAMP '"+text.replace('T',' ')+"'";
        }
        return quoted;
    }
    private static void checkCancelled() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Export cancelled");
    }
}
