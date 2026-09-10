package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseError;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Converts structured text formats through a JSON-compatible object model.
 *
 * <p>All parsing and serialization is kept outside Swing so callers can
 * validate input and display a useful syntax diagnostic before converting it.
 * XML and CSV retain the workbench's existing mapping rules. INI values are
 * textual by definition, while TOML date/time literals become strings because
 * the intermediate model intentionally remains JSON-compatible.</p>
 */
public final class FormatConversionService {

    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;
    private final XmlMapper xmlMapper;

    public FormatConversionService() {
        this(Json.prettyMapper(), new YAMLMapper(), new XmlMapper());
    }

    FormatConversionService(ObjectMapper jsonMapper, YAMLMapper yamlMapper, XmlMapper xmlMapper) {
        this.jsonMapper = jsonMapper;
        this.yamlMapper = yamlMapper;
        this.xmlMapper = xmlMapper;
    }

    public ConversionResult convert(String source, String sourceFormat, String targetFormat) {
        try {
            Format from = Format.fromLabel(sourceFormat);
            Format to = Format.fromLabel(targetFormat);
            String normalized = requireSource(source);
            return ConversionResult.success(format(parse(normalized, from), to));
        } catch (Exception ex) {
            return ConversionResult.failure(describe(ex));
        }
    }

    public ValidationResult validate(String source, String format) {
        try {
            Format parsedFormat = Format.fromLabel(format);
            parse(requireSource(source), parsedFormat);
            return ValidationResult.success();
        } catch (Exception ex) {
            return ValidationResult.failure(describe(ex));
        }
    }

    public Object parse(String source, String format) {
        return parse(requireSource(source), Format.fromLabel(format));
    }

    public String format(Object value, String format) {
        return format(value, Format.fromLabel(format));
    }

    public String convertOrThrow(String source, String sourceFormat, String targetFormat) {
        return format(parse(requireSource(source), Format.fromLabel(sourceFormat)), Format.fromLabel(targetFormat));
    }

    private Object parse(String source, Format format) {
        try {
            return switch (format) {
                case JSON -> jsonMapper.readValue(source, Object.class);
                case YAML -> yamlMapper.readValue(source, Object.class);
                case XML -> xmlMapper.readValue(source, Object.class);
                case CSV -> parseCsv(source);
                case PROPERTIES -> parseProperties(source);
                case INI -> IniCodec.parse(source);
                case TOML -> parseToml(source);
            };
        } catch (FormatConversionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw syntaxError(format, ex);
        }
    }

    private String format(Object value, Format format) {
        try {
            return switch (format) {
                case JSON -> jsonMapper.writeValueAsString(value);
                case YAML -> yamlMapper.writeValueAsString(value);
                case XML -> xmlMapper.writeValueAsString(value);
                case CSV -> toCsv(value);
                case PROPERTIES -> toProperties(value);
                case INI -> IniCodec.format(value);
                case TOML -> TomlWriter.write(value);
            };
        } catch (FormatConversionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new FormatConversionException(format.displayName + " 序列化失败: " + rootMessage(ex), 1, 1, ex);
        }
    }

