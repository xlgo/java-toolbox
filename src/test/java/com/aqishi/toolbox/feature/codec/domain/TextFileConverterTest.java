package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.feature.codec.domain.TextFileConverter.BomMode;
import com.aqishi.toolbox.feature.codec.domain.TextFileConverter.LineEnding;
import com.aqishi.toolbox.feature.codec.domain.TextFileConverter.Options;
import com.aqishi.toolbox.feature.codec.domain.TextFileConverter.Result;
import com.aqishi.toolbox.feature.codec.domain.TextFileConverter.Status;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFileConverterTest {

    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset GB18030 = Charset.forName("GB18030");
    private static final String TEXT = "第一行：中文内容\r\n第二行 second line\r\n第三行\r\n";

    private static Options to(Charset target) {
        return new Options(target, BomMode.REMOVE, LineEnding.KEEP, false);
    }

    @Test
    void convertsGbkToUtf8AndBack() {
        byte[] gbk = TEXT.getBytes(GBK);
        Result utf8 = TextFileConverter.convert(gbk, GBK, to(StandardCharsets.UTF_8));
        assertEquals(Status.CONVERTED, utf8.status());
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), utf8.output());
        assertEquals(3, utf8.found().crlf());
        assertEquals(TextFileConverter.LineStats.Style.CRLF, utf8.found().style());

        Result back = TextFileConverter.convert(utf8.output(), StandardCharsets.UTF_8, to(GBK));
        assertArrayEquals(gbk, back.output());

        Result gb18030 = TextFileConverter.convert(gbk, GBK, to(GB18030));
        assertEquals(TEXT, new String(gb18030.output(), GB18030));
    }

    @Test
    void roundTripsUtf16WithBom() {
        byte[] utf8 = TEXT.getBytes(StandardCharsets.UTF_8);
        Options le = new Options(StandardCharsets.UTF_16LE, BomMode.ADD, LineEnding.KEEP, false);
        Result result = TextFileConverter.convert(utf8, StandardCharsets.UTF_8, le);
        assertTrue(result.targetBom());
        assertEquals((byte) 0xFF, result.output()[0]);
        assertEquals((byte) 0xFE, result.output()[1]);
        TextFileConverter.Decoded decoded = TextFileConverter.decode(result.output(), StandardCharsets.UTF_16LE);
        assertEquals(2, decoded.bomLength());
        assertEquals(TEXT, decoded.text());

        Options be = new Options(StandardCharsets.UTF_16BE, BomMode.ADD, LineEnding.KEEP, false);
        Result bigEndian = TextFileConverter.convert(result.output(), StandardCharsets.UTF_16LE, be);
        assertEquals((byte) 0xFE, bigEndian.output()[0]);
        assertEquals(TEXT, TextFileConverter.decode(bigEndian.output(), StandardCharsets.UTF_16BE).text());
    }

    @Test
    void reportsUnmappableCharacterWithLine() {
        String text = "line one\nline two has an emoji " + new String(Character.toChars(0x1F600)) + "\n";
        Result result = TextFileConverter.convert(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
                to(GBK));
        assertEquals(Status.FAILED, result.status());
        assertEquals(TextFileConverter.Failure.UNMAPPABLE_CHARACTER, result.failure());
        assertEquals(0x1F600, result.errorCodePoint());
        assertEquals(2, result.errorLine());
        assertNull(result.output());
    }

    @Test
    void replacesUnmappableWhenAsked() {
        String text = "ok " + new String(Character.toChars(0x1F600)) + " done";
        Options replace = new Options(GBK, BomMode.REMOVE, LineEnding.KEEP, true);
        Result result = TextFileConverter.convert(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8,
                replace);
        assertEquals(Status.CONVERTED, result.status());
        assertEquals(1, result.replacements());
        assertEquals("ok ? done", new String(result.output(), GBK));
    }

    @Test
    void strictDecodeFailureReportsOffsetAndLine() {
        byte[] good = "abc\ndef\n".getBytes(StandardCharsets.US_ASCII);
        byte[] bad = Arrays.copyOf(good, good.length + 3);
        bad[good.length] = 'x';
        bad[good.length + 1] = (byte) 0xC3; // 缺少续字节
        bad[good.length + 2] = 'y';
        Result result = TextFileConverter.convert(bad, StandardCharsets.UTF_8, to(GBK));
        assertEquals(Status.FAILED, result.status());
        assertEquals(TextFileConverter.Failure.MALFORMED_INPUT, result.failure());
        assertEquals(good.length + 1, result.errorOffset());
        assertEquals(3, result.errorLine());

        // GBK 字节按 UTF-8 严格解码必然失败，而不是静默替换
        TextFileConverter.Decoded decoded = TextFileConverter.decode(TEXT.getBytes(GBK), StandardCharsets.UTF_8);
        assertFalse(decoded.ok());
        assertTrue(decoded.errorOffset() >= 0);
    }

    @Test
    void offsetAccountsForBom() {
        byte[] data = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', (byte) 0xFF};
        TextFileConverter.Decoded decoded = TextFileConverter.decode(data, StandardCharsets.UTF_8);
        assertFalse(decoded.ok());
        assertEquals(4, decoded.errorOffset());
    }

    @Test
    void normalizesLineEndings() {
        String mixed = "a\r\nb\nc\rd";
        TextFileConverter.LineStats stats = TextFileConverter.LineStats.of(mixed);
        assertEquals(1, stats.crlf());
        assertEquals(1, stats.lf());
        assertEquals(1, stats.cr());
        assertEquals(TextFileConverter.LineStats.Style.MIXED, stats.style());

        byte[] data = mixed.getBytes(StandardCharsets.UTF_8);
        assertEquals("a\nb\nc\nd", convertLines(data, LineEnding.LF));
        assertEquals("a\r\nb\r\nc\r\nd", convertLines(data, LineEnding.CRLF));
        assertEquals("a\rb\rc\rd", convertLines(data, LineEnding.CR));
        assertEquals(mixed, convertLines(data, LineEnding.KEEP));
        assertEquals(TextFileConverter.LineStats.Style.NONE, TextFileConverter.LineStats.of("x").style());
    }

    private static String convertLines(byte[] data, LineEnding ending) {
        Result result = TextFileConverter.convert(data, StandardCharsets.UTF_8,
                new Options(null, BomMode.KEEP, ending, false));
        return new String(result.output(), StandardCharsets.UTF_8);
    }

    @Test
    void addsKeepsAndRemovesBom() {
        byte[] plain = TEXT.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = concat(TextFileConverter.bomFor(StandardCharsets.UTF_8), plain);

        Result added = TextFileConverter.convert(plain, StandardCharsets.UTF_8,
                new Options(StandardCharsets.UTF_8, BomMode.ADD, LineEnding.KEEP, false));
        assertEquals(Status.CONVERTED, added.status());
        assertArrayEquals(withBom, added.output());

        Result removed = TextFileConverter.convert(withBom, StandardCharsets.UTF_8,
                new Options(StandardCharsets.UTF_8, BomMode.REMOVE, LineEnding.KEEP, false));
        assertTrue(removed.sourceBom());
        assertArrayEquals(plain, removed.output());

        Result kept = TextFileConverter.convert(withBom, StandardCharsets.UTF_8,
                new Options(StandardCharsets.UTF_8, BomMode.KEEP, LineEnding.KEEP, false));
        assertEquals(Status.UNCHANGED, kept.status());

        // 非 Unicode 目标没有 BOM：ADD 被忽略
        Result gbk = TextFileConverter.convert(withBom, StandardCharsets.UTF_8,
                new Options(GBK, BomMode.ADD, LineEnding.KEEP, false));
        assertFalse(gbk.targetBom());
        assertArrayEquals(TEXT.getBytes(GBK), gbk.output());
    }

    @Test
    void identicalOutputIsReportedUnchanged() {
        byte[] utf8 = TEXT.getBytes(StandardCharsets.UTF_8);
        assertEquals(Status.UNCHANGED,
                TextFileConverter.convert(utf8, StandardCharsets.UTF_8, to(StandardCharsets.UTF_8)).status());

        // 纯 ASCII 文件转 UTF-8 也是空操作
        byte[] ascii = "plain\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals(Status.UNCHANGED,
                TextFileConverter.convert(ascii, StandardCharsets.US_ASCII, to(StandardCharsets.UTF_8)).status());

        // 只换行不同则需要转换
        assertEquals(Status.CONVERTED, TextFileConverter.convert(utf8, StandardCharsets.UTF_8,
                new Options(StandardCharsets.UTF_8, BomMode.REMOVE, LineEnding.LF, false)).status());
    }

    @Test
    void handlesEmptyInput() {
        Result result = TextFileConverter.convert(new byte[0], StandardCharsets.UTF_8, to(GBK));
        assertEquals(Status.UNCHANGED, result.status());
        assertEquals(0, result.output().length);
    }

    @Test
    void genericUtf16SourceUsesItsBom() {
        byte[] data = concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "hi".getBytes(StandardCharsets.UTF_16LE));
        TextFileConverter.Decoded decoded = TextFileConverter.decode(data, StandardCharsets.UTF_16);
        assertEquals("hi", decoded.text());
        assertEquals(2, decoded.bomLength());
    }

    @Test
    void largeInputGrowsBuffers() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            builder.append("行").append(i).append('\n');
        }
        String text = builder.toString();
        Result result = TextFileConverter.convert(text.getBytes(GBK), GBK,
                new Options(StandardCharsets.UTF_16BE, BomMode.ADD, LineEnding.CRLF, false));
        TextFileConverter.Decoded decoded = TextFileConverter.decode(result.output(), StandardCharsets.UTF_16BE);
        assertEquals(text.replace("\n", "\r\n"), decoded.text());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
