package com.aqishi.toolbox.feature.cloud.domain;

import com.aqishi.toolbox.feature.codec.domain.StructuredDiff;
import com.aqishi.toolbox.feature.codec.domain.StructuredDiff.Change;
import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.util.*;

/**
 * Kubernetes 多文档 Manifest 结构差异对比器。
 *
 * <p>支持解析多文档 YAML（以 --- 分割）与 JSON 列表，自动提取 kind/namespace/name 进行资源对齐；
 * 默认过滤 status、managedFields、resourceVersion 等运行时注入的噪声字段，
 * 并按容器与配置的 name 属性对齐数组对象。</p>
 */
public final class K8sManifestDiff {

    public enum DiffType {
        ADDED,
        REMOVED,
        MODIFIED,
        UNCHANGED
    }

    public record ResourceKey(String kind, String namespace, String name) {
        public String display() {
            String ns = (namespace == null || namespace.isBlank()) ? "default" : namespace;
            return kind + "/" + ns + "/" + name;
        }
    }

    public record ResourceDiff(
            ResourceKey key,
            String apiVersion,
            DiffType type,
            List<Change> changes,
            JsonNode beforeNode,
            JsonNode afterNode) {}

    public record DiffSummary(
            int totalBefore,
            int totalAfter,
            int addedCount,
            int removedCount,
            int modifiedCount,
            int unchangedCount,
            List<ResourceDiff> resourceDiffs) {}

    /** 常见的 K8s 运行时注入元数据与状态字段（对比清单时通常视为噪声）。 */
    public static final Set<String> DEFAULT_IGNORED_PATHS = Set.of(
            "$.status",
            "$.metadata.managedFields",
            "$.metadata.resourceVersion",
            "$.metadata.uid",
            "$.metadata.generation",
            "$.metadata.creationTimestamp",
            "$.metadata.selfLink",
            "$.metadata.annotations['kubectl.kubernetes.io/last-applied-configuration']",
            "$.metadata.annotations['deployment.kubernetes.io/revision']"
    );

    private final ObjectMapper yamlMapper;
    private final StructuredDiff structuredDiff;

