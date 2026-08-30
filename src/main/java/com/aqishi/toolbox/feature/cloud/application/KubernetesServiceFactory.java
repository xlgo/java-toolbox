package com.aqishi.toolbox.feature.cloud.application;

import com.aqishi.toolbox.infra.kubernetes.KubernetesClient;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

/**
 * Factory injected at the catalog boundary to create per-cluster services.
 *
 * <p>TLS material is passed in rather than derived from a flag, so the panel
 * that owns the user's verification choice is also the only place that can
 * weaken it.</p>
 */
public interface KubernetesServiceFactory {

    KubernetesService create(String serverUrl, String token,
                             SSLSocketFactory socketFactory, HostnameVerifier hostnameVerifier);

    static KubernetesServiceFactory standard() {
        return (serverUrl, token, socketFactory, hostnameVerifier) ->
                new KubernetesService(new KubernetesClient(
                        serverUrl, token, socketFactory, hostnameVerifier));
    }
}
