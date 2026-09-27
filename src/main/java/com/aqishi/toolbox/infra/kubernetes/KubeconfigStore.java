package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.domain.KubernetesProfile;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager;
import com.aqishi.toolbox.infra.secrets.SecretProfileRepository;
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
 *
 * <p>Bearer tokens and client private keys (including those imported from a
 * kubeconfig) are not written here; they live in the vault under
 * {@link #SECRET_NAMESPACE}. See {@link SecretProfileRepository}.</p>
 */
public final class KubeconfigStore implements ProfileSecretManager.ProfileRepository<KubernetesProfile> {

    /** Vault namespace for token / client-key secrets. */
    public static final String SECRET_NAMESPACE = "k8s";
    private static final String KEY = "k8s_manager_profiles";
    private final SecretProfileRepository<KubernetesProfile> store;

    public KubeconfigStore(Preferences preferences) {
        this.store = new SecretProfileRepository<>(preferences, KEY,
                new TypeReference<LinkedHashMap<String, KubernetesProfile>>() { });
    }

    @Override
    public LinkedHashMap<String, KubernetesProfile> load() {
        return store.load();
    }

    @Override
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
