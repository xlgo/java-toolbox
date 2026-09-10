package com.aqishi.toolbox.feature.codec.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, deterministic INI codec used by the format workbench.
 *
 * <p>The codec supports comments ({@code #} and {@code ;}), section names,
 * {@code key=value} and {@code key:value} pairs, quoted values, and the
 * bracketed scalar-list form emitted by this class. Section names containing
 * dots are represented as nested maps. INI has no portable type or array
 * model; values which are not quoted remain strings, and bracketed lists are
 * the explicit extension used for lossless round trips inside the workbench.
 * Lists containing objects are rejected instead of being flattened silently.</p>
 */
final class IniCodec {

    private IniCodec() {
    }

    static Map<String, Object> parse(String source) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> current = root;
        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i == 0 && !line.isEmpty() && line.charAt(0) == '\ufeff') {
                line = line.substring(1);
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                continue;
            }

            String content = stripComment(line);
            trimmed = content.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            if (trimmed.startsWith("[")) {
                if (!trimmed.endsWith("]") || trimmed.length() < 3
                        || trimmed.charAt(1) == '[' || trimmed.charAt(trimmed.length() - 2) == ']') {
                    throw error(i, Math.max(1, line.indexOf('[') + 1), "INI section 语法错误");
                }
                String section = trimmed.substring(1, trimmed.length() - 1).trim();
                if (section.isEmpty()) {
                    throw error(i, line.indexOf('[') + 2, "section 名称不能为空");
                }
                validateSection(section, i, line.indexOf('[') + 2);
                current = section(root, section, i, line.indexOf('[') + 1);
                continue;
            }

            int separator = findSeparator(content);
            if (separator < 0) {
                throw error(i, Math.max(1, firstNonWhitespace(content) + 1),
                        "INI 键值行必须包含 '=' 或 ':'");
            }
            String key = content.substring(0, separator).trim();
            if (key.isEmpty()) {
                throw error(i, separator + 1, "INI 键不能为空");
            }
            validateKey(key, i, separator + 1);
            if (current.containsKey(key)) {
                throw error(i, separator + 1, "重复的 INI 键: " + key);
            }
            String value = content.substring(separator + 1).trim();
            current.put(key, parseValue(value, i, separator + 2));
        }
        return root;
    }

    static String format(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new FormatConversionException("INI 目标值必须是对象", 1, 1);
        }
        StringBuilder out = new StringBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) value;
        appendMap(out, root, null);
        return trimTrailingNewline(out);
    }

    private static void appendMap(StringBuilder out, Map<String, Object> map, String section) {
        if (section != null) {
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append('[').append(section).append("]\n");
        }

        List<Map.Entry<String, Object>> nested = new ArrayList<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            validateOutputKey(entry.getKey(), "INI");
            if (entry.getValue() instanceof Map<?, ?>) {
                // Nested maps are flattened into dotted section names. A dot
                // in one component would be interpreted as an extra path
                // segment when the generated INI is parsed again.
                validateSectionKey(entry.getKey(), "INI");
                nested.add(entry);
            } else {
                out.append(entry.getKey()).append('=').append(formatValue(entry.getValue())).append('\n');
            }
        }
        for (Map.Entry<String, Object> entry : nested) {
            String child = section == null ? entry.getKey() : section + "." + entry.getKey();
            @SuppressWarnings("unchecked")
            Map<String, Object> childMap = (Map<String, Object>) entry.getValue();
            appendMap(out, childMap, child);
        }
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "\"\"";
        }
        if (value instanceof Collection<?> collection) {
            StringBuilder out = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (item instanceof Collection<?> || item instanceof Map<?, ?>) {
                    throw new FormatConversionException("INI 只支持标量列表", 1, 1);
                }
                if (!first) {
                    out.append(", ");
                }
                out.append(formatValue(item));
                first = false;
            }
            return out.append(']').toString();
        }
        if (value instanceof Map<?, ?>) {
            throw new FormatConversionException("INI 不支持嵌套对象作为键值", 1, 1);
        }
        String text = String.valueOf(value);
        if (text.isEmpty() || needsQuotes(text)) {
            return quote(text);
        }
        return text;
    }

    private static boolean needsQuotes(String value) {
        if (!value.equals(value.trim())) {
            return true;
        }
        if (value.startsWith("[") || value.startsWith("#") || value.startsWith(";")
                || value.startsWith("'")) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=' || c == ':' || c == ',' || c == '\n' || c == '\r' || c == '"' || c == '\\'
                    || (c == '#' || c == ';') && (i == 0 || Character.isWhitespace(value.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + '"';
    }

    private static Object parseValue(String value, int line, int column) {
        if (value.isEmpty()) {
            return "";
        }
        if (value.startsWith("\"") || value.startsWith("'")) {
            char quote = value.charAt(0);
            if (value.length() < 2 || value.charAt(value.length() - 1) != quote) {
                throw error(line, column, "未闭合的 INI 引号");
            }
            String body = value.substring(1, value.length() - 1);
            return quote == '"' ? unescape(body, line, column) : body;
        }
        if (value.startsWith("[")) {
            if (!value.endsWith("]")) {
                throw error(line, column, "INI 列表未闭合");
            }
            return parseList(value.substring(1, value.length() - 1), line, column + 1);
        }
        return value;
    }

    private static List<Object> parseList(String body, int line, int column) {
        List<Object> values = new ArrayList<>();
        if (body.trim().isEmpty()) {
            return values;
        }
        for (String item : splitComma(body, line, column)) {
            values.add(parseValue(item.trim(), line, column));
        }
        return values;
    }

    private static List<String> splitComma(String value, int line, int column) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (quote == '"' && c == '\\' && !escaped) {
                    escaped = true;
                } else if (c == quote && !escaped) {
                    quote = 0;
                    escaped = false;
                } else {
                    escaped = false;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == ',') {
                if (current.toString().trim().isEmpty()) {
                    throw error(line, column + i, "INI 列表包含空元素");
                }
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quote != 0) {
            throw error(line, column + value.length(), "INI 列表中的引号未闭合");
        }
        if (current.toString().trim().isEmpty()) {
            throw error(line, column + value.length(), "INI 列表包含空元素");
        }
        result.add(current.toString());
        return result;
    }

    private static Map<String, Object> section(Map<String, Object> root, String name, int line, int column) {
        Map<String, Object> current = root;
        for (String part : name.split("\\.", -1)) {
            if (part.isEmpty()) {
                throw error(line, column, "INI section 名称不能包含空路径段");
            }
            Object existing = current.get(part);
            if (existing == null) {
                Map<String, Object> created = new LinkedHashMap<>();
                current.put(part, created);
                current = created;
            } else if (existing instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) existing;
                current = nested;
            } else {
                throw error(line, column, "section 与已有键冲突: " + name);
            }
        }
        return current;
    }

    private static String stripComment(String line) {
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\' && !escaped) {
                    escaped = true;
                } else if (c == quote && !escaped) {
                    quote = 0;
                    escaped = false;
                } else {
                    escaped = false;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if ((c == '#' || c == ';') && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        if (quote != 0) {
            // Let the value parser report a location when this is a key/value line.
            return line;
        }
        return line;
    }

    private static int findSeparator(String line) {
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (quote == '"' && c == '\\' && !escaped) {
                    escaped = true;
                } else if (c == quote && !escaped) {
                    quote = 0;
                    escaped = false;
                } else {
                    escaped = false;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value, int line, int column) {
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!escaped && c == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case '\\' -> out.append('\\');
                    case '"' -> out.append('"');
                    default -> throw error(line, column + i, "不支持的 INI 转义: \\" + c);
                }
                escaped = false;
            } else {
                out.append(c);
            }
        }
        if (escaped) {
            throw error(line, column + value.length(), "INI 转义序列不完整");
        }
        return out.toString();
    }

    private static void validateKey(String key, int line, int column) {
        if (key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0 || key.indexOf('=') >= 0 || key.indexOf(':') >= 0) {
            throw error(line, column, "INI 键包含非法字符");
        }
    }

    private static void validateSection(String section, int line, int column) {
        if (section.indexOf('[') >= 0 || section.indexOf(']') >= 0
                || section.indexOf(';') >= 0 || section.indexOf('#') >= 0) {
            throw error(line, column, "INI section 名称包含非法字符");
        }
        for (String part : section.split("\\.", -1)) {
            if (part.trim().isEmpty()) {
                throw error(line, column, "INI section 名称不能包含空路径段");
            }
        }
    }

    private static void validateOutputKey(String key, String format) {
        if (key == null || key.trim().isEmpty() || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0
                || !key.equals(key.trim()) || key.indexOf('=') >= 0 || key.indexOf(':') >= 0
                || key.indexOf('[') >= 0 || key.indexOf(']') >= 0
                // Keys can become section names when nested. A comment marker
                // would therefore either truncate the line or invalidate the section.
                || key.indexOf('#') >= 0 || key.indexOf(';') >= 0) {
            throw new FormatConversionException(format + " 键包含非法字符", 1, 1);
        }
    }

    private static void validateSectionKey(String key, String format) {
        if (key.indexOf('.') >= 0) {
            throw new FormatConversionException(format + " section 键不能包含 '.'", 1, 1);
        }
    }

    private static int firstNonWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return 0;
    }

    private static String trimTrailingNewline(StringBuilder value) {
        while (value.length() > 0 && (value.charAt(value.length() - 1) == '\n' || value.charAt(value.length() - 1) == '\r')) {
            value.setLength(value.length() - 1);
        }
        return value.toString();
    }

    private static FormatConversionException error(int line, int column, String message) {
        return new FormatConversionException("INI 格式错误（第" + (line + 1) + "行第" + Math.max(1, column) + "列）: " + message,
                line + 1, Math.max(1, column));
    }
}
