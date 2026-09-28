package com.aqishi.toolbox.feature.monitor.ui;

import com.aqishi.toolbox.feature.monitor.application.VideoPlayer;
import com.aqishi.toolbox.feature.monitor.application.VideoPlayerFactory;
import com.aqishi.toolbox.feature.monitor.application.VideoWallStore;
import com.aqishi.toolbox.feature.monitor.domain.VideoSource;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换布局的行为：留下的通道继续播放（播放器不重建），消失的通道被释放（且只释放一次），
 * 所有通道都留在保存的配置里。
 */
class VideoMonitorPanelTest {

    private final Map<String, String> settings = new LinkedHashMap<>();
    private final FakePlayerFactory players = new FakePlayerFactory();
    private VideoMonitorPanel panel;

    @BeforeEach
    void setUp() throws Exception {
        panel = new VideoMonitorPanel(storeFor(settings), players);
        onEdt(panel::getView);
    }

    private static VideoWallStore storeFor(Map<String, String> settings) {
        return new VideoWallStore(new VideoWallStore.Settings() {
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
        }, SecretStore.disabled());
    }

    private static void onEdt(Runnable body) throws Exception {
        SwingUtilities.invokeAndWait(body);
    }

    /** 新画面墙是 1 画面；需要多个格子的用例先切到 4 画面。 */
    private void fourScreens() {
        panel.layoutComboForTest().setSelectedIndex(1);
    }

    private static VideoSource source(String id, String url) {
        return new VideoSource(id, "cam-" + id, url, true);
    }

    @Test
    @DisplayName("换布局：留下的通道继续播放且播放器不重建，消失的通道释放一次")
    void layoutSwitchKeepsPlayingCellsAndReleasesRemovedPlayers() throws Exception {
        onEdt(() -> {
            fourScreens();
            for (int i = 0; i < 4; i++) {
                panel.assignForTest(i, source("s" + i, "rtsp://10.0.0." + i + ":554/stream"));
            }
        });
        FakePlayer first = players.created.get(0);
        FakePlayer second = players.created.get(1);
        assertEquals(4, players.created.size());

        onEdt(() -> panel.layoutComboForTest().setSelectedIndex(0));   // 4 画面 -> 1 画面

        assertEquals(1, panel.cellCountForTest());
        assertEquals(1, panel.playersForTest().size());
        assertSame(first, players.created.get(0), "第一个通道应沿用原播放器");
        assertEquals(0, first.closes);
        assertTrue(first.playing);
        assertEquals(1, first.starts, "已在播放的通道不应被重启");

        assertEquals(1, second.closes);
        assertEquals(1, players.byId("s1").closes);
        assertEquals(1, players.byId("s2").closes);
        assertEquals(1, players.byId("s3").closes);
        assertEquals(List.of("s1", "s2", "s3"),
                panel.modelForTest().hiddenSources().stream().map(VideoSource::id).toList());

        // 放大回来：隐藏的通道重新创建播放器并自动开始播放
        onEdt(() -> panel.layoutComboForTest().setSelectedIndex(1));
        assertEquals(4, panel.cellCountForTest());
        assertEquals(4, panel.playersForTest().size());
        assertTrue(panel.playersForTest().player("s1").isPlaying());
        assertSame(first, panel.playersForTest().player("s0"));
    }

