package com.aqishi.toolbox.feature.cloud.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KubernetesResourceApplyServiceTest {

    private final KubernetesResourceApplyService service = new KubernetesResourceApplyService();

    @Test
    void preparesNamespacedDeploymentPathAndJsonBody() throws Exception {
        KubernetesResourceApplyService.ApplyPlan plan = service.prepare(
                "apiVersion: apps/v1\n"
                        + "kind: Deployment\n"
                        + "metadata:\n"
                        + "  name: api\n"
                        + "spec:\n"
                        + "  replicas: 2\n",
                "staging");

        assertEquals("/apis/apps/v1/namespaces/staging/deployments/api", plan.resourcePath());
        assertEquals("/apis/apps/v1/namespaces/staging/deployments", plan.collectionPath());
        assertTrue(plan.jsonBody().contains("\"replicas\":2"));
    }

    @Test
    void preparesClusterScopedNodeWithoutNamespace() throws Exception {
        KubernetesResourceApplyService.ApplyPlan plan = service.prepare(
                "apiVersion: v1\nkind: Node\nmetadata:\n  name: node-1\n", "all");

        assertEquals("/api/v1/nodes/node-1", plan.resourcePath());
        assertEquals("", plan.namespace());
        assertTrue(!plan.namespaced());
    }

    @Test
    void rejectsMissingIdentityAndPathTraversal() {
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("apiVersion: v1\nkind: Pod\nmetadata: {}\n", "default"));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("kind: Pod\nmetadata:\n  name: safe\n", "default"));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("apiVersion: v1\nkind: Deployment\nmetadata:\n  name: safe\n", "default"));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("apiVersion: v1\nkind: Pod\nmetadata:\n  name: ../pod\n", "default"));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepare("apiVersion: apps/v1/../../secrets\nkind: Widget\nmetadata:\n  name: safe\n", "default"));
    }

    @Test
    void acceptsLongDnsSafeResourceNames() throws Exception {
        String name = "a".repeat(253);
        KubernetesResourceApplyService.ApplyPlan plan = service.prepare(
                "apiVersion: example.io/v1\nkind: Widget\nmetadata:\n  name: " + name + "\n", "default");

        assertTrue(plan.resourcePath().endsWith("/widgets/" + name));
    }

    @Test
    void mergesExistingResourceVersionIntoPutBody() throws Exception {
        KubernetesResourceApplyService.ApplyPlan plan = service.prepare(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: settings\ndata:\n  mode: test\n",
                "default");

        String body = service.updateResourceVersion(plan,
                "{\"metadata\":{\"resourceVersion\":\"42\"}}");

        assertTrue(body.contains("\"resourceVersion\":\"42\""));
        assertTrue(body.contains("\"mode\":\"test\""));
    }
}
