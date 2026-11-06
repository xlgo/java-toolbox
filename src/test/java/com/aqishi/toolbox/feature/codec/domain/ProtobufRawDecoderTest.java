package com.aqishi.toolbox.feature.codec.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.codec.domain.ProtobufRawDecoder.*;
import org.junit.jupiter.api.Test;

import java.util.List;

class ProtobufRawDecoderTest {

    @Test
    void decodesVarintStringAndNestedMessageFromHex() {
        // field 1 (varint): 150 (0x96 0x01)
        // field 2 (string): "testing"
        // field 3 (embedded message): { field 1: 42 }
        String hex = "08 96 01 12 07 74 65 73 74 69 6e 67 1a 02 08 2a";
        ProtobufRawDecoder decoder = new ProtobufRawDecoder();
        String result = decoder.decode(hex);

        assertTrue(result.contains("1: 150 (varint"), result);
        assertTrue(result.contains("2: \"testing\" (string, 7 bytes)"), result);
        assertTrue(result.contains("3: {"), result);
        assertTrue(result.contains("1: 42 (varint"), result);
    }

    @Test
    void decodesFromBase64Automatically() {
        // 08 96 01 12 07 74 65 73 74 69 6e 67 1a 02 08 2a 的 Base64:
        // CJYBEgd0ZXN0aW5nGgIIKg==
        String b64 = "CJYBEgd0ZXN0aW5nGgIIKg==";
        String result = new ProtobufRawDecoder().decode(b64);

        assertTrue(result.contains("1: 150"), result);
        assertTrue(result.contains("\"testing\""), result);
    }

    @Test
    void decodesFixed32AndFixed64() {
        // field 1 (fixed32): 123.456f
        // tag = (1 << 3) | 5 = 0x0d
        int floatBits = Float.floatToIntBits(123.456f);
        // field 2 (fixed64): 9876.54321d
        // tag = (2 << 3) | 1 = 0x11
        long doubleBits = Double.doubleToLongBits(9876.54321d);

        byte[] raw = new byte[1 + 4 + 1 + 8];
        raw[0] = 0x0d;
        for (int i = 0; i < 4; i++) raw[1 + i] = (byte) ((floatBits >>> (i * 8)) & 0xFF);
        raw[5] = 0x11;
        for (int i = 0; i < 8; i++) raw[6 + i] = (byte) ((doubleBits >>> (i * 8)) & 0xFF);

        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x ", b));

        String result = new ProtobufRawDecoder().decode(hex.toString());
        assertTrue(result.contains("fixed32"), result);
        assertTrue(result.contains("fixed64"), result);
        assertTrue(result.contains("123.456"), result);
        assertTrue(result.contains("9876.54321"), result);
    }

    @Test
    void handlesHexPrefixesAndDelimiters() {
        String formatted = "0x08:0x96:0x01, 0x12, 0x04, 0x74, 0x65, 0x73, 0x74";
        String result = new ProtobufRawDecoder().decode(formatted);
        assertTrue(result.contains("1: 150"), result);
        assertTrue(result.contains("\"test\""), result);
    }

    @Test
    void rejectsEmptyOrCorruptedInput() {
        assertThrows(IllegalArgumentException.class, () -> new ProtobufRawDecoder().decode(""));
        assertThrows(IllegalArgumentException.class, () -> new ProtobufRawDecoder().decode("not valid hex or base64 $$$$"));
    }
}
