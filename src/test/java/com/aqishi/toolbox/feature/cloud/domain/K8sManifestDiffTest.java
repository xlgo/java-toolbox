package com.aqishi.toolbox.feature.cloud.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.cloud.domain.K8sManifestDiff.*;
import org.junit.jupiter.api.Test;

import java.util.Set;

class K8sManifestDiffTest {

    private static final String LEFT =
            "apiVersion: apps/v1\n"
                    + "kind: Deployment\n"
                    + "metadata:\n"
                    + "  name: web-app\n"
                    + "  namespace: default\n"
                    + "  resourceVersion: '999'\n"
                    + "spec:\n"
                    + "  replicas: 2\n"
                    + "  template:\n"
                    + "    spec:\n"
                    + "      containers:\n"
                    + "      - name: nginx\n"
                    + "        image: nginx:1.20\n"
                    + "status:\n"
                    + "  availableReplicas: 2\n"
                    + "---\n"
                    + "apiVersion: v1\n"
                    + "kind: ConfigMap\n"
                    + "metadata:\n"
                    + "  name: old-config\n"
                    + "  namespace: default\n"
                    + "data:\n"
                    + "  k1: v1\n";

    private static final String RIGHT =
            "apiVersion: apps/v1\n"
                    + "kind: Deployment\n"
                    + "metadata:\n"
                    + "  name: web-app\n"
                    + "  namespace: default\n"
                    + "spec:\n"
                    + "  replicas: 3\n"
                    + "  template:\n"
                    + "    spec:\n"
                    + "      containers:\n"
                    + "      - name: nginx\n"
                    + "        image: nginx:1.21\n"
                    + "---\n"
                    + "apiVersion: v1\n"
                    + "kind: Service\n"
                    + "metadata:\n"
                    + "  name: web-svc\n"
                    + "  namespace: default\n"
                    + "spec:\n"
                    + "  ports:\n"
                    + "  - port: 80\n";

    @Test
    void alignsResourcesAndFiltersNoiseByDefault() {
        K8sManifestDiff diff = new K8sManifestDiff();
        DiffSummary summary = diff.compare(LEFT, RIGHT, K8sManifestDiff.DEFAULT_IGNORED_PATHS);

        assertEquals(2, summary.totalBefore());
        assertEquals(2, summary.totalAfter());
        assertEquals(1, summary.addedCount());    // web-svc
        assertEquals(1, summary.removedCount());  // old-config
        assertEquals(1, summary.modifiedCount()); // web-app
        assertEquals(0, summary.unchangedCount());

        // 验证 web-app 只检测到了 spec.replicas 和 image 的变化，status 和 resourceVersion 被忽略
        ResourceDiff webAppDiff = summary.resourceDiffs().stream()
                .filter(d -> d.key().name().equals("web-app"))
                .findFirst().orElseThrow();
        assertEquals(DiffType.MODIFIED, webAppDiff.type());

        boolean hasReplicasChange = webAppDiff.changes().stream().anyMatch(c -> c.path().contains("replicas"));
        boolean hasImageChange = webAppDiff.changes().stream().anyMatch(c -> c.path().contains("image"));
        boolean hasStatusChange = webAppDiff.changes().stream().anyMatch(c -> c.path().contains("status"));
        boolean hasRvChange = webAppDiff.changes().stream().anyMatch(c -> c.path().contains("resourceVersion"));

        assertTrue(hasReplicasChange);
        assertTrue(hasImageChange);
        assertFalse(hasStatusChange);
        assertFalse(hasRvChange);
    }

    @Test
    void supportsCustomIgnoredPath() {
        K8sManifestDiff diff = new K8sManifestDiff();
        // 额外忽略 $.spec.replicas
        String report = diff.compareToReport(LEFT, RIGHT, true, Set.of("$.spec.replicas"), "");

        assertFalse(report.contains("$.spec.replicas"), report);
        assertTrue(report.contains("nginx:1.21"), report);
    }

    @Test
    void supportsKeywordFiltering() {
        K8sManifestDiff diff = new K8sManifestDiff();
        String report = diff.compareToReport(LEFT, RIGHT, true, Set.of(), "Service");

        assertTrue(report.contains("web-svc"), report);
        assertFalse(report.contains("old-config"), report);
    }

    @Test
    void rejectsBothEmptyInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> new K8sManifestDiff().compareToReport("", "", true, Set.of(), ""));
    }
}
