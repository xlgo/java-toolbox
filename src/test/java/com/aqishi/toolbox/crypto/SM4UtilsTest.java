package com.aqishi.toolbox.crypto;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SM4Utils 单元测试。
 */
class SM4UtilsTest {

    @Test
    void testGenerateKey() {
        String key = SM4Utils.generateKey();
        assertNotNull(key);
        assertFalse(key.isEmpty());
        // Base64 编码的 16 字节密钥应为 24 字符（无填充时可能 22~24）
        byte[] decoded = java.util.Base64.getDecoder().decode(key);
        assertEquals(SM4Utils.KEY_SIZE, decoded.length);
    }

    @Test
    void testGenerateKeyUnique() {
        String key1 = SM4Utils.generateKey();
        String key2 = SM4Utils.generateKey();
        assertNotEquals(key1, key2);
    }

    // ===== ECB 模式测试 =====

    @Test
    void testEcbEncryptDecrypt() {
        String key = SM4Utils.generateKey();
        String plainText = "Hello SM4 ECB mode";
        String cipher = SM4Utils.encryptECB(plainText, key);
        assertNotNull(cipher);
        assertFalse(cipher.isEmpty());

        String decrypted = SM4Utils.decryptECB(cipher, key);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEcbEncryptDecryptChinese() {
        String key = SM4Utils.generateKey();
        String plainText = "你好世界！SM4 国密对称加密 🇨🇳";
        String cipher = SM4Utils.encryptECB(plainText, key);
        String decrypted = SM4Utils.decryptECB(cipher, key);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testEcbDecryptWithWrongKey() {
        String key1 = SM4Utils.generateKey();
        String key2 = SM4Utils.generateKey();
        String cipher = SM4Utils.encryptECB("secret", key1);

        assertThrows(RuntimeException.class, () -> {
            SM4Utils.decryptECB(cipher, key2);
        });
    }

    @Test
    void testEcbLongText() {
        String key = SM4Utils.generateKey();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("SM4国密算法分组加密测试第").append(i).append("组。");
        }
        String plainText = sb.toString();
        String cipher = SM4Utils.encryptECB(plainText, key);
        String decrypted = SM4Utils.decryptECB(cipher, key);
        assertEquals(plainText, decrypted);
    }

    // ===== CBC 模式测试 =====

    @Test
    void testCbcEncryptDecrypt() {
        String key = SM4Utils.generateKey();
        String plainText = "Hello SM4 CBC mode";
        String cipher = SM4Utils.encryptCBC(plainText, key);
        assertNotNull(cipher);
        assertFalse(cipher.isEmpty());

        String decrypted = SM4Utils.decryptCBC(cipher, key);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testCbcEncryptDecryptChinese() {
        String key = SM4Utils.generateKey();
        String plainText = "SM4-CBC 模式加密：包含中文和特殊字符！@#$%";
        String cipher = SM4Utils.encryptCBC(plainText, key);
        String decrypted = SM4Utils.decryptCBC(cipher, key);
        assertEquals(plainText, decrypted);
    }

    @Test
    void testCbcDifferentIvProducesDifferentCipher() {
        String key = SM4Utils.generateKey();
        String plainText = "same plaintext";
        String cipher1 = SM4Utils.encryptCBC(plainText, key);
        String cipher2 = SM4Utils.encryptCBC(plainText, key);
        // CBC 模式每次随机 IV，密文应不同
        assertNotEquals(cipher1, cipher2);
    }

    @Test
    void testCbcDecryptWithWrongKey() {
        String key1 = SM4Utils.generateKey();
        String key2 = SM4Utils.generateKey();
        String cipher = SM4Utils.encryptCBC("secret", key1);

        assertThrows(RuntimeException.class, () -> {
            SM4Utils.decryptCBC(cipher, key2);
        });
    }

    // ===== 交叉测试 =====

    @Test
    void testEcbAndCbcDifferentOutput() {
        String key = SM4Utils.generateKey();
        String plainText = "compare ecb and cbc";
        String ecbCipher = SM4Utils.encryptECB(plainText, key);
        String cbcCipher = SM4Utils.encryptCBC(plainText, key);
        // 模式不同，密文不同
        assertNotEquals(ecbCipher, cbcCipher);
    }

    @Test
    void testEmptyStringEncryptDecrypt() {
        String key = SM4Utils.generateKey();

        String ecbCipher = SM4Utils.encryptECB("", key);
        assertEquals("", SM4Utils.decryptECB(ecbCipher, key));

        String cbcCipher = SM4Utils.encryptCBC("", key);
        assertEquals("", SM4Utils.decryptCBC(cbcCipher, key));
    }
}
