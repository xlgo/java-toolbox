package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncodingDetectorTest {

    private static final String CHINESE = "编码探测测试：这是一段用于检测文件编码的中文文本，"
            + "包含常用汉字、标点符号以及 ASCII 字符 abc123。\n第二行内容继续描述转换工具的功能。\n";
    private static final String TRADITIONAL = "繁體中文測試：這是一段用於檢測檔案編碼的文字，"
            + "臺灣與香港常用正體字。\n第二行內容繼續說明轉換工具的功能。\n";
    private static final String JAPANESE = "これは日本語のテキストです。ファイルの文字コードを判定します。\n"
            + "ひらがなとカタカナと漢字が含まれています。\n";

    private final EncodingDetector detector = new EncodingDetector();

    private static Charset cs(String name) {
        return Charset.forName(name);
    }

    @Test
    void recognisesUtf8WithoutBom() {
        EncodingDetector.Result result = detector.detect(CHINESE.getBytes(StandardCharsets.UTF_8));
        assertEquals(StandardCharsets.UTF_8, result.charset());
        assertEquals(EncodingDetector.Reason.UTF8_VALID, result.reason());
        assertFalse(result.bom());
        assertTrue(result.confidence() > 0.95, "confidence " + result.confidence());
        // 其余解释只作为备选，置信度低于首选
        assertTrue(result.candidates().size() > 1);
        assertTrue(result.candidates().get(1).confidence() < result.confidence());
    }

    @Test
    void recognisesBoms() {
        byte[] body = CHINESE.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, body);
        EncodingDetector.Result utf8 = detector.detect(withBom);
        assertEquals(StandardCharsets.UTF_8, utf8.charset());
        assertTrue(utf8.bom());
        assertEquals(3, utf8.bomLength());
        assertEquals(EncodingDetector.Reason.BOM, utf8.reason());
        assertEquals(1.0, utf8.confidence());

        EncodingDetector.Result le = detector.detect(concat(new byte[]{(byte) 0xFF, (byte) 0xFE},
                CHINESE.getBytes(StandardCharsets.UTF_16LE)));
        assertEquals(StandardCharsets.UTF_16LE, le.charset());
        assertEquals(2, le.bomLength());

        EncodingDetector.Result be = detector.detect(concat(new byte[]{(byte) 0xFE, (byte) 0xFF},
                CHINESE.getBytes(StandardCharsets.UTF_16BE)));
        assertEquals(StandardCharsets.UTF_16BE, be.charset());

        EncodingDetector.Result utf32le = detector.detect(concat(new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 0},
                CHINESE.getBytes(cs("UTF-32LE"))));
        assertEquals(cs("UTF-32LE"), utf32le.charset());
        assertEquals(4, utf32le.bomLength());

        EncodingDetector.Result utf32be = detector.detect(concat(new byte[]{0, 0, (byte) 0xFE, (byte) 0xFF},
                CHINESE.getBytes(cs("UTF-32BE"))));
        assertEquals(cs("UTF-32BE"), utf32be.charset());
    }

    @Test
    void bomWithBrokenBodyIsStillReportedButLessConfident() {
        byte[] broken = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, new byte[]{'a', (byte) 0xC3});
        EncodingDetector.Result result = detector.detect(broken);
        assertEquals(StandardCharsets.UTF_8, result.charset());
        assertEquals(EncodingDetector.Reason.BOM_INVALID_BODY, result.reason());
        assertTrue(result.confidence() < 1.0);
    }

    @Test
    void recognisesGbk() {
        EncodingDetector.Result result = detector.detect(CHINESE.getBytes(cs("GBK")));
        assertEquals(cs("GBK"), result.charset());
        assertEquals(EncodingDetector.Reason.DOUBLE_BYTE_SCORE, result.reason());
        assertTrue(result.confidence() >= 0.8, "confidence " + result.confidence());
        assertTrue(result.candidates().size() > 1, "ranked alternatives are returned");
    }

    @Test
    void recognisesGb18030WithFourByteCharacter() {
        // U+20000 只能以 GB18030 四字节序列表示
        String text = CHINESE + "生僻字：" + new String(Character.toChars(0x20000)) + "。\n";
        byte[] bytes = text.getBytes(cs("GB18030"));
        EncodingDetector.Result result = detector.detect(bytes);
        assertEquals(cs("GB18030"), result.charset());
        assertTrue(result.confidence() >= 0.8, "confidence " + result.confidence());
    }

    @Test
    void recognisesUtf16WithoutBomByNulPattern() {
        EncodingDetector.Result le = detector.detect(CHINESE.getBytes(StandardCharsets.UTF_16LE));
        assertEquals(StandardCharsets.UTF_16LE, le.charset());
        assertEquals(EncodingDetector.Reason.UTF16_NUL_PATTERN, le.reason());
        assertFalse(le.bom());

        EncodingDetector.Result be = detector.detect(CHINESE.getBytes(StandardCharsets.UTF_16BE));
        assertEquals(StandardCharsets.UTF_16BE, be.charset());
        assertTrue(be.confidence() > 0.8, "confidence " + be.confidence());
    }

    @Test
    void recognisesPureCjkUtf16WithoutAnyNul() {
        String text = "中文编码测试这是某段没有任何半角字符的文本，用来检验无零字节时的判断。";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_16LE);
        for (byte b : bytes) {
            assertNotEquals(0, b, "sample must not contain NUL");
        }
        EncodingDetector.Result result = detector.detect(bytes);
        assertEquals(StandardCharsets.UTF_16LE, result.charset());
        assertEquals(EncodingDetector.Reason.UTF16_TEXT_PATTERN, result.reason());
    }

    @Test
    void recognisesBig5() {
        EncodingDetector.Result result = detector.detect(TRADITIONAL.getBytes(cs("Big5")));
        assertEquals(cs("Big5"), result.charset());
        assertTrue(result.confidence() >= 0.6, "confidence " + result.confidence());
    }

    @Test
    void recognisesShiftJis() {
        EncodingDetector.Result result = detector.detect(JAPANESE.getBytes(cs("Shift_JIS")));
        assertEquals(cs("windows-31j"), result.charset());
        assertTrue(result.confidence() >= 0.7, "confidence " + result.confidence());
    }

    @Test
    void reportsAsciiAndEmpty() {
        EncodingDetector.Result ascii = detector.detect("plain ascii\r\nline two\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals(StandardCharsets.US_ASCII, ascii.charset());
        assertEquals(EncodingDetector.Reason.ASCII, ascii.reason());
        assertTrue(ascii.ascii());
        assertEquals(1.0, ascii.confidence());

        EncodingDetector.Result empty = detector.detect(new byte[0]);
        assertEquals(EncodingDetector.Reason.EMPTY, empty.reason());
    }

    @Test
    void fallsBackToLatin1ForAccentedWesternText() {
        String text = "Café crème brûlée, naïve façade à la résumé.";
        EncodingDetector.Result result = detector.detect(text.getBytes(StandardCharsets.ISO_8859_1));
        assertEquals(StandardCharsets.ISO_8859_1, result.charset());
        assertEquals(EncodingDetector.Reason.SINGLE_BYTE_FALLBACK, result.reason());
        assertTrue(result.confidence() >= 0.6, "confidence " + result.confidence());
    }

    @Test
    void windows1252QuotesSelectWindows1252() {
        String text = "He said “hello” – it’s fine.";
        EncodingDetector.Result result = detector.detect(text.getBytes(cs("windows-1252")));
        assertEquals(cs("windows-1252"), result.charset());
    }

    @Test
    void detectsBinary() {
        byte[] data = new byte[4096];
        new Random(42).nextBytes(data);
        EncodingDetector.Result random = detector.detect(data);
        assertTrue(random.binary());
        assertNull(random.charset());

        // PNG 头：NUL 与控制字符
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R',
                0, 0, 1, 0, 0, 0, 1, 0, 8, 6, 0, 0, 0};
        assertTrue(detector.detect(png).binary());

        byte[] controls = new byte[200];
        Arrays.fill(controls, (byte) 'a');
        for (int i = 0; i < controls.length; i += 5) {
            controls[i] = 0x03;
        }
        assertTrue(detector.detect(controls).binary());
    }

    @Test
    void toleratesUtf8CutAtSampleBoundaryOnlyWhenTruncated() {
        byte[] full = CHINESE.getBytes(StandardCharsets.UTF_8);
        // 找一个落在三字节序列中间的切点
        int cut = 40;
        while ((full[cut] & 0xC0) != 0x80) {
            cut++;
        }
        EncodingDetector.Result prefix = detector.detect(full, cut, true);
        assertEquals(StandardCharsets.UTF_8, prefix.charset());
        assertEquals(EncodingDetector.Reason.UTF8_VALID, prefix.reason());
        assertTrue(prefix.sampleTruncated());

        // 同样的字节若就是整个文件，说明文件本身被截断了
        byte[] cutFile = Arrays.copyOf(full, cut);
        EncodingDetector.Result whole = detector.detect(cutFile);
        EncodingDetector.Candidate utf8 = whole.candidates().stream()
                .filter(candidate -> candidate.charset().equals(StandardCharsets.UTF_8))
                .findFirst().orElseThrow();
        assertEquals(EncodingDetector.Reason.UTF8_TRUNCATED_TAIL, utf8.reason());
        assertTrue(utf8.confidence() <= 0.5);
    }

    @Test
    void sampleLimitMarksResultTruncated() {
        EncodingDetector small = new EncodingDetector(64);
        byte[] data = (CHINESE + CHINESE).getBytes(StandardCharsets.UTF_8);
        EncodingDetector.Result result = small.detect(data);
        assertTrue(result.sampleTruncated());
        assertEquals(StandardCharsets.UTF_8, result.charset());
    }

    @Test
    void rejectsInvalidUtf8Forms() {
        // 过长编码、代理项、超出 U+10FFFF：都不是合法 UTF-8
        byte[][] invalid = {
                {'a', (byte) 0xC0, (byte) 0xAF, 'b'},
                {'a', (byte) 0xE0, (byte) 0x80, (byte) 0xAF, 'b'},
                {'a', (byte) 0xED, (byte) 0xA0, (byte) 0x80, 'b'},
                {'a', (byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80, 'b'},
                {'a', (byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80, 'b'}};
        for (byte[] bytes : invalid) {
            EncodingDetector.Result result = detector.detect(bytes);
            assertNotEquals(StandardCharsets.UTF_8, result.charset(), Arrays.toString(bytes));
        }
    }

    /**
     * 两三个汉字的 GBK 文本同时也是合法的 Big5 / Shift_JIS 字节，证据太少。
     * 期望：GBK 仍排第一（常用字分布占优），但置信度低于 0.6 的自动转换阈值，需用户确认。
     */
    @Test
    void shortGbkTextRanksGbkFirstWithLowConfidence() {
        for (String text : new String[]{"中文", "你好", "测试一"}) {
            EncodingDetector.Result result = detector.detect(text.getBytes(cs("GBK")));
            assertEquals(cs("GBK"), result.charset(), text);
            assertTrue(result.confidence() < 0.6, text + " confidence " + result.confidence());
            assertTrue(result.confidence() > 0.3, text + " confidence " + result.confidence());
            assertTrue(result.candidates().size() >= 2, text + " should offer alternatives");
        }
    }

    @Test
    void candidatesAreSortedByConfidence() {
        EncodingDetector.Result result = detector.detect(CHINESE.getBytes(cs("GBK")));
        for (int i = 1; i < result.candidates().size(); i++) {
            assertTrue(result.candidates().get(i - 1).confidence() >= result.candidates().get(i).confidence());
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
