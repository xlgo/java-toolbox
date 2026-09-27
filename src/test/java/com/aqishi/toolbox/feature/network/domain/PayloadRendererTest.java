package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PayloadRendererTest {

    @Test
    void rendersSpacedUppercaseHex() {
        assertEquals("48 65 6C", PayloadRenderer.toHex(new byte[]{0x48, 0x65, 0x6C}));
        assertEquals("00 FF", PayloadRenderer.toHex(new byte[]{0, (byte) 0xFF}));
        assertEquals("", PayloadRenderer.toHex(new byte[0]));
    }

    @Test
    void rendersHexDumpWithOffsetAndAsciiColumn() {
        byte[] data = "Hello, World!\r\n\u0001ABC".getBytes(StandardCharsets.US_ASCII);
        String dump = PayloadRenderer.hexDump(data);
        String[] lines = dump.split("\n");
        assertEquals(2, lines.length);
        assertEquals("0000: 48 65 6C 6C 6F 2C 20 57  6F 72 6C 64 21 0D 0A 01  |Hello, World!...|", lines[0]);
        assertEquals("0010: 41 42 43" + " ".repeat(42) + "|ABC|", lines[1]);
        assertEquals("", PayloadRenderer.hexDump(new byte[0]));
    }

    @Test
    void escapesOrDotsControlCharacters() {
        byte[] data = {'a', '\r', '\n', '\t', 0, 0x01, 0x7F, 'z'};
        assertEquals("a\\r\\n\\t\\0\\x01\\x7Fz",
                PayloadRenderer.text(data, StandardCharsets.US_ASCII, PayloadRenderer.ControlStyle.ESCAPE));
        assertEquals("a......z",
                PayloadRenderer.text(data, StandardCharsets.US_ASCII, PayloadRenderer.ControlStyle.DOT));
        assertEquals("plain", PayloadRenderer.text("plain".getBytes(StandardCharsets.US_ASCII),
                StandardCharsets.US_ASCII, PayloadRenderer.ControlStyle.ESCAPE));
    }

    @Test
    void decodesWithChosenCharset() {
        Charset gbk = Charset.forName("GBK");
        byte[] data = "中文".getBytes(gbk);
        assertEquals("中文", PayloadRenderer.text(data, gbk, PayloadRenderer.ControlStyle.ESCAPE));
    }

    @Test
    void rendersEachDisplayMode() {
        byte[] data = {'O', 'K', '\n'};
        Charset ascii = StandardCharsets.US_ASCII;
        PayloadRenderer.ControlStyle esc = PayloadRenderer.ControlStyle.ESCAPE;
        assertEquals("OK\\n", PayloadRenderer.render(data, PayloadDisplayMode.TEXT, ascii, esc, false));
        assertEquals("4F 4B 0A", PayloadRenderer.render(data, PayloadDisplayMode.HEX, ascii, esc, false));
        assertEquals("OK\\n  |  4F 4B 0A", PayloadRenderer.render(data, PayloadDisplayMode.BOTH, ascii, esc, false));
        assertEquals("OK\\n\n0000: 4F 4B 0A" + " ".repeat(40) + "  |OK.|",
                PayloadRenderer.render(data, PayloadDisplayMode.BOTH, ascii, esc, true));
    }
}
