package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.util.I18n;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Reconstructs a single unambiguous Preparing/Parameters pair; it never executes SQL. */
public final class MyBatisLogSql {
    private static final Pattern TYPED =
            Pattern.compile("\\(([A-Za-z][A-Za-z0-9.$\\[\\]]*)\\)(?=,\\s|$)");

    public String restore(String log, QueryResultExporter.Dialect dialect) {
        if (log.length() > 2_000_000) throw error("devtools.inputLimit");
        String sql = null, params = null;
        for (String line : log.split("\\R")) {
            int prepare = line.indexOf("Preparing:");
            int parameters = line.indexOf("Parameters:");
            if (prepare >= 0) {
                if (sql != null || params != null) throw error("mybatis.single");
                sql = line.substring(prepare + 10).trim();
            }
            if (parameters >= 0) {
                if (params != null || sql == null) throw error("mybatis.single");
                params = line.substring(parameters + 11).trim();
            }
        }
        if (sql == null || params == null) throw error("mybatis.single");
        List<Object> values = parseParameters(params);
        StringBuilder result = new StringBuilder();
        int p = 0;
        for (int i = 0; i < sql.length(); ) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`' || c == '[') {
                char end = c == '[' ? ']' : c;
                int start = i++;
                boolean closed = false;
                while (i < sql.length()) {
                    char v = sql.charAt(i++);
                    if (v == '\\'
                            && i < sql.length()
                            && (dialect == QueryResultExporter.Dialect.MYSQL
                                    || dialect == QueryResultExporter.Dialect.POSTGRESQL)) {
                        i++;
                        continue;
                    }
                    if (v == end) {
                        if (i < sql.length() && sql.charAt(i) == end) {
                            i++;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                }
                if (!closed) throw error("mybatis.quote");
                result.append(sql, start, i);
                continue;
            }
            if (i + 1 < sql.length() && sql.startsWith("--", i)) {
                result.append(sql.substring(i));
                break;
            }
            if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) throw error("mybatis.quote");
                result.append(sql, i, end + 2);
                i = end + 2;
                continue;
            }
            if (c == '$') {
                Matcher tag =
                        Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$")
                                .matcher(sql.substring(i));
                if (tag.lookingAt()) {
                    String delimiter = tag.group();
                    int end = sql.indexOf(delimiter, i + delimiter.length());
                    if (end < 0) throw error("mybatis.quote");
                    result.append(sql, i, end + delimiter.length());
                    i = end + delimiter.length();
                    continue;
                }
            }
            if (c == '?') {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == '?') {
                    result.append("??");
                    i += 2;
                    continue;
                }
                if (p >= values.size()) throw error("mybatis.count");
                result.append(QueryResultExporter.literal(values.get(p++), dialect));
            } else result.append(c);
            i++;
        }
        if (p != values.size()) throw error("mybatis.count");
        return result.toString();
    }

    static List<Object> parseParameters(String text) {
        List<Object> values = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            if (text.startsWith("null", start)
                    && (start + 4 == text.length() || text.startsWith(", ", start + 4))) {
                values.add(null);
                start += 4;
            } else {
                Matcher matcher = TYPED.matcher(text);
                if (!matcher.find(start)) throw error("mybatis.parameter");
                String raw = text.substring(start, matcher.start());
                String type = matcher.group(1);
                start = matcher.end();
                try {
                    values.add(convert(raw, type));
                } catch (RuntimeException invalid) {
                    throw new IllegalArgumentException(
                            I18n.get("mybatis.parameterType", values.size() + 1, type));
                }
            }
            if (start < text.length()) {
                if (!text.startsWith(", ", start)) throw error("mybatis.parameter");
                start += 2;
                if (start == text.length()) throw error("mybatis.parameter");
            }
        }
        return values;
    }

    private static Object convert(String raw, String type) {
        return switch (type) {
            case "String", "Character" -> raw;
            case "Byte", "Short", "Integer", "Long", "BigInteger" -> new BigInteger(raw.trim());
            case "BigDecimal", "Float", "Double" -> new BigDecimal(raw.trim());
            case "Boolean" -> {
                if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false"))
                    throw error("mybatis.parameter");
                yield Boolean.valueOf(raw);
            }
            case "Date", "LocalDate" -> LocalDate.parse(raw.trim());
            case "Time", "LocalTime" -> LocalTime.parse(raw.trim());
            case "Timestamp", "LocalDateTime" -> LocalDateTime.parse(raw.trim().replace(' ', 'T'));
            default -> throw error("mybatis.parameter");
        };
    }

    private static IllegalArgumentException error(String key) {
        return new IllegalArgumentException(I18n.get(key));
    }
}
