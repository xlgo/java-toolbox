package com.aqishi.toolbox.feature.cloud.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesResourceRowsTest {

    private static final Instant NOW = Instant.parse("2026-09-11T12:00:00Z");

    private static JsonNode item(String json) throws Exception {
        return Json.mapper().readTree(json);
    }

    @Test
    void listPathScopesToNamespaceUnlessAll() {
        assertEquals("/api/v1/namespaces/prod/pods",
                KubernetesResourceRows.listPath("/api/v1", "pods", "prod"));
        assertEquals("/api/v1/pods",
                KubernetesResourceRows.listPath("/api/v1", "pods", "all"));
    }

    @Test
    void formatAgePicksLargestUnit() {
        assertEquals("3d", KubernetesResourceRows.formatAge("2026-09-08T12:00:00Z", NOW));
        assertEquals("5h", KubernetesResourceRows.formatAge("2026-09-11T07:00:00Z", NOW));
        assertEquals("45m", KubernetesResourceRows.formatAge("2026-09-11T11:15:00Z", NOW));
        assertEquals("30s", KubernetesResourceRows.formatAge("2026-09-11T11:59:30Z", NOW));
    }

    @Test
    void formatAgeFallsBackToDashOnUnparseableInput() {
        assertEquals("-", KubernetesResourceRows.formatAge("not-a-timestamp", NOW));
        assertEquals("-", KubernetesResourceRows.formatAge("", NOW));
    }

    @Test
    void podRowSumsRestartsAcrossContainers() throws Exception {
        JsonNode pod = item("{\"metadata\":{\"namespace\":\"prod\",\"name\":\"web\","
                + "\"creationTimestamp\":\"2026-09-10T12:00:00Z\"},"
                + "\"spec\":{\"nodeName\":\"node-1\"},"
                + "\"status\":{\"phase\":\"Running\",\"podIP\":\"10.0.0.5\","
                + "\"containerStatuses\":[{\"restartCount\":2},{\"restartCount\":3}]}}");

        Object[] row = KubernetesResourceRows.podRow(pod, NOW);
        assertArrayEquals(new Object[]{"prod", "web", "Running", 5, "10.0.0.5", "node-1", "1d"}, row);
    }

    @Test
    void podRowDefaultsMissingFields() throws Exception {
        JsonNode pod = item("{\"metadata\":{\"namespace\":\"prod\",\"name\":\"web\"}}");
        Object[] row = KubernetesResourceRows.podRow(pod, NOW);
        assertEquals("", row[2]);  // phase 缺失 → 空串
        assertEquals(0, row[3]);   // 无容器状态 → 0 重启
        assertEquals("-", row[4]); // podIP 缺失 → "-"
        assertEquals("-", row[6]); // 无创建时间 → "-"
    }

    @Test
    void deploymentRowRendersReadyOverDesired() throws Exception {
        JsonNode deploy = item("{\"metadata\":{\"namespace\":\"prod\",\"name\":\"api\","
                + "\"creationTimestamp\":\"2026-09-11T06:00:00Z\"},"
                + "\"spec\":{\"replicas\":3},"
                + "\"status\":{\"readyReplicas\":2,\"updatedReplicas\":3,\"availableReplicas\":2}}");
        assertArrayEquals(new Object[]{"prod", "api", "2/3", 3, 2, "6h"},
                KubernetesResourceRows.deploymentRow(deploy, NOW));
    }

    @Test
    void cronJobRowReadsScheduleAndSuspend() throws Exception {
        JsonNode cron = item("{\"metadata\":{\"namespace\":\"prod\",\"name\":\"nightly\"},"
                + "\"spec\":{\"schedule\":\"0 3 * * *\",\"suspend\":true},"
                + "\"status\":{\"active\":[{}],\"lastScheduleTime\":\"2026-09-11T03:00:00Z\"}}");
        Object[] row = KubernetesResourceRows.cronJobRow(cron, NOW);
        assertEquals("0 3 * * *", row[2]);
        assertEquals(true, row[3]);
        assertEquals(1, row[4]);
        assertEquals("2026-09-11T03:00:00Z", row[5]);
    }

    @Test
    void serviceRowBuildsExternalAddressesAndPorts() throws Exception {
        JsonNode svc = item("{\"metadata\":{\"namespace\":\"prod\",\"name\":\"web\"},"
                + "\"spec\":{\"type\":\"LoadBalancer\",\"clusterIP\":\"10.96.0.1\","
                + "\"ports\":[{\"port\":80,\"protocol\":\"TCP\"},{\"port\":443,\"protocol\":\"TCP\"}]},"
                + "\"status\":{\"loadBalancer\":{\"ingress\":[{\"ip\":\"1.2.3.4\"},{\"hostname\":\"lb.example.com\"}]}}}");
        Object[] row = KubernetesResourceRows.serviceRow(svc, NOW);
        assertEquals("LoadBalancer", row[2]);
        assertEquals("1.2.3.4,lb.example.com", row[4]);
        assertEquals("80/TCP,443/TCP", row[5]);
    }

    @Test
    void serviceRowShowsNoneWithoutLoadBalancerIngress() throws Exception {
        JsonNode svc = item("{\"metadata\":{\"namespace\":\"prod\",\"name\":\"web\"},"
                + "\"spec\":{\"type\":\"ClusterIP\",\"clusterIP\":\"10.96.0.1\",\"ports\":[]}}");
        assertEquals("<none>", KubernetesResourceRows.serviceRow(svc, NOW)[4]);
    }

    @Test
    void nodeRowDerivesStatusAndRoles() throws Exception {
        JsonNode node = item("{\"metadata\":{\"name\":\"node-1\",\"labels\":{"
                + "\"node-role.kubernetes.io/control-plane\":\"\",\"node-role.kubernetes.io/worker\":\"\","
                + "\"other\":\"x\"}},"
                + "\"status\":{\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}],"
                + "\"nodeInfo\":{\"kubeletVersion\":\"v1.28.0\",\"osImage\":\"Ubuntu 24.04\"}}}");
        Object[] row = KubernetesResourceRows.nodeRow(node, NOW);
        assertEquals("node-1", row[0]);
        assertEquals("Ready", row[1]);
        assertEquals("control-plane,worker", row[2]);
        assertEquals("v1.28.0", row[3]);
    }

    @Test
    void nodeRowNotReadyWhenConditionFalseOrMissing() throws Exception {
        JsonNode notReady = item("{\"metadata\":{\"name\":\"n\"},\"status\":{"
                + "\"conditions\":[{\"type\":\"Ready\",\"status\":\"False\"}]}}");
        assertEquals("NotReady", KubernetesResourceRows.nodeRow(notReady, NOW)[1]);

        JsonNode noConditions = item("{\"metadata\":{\"name\":\"n\"}}");
        assertEquals("NotReady", KubernetesResourceRows.nodeRow(noConditions, NOW)[1]);
        assertEquals("<none>", KubernetesResourceRows.nodeRow(noConditions, NOW)[2]);
    }

    @Test
    void rowsOfMapsItemsAndSkipsNullRows() throws Exception {
        String response = "{\"items\":[{\"metadata\":{\"name\":\"a\",\"namespace\":\"n\"}},"
                + "{\"metadata\":{\"name\":\"b\",\"namespace\":\"n\"}}]}";
        List<Object[]> rows = KubernetesResourceRows.rowsOf(response,
                item -> "a".equals(KubernetesResourceRows.nameOf(item)) ? null
                        : new Object[]{KubernetesResourceRows.nameOf(item)});
        assertEquals(1, rows.size());
        assertEquals("b", rows.get(0)[0]);
    }

    @Test
    void secretAndConfigMapRowsCountDataEntries() throws Exception {
        JsonNode secret = item("{\"metadata\":{\"namespace\":\"n\",\"name\":\"s\"},"
                + "\"type\":\"Opaque\",\"data\":{\"k1\":\"v\",\"k2\":\"v\"}}");
        assertArrayEquals(new Object[]{"n", "s", "Opaque", 2, "-"},
                KubernetesResourceRows.secretRow(secret, NOW));

        JsonNode cm = item("{\"metadata\":{\"namespace\":\"n\",\"name\":\"c\"},"
                + "\"data\":{\"a\":\"1\"}}");
        assertArrayEquals(new Object[]{"n", "c", 1, "-"},
                KubernetesResourceRows.configMapRow(cm, NOW));
    }
}
