package com.aqishi.toolbox.feature.codec.domain;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * Restricted TOML writer for the JSON-compatible model returned by Tomlj.
 * Nested maps are emitted as tables; maps inside arrays are emitted as inline
 * tables. This keeps output deterministic without pretending that TOML has a
 * null value or a portable representation for arbitrary Java objects. TOML
 * date/time input is normalized to strings by {@link FormatConversionService}
 * before it reaches this writer.
 */
final class TomlWriter {

    private static final Pattern BARE_KEY = Pattern.compile("[A-Za-z0-9_-]+");

    private TomlWriter() {
    }

    static String write(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new FormatConversionException("TOML 目标值必须是对象", 1, 1);
        }
        StringBuilder output = new StringBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) value;
        appendMap(output, root, null);
        while (output.length() > 0 && output.charAt(output.length() - 1) == '\n') {
            output.setLength(output.length() - 1);
        }
        return output.toString();
    }

    private static void appendMap(StringBuilder output, Map<String, Object> map, String path) {
        if (path != null) {
            separate(output);
            output.append('[').append(path).append("]\n");
        }

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            requireKey(entry.getKey());
            if (!(entry.getValue() instanceof Map<?, ?>)) {
                output.append(formatKey(entry.getKey())).append(" = ")
                        .append(formatValue(entry.getValue())).append('\n');
            }
        }
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> child) {
                String childPath = path == null ? formatKey(entry.getKey())
                        : path + "." + formatKey(entry.getKey());
                @SuppressWarnings("unchecked")
                Map<String, Object> childMap = (Map<String, Object>) child;
                appendMap(output, childMap, childPath);
            }
        }
    }

    private static void separate(StringBuilder output) {
        if (output.length() > 0 && output.charAt(output.length() - 1) != '\n') {
            output.append('\n');
        }
        if (output.length() > 0 && output.charAt(output.length() - 1) == '\n'
                && (output.length() < 2 || output.charAt(output.length() - 2) != '\n')) {
            output.append('\n');
        }
    }

    private static String formatValue(Object value) {
        if (value == null) {
            throw new FormatConversionException("TOML 不支持 null 值", 1, 1);
        }
        if (value instanceof String || value instanceof Character) {
            return quote(String.valueOf(value));
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof java.math.BigInteger || value instanceof java.math.BigDecimal) {
            if (value instanceof Double doubleValue) {
                if (doubleValue.isNaN()) {
                    return "nan";
                }
                if (doubleValue == Double.POSITIVE_INFINITY) {
                    return "inf";
                }
                if (doubleValue == Double.NEGATIVE_INFINITY) {
                    return "-inf";
                }
            }
            if (value instanceof Float floatValue) {
                if (floatValue.isNaN()) {
                    return "nan";
                }
                if (floatValue == Float.POSITIVE_INFINITY) {
                    return "inf";
                }
                if (floatValue == Float.NEGATIVE_INFINITY) {
                    return "-inf";
                }
            }
            if (value instanceof BigInteger integer
                    && (integer.compareTo(BigInteger.valueOf(Long.MIN_VALUE)) < 0
                    || integer.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0)) {
                throw new FormatConversionException("TOML 整数超出 64 位范围", 1, 1);
            }
            return String.valueOf(value);
        }
        if (value instanceof Collection<?> collection) {
            validateArray(collection);
            StringBuilder result = new StringBuilder("[");
            boolean first = true;
            for (Object item : collection) {
                if (!first) {
                    result.append(", ");
                }
                result.append(formatValue(item));
                first = false;
            }
            return result.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder result = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                requireKey(key);
                if (!first) {
                    result.append(", ");
                }
                result.append(formatKey(key)).append(" = ").append(formatValue(entry.getValue()));
                first = false;
            }
            return result.append('}').toString();
        }
        throw new FormatConversionException("TOML 不支持值类型: " + value.getClass().getSimpleName(), 1, 1);
    }

    private static void validateArray(Collection<?> values) {
        String expectedType = null;
        for (Object value : values) {
            String actualType = valueType(value);
            if (expectedType == null) {
                expectedType = actualType;
            } else if (!expectedType.equals(actualType)) {
                throw new FormatConversionException("TOML 数组元素类型必须一致", 1, 1);
            }
        }
    }

    private static String valueType(Object value) {
        if (value == null) {
            throw new FormatConversionException("TOML 不支持 null 值", 1, 1);
        }
        if (value instanceof String || value instanceof Character) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof java.math.BigInteger) {
            return "integer";
        }
        if (value instanceof Float || value instanceof Double || value instanceof java.math.BigDecimal) {
            return "float";
        }
        if (value instanceof Collection<?> collection) {
            validateArray(collection);
            return "array";
        }
        if (value instanceof Map<?, ?>) {
            return "table";
        }
        throw new FormatConversionException("TOML 不支持值类型: " + value.getClass().getSimpleName(), 1, 1);
    }

    private static void requireKey(String key) {
        if (key == null || key.isEmpty() || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            throw new FormatConversionException("TOML 键不能为空或包含换行", 1, 1);
        }
    }

    private static String formatKey(String key) {
        return BARE_KEY.matcher(key).matches() ? key : quote(key);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\b' -> result.append("\\b");
                case '\t' -> result.append("\\t");
                case '\n' -> result.append("\\n");
                case '\f' -> result.append("\\f");
                case '\r' -> result.append("\\r");
                default -> {
                    if (c < 0x20 || c == 0x7f) {
                        result.append(String.format("\\u%04X", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
