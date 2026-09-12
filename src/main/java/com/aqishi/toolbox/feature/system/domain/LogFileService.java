package com.aqishi.toolbox.feature.system.domain;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大日志文件的按页读取与正则匹配（纯逻辑，便于单测）。
 *
 * <p>日志文件动辄数百 MB，直接把整个文件读进内存会 OOM，因此这里始终以
 * 「字节偏移 + 行数上限 + 字节上限」的窗口方式读取，只把当前页返回给 UI。
 * 逐行读取按字节扫描 {@code \n} / {@code \r\n}，再按 UTF-8 解码，避免使用
 * {@link RandomAccessFile#readLine()}（它按 ISO-8859-1 解码，会破坏中文）。</p>
 */
public final class LogFileService {

    /** 单行展示的最大字符数，防止某一超长行把内存撑爆。 */
    public static final int MAX_LINE_CHARS = 8192;

    private LogFileService() {
    }

    /** 一次读取的结果。 */
    public static final class Chunk {

        private final List<String> lines;
        private final long startOffset;
        private final long nextOffset;
        private final boolean endOfFile;
        private final boolean truncated;

        Chunk(List<String> lines, long startOffset, long nextOffset, boolean endOfFile, boolean truncated) {
            this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
            this.startOffset = startOffset;
            this.nextOffset = nextOffset;
            this.endOfFile = endOfFile;
            this.truncated = truncated;
        }

        public List<String> getLines() {
            return lines;
        }

        /** 本页起始字节偏移。 */
        public long getStartOffset() {
            return startOffset;
        }

        /** 下一页的起始字节偏移。 */
        public long getNextOffset() {
            return nextOffset;
        }

        public boolean isEndOfFile() {
            return endOfFile;
        }

        /** 是否因行数/字节上限提前截断（后面还有内容）。 */
        public boolean isTruncated() {
            return truncated;
        }
    }

    /**
     * 从 {@code fromOffset} 起按行读取一页。
     *
     * @param file      目标文件
     * @param fromOffset 起始字节偏移（从 0 开始；越界则返回空页）
     * @param maxLines  最多行数
     * @param maxBytes  最多读取字节数（防止超长行拖垮内存）
     */
    public static Chunk readLines(Path file, long fromOffset, int maxLines, long maxBytes) throws IOException {
        long fileSize = Files.size(file);
        if (fromOffset >= fileSize) {
            return new Chunk(Collections.emptyList(), Math.max(0, fileSize), fileSize, true, false);
        }
        List<String> lines = new ArrayList<>();
        boolean truncated = false;
        boolean endOfFile = false;
        long consumed = 0;
        long nextOffset = fromOffset;

        try (InputStream raw = new FileInputStream(file.toFile())) {
            skipFully(raw, fromOffset);
            InputStream in = new BufferedInputStream(raw, 64 * 1024);
            ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);
            boolean lineTruncated = false;
            while (lines.size() < maxLines && consumed < maxBytes) {
                int b = in.read();
                if (b == -1) {
                    endOfFile = true;
                    if (lineBuf.size() > 0 || lineTruncated) {
                        lines.add(decode(lineBuf, lineTruncated));
                    }
                    nextOffset = fromOffset + consumed;
                    break;
                }
                consumed++;
                if (b == '\n') {
                    lines.add(decode(lineBuf, lineTruncated));
                    lineBuf.reset();
                    lineTruncated = false;
                    nextOffset = fromOffset + consumed;
                } else if (b == '\r') {
                    // \r\n 与孤立 \r 都当作一个换行：探测下一个字节，若非 \n 则回退，
                    // 保留给下一行（BufferedInputStream 支持窗口内的 mark/reset）。
                    in.mark(1);
                    int n = in.read();
                    if (n == '\n') {
                        consumed++;
                    } else if (n != -1) {
                        in.reset();
                    }
                    lines.add(decode(lineBuf, lineTruncated));
                    lineBuf.reset();
                    lineTruncated = false;
                    nextOffset = fromOffset + consumed;
                } else {
                    if (lineBuf.size() < MAX_LINE_CHARS) {
                        lineBuf.write(b);
                    } else {
                        lineTruncated = true;
                    }
                }
            }
            if (!endOfFile && lineBuf.size() > 0) {
                // 命中行数/字节上限，把已读到的部分行作为本页最后一行
                lines.add(decode(lineBuf, lineTruncated));
                nextOffset = fromOffset + consumed;
            }
            if (!endOfFile && lines.size() >= maxLines) {
                truncated = nextOffset < fileSize;
            }
            // 恰好读到文件末尾（例如行数上限正好卡在最后一行）时，下一次读取不会有内容，
            // 这里直接标记 EOF，避免 UI 误判「还有下一页」。
            if (!endOfFile && nextOffset >= fileSize) {
                endOfFile = true;
                truncated = false;
            }
        }
        return new Chunk(lines, fromOffset, nextOffset, endOfFile, truncated);
    }

    /**
     * 计算「上一页」的起始字节偏移：向前回溯最多 {@code maxLines} 行。
     *
     * <p>为避免整文件扫描，只在 {@code [currentStart - maxBackBytes, currentStart)} 窗口内
     * 统计行首位置；窗口内不足 {@code maxLines} 行时返回窗口起点。</p>
     */
    public static long previousPageStart(Path file, long currentStart, int maxLines, long maxBackBytes)
            throws IOException {
        if (currentStart <= 0) {
            return 0;
        }
        long back = Math.min(currentStart, Math.max(maxBackBytes, 1));
        long windowStart = currentStart - back;
        byte[] buf = new byte[(int) back];
        try (FileChannel ch = FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            ch.position(windowStart);
            ByteBuffer bb = ByteBuffer.wrap(buf);
            while (bb.hasRemaining()) {
                if (ch.read(bb) == -1) {
                    break;
                }
            }
        }
        // 收集窗口内每个行首的绝对偏移（'\n' 之后的位置）
        List<Long> starts = new ArrayList<>();
        if (windowStart == 0) {
            starts.add(0L);
        }
        for (int i = 0; i < buf.length; i++) {
            if (buf[i] == '\n' && windowStart + i + 1 < currentStart) {
                starts.add(windowStart + i + 1);
            }
        }
        if (starts.isEmpty()) {
            return windowStart;
        }
        int index = Math.max(0, starts.size() - maxLines);
        return starts.get(index);
    }

    /** 返回一行中所有匹配区间 {@code [start, end)}。 */
    public static List<int[]> matchRanges(String line, Pattern pattern) {
        if (line == null || pattern == null) {
            return Collections.emptyList();
        }
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            if (matcher.end() > matcher.start()) {
                ranges.add(new int[]{matcher.start(), matcher.end()});
            }
        }
        return ranges;
    }

    private static String decode(ByteArrayOutputStream buf, boolean truncated) {
        String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
        if (truncated) {
            return text + " …";
        }
        return text;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    return;
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}
