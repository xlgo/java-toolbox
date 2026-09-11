package com.aqishi.toolbox.feature.data.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KafkaMessageFormatTest {

    @Test
    void detectClassifiesByStructure() {
        assertEquals(KafkaMessageFormat.Kind.EMPTY, KafkaMessageFormat.detect(null));
        assertEquals(KafkaMessageFormat.Kind.EMPTY, KafkaMessageFormat.detect("  \n "));
        assertEquals(KafkaMessageFormat.Kind.JSON, KafkaMessageFormat.detect("{\"a\":1}"));
        assertEquals(KafkaMessageFormat.Kind.JSON, KafkaMessageFormat.detect(" [1,2] "));
        assertEquals(KafkaMessageFormat.Kind.XML, KafkaMessageFormat.detect("<root/>"));
        assertEquals(KafkaMessageFormat.Kind.PLAIN, KafkaMessageFormat.detect("hello"));
        // 只有起始符、没有配对的结尾符不算结构化
        assertEquals(KafkaMessageFormat.Kind.PLAIN, KafkaMessageFormat.detect("{ not closed"));
    }

    @Test
    void tryFormatJsonPrettyPrintsValidJson() {
        String formatted = KafkaMessageFormat.tryFormatJson("{\"a\":1}");
        assertTrue(formatted.contains("\n"), "美化后的 JSON 应当换行: " + formatted);
        assertTrue(formatted.contains("\"a\" : 1"), "实际输出: " + formatted);
    }

    @Test
    void tryFormatJsonFallsBackToRawOnInvalidContent() {
        String raw = "{ not really json }";
        assertEquals(raw, KafkaMessageFormat.tryFormatJson(raw));
    }

    @Test
    void tryFormatJsonNormalizesBlankToEmptyAndPassesPlainThrough() {
        assertEquals("", KafkaMessageFormat.tryFormatJson(null));
        assertEquals("", KafkaMessageFormat.tryFormatJson("   "));
        assertEquals("plain text", KafkaMessageFormat.tryFormatJson("plain text"));
    }

    @Test
    void isBinaryDataUsesFivePercentThreshold() {
        assertFalse(KafkaMessageFormat.isBinaryData(null));
        assertFalse(KafkaMessageFormat.isBinaryData(new byte[0]));
        assertFalse(KafkaMessageFormat.isBinaryData("纯文本 text".getBytes(StandardCharsets.UTF_8)));

        // 100 字节里 6 个不可打印字节 = 6% > 5%
        byte[] binary = new byte[100];
        java.util.Arrays.fill(binary, (byte) 'A');
        for (int i = 0; i < 6; i++) {
            binary[i] = 0x01;
        }
        assertTrue(KafkaMessageFormat.isBinaryData(binary));

        // 3 个不可打印字节 = 3%，仍按文本处理；制表/换行/回车不算不可打印
        byte[] mostlyText = new byte[100];
        java.util.Arrays.fill(mostlyText, (byte) 'A');
        mostlyText[0] = 0x01;
        mostlyText[1] = 0x09; // \t
        mostlyText[2] = 0x0A; // \n
        mostlyText[3] = 0x0D; // \r
        mostlyText[4] = 0x7F; // DEL 算不可打印
        mostlyText[5] = 0x02;
        assertFalse(KafkaMessageFormat.isBinaryData(mostlyText));
    }

    @Test
    void formatHexDumpRendersOffsetsHexAndAscii() {
        byte[] data = "Hello, World!!".getBytes(StandardCharsets.UTF_8); // 14 字节
        String dump = KafkaMessageFormat.formatHexDump(data);
        String[] lines = dump.split("\n");
        assertEquals(1, lines.length);
        assertTrue(lines[0].startsWith("00000000"), lines[0]);
        assertTrue(lines[0].contains("48 65 6C 6C 6F"), lines[0]);
        assertTrue(lines[0].contains("|Hello, World!!"), lines[0]);
        // 末尾不足 16 字节时 ASCII 区用空格补齐
        assertTrue(lines[0].endsWith("|"), lines[0]);
    }

    @Test
    void formatHexDumpHandlesEmptyInput() {
        assertEquals("[空数据]", KafkaMessageFormat.formatHexDump(null));
        assertEquals("[空数据]", KafkaMessageFormat.formatHexDump(new byte[0]));
    }

    @Test
    void parseHeadersSupportsEqualsAndColon() {
        List<KafkaMessageFormat.Header> headers = KafkaMessageFormat.parseHeaders(
                "trace-id=abc123\nsource: order-service\n# 注释\n\n无效行\n");
        assertEquals(2, headers.size());
        assertEquals(new KafkaMessageFormat.Header("trace-id", "abc123"), headers.get(0));
        assertEquals(new KafkaMessageFormat.Header("source", "order-service"), headers.get(1));
    }

    @Test
    void parseHeadersPrefersEqualsOverColon() {
        // 同时含两种分隔符时以 '=' 为准（与面板原逻辑一致）
        List<KafkaMessageFormat.Header> headers =
                KafkaMessageFormat.parseHeaders("url=http://x");
        assertEquals(List.of(new KafkaMessageFormat.Header("url", "http://x")), headers);
    }

    @Test
    void parseHeadersHandlesEmptyInput() {
        assertTrue(KafkaMessageFormat.parseHeaders(null).isEmpty());
        assertTrue(KafkaMessageFormat.parseHeaders(" \n# 只有注释").isEmpty());
    }
}
