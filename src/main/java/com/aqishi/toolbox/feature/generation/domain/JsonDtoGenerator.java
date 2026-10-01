package com.aqishi.toolbox.feature.generation.domain;

import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.*;

import java.util.*;

/**
 * Infers DTOs from all array elements, with deterministic names and conservative mixed-type
 * fallback.
 */
public final class JsonDtoGenerator {
    public enum Style {
        BEAN,
        LOMBOK,
        RECORD
    }

    public record Options(
            String packageName,
            String className,
            Style style,
            boolean jackson,
            Map<String, String> rename) {}

    private static final Set<String> RESERVED =
            Set.of(
                    "abstract",
                    "assert",
                    "boolean",
                    "break",
                    "byte",
                    "case",
                    "catch",
                    "char",
                    "class",
                    "const",
                    "continue",
                    "default",
                    "do",
                    "double",
                    "else",
                    "enum",
                    "extends",
                    "final",
                    "finally",
                    "float",
                    "for",
                    "goto",
                    "if",
                    "implements",
                    "import",
                    "instanceof",
                    "int",
                    "interface",
                    "long",
                    "native",
                    "new",
                    "package",
                    "private",
                    "protected",
                    "public",
                    "return",
                    "short",
                    "static",
                    "strictfp",
                    "super",
                    "switch",
                    "synchronized",
                    "this",
                    "throw",
                    "throws",
                    "transient",
                    "try",
                    "void",
                    "volatile",
                    "while",
                    "true",
                    "false",
                    "null",
                    "record",
                    "sealed",
                    "permits",
                    "yield",
                    "var",
                    "_");

    private static final class Shape {
        String kind;
        Map<String, Shape> fields = new LinkedHashMap<>();
        Shape item;

        Shape(String kind) {
            this.kind = kind;
        }
    }

    private record Definition(String name, Shape shape) {}

    public String generate(String input, Options options) throws Exception {
        if (input.length() > 2_000_000)
            throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
        validateIdentifier(options.className());
        if (Set.of("java", "com", "lombok").contains(options.className()))
            throw new IllegalArgumentException(I18n.get("dto.identifier", options.className()));
        if (!options.packageName().isBlank())
            for (String part : options.packageName().split("\\.", -1)) validateIdentifier(part);
        for (String value : options.rename().values()) validateIdentifier(value);
        JsonNode node =
                new ObjectMapper()
                        .enable(
                                com.fasterxml.jackson.core.JsonParser.Feature
                                        .STRICT_DUPLICATE_DETECTION)
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .readTree(input);
        Shape root = infer(node, 0);
        if (root.kind.equals("array")) root = root.item;
        if (root == null || !root.kind.equals("object"))
            throw new IllegalArgumentException(I18n.get("dto.objectRequired"));
        List<Definition> definitions = new ArrayList<>();
        definitions.add(new Definition(options.className(), root));
        Set<String> classNames = new HashSet<>();
        classNames.add(options.className());
        StringBuilder source =
                new StringBuilder(
                        "// Generated from sample data; Object denotes an unknown or mixed"
                                + " type.\n");
        if (!options.packageName().isBlank())
            source.append("package ").append(options.packageName()).append(";\n\n");
        for (int index = 0; index < definitions.size(); index++) {
            if (definitions.size() > 200)
                throw new IllegalArgumentException(I18n.get("devtools.resultLimit"));
            Definition def = definitions.get(index);
            boolean nested = index > 0;
            String indent = nested ? "    " : "";
            List<String> names = new ArrayList<>(),
                    types = new ArrayList<>(),
                    jsonNames = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (var entry : def.shape().fields.entrySet()) {
                String name =
                        options.rename().getOrDefault(entry.getKey(), fieldName(entry.getKey()));
                String original = name;
                int suffix = 2;
                if (upper(name).equals("Class")) name = original = "classValue";
                if (options.style() == Style.RECORD
                        && Set.of(
                                        "clone",
                                        "finalize",
                                        "getClass",
                                        "hashCode",
                                        "notify",
                                        "notifyAll",
                                        "toString",
                                        "wait")
                                .contains(name)) name = original = name + "Value";
                while (!used.add(upper(name))) name = original + suffix++;
                names.add(name);
                jsonNames.add(entry.getKey());
                types.add(javaType(entry.getValue(), upper(name), definitions, classNames));
            }
            if (options.style() == Style.LOMBOK) source.append(indent).append("@lombok.Data\n");
            source.append(indent)
                    .append("public ")
                    .append(nested && options.style() != Style.RECORD ? "static " : "");
            if (options.style() == Style.RECORD) {
                source.append("record ").append(def.name()).append("(\n");
                for (int i = 0; i < names.size(); i++)
                    source.append(indent)
                            .append("    ")
                            .append(annotation(jsonNames.get(i), options))
                            .append(types.get(i))
                            .append(' ')
                            .append(names.get(i))
                            .append(i + 1 < names.size() ? ",\n" : "\n");
                source.append(indent).append(") {\n");
            } else {
                source.append("class ").append(def.name()).append(" {\n");
                for (int i = 0; i < names.size(); i++)
                    source.append(indent)
                            .append("    ")
                            .append(annotation(jsonNames.get(i), options))
                            .append("private ")
                            .append(types.get(i))
                            .append(' ')
                            .append(names.get(i))
                            .append(";\n");
                if (options.style() == Style.BEAN)
                    for (int i = 0; i < names.size(); i++) {
                        String name = names.get(i), type = types.get(i), cap = upper(name);
                        source.append('\n')
                                .append(indent)
                                .append("    public ")
                                .append(type)
                                .append(" get")
                                .append(cap)
                                .append("() { return ")
                                .append(name)
                                .append("; }\n");
                        source.append(indent)
                                .append("    public void set")
                                .append(cap)
                                .append('(')
                                .append(type)
                                .append(' ')
                                .append(name)
                                .append(") { this.")
                                .append(name)
                                .append(" = ")
                                .append(name)
                                .append("; }\n");
                    }
            }
            if (index > 0) source.append(indent).append("}\n");
            if (source.length() > 4_000_000)
                throw new IllegalArgumentException(I18n.get("devtools.resultLimit"));
        }
        return source.append("}\n").toString();
    }

