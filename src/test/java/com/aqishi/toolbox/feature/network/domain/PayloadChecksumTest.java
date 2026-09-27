package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 校验值以 CRC RevEng 目录的 "123456789" 标准检验值为准。 */
class PayloadChecksumTest {

    private static final byte[] CHECK = "123456789".getBytes(StandardCharsets.US_ASCII);

    @Test
    void matchesStandardCheckValues() {
        assertEquals(0x4B37L, PayloadChecksum.CRC16_MODBUS.compute(CHECK));
        assertEquals(0x29B1L, PayloadChecksum.CRC16_CCITT_FALSE.compute(CHECK));
        assertEquals(0xCBF43926L, PayloadChecksum.CRC32.compute(CHECK));
        assertEquals(0xF4L, PayloadChecksum.CRC8.compute(CHECK));
        assertEquals(0xDDL, PayloadChecksum.SUM8.compute(CHECK)); // 0x1DD & 0xFF
        assertEquals(0x31L, PayloadChecksum.XOR8.compute(CHECK));
    }

    @Test
    void sumAndXorSmallCases() {
        assertEquals(0x00L, PayloadChecksum.SUM8.compute(new byte[]{(byte) 0xFF, 0x01}));
        assertEquals(0x03L, PayloadChecksum.SUM8.compute(new byte[]{0x01, 0x02}));
        assertEquals(0xFEL, PayloadChecksum.XOR8.compute(new byte[]{(byte) 0xFF, 0x01}));
        assertEquals(0x00L, PayloadChecksum.XOR8.compute(new byte[]{0x5A, 0x5A}));
        assertEquals(0L, PayloadChecksum.SUM8.compute(new byte[0]));
    }

    @Test
    void appendsWithProtocolByteOrder() {
        assertArrayEquals(new byte[]{0x37, 0x4B}, tail(PayloadChecksum.CRC16_MODBUS.append(CHECK), 2));
        assertArrayEquals(new byte[]{0x29, (byte) 0xB1}, tail(PayloadChecksum.CRC16_CCITT_FALSE.append(CHECK), 2));
        assertArrayEquals(new byte[]{(byte) 0xCB, (byte) 0xF4, 0x39, 0x26}, tail(PayloadChecksum.CRC32.append(CHECK), 4));
        assertArrayEquals(new byte[]{(byte) 0xF4}, tail(PayloadChecksum.CRC8.append(CHECK), 1));
        assertArrayEquals(CHECK, PayloadChecksum.NONE.append(CHECK));
        assertEquals(CHECK.length + 4, PayloadChecksum.CRC32.append(CHECK).length);
    }

    @Test
    void formatsWithWidthPadding() {
        assertEquals("0x4B37", PayloadChecksum.CRC16_MODBUS.format(0x4B37));
        assertEquals("0x0A", PayloadChecksum.SUM8.format(0x0A));
        assertEquals("0x0000ABCD", PayloadChecksum.CRC32.format(0xABCD));
        assertEquals("", PayloadChecksum.NONE.format(0));
    }

    private static byte[] tail(byte[] data, int count) {
        byte[] out = new byte[count];
        System.arraycopy(data, data.length - count, out, 0, count);
        return out;
    }
}
