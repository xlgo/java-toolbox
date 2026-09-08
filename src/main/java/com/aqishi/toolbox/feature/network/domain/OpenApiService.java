package com.aqishi.toolbox.feature.network.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * OpenAPI 3.x / Swagger 2.0 规范解析与请求构建服务。
 */
public class OpenApiService {

    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    public static final String SAMPLE_SPEC = "openapi: 3.0.1\n" +
            "info:\n" +
            "  title: 示例用户与订单服务 (Swagger Petstore Sample)\n" +
            "  description: 本地测试与接口调试的 OpenAPI 3 示例文档\n" +
            "  version: 1.0.0\n" +
            "servers:\n" +
            "  - url: https://httpbin.org\n" +
            "paths:\n" +
            "  /get:\n" +
            "    get:\n" +
            "      tags:\n" +
            "        - 基础查询\n" +
            "      summary: 获取客户端信息与请求详情\n" +
            "      description: 测试 HTTP GET 请求及 Query 参数响应\n" +
            "      parameters:\n" +
            "        - name: keyword\n" +
            "          in: query\n" +
            "          required: false\n" +
            "          description: 搜索关键字\n" +
            "          schema:\n" +
            "            type: string\n" +
            "            example: test123\n" +
            "        - name: page\n" +
            "          in: query\n" +
            "          required: false\n" +
            "          description: 当前页码\n" +
            "          schema:\n" +
            "            type: integer\n" +
            "            example: 1\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: 成功返回请求元数据\n" +
            "  /post:\n" +
            "    post:\n" +
            "      tags:\n" +
            "        - 订单与创建\n" +
            "      summary: 提交订单信息\n" +
            "      description: 测试 POST 请求体与 JSON 序列化\n" +
            "      requestBody:\n" +
            "        required: true\n" +
            "        content:\n" +
            "          application/json:\n" +
            "            schema:\n" +
            "              type: object\n" +
            "              properties:\n" +
            "                orderId:\n" +
            "                  type: string\n" +
            "                  example: \"ORD-20260908-01\"\n" +
            "                amount:\n" +
            "                  type: number\n" +
            "                  example: 99.5\n" +
            "                items:\n" +
            "                  type: array\n" +
            "                  items:\n" +
            "                    type: string\n" +
            "                    example: \"Book-Java-Concurrency\"\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: 订单创建成功\n" +
            "  /anything/{category}:\n" +
            "    put:\n" +
            "      tags:\n" +
            "        - 路径参数测试\n" +
            "      summary: 更新特定分类数据\n" +
            "      parameters:\n" +
            "        - name: category\n" +
            "          in: path\n" +
            "          required: true\n" +
            "          description: 分类标识\n" +
            "          schema:\n" +
            "            type: string\n" +
            "            example: electronics\n" +
            "      requestBody:\n" +
            "        content:\n" +
            "          application/json:\n" +
            "            schema:\n" +
            "              type: object\n" +
            "              properties:\n" +
            "                status:\n" +
            "                  type: string\n" +
            "                  example: \"active\"\n" +
            "      responses:\n" +
            "        '200':\n" +
            "          description: 分类已更新\n";

