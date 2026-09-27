package com.aqishi.toolbox.domain;

import com.aqishi.toolbox.infra.secrets.SecretBearing;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;

/**
 * Persisted Kubernetes cluster profile. Field names intentionally match the
 * legacy JSON payload stored under {@code k8s_manager_profiles}.
 */
public class KubernetesProfile implements SecretBearing {
    public String name;
    public String serverUrl;
    /** Bearer token; runtime/legacy only, the vault holds the saved copy. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String token;
    public boolean skipTls;
    public String clientCertData;
    /** PEM client private key; runtime/legacy only, the vault holds the saved copy. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
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

    /** Stable vault key; assigned on first load and persisted with the profile. */
    public String id;
    /** True when the secret is kept in the vault; the only secret-related value in preferences. */
    public boolean secretStored;
    /** Runtime only: loaded with a legacy plaintext secret that is not yet in the vault. */
    @JsonIgnore
    public boolean plaintextPending;

    @Override
    public String secretId() {
        return id;
    }

    @Override
    public void assignSecretId(String id) {
        this.id = id;
    }

    @Override
    public Map<String, String> secretFields() {
        Map<String, String> fields = new HashMap<>();
        if (token != null && !token.isEmpty()) fields.put("token", token);
        if (clientKeyData != null && !clientKeyData.isEmpty()) fields.put("clientKeyData", clientKeyData);
        return fields;
    }

    @Override
    public void applySecretFields(Map<String, String> fields) {
        token = fields == null ? null : fields.get("token");
        clientKeyData = fields == null ? null : fields.get("clientKeyData");
    }

    @Override
    public String primarySecretField() {
        return "token";
    }

    @Override
    public boolean secretStored() {
        return secretStored;
    }

    @Override
    public void markSecretStored(boolean stored) {
        this.secretStored = stored;
    }

    @Override
    public boolean plaintextPending() {
        return plaintextPending;
    }

    @Override
    public void markPlaintextPending(boolean pending) {
        this.plaintextPending = pending;
    }

    @Override
    public String secretLabel() {
        return name;
    }
}
