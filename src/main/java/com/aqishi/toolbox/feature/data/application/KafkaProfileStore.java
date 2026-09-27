package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.domain.KafkaProfile;
import com.aqishi.toolbox.infra.secrets.ProfileSecretManager;
import com.aqishi.toolbox.infra.secrets.SecretProfileRepository;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * Retains Kafka profile persistence outside the Swing adapter.
 *
 * <p>Credential property lines (SASL JAAS config, SSL key passwords ...) are not written:
 * they live in the vault, the profile keeps a {@code secretStored} flag and its id.</p>
 */
public final class KafkaProfileStore implements ProfileSecretManager.ProfileRepository<KafkaProfile> {
    /** Vault namespace for these profiles' secrets. */
    public static final String SECRET_NAMESPACE = "kafka";

    private final SecretProfileRepository<KafkaProfile> store;

    public KafkaProfileStore(Preferences preferences) {
        this.store = new SecretProfileRepository<>(preferences, "kafka_profiles",
                new TypeReference<LinkedHashMap<String, KafkaProfile>>() { });
    }

    @Override
    public LinkedHashMap<String, KafkaProfile> load() {
        return store.load();
    }

    @Override
    public void save(Map<String, KafkaProfile> profiles) {
        store.save(profiles);
    }
}
