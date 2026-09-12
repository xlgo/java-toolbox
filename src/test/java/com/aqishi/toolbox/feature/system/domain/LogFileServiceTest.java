package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogFileService} 的纯逻辑测试：分页读取、换行兼容、UTF-8 解码、
 * 超长行截断、上一页回溯与正则匹配区间。
 */
class LogFileServiceTest {

    @TempDir
    Path tempDir;

    private Path write(String name, String content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    @DisplayName("按 LF 逐行读取，末尾无换行也能取到最后一行")
    void readsLinesWithLfAndTrailingLineWithoutNewline() throws IOException {
        Path file = write("lf.log", "alpha\nbeta\ngamma");

        LogFileService.Chunk chunk =
                LogFileService.readLines(file, 0, 100, 1 << 20);

        assertEquals(List.of("alpha", "beta", "gamma"), chunk.getLines());
        assertTrue(chunk.isEndOfFile());
        assertFalse(chunk.isTruncated());
        assertEquals(0, chunk.getStartOffset());
        assertEquals(Files.size(file), chunk.getNextOffset());
    }

    @Test
    @DisplayName("同时兼容 CRLF 与孤立 CR 换行")
    void handlesCrlfAndLoneCr() throws IOException {
        Path file = write("crlf.log", "a\r\nb\rc\nd");

        LogFileService.Chunk chunk =
                LogFileService.readLines(file, 0, 100, 1 << 20);

        assertEquals(List.of("a", "b", "c", "d"), chunk.getLines());
        assertTrue(chunk.isEndOfFile());
    }

    @Test
    @DisplayName("从非零偏移起读，页边界与页内续读不重不漏")
    void resumesFromOffsetWithoutDuplication() throws IOException {
        // 每行 5 字节（"L000\n" …… "L003\n"），共 4 行 20 字节
        Path file = write("page.log", "L000\nL001\nL002\nL003\n");

        LogFileService.Chunk first = LogFileService.readLines(file, 0, 2, 1 << 20);
        assertEquals(List.of("L000", "L001"), first.getLines());
        assertTrue(first.isTruncated());
        assertFalse(first.isEndOfFile());
        assertEquals(10, first.getNextOffset());

        LogFileService.Chunk second =
                LogFileService.readLines(file, first.getNextOffset(), 2, 1 << 20);
        assertEquals(List.of("L002", "L003"), second.getLines());
        assertTrue(second.isEndOfFile());
        assertEquals(20, second.getNextOffset());
    }

    @Test
    @DisplayName("偏移越过文件末尾返回空页并标记 EOF")
    void offsetBeyondEofYieldsEmptyPage() throws IOException {
        Path file = write("small.log", "only\n");

        LogFileService.Chunk chunk = LogFileService.readLines(file, 999, 10, 1 << 20);

        assertTrue(chunk.getLines().isEmpty());
        assertTrue(chunk.isEndOfFile());
    }

    @Test
    @DisplayName("UTF-8 多字节中文按字符正确解码")
    void decodesUtf8ChineseText() throws IOException {
        Path file = write("zh.log", "第一行\n第二行\n第三行\n");

        LogFileService.Chunk chunk = LogFileService.readLines(file, 0, 10, 1 << 20);

        assertEquals(List.of("第一行", "第二行", "第三行"), chunk.getLines());
    }

    @Test
    @DisplayName("超长行按 MAX_LINE_CHARS 截断并追加省略标记")
    void truncatesOverlongLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LogFileService.MAX_LINE_CHARS + 500; i++) {
            sb.append('x');
        }
        sb.append('\n');
        Path file = write("huge.log", sb.toString());

        LogFileService.Chunk chunk = LogFileService.readLines(file, 0, 10, 1 << 20);

        assertEquals(1, chunk.getLines().size());
        String line = chunk.getLines().get(0);
        assertTrue(line.endsWith(" …"), "截断行应以省略标记结尾");
        assertEquals(LogFileService.MAX_LINE_CHARS + 2, line.length());
    }

    @Test
    @DisplayName("字节上限生效：单次读取不超过 maxBytes")
    void respectsByteLimit() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            sb.append("0123456789\n"); // 每行 11 字节
        }
        Path file = write("many.log", sb.toString());

        LogFileService.Chunk chunk = LogFileService.readLines(file, 0, 1_000_000, 55);

        assertTrue(chunk.getLines().size() <= 5, "55 字节内最多 5 行 11 字节行");
        assertTrue(chunk.getNextOffset() <= 55);
    }

    @Test
    @DisplayName("previousPageStart 回到固定行数之前")
    void computesPreviousPageStart() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("L").append(String.format("%03d", i)).append('\n'); // "L000\n" 每行 5 字节
        }
        Path file = write("back.log", sb.toString());

        // 第 5 行起始偏移 = 5 * 5 = 25，回退 3 行应到第 2 行起始 = 10
        long start = LogFileService.previousPageStart(file, 25, 3, 1 << 20);

        assertEquals(10, start);
    }

    @Test
    @DisplayName("previousPageStart 在文件开头附近退化为 0")
    void previousPageStartClampsAtZero() throws IOException {
        Path file = write("head.log", "A\nB\nC\n");

        assertEquals(0, LogFileService.previousPageStart(file, 0, 5, 1 << 20));
        assertEquals(0, LogFileService.previousPageStart(file, 2, 5, 1 << 20));
    }

    @Test
    @DisplayName("matchRanges 返回全部不重叠匹配区间")
    void matchesRanges() {
        List<int[]> ranges = LogFileService.matchRanges("abc123def456", Pattern.compile("\\d+"));

        assertEquals(2, ranges.size());
        assertArrayEquals(new int[]{3, 6}, ranges.get(0));
        assertArrayEquals(new int[]{9, 12}, ranges.get(1));
    }

    @Test
    @DisplayName("matchRanges 对空行 / 无匹配返回空列表")
    void matchesRangesEmpty() {
        assertTrue(LogFileService.matchRanges("", Pattern.compile("\\d+")).isEmpty());
        assertTrue(LogFileService.matchRanges("no digits here", Pattern.compile("\\d+")).isEmpty());
    }
}
