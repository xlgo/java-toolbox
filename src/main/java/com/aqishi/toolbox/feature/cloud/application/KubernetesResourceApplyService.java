package com.aqishi.toolbox.feature.cloud.application;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Prepares Kubernetes apply requests without coupling YAML parsing to Swing.
 *
 * <p>The panel still owns the confirmation dialog and transport lifecycle;
 * this service owns the deterministic part of turning a manifest into an API
 * path and JSON request body.</p>
 */
public final class KubernetesResourceApplyService {

    // Kubernetes resource names are commonly DNS subdomains (up to 253 chars).
    // Keep the validation focused on URL safety while accepting long, legacy names.
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,251}[A-Za-z0-9])?");
    private static final Pattern DNS_SUBDOMAIN = Pattern.compile(
            "[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern DNS_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");
    private static final Pattern KIND = Pattern.compile("[A-Za-z][A-Za-z0-9]*");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = Json.mapper();

    public ApplyPlan prepare(String yamlText, String selectedNamespace) throws Exception {
        if (yamlText == null || yamlText.trim().isEmpty()) {
            throw new IllegalArgumentException("YAML 内容不能为空");
        }
        JsonNode node = yamlMapper.readTree(yamlText);
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("YAML 必须是对象文档");
        }

        String kind = node.path("kind").asText("").trim();
        String name = node.path("metadata").path("name").asText("").trim();
        if (kind.isEmpty() || name.isEmpty()) {
            throw new IllegalArgumentException("YAML 格式错误：未找到 kind 或 metadata.name");
        }
        validateSegment(name, "metadata.name");

        ResourceKind resourceKind = ResourceKind.resolve(kind, node.path("apiVersion").asText(""));
        String namespace = node.path("metadata").path("namespace").asText("").trim();
        if (resourceKind.namespaced()) {
            if (namespace.isEmpty()) {
                namespace = selectedNamespace == null || selectedNamespace.trim().isEmpty()
                        || "all".equalsIgnoreCase(selectedNamespace.trim())
                        ? "default"
                        : selectedNamespace.trim();
            }
            validateSegment(namespace, "metadata.namespace");
        } else {
            namespace = "";
        }

        String collectionPath = resourceKind.groupPrefix()
                + (resourceKind.namespaced() ? "/namespaces/" + namespace : "")
                + "/" + resourceKind.plural();
        String resourcePath = collectionPath + "/" + name;
        return new ApplyPlan(resourcePath, collectionPath, name, namespace,
                resourceKind.namespaced(), jsonMapper.writeValueAsString(node));
    }

    public String updateResourceVersion(ApplyPlan plan, String existingJson) throws Exception {
        Objects.requireNonNull(plan, "plan");
        JsonNode existing = jsonMapper.readTree(existingJson);
        String resourceVersion = existing == null
                ? ""
                : existing.path("metadata").path("resourceVersion").asText("");
        if (resourceVersion.isEmpty()) {
            throw new IllegalArgumentException("现有资源缺少 metadata.resourceVersion");
        }
        JsonNode desired = jsonMapper.readTree(plan.jsonBody());
        if (!(desired instanceof ObjectNode)) {
            throw new IllegalArgumentException("资源清单必须是 JSON 对象");
        }
        JsonNode metadata = desired.get("metadata");
        if (!(metadata instanceof ObjectNode)) {
            metadata = ((ObjectNode) desired).putObject("metadata");
        }
        ((ObjectNode) metadata).put("resourceVersion", resourceVersion);
        return jsonMapper.writeValueAsString(desired);
    }

    private static void validateSegment(String value, String label) {
        if (!PATH_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException(label + " 含有非法路径字符");
        }
    }

    public record ApplyPlan(String resourcePath, String collectionPath, String resourceName,
                            String namespace, boolean namespaced, String jsonBody) {
    }

    private record ResourceKind(String plural, String groupPrefix, boolean namespaced) {
        private static ResourceKind resolve(String kind, String apiVersion) {
            String normalizedVersion = validateApiVersion(apiVersion == null ? "" : apiVersion.trim());
            return switch (kind) {
                case "Pod" -> standard(kind, normalizedVersion, "v1", "pods", "/api/v1", true);
                case "Service" -> standard(kind, normalizedVersion, "v1", "services", "/api/v1", true);
                case "ConfigMap" -> standard(kind, normalizedVersion, "v1", "configmaps", "/api/v1", true);
                case "Secret" -> standard(kind, normalizedVersion, "v1", "secrets", "/api/v1", true);
                case "Namespace" -> standard(kind, normalizedVersion, "v1", "namespaces", "/api/v1", false);
                case "Node" -> standard(kind, normalizedVersion, "v1", "nodes", "/api/v1", false);
                case "Deployment" -> standard(kind, normalizedVersion, "apps/v1", "deployments", "/apis/apps/v1", true);
                case "StatefulSet" -> standard(kind, normalizedVersion, "apps/v1", "statefulsets", "/apis/apps/v1", true);
                case "DaemonSet" -> standard(kind, normalizedVersion, "apps/v1", "daemonsets", "/apis/apps/v1", true);
                case "Ingress" -> standard(kind, normalizedVersion, "networking.k8s.io/v1", "ingresses", "/apis/networking.k8s.io/v1", true);
                default -> generic(kind, normalizedVersion);
            };
        }

        private static ResourceKind standard(String kind, String apiVersion, String expectedApiVersion,
                                             String plural, String groupPrefix, boolean namespaced) {
            if (!expectedApiVersion.equals(apiVersion)) {
                throw new IllegalArgumentException(kind + " 仅支持 apiVersion: " + expectedApiVersion);
            }
            return new ResourceKind(plural, groupPrefix, namespaced);
        }

        private static ResourceKind generic(String kind, String apiVersion) {
            if (!KIND.matcher(kind.trim()).matches()) {
                throw new IllegalArgumentException("kind 含有非法路径字符");
            }
            String normalizedKind = kind.trim();
            String plural = normalizedKind.toLowerCase(Locale.ROOT) + "s";
            String prefix = apiVersion.contains("/") ? "/apis/" + apiVersion : "/api/" + apiVersion;
            return new ResourceKind(plural, prefix, true);
        }

        private static String validateApiVersion(String apiVersion) {
            if (apiVersion == null || apiVersion.isEmpty()) {
                throw new IllegalArgumentException("YAML 格式错误：未找到 apiVersion");
            }
            String[] segments = apiVersion.split("/", -1);
            boolean valid = (segments.length == 1 && DNS_LABEL.matcher(segments[0]).matches())
                    || (segments.length == 2 && DNS_SUBDOMAIN.matcher(segments[0]).matches()
                    && DNS_LABEL.matcher(segments[1]).matches());
            if (!valid) {
                throw new IllegalArgumentException("apiVersion 含有非法路径字符");
            }
            return apiVersion;
        }
    }
}