    private Map<String, Object> parseProperties(String source) throws Exception {
        Properties properties = new Properties();
        properties.load(new StringReader(source));
        Map<String, Object> root = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) {
            String[] keys = name.split("\\.");
            Map<String, Object> current = root;
            for (int i = 0; i < keys.length - 1; i++) {
                Object next = current.get(keys[i]);
                if (next instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> nested = (Map<String, Object>) next;
                    current = nested;
                } else {
                    Map<String, Object> nested = new LinkedHashMap<>();
                    current.put(keys[i], nested);
                    current = nested;
                }
            }
            current.put(keys[keys.length - 1], properties.getProperty(name));
        }
        return root;
    }

    private String toProperties(Object value) throws Exception {
        Properties properties = new Properties();
        if (value instanceof Map<?, ?> map) {
            flattenMap(map, "", properties);
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                if (item instanceof Map<?, ?> map) {
                    flattenMap(map, String.valueOf(i), properties);
                } else {
                    properties.setProperty(String.valueOf(i), item == null ? "" : String.valueOf(item));
                }
            }
        } else {
            properties.setProperty("value", value == null ? "" : String.valueOf(value));
        }

        StringWriter writer = new StringWriter();
        properties.store(writer, null);
        StringBuilder output = new StringBuilder();
        for (String line : writer.toString().split("\\R")) {
            if (!line.startsWith("#")) {
                output.append(line).append('\n');
            }
        }
        return output.toString().trim();
    }

    private Map<String, Object> parseToml(String source) {
        TomlParseResult result = Toml.parse(source);
        if (result.hasErrors()) {
            TomlParseError error = result.errors().get(0);
            TomlPosition position = error.position();
            int line = position == null ? 1 : Math.max(1, position.line());
            int column = position == null ? 1 : Math.max(1, position.column());
            throw new FormatConversionException("TOML 格式错误（第" + line + "行第" + column + "列）: "
                    + rootMessage(error), line, column, error);
        }
        return normalizeTomlMap(result.toMap());
    }

    private Map<String, Object> normalizeTomlMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            result.put(entry.getKey(), normalizeTomlValue(entry.getValue()));
        }
        return result;
    }

    private Object normalizeTomlValue(Object value) {
        if (value instanceof TomlTable table) {
            return normalizeTomlMap(table.toMap());
        }
        if (value instanceof TomlArray array) {
            List<Object> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                result.add(normalizeTomlValue(array.get(i)));
            }
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), normalizeTomlValue(entry.getValue()));
            }
            return result;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> result = new ArrayList<>();
            for (Object item : collection) {
                result.add(normalizeTomlValue(item));
            }
            return result;
        }
        if (value instanceof java.time.temporal.TemporalAccessor) {
            return value.toString();
        }
        return value;
    }

    private void flattenMap(Map<?, ?> map, String prefix, Properties properties) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flattenMap(nested, key, properties);
            } else if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++) {
                    Object item = list.get(i);
                    if (item instanceof Map<?, ?> nested) {
                        flattenMap(nested, key + "." + i, properties);
                    } else {
                        properties.setProperty(key + "." + i, item == null ? "" : String.valueOf(item));
                    }
                }
            } else {
                properties.setProperty(key, value == null ? "" : String.valueOf(value));
            }
        }
    }

    private List<Map<String, Object>> parseCsv(String source) {
        List<List<String>> rows = parseCsvRows(source);
        List<Map<String, Object>> result = new ArrayList<>();
        if (rows.isEmpty()) {
            return result;
        }
        List<String> headers = rows.get(0);
        for (int row = 1; row < rows.size(); row++) {
            List<String> values = rows.get(row);
            boolean blank = values.stream().allMatch(String::isEmpty);
            if (blank) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            for (int column = 0; column < headers.size(); column++) {
                item.put(headers.get(column), column < values.size() ? values.get(column) : "");
            }
            result.add(item);
        }
        return result;
    }

    private List<List<String>> parseCsvRows(String source) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int column = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            column++;
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < source.length() && source.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                        column++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                    if (c == '\n') {
                        line++;
                        column = 0;
                    }
                }
                continue;
            }
            if (c == '"') {
                if (field.length() != 0) {
                    throw new FormatConversionException("CSV 格式错误（第" + line + "行第" + column + "列）: 引号必须位于字段开头", line, column);
                }
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < source.length() && source.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString().trim());
                rows.add(row);
                row = new ArrayList<>();
                field.setLength(0);
                line++;
                column = 0;
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new FormatConversionException("CSV 格式错误（第" + line + "行第" + column + "列）: 引号未闭合", line, column);
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    private String toCsv(Object value) {
        List<Map<?, ?>> rows = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    rows.add(map);
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            rows.add(map);
        }
        if (rows.isEmpty()) {
            return "";
        }

        Set<String> headers = new LinkedHashSet<>();
        for (Map<?, ?> row : rows) {
            for (Object key : row.keySet()) {
                headers.add(String.valueOf(key));
            }
        }

        StringBuilder output = new StringBuilder();
        appendCsvRow(output, headers);
        for (Map<?, ?> row : rows) {
            List<String> values = new ArrayList<>();
            for (String header : headers) {
                Object valueForHeader = row.get(header);
                values.add(valueForHeader == null ? "" : String.valueOf(valueForHeader));
            }
            appendCsvRow(output, values);
        }
        return output.toString();
    }

    private void appendCsvRow(StringBuilder output, Iterable<String> fields) {
        boolean first = true;
        for (String field : fields) {
            if (!first) {
                output.append(',');
            }
            if (field.indexOf(',') >= 0 || field.indexOf('"') >= 0 || field.indexOf('\n') >= 0 || field.indexOf('\r') >= 0) {
                output.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                output.append(field);
            }
            first = false;
        }
        output.append('\n');
    }

    private String requireSource(String source) {
        if (source == null || source.trim().isEmpty()) {
            throw new FormatConversionException("输入内容不能为空", 1, 1);
        }
        return source;
    }

    private FormatConversionException syntaxError(Format format, Exception exception) {
        if (exception instanceof JsonProcessingException processingException) {
            JsonLocation location = processingException.getLocation();
            int line = location == null ? 1 : Math.max(1, location.getLineNr());
            int column = location == null ? 1 : Math.max(1, location.getColumnNr());
            return new FormatConversionException(format.displayName + " 格式错误（第" + line + "行第" + column + "列）: "
                    + rootMessage(processingException), line, column, exception);
        }
        return new FormatConversionException(format.displayName + " 格式错误: " + rootMessage(exception), 1, 1, exception);
    }

    private String describe(Exception exception) {
        if (exception instanceof FormatConversionException) {
            return exception.getMessage();
        }
        return rootMessage(exception);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        int newline = message.indexOf('\n');
        return (newline >= 0 ? message.substring(0, newline) : message).trim();
    }

    public enum Format {
        JSON("JSON"),
        XML("XML"),
        YAML("YAML"),
        CSV("CSV"),
        PROPERTIES("Properties"),
        INI("INI"),
        TOML("TOML");

        private final String displayName;

        Format(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static Format fromLabel(String label) {
            for (Format format : values()) {
                if (format.displayName.equalsIgnoreCase(label == null ? "" : label.trim())) {
                    return format;
                }
            }
            throw new FormatConversionException("不支持的格式: " + label, 1, 1);
        }
    }

    public static final class ConversionResult {
        private final boolean success;
        private final String output;
        private final String errorMessage;

        private ConversionResult(boolean success, String output, String errorMessage) {
            this.success = success;
            this.output = output;
            this.errorMessage = errorMessage;
        }

        public static ConversionResult success(String output) {
            return new ConversionResult(true, output, null);
        }

        public static ConversionResult failure(String errorMessage) {
            return new ConversionResult(false, null, errorMessage);
        }

        public boolean isSuccess() {
            return success;
        }

        public String getOutput() {
            return output;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    public static final class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
