package com.aqishi.toolbox.feature.monitor.application;

import com.aqishi.toolbox.domain.JdbcUrlSecrets;
import com.aqishi.toolbox.feature.monitor.domain.VideoSource;
import com.aqishi.toolbox.feature.monitor.domain.VideoWallModel;
import com.aqishi.toolbox.feature.monitor.domain.WallLayout;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.Errors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 画面墙的持久化：当前布局与全部通道（含隐藏通道）存进本地配置，地址里的密码存进保险库。
 *
 * <p>配置里的地址只含 {@code {{vault:名称}}} 占位符（拆分规则复用 {@link JdbcUrlSecrets}，
 * 它同样识别 {@code rtsp://user:pass@host} 与 {@code ?password=}）。保险库未解锁时密码不落盘，
 * 只在本次会话有效；解锁后再保存才写入。命名布局沿用旧版的键与编码，已保存的布局照常可用。</p>
 */
public final class VideoWallStore {

    /** 保险库命名空间；条目 id 为 {@code 源id|字段名}。 */
    public static final String NAMESPACE = "video-monitor";
    static final String WALL_KEY = "monitor.wall";
    static final String NAMES_KEY = "monitor.saved.names";
    static final String LAYOUT_PREFIX = "monitor.layout.";
    private static final String SEPARATOR = "|";

    /** 最小的键值配置接口：生产用 {@link ConfigManager}，测试用内存实现。 */
    public interface Settings {
        String get(String key, String def);

        void set(String key, String value);

        boolean save();

        static Settings configManager() {
            return new Settings() {
                @Override
                public String get(String key, String def) {
                    return ConfigManager.get(key, def);
                }

                @Override
                public void set(String key, String value) {
                    ConfigManager.set(key, value);
                }

                @Override
                public boolean save() {
                    return ConfigManager.save();
                }
            };
        }
    }

    /** 读出的状态：画面墙模型与下拉框选中项（{@code preset:N} / {@code saved:名称} / {@code custom}）。 */
    public record Saved(VideoWallModel model, String selection) {
    }

    private final Settings settings;
    private final SecretStore secrets;
    private final ObjectMapper mapper = new ObjectMapper();

    public VideoWallStore(Settings settings, SecretStore secrets) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.secrets = secrets == null ? SecretStore.disabled() : secrets;
    }

    public SecretStore secrets() {
        return secrets;
    }

    /** 密码能否持久保存：只有保险库解锁时才能。 */
    public boolean canPersistSecrets() {
        return secrets.status() == SecretStore.Status.UNLOCKED;
    }

    /** 读取上次的画面墙；从未保存过或数据损坏时返回 null。 */
    public Saved load() {
        String json = settings.get(WALL_KEY, "");
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = mapper.readTree(json);
            WallLayout layout = WallLayout.decode(root.path("layout").asText(""));
            if (layout == null) return null;
            List<VideoSource> channels = new ArrayList<>();
            for (JsonNode node : root.path("channels")) {
                if (node == null || node.isNull() || !node.hasNonNull("id")) {
                    channels.add(null);
                    continue;
                }
                channels.add(resolve(new VideoSource(node.path("id").asText(), node.path("name").asText(""),
                        node.path("url").asText(""), node.path("playing").asBoolean(true))));
            }
            return new Saved(VideoWallModel.restore(layout, channels), root.path("selection").asText(""));
        } catch (Exception error) {
            Errors.ignored("Corrupt video wall configuration, starting with the default layout", error);
            return null;
        }
    }

    /**
     * 保存画面墙。配置同步写出；返回的 future 表示保险库写入（未解锁时立即完成）。
     */
    public CompletableFuture<Void> save(VideoWallModel model, String selection) {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", 1);
        root.put("selection", selection == null ? "" : selection);
        root.put("layout", model.layout().encode());
        ArrayNode channels = root.putArray("channels");
        Map<String, String> puts = new LinkedHashMap<>();
        Set<String> pendingIds = new HashSet<>();
        for (VideoSource source : model.channels()) {
            if (source == null) {
                channels.addNull();
                continue;
            }
            JdbcUrlSecrets.Split split = JdbcUrlSecrets.split(source.url());
            ObjectNode node = channels.addObject();
            node.put("id", source.id());
            node.put("name", source.name());
            node.put("url", split.maskedUrl());
            node.put("playing", source.playing());
            split.secrets().forEach((field, value) -> puts.put(source.id() + SEPARATOR + field, value));
            if (source.credentialsPending()) pendingIds.add(source.id());
        }
        settings.set(WALL_KEY, root.toString());
        settings.save();
        return writeSecrets(puts, pendingIds);
    }

    /** 把占位符还原成保险库里的密码；保险库未解锁或缺值时原样返回。 */
    public VideoSource resolve(VideoSource source) {
        if (source == null || !source.credentialsPending() || !canPersistSecrets()) return source;
        String prefix = source.id() + SEPARATOR;
        Map<String, String> fields = new LinkedHashMap<>();
        for (String id : secrets.ids(NAMESPACE)) {
            if (!id.startsWith(prefix)) continue;
            String value = secrets.get(NAMESPACE, id);
            if (value != null) fields.put(id.substring(prefix.length()), value);
        }
        String restored = JdbcUrlSecrets.restore(source.url(), fields);
        return restored.equals(source.url()) ? source : source.withUrl(restored);
    }

    private CompletableFuture<Void> writeSecrets(Map<String, String> puts, Set<String> pendingIds) {
        if (!canPersistSecrets()) return CompletableFuture.completedFuture(null);
        Map<String, String> changed = new LinkedHashMap<>();
        puts.forEach((id, value) -> {
            if (!value.equals(secrets.get(NAMESPACE, id))) changed.put(id, value);
        });
        List<String> removals = new ArrayList<>();
        for (String id : secrets.ids(NAMESPACE)) {
            int cut = id.indexOf(SEPARATOR);
            // 仍是占位符的源（刚解锁、尚未还原）的密码要保留，否则保存一次就把它删了
            boolean pending = cut > 0 && pendingIds.contains(id.substring(0, cut));
            if (!puts.containsKey(id) && !pending) removals.add(id);
        }
        if (changed.isEmpty() && removals.isEmpty()) return CompletableFuture.completedFuture(null);
        return secrets.apply(NAMESPACE, changed, removals);
    }

    // ==================== 命名布局（与旧版键兼容，只存几何） ====================

    public List<String> savedLayoutNames() {
        List<String> names = new ArrayList<>();
        for (String n : settings.get(NAMES_KEY, "").split(",")) {
            String t = n.trim();
            if (!t.isEmpty() && !names.contains(t)) names.add(t);
        }
        return names;
    }

    public WallLayout namedLayout(String name) {
        return WallLayout.decode(settings.get(LAYOUT_PREFIX + name, ""));
    }

    public void saveNamedLayout(String name, WallLayout layout) {
        settings.set(LAYOUT_PREFIX + name, layout.encode());
        List<String> names = savedLayoutNames();
        if (!names.contains(name)) names.add(name);
        settings.set(NAMES_KEY, String.join(",", names));
        settings.save();
    }
}
