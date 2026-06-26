package com.aqishi.toolbox.crypto;

import org.bouncycastle.crypto.digests.SM3Digest;

import java.nio.charset.StandardCharsets;

/**
 * SM3 国密哈希工具类（256 位输出，纯轻量级 API，不依赖 JCE）。
 * <p>SM3 是中国国家密码管理局发布的密码杂凑算法标准，
 * 适用于数字签名和验证、消息认证码的生成与验证以及随机数的生成。</p>
 */
public final class SM3Utils {

    private SM3Utils() {
    }

    /**
     * 计算字符串的 SM3 哈希值。
     *
     * @param text 输入文本
     * @return 十六进制格式的哈希值（64 位）
     */
    public static String hash(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        SM3Digest digest = new SM3Digest();
        digest.update(input, 0, input.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return bytesToHex(result);
    }

    /**
     * 计算字节数组的 SM3 哈希值。
     *
     * @param data 输入数据
     * @return 十六进制格式的哈希值（64 位）
     */
    public static String hash(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return bytesToHex(result);
    }

    /**
     * 计算字符串的 SM3 哈希值（返回原始字节）。
     *
     * @param text 输入文本
     * @return 32 字节哈希值
     */
    public static byte[] hashBytes(String text) {
        byte[] input = text.getBytes(StandardCharsets.UTF_8);
        SM3Digest digest = new SM3Digest();
        digest.update(input, 0, input.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    /**
     * 计算数据的 SM3 哈希值（返回原始字节）。
     *
     * @param data 输入数据
     * @return 32 字节哈希值
     */
    public static byte[] hashBytes(byte[] data) {
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] result = new byte[digest.getDigestSize()];
        digest.doFinal(result, 0);
        return result;
    }

    /**
     * SM3 HMAC 实现。
     *
     * @param key 密钥
     * @param text 消息
     * @return 十六进制格式的 HMAC 值
     */
    public static String hmac(String key, String text) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        // HMAC-SM3 实现：基于 RFC 2104
        final int blockSize = 64;
        byte[] keyPadded = new byte[blockSize];

        if (keyBytes.length > blockSize) {
            byte[] hashed = hashBytes(keyBytes);
            System.arraycopy(hashed, 0, keyPadded, 0, hashed.length);
        } else {
            System.arraycopy(keyBytes, 0, keyPadded, 0, keyBytes.length);
        }

        byte[] ipad = new byte[blockSize];
        byte[] opad = new byte[blockSize];
        for (int i = 0; i < blockSize; i++) {
            ipad[i] = (byte) (keyPadded[i] ^ 0x36);
            opad[i] = (byte) (keyPadded[i] ^ 0x5C);
        }

        byte[] innerData = new byte[blockSize + textBytes.length];
        System.arraycopy(ipad, 0, innerData, 0, blockSize);
        System.arraycopy(textBytes, 0, innerData, blockSize, textBytes.length);
        byte[] innerHash = hashBytes(innerData);

        byte[] outerData = new byte[blockSize + innerHash.length];
        System.arraycopy(opad, 0, outerData, 0, blockSize);
        System.arraycopy(innerHash, 0, outerData, blockSize, innerHash.length);

        return bytesToHex(hashBytes(outerData));
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
