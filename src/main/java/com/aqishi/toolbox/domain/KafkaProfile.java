package com.aqishi.toolbox.domain;

import com.aqishi.toolbox.infra.secrets.SecretBearing;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashMap;
import java.util.Map;

/**
 * Persisted Kafka connection profile. Its JSON field names are part of the
 * existing {@code kafka_profiles} user-data contract.
 *
 * <p>Credential lines inside {@code customProperties} (see {@link KafkaSensitiveProperties})
 * are the profile's secret: at runtime they are part of the text, in preferences only the
 * public lines are written and the credential lines live in the vault.</p>
 */
public class KafkaProfile implements SecretBearing {
    /** Secret field name holding the credential property lines. */
    public static final String SECRET_PROPERTIES = "properties";

    public String name;
    public String bootstrapServers;
    public String customProperties;
    /** Stable vault key; assigned on first load and persisted with the profile. */
    public String id;
    /** True when credential lines are kept in the vault. */
    public boolean secretStored;
    /** Runtime only: loaded with credential lines that are not yet in the vault. */
    @JsonIgnore
    public boolean plaintextPending;

    public KafkaProfile() {
    }

    public KafkaProfile(String name, String bootstrapServers, String customProperties) {
        this.name = name;
        this.bootstrapServers = bootstrapServers;
        this.customProperties = customProperties;
    }

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
        String sensitive = KafkaSensitiveProperties.sensitivePart(customProperties);
        if (!sensitive.isEmpty()) fields.put(SECRET_PROPERTIES, sensitive);
        return fields;
    }

    @Override
    public void applySecretFields(Map<String, String> fields) {
        if (customProperties == null && (fields == null || fields.isEmpty())) return;
        String publicText = KafkaSensitiveProperties.publicPart(customProperties);
        customProperties = KafkaSensitiveProperties.merge(publicText,
                fields == null ? null : fields.get(SECRET_PROPERTIES));
    }

    @Override
    public String primarySecretField() {
        return SECRET_PROPERTIES;
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
