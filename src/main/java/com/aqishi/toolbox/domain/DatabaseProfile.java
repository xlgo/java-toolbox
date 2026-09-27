package com.aqishi.toolbox.domain;

import com.aqishi.toolbox.infra.secrets.SecretBearing;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.Map;

/**
 * Persisted JDBC connection profile. The mutable public fields preserve the
 * previous JSON schema used by {@code db_profiles}.
 *
 * <p>{@code password} is runtime/legacy only: it is read from older payloads for the
 * one-time vault migration and is never written unless that migration is still pending.</p>
 */
public class DatabaseProfile implements SecretBearing {
    public String name;
    public String dbType;
    public String host;
    public String port;
    public String database;
    public String username;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public String password;
    public String driverClass;
    public String url;
    public String jarPath;

    public DatabaseProfile() {
    }

    public DatabaseProfile(String name, String dbType, String host, String port,
                           String database, String username, String password,
                           String driverClass, String url, String jarPath) {
        this.name = name;
        this.dbType = dbType;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.driverClass = driverClass;
        this.url = url;
        this.jarPath = jarPath;
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
