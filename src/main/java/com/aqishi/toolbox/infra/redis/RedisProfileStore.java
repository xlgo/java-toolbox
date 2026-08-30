package com.aqishi.toolbox.infra.redis;

import com.aqishi.toolbox.domain.RedisProfile;
import com.aqishi.toolbox.infra.config.JsonPreferencesStore;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/** Persistence adapter for the stable {@code redis_profiles} preference payload. */
public final class RedisProfileStore {
    private final JsonPreferencesStore<RedisProfile> store;

    public RedisProfileStore(Preferences preferences) {
        this.store = new JsonPreferencesStore<>(preferences, "redis_profiles",
                new TypeReference<LinkedHashMap<String, RedisProfile>>() { });
    }

    public LinkedHashMap<String, RedisProfile> load() {
        return store.load();
    }

    public void save(Map<String, RedisProfile> profiles) {
        store.save(profiles);
    }
}
