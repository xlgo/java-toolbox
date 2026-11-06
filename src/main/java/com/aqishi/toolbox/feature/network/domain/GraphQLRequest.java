package com.aqishi.toolbox.feature.network.domain;

import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * GraphQL 请求模型、载荷序列化与 Schema 内省（Introspection）解析。
 */
public final class GraphQLRequest {

    /** 标准 GraphQL 内省查询，用于检索服务端支持的 Query、Mutation 及类型定义。 */
    public static final String INTROSPECTION_QUERY =
            """
            query IntrospectionQuery {
              __schema {
                queryType { name }
                mutationType { name }
                subscriptionType { name }
                types {
                  kind
                  name
                  description
                  fields(includeDeprecated: true) {
                    name
                    description
                    args {
                      name
                      description
                      type {
                        kind
                        name
                        ofType { kind name }
                      }
                    }
                    type {
                      kind
                      name
                      ofType { kind name }
                    }
                  }
                }
              }
            }
            """;

    private GraphQLRequest() {}

    /** 将 GraphQL Query、Variables 与 operationName 包装为符合规范的 JSON 请求体。 */
    public static String buildJsonPayload(String query, String variablesJson, String operationName) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(I18n.get("graphql.queryRequired"));
        }

        ObjectNode root = Json.mapper().createObjectNode();
        root.put("query", query.trim());

        if (variablesJson != null && !variablesJson.isBlank()) {
            try {
                JsonNode varNode = Json.mapper().readTree(variablesJson.trim());
                if (varNode != null && !varNode.isNull()) {
                    root.set("variables", varNode);
                }
            } catch (Exception e) {
                throw new IllegalArgumentException(I18n.get("graphql.variablesInvalid", e.getMessage()));
            }
        }

        if (operationName != null && !operationName.isBlank()) {
            root.put("operationName", operationName.trim());
        }

        return root.toString();
    }

    /**
     * 解析 GraphQL 内省响应结果，提取 Query/Mutation 根类型与操作字段，
     * 输出结构化的人类可读摘要。
     */
    public static String summarizeIntrospection(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            return "";
        }
        try {
            JsonNode root = Json.mapper().readTree(responseJson);
            JsonNode schemaNode = root.path("data").path("__schema");
            if (schemaNode.isMissingNode() || schemaNode.isNull()) {
                if (root.has("errors")) {
                    return I18n.get("graphql.introspectionError") + "\n" + root.path("errors").toPrettyString();
                }
                return I18n.get("graphql.notIntrospection");
            }

            StringBuilder sb = new StringBuilder();
            String queryTypeName = schemaNode.path("queryType").path("name").asText("Query");
            String mutationTypeName = schemaNode.path("mutationType").path("name").asText(null);
            String subscriptionTypeName = schemaNode.path("subscriptionType").path("name").asText(null);

            JsonNode typesNode = schemaNode.path("types");
            Map<String, JsonNode> typeMap = new HashMap<>();
            if (typesNode.isArray()) {
                for (JsonNode t : typesNode) {
                    if (t.hasNonNull("name")) {
                        typeMap.put(t.get("name").asText(), t);
                    }
                }
            }

            sb.append("=== ").append(I18n.get("graphql.section.schema")).append(" ===\n");
            sb.append("Query Type: ").append(queryTypeName).append("\n");
            if (mutationTypeName != null) sb.append("Mutation Type: ").append(mutationTypeName).append("\n");
            if (subscriptionTypeName != null) sb.append("Subscription Type: ").append(subscriptionTypeName).append("\n");
            sb.append("\n");

            // Query 字段
            JsonNode qType = typeMap.get(queryTypeName);
            if (qType != null && qType.has("fields") && qType.get("fields").isArray()) {
                sb.append("• ").append(I18n.get("graphql.section.queries")).append(" (").append(qType.get("fields").size()).append("):\n");
                appendFields(sb, qType.get("fields"));
                sb.append("\n");
            }

            // Mutation 字段
            if (mutationTypeName != null) {
                JsonNode mType = typeMap.get(mutationTypeName);
                if (mType != null && mType.has("fields") && mType.get("fields").isArray()) {
                    sb.append("• ").append(I18n.get("graphql.section.mutations")).append(" (").append(mType.get("fields").size()).append("):\n");
                    appendFields(sb, mType.get("fields"));
                    sb.append("\n");
                }
            }

            return sb.toString().trim();
        } catch (Exception e) {
            return I18n.get("graphql.introspectionParseFailed", e.getMessage());
        }
    }

    private static void appendFields(StringBuilder sb, JsonNode fields) {
        for (JsonNode f : fields) {
            String name = f.path("name").asText();
            String returnType = formatType(f.path("type"));
            sb.append("   - ").append(name);

            JsonNode args = f.path("args");
            if (args.isArray() && args.size() > 0) {
                sb.append("(");
                List<String> argList = new ArrayList<>();
                for (JsonNode a : args) {
                    argList.add(a.path("name").asText() + ": " + formatType(a.path("type")));
                }
                sb.append(String.join(", ", argList)).append(")");
            }
            sb.append(": ").append(returnType);

            String desc = f.path("description").asText(null);
            if (desc != null && !desc.isBlank()) {
                sb.append("  # ").append(desc.replace("\n", " ").trim());
            }
            sb.append("\n");
        }
    }

    private static String formatType(JsonNode typeNode) {
        if (typeNode == null || typeNode.isNull() || typeNode.isMissingNode()) return "Unknown";
        String kind = typeNode.path("kind").asText("");
        if ("NON_NULL".equals(kind)) {
            return formatType(typeNode.path("ofType")) + "!";
        }
        if ("LIST".equals(kind)) {
            return "[" + formatType(typeNode.path("ofType")) + "]";
        }
        return typeNode.path("name").asText("Unknown");
    }
}
