package com.aqishi.toolbox.feature.security.domain;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 对称加密 round-trip 测试：覆盖 AES/DES/3DES/SM4 的多模式与 IV 处理。
 */
class SymmetricUtilsTest {

    private static final String PLAIN = "阿启视 AqiVision 具身智能 — hello world 12345!@#";
    private final SecureRandom random = new SecureRandom();

    @Test
    void aesGcmAutoIvRoundTripBase64() throws Exception {
        byte[] key = Base64.getDecoder().decode(SymmetricUtils.generateKey("AES", 256));
        String cipher = SymmetricUtils.encrypt("AES", "GCM", "PKCS5Padding", PLAIN, key, null, false);
        assertNotEquals(PLAIN, cipher);
        assertEquals(PLAIN, SymmetricUtils.decrypt("AES", "GCM", "PKCS5Padding", cipher, key, null, false));
    }

    @Test
    void aesGcmCustomIvRoundTripHex() throws Exception {
        byte[] key = Base64.getDecoder().decode(SymmetricUtils.generateKey("AES", 128));
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        String cipher = SymmetricUtils.encrypt("AES", "GCM", "PKCS5Padding", PLAIN, key, iv, true);
        assertEquals(PLAIN, SymmetricUtils.decrypt("AES", "GCM", "PKCS5Padding", cipher, key, iv, true));
    }

    @Test
    void aesCbcAutoIvRoundTrip() throws Exception {
        byte[] key = Base64.getDecoder().decode(SymmetricUtils.generateKey("AES", 192));
        String cipher = SymmetricUtils.encrypt("AES", "CBC", "PKCS5Padding", PLAIN, key, null, false);
        assertEquals(PLAIN, SymmetricUtils.decrypt("AES", "CBC", "PKCS5Padding", cipher, key, null, false));
    }

    @Test
    void aesCbcCustomIvRoundTrip() throws Exception {
        byte[] key = Base64.getDecoder().decode(SymmetricUtils.generateKey("AES", 128));
        byte[] iv = new byte[16];
        random.nextBytes(iv);
        String cipher = SymmetricUtils.encrypt("AES", "CBC", "PKCS5Padding", PLAIN, key, iv, false);
        assertEquals(PLAIN, SymmetricUtils.decrypt("AES", "CBC", "PKCS5Padding", cipher, key, iv, false));
    }

    @Test
    void aesEcbRoundTrip() throws Exception {
        byte[] key = Base64.getDecoder().decode(SymmetricUtils.generateKey("AES", 128));
        String cipher = SymmetricUtils.encrypt("AES", "ECB", "PKCS5Padding", PLAIN, key, null, false);
        assertEquals(PLAIN, SymmetricUtils.decrypt("AES", "ECB", "PKCS5Padding", cipher, key, null, false));
    }

    @Test
    void desCbcRoundTrip() throws Exception {
        byte[] key = new byte[8];
        random.nextBytes(key);
        String cipher = SymmetricUtils.encrypt("DES", "CBC", "PKCS5Padding", PLAIN, key, null, false);
        assertEquals(PLAIN, SymmetricUtils.decrypt("DES", "CBC", "PKCS5Padding", cipher, key, null, false));
    }

    @Test
    void tripleDesEcbRoundTrip() throws Exception {
        byte[] key = new byte[24];
        random.nextBytes(key);
        String cipher = SymmetricUtils.encrypt("DESede", "ECB", "PKCS5Padding", PLAIN, key, null, false);
        assertEquals(PLAIN, SymmetricUtils.decrypt("DESede", "ECB", "PKCS5Padding", cipher, key, null, false));
    }

    @Test
    void sm4CbcRoundTripWhenProviderAvailable() throws Exception {
        // SM4 依赖 BouncyCastle；注册失败则跳过而非让其抛难以理解的错误
        if (SymmetricUtils.providerInitError() != null) {
            return;
        }
        byte[] key = Base64.getDecoder().decode(SymmetricUtils.generateKey("SM4", 128));
        String cipher = SymmetricUtils.encrypt("SM4", "CBC", "PKCS5Padding", PLAIN, key, null, false);
        assertEquals(PLAIN, SymmetricUtils.decrypt("SM4", "CBC", "PKCS5Padding", cipher, key, null, false));
    }

    @Test
    void helperPredicates() {
        assertTrue(SymmetricUtils.requiresIv("CBC"));
        assertTrue(SymmetricUtils.requiresIv("GCM"));
        assertFalse(SymmetricUtils.requiresIv("ECB"));
        assertTrue(SymmetricUtils.isAuthenticated("GCM"));
        assertFalse(SymmetricUtils.isAuthenticated("CBC"));
        assertFalse(SymmetricUtils.isAuthenticated("ECB"));
        assertEquals(16, SymmetricUtils.getBlockSize("AES"));
        assertEquals(16, SymmetricUtils.getBlockSize("SM4"));
        assertEquals(8, SymmetricUtils.getBlockSize("DES"));
        assertEquals(8, SymmetricUtils.getBlockSize("DESede"));
    }

    @Test
    void hexRoundTrip() {
        byte[] data = new byte[20];
        random.nextBytes(data);
        String hex = SymmetricUtils.bytesToHex(data);
        assertArrayEquals(data, SymmetricUtils.hexToBytes(hex));
        // 容忍空白
        assertArrayEquals(data, SymmetricUtils.hexToBytes(hex.replaceAll("(.{2})", "$1 ").trim()));
    }

    @Test
    void noPaddingRequiresBlockAlignedPlaintext() throws Exception {
        byte[] key = new byte[16];
        random.nextBytes(key);
        String aligned = "1234567890ABCDEF"; // 16 bytes in UTF-8
        String cipher = SymmetricUtils.encrypt("AES", "ECB", "NoPadding", aligned, key, null, false);
        assertEquals(aligned, SymmetricUtils.decrypt("AES", "ECB", "NoPadding", cipher, key, null, false));
    }

    @Test
    void generateKeyProducesValidBase64Key() throws Exception {
        String key = SymmetricUtils.generateKey("AES", 256);
        byte[] bytes = Base64.getDecoder().decode(key);
        assertEquals(32, bytes.length);
    }
}
