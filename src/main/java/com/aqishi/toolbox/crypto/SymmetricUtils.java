package com.aqishi.toolbox.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;

/**
 * 通用对称加密工具类：支持 AES、DES、3DES、SM4 算法。
 * 支持 ECB、CBC 模式与 PKCS5Padding 填充。
 */
public final class SymmetricUtils {

    static {
        try {
            // 动态注册 BouncyCastle 提供者以支持 SM4 算法
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        } catch (Throwable ignored) {
        }
    }

    private SymmetricUtils() {
    }

    /**
     * 生成随机密钥
     *
     * @param algorithm 算法名称 ("AES", "DES", "DESede" (3DES), "SM4")
     * @param keySize   密钥位长度 (AES: 128/192/256; DES: 64; 3DES: 192; SM4: 128)
     * @return Base64 编码的密钥字符串
     */
    public static String generateKey(String algorithm, int keySize) throws Exception {
        if ("SM4".equalsIgnoreCase(algorithm)) {
            byte[] key = new byte[16];
            new SecureRandom().nextBytes(key);
            return Base64.getEncoder().encodeToString(key);
        }
        KeyGenerator keyGen = KeyGenerator.getInstance(algorithm);
        keyGen.init(keySize, new SecureRandom());
        SecretKey secretKey = keyGen.generateKey();
        return Base64.getEncoder().encodeToString(secretKey.getEncoded());
    }

    /**
     * 加密
     *
     * @param algorithm  算法 ("AES", "DES", "DESede", "SM4")
     * @param mode       模式 ("ECB", "CBC")
     * @param plainText  明文
     * @param keyBytes   密钥字节数组
     * @param customIv   自定义 IV，如果为 CBC 模式且 customIv 为空，则自动生成随机 IV 拼在密文前
     * @param useHex     是否输出 Hex 字符串（否则输出 Base64）
     * @return 密文
     */
    public static String encrypt(String algorithm, String mode, String plainText, byte[] keyBytes, byte[] customIv, boolean useHex) throws Exception {
        byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, algorithm);
        
        // SM4 优先使用 BC Provider
        Cipher cipher = "SM4".equalsIgnoreCase(algorithm)
                ? Cipher.getInstance("SM4/" + mode + "/PKCS5Padding", "BC")
                : Cipher.getInstance(algorithm + "/" + mode + "/PKCS5Padding");

        byte[] ivBytes = null;
        if ("CBC".equalsIgnoreCase(mode)) {
            int blockSize = cipher.getBlockSize();
            if (customIv != null && customIv.length > 0) {
                ivBytes = new byte[blockSize];
                System.arraycopy(customIv, 0, ivBytes, 0, Math.min(customIv.length, blockSize));
            } else {
                ivBytes = new byte[blockSize];
                new SecureRandom().nextBytes(ivBytes);
            }
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
        } else {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
        }

        byte[] cipherBytes = cipher.doFinal(plainBytes);

        byte[] finalBytes;
        if (ivBytes != null && (customIv == null || customIv.length == 0)) {
            // 如果是自动生成的 IV，将 IV 拼在密文前面
            finalBytes = new byte[ivBytes.length + cipherBytes.length];
            System.arraycopy(ivBytes, 0, finalBytes, 0, ivBytes.length);
            System.arraycopy(cipherBytes, 0, finalBytes, ivBytes.length, cipherBytes.length);
        } else {
            finalBytes = cipherBytes;
        }

        return useHex ? bytesToHex(finalBytes) : Base64.getEncoder().encodeToString(finalBytes);
    }

    /**
     * 解密
     *
     * @param algorithm  算法 ("AES", "DES", "DESede", "SM4")
     * @param mode       模式 ("ECB", "CBC")
     * @param cipherText 密文 (Base64 或 Hex 格式)
     * @param keyBytes   密钥字节数组
     * @param customIv   自定义 IV，若为 CBC 且为空，则默认密文前面拼有 IV
     * @param isHex      密文是否为 Hex 格式
     * @return 明文
     */
    public static String decrypt(String algorithm, String mode, String cipherText, byte[] keyBytes, byte[] customIv, boolean isHex) throws Exception {
        byte[] inputBytes = isHex ? hexToBytes(cipherText.trim()) : Base64.getDecoder().decode(cipherText.trim());
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, algorithm);
        
        Cipher cipher = "SM4".equalsIgnoreCase(algorithm)
                ? Cipher.getInstance("SM4/" + mode + "/PKCS5Padding", "BC")
                : Cipher.getInstance(algorithm + "/" + mode + "/PKCS5Padding");

        byte[] cipherBytes;
        if ("CBC".equalsIgnoreCase(mode)) {
            int blockSize = cipher.getBlockSize();
            byte[] ivBytes = new byte[blockSize];
            if (customIv != null && customIv.length > 0) {
                System.arraycopy(customIv, 0, ivBytes, 0, Math.min(customIv.length, blockSize));
                cipherBytes = inputBytes;
            } else {
                if (inputBytes.length < blockSize) {
                    throw new IllegalArgumentException("密文长度小于 IV 分组长度");
                }
                System.arraycopy(inputBytes, 0, ivBytes, 0, blockSize);
                cipherBytes = new byte[inputBytes.length - blockSize];
                System.arraycopy(inputBytes, blockSize, cipherBytes, 0, cipherBytes.length);
            }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(ivBytes));
        } else {
            cipherBytes = inputBytes;
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
        }

        byte[] plainBytes = cipher.doFinal(cipherBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hexString) {
        String clean = hexString.replaceAll("\\s+", "");
        if (clean.length() % 2 != 0) {
            throw new IllegalArgumentException("Hex 字符个数必须为偶数");
        }
        byte[] data = new byte[clean.length() / 2];
        for (int i = 0; i < clean.length(); i += 2) {
            data[i / 2] = (byte) ((Character.digit(clean.charAt(i), 16) << 4)
                    + Character.digit(clean.charAt(i + 1), 16));
        }
        return data;
    }
}