    private static String annotation(String name, Options options) throws Exception {
        return options.jackson()
                ? "@com.fasterxml.jackson.annotation.JsonProperty("
                        + new ObjectMapper().writeValueAsString(name)
                        + ") "
                : "";
    }

    private static String javaType(
            Shape shape, String hint, List<Definition> defs, Set<String> names) {
        if (shape == null) return "java.lang.Object";
        return switch (shape.kind) {
            case "object" -> {
                String name = hint;
                int i = 2;
                while (!names.add(name)) name = hint + i++;
                defs.add(new Definition(name, shape));
                yield name;
            }
            case "array" ->
                    "java.util.List<" + javaType(shape.item, hint + "Item", defs, names) + ">";
            case "integer" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "bigint" -> "java.math.BigInteger";
            case "decimal" -> "java.math.BigDecimal";
            case "boolean" -> "java.lang.Boolean";
            case "string" -> "java.lang.String";
            default -> "java.lang.Object";
        };
    }

    private static Shape infer(JsonNode node, int depth) {
        if (depth > 64) throw new IllegalArgumentException(I18n.get("devtools.resultLimit"));
        if (Thread.currentThread().isInterrupted())
            throw new java.util.concurrent.CancellationException();
        if (node == null || node.isNull()) return new Shape("null");
        if (node.isObject()) {
            if (node.size() > 1000)
                throw new IllegalArgumentException(I18n.get("devtools.resultLimit"));
            Shape shape = new Shape("object");
            node.fields()
                    .forEachRemaining(
                            e -> shape.fields.put(e.getKey(), infer(e.getValue(), depth + 1)));
            return shape;
        }
        if (node.isArray()) {
            Shape shape = new Shape("array");
            for (JsonNode item : node) shape.item = merge(shape.item, infer(item, depth + 1));
            return shape;
        }
        if (node.isIntegralNumber())
            return new Shape(
                    node.canConvertToInt()
                            ? "integer"
                            : node.canConvertToLong() ? "long" : "bigint");
        if (node.isNumber()) return new Shape("decimal");
        return new Shape(node.isBoolean() ? "boolean" : "string");
    }

    private static Shape merge(Shape a, Shape b) {
        if (a == null || a.kind.equals("null")) return b;
        if (b == null || b.kind.equals("null")) return a;
        if (a.kind.equals(b.kind)) {
            if (a.kind.equals("object"))
                b.fields.forEach((k, v) -> a.fields.merge(k, v, JsonDtoGenerator::merge));
            if (a.kind.equals("array")) a.item = merge(a.item, b.item);
            return a;
        }
        List<String> numeric = List.of("integer", "long", "bigint", "decimal");
        int x = numeric.indexOf(a.kind), y = numeric.indexOf(b.kind);
        return new Shape(x >= 0 && y >= 0 ? numeric.get(Math.max(x, y)) : "mixed");
    }

    private static String fieldName(String input) {
        String[] words = input.replaceAll("[^A-Za-z0-9_$]", " ").trim().split("\\s+");
        StringBuilder name = new StringBuilder();
        for (String word : words)
            if (!word.isEmpty()) name.append(name.length() == 0 ? word : upper(word));
        if (name.length() == 0) name.append("field");
        if (Character.isDigit(name.charAt(0))) name.insert(0, '_');
        String result = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        if (RESERVED.contains(result) || result.equals("getClass")) result += "Value";
        return result;
    }

    private static String upper(String name) {
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    public static void validateIdentifier(String name) {
        if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*") || RESERVED.contains(name))
            throw new IllegalArgumentException(I18n.get("dto.identifier", name));
    }
}