    @Test
    @DisplayName("换布局不丢通道：配置里始终保留全部源，重启后仍在原来的格子里")
    void layoutSwitchPersistsAllChannels() throws Exception {
        onEdt(() -> {
            fourScreens();
            panel.assignForTest(0, source("s0", "rtsp://10.0.0.0:554/stream"));
            panel.assignForTest(1, source("s1", "rtsp://10.0.0.1:554/stream"));
            panel.assignForTest(2, source("s2", "rtsp://10.0.0.2:554/stream"));
            panel.assignForTest(3, source("s3", "rtsp://10.0.0.3:554/stream"));
        });

        onEdt(() -> panel.layoutComboForTest().setSelectedIndex(0));   // 1 画面
        String json = settings.get("monitor.wall");
        for (int i = 0; i < 4; i++) {
            assertTrue(json.contains("\"s" + i + "\""), json);
        }

        // 重启：新的面板读到同一个配置
        VideoMonitorPanel restarted = new VideoMonitorPanel(storeFor(settings), new FakePlayerFactory());
        onEdt(restarted::getView);
        assertEquals(1, restarted.cellCountForTest());
        assertEquals("s0", restarted.modelForTest().source(0).id());
        assertEquals(3, restarted.modelForTest().hiddenSources().size());

        onEdt(() -> restarted.layoutComboForTest().setSelectedIndex(1));
        for (int i = 0; i < 4; i++) {
            assertEquals("s" + i, restarted.modelForTest().source(i).id());
        }
        onEdt(restarted::closeResources);
    }

    @Test
    @DisplayName("恢复上次布局时下拉框自动回到该项，且不会因为重建下拉项而重放布局")
    void restoreSelectsSavedLayoutWithoutReapplying() throws Exception {
        onEdt(() -> {
            panel.assignForTest(0, source("s0", "rtsp://10.0.0.0:554/stream"));
            panel.layoutComboForTest().setSelectedIndex(1);   // 4 画面
        });
        assertEquals(4, panel.cellCountForTest());

        VideoMonitorPanel restarted = new VideoMonitorPanel(storeFor(settings), new FakePlayerFactory());
        onEdt(restarted::getView);

        assertEquals(1, restarted.layoutComboForTest().getSelectedIndex());
        assertEquals(4, restarted.cellCountForTest());
        assertEquals("s0", restarted.modelForTest().source(0).id());
        onEdt(restarted::closeResources);
    }

    @Test
    @DisplayName("暂停后换布局：留下的通道保持暂停，不偷偷开始播放")
    void pauseSurvivesLayoutSwitch() throws Exception {
        onEdt(() -> {
            fourScreens();
            panel.assignForTest(0, source("s0", "rtsp://10.0.0.0:554/stream"));
            panel.assignForTest(1, source("s1", "rtsp://10.0.0.1:554/stream"));
            panel.togglePlayingForTest(1);
        });
        FakePlayer paused = players.byId("s1");
        assertFalse(paused.playing);

        onEdt(() -> panel.layoutComboForTest().setSelectedIndex(0));   // 1 画面
        onEdt(() -> panel.layoutComboForTest().setSelectedIndex(1));   // 回到 4 画面

        VideoPlayer recreated = panel.playersForTest().player("s1");
        assertNotNull(recreated);
        assertFalse(recreated.isPlaying());
        assertFalse(panel.modelForTest().source(1).playing());
    }

    @Test
    @DisplayName("合并：合并掉的通道进隐藏通道，只释放消失的播放器；再换布局通道仍在")
    void mergeAndSplitMovePlayersAround() throws Exception {
        onEdt(() -> {
            fourScreens();
            for (int i = 0; i < 4; i++) {
                panel.assignForTest(i, source("s" + i, "rtsp://10.0.0." + i + ":554/stream"));
            }
            panel.mergeForTest(0, 1, 2, 3);
        });

        assertEquals(1, panel.cellCountForTest());
        assertEquals("s0", panel.modelForTest().source(0).id());
        assertEquals(3, panel.modelForTest().hiddenSources().size());
        assertEquals(1, panel.playersForTest().size());
        assertSame(players.byId("s0"), panel.playersForTest().player("s0"));
        assertEquals(1, players.byId("s2").closes);

        onEdt(() -> panel.layoutComboForTest().setSelectedIndex(2));   // 5 画面：格子够用，隐藏通道全回来
        assertEquals(5, panel.cellCountForTest());
        assertEquals(List.of("s0", "s1", "s2", "s3"),
                panel.modelForTest().visibleSources().stream().map(VideoSource::id).toList());
        assertTrue(panel.modelForTest().hiddenSources().isEmpty());
        assertSame(players.byId("s0"), panel.playersForTest().player("s0"));
        assertEquals(0, players.byId("s0").closes);
    }

