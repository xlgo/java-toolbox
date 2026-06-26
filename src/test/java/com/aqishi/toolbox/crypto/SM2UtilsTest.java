package com.aqishi.toolbox.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SM2Utils 单元测试。
 */
class SM2UtilsTest {

    @Test
    void testGenerateKeyPair() {
        SM2Utils.SM2KeyPair kp = SM2Utils.generateKeyPair();
        assertNotNull(kp);
        assertNotNull(kp.publicKey);
        assertNotNull(kp.privateKey);
        assertFalse(kp.publicKey.isEmpty());
        assertFalse(kp.privateKey.isEmpty());
        assertTrue(kp.publicKey.startsWith("04") || kp.publicKey.startsWith("3059")); // 非压缩或 DER 格式
    }

    @Test
    void testEncryptDecrypt() {
        SM2Utils.SM2KeyPair kp = SM2Utils.generateKeyPair();
        String plainText = "Hello SM2 国密算法";
        String cipher = SM2Utils.encrypt(plainText, kp.publicKey);
        assertNotNull(cipher);
        assertFalse(cipher.isEmpty());

        String decrypted = SM2Utils.decrypt(cipher, kp.privateKey);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEncryptDecryptChinese() {
        SM2Utils.SM2KeyPair kp = SM2Utils.generateKeyPair();
        String plainText = "你好，世界！SM2 非对称加密测试 🔐";
        String cipher = SM2Utils.encrypt(plainText, kp.publicKey);
        String decrypted = SM2Utils.decrypt(cipher, kp.privateKey);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEncryptDecryptLongText() {
        SM2Utils.SM2KeyPair kp = SM2Utils.generateKeyPair();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("国密SM2算法测试数据第").append(i).append("条。");
        }
        String plainText = sb.toString();
        String cipher = SM2Utils.encrypt(plainText, kp.publicKey);
        String decrypted = SM2Utils.decrypt(cipher, kp.privateKey);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testSignAndVerify() {
        SM2Utils.SM2KeyPair kp = SM2Utils.generateKeyPair();
        String message = "This is a test message for SM2 signature";
        String signature = SM2Utils.sign(message, kp.privateKey);
        assertNotNull(signature);
        assertFalse(signature.isEmpty());

        boolean verified = SM2Utils.verify(message, signature, kp.publicKey);
        assertTrue(verified);
    }

    @Test
    void testVerifyWithTamperedMessage() {
        SM2Utils.SM2KeyPair kp = SM2Utils.generateKeyPair();
        String message = "original message";
        String signature = SM2Utils.sign(message, kp.privateKey);

        // 篡改消息后验签应失败
        boolean verified = SM2Utils.verify("tampered message", signature, kp.publicKey);
        assertFalse(verified);
    }

    @Test
    void testVerifyWithWrongPublicKey() {
        SM2Utils.SM2KeyPair kp1 = SM2Utils.generateKeyPair();
        SM2Utils.SM2KeyPair kp2 = SM2Utils.generateKeyPair();
        String message = "test message";
        String signature = SM2Utils.sign(message, kp1.privateKey);

        // 用错误的公钥验签应失败
        boolean verified = SM2Utils.verify(message, signature, kp2.publicKey);
        assertFalse(verified);
    }

    @Test
    void testEncryptWithWrongKeyFails() {
        SM2Utils.SM2KeyPair kp1 = SM2Utils.generateKeyPair();
        SM2Utils.SM2KeyPair kp2 = SM2Utils.generateKeyPair();
        String plainText = "secret data";
        String cipher = SM2Utils.encrypt(plainText, kp1.publicKey);

        // 用错误的私钥解密应抛出异常
        assertThrows(RuntimeException.class, () -> {
            SM2Utils.decrypt(cipher, kp2.privateKey);
        });
    }

    @Test
    void testMultipleKeyPairsAreDifferent() {
        SM2Utils.SM2KeyPair kp1 = SM2Utils.generateKeyPair();
        SM2Utils.SM2KeyPair kp2 = SM2Utils.generateKeyPair();
        assertNotEquals(kp1.publicKey, kp2.publicKey);
        assertNotEquals(kp1.privateKey, kp2.privateKey);
    }
}
