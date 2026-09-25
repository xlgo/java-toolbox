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

    /** 回归：两个带证书的 K8s 配置就超过 Preferences 单值 8192 字符上限，旧实现保存直接抛异常。 */
    @Test
    void payloadsLargerThanPreferencesLimitRoundTrip() throws Exception {
        LinkedHashMap<String, KafkaProfile> profiles = new LinkedHashMap<>();
        String pem = "-----BEGIN CERTIFICATE-----\n" + "A".repeat(6000) + "\n-----END CERTIFICATE-----";
        for (int i = 0; i < 4; i++) {
            profiles.put("cluster-" + i, new KafkaProfile("cluster-" + i, "h" + i + ":9092", pem + i));
        }

        store.save(profiles);

        LinkedHashMap<String, KafkaProfile> loaded = store.load();
        assertEquals(4, loaded.size());
        assertEquals(pem + "3", loaded.get("cluster-3").customProperties);
        for (String key : node.keys()) {
            assertTrue(node.get(key, "").length() <= Preferences.MAX_VALUE_LENGTH, key);
        }
    }

    @Test
    void shrinkingBackToInlineRemovesStaleChunks() throws Exception {
        LinkedHashMap<String, KafkaProfile> big = new LinkedHashMap<>();
        big.put("big", new KafkaProfile("big", "h:9092", "x".repeat(20000)));
        store.save(big);
        assertTrue(node.keys().length > 1);

        LinkedHashMap<String, KafkaProfile> small = new LinkedHashMap<>();
        small.put("small", new KafkaProfile("small", "h:9092", null));
        store.save(small);

        assertEquals(List.of("profiles"), List.of(node.keys()));
        assertEquals(List.of("small"), List.copyOf(store.load().keySet()));
    }

    @Test
    void rewritingAChunkedPayloadDropsThePreviousGeneration() throws Exception {
        LinkedHashMap<String, KafkaProfile> first = new LinkedHashMap<>();
        first.put("a", new KafkaProfile("a", "h:9092", "1".repeat(20000)));
        store.save(first);
        int keysAfterFirst = node.keys().length;

        LinkedHashMap<String, KafkaProfile> second = new LinkedHashMap<>();
        second.put("b", new KafkaProfile("b", "h:9092", "2".repeat(20000)));
        store.save(second);

        assertEquals(keysAfterFirst, node.keys().length);
        assertEquals("2".repeat(20000), store.load().get("b").customProperties);
    }

    /** 已有用户的数据是单值写法，升级后必须照常读出。 */
    @Test
    void readsLegacyInlinePayload() {
        node.put("profiles", "{\"x\":{\"name\":\"x\",\"bootstrapServers\":\"h:1\"}}");

        assertEquals("h:1", store.load().get("x").bootstrapServers);
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
