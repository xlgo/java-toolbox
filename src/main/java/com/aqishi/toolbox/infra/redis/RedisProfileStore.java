package com.aqishi.toolbox.infra.redis;

import com.aqishi.toolbox.domain.RedisProfile;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager;
import com.aqishi.toolbox.infra.secrets.SecretProfileRepository;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Persistence adapter for the stable {@code redis_profiles} preference payload.
 *
 * <p>Passwords are not written: profiles carry a stable id and a {@code secretStored}
 * flag, the value itself lives in the vault (see {@link SecretProfileRepository}).</p>
 */
public final class RedisProfileStore implements ProfileSecretManager.ProfileRepository<RedisProfile> {
    /** Vault namespace for these profiles' secrets. */
    public static final String SECRET_NAMESPACE = "redis";

    private final SecretProfileRepository<RedisProfile> store;

    public RedisProfileStore(Preferences preferences) {
        this.store = new SecretProfileRepository<>(preferences, "redis_profiles",
                new TypeReference<LinkedHashMap<String, RedisProfile>>() { });
    }

    @Override
    public LinkedHashMap<String, RedisProfile> load() {
        return store.load();
    }

    @Override
    public void save(Map<String, RedisProfile> profiles) {
        store.save(profiles);
    }
}
