package com.aqishi.toolbox.infra.database;

import com.aqishi.toolbox.domain.DatabaseProfile;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager;
import com.aqishi.toolbox.infra.secrets.SecretProfileRepository;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Persistence adapter for the stable {@code db_profiles} preference payload.
 *
 * <p>Passwords are not written: profiles carry a stable id and a {@code secretStored}
 * flag, the value itself lives in the vault (see {@link SecretProfileRepository}).</p>
 */
public final class DatabaseProfileStore implements ProfileSecretManager.ProfileRepository<DatabaseProfile> {
    /** Vault namespace for these profiles' secrets. */
    public static final String SECRET_NAMESPACE = "db";

    private final SecretProfileRepository<DatabaseProfile> store;

    public DatabaseProfileStore(Preferences preferences) {
        this.store = new SecretProfileRepository<>(preferences, "db_profiles",
                new TypeReference<LinkedHashMap<String, DatabaseProfile>>() { });
    }

    @Override
    public LinkedHashMap<String, DatabaseProfile> load() {
        return store.load();
    }

    @Override
    public void save(Map<String, DatabaseProfile> profiles) {
        store.save(profiles);
    }
}
