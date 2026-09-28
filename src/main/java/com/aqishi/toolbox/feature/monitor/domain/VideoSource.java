package com.aqishi.toolbox.feature.monitor.domain;

import com.aqishi.toolbox.domain.JdbcUrlSecrets;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/**
 * 分配到画面墙某个通道的视频源。
 *
 * <p>{@code id} 是稳定标识：换布局、合并拆分时播放器按它复用，保险库里的密码也按它存放。
 * 同一台摄像头分配到两个格子会得到两个不同的 id（两路独立播放）。</p>
 *
 * <p>{@code url} 可能带凭据（{@code rtsp://user:pass@host}、{@code ?password=}），
 * 或者是尚未从保险库还原的占位符形式。界面与日志一律用 {@link #displayUrl()}；
 * {@link #toString()} 同样不含密码，误打日志也不会泄露。</p>
 */
public record VideoSource(String id, String name, String url, boolean playing) {

    public VideoSource {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id");
        Objects.requireNonNull(url, "url");
        name = name == null ? "" : name;
    }

    /** 新分配的源：新 id，默认处于播放状态（与原先分配即显示实况一致）。 */
    public static VideoSource create(String name, String url) {
        String trimmed = url == null ? "" : url.trim();
        String label = name == null || name.isBlank() ? defaultName(trimmed) : name.trim();
        return new VideoSource(UUID.randomUUID().toString(), label, trimmed, true);
    }

    public VideoSource withPlaying(boolean value) {
        return value == playing ? this : new VideoSource(id, name, url, value);
    }

    public VideoSource withUrl(String value) {
        return new VideoSource(id, name, value, playing);
    }

    /** 密码（明文或占位符）替换为星号后的地址，供界面与日志使用。 */
    public String displayUrl() {
        return JdbcUrlSecrets.redact(url);
    }

    /** 地址里还有未从保险库还原的密码占位符：此时不能真正连接。 */
    public boolean credentialsPending() {
        return JdbcUrlSecrets.hasPlaceholders(url);
    }

    /** 地址里是否嵌有密码（明文或占位符）。 */
    public boolean hasCredentials() {
        return credentialsPending() || !JdbcUrlSecrets.split(url).secrets().isEmpty();
    }

    /** 没有名称时用主机名（不含 userinfo）；解析不了就用脱敏后的地址。 */
    public static String defaultName(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String host = URI.create(JdbcUrlSecrets.split(url).maskedUrl()
                    .replace("{{vault:", "x").replace("}}", "x")).getHost();
            if (host != null && !host.isEmpty()) return host;
        } catch (IllegalArgumentException ignored) {
            // 非标准 URI（本地设备路径等）：退回脱敏地址
        }
        return JdbcUrlSecrets.redact(url);
    }

    @Override
    public String toString() {
        return "VideoSource[" + id + ", " + name + ", " + displayUrl() + (playing ? ", playing" : "") + "]";
    }
}
