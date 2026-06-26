package com.aqishi.toolbox.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SM3Utils 单元测试。
 */
class SM3UtilsTest {

    @Test
    void testHashEmptyString() {
        String result = SM3Utils.hash("");
        assertNotNull(result);
        assertEquals(64, result.length()); // SM3 输出 256 位 = 64 个十六进制字符
    }

    @Test
    void testHashKnownValue() {
        // SM3("abc") 的已知标准值
        String result = SM3Utils.hash("abc");
        assertEquals("66c7f0f462eeedd9d1f2d46bdc10e4e24167c4875cf2f7a2297da02b8f4ba8e0", result);
    }

    @Test
    void testHashChineseText() {
        String result = SM3Utils.hash("你好世界");
        assertNotNull(result);
        assertEquals(64, result.length());
    }

    @Test
    void testHashDeterministic() {
        String input = "hello sm3";
        String r1 = SM3Utils.hash(input);
        String r2 = SM3Utils.hash(input);
        assertEquals(r1, r2);
    }

    @Test
    void testHashBytes() {
        byte[] data = "test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String result = SM3Utils.hash(data);
        assertNotNull(result);
        assertEquals(64, result.length());
    }

    @Test
    void testHashBytesConsistentWithString() {
        String text = "consistency check";
        assertEquals(SM3Utils.hash(text), SM3Utils.hash(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void testHashBytesRaw() {
        byte[] result = SM3Utils.hashBytes("test");
        assertNotNull(result);
        assertEquals(32, result.length); // SM3 输出 32 字节
    }

    @Test
    void testHmac() {
        String hmac = SM3Utils.hmac("key123", "message");
        assertNotNull(hmac);
        assertEquals(64, hmac.length());
    }

    @Test
    void testHmacDeterministic() {
        String key = "secret";
        String msg = "data";
        assertEquals(SM3Utils.hmac(key, msg), SM3Utils.hmac(key, msg));
    }

    @Test
    void testHmacDifferentKeysProduceDifferentResults() {
        String msg = "same message";
        assertNotEquals(SM3Utils.hmac("key1", msg), SM3Utils.hmac("key2", msg));
    }

    @Test
    void testAvalancheEffect() {
        // 输入微小变化导致输出显著不同
        String r1 = SM3Utils.hash("hello");
        String r2 = SM3Utils.hash("Hello");
        assertNotEquals(r1, r2);
    }
}
