package com.aqishi.toolbox.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Hex 查表编码测试：与 {@code String.format("%02x")} 逐字节结果保持一致。
 */
class HexTest {

    @Test
    void toHexMatchesStringFormat() {
        byte[] data = new byte[256];
        for (int i = 0; i < 256; i++) {
            data[i] = (byte) i;
        }
        StringBuilder expected = new StringBuilder();
        for (byte b : data) {
            expected.append(String.format("%02x", b & 0xff));
        }
        assertEquals(expected.toString(), Hex.toHex(data));
    }

    @Test
    void upperAndLowerDifferOnlyInCase() {
        byte[] data = {(byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef};
        assertEquals("deadbeef", Hex.toHex(data));
        assertEquals("DEADBEEF", Hex.toHexUpper(data));
    }

    @Test
    void emptyArrayYieldsEmptyString() {
        assertEquals("", Hex.toHex(new byte[0]));
        assertEquals("", Hex.toHexUpper(new byte[0]));
    }

    @Test
    void partialRangeEncodesOnlySlice() {
        byte[] data = {(byte) 0x01, (byte) 0x02, (byte) 0x03, (byte) 0x04};
        assertEquals("0203", Hex.toHex(data, 1, 2));
        assertEquals("0203", Hex.toHexUpper(data, 1, 2));
    }

    @Test
    void outOfRangeThrows() {
        byte[] data = {1, 2, 3};
        assertThrows(IndexOutOfBoundsException.class, () -> Hex.toHex(data, 2, 5));
        assertThrows(IndexOutOfBoundsException.class, () -> Hex.toHex(data, -1, 1));
    }
}
