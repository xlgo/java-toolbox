package com.aqishi.toolbox.feature.security.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SM3 哈希 / HMAC 测试（含国标标准向量）。
 */
class SM3UtilsTest {

    @Test
    void hashKnownVectorForAbc() {
        // GB/T 32905 标准测试向量：SM3("abc")
        String expected = "66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0";
        assertEquals(expected, SM3Utils.hash("abc"));
    }

    @Test
    void hashIsDeterministic() {
        String a = SM3Utils.hash("阿启视 AqiVision 2026");
        String b = SM3Utils.hash("阿启视 AqiVision 2026");
        assertEquals(a, b);
    }

    @Test
    void hashDiffersForDifferentInput() {
        assertNotEquals(SM3Utils.hash("foo"), SM3Utils.hash("bar"));
    }

    @Test
    void hashBytesEqualsHashString() {
        String text = "商机体智能平台";
        assertArrayEquals(SM3Utils.hashBytes(text), SM3Utils.hashBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void hmacProducesStableHex() {
        String a = SM3Utils.hmac("key", "message");
        String b = SM3Utils.hmac("key", "message");
        assertEquals(a, b);
        assertEquals(64, a.length());
        assertNotEquals(a, SM3Utils.hmac("key", "message2"));
    }
}
