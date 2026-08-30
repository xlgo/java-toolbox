package com.aqishi.toolbox.domain;

/**
 * Persisted Kubernetes cluster profile. Field names intentionally match the
 * legacy JSON payload stored under {@code k8s_manager_profiles}.
 */
public class KubernetesProfile {
    public String name;
    public String serverUrl;
    public String token;
    public boolean skipTls;
    public String clientCertData;
    public String clientKeyData;
    /** PEM-encoded cluster CA; when present it becomes the only trust anchor. */
    public String caCertData;

    public KubernetesProfile() {
    }

    public KubernetesProfile(String name, String serverUrl, String token, boolean skipTls) {
        this(name, serverUrl, token, skipTls, null, null, null);
    }

    public KubernetesProfile(String name, String serverUrl, String token, boolean skipTls,
                             String clientCertData, String clientKeyData) {
        this(name, serverUrl, token, skipTls, clientCertData, clientKeyData, null);
    }

    public KubernetesProfile(String name, String serverUrl, String token, boolean skipTls,
                             String clientCertData, String clientKeyData, String caCertData) {
        this.name = name;
        this.serverUrl = serverUrl;
        this.token = token;
        this.skipTls = skipTls;
        this.clientCertData = clientCertData;
        this.clientKeyData = clientKeyData;
        this.caCertData = caCertData;
    }
}
