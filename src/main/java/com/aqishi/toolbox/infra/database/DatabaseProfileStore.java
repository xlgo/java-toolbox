package com.aqishi.toolbox.infra.database;

import com.aqishi.toolbox.domain.DatabaseProfile;
import com.aqishi.toolbox.infra.config.JsonPreferencesStore;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/** Persistence adapter for the stable {@code db_profiles} preference payload. */
public final class DatabaseProfileStore {
    private final JsonPreferencesStore<DatabaseProfile> store;

    public DatabaseProfileStore(Preferences preferences) {
        this.store = new JsonPreferencesStore<>(preferences, "db_profiles",
                new TypeReference<LinkedHashMap<String, DatabaseProfile>>() { });
    }

    public LinkedHashMap<String, DatabaseProfile> load() {
        return store.load();
    }

    public void save(Map<String, DatabaseProfile> profiles) {
        store.save(profiles);
    }
}
