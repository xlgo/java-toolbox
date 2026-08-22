package com.aqishi.toolbox.feature.cloud.application;

import com.aqishi.toolbox.infra.kubernetes.KubernetesClient;

import javax.net.ssl.SSLSocketFactory;

/** Factory injected at the catalog boundary to create per-cluster services. */
public interface KubernetesServiceFactory {

    KubernetesService create(String serverUrl, String token,
                             boolean skipTlsVerification, SSLSocketFactory socketFactory);

    static KubernetesServiceFactory standard() {
        return (serverUrl, token, skipTlsVerification, socketFactory) ->
                new KubernetesService(new KubernetesClient(
                        serverUrl, token, skipTlsVerification, socketFactory));
    }
}
