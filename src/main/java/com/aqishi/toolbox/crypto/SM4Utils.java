package com.aqishi.toolbox.crypto;

import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.modes.CBCBlockCipher;
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher;
import org.bouncycastle.crypto.paddings.PKCS7Padding;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * SM4 国密对称加密工具类（纯 BouncyCastle 轻量级 API，不依赖 JCE）。
 * <p>SM4 分组长度 128 位，密钥长度 128 位。</p>
 * <p>支持模式：ECB / CBC（PKCS7 填充）。</p>
 */
public final class SM4Utils {

    /** SM4 密钥长度（字节） */
    public static final int KEY_SIZE = 16;

    /** SM4 IV 长度（字节） */
    public static final int IV_SIZE = 16;

    private SM4Utils() {
    }

    /**
     * 生成 SM4 随机密钥。
     *
     * @return Base64 编码的密钥
     */
    public static String generateKey() {
        byte[] key = new byte[KEY_SIZE];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * SM4-ECB 加密。
     */
    public static String encryptECB(String plainText, String keyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            byte[] plainData = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] cipherData = processECB(true, plainData, keyBytes);
            return Base64.getEncoder().encodeToString(cipherData);
        } catch (Exception e) {
            throw new RuntimeException("SM4-ECB 加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * SM4-ECB 解密。
     */
    public static String decryptECB(String cipherBase64, String keyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            byte[] cipherData = Base64.getDecoder().decode(cipherBase64);
            byte[] plainData = processECB(false, cipherData, keyBytes);
            return new String(plainData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("SM4-ECB 解密失败：" + e.getMessage(), e);
        }
    }

    /**
     * SM4-CBC 加密（随机 IV，IV 拼在密文前）。
     */
    public static String encryptCBC(String plainText, String keyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            byte[] plainData = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] iv = new byte[IV_SIZE];
            new SecureRandom().nextBytes(iv);
            byte[] cipherData = processCBC(true, plainData, keyBytes, iv);
            byte[] all = new byte[IV_SIZE + cipherData.length];
            System.arraycopy(iv, 0, all, 0, IV_SIZE);
            System.arraycopy(cipherData, 0, all, IV_SIZE, cipherData.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (Exception e) {
            throw new RuntimeException("SM4-CBC 加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * SM4-CBC 解密（密文前 16 字节为 IV）。
     */
    public static String decryptCBC(String cipherBase64, String keyBase64) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
            byte[] all = Base64.getDecoder().decode(cipherBase64);
            byte[] iv = new byte[IV_SIZE];
            byte[] cipherData = new byte[all.length - IV_SIZE];
            System.arraycopy(all, 0, iv, 0, IV_SIZE);
            System.arraycopy(all, IV_SIZE, cipherData, 0, cipherData.length);
            byte[] plainData = processCBC(false, cipherData, keyBytes, iv);
            return new String(plainData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("SM4-CBC 解密失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部实现 ====================

    /** ECB 模式处理（PKCS7 填充） */
    private static byte[] processECB(boolean encrypt, byte[] data, byte[] key) {
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
                new SM4Engine(), new PKCS7Padding());
        cipher.init(encrypt, new KeyParameter(key));
        byte[] out = new byte[cipher.getOutputSize(data.length)];
        int len = cipher.processBytes(data, 0, data.length, out, 0);
        try {
            len += cipher.doFinal(out, len);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (len < out.length) {
            byte[] trimmed = new byte[len];
            System.arraycopy(out, 0, trimmed, 0, len);
            return trimmed;
        }
        return out;
    }

    /** CBC 模式处理（PKCS7 填充） */
    private static byte[] processCBC(boolean encrypt, byte[] data, byte[] key, byte[] iv) {
        PaddedBufferedBlockCipher cipher = new PaddedBufferedBlockCipher(
                new CBCBlockCipher(new SM4Engine()), new PKCS7Padding());
        cipher.init(encrypt, new ParametersWithIV(new KeyParameter(key), iv));
        byte[] out = new byte[cipher.getOutputSize(data.length)];
        int len = cipher.processBytes(data, 0, data.length, out, 0);
        try {
            len += cipher.doFinal(out, len);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (len < out.length) {
            byte[] trimmed = new byte[len];
            System.arraycopy(out, 0, trimmed, 0, len);
            return trimmed;
        }
        return out;
    }
}
