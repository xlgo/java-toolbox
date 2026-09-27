package com.aqishi.toolbox.domain;

import com.aqishi.toolbox.infra.secrets.SecretBearing;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;

/**
 * Persisted Redis connection profile. Field names preserve the existing
 * {@code redis_profiles} payload consumed by earlier releases.
 */
public class RedisProfile implements SecretBearing {
    public String name;
    public String host;
    public int port;
    /** Runtime/legacy only; see {@link DatabaseProfile#password}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String password;
    public int db;

    public RedisProfile() {
    }

    public RedisProfile(String name, String host, int port, String password, int db) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.password = password;
        this.db = db;
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
        if (password != null && !password.isEmpty()) fields.put("password", password);
        return fields;
    }

    @Override
    public void applySecretFields(Map<String, String> fields) {
        password = fields == null ? null : fields.get("password");
    }

    @Override
    public String primarySecretField() {
        return "password";
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
