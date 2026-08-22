package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.KafkaProfile;
import com.aqishi.toolbox.infra.config.JsonPreferencesStore;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/** Retains Kafka profile persistence outside the Swing adapter. */
public final class KafkaProfileStore {
    private final JsonPreferencesStore<KafkaProfile> store;

    public KafkaProfileStore(Preferences preferences) {
        this.store = new JsonPreferencesStore<>(preferences, "kafka_profiles",
                new TypeReference<LinkedHashMap<String, KafkaProfile>>() { });
    }

    public LinkedHashMap<String, KafkaProfile> load() {
        return store.load();
    }

    public void save(Map<String, KafkaProfile> profiles) {
        store.save(profiles);
    }
}
