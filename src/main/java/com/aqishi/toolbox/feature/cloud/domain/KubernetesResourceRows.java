package com.aqishi.toolbox.feature.cloud.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Kubernetes 资源列表的纯行投影：把 API 返回的 JSON item 映射成
 * 各资源表格的行数据。所有方法不碰 Swing，方便直接单测。
 */
public final class KubernetesResourceRows {

    private static final String NODE_ROLE_PREFIX = "node-role.kubernetes.io/";

    private KubernetesResourceRows() {
    }

    /** 从列表响应 JSON 中逐条提取 item 并映射成行；映射返回 null 的条目跳过。 */
    public static List<Object[]> rowsOf(String responseJson, Function<JsonNode, Object[]> rowMapper)
            throws Exception {
        List<Object[]> rows = new ArrayList<>();
        for (JsonNode item : Json.mapper().readTree(responseJson).path("items")) {
            Object[] row = rowMapper.apply(item);
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** 资源列表路径；命名空间为 "all" 时跨命名空间查询。 */
    public static String listPath(String apiGroup, String resource, String namespace) {
        return "all".equals(namespace)
                ? apiGroup + "/" + resource
                : apiGroup + "/namespaces/" + namespace + "/" + resource;
    }

    /** 创建时长的人性化格式：天/小时/分钟/秒逐档降级；无法解析时返回 "-"。 */
    public static String formatAge(String creationTimestamp) {
        return formatAge(creationTimestamp, Instant.now());
    }

    public static String formatAge(String creationTimestamp, Instant now) {
        try {
            Instant created = Instant.parse(creationTimestamp);
            Duration d = Duration.between(created, now);
            long days = d.toDays();
            if (days > 0) {
                return days + "d";
            }
            long hours = d.toHours();
            if (hours > 0) {
                return hours + "h";
            }
            long minutes = d.toMinutes();
            if (minutes > 0) {
                return minutes + "m";
            }
            return d.getSeconds() + "s";
        } catch (Exception error) {
            return "-";
        }
    }

    public static String namespaceOf(JsonNode item) {
        return item.path("metadata").path("namespace").asText();
    }

    public static String nameOf(JsonNode item) {
        return item.path("metadata").path("name").asText();
    }

    public static String ageOf(JsonNode item, Instant now) {
        return formatAge(item.path("metadata").path("creationTimestamp").asText(), now);
    }

    /** 就绪/期望副本数，如 "2/3"。 */
    public static String readyCount(JsonNode item) {
        return item.path("status").path("readyReplicas").asInt(0)
                + "/" + item.path("spec").path("replicas").asInt(0);
    }

    // ===== 各资源的行投影（列顺序与面板表头一一对应） =====

    public static Object[] podRow(JsonNode item, Instant now) {
        int restarts = 0;
        for (JsonNode status : item.path("status").path("containerStatuses")) {
            restarts += status.path("restartCount").asInt();
        }
        return new Object[]{
                namespaceOf(item), nameOf(item),
                item.path("status").path("phase").asText(),
                restarts,
                item.path("status").path("podIP").asText("-"),
                item.path("spec").path("nodeName").asText("-"),
                ageOf(item, now)
        };
    }

    public static Object[] deploymentRow(JsonNode item, Instant now) {
        return new Object[]{
                namespaceOf(item), nameOf(item),
                readyCount(item),
                item.path("status").path("updatedReplicas").asInt(0),
                item.path("status").path("availableReplicas").asInt(0),
                ageOf(item, now)
        };
    }

    public static Object[] statefulSetRow(JsonNode item, Instant now) {
        return new Object[]{
                namespaceOf(item), nameOf(item),
                readyCount(item),
                item.path("status").path("currentReplicas").asInt(0),
                ageOf(item, now)
        };
    }

    public static Object[] daemonSetRow(JsonNode item, Instant now) {
        return new Object[]{
                namespaceOf(item), nameOf(item),
                item.path("status").path("desiredNumberScheduled").asInt(0),
                item.path("status").path("currentNumberScheduled").asInt(0),
                item.path("status").path("numberReady").asInt(0),
                item.path("status").path("updatedNumberScheduled").asInt(0),
                ageOf(item, now)
        };
    }

    public static Object[] cronJobRow(JsonNode item, Instant now) {
        return new Object[]{
                namespaceOf(item), nameOf(item),
                item.path("spec").path("schedule").asText("-"),
                item.path("spec").path("suspend").asBoolean(false),
                item.path("status").path("active").size(),
                item.path("status").path("lastScheduleTime").asText("-"),
                ageOf(item, now)
        };
    }

    public static Object[] serviceRow(JsonNode item, Instant now) {
        return new Object[]{
                namespaceOf(item), nameOf(item),
                item.path("spec").path("type").asText(),
                item.path("spec").path("clusterIP").asText(),
                externalAddresses(item), portsText(item), ageOf(item, now)
        };
    }

    public static Object[] configMapRow(JsonNode item, Instant now) {
        return new Object[]{namespaceOf(item), nameOf(item),
                item.path("data").size(), ageOf(item, now)};
    }

    public static Object[] secretRow(JsonNode item, Instant now) {
        return new Object[]{
                namespaceOf(item), nameOf(item),
                item.path("type").asText(),
                item.path("data").size(),
                ageOf(item, now)
        };
    }

    public static Object[] nodeRow(JsonNode item, Instant now) {
        return new Object[]{
                nameOf(item), nodeStatus(item), nodeRoles(item),
                item.path("status").path("nodeInfo").path("kubeletVersion").asText(),
                item.path("status").path("nodeInfo").path("osImage").asText(),
                ageOf(item, now)
        };
    }

    /** LoadBalancer 的外部地址（IP 优先于 hostname），没有则 {@code <none>}。 */
    static String externalAddresses(JsonNode item) {
        StringBuilder external = new StringBuilder();
        for (JsonNode ingress : item.path("status").path("loadBalancer").path("ingress")) {
            if (external.length() > 0) {
                external.append(",");
            }
            if (ingress.has("ip")) {
                external.append(ingress.path("ip").asText());
            } else if (ingress.has("hostname")) {
                external.append(ingress.path("hostname").asText());
            }
        }
        return external.length() == 0 ? "<none>" : external.toString();
    }

    /** 端口列表文本，如 "80/TCP,443/TCP"。 */
    static String portsText(JsonNode item) {
        StringBuilder ports = new StringBuilder();
        for (JsonNode port : item.path("spec").path("ports")) {
            if (ports.length() > 0) {
                ports.append(",");
            }
            ports.append(port.path("port").asInt()).append("/")
                    .append(port.path("protocol").asText());
        }
        return ports.toString();
    }

    /** 节点 Ready 状态；没有 Ready 条件时按 NotReady 处理。 */
    static String nodeStatus(JsonNode item) {
        String status = "NotReady";
        for (JsonNode condition : item.path("status").path("conditions")) {
            if ("Ready".equals(condition.path("type").asText())) {
                if ("True".equals(condition.path("status").asText())) {
                    status = "Ready";
                }
                break;
            }
        }
        return status;
    }

    /** 节点角色标签（去掉 node-role.kubernetes.io/ 前缀），没有则 {@code <none>}。 */
    static String nodeRoles(JsonNode item) {
        StringBuilder roles = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> labels = item.path("metadata").path("labels").fields();
        while (labels.hasNext()) {
            Map.Entry<String, JsonNode> label = labels.next();
            if (label.getKey().startsWith(NODE_ROLE_PREFIX)) {
                if (roles.length() > 0) {
                    roles.append(",");
                }
                roles.append(label.getKey().substring(NODE_ROLE_PREFIX.length()));
            }
        }
        return roles.length() == 0 ? "<none>" : roles.toString();
    }
}