    public K8sManifestDiff() {
        this.yamlMapper = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER))
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.structuredDiff = new StructuredDiff();
    }

    public String compareToReport(
            String beforeManifest,
            String afterManifest,
            boolean filterNoise,
            Set<String> customIgnored,
            String filterKeyword) {

        if ((beforeManifest == null || beforeManifest.isBlank()) && (afterManifest == null || afterManifest.isBlank())) {
            throw new IllegalArgumentException(I18n.get("k8s.diff.empty"));
        }
        if ((beforeManifest != null && beforeManifest.length() > 2_000_000)
                || (afterManifest != null && afterManifest.length() > 2_000_000)) {
            throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
        }

        Set<String> ignored = new HashSet<>();
        if (filterNoise) {
            ignored.addAll(DEFAULT_IGNORED_PATHS);
        }
        if (customIgnored != null) {
            for (String p : customIgnored) {
                if (p != null && !p.isBlank()) {
                    String clean = p.trim();
                    if (!clean.startsWith("$")) {
                        clean = "$." + clean;
                    }
                    ignored.add(clean);
                }
            }
        }

        DiffSummary summary = compare(beforeManifest, afterManifest, ignored);
        return formatReport(summary, filterKeyword);
    }

    public DiffSummary compare(String beforeManifest, String afterManifest, Set<String> ignored) {
        Map<ResourceKey, JsonNode> beforeDocs = parseDocuments(beforeManifest != null ? beforeManifest : "");
        Map<ResourceKey, JsonNode> afterDocs = parseDocuments(afterManifest != null ? afterManifest : "");

        Set<ResourceKey> allKeys = new LinkedHashSet<>(beforeDocs.keySet());
        allKeys.addAll(afterDocs.keySet());

        List<ResourceDiff> diffs = new ArrayList<>();
        int added = 0;
        int removed = 0;
        int modified = 0;
        int unchanged = 0;

        for (ResourceKey key : allKeys) {
            JsonNode bNode = beforeDocs.get(key);
            JsonNode aNode = afterDocs.get(key);

            if (bNode == null) {
                added++;
                String apiVersion = aNode.hasNonNull("apiVersion") ? aNode.get("apiVersion").asText() : "";
                diffs.add(new ResourceDiff(key, apiVersion, DiffType.ADDED, List.of(), null, aNode));
            } else if (aNode == null) {
                removed++;
                String apiVersion = bNode.hasNonNull("apiVersion") ? bNode.get("apiVersion").asText() : "";
                diffs.add(new ResourceDiff(key, apiVersion, DiffType.REMOVED, List.of(), bNode, null));
            } else {
                String apiVersion = aNode.hasNonNull("apiVersion") ? aNode.get("apiVersion").asText() : "";
                List<Change> changes = compareNodes(bNode, aNode, ignored);
                if (changes.isEmpty()) {
                    unchanged++;
                    diffs.add(new ResourceDiff(key, apiVersion, DiffType.UNCHANGED, List.of(), bNode, aNode));
                } else {
                    modified++;
                    diffs.add(new ResourceDiff(key, apiVersion, DiffType.MODIFIED, changes, bNode, aNode));
                }
            }
        }

        return new DiffSummary(beforeDocs.size(), afterDocs.size(), added, removed, modified, unchanged, diffs);
    }

    private List<Change> compareNodes(JsonNode beforeNode, JsonNode afterNode, Set<String> ignored) {
        try {
            String bYaml = yamlMapper.writeValueAsString(beforeNode);
            String aYaml = yamlMapper.writeValueAsString(afterNode);
            // K8s 列表通常通过 "name" 属性对齐容器、env 等
            return structuredDiff.compare(bYaml, aYaml, true, ignored, "name");
        } catch (Exception e) {
            return List.of(new Change("$", "error", "", e.getMessage()));
        }
    }

    public Map<ResourceKey, JsonNode> parseDocuments(String text) {
        Map<ResourceKey, JsonNode> map = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return map;
        }

        int unnamedIndex = 1;
        try (JsonParser parser = new YAMLFactory().createParser(text)) {
            MappingIterator<JsonNode> it = yamlMapper.readValues(parser, JsonNode.class);
            while (it.hasNext()) {
                JsonNode doc = it.next();
                if (doc == null || doc.isNull() || doc.isMissingNode() || !doc.isObject()) {
                    continue;
                }

                // 处理 K8s List 包装结构
                if (doc.hasNonNull("items") && doc.get("items").isArray()) {
                    for (JsonNode item : doc.get("items")) {
                        if (item.isObject()) {
                            ResourceKey k = extractKey(item, unnamedIndex);
                            if (k.name().startsWith("unnamed_")) unnamedIndex++;
                            map.put(k, item);
                        }
                    }
                } else {
                    ResourceKey k = extractKey(doc, unnamedIndex);
                    if (k.name().startsWith("unnamed_")) unnamedIndex++;
                    map.put(k, doc);
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private static ResourceKey extractKey(JsonNode doc, int fallbackIndex) {
        String kind = doc.hasNonNull("kind") ? doc.get("kind").asText().trim() : "Unknown";
        String ns = "default";
        String name = "unnamed_" + fallbackIndex;

        JsonNode meta = doc.get("metadata");
        if (meta != null && meta.isObject()) {
            if (meta.hasNonNull("namespace")) {
                ns = meta.get("namespace").asText().trim();
            }
            if (meta.hasNonNull("name")) {
                name = meta.get("name").asText().trim();
            }
        }
        return new ResourceKey(kind, ns, name);
    }

    private String formatReport(DiffSummary summary, String filterKeyword) {
        StringBuilder sb = new StringBuilder();
        String kw = filterKeyword != null ? filterKeyword.trim().toLowerCase(Locale.ROOT) : "";

        sb.append("=== ").append(I18n.get("k8s.diff.section.summary")).append(" ===\n");
        sb.append(I18n.get("k8s.diff.summary",
                summary.totalBefore(),
                summary.totalAfter(),
                summary.addedCount(),
                summary.removedCount(),
                summary.modifiedCount(),
                summary.unchangedCount())).append("\n\n");

        sb.append("=== ").append(I18n.get("k8s.diff.section.details")).append(" ===\n");

        int shown = 0;
        for (ResourceDiff rd : summary.resourceDiffs()) {
            String displayKey = rd.key().display();
            if (!kw.isEmpty() && !displayKey.toLowerCase(Locale.ROOT).contains(kw)) {
                continue;
            }

            switch (rd.type()) {
                case ADDED -> {
                    sb.append("  [+] ").append(displayKey).append("  (").append(I18n.get("k8s.diff.type.added")).append(")\n");
                    shown++;
                }
                case REMOVED -> {
                    sb.append("  [-] ").append(displayKey).append("  (").append(I18n.get("k8s.diff.type.removed")).append(")\n");
                    shown++;
                }
                case MODIFIED -> {
                    sb.append("  [*] ").append(displayKey).append("  (")
                            .append(I18n.get("k8s.diff.type.modified", rd.changes().size())).append("):\n");
                    for (Change c : rd.changes()) {
                        String prefix = switch (c.kind()) {
                            case "added" -> "      + ";
                            case "removed" -> "      - ";
                            default -> "      ~ ";
                        };
                        sb.append(prefix).append(c.path());
                        if ("added".equals(c.kind())) {
                            sb.append(": ").append(truncateVal(c.after()));
                        } else if ("removed".equals(c.kind())) {
                            sb.append(": ").append(truncateVal(c.before()));
                        } else {
                            sb.append(": ").append(truncateVal(c.before())).append("  ->  ").append(truncateVal(c.after()));
                        }
                        sb.append("\n");
                    }
                    shown++;
                }
                case UNCHANGED -> {
                    if (kw.isEmpty()) {
                        // 无过滤时，未变更项按单行紧凑展示或仅在需要时查看
                    } else {
                        sb.append("  [ ] ").append(displayKey).append("  (").append(I18n.get("k8s.diff.type.unchanged")).append(")\n");
                        shown++;
                    }
                }
            }
        }

        if (shown == 0 && summary.unchangedCount() > 0 && kw.isEmpty()) {
            sb.append(I18n.get("k8s.diff.allUnchanged")).append("\n");
        } else if (shown == 0) {
            sb.append(I18n.get("k8s.diff.noMatch")).append("\n");
        }

        return sb.toString().trim();
    }

    private static String truncateVal(String val) {
        if (val == null) return "<null>";
        String trimmed = val.replace("\n", " ").trim();
        return trimmed.length() > 60 ? trimmed.substring(0, 57) + "..." : trimmed;
    }
}
