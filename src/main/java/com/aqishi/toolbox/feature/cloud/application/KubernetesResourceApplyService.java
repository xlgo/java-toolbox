package com.aqishi.toolbox.feature.cloud.application;

import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 常见的集群级资源类型（不属于任何命名空间）。 */
    private static final Set<String> CLUSTER_SCOPED = Set.of(
            "Namespace", "Node", "PersistentVolume", "StorageClass", "ClusterRole", "ClusterRoleBinding",
            "CustomResourceDefinition", "PriorityClass", "IngressClass", "RuntimeClass", "CSIDriver", "CSINode",
            "VolumeAttachment", "APIService", "ValidatingWebhookConfiguration", "MutatingWebhookConfiguration",
            "CertificateSigningRequest", "ComponentStatus", "ClusterIssuer");

    /** 不符合一般规则的复数。 */
    private static final Map<String, String> IRREGULAR_PLURALS = Map.of(
            "endpoints", "endpoints");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
    private final ObjectMapper jsonMapper = Json.mapper();

    /**
     * 解析单文档清单。多文档输入请用 {@link #prepareAll}；这里遇到多个文档会直接报错，
     * 而不是像早先那样只取第一个、把其余资源静默丢掉。
     */
    private static final String EMPTY_YAML = "YAML 内容不能为空";

    public ApplyPlan prepare(String yamlText, String selectedNamespace) throws Exception {
        List<ApplyPlan> plans = prepareAll(yamlText, selectedNamespace);
        if (plans.size() != 1) {
            throw new IllegalArgumentException(I18n.get("tool.k8s.apply.multipleDocuments", plans.size()));
        }
        return plans.get(0);
    }

    /**
     * 解析以 {@code ---} 分隔的多文档清单，按出现顺序返回每个资源的请求计划。
     *
     * <p>先全部校验再返回：任何一个文档不合法都会抛出异常，调用方因此不会在应用了一半之后才发现后面有错。
     * 空文档（例如开头或结尾多余的 {@code ---}）会被跳过。</p>
     */
    public List<ApplyPlan> prepareAll(String yamlText, String selectedNamespace) throws Exception {
        if (yamlText == null || yamlText.trim().isEmpty()) {
            throw new IllegalArgumentException(EMPTY_YAML);
        }
        List<ApplyPlan> plans = new ArrayList<>();
        try (MappingIterator<JsonNode> documents = yamlMapper.readerFor(JsonNode.class).readValues(yamlText)) {
            int index = 0;
            while (documents.hasNextValue()) {
                JsonNode node = documents.nextValue();
                index++;
                if (node == null || node.isNull() || node.isMissingNode()) {
                    continue;
                }
                try {
                    plans.add(prepareDocument(node, selectedNamespace));
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(I18n.get("tool.k8s.apply.documentInvalid", index, invalid.getMessage()), invalid);
                }
            }
        }
        if (plans.isEmpty()) {
            throw new IllegalArgumentException(EMPTY_YAML);
        }
        return plans;
    }

    private ApplyPlan prepareDocument(JsonNode node, String selectedNamespace) throws Exception {
        if (!node.isObject()) {
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

        /**
         * 表外的类型按英语复数规则推断 REST 资源名，并用已知清单判断是否为集群级资源。
         * 早先一律加 "s" 且当作命名空间资源：NetworkPolicy 成了 networkpolicys，
         * ClusterRole、StorageClass、CRD 被发到 /namespaces/... 下而 404。
         * 自定义资源若复数不规则，仍需服务端 API 发现才能准确得到。
         */
        private static ResourceKind generic(String kind, String apiVersion) {
            if (!KIND.matcher(kind.trim()).matches()) {
                throw new IllegalArgumentException("kind 含有非法路径字符");
            }
            String normalizedKind = kind.trim();
            String prefix = apiVersion.contains("/") ? "/apis/" + apiVersion : "/api/" + apiVersion;
            return new ResourceKind(plural(normalizedKind), prefix, !CLUSTER_SCOPED.contains(normalizedKind));
        }

        static String plural(String kind) {
            String lower = kind.toLowerCase(Locale.ROOT);
            if (IRREGULAR_PLURALS.containsKey(lower)) {
                return IRREGULAR_PLURALS.get(lower);
            }
            if (lower.endsWith("y") && lower.length() > 1 && "aeiou".indexOf(lower.charAt(lower.length() - 2)) < 0) {
                return lower.substring(0, lower.length() - 1) + "ies";
            }
            if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("ch") || lower.endsWith("sh")) {
                return lower + "es";
            }
            return lower + "s";
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
