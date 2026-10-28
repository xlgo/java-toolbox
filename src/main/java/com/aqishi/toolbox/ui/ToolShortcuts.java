package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 收藏与最近使用：效果页侧栏的「我的收藏」「最近使用」两个入口共用同一份状态。
 *
 * <p>收藏按工具稳定 ID 保存到 {@code nav.favorites}，最近使用保存到 {@code nav.recent}，
 * 两者都是逗号分隔的列表，因此不需要额外的存储格式。所有写操作立即落盘——
 * 收藏是一个低频、用户预期「点了就在」的动作，攒着等退出再存反而容易丢。</p>
 *
 * <p>侧栏、首页卡片与工具页标题上的星标都订阅 {@link #addListener(Consumer)}，
 * 任何一处切换收藏，其它位置同步刷新，不会出现「侧栏已取消、卡片还是实心」的错位。</p>
 */
public final class ToolShortcuts {

    /** 最近使用的最大条数：再多就失去「快速回到刚才那个工具」的意义 */
    public static final int RECENT_LIMIT = 8;

    private static final String FAVORITES_KEY = "nav.favorites";
    private static final String RECENT_KEY = "nav.recent";

    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<String> recent = new ArrayList<>();
    private final List<Consumer<String>> listeners = new ArrayList<>();

    private ToolShortcuts() {
    }

    /** 从配置读取当前状态 */
    public static ToolShortcuts load() {
        ToolShortcuts shortcuts = new ToolShortcuts();
        shortcuts.favorites.addAll(split(ConfigManager.get(FAVORITES_KEY, "")));
        shortcuts.recent.addAll(split(ConfigManager.get(RECENT_KEY, "")));
        if (shortcuts.recent.size() > RECENT_LIMIT) {
            shortcuts.recent.subList(RECENT_LIMIT, shortcuts.recent.size()).clear();
        }
        return shortcuts;
    }

    private static List<String> split(String stored) {
        List<String> values = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return values;
        }
        for (String id : stored.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty() && !values.contains(trimmed)) {
                values.add(trimmed);
            }
        }
        return values;
    }

    // ---------------------------------------------------------------- 收藏

    public boolean isFavorite(String toolId) {
        return toolId != null && favorites.contains(toolId);
    }

    public Set<String> getFavorites() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(favorites));
    }

    public int favoriteCount() {
        return favorites.size();
    }

    /** 切换收藏，返回切换后的状态 */
    public boolean toggleFavorite(String toolId) {
        if (toolId == null) {
            return false;
        }
        boolean nowFavorite = favorites.contains(toolId) ? !favorites.remove(toolId) : favorites.add(toolId);
        ConfigManager.set(FAVORITES_KEY, String.join(",", favorites));
        ConfigManager.save();
        notifyListeners(toolId);
        return nowFavorite;
    }

    public void setFavorite(String toolId, boolean value) {
        if (toolId == null || favorites.contains(toolId) == value) {
            return;
        }
        toggleFavorite(toolId);
    }

    // ---------------------------------------------------------------- 最近使用

    public List<String> getRecent() {
        return Collections.unmodifiableList(new ArrayList<>(recent));
    }

    public int recentCount() {
        return recent.size();
    }

    /** 记录一次打开：去重后置顶，超出上限的尾部丢弃 */
    public void markOpened(String toolId) {
        if (toolId == null || toolId.isEmpty()) {
            return;
        }
        recent.remove(toolId);
        recent.add(0, toolId);
        while (recent.size() > RECENT_LIMIT) {
            recent.remove(recent.size() - 1);
        }
        ConfigManager.set(RECENT_KEY, String.join(",", recent));
        ConfigManager.save();
        notifyListeners(toolId);
    }

    // ---------------------------------------------------------------- 订阅

    /** 收藏或最近使用发生变化时通知；参数为受影响的工具 ID */
    public void addListener(Consumer<String> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyListeners(String toolId) {
        for (Consumer<String> listener : new ArrayList<>(listeners)) {
            listener.accept(toolId);
        }
    }
}
