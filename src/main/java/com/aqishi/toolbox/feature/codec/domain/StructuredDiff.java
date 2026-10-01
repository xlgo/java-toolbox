package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.*;

/** Structural comparison, with exact ignored paths and optional object-array identity matching. */
public final class StructuredDiff {
    public record Change(String path, String kind, String before, String after) {}

    private final ObjectMapper json =
            new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .STRICT_DUPLICATE_DETECTION);
    private final ObjectMapper yaml =
            new ObjectMapper(new YAMLFactory())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .enable(
                            com.fasterxml.jackson.core.JsonParser.Feature
                                    .STRICT_DUPLICATE_DETECTION);

    public List<Change> compare(
            String left, String right, boolean isYaml, Set<String> ignored, String arrayKey)
            throws Exception {
        if (left.length() > 2_000_000 || right.length() > 2_000_000)
            throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
        ObjectMapper mapper = isYaml ? yaml : json;
        JsonNode a = mapper.readTree(left), b = mapper.readTree(right);
        if (a == null || b == null)
            throw new IllegalArgumentException(I18n.get("devtools.inputRequired"));
        List<Change> result = new ArrayList<>();
        walk(a, b, "$", ignored, arrayKey.trim(), 0, result);
        return List.copyOf(result);
    }

    private void walk(
            JsonNode a,
            JsonNode b,
            String path,
            Set<String> ignored,
            String key,
            int depth,
            List<Change> out) {
        if (ignored.contains(path)) return;
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        if (depth > 128 || out.size() >= 10000)
            throw new IllegalArgumentException(I18n.get("devtools.resultLimit"));
        if (a == null || b == null) {
            out.add(new Change(path, a == null ? "added" : "removed", value(a), value(b)));
            return;
        }
        if (a.isNumber() && b.isNumber() && a.decimalValue().compareTo(b.decimalValue()) == 0)
            return;
        if (a.isObject() && b.isObject()) {
            Set<String> names = new TreeSet<>();
            a.fieldNames().forEachRemaining(names::add);
            b.fieldNames().forEachRemaining(names::add);
            for (String name : names)
                walk(a.get(name), b.get(name), child(path, name), ignored, key, depth + 1, out);
        } else if (a.isArray() && b.isArray()) {
            if (!key.isBlank() && objectArray(a) && objectArray(b)) {
                Map<String, JsonNode> aa = index(a, key, path), bb = index(b, key, path);
                Set<String> ids = new LinkedHashSet<>(aa.keySet());
                ids.addAll(bb.keySet());
                for (String id : ids)
                    walk(
                            aa.get(id),
                            bb.get(id),
                            path + "[" + key + "=" + id + "]",
                            ignored,
                            key,
                            depth + 1,
                            out);
            } else
                for (int i = 0; i < Math.max(a.size(), b.size()); i++)
                    walk(a.get(i), b.get(i), path + "[" + i + "]", ignored, key, depth + 1, out);
        } else if (!a.equals(b)) out.add(new Change(path, "changed", value(a), value(b)));
    }

    private static boolean objectArray(JsonNode array) {
        for (JsonNode value : array) if (!value.isObject()) return false;
        return true;
    }

    private static Map<String, JsonNode> index(JsonNode array, String key, String path) {
        Map<String, JsonNode> result = new LinkedHashMap<>();
        for (JsonNode value : array) {
            JsonNode id = value.get(key);
            if (id == null || id.isNull() || !id.isValueNode())
                throw new IllegalArgumentException(I18n.get("structured.keyMissing", path, key));
            if (result.put(id.toString(), value) != null)
                throw new IllegalArgumentException(I18n.get("structured.keyDuplicate", path, key));
        }
        return result;
    }

    private static String child(String path, String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*")
                ? path + "." + name
                : path + "['" + name.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private static String value(JsonNode node) {
        return node == null ? "<missing>" : node.toString();
    }
}
