package com.aqishi.toolbox.feature.network.infra;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts named UTF-8 text fields from a multipart/form-data payload. */
final class MultipartFormDataParser {
    private static final Pattern PARAMETER = Pattern.compile(
            "(?:^|;)\\s*([A-Za-z0-9_-]+)\\s*=\\s*(?:\\\"((?:\\\\.|[^\\\"])*)\\\"|([^;\\s]*))");

    Map<String, List<String>> parse(String body, String contentType) {
        Map<String, List<String>> values =
                new LinkedHashMap<String, List<String>>();
        String boundary = parameter(contentType, "boundary");
        if (body == null || boundary == null || boundary.length() == 0) {
            return values;
        }

        String delimiter = "--" + boundary;
        String[] parts = body.split(Pattern.quote(delimiter), -1);
        for (int index = 1; index < parts.length; index++) {
            String part = removeLeadingLineBreak(parts[index]);
            if (part.startsWith("--")) {
                break;
            }
            addTextField(part, values);
        }
        return values;
    }

    private static void addTextField(String part, Map<String, List<String>> values) {
        int headerEnd = part.indexOf("\r\n\r\n");
        int separatorLength = 4;
        if (headerEnd < 0) {
            headerEnd = part.indexOf("\n\n");
            separatorLength = 2;
        }
        if (headerEnd < 0) {
            return;
        }

        Map<String, String> headers = parseHeaders(part.substring(0, headerEnd));
        String disposition = headers.get("content-disposition");
        String name = parameter(disposition, "name");
        if (name == null || name.length() == 0
                || parameter(disposition, "filename") != null) {
            return;
        }

        String value = removeTrailingLineBreak(part.substring(headerEnd + separatorLength));
        List<String> repeated = values.get(name);
        if (repeated == null) {
            repeated = new ArrayList<String>();
            values.put(name, repeated);
        }
        repeated.add(value);
    }

    private static Map<String, String> parseHeaders(String headersText) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String[] lines = headersText.split("\\r?\\n");
        for (String line : lines) {
            int separator = line.indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = line.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(separator + 1).trim();
            if (!headers.containsKey(name)) {
                headers.put(name, value);
            }
        }
        return headers;
    }

    private static String parameter(String value, String name) {
        if (value == null) {
            return null;
        }
        Matcher matcher = PARAMETER.matcher(value);
        while (matcher.find()) {
            if (name.equalsIgnoreCase(matcher.group(1))) {
                String quoted = matcher.group(2);
                return quoted == null ? matcher.group(3)
                        : quoted.replace("\\\\\"", "\"");
            }
        }
        return null;
    }

    private static String removeLeadingLineBreak(String value) {
        if (value.startsWith("\r\n")) {
            return value.substring(2);
        }
        return value.startsWith("\n") ? value.substring(1) : value;
    }

    private static String removeTrailingLineBreak(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        return value.endsWith("\n") ? value.substring(0, value.length() - 1) : value;
    }
}
