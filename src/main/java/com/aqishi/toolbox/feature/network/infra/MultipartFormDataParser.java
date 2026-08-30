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
        int delimiterStart = findDelimiter(body, delimiter, 0);
        while (delimiterStart >= 0) {
            int contentStart = delimiterStart + delimiter.length();
            if (startsWith(body, contentStart, "--")) {
                break;
            }

            int lineBreakLength = lineBreakLength(body, contentStart);
            if (lineBreakLength == 0) {
                break;
            }
            contentStart += lineBreakLength;

            int nextDelimiter = findDelimiter(body, delimiter, contentStart);
            if (nextDelimiter < 0) {
                break;
            }
            addTextField(body.substring(contentStart, nextDelimiter), values);
            delimiterStart = nextDelimiter;
        }
        return values;
    }

    /**
     * Finds a delimiter only when it is a complete multipart delimiter line.
     * A boundary token embedded in a field value is therefore left untouched.
     */
    private static int findDelimiter(String body, String delimiter, int fromIndex) {
        int candidate = body.indexOf(delimiter, fromIndex);
        while (candidate >= 0) {
            boolean atLineStart = candidate == 0 || body.charAt(candidate - 1) == '\n';
            if (atLineStart) {
                int after = candidate + delimiter.length();
                if (startsWith(body, after, "--")) {
                    int closingEnd = after + 2;
                    if (closingEnd == body.length()
                            || lineBreakLength(body, closingEnd) > 0) {
                        return candidate;
                    }
                } else if (lineBreakLength(body, after) > 0) {
                    return candidate;
                }
            }
            candidate = body.indexOf(delimiter, candidate + 1);
        }
        return -1;
    }

    private static boolean startsWith(String value, int offset, String prefix) {
        return offset >= 0 && offset + prefix.length() <= value.length()
                && value.regionMatches(offset, prefix, 0, prefix.length());
    }

    private static int lineBreakLength(String value, int offset) {
        if (offset < 0 || offset >= value.length()) {
            return 0;
        }
        if (startsWith(value, offset, "\r\n")) {
            return 2;
        }
        return value.charAt(offset) == '\n' ? 1 : 0;
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
