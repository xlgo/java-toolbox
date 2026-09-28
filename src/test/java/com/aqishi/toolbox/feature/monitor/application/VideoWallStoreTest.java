package com.aqishi.toolbox.feature.monitor.application;

import com.aqishi.toolbox.feature.monitor.domain.VideoSource;
import com.aqishi.toolbox.feature.monitor.domain.VideoWallModel;
import com.aqishi.toolbox.feature.monitor.domain.WallLayout;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 持久化：布局与全部通道（含隐藏通道）往返一致，密码走保险库、配置里只留占位符。 */
class VideoWallStoreTest {

    private final Map<String, String> settings = new LinkedHashMap<>();
    private final MemorySecretStore secrets = new MemorySecretStore();

    private VideoWallStore store() {
        return new VideoWallStore(settings(), secrets);
    }

    private VideoWallStore.Settings settings() {
        return new VideoWallStore.Settings() {
            @Override
            public String get(String key, String def) {
                return settings.getOrDefault(key, def);
            }

            @Override
            public void set(String key, String value) {
                settings.put(key, value);
            }

            @Override
            public boolean save() {
                return true;
            }
        };
    }

    private static VideoSource credentialed(String id, String name, String url) {
        return new VideoSource(id, name, url, true);
    }

    @Test
    @DisplayName("往返：布局、通道、播放状态与选中项都能读回来")
    void roundTripsLayoutAndChannels() {
        VideoWallStore store = store();
        VideoWallModel model = new VideoWallModel(WallLayout.preset(1));
        model.assign(0, credentialed("a", "大门", "rtsp://10.0.0.1:554/stream"));
        model.assign(1, credentialed("b", "停车场", "rtsp://10.0.0.2:554/stream").withPlaying(false));
        model.setLayout(WallLayout.preset(0));   // b 变成隐藏通道

        store.save(model, "saved:我的布局");

        VideoWallStore.Saved saved = store.load();
        assertNotNull(saved);
        assertEquals("saved:我的布局", saved.selection());
        assertEquals(1, saved.model().cellCount());
        assertEquals("a", saved.model().source(0).id());
        assertEquals(List.of("b"), saved.model().hiddenSources().stream().map(VideoSource::id).toList());
        assertFalse(saved.model().hiddenSources().get(0).playing());
        assertEquals("大门", saved.model().source(0).name());
    }

    @Test
    @DisplayName("凭据：配置里只存占位符，密码在保险库，读回来能还原")
    void storesCredentialsInTheVaultOnly() {
        VideoWallStore store = store();
        VideoWallModel model = new VideoWallModel(WallLayout.preset(0));
        model.assign(0, credentialed("a", "大门", "rtsp://admin:s3cret@10.0.0.1:554/stream"));

        store.save(model, "preset:0").join();

        String json = settings.get("monitor.wall");
        assertNotNull(json);
        assertFalse(json.contains("s3cret"), json);
        assertTrue(json.contains("{{vault:"), json);
        assertEquals(1, secrets.values.size());

        VideoWallModel restored = store.load().model();
        assertEquals("rtsp://admin:s3cret@10.0.0.1:554/stream", restored.source(0).url());
        assertEquals("rtsp://admin:******@10.0.0.1:554/stream", restored.source(0).displayUrl());
    }

    @Test
    @DisplayName("保险库锁定时：配置里不出现密码，源本身仍可用（凭据未还原）")
    void lockedVaultKeepsCredentialsInMemoryOnly() {
        VideoWallStore store = store();
        secrets.status = SecretStore.Status.LOCKED;
        VideoWallModel model = new VideoWallModel(WallLayout.preset(0));
        model.assign(0, credentialed("a", "大门", "rtsp://admin:s3cret@10.0.0.1:554/stream"));

        store.save(model, "preset:0").join();

        assertFalse(settings.get("monitor.wall").contains("s3cret"));
        assertTrue(secrets.values.isEmpty());
    }

    @Test
    @DisplayName("打开保险库后：占位符还原，密码写入保险库")
    void unlockingResolvesAndPersistsCredentials() {
        VideoWallStore store = store();
        secrets.status = SecretStore.Status.LOCKED;
        VideoWallModel model = new VideoWallModel(WallLayout.preset(0));
        VideoSource source = credentialed("a", "大门", "rtsp://admin:s3cret@10.0.0.1:554/stream");
        model.assign(0, source);
        store.save(model, "preset:0").join();   // 只有明文在内存里

        secrets.status = SecretStore.Status.UNLOCKED;
        // 内存中的源仍是明文，保存一次即写入保险库
        store.save(model, "preset:0").join();

        assertEquals(1, secrets.values.size());
        assertFalse(settings.get("monitor.wall").contains("s3cret"));
        assertEquals(source.url(), store.load().model().source(0).url());
    }

