package com.aqishi.toolbox.feature.monitor.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 换布局只换几何：通道分配必须原样保留，缩小时多余的通道转成隐藏通道。 */
class VideoWallModelTest {

    private static VideoSource source(String id) {
        return new VideoSource(id, "cam-" + id, "rtsp://10.0.0." + id + ":554/stream", true);
    }

    /** 4 画面用预设 1，9 画面用预设 3，两种都是均匀网格。 */
    private static VideoWallModel populated(int cells) {
        VideoWallModel model = new VideoWallModel(WallLayout.preset(cells == 4 ? 1 : 3));
        for (int i = 0; i < cells; i++) {
            model.assign(i, source(String.valueOf(i)));
        }
        return model;
    }

    @Test
    @DisplayName("缩小布局：可见通道变少，多余的源成为隐藏通道而不是被丢弃")
    void shrinkingKeepsHiddenSources() {
        VideoWallModel model = populated(4);

        model.setLayout(WallLayout.preset(0));   // 1 画面

        assertEquals(1, model.cellCount());
        assertEquals(List.of("0"), ids(model.visibleSources()));
        assertEquals(List.of("1", "2", "3"), ids(model.hiddenSources()));
        assertEquals(4, model.channels().size());
    }

    @Test
    @DisplayName("缩小再放大：原来的源按原顺序回到格子里")
    void growingRestoresHiddenSources() {
        VideoWallModel model = populated(4);

        model.setLayout(WallLayout.preset(0));
        model.setLayout(WallLayout.preset(1));   // 回到 4 画面

        assertEquals(4, model.cellCount());
        assertEquals(List.of("0", "1", "2", "3"), ids(model.visibleSources()));
        assertTrue(model.hiddenSources().isEmpty());
        for (int i = 0; i < 4; i++) {
            assertEquals("cam-" + i, model.source(i).name());
        }
    }

    @Test
    @DisplayName("合并后再拆分：合并格的源留在左上角，被合并掉的源按顺序排在隐藏通道里")
    void mergeThenSplitRoundTripsSources() {
        VideoWallModel model = populated(4);

        assertTrue(model.merge(List.of(0, 1, 2, 3)));
        assertEquals(1, model.cellCount());
        assertEquals("0", model.source(0).id());
        assertEquals(List.of("1", "2", "3"), ids(model.hiddenSources()));

        assertTrue(model.split(0));
        assertEquals(4, model.cellCount());
        assertEquals(List.of("0", "1", "2", "3"), ids(model.visibleSources()));
    }

    @Test
    @DisplayName("不构成矩形的多选不能合并，模型保持原样")
    void mergeOfNonRectangleIsRejected() {
        VideoWallModel model = new VideoWallModel(WallLayout.uniform(2, 2));
        model.assign(0, source("0"));
        model.assign(3, source("3"));

        assertFalse(model.merge(List.of(0, 3)));

        assertEquals(4, model.cellCount());
        assertEquals("0", model.source(0).id());
        assertEquals("3", model.source(3).id());
    }

    @Test
    @DisplayName("1×1 的格子不能拆分")
    void splitRejectsSingleCell() {
        VideoWallModel model = populated(4);
        assertFalse(model.split(0));
        assertEquals(4, model.cellCount());
    }

    @Test
    @DisplayName("命名布局编码：几何往返一致，损坏的配置返回 null")
    void layoutEncodingRoundTrips() {
        WallLayout five = WallLayout.preset(2);
        assertEquals(5, five.size());
        WallLayout decoded = WallLayout.decode(five.encode());
        assertEquals(five, decoded);
        assertEquals(4, decoded.rows());
        assertEquals(3, decoded.cols());

        assertNull(WallLayout.decode(""));
        assertNull(WallLayout.decode("2:2,0:0:9:9"));
        assertNull(WallLayout.decode("not a layout"));
    }

    @Test
    @DisplayName("空格子、清空与隐藏通道计数")
    void emptyCellsAndClear() {
        VideoWallModel model = new VideoWallModel(WallLayout.uniform(2, 2));
        assertEquals(0, model.firstEmptyCell());
        model.assign(1, source("1"));
        assertEquals(0, model.firstEmptyCell());
        assertNull(model.source(0));
        assertEquals(1, model.cellOf("1"));

        model.assign(2, source("2"));
        assertEquals(2, model.visibleSources().size());
        model.clear(2);

        assertEquals(List.of("1"), ids(model.visibleSources()));
        model.clearAll();
        assertTrue(model.visibleSources().isEmpty());
        assertTrue(model.hiddenSources().isEmpty());
        assertEquals(-1, model.cellOf("1"));
    }

    @Test
    @DisplayName("恢复时保留空位与隐藏通道")
    void restoreKeepsChannelTable() {
        List<VideoSource> channels = java.util.Arrays.asList(source("a"), null, source("c"));
        VideoWallModel model = VideoWallModel.restore(WallLayout.uniform(2, 2), channels);

        assertEquals("a", model.source(0).id());
        assertNull(model.source(1));
        assertEquals("c", model.source(2).id());
        assertNull(model.source(3));
        assertEquals(3, model.channels().size());
    }

    @Test
    @DisplayName("源只在替换时换对象，布局变化不动对象本身")
    void layoutChangesKeepSourceInstances() {
        VideoWallModel model = populated(4);
        VideoSource first = model.source(0);

        model.setLayout(WallLayout.preset(0));
        model.setLayout(WallLayout.preset(4));   // 16 画面

        assertSame(first, model.source(0));
    }

    private static List<String> ids(List<VideoSource> sources) {
        return sources.stream().map(VideoSource::id).toList();
    }
}
