package com.aqishi.toolbox.feature.cloud.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KubernetesResourceRefTest {

    @Test
    void keepsRequiredIdentityFields() {
        KubernetesResourceRef ref = new KubernetesResourceRef("apps/v1", "prod", "deployments", "api");
        assertEquals("apps/v1", ref.getApiVersion());
        assertEquals("prod", ref.getNamespace());
        assertEquals("deployments", ref.getResourceType());
        assertEquals("api", ref.getName());
    }

    @Test
    void nullNamespaceBecomesEmptyStringForClusterScopedResources() {
        KubernetesResourceRef ref = new KubernetesResourceRef("v1", null, "nodes", "node-1");
        assertEquals("", ref.getNamespace());
    }

    @Test
    void rejectsBlankApiVersionResourceTypeAndName() {
        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesResourceRef(" ", "default", "pods", "p"));
        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesResourceRef("v1", "default", "", "p"));
        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesResourceRef("v1", "default", "pods", null));
        assertThrows(IllegalArgumentException.class,
                () -> new KubernetesResourceRef("v1", "default", "pods", "  "));
    }
}