    @Test
    @DisplayName("已存进保险库的密码不会被后续保存删掉")
    void savingTwiceKeepsVaultEntries() {
        VideoWallStore store = store();
        VideoWallModel model = new VideoWallModel(WallLayout.preset(0));
        model.assign(0, credentialed("a", "大门", "rtsp://admin:s3cret@10.0.0.1:554/stream"));
        store.save(model, "preset:0").join();

        // 模拟重启：从配置读回来（地址是占位符），再保存一次
        VideoWallModel reloaded = store.load().model();
        store.save(reloaded, "preset:0").join();

        assertEquals(1, secrets.values.size());
        assertEquals("rtsp://admin:s3cret@10.0.0.1:554/stream", store.load().model().source(0).url());
    }

    @Test
    @DisplayName("清掉的源，其保险库条目会在下次保存时删除")
    void removingASourceClearsItsVaultEntry() {
        VideoWallStore store = store();
        VideoWallModel model = new VideoWallModel(WallLayout.preset(0));
        model.assign(0, credentialed("a", "大门", "rtsp://admin:s3cret@10.0.0.1:554/stream"));
        store.save(model, "preset:0").join();
        assertEquals(1, secrets.values.size());

        model.clearAll();
        store.save(model, "preset:0").join();

        assertTrue(secrets.values.isEmpty());
    }

    @Test
    @DisplayName("命名布局沿用旧版键与编码，只存几何")
    void savedLayoutsKeepTheirKeys() {
        VideoWallStore store = store();
        store.saveNamedLayout("大厅", WallLayout.preset(3));

        assertEquals("[大厅]", store.savedLayoutNames().toString());
        assertEquals(WallLayout.preset(3), store.namedLayout("大厅"));
        assertEquals(WallLayout.preset(3).encode(), settings.get("monitor.layout.大厅"));
        assertNull(store.namedLayout("不存在"));
    }

    @Test
    @DisplayName("旧版配置：没有画面墙数据时返回 null，数据损坏时也不抛异常")
    void handlesMissingOrCorruptData() {
        VideoWallStore store = store();
        assertNull(store.load());

        settings.put("monitor.wall", "{ not json");
        assertNull(store.load());

        settings.put("monitor.wall", "{\"layout\":\"0:0,0:0:1:1\"}");
        assertNull(store.load());
    }

    @Test
    @DisplayName("启动时保险库锁定：源带占位符恢复，解锁后还原；期间保存不会删掉保险库里的密码")
    void pendingCredentialsSurviveSavesAndResolveAfterUnlock() {
        VideoWallStore store = store();
        VideoWallModel model = new VideoWallModel(WallLayout.preset(0));
        model.assign(0, credentialed("a", "大门", "rtsp://admin:s3cret@10.0.0.1:554/stream"));
        store.save(model, "preset:0").join();

        secrets.status = SecretStore.Status.LOCKED;
        VideoWallModel reloaded = store.load().model();
        VideoSource pending = reloaded.source(0);
        assertTrue(pending.credentialsPending());
        assertFalse(pending.displayUrl().contains("s3cret"));

        secrets.status = SecretStore.Status.UNLOCKED;
        store.save(reloaded, "preset:0").join();   // 还没来得及还原就保存
        assertEquals(1, secrets.values.size());

        VideoSource resolved = store.resolve(pending);
        assertEquals("rtsp://admin:s3cret@10.0.0.1:554/stream", resolved.url());
        assertEquals(pending.id(), resolved.id());
    }

    /** 内存保险库：只认状态，不做真加密。 */
    private static final class MemorySecretStore implements SecretStore {

        private final Map<String, String> values = new LinkedHashMap<>();
        private Status status = Status.UNLOCKED;
        private final List<Listener> listeners = new ArrayList<>();

        @Override
        public Status status() {
            return status;
        }

        @Override
        public String get(String namespace, String id) {
            return status == Status.UNLOCKED ? values.get(namespace + "|" + id) : null;
        }

        @Override
        public List<String> ids(String namespace) {
            if (status != Status.UNLOCKED) return List.of();
            List<String> ids = new ArrayList<>();
            for (String key : values.keySet()) {
                if (key.startsWith(namespace + "|")) ids.add(key.substring(namespace.length() + 1));
            }
            return ids;
        }

        @Override
        public CompletableFuture<Void> put(String namespace, String id, String secret) {
            values.put(namespace + "|" + id, secret);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> remove(String namespace, String id) {
            values.remove(namespace + "|" + id);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> move(String namespace, String fromId, String toId) {
            String value = values.remove(namespace + "|" + fromId);
            if (value != null) values.put(namespace + "|" + toId, value);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> apply(String namespace, Map<String, String> puts,
                                             Collection<String> removals) {
            if (puts != null) puts.forEach((id, secret) -> put(namespace, id, secret));
            if (removals != null) removals.forEach(id -> remove(namespace, id));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> unlock(char[] masterPassword) {
            status = Status.UNLOCKED;
            listeners.forEach(listener -> listener.onSecretStoreStatus(status));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void addListener(Listener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(Listener listener) {
            listeners.remove(listener);
        }
    }
}
