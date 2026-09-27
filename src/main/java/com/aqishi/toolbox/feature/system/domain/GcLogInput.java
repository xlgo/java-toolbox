package com.aqishi.toolbox.feature.system.domain;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 以流的方式打开 GC 日志文件：按文件头嗅探字符集，并限制最多读取的字节数。
 *
 * <p>字符集判定与线程转储工具一致——先认 BOM（PowerShell 重定向出来的是 UTF-16LE），再用文件头
 * 做严格 UTF-8 校验，失败退回 {@code native.encoding}。只看文件头而不是整份文件，是为了不必
 * 为了判定编码先把几百兆读进内存；GC 日志几乎全是 ASCII，头部足以判断。</p>
 */
public final class GcLogInput {

    /** 嗅探字符集时读取的字节数。 */
    static final int SNIFF_BYTES = 64 * 1024;

    /**
     * 打开的输入。
     *
     * @param reader  已按字符集解码、跳过 BOM 的读取器，调用方负责关闭
     * @param charset 判定的字符集
     * @param size    文件大小（字节）
     */
    public record Opened(Reader reader, Charset charset, long size) {
    }

    private GcLogInput() {
    }

    /**
     * @param maxBytes 最多读取的字节数，超出部分被忽略（调用方应先按 {@link Opened#size()} 判定是否截断）
     */
    public static Opened open(Path file, long maxBytes) throws IOException {
        long size = Files.size(file);
        InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        try {
            raw.mark(SNIFF_BYTES + 1);
            byte[] head = raw.readNBytes(SNIFF_BYTES);
            raw.reset();
            Sniffed sniffed = sniff(head, head.length < SNIFF_BYTES);
            long skipped = raw.skip(sniffed.bomLength());
            InputStream limited = new Limited(raw, Math.max(0, maxBytes - skipped));
            return new Opened(new InputStreamReader(limited, sniffed.charset()), sniffed.charset(), size);
        } catch (IOException | RuntimeException error) {
            raw.close();
            throw error;
        }
    }

    /**
     * 文件开头的预览。
     *
     * @param text    开头若干字符
     * @param charset 判定的字符集
     */
    public record Preview(String text, Charset charset) {
    }

    /** 读取文件开头若干字符，用于输入框预览。 */
    public static Preview preview(Path file, int maxChars) throws IOException {
        Opened opened = open(file, (long) maxChars * 4);
        try (Reader reader = opened.reader()) {
            char[] buffer = new char[maxChars];
            int total = 0;
            int read;
            while (total < maxChars && (read = reader.read(buffer, total, maxChars - total)) > 0) {
                total += read;
            }
            return new Preview(new String(buffer, 0, total), opened.charset());
        }
    }

    record Sniffed(Charset charset, int bomLength) {
    }

    /**
     * @param complete {@code head} 是否就是整个文件；不是时末尾可能截断了一个多字节字符，校验前先让掉
     */
    static Sniffed sniff(byte[] head, boolean complete) {
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            return new Sniffed(StandardCharsets.UTF_8, 3);
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xFE) {
            return new Sniffed(StandardCharsets.UTF_16LE, 2);
        }
        if (head.length >= 2 && (head[0] & 0xFF) == 0xFE && (head[1] & 0xFF) == 0xFF) {
            return new Sniffed(StandardCharsets.UTF_16BE, 2);
        }
        if (head.length >= 4 && head[0] != 0 && head[1] == 0 && head[2] != 0 && head[3] == 0) {
            return new Sniffed(StandardCharsets.UTF_16LE, 0);
        }
        int length = head.length;
        if (!complete) {
            // 回退到最后一个完整字符的边界：UTF-8 续字节形如 10xxxxxx。
            int back = 0;
            while (back < 3 && length - back - 1 >= 0 && (head[length - back - 1] & 0xC0) == 0x80) {
                back++;
            }
            if (length - back - 1 >= 0 && (head[length - back - 1] & 0x80) != 0) {
                length = length - back - 1;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(head, 0, length));
            return new Sniffed(StandardCharsets.UTF_8, 0);
        } catch (CharacterCodingException notUtf8) {
            return new Sniffed(nativeCharset(), 0);
        }
    }

    private static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null) {
            try {
                return Charset.forName(name);
            } catch (RuntimeException ignored) {
                // 属性值无效时退回默认编码。
            }
        }
        return Charset.defaultCharset();
    }

    /** 读到上限就当作流结束。 */
    private static final class Limited extends FilterInputStream {
        private long remaining;

        Limited(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int count = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (count > 0) {
                remaining -= count;
            }
            return count;
        }
    }
}
