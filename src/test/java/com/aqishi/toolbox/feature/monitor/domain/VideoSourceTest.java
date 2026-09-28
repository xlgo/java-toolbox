package com.aqishi.toolbox.feature.monitor.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 流地址里的凭据只在连接时使用：界面、日志、toString 一律脱敏。 */
class VideoSourceTest {

    @Test
    @DisplayName("userinfo 密码在显示地址与 toString 中被打码")
    void masksUserinfoPassword() {
        VideoSource source = VideoSource.create("gate", "rtsp://admin:s3cret@10.0.0.1:554/stream");

        assertEquals("rtsp://admin:******@10.0.0.1:554/stream", source.displayUrl());
        assertFalse(source.toString().contains("s3cret"), source.toString());
        assertTrue(source.hasCredentials());
        assertFalse(source.credentialsPending());
    }

    @Test
    @DisplayName("查询参数里的 password / token 也被打码")
    void masksQueryParameterSecrets() {
        VideoSource source = VideoSource.create("nvr",
                "http://10.0.0.2/cgi-bin/stream?user=admin&password=p4ss&token=abc123");

        String shown = source.displayUrl();
        assertFalse(shown.contains("p4ss"), shown);
        assertFalse(shown.contains("abc123"), shown);
        assertTrue(shown.contains("user=admin"), shown);
    }

    @Test
    @DisplayName("没有凭据的地址原样显示；默认名称取主机名且不含 userinfo")
    void plainUrlsAndDefaultNames() {
        VideoSource plain = VideoSource.create(null, "rtsp://192.168.1.101:554/stream");
        assertEquals("rtsp://192.168.1.101:554/stream", plain.displayUrl());
        assertFalse(plain.hasCredentials());
        assertEquals("192.168.1.101", plain.name());

        VideoSource withUser = VideoSource.create(" ", "rtsp://admin:s3cret@cam.local:554/live");
        assertEquals("cam.local", withUser.name());
    }

    @Test
    @DisplayName("占位符形式的地址视为凭据未就绪，显示时同样打码")
    void placeholderUrlsArePending() {
        VideoSource pending = new VideoSource("id-1", "gate",
                "rtsp://admin:{{vault:userinfo}}@10.0.0.1:554/stream", true);

        assertTrue(pending.credentialsPending());
        assertEquals("rtsp://admin:******@10.0.0.1:554/stream", pending.displayUrl());
    }

    @Test
    @DisplayName("每次分配都是新 id；改播放状态保留 id")
    void identity() {
        VideoSource a = VideoSource.create("gate", "rtsp://10.0.0.1/stream");
        VideoSource b = VideoSource.create("gate", "rtsp://10.0.0.1/stream");
        assertNotEquals(a.id(), b.id());
        assertTrue(a.playing());

        VideoSource paused = a.withPlaying(false);
        assertEquals(a.id(), paused.id());
        assertFalse(paused.playing());
    }
}
