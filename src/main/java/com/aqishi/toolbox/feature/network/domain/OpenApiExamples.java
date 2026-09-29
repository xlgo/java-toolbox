package com.aqishi.toolbox.feature.network.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Builds JSON examples with bounded recursion and correctly escaped property names and values. */
final class OpenApiExamples {
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private final JsonNode root;
    private final Set<String> references = new HashSet<>();
    private int remaining = 1000;

    private OpenApiExamples(JsonNode root) {
        this.root = root;
    }

    static String generate(JsonNode schema, JsonNode root) {
        return new OpenApiExamples(root).sample(schema, 0).toPrettyString();
    }

    /** Only local references are resolved; importing a document never fetches referenced URLs. */
    static JsonNode resolve(String ref, JsonNode root) {
        if (ref == null || !ref.startsWith("#/")) return NullNode.instance;
        try {
            return root.at(ref.substring(1));
        } catch (IllegalArgumentException invalidPointer) {
            return NullNode.instance;
        }
    }

    static JsonNode dereference(JsonNode node, JsonNode root) {
        Set<String> seen = new HashSet<>();
        while (node != null && node.has("$ref")) {
            String ref = node.path("$ref").asText();
            if (seen.size() >= 32 || !seen.add(ref)) return NullNode.instance;
            node = resolve(ref, root);
        }
        return node == null ? NullNode.instance : node;
    }

    private JsonNode sample(JsonNode node, int depth) {
        if (--remaining < 0 || depth > 32 || node == null || node.isMissingNode() || node.isNull()) {
            return NullNode.instance;
        }
        if (node.has("example")) return node.get("example");
        if (node.has("default")) return node.get("default");
        if (node.path("enum").isArray() && !node.path("enum").isEmpty()) return node.path("enum").get(0);
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText();
            if (!references.add(ref)) return NullNode.instance;
            try {
                return sample(resolve(ref, root), depth + 1);
            } finally {
                references.remove(ref);
            }
        }
        String type = node.path("type").asText(node.has("properties") ? "object" : "string");
        switch (type.toLowerCase(Locale.ROOT)) {
            case "object":
                var object = JSON.objectNode();
                var fields = node.path("properties").fields();
                while (fields.hasNext() && remaining > 0) {
                    var field = fields.next();
                    object.set(field.getKey(), sample(field.getValue(), depth + 1));
                }
                return object;
            case "array": return JSON.arrayNode().add(sample(node.path("items"), depth + 1));
            case "integer": case "int": return JSON.numberNode(1);
            case "number": case "float": case "double": return JSON.numberNode(10.5);
            case "boolean": return JSON.booleanNode(true);
            default: return JSON.textNode("sample");
        }
    }
}
