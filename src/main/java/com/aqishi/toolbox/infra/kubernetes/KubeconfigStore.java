package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.feature.cloud.domain.KubernetesProfile;
import com.aqishi.toolbox.infra.config.JsonPreferencesStore;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Persistence boundary for cluster profiles. It retains the legacy preference
 * key and JSON field names so existing imported kubeconfig profiles keep
 * working after the panel refactor.
 */
public final class KubeconfigStore {

    private static final String KEY = "k8s_manager_profiles";
    private final JsonPreferencesStore<KubernetesProfile> store;

    public KubeconfigStore(Preferences preferences) {
        this.store = new JsonPreferencesStore<>(preferences, KEY,
                new TypeReference<LinkedHashMap<String, KubernetesProfile>>() { });
    }

    public LinkedHashMap<String, KubernetesProfile> load() {
        return store.load();
    }

    public void save(Map<String, KubernetesProfile> profiles) {
        store.save(profiles);
    }

    /** Returns profiles in their persisted navigation order. */
    public List<KubernetesProfile> list() {
        return Collections.unmodifiableList(new ArrayList<>(load().values()));
    }

    /** Saves one profile under its stable profile name. */
    public void save(KubernetesProfile profile) {
        if (profile == null || profile.name == null || profile.name.trim().isEmpty()) {
            throw new IllegalArgumentException("profile.name");
        }
        LinkedHashMap<String, KubernetesProfile> profiles = load();
        profiles.put(profile.name, profile);
        save(profiles);
    }

    /** Deletes one profile by its stable persisted name. */
    public void delete(String id) {
        if (id == null || id.trim().isEmpty()) return;
        LinkedHashMap<String, KubernetesProfile> profiles = load();
        profiles.remove(id);
        save(profiles);
    }
}
