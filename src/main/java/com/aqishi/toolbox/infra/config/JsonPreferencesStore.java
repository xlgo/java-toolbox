package com.aqishi.toolbox.infra.config;

import com.aqishi.toolbox.infra.InfrastructureException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * JSON map persistence over a caller-provided preferences node.
 *
 * <p>The caller owns the exact node and key, which keeps legacy user-data
 * locations stable while moving serialization out of Swing panels.</p>
 */
public final class JsonPreferencesStore<T> {

    private final Preferences preferences;
    private final String key;
    private final TypeReference<LinkedHashMap<String, T>> mapType;
    private final ObjectMapper mapper;

    public JsonPreferencesStore(Preferences preferences, String key,
                                TypeReference<LinkedHashMap<String, T>> mapType) {
        if (preferences == null) throw new NullPointerException("preferences");
        if (key == null || key.trim().isEmpty()) throw new IllegalArgumentException("key");
        if (mapType == null) throw new NullPointerException("mapType");
        this.preferences = preferences;
        this.key = key;
        this.mapType = mapType;
        this.mapper = new ObjectMapper();
    }

    public LinkedHashMap<String, T> load() {
        String json = preferences.get(key, null);
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, T> values = mapper.readValue(json, mapType);
            return values == null ? new LinkedHashMap<String, T>() : values;
        } catch (Exception error) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "无法读取本地配置 " + key, error);
        }
    }

    public void save(Map<String, T> values) {
        try {
            preferences.put(key, mapper.writeValueAsString(new LinkedHashMap<>(values)));
        } catch (Exception error) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "无法保存本地配置 " + key, error);
        }
    }
}
