package com.aqishi.toolbox.feature.monitor.application;

import com.aqishi.toolbox.domain.JdbcUrlSecrets;
import com.aqishi.toolbox.feature.monitor.domain.VideoSource;
import com.aqishi.toolbox.util.Errors;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 画面墙上各路播放器的生命周期，按源 id 管理，与格子组件无关。
 *
 * <p>{@link #sync} 让播放器集合与当前可见的源一致：仍可见的源沿用原播放器（正在播放的
 * 继续播放，换布局不闪断）；不再可见、或地址变了的源，其播放器被释放且只释放一次；
 * 新出现的源按需创建。凭据还在保险库里没取出来的源不创建播放器。</p>
 *
 * <p>方法互斥执行：平时在 EDT 上调用，应用退出时 shutdown() 可来自任意线程。</p>
 */
public final class VideoWallPlayers {

    private record Entry(VideoPlayer player, String url) {
    }

    private final VideoPlayerFactory factory;
    private final Consumer<String> frameListener;
    private final Map<String, Entry> players = new LinkedHashMap<>();
    private boolean shutdown;

    /**
     * @param frameListener 收到某个源（按 id）的新画面时调用，可在任意线程；
     *                      调用方按 id 找到当前显示它的格子去重绘，所以换布局后自动跟随
     */
    public VideoWallPlayers(VideoPlayerFactory factory, Consumer<String> frameListener) {
        this.factory = Objects.requireNonNull(factory, "factory");
        this.frameListener = Objects.requireNonNull(frameListener, "frameListener");
    }

    public synchronized void sync(List<VideoSource> visible) {
        if (shutdown) return;
        Map<String, VideoSource> wanted = new LinkedHashMap<>();
        for (VideoSource source : visible) {
            if (source != null && !source.credentialsPending()) wanted.put(source.id(), source);
        }
        Iterator<Map.Entry<String, Entry>> it = players.entrySet().iterator();
        List<VideoPlayer> released = new ArrayList<>();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            VideoSource source = wanted.get(e.getKey());
            if (source == null || !source.url().equals(e.getValue().url())) {
                it.remove();
                released.add(e.getValue().player());
            }
        }
        released.forEach(VideoWallPlayers::closeQuietly);
        for (VideoSource source : wanted.values()) {
            Entry entry = players.get(source.id());
            if (entry == null) {
                entry = create(source);
                if (entry == null) continue;
                players.put(source.id(), entry);
            }
            try {
                if (source.playing() && !entry.player().isPlaying()) entry.player().start();
                else if (!source.playing() && entry.player().isPlaying()) entry.player().stop();
            } catch (RuntimeException error) {
                log("Unable to change the playback state of video source " + source.id(), error);
            }
        }
    }

    /** 某个源的播放器；没有（不可见、凭据未就绪、创建失败）时返回 null。 */
    public synchronized VideoPlayer player(String sourceId) {
        Entry entry = players.get(sourceId);
        return entry == null ? null : entry.player();
    }

    public synchronized int size() {
        return players.size();
    }

    /** 释放全部播放器；之后 {@link #sync} 不再创建新播放器（面板已关闭）。 */
    public synchronized void shutdown() {
        shutdown = true;
        List<Entry> all = new ArrayList<>(players.values());
        players.clear();
        for (Entry entry : all) closeQuietly(entry.player());
    }

    private Entry create(VideoSource source) {
        String id = source.id();
        try {
            VideoPlayer player = factory.create(source, () -> frameListener.accept(id));
            return player == null ? null : new Entry(player, source.url());
        } catch (RuntimeException error) {
            log("Unable to create a player for video source " + id, error);
            return null;
        }
    }

    private static void closeQuietly(VideoPlayer player) {
        try {
            player.close();
        } catch (RuntimeException error) {
            log("Unable to release a video player", error);
        }
    }

    /** 播放器的异常信息可能带着完整地址：只记录类名与脱敏后的消息，绝不带出密码。 */
    private static void log(String context, RuntimeException error) {
        String message = error.getMessage() == null ? "" : ": " + JdbcUrlSecrets.redact(error.getMessage());
        Errors.log(context, new IllegalStateException(error.getClass().getName() + message));
    }
}
