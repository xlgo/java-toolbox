package com.aqishi.toolbox.feature.cloud.domain;

/**
 * Immutable identity of a resource addressed through the Kubernetes API.
 */
public final class KubernetesResourceRef {

    private final String apiVersion;
    private final String namespace;
    private final String resourceType;
    private final String name;

    public KubernetesResourceRef(String apiVersion, String namespace,
                                 String resourceType, String name) {
        this.apiVersion = require(apiVersion, "apiVersion");
        this.namespace = namespace == null ? "" : namespace;
        this.resourceType = require(resourceType, "resourceType");
        this.name = require(name, "name");
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getName() {
        return name;
    }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
