package com.aqishi.toolbox.infra.config;

import com.aqishi.toolbox.domain.KafkaProfile;
import com.aqishi.toolbox.infra.InfrastructureException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

class JsonPreferencesStoreTest {

    private Preferences node;
    private JsonPreferencesStore<KafkaProfile> store;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("toolbox-test-" + UUID.randomUUID());
        store = new JsonPreferencesStore<>(node, "profiles",
                new TypeReference<LinkedHashMap<String, KafkaProfile>>() { });
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    void loadReturnsEmptyMapWhenKeyMissing() {
        assertTrue(store.load().isEmpty());
    }

    @Test
    void saveThenLoadRoundTripsValuesInOrder() {
        LinkedHashMap<String, KafkaProfile> profiles = new LinkedHashMap<>();
        profiles.put("b-集群", new KafkaProfile("b-集群", "b:9092", "sasl=true"));
        profiles.put("a-集群", new KafkaProfile("a-集群", "a:9092", null));
        store.save(profiles);

        LinkedHashMap<String, KafkaProfile> loaded = store.load();
        assertEquals(List.of("b-集群", "a-集群"), List.copyOf(loaded.keySet()));
        assertEquals("b:9092", loaded.get("b-集群").bootstrapServers);
        assertEquals("sasl=true", loaded.get("b-集群").customProperties);
        assertNull(loaded.get("a-集群").customProperties);
    }

    @Test
    void corruptedJsonRaisesConfigurationError() {
        node.put("profiles", "{not json");
        InfrastructureException error = assertThrows(InfrastructureException.class, store::load);
        assertEquals(InfrastructureException.Kind.CONFIGURATION, error.getKind());
    }

    @Test
    void constructorRejectsInvalidArguments() {
        assertThrows(NullPointerException.class,
                () -> new JsonPreferencesStore<>(null, "k",
                        new TypeReference<LinkedHashMap<String, String>>() { }));
        assertThrows(IllegalArgumentException.class,
                () -> new JsonPreferencesStore<>(node, "  ",
                        new TypeReference<LinkedHashMap<String, String>>() { }));
        assertThrows(NullPointerException.class,
                () -> new JsonPreferencesStore<>(node, "k", null));
    }

    @Test
    void saveNullValueEntriesSurviveRoundTrip() {
        Map<String, KafkaProfile> profiles = new LinkedHashMap<>();
        profiles.put("empty", new KafkaProfile());
        store.save(profiles);
        KafkaProfile loaded = store.load().get("empty");
        assertNotNull(loaded);
        assertNull(loaded.name);
    }
}
