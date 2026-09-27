package com.aqishi.toolbox.infra.secrets;

import com.aqishi.toolbox.infra.config.JsonPreferencesStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;

/**
 * Name-keyed profile map in preferences that never writes vault-managed secrets.
 *
 * <p>On load every profile gets a stable {@link SecretBearing#secretId()} (persisted
 * right away when it was missing) and a profile that still carries a legacy plaintext
 * secret is flagged {@link SecretBearing#plaintextPending()}. On save each profile is
 * written as a copy whose secret fields are cleared, <em>except</em> for profiles whose
 * legacy plaintext has not been migrated yet: dropping those before they reach the
 * vault would silently lose the user's saved password.</p>
 */
public final class SecretProfileRepository<P extends SecretBearing>
        implements ProfileSecretManager.ProfileRepository<P> {

    private final JsonPreferencesStore<P> store;
    private final ObjectMapper mapper = new ObjectMapper();

    public SecretProfileRepository(Preferences preferences, String key,
                                   TypeReference<LinkedHashMap<String, P>> type) {
        this.store = new JsonPreferencesStore<>(preferences, key, type);
    }

    @Override
    public LinkedHashMap<String, P> load() {
        LinkedHashMap<String, P> profiles = store.load();
        boolean assigned = false;
        for (P profile : profiles.values()) {
            if (profile == null) continue;
            if (profile.secretId() == null || profile.secretId().trim().isEmpty()) {
                profile.assignSecretId(UUID.randomUUID().toString());
                assigned = true;
            }
            profile.markPlaintextPending(!SecretFields.clean(profile.secretFields()).isEmpty());
        }
        profiles.values().removeIf(profile -> profile == null);
        if (assigned) {
            // Persist new ids before anything is keyed by them, so a later migration
            // and the profile always agree on the vault key. Pending plaintext is kept.
            try {
                save(profiles);
            } catch (RuntimeException ignored) {
                // Ids are regenerated next time; nothing has been stored under them yet.
            }
        }
        return profiles;
    }

    @Override
    public void save(Map<String, P> profiles) {
        LinkedHashMap<String, P> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, P> entry : profiles.entrySet()) {
            P profile = entry.getValue();
            if (profile == null) continue;
            sanitized.put(entry.getKey(), sanitizedCopy(profile));
        }
        store.save(sanitized);
    }

    @SuppressWarnings("unchecked")
    private P sanitizedCopy(P profile) {
        P copy = (P) mapper.convertValue(profile, profile.getClass());
        if (!profile.plaintextPending()) {
            copy.applySecretFields(null);
        }
        return copy;
    }
}