    /**
     * 解析 OpenAPI / Swagger 文本内容（自动判断 JSON 或 YAML）。
     */
    public OpenApiSpec parse(String content) throws Exception {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("规范内容不能为空");
        }
        String trimmed = content.trim();
        JsonNode root;
        try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                root = jsonMapper.readTree(trimmed);
            } else {
                root = yamlMapper.readTree(trimmed);
            }
        } catch (Exception ex) {
            // 如果一种格式失败，尝试另一种解析
            try {
                root = yamlMapper.readTree(trimmed);
            } catch (Exception ex2) {
                root = jsonMapper.readTree(trimmed);
            }
        }

        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("无法识别的 OpenAPI 根节点格式");
        }

        OpenApiSpec spec = new OpenApiSpec();

        // 1. Info 解析
        JsonNode infoNode = root.path("info");
        if (!infoNode.isMissingNode()) {
            spec.setTitle(infoNode.path("title").asText("Untitled API"));
            spec.setVersion(infoNode.path("version").asText("1.0.0"));
            spec.setDescription(infoNode.path("description").asText(""));
        }

        // 2. Servers / BasePath 解析
        List<String> servers = new ArrayList<>();
        JsonNode serversNode = root.path("servers");
        if (serversNode.isArray()) {
            for (JsonNode s : serversNode) {
                String u = s.path("url").asText("");
                if (!u.isEmpty()) servers.add(u);
            }
        }
        // Swagger 2.0 host / basePath
        if (servers.isEmpty()) {
            String host = root.path("host").asText("");
            String basePath = root.path("basePath").asText("");
            JsonNode schemes = root.path("schemes");
            String scheme = (schemes.isArray() && schemes.size() > 0) ? schemes.get(0).asText("http") : "http";
            if (!host.isEmpty()) {
                servers.add(scheme + "://" + host + (basePath.startsWith("/") ? basePath : "/" + basePath));
            } else if (!basePath.isEmpty()) {
                servers.add(basePath);
            }
        }
        if (servers.isEmpty()) {
            servers.add("https://httpbin.org");
        }
        spec.setServers(servers);

        // 3. Paths 解析
        JsonNode pathsNode = root.path("paths");
        List<OpenApiSpec.ApiEndpoint> endpointList = new ArrayList<>();
        if (pathsNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> pathFields = pathsNode.fields();
            while (pathFields.hasNext()) {
                Map.Entry<String, JsonNode> pathEntry = pathFields.next();
                String path = pathEntry.getKey();
                JsonNode pathObj = pathEntry.getValue();

                // 检查公共参数
                List<OpenApiSpec.Parameter> commonParams = extractParameters(pathObj.path("parameters"), root);

                // 遍历 HTTP 动词
                String[] methods = {"get", "post", "put", "delete", "patch", "head", "options"};
                for (String m : methods) {
                    JsonNode methodNode = pathObj.path(m);
                    if (methodNode.isObject()) {
                        OpenApiSpec.ApiEndpoint ep = new OpenApiSpec.ApiEndpoint();
                        ep.setMethod(m.toUpperCase(Locale.ROOT));
                        ep.setPath(path);
                        ep.setSummary(methodNode.path("summary").asText(""));
                        ep.setDescription(methodNode.path("description").asText(""));

                        // Tag
                        JsonNode tags = methodNode.path("tags");
                        if (tags.isArray() && tags.size() > 0) {
                            ep.setTag(tags.get(0).asText("Default"));
                        } else {
                            ep.setTag("Default");
                        }

                        // Parameters (合并公共参数与方法特有参数)
                        List<OpenApiSpec.Parameter> epParams = new ArrayList<>(commonParams);
                        epParams.addAll(extractParameters(methodNode.path("parameters"), root));
                        ep.setParameters(epParams);

                        // RequestBody (OpenAPI 3)
                        JsonNode reqBody = methodNode.path("requestBody");
                        if (reqBody.isObject()) {
                            JsonNode contentNode = reqBody.path("content");
                            if (contentNode.isObject()) {
                                Iterator<Map.Entry<String, JsonNode>> mediaTypes = contentNode.fields();
                                if (mediaTypes.hasNext()) {
                                    Map.Entry<String, JsonNode> mt = mediaTypes.next();
                                    ep.setRequestContentType(mt.getKey());
                                    JsonNode schema = mt.getValue().path("schema");
                                    ep.setRequestBodyExample(generateExampleFromSchema(schema, root));
                                }
                            }
                        } else {
                            // Swagger 2.0 body parameter
                            for (OpenApiSpec.Parameter p : epParams) {
                                if ("body".equalsIgnoreCase(p.getIn())) {
                                    ep.setRequestContentType("application/json");
                                    ep.setRequestBodyExample(p.getExample());
                                }
                            }
                        }

                        // Responses
                        JsonNode respNode = methodNode.path("responses");
                        if (respNode.isObject()) {
                            List<OpenApiSpec.ResponseItem> respList = new ArrayList<>();
                            Iterator<Map.Entry<String, JsonNode>> respEntries = respNode.fields();
                            while (respEntries.hasNext()) {
                                Map.Entry<String, JsonNode> re = respEntries.next();
                                respList.add(new OpenApiSpec.ResponseItem(re.getKey(), re.getValue().path("description").asText("")));
                            }
                            ep.setResponses(respList);
                        }

                        endpointList.add(ep);
                    }
                }
            }
        }
        spec.setEndpoints(endpointList);
        return spec;
    }

    /**
     * 从远程 URL 获取规范文本。
     */
    public String fetchRemoteSpec(String urlStr) throws Exception {
        if (urlStr == null || !urlStr.startsWith("http")) {
            throw new IllegalArgumentException("请输入合法的 HTTP/HTTPS 规范地址");
        }
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Accept", "application/json, application/yaml, text/yaml, */*");
        conn.setRequestProperty("User-Agent", "JavaToolbox-OpenAPI-Workbench");

        int code = conn.getResponseCode();
        InputStream is = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) throw new IllegalStateException("HTTP 状态码: " + code + ", 无法获取响应体");

        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 生成 cURL 调用命令。
     */
    public String buildCurl(String baseUrl, OpenApiSpec.ApiEndpoint endpoint,
                            Map<String, String> pathParams,
                            Map<String, String> queryParams,
                            Map<String, String> headers,
                            String body) {
        String base = (baseUrl != null && !baseUrl.isEmpty()) ? baseUrl.trim() : "https://httpbin.org";
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        String path = endpoint.getPath();
        if (pathParams != null) {
            for (Map.Entry<String, String> entry : pathParams.entrySet()) {
                path = path.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        if (!path.startsWith("/")) path = "/" + path;

        StringBuilder urlBuilder = new StringBuilder(base).append(path);
        if (queryParams != null && !queryParams.isEmpty()) {
            urlBuilder.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (!first) urlBuilder.append("&");
                urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }

        StringBuilder curl = new StringBuilder("curl -X ").append(endpoint.getMethod()).append(" \"").append(urlBuilder).append("\"");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                curl.append(" \\\n  -H \"").append(entry.getKey()).append(": ").append(entry.getValue()).append("\"");
            }
        }
        if (body != null && !body.trim().isEmpty() && !"GET".equalsIgnoreCase(endpoint.getMethod())) {
            String sanitizedBody = body.replace("\"", "\\\"");
            curl.append(" \\\n  -d \"").append(sanitizedBody).append("\"");
        }
        return curl.toString();
    }

    private List<OpenApiSpec.Parameter> extractParameters(JsonNode paramsNode, JsonNode root) {
        List<OpenApiSpec.Parameter> list = new ArrayList<>();
        if (!paramsNode.isArray()) return list;

        for (JsonNode p : paramsNode) {
            if (p.has("$ref")) {
                p = resolveRef(p.path("$ref").asText(""), root);
            }
            if (p == null || !p.isObject()) continue;

            String name = p.path("name").asText("");
            String in = p.path("in").asText("query");
            boolean required = p.path("required").asBoolean(false);
            String desc = p.path("description").asText("");

            JsonNode schema = p.path("schema");
            String type = schema.path("type").asText(p.path("type").asText("string"));
            String example = p.path("example").asText("");
            if (example.isEmpty()) {
                example = schema.path("example").asText("");
            }
            list.add(new OpenApiSpec.Parameter(name, in, required, type, desc, example));
        }
        return list;
    }

    private String generateExampleFromSchema(JsonNode schema, JsonNode root) {
        if (schema == null || schema.isMissingNode()) return "{}";
        if (schema.has("$ref")) {
            schema = resolveRef(schema.path("$ref").asText(""), root);
        }
        if (schema == null || schema.isMissingNode()) return "{}";

        String type = schema.path("type").asText("object");
        if ("object".equalsIgnoreCase(type) || schema.has("properties")) {
            StringBuilder sb = new StringBuilder("{\n");
            JsonNode props = schema.path("properties");
            if (props.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = props.fields();
                boolean first = true;
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> prop = it.next();
                    if (!first) sb.append(",\n");
                    sb.append("  \"").append(prop.getKey()).append("\": ");
                    sb.append(generateSampleValue(prop.getValue(), root, "  "));
                    first = false;
                }
            }
            sb.append("\n}");
            return sb.toString();
        } else if ("array".equalsIgnoreCase(type)) {
            JsonNode items = schema.path("items");
            return "[\n  " + generateSampleValue(items, root, "  ") + "\n]";
        }
        return generateSampleValue(schema, root, "");
    }

    private String generateSampleValue(JsonNode node, JsonNode root, String indent) {
        if (node == null || node.isMissingNode()) return "\"value\"";
        if (node.has("$ref")) {
            node = resolveRef(node.path("$ref").asText(""), root);
        }
        if (node == null) return "\"value\"";

        if (node.has("example")) {
            JsonNode ex = node.get("example");
            return ex.isTextual() ? "\"" + ex.asText() + "\"" : ex.toString();
        }

        String type = node.path("type").asText("string");
        switch (type.toLowerCase(Locale.ROOT)) {
            case "integer":
            case "int":
                return "1";
            case "number":
            case "float":
            case "double":
                return "10.5";
            case "boolean":
                return "true";
            case "array":
                JsonNode items = node.path("items");
                return "[" + generateSampleValue(items, root, indent) + "]";
            case "object":
                return "{}";
            case "string":
            default:
                return "\"sample\"";
        }
    }

    private JsonNode resolveRef(String ref, JsonNode root) {
        if (ref == null || !ref.startsWith("#/")) return null;
        String[] parts = ref.substring(2).split("/");
        JsonNode current = root;
        for (String p : parts) {
            if (current == null) return null;
            current = current.path(p);
        }
        return current;
    }
}
