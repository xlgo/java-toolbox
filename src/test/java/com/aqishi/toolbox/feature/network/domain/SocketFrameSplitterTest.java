package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocketFrameSplitterTest {

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static List<String> strings(List<byte[]> frames) {
        List<String> out = new ArrayList<>();
        for (byte[] f : frames) {
            out.add(new String(f, StandardCharsets.US_ASCII));
        }
        return out;
    }

    @Test
    void rawEmitsEachChunk() {
        SocketFrameSplitter splitter = new SocketFrameSplitter(SocketFrameSplitter.Config.raw());
        assertEquals(List.of("abc"), strings(splitter.feed(b("abc"), 0)));
        assertEquals(List.of("de"), strings(splitter.feed(b("xde"), 1, 2, 0)));
        assertEquals(0, splitter.pending());
        assertTrue(splitter.feed(new byte[0], 0).isEmpty());
    }

    @Test
    void delimiterSplitsAcrossChunksAndStripsByDefault() {
        SocketFrameSplitter splitter = new SocketFrameSplitter(
                SocketFrameSplitter.Config.delimiter(b("\r\n"), false));
        assertEquals(List.of(), strings(splitter.feed(b("hel"), 0)));
        // 分隔符被拆在两块之间：\r 在第一块末尾，\n 在第二块开头
        assertEquals(List.of(), strings(splitter.feed(b("lo\r"), 0)));
        assertEquals(List.of("hello", "world"), strings(splitter.feed(b("\nworld\r\nne"), 0)));
        assertEquals(2, splitter.pending());
        assertArrayEquals(b("ne"), splitter.drain());
        assertNull(splitter.drain());
    }

    @Test
    void delimiterCanBeKept() {
        SocketFrameSplitter splitter = new SocketFrameSplitter(
                SocketFrameSplitter.Config.delimiter(b("\n"), true));
        assertEquals(List.of("a\n", "\n", "bc\n"), strings(splitter.feed(b("a\n\nbc\n"), 0)));
    }

    @Test
    void customMultiByteDelimiter() {
        SocketFrameSplitter splitter = new SocketFrameSplitter(
                SocketFrameSplitter.Config.delimiter(new byte[]{(byte) 0xAA, 0x55}, false));
        List<byte[]> first = splitter.feed(new byte[]{1, 2, (byte) 0xAA}, 0);
        assertTrue(first.isEmpty());
        List<byte[]> second = splitter.feed(new byte[]{0x55, 3, (byte) 0xAA, 0x55}, 0);
        assertEquals(2, second.size());
        assertArrayEquals(new byte[]{1, 2}, second.get(0));
        assertArrayEquals(new byte[]{3}, second.get(1));
        assertThrows(IllegalArgumentException.class, () -> SocketFrameSplitter.Config.delimiter(new byte[0], true));
    }

    @Test
    void fixedLengthAcrossChunks() {
        SocketFrameSplitter splitter = new SocketFrameSplitter(SocketFrameSplitter.Config.fixedLength(4));
        assertEquals(List.of(), strings(splitter.feed(b("ab"), 0)));
        assertEquals(List.of("abcd"), strings(splitter.feed(b("cdef"), 0)));
        assertEquals(List.of("efgh", "ijkl"), strings(splitter.feed(b("ghijklm"), 0)));
        assertEquals(1, splitter.pending());
    }

    @Test
    void idleTimeoutMergesUntilQuietWithInjectedClock() {
        SocketFrameSplitter splitter = new SocketFrameSplitter(SocketFrameSplitter.Config.idleTimeout(50));
        assertEquals(-1L, splitter.nextDeadline());
        assertTrue(splitter.feed(b("ab"), 1000).isEmpty());
        assertEquals(1050L, splitter.nextDeadline());
        assertTrue(splitter.poll(1030).isEmpty());
        assertTrue(splitter.feed(b("cd"), 1040).isEmpty());
        // 新数据把截止时间推后
        assertEquals(1090L, splitter.nextDeadline());
        assertTrue(splitter.poll(1060).isEmpty());
        assertEquals(List.of("abcd"), strings(splitter.poll(1090)));
        assertEquals(-1L, splitter.nextDeadline());
        assertTrue(splitter.poll(5000).isEmpty());
    }

    @Test
    void maxFrameGuardForcesSplit() {
        SocketFrameSplitter delimited = new SocketFrameSplitter(
                SocketFrameSplitter.Config.delimiter(b("\n"), false).withMaxFrameSize(4));
        assertEquals(List.of("abcd", "efgh"), strings(delimited.feed(b("abcdefghi"), 0)));
        assertEquals(List.of("ij"), strings(delimited.feed(b("j\n"), 0)));

        SocketFrameSplitter idle = new SocketFrameSplitter(
                SocketFrameSplitter.Config.idleTimeout(100).withMaxFrameSize(3));
        assertEquals(List.of("abc"), strings(idle.feed(b("abcde"), 0)));
        assertEquals(List.of("de"), strings(idle.poll(100)));

        SocketFrameSplitter raw = new SocketFrameSplitter(SocketFrameSplitter.Config.raw().withMaxFrameSize(2));
        assertEquals(List.of("ab", "cd", "e"), strings(raw.feed(b("abcde"), 0)));

        // 定长模式的上限不会小于帧长
        assertEquals(8, SocketFrameSplitter.Config.fixedLength(8).withMaxFrameSize(2).getMaxFrameSize());
    }

    @Test
    void idleGapSeparatesFramesEvenIfPollingWasDelayed() {
        var splitter = new SocketFrameSplitter(SocketFrameSplitter.Config.idleTimeout(50));
        assertTrue(splitter.feed(b("first"), 100).isEmpty());
        assertEquals(List.of("first"), strings(splitter.feed(b("second"), 200)));
        assertEquals(List.of("second"), strings(splitter.poll(250)));
    }

    @Test
    void completeDelimitedFrameStillHonorsMaximumSize() {
        var splitter = new SocketFrameSplitter(SocketFrameSplitter.Config.delimiter(b("\n"), false).withMaxFrameSize(4));
        assertEquals(List.of("abcd", "efgh", "ij"), strings(splitter.feed(b("abcdefghij\n"), 0)));
    }
}
