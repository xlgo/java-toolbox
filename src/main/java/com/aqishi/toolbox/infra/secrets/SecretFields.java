package com.aqishi.toolbox.infra.secrets;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Codec between a profile's named secret fields and the single opaque string stored
 * per profile in the vault (a small JSON object such as {@code {"password":"..."}}).
 *
 * <p>Failures never echo the input: a value that cannot be decoded is treated as absent.</p>
 */
public final class SecretFields {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<TreeMap<String, String>> TYPE =
            new TypeReference<TreeMap<String, String>>() { };

    private SecretFields() {
    }

    /** Drops null/empty entries; returns an empty map for null input. */
    public static Map<String, String> clean(Map<String, String> fields) {
        TreeMap<String, String> cleaned = new TreeMap<>();
        if (fields == null) return cleaned;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                cleaned.put(entry.getKey(), entry.getValue());
            }
        }
        return cleaned;
    }

    public static String encode(Map<String, String> fields) {
        try {
            return MAPPER.writeValueAsString(clean(fields));
        } catch (Exception error) {
            // Never attach the cause: its message may quote the value being encoded.
            throw new IllegalStateException("Unable to encode connection secret");
        }
    }

    public static Map<String, String> decode(String stored) {
        if (stored == null || stored.isEmpty()) return Collections.emptyMap();
        try {
            TreeMap<String, String> decoded = MAPPER.readValue(stored, TYPE);
            return decoded == null ? Collections.<String, String>emptyMap() : clean(decoded);
        } catch (Exception malformed) {
            return Collections.emptyMap();
        }
    }
}