    @Test
    @DisplayName("清空：释放全部播放器（含隐藏通道），配置里的通道也清掉")
    void clearReleasesEverything() throws Exception {
        onEdt(() -> {
            fourScreens();
            panel.assignForTest(0, source("s0", "rtsp://10.0.0.0:554/stream"));
            panel.assignForTest(1, source("s1", "rtsp://10.0.0.1:554/stream"));
            panel.clearAllForTest();
        });

        assertEquals(0, panel.playersForTest().size());
        assertEquals(1, players.byId("s0").closes);
        assertEquals(1, players.byId("s1").closes);
        assertTrue(panel.modelForTest().channels().isEmpty());
        assertFalse(settings.get("monitor.wall").contains("\"s0\""));
    }

    @Test
    @DisplayName("closeResources：释放全部播放器与保险库监听，可重复调用")
    void closeResourcesReleasesEverything() throws Exception {
        onEdt(() -> {
            fourScreens();
            panel.assignForTest(0, source("s0", "rtsp://10.0.0.0:554/stream"));
            panel.assignForTest(1, source("s1", "rtsp://10.0.0.1:554/stream"));
        });

        onEdt(panel::closeResources);
        onEdt(panel::closeResources);

        assertEquals(0, panel.playersForTest().size());
        for (FakePlayer player : players.created) {
            assertEquals(1, player.closes, "每个播放器只释放一次");
        }
        // 关闭后再改模型也不会新建播放器
        onEdt(() -> panel.assignForTest(0, source("sX", "rtsp://10.0.0.9:554/stream")));
        assertEquals(0, panel.playersForTest().size());
    }

    @Test
    @DisplayName("5 画面预设与自定义网格：先把通道填满再切换，通道都还在")
    void fiveScreenPresetKeepsSources() throws Exception {
        onEdt(() -> {
            panel.layoutComboForTest().setSelectedIndex(2);   // 5 画面
            for (int i = 0; i < 5; i++) {
                panel.assignForTest(i, source("s" + i, "rtsp://10.0.0." + i + ":554/stream"));
            }
        });
        assertEquals(5, panel.cellCountForTest());
        assertEquals(5, panel.playersForTest().size());

        onEdt(() -> panel.layoutComboForTest().setSelectedIndex(3));   // 9 画面
        assertEquals(9, panel.cellCountForTest());
        assertEquals(5, panel.playersForTest().size());
        for (int i = 0; i < 5; i++) {
            assertNotNull(panel.modelForTest().source(i));
        }
    }

    /** 记录创建/释放次数与播放状态的假播放器。 */
    private static final class FakePlayer implements VideoPlayer {
        private final String id;
        private final List<String> log;
        private int closes;
        private int starts;
        private boolean playing;

        FakePlayer(String id, List<String> log) {
            this.id = id;
            this.log = log;
        }

        @Override
        public void start() {
            if (!playing) starts++;
            playing = true;
            log.add("start:" + id);
        }

        @Override
        public void stop() {
            playing = false;
            log.add("stop:" + id);
        }

        @Override
        public boolean isPlaying() {
            return playing;
        }

        @Override
        public void close() {
            closes++;
            playing = false;
            log.add("close:" + id);
        }
    }

    private static final class FakePlayerFactory implements VideoPlayerFactory {
        private final List<FakePlayer> created = new ArrayList<>();
        private final List<String> log = new ArrayList<>();

        @Override
        public VideoPlayer create(VideoSource source, Runnable frameListener) {
            FakePlayer player = new FakePlayer(source.id(), log);
            created.add(player);
            log.add("create:" + source.id());
            return player;
        }

        FakePlayer byId(String id) {
            return created.stream().filter(p -> p.id.equals(id)).findFirst().orElseThrow();
        }
    }
}
