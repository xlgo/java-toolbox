package com.aqishi.toolbox.feature.cloud.application;

import com.aqishi.toolbox.infra.ManagedResource;
import com.aqishi.toolbox.infra.kubernetes.KubernetesClient;

/**
 * Application boundary for Kubernetes resource actions.
 *
 * <p>It deliberately returns plain text because existing panels already own
 * YAML/JSON parsing and table projection. New use cases can add typed methods
 * here without coupling those views to HTTP transport.</p>
 */
public final class KubernetesService implements ManagedResource {

    private final KubernetesClient client;

    public KubernetesService(KubernetesClient client) {
        if (client == null) {
            throw new NullPointerException("client");
        }
        this.client = client;
    }

    public String request(String method, String apiPath, String body) throws Exception {
        return client.execute(method, apiPath, body);
    }

    @Override
    public boolean isOpen() {
        return client.isOpen();
    }

    @Override
    public void close() {
        client.close();
    }
}
