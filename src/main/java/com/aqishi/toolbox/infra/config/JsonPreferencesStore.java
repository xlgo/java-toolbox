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
 *
 * <p>{@link Preferences} caps a single value at {@link Preferences#MAX_VALUE_LENGTH}
 * (8192) characters. A couple of Kubernetes profiles with embedded CA, client
 * certificate and key PEMs exceed that, and the plain {@code put} then threw on
 * every save. Oversized payloads are therefore split across generation-tagged
 * chunk keys: the new chunks are written first, the main key is then switched
 * to point at them, and only afterwards is the previous generation removed, so
 * an interrupted save leaves the last complete payload readable. Payloads that
 * fit keep the original single-value layout, so existing data loads unchanged.</p>
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
        String json = readPayload();
        if (json == null || json.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            LinkedHashMap<String, T> values = mapper.readValue(json, mapType);
            return values == null ? new LinkedHashMap<String, T>() : values;
        } catch (Exception error) {
            throw unreadable(error);
        }
    }

    public void save(Map<String, T> values) {
        try {
            writePayload(mapper.writeValueAsString(new LinkedHashMap<>(values)));
        } catch (Exception error) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "无法保存本地配置 " + key, error);
        }
    }

    /** Main-key marker for a chunked payload: {@code prefix + generation + ":" + chunkCount}. */
    private static final String CHUNK_MARKER = "@chunked:";
    /** Leaves headroom below MAX_VALUE_LENGTH so the limit is never hit exactly. */
    static final int CHUNK_SIZE = Preferences.MAX_VALUE_LENGTH - 192;

    private String readPayload() {
        String main = preferences.get(key, null);
        if (main == null || !main.startsWith(CHUNK_MARKER)) {
            return main;
        }
        String[] parts = main.substring(CHUNK_MARKER.length()).split(":");
        try {
            long generation = Long.parseLong(parts[0]);
            int count = Integer.parseInt(parts[1]);
            StringBuilder json = new StringBuilder(count * CHUNK_SIZE);
            for (int i = 0; i < count; i++) {
                String chunk = preferences.get(chunkKey(generation, i), null);
                if (chunk == null) {
                    throw new IllegalStateException("missing chunk " + i + " of " + count);
                }
                json.append(chunk);
            }
            return json.toString();
        } catch (RuntimeException malformed) {
            throw unreadable(malformed);
        }
    }

    private InfrastructureException unreadable(Exception cause) {
        return new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                "无法读取本地配置 " + key, cause);
    }

    private void writePayload(String json) throws Exception {
        long previous = currentGeneration();
        if (json.length() <= CHUNK_SIZE) {
            preferences.put(key, json);
        } else {
            long generation = previous + 1;
            int count = (json.length() + CHUNK_SIZE - 1) / CHUNK_SIZE;
            for (int i = 0; i < count; i++) {
                int start = i * CHUNK_SIZE;
                preferences.put(chunkKey(generation, i), json.substring(start, Math.min(json.length(), start + CHUNK_SIZE)));
            }
            preferences.put(key, CHUNK_MARKER + generation + ":" + count);
        }
        if (previous >= 0) {
            removeGeneration(previous);
        }
        preferences.flush();
    }

    /** Generation referenced by the main key, or -1 when the payload is stored inline. */
    private long currentGeneration() {
        String main = preferences.get(key, null);
        if (main == null || !main.startsWith(CHUNK_MARKER)) {
            return -1;
        }
        try {
            return Long.parseLong(main.substring(CHUNK_MARKER.length()).split(":")[0]);
        } catch (RuntimeException malformed) {
            return -1;
        }
    }

    private void removeGeneration(long generation) throws Exception {
        String prefix = key + ".g" + generation + ".";
        for (String existing : preferences.keys()) {
            if (existing.startsWith(prefix)) {
                preferences.remove(existing);
            }
        }
    }

    private String chunkKey(long generation, int index) {
        return key + ".g" + generation + "." + index;
    }
}
