package com.aqishi.toolbox.feature.network.domain.callbackmock;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Normalized immutable representation of an incoming callback request.
 * Header map keys are stored in lower case; all other map keys retain their
 * original spelling.
 */
public final class MockRequest {
    private final String method;
    private final String path;
    private final String rawQuery;
    private final Map<String, List<String>> query;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> form;
    private final JsonNode json;
    private final String rawBody;
    private final boolean bodyTruncated;
    private final String clientAddress;

    private MockRequest(Builder builder) {
        this.method = builder.method == null ? "" : builder.method;
        this.path = builder.path == null ? "" : builder.path;
        this.rawQuery = builder.rawQuery;
        this.query = immutableCopy(builder.query, false);
        this.headers = immutableCopy(builder.headers, true);
        this.form = immutableCopy(builder.form, false);
        this.json = builder.json == null ? null : builder.json.deepCopy();
        this.rawBody = builder.rawBody;
        this.bodyTruncated = builder.bodyTruncated;
        this.clientAddress = builder.clientAddress;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public Map<String, List<String>> getQuery() {
        return query;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public Map<String, List<String>> getForm() {
        return form;
    }

    public JsonNode getJson() {
        return json == null ? null : json.deepCopy();
    }

    public JsonNode getJsonRoot() {
        return json == null ? null : json.deepCopy();
    }

    public String getRawBody() {
        return rawBody;
    }

    public boolean isBodyTruncated() {
        return bodyTruncated;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    /**
     * Returns all values for a non-JSON source. Header names are looked up
     * case-insensitively. For JSON, a scalar/object/array at the path is
     * represented as one compact textual value.
     */
    public List<String> values(MatchSource source, String field) {
        if (source == null) {
            return Collections.emptyList();
        }
        switch (source) {
            case QUERY:
                return valuesFrom(query, field, false);
            case HEADER:
                return valuesFrom(headers, field, true);
            case FORM:
                return valuesFrom(form, field, false);
            case JSON:
                JsonNode node = jsonValue(field);
                if (node == null || node.isMissingNode()) {
                    return Collections.emptyList();
                }
                return Collections.singletonList(jsonText(node));
            default:
                return Collections.emptyList();
        }
    }

    /**
     * Looks up dotted object segments and array-index segments such as
     * {@code order.items[0].sku}. Invalid or absent paths return null.
     */
    public JsonNode jsonValue(String fieldPath) {
        if (json == null || fieldPath == null || fieldPath.length() == 0) {
            return null;
        }
        int length = fieldPath.length();
        int position = 0;
        JsonNode current = json;
        boolean needSegment = true;
        while (position < length) {
            if (fieldPath.charAt(position) == '.') {
                return null;
            }

            if (fieldPath.charAt(position) != '[') {
                int start = position;
                while (position < length
                        && fieldPath.charAt(position) != '.'
                        && fieldPath.charAt(position) != '[') {
                    position++;
                }
                if (start == position || current == null || !current.isObject()) {
                    return null;
                }
                current = current.get(fieldPath.substring(start, position));
                needSegment = false;
            }

            while (position < length && fieldPath.charAt(position) == '[') {
                int close = fieldPath.indexOf(']', position + 1);
                if (close < 0 || close == position + 1 || current == null
                        || !current.isArray()) {
                    return null;
                }
                String indexText = fieldPath.substring(position + 1, close);
                int index;
                try {
                    index = Integer.parseInt(indexText);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (index < 0 || index >= current.size()) {
                    return null;
                }
                current = current.get(index);
                position = close + 1;
                needSegment = false;
            }

            if (position < length) {
                if (fieldPath.charAt(position) != '.' || needSegment) {
                    return null;
                }
                position++;
                if (position >= length || fieldPath.charAt(position) == '.') {
                    return null;
                }
                needSegment = true;
            }
        }
        return current;
    }

    private static String jsonText(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        return node.toString();
    }

    private static List<String> valuesFrom(Map<String, List<String>> values,
                                           String field, boolean header) {
        if (field == null) {
            return Collections.emptyList();
        }
        String key = header ? field.toLowerCase(Locale.ROOT) : field;
        List<String> result = values.get(key);
        return result == null ? Collections.<String>emptyList() : result;
    }

    private static Map<String, List<String>> immutableCopy(
            Map<String, List<String>> source, boolean lowerCaseKeys) {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            String key = entry.getKey();
            if (lowerCaseKeys && key != null) {
                key = key.toLowerCase(Locale.ROOT);
            }
            List<String> oldValues = entry.getValue();
            List<String> copied = oldValues == null
                    ? new ArrayList<String>()
                    : new ArrayList<String>(oldValues);
            List<String> existing = result.get(key);
            if (existing == null) {
                result.put(key, Collections.unmodifiableList(copied));
            } else {
                List<String> combined = new ArrayList<String>(existing);
                combined.addAll(copied);
                result.put(key, Collections.unmodifiableList(combined));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MockRequest)) {
            return false;
        }
        MockRequest that = (MockRequest) other;
        return bodyTruncated == that.bodyTruncated
                && Objects.equals(method, that.method)
                && Objects.equals(path, that.path)
                && Objects.equals(rawQuery, that.rawQuery)
                && Objects.equals(query, that.query)
                && Objects.equals(headers, that.headers)
                && Objects.equals(form, that.form)
                && Objects.equals(json, that.json)
                && Objects.equals(rawBody, that.rawBody)
                && Objects.equals(clientAddress, that.clientAddress);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, path, rawQuery, query, headers, form,
                json, rawBody, bodyTruncated, clientAddress);
    }

    public static final class Builder {
        private String method = "";
        private String path = "/";
        private String rawQuery;
        private final Map<String, List<String>> query =
                new LinkedHashMap<String, List<String>>();
        private final Map<String, List<String>> headers =
                new LinkedHashMap<String, List<String>>();
        private final Map<String, List<String>> form =
                new LinkedHashMap<String, List<String>>();
        private JsonNode json;
        private String rawBody;
        private boolean bodyTruncated;
        private String clientAddress;

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder rawQuery(String rawQuery) {
            this.rawQuery = rawQuery;
            return this;
        }

        public Builder query(String key, String value) {
            add(query, key, value);
            return this;
        }

        public Builder header(String key, String value) {
            add(headers, key == null ? null : key.toLowerCase(Locale.ROOT), value);
            return this;
        }

        public Builder form(String key, String value) {
            add(form, key, value);
            return this;
        }

        public Builder json(JsonNode root) {
            this.json = root;
            return this;
        }

        public Builder body(String rawBody, boolean truncated) {
            this.rawBody = rawBody;
            this.bodyTruncated = truncated;
            return this;
        }

        public Builder clientAddress(String clientAddress) {
            this.clientAddress = clientAddress;
            return this;
        }

        public MockRequest build() {
            return new MockRequest(this);
        }

        private static void add(Map<String, List<String>> target,
                                String key, String value) {
            List<String> values = target.get(key);
            if (values == null) {
                values = new ArrayList<String>();
                target.put(key, values);
            }
            values.add(value);
        }
    }
}
