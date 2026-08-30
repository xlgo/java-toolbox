package com.aqishi.toolbox.domain;

/**
 * Persisted Kafka connection profile. Its JSON field names are part of the
 * existing {@code kafka_profiles} user-data contract.
 */
public class KafkaProfile {
    public String name;
    public String bootstrapServers;
    public String customProperties;

    public KafkaProfile() {
    }

    public KafkaProfile(String name, String bootstrapServers, String customProperties) {
        this.name = name;
        this.bootstrapServers = bootstrapServers;
        this.customProperties = customProperties;
    }
}
