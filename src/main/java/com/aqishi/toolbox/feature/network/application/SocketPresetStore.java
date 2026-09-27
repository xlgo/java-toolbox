package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.PayloadPreset;
import com.aqishi.toolbox.infra.config.JsonPreferencesStore;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * 快捷发送预设的持久化。以有序 map 存入调用方给定的偏好节点，键只是顺序编号，
 * 真正的名称在条目内部——允许重名，也不会因改名丢数据。
 */
public final class SocketPresetStore {

    /** 偏好中的键名。 */
    public static final String KEY = "socket_debug_presets";

    private final JsonPreferencesStore<PayloadPreset> store;

    public SocketPresetStore(Preferences preferences) {
        this.store = new JsonPreferencesStore<>(Objects.requireNonNull(preferences, "preferences"), KEY,
                new TypeReference<LinkedHashMap<String, PayloadPreset>>() { });
    }

    /** 读取全部预设；数据损坏时抛出 InfrastructureException，由调用方决定是否提示。 */
    public List<PayloadPreset> load() {
        return new ArrayList<>(store.load().values());
    }

    public void save(List<PayloadPreset> presets) {
        LinkedHashMap<String, PayloadPreset> values = new LinkedHashMap<>();
        int index = 0;
        for (PayloadPreset preset : presets) {
            values.put(String.format("p%04d", index++), preset);
        }
        store.save(values);
    }
}
