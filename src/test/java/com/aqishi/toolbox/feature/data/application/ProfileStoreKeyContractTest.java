package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.domain.DatabaseProfile;
import com.aqishi.toolbox.domain.KafkaProfile;
import com.aqishi.toolbox.domain.RedisProfile;
import com.aqishi.toolbox.infra.database.DatabaseProfileStore;
import com.aqishi.toolbox.infra.redis.RedisProfileStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 钉住各 Profile 存储的<em>历史偏好键名</em>：这些键是已发布版本的用户数据合同，
 * 改名意味着老用户的连接配置静默丢失。
 */
class ProfileStoreKeyContractTest {

    private Preferences node;

    @BeforeEach
    void setUp() {
        node = Preferences.userRoot().node("toolbox-test-" + UUID.randomUUID());
    }

    @AfterEach
    void tearDown() throws Exception {
        node.removeNode();
    }

    @Test
    void kafkaProfilesPersistUnderLegacyKey() {
        KafkaProfileStore store = new KafkaProfileStore(node);
        store.save(Map.of("本地", new KafkaProfile("本地", "localhost:9092", "")));

        assertNotNull(node.get("kafka_profiles", null), "Kafka 配置必须写在 kafka_profiles 键下");
        assertEquals("localhost:9092", store.load().get("本地").bootstrapServers);
    }

    @Test
    void databaseProfilesPersistUnderLegacyKey() {
        DatabaseProfileStore store = new DatabaseProfileStore(node);
        store.save(Map.of("订单库", new DatabaseProfile("订单库", "mysql", "127.0.0.1",
                "3306", "orders", "root", "secret", null, null, null)));

        assertNotNull(node.get("db_profiles", null), "数据库配置必须写在 db_profiles 键下");
        DatabaseProfile loaded = store.load().get("订单库");
        assertEquals("mysql", loaded.dbType);
        assertEquals("3306", loaded.port);
        assertEquals("secret", loaded.password);
    }

    @Test
    void redisProfilesPersistUnderLegacyKey() {
        RedisProfileStore store = new RedisProfileStore(node);
        store.save(Map.of("缓存", new RedisProfile("缓存", "127.0.0.1", 6379, "pw", 2)));

        assertNotNull(node.get("redis_profiles", null), "Redis 配置必须写在 redis_profiles 键下");
        RedisProfile loaded = store.load().get("缓存");
        assertEquals(6379, loaded.port);
        assertEquals(2, loaded.db);
    }

    @Test
    void storesKeepInsertionOrderAcrossReload() {
        KafkaProfileStore store = new KafkaProfileStore(node);
        LinkedHashMap<String, KafkaProfile> profiles = new LinkedHashMap<>();
        profiles.put("z", new KafkaProfile("z", "z:9092", null));
        profiles.put("a", new KafkaProfile("a", "a:9092", null));
        store.save(profiles);

        assertEquals(java.util.List.of("z", "a"),
                java.util.List.copyOf(store.load().keySet()));
    }
}
