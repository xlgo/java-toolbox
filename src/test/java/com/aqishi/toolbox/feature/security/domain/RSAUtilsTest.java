package com.aqishi.toolbox.feature.security.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RSA 加密 / 签名 round-trip 测试。
 */
class RSAUtilsTest {

    private static final String MESSAGE = "阿启视 AqiVision 商机挖掘助手 — 机密数据 2026";

    @Test
    void encryptDecryptRoundTrip2048() throws Exception {
        RSAUtils.RSAKeyPair kp = RSAUtils.generateKeyPair(2048);
        String cipher = RSAUtils.encrypt(MESSAGE, kp.publicKey);
        assertNotEquals(MESSAGE, cipher);
        assertEquals(MESSAGE, RSAUtils.decrypt(cipher, kp.privateKey));
    }

    @Test
    void signVerifySha256() throws Exception {
        RSAUtils.RSAKeyPair kp = RSAUtils.generateKeyPair(2048);
        String sig = RSAUtils.sign(MESSAGE, kp.privateKey, "SHA256withRSA");
        assertTrue(RSAUtils.verify(MESSAGE, sig, kp.publicKey, "SHA256withRSA"));
    }

    @Test
    void verifyFailsOnTamperedMessage() throws Exception {
        RSAUtils.RSAKeyPair kp = RSAUtils.generateKeyPair(2048);
        String sig = RSAUtils.sign(MESSAGE, kp.privateKey, "SHA256withRSA");
        assertFalse(RSAUtils.verify(MESSAGE + " tampered", sig, kp.publicKey, "SHA256withRSA"));
    }

    @Test
    void keysAreDistinctPerGeneration() throws Exception {
        RSAUtils.RSAKeyPair a = RSAUtils.generateKeyPair(1024);
        RSAUtils.RSAKeyPair b = RSAUtils.generateKeyPair(1024);
        assertNotEquals(a.publicKey, b.publicKey);
        assertNotEquals(a.privateKey, b.privateKey);
    }
}
