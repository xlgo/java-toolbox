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

    /** 回归：多文档清单只读第一个文档，其余资源被静默丢弃，界面仍提示"发布成功"。 */
    @Test
    void preparesEveryDocumentInAMultiDocumentManifest() throws Exception {
        java.util.List<KubernetesResourceApplyService.ApplyPlan> plans = service.prepareAll(
                "---\n"
                        + "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: cfg\n"
                        + "---\n"
                        + "apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: api\n"
                        + "---\n"
                        + "apiVersion: v1\nkind: Service\nmetadata:\n  name: api-svc\n"
                        + "---\n",
                "prod");

        assertEquals(3, plans.size());
        assertEquals("/api/v1/namespaces/prod/configmaps/cfg", plans.get(0).resourcePath());
        assertEquals("/apis/apps/v1/namespaces/prod/deployments/api", plans.get(1).resourcePath());
        assertEquals("/api/v1/namespaces/prod/services/api-svc", plans.get(2).resourcePath());
    }

    /** 任何一个文档不合法都要在发出第一个请求前失败，并指出是第几个。 */
    @Test
    void rejectsWholeManifestWhenAnyDocumentIsInvalid() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.prepareAll(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: ok\n---\nkind: Service\n", "default"));

        assertTrue(error.getMessage().contains("2"), error.getMessage());
    }

    @Test
    void singleDocumentPrepareRefusesMultipleDocuments() {
        assertThrows(IllegalArgumentException.class, () -> service.prepare(
                "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: a\n---\n"
                        + "apiVersion: v1\nkind: ConfigMap\nmetadata:\n  name: b\n", "default"));
    }

    /** 回归：一律加 s 且当作命名空间资源，NetworkPolicy 成了 networkpolicys，ClusterRole 被发到 /namespaces 下。 */
    @Test
    void infersPluralAndScopeForUnlistedKinds() throws Exception {
        assertTrue(service.prepare("apiVersion: networking.k8s.io/v1\nkind: NetworkPolicy\nmetadata:\n  name: np\n",
                "default").resourcePath().endsWith("/namespaces/default/networkpolicies/np"));
        assertEquals("/apis/rbac.authorization.k8s.io/v1/clusterroles/reader", service.prepare(
                "apiVersion: rbac.authorization.k8s.io/v1\nkind: ClusterRole\nmetadata:\n  name: reader\n",
                "default").resourcePath());
        assertEquals("/apis/storage.k8s.io/v1/storageclasses/fast", service.prepare(
                "apiVersion: storage.k8s.io/v1\nkind: StorageClass\nmetadata:\n  name: fast\n",
                "default").resourcePath());
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
