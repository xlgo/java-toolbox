package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadCodecTest {

    private static final byte[] HEL = {0x48, 0x65, 0x6C};

    @Test
    void parsesHexInAllCommonNotations() {
        assertArrayEquals(HEL, PayloadCodec.parseHex("48 65 6C"));
        assertArrayEquals(HEL, PayloadCodec.parseHex("48656C"));
        assertArrayEquals(HEL, PayloadCodec.parseHex("48656c"));
        assertArrayEquals(HEL, PayloadCodec.parseHex("0x48 0x65 0x6C"));
        assertArrayEquals(HEL, PayloadCodec.parseHex("0X48,0X65,0X6C"));
        assertArrayEquals(HEL, PayloadCodec.parseHex("48-65-6C"));
        assertArrayEquals(HEL, PayloadCodec.parseHex("48, 65,\n6C\r\n"));
        assertArrayEquals(HEL, PayloadCodec.parseHex("48:65;6C"));
        assertArrayEquals(HEL, PayloadCodec.parseHex("  4865 6C  "));
        assertArrayEquals(new byte[0], PayloadCodec.parseHex("   "));
        assertArrayEquals(new byte[]{(byte) 0xFF, 0x00}, PayloadCodec.parseHex("0xFF00"));
    }

    @Test
    void rejectsInvalidHexWithPosition() {
        PayloadParseException invalid = assertThrows(PayloadParseException.class,
                () -> PayloadCodec.parseHex("48 6G"));
        assertEquals(PayloadParseException.Code.INVALID_HEX_CHAR, invalid.getCode());
        assertEquals(4, invalid.getPosition());

        PayloadParseException odd = assertThrows(PayloadParseException.class,
                () -> PayloadCodec.parseHex("48 656"));
        assertEquals(PayloadParseException.Code.ODD_HEX_DIGITS, odd.getCode());
        assertEquals(3, odd.getPosition());

        PayloadParseException single = assertThrows(PayloadParseException.class,
                () -> PayloadCodec.parseHex("A"));
        assertEquals(PayloadParseException.Code.ODD_HEX_DIGITS, single.getCode());
        assertEquals(0, single.getPosition());

        PayloadParseException bareprefix = assertThrows(PayloadParseException.class,
                () -> PayloadCodec.parseHex("48 0x"));
        assertEquals(3, bareprefix.getPosition());
    }

    @Test
    void interpretsEscapeSequences() {
        Charset utf8 = StandardCharsets.UTF_8;
        assertArrayEquals(new byte[]{'a', '\r', '\n', '\t', 0, '\\', 'b'},
                PayloadCodec.encodeEscaped("a\\r\\n\\t\\0\\\\b", utf8));
        // \xNN 是原始字节，不能被 UTF-8 再编码成 C3 BF
        assertArrayEquals(new byte[]{'A', (byte) 0xFF, 'B'}, PayloadCodec.encodeEscaped("A\\xFFB", utf8));
        // backslash-u 是字符，按字符集编码
        assertArrayEquals("中".getBytes(utf8), PayloadCodec.encodeEscaped("\\u4E2D", utf8));
        assertArrayEquals("中".getBytes(Charset.forName("GBK")),
                PayloadCodec.encodeEscaped("\\u4e2d", Charset.forName("GBK")));
    }

    @Test
    void rejectsBadEscapesWithPosition() {
        PayloadParseException unknown = assertThrows(PayloadParseException.class,
                () -> PayloadCodec.encodeEscaped("ab\\q", StandardCharsets.UTF_8));
        assertEquals(PayloadParseException.Code.INVALID_ESCAPE, unknown.getCode());
        assertEquals(2, unknown.getPosition());

        assertEquals(1, assertThrows(PayloadParseException.class,
                () -> PayloadCodec.encodeEscaped("a\\x4", StandardCharsets.UTF_8)).getPosition());
        assertEquals(0, assertThrows(PayloadParseException.class,
                () -> PayloadCodec.encodeEscaped("\\xZZ", StandardCharsets.UTF_8)).getPosition());
        assertEquals(1, assertThrows(PayloadParseException.class,
                () -> PayloadCodec.encodeEscaped("a\\", StandardCharsets.UTF_8)).getPosition());
    }

    @Test
    void escapesAreLiteralWhenDisabled() {
        PayloadCodec.Spec spec = new PayloadCodec.Spec(PayloadFormat.TEXT, StandardCharsets.UTF_8,
                false, PayloadLineEnding.NONE, PayloadChecksum.NONE);
        assertArrayEquals("a\\n".getBytes(StandardCharsets.UTF_8), PayloadCodec.encode("a\\n", spec));
    }

    @Test
    void appendsLineEndingsToTextOnly() {
        for (PayloadLineEnding ending : PayloadLineEnding.values()) {
            PayloadCodec.Spec spec = new PayloadCodec.Spec(PayloadFormat.TEXT, StandardCharsets.US_ASCII,
                    false, ending, PayloadChecksum.NONE);
            byte[] expected = PayloadCodec.concat("AT".getBytes(StandardCharsets.US_ASCII), ending.bytes());
            assertArrayEquals(expected, PayloadCodec.encode("AT", spec), ending.name());
        }
        assertArrayEquals(new byte[]{'A', 'T', '\r', '\n'}, PayloadCodec.encode("AT",
                new PayloadCodec.Spec(PayloadFormat.TEXT, StandardCharsets.US_ASCII, false,
                        PayloadLineEnding.CRLF, PayloadChecksum.NONE)));
        // HEX 模式下不追加行尾
        assertArrayEquals(HEL, PayloadCodec.encode("48 65 6C",
                new PayloadCodec.Spec(PayloadFormat.HEX, StandardCharsets.UTF_8, false,
                        PayloadLineEnding.CRLF, PayloadChecksum.NONE)));
    }

    @Test
    void roundTripsGbkAndOtherCharsets() {
        String text = "温度=25.5℃";
        Charset gbk = Charset.forName("GBK");
        byte[] encoded = PayloadCodec.encode(text, new PayloadCodec.Spec(PayloadFormat.TEXT, gbk, false,
                PayloadLineEnding.NONE, PayloadChecksum.NONE));
        assertArrayEquals(text.getBytes(gbk), encoded);
        assertEquals(text, new String(encoded, gbk));

        for (String name : PayloadCodec.CHARSETS) {
            Charset charset = Charset.forName(name);
            byte[] bytes = PayloadCodec.encode("Hi", new PayloadCodec.Spec(PayloadFormat.TEXT, charset,
                    true, PayloadLineEnding.NONE, PayloadChecksum.NONE));
            assertEquals("Hi", new String(bytes, charset), name);
        }
        assertArrayEquals(new byte[]{'H', 0, 'i', 0}, PayloadCodec.encode("Hi",
                new PayloadCodec.Spec(PayloadFormat.TEXT, StandardCharsets.UTF_16LE, false,
                        PayloadLineEnding.NONE, PayloadChecksum.NONE)));
    }

    @Test
    void checksumCoversBodyAndLineEnding() {
        PayloadCodec.Spec modbus = new PayloadCodec.Spec(PayloadFormat.HEX, StandardCharsets.UTF_8, false,
                PayloadLineEnding.NONE, PayloadChecksum.CRC16_MODBUS);
        // 经典 Modbus RTU 读保持寄存器请求：01 03 00 00 00 0A → CRC C5 CD
        assertArrayEquals(PayloadCodec.parseHex("01 03 00 00 00 0A C5 CD"),
                PayloadCodec.encode("01 03 00 00 00 0A", modbus));

        PayloadCodec.Spec sum = new PayloadCodec.Spec(PayloadFormat.TEXT, StandardCharsets.US_ASCII, false,
                PayloadLineEnding.LF, PayloadChecksum.SUM8);
        byte[] out = PayloadCodec.encode("A", sum);
        assertArrayEquals(new byte[]{'A', '\n', (byte) ('A' + '\n')}, out);
    }

    @Test
    void presetBuildsSpecAndFallsBackOnUnknownValues() {
        PayloadPreset preset = new PayloadPreset("ping", PayloadFormat.HEX, "01 02",
                PayloadLineEnding.CRLF, PayloadChecksum.XOR8);
        assertEquals(PayloadFormat.HEX, preset.toSpec().getFormat());
        assertEquals(PayloadChecksum.XOR8, preset.toSpec().getChecksum());
        preset.setFormat("BOGUS");
        preset.setCharset("no-such-charset");
        preset.setChecksum(null);
        assertEquals(PayloadFormat.TEXT, preset.toSpec().getFormat());
        assertEquals(StandardCharsets.UTF_8, preset.toSpec().getCharset());
        assertEquals(PayloadChecksum.NONE, preset.toSpec().getChecksum());
    }
}
