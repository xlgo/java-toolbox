package com.aqishi.toolbox.feature.security.domain;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.Security;
import java.util.Base64;

/**
 * 通用对称加密工具类：支持 AES、DES、3DES、SM4 算法。
 *
 * <p>支持 GCM（推荐，自带完整性校验）、CBC 与 ECB 模式。ECB 不提供语义安全
 * ——相同明文块永远产生相同密文块，会直接泄露数据模式——因此仅保留用于解密
 * 历史遗留数据，新数据请使用 GCM。</p>
 */
public final class SymmetricUtils {

    /** GCM 推荐的 96 位 nonce 长度。 */
    private static final int GCM_IV_LENGTH = 12;
    /** 认证标签长度（位），128 位是 GCM 的标准强度。 */
    private static final int GCM_TAG_BITS = 128;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static volatile String providerInitError;

    static {
        try {
            // 动态注册 BouncyCastle 提供者以支持 SM4 算法
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        } catch (Throwable error) {
            // SM4 依赖该提供者。静默吞掉会让加密在几步之后抛出难以理解的错误，
            // 所以记录下来并在真正用到 SM4 时给出明确原因。
            providerInitError = error.getClass().getSimpleName()
                    + ": " + error.getMessage();
        }
    }

    private SymmetricUtils() {
    }

    /** 支持的填充方式 */
    public static final String[] PADDINGS = {"PKCS5Padding", "NoPadding", "ISO10126Padding"};

    /** 支持的分组模式，GCM 在最前以便作为默认选择。 */
    public static final String[] MODES = {"GCM", "CBC", "ECB"};

    /**
     * BouncyCastle 提供者注册失败的原因，注册成功时为 null。
     *
     * <p>SM4 与国密相关功能依赖该提供者，UI 应据此提示用户而不是等到加解密
     * 抛出底层异常。</p>
     */
    public static String providerInitError() {
        return providerInitError;
    }

    /** 该模式是否需要 IV / nonce。 */
    public static boolean requiresIv(String mode) {
        return "CBC".equalsIgnoreCase(mode) || "GCM".equalsIgnoreCase(mode);
    }

    /** GCM 自带认证标签，不接受外部 padding 设置。 */
    public static boolean isAuthenticated(String mode) {
        return "GCM".equalsIgnoreCase(mode);
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
     * @param padding    填充方式 ("PKCS5Padding", "NoPadding")
     * @param plainText  明文
     * @param keyBytes   密钥字节数组
     * @param customIv   自定义 IV，如果为 CBC 模式且 customIv 为空，则自动生成随机 IV 拼在密文前
     * @param useHex     是否输出 Hex 字符串（否则输出 Base64）
     * @return 密文
     */
    public static String encrypt(String algorithm, String mode, String padding, String plainText, byte[] keyBytes, byte[] customIv, boolean useHex) throws Exception {
        byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);

        // NoPadding 要求原文长度为分组大小的整数倍
        if ("NoPadding".equalsIgnoreCase(padding)) {
            int blockSize = getBlockSize(algorithm);
            if (plainBytes.length % blockSize != 0) {
                throw new IllegalArgumentException("NoPadding 模式要求明文长度必须是 "
                        + blockSize + " 字节的整数倍（当前 " + plainBytes.length + " 字节）");
            }
        }

        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, algorithm);

        // GCM 是认证加密，只在 NoPadding 下定义，padding 选择对它没有意义。
        String effectivePadding = isAuthenticated(mode) ? "NoPadding" : padding;
        String transform = algorithm + "/" + mode + "/" + effectivePadding;
        Cipher cipher = createCipher(algorithm, transform);

        byte[] ivBytes = null;
        if ("GCM".equalsIgnoreCase(mode)) {
            ivBytes = resolveIv(customIv, GCM_IV_LENGTH);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, ivBytes));
        } else if ("CBC".equalsIgnoreCase(mode)) {
            int blockSize = cipher.getBlockSize();
            ivBytes = resolveIv(customIv, blockSize);
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
     * @param padding    填充方式 ("PKCS5Padding", "NoPadding")
     * @param cipherText 密文 (Base64 或 Hex 格式)
     * @param keyBytes   密钥字节数组
     * @param customIv   自定义 IV，若为 CBC 且为空，则默认密文前面拼有 IV
     * @param isHex      密文是否为 Hex 格式
     * @return 明文
     */
    public static String decrypt(String algorithm, String mode, String padding, String cipherText, byte[] keyBytes, byte[] customIv, boolean isHex) throws Exception {
        byte[] inputBytes = isHex ? hexToBytes(cipherText.trim()) : Base64.getDecoder().decode(cipherText.trim());
        SecretKeySpec keySpec = new SecretKeySpec(keyBytes, algorithm);

        String effectivePadding = isAuthenticated(mode) ? "NoPadding" : padding;
        String transform = algorithm + "/" + mode + "/" + effectivePadding;
        Cipher cipher = createCipher(algorithm, transform);

        byte[] cipherBytes;
        if ("GCM".equalsIgnoreCase(mode)) {
            byte[] nonce = new byte[GCM_IV_LENGTH];
            if (customIv != null && customIv.length > 0) {
                System.arraycopy(customIv, 0, nonce, 0, Math.min(customIv.length, GCM_IV_LENGTH));
                cipherBytes = inputBytes;
            } else {
                if (inputBytes.length < GCM_IV_LENGTH) {
                    throw new IllegalArgumentException("密文长度小于 GCM nonce 长度");
                }
                System.arraycopy(inputBytes, 0, nonce, 0, GCM_IV_LENGTH);
                cipherBytes = new byte[inputBytes.length - GCM_IV_LENGTH];
                System.arraycopy(inputBytes, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.length);
            }
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        } else if ("CBC".equalsIgnoreCase(mode)) {
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

        // A GCM tag mismatch throws AEADBadTagException: the payload was
        // tampered with or the key is wrong. Either way the plaintext is not
        // trustworthy, so it must never be surfaced to the caller.
        byte[] plainBytes = cipher.doFinal(cipherBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    /**
     * 选择密码实例。SM4 必须走 BouncyCastle，注册失败时给出可操作的提示。
     */
    private static Cipher createCipher(String algorithm, String transform) throws Exception {
        if ("SM4".equalsIgnoreCase(algorithm)) {
            if (providerInitError != null) {
                throw new IllegalStateException(
                        "BouncyCastle 提供者注册失败，无法使用 SM4：" + providerInitError);
            }
            return Cipher.getInstance(transform, "BC");
        }
        return Cipher.getInstance(transform);
    }

    /** 使用调用方提供的 IV，或在缺省时生成随机 IV。 */
    private static byte[] resolveIv(byte[] customIv, int length) {
        byte[] iv = new byte[length];
        if (customIv != null && customIv.length > 0) {
            System.arraycopy(customIv, 0, iv, 0, Math.min(customIv.length, length));
            return iv;
        }
        new SecureRandom().nextBytes(iv);
        return iv;
    }

    public static String bytesToHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(out);
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

    /** 获取分组大小（字节） */
    public static int getBlockSize(String algorithm) {
        switch (algorithm.toUpperCase()) {
            case "AES": case "SM4": return 16;
            case "DES": case "DESEDE": case "3DES": return 8;
            default: return 16;
        }
    }
}
