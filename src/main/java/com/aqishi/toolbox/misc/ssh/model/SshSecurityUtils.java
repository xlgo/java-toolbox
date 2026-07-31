package com.aqishi.toolbox.misc.ssh.model;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * SSH 凭据加密工具。
 *
 * 新格式使用随机盐、随机 nonce 和 AES-GCM。旧版本使用的无 nonce AES
 * 字符串仍可读取，用于迁移历史配置，但不会再用于新写入。
 */
public final class SshSecurityUtils {

    private static final String VERSION = "v2";
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String AAD = "JavaToolbox.SSH.Credential.v2";
    private static final int KEY_BITS = 128;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SshSecurityUtils() {
    }

    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            return "";
        }
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(salt),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return VERSION + "." + encoder.encodeToString(salt) + "."
                    + encoder.encodeToString(nonce) + "." + encoder.encodeToString(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法安全加密 SSH 凭据", e);
        }
    }

    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return "";
        }
        if (!isEncrypted(cipherText)) {
            return decryptLegacyOrPlainText(cipherText);
        }
        try {
            String[] parts = cipherText.split("\\.", -1);
            if (parts.length != 4) {
                throw new IllegalArgumentException("SSH 凭据密文格式无效");
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[1]);
            byte[] nonce = decoder.decode(parts[2]);
            byte[] encrypted = decoder.decode(parts[3]);
            if (salt.length != SALT_BYTES || nonce.length != NONCE_BYTES) {
                throw new IllegalArgumentException("SSH 凭据密文参数无效");
            }
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(salt),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(AAD.getBytes(StandardCharsets.UTF_8));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalArgumentException("SSH 凭据校验失败", e);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("SSH 凭据密文无效", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(VERSION + ".");
    }

    /** Re-encrypts old AES/plain values before a configuration file is written. */
    public static String migrate(String storedValue) {
        if (storedValue == null || storedValue.isEmpty() || isEncrypted(storedValue)) {
            return storedValue == null ? "" : storedValue;
        }
        return encrypt(decryptLegacyOrPlainText(storedValue));
    }

    private static String decryptLegacyOrPlainText(String value) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0) return value;
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(legacyKeyMaterial(), KEY_ALGORITHM));
            return new String(cipher.doFinal(decoded), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 仅用于一次性迁移旧版本已经写入的明文。
            return value;
        }
    }

    private static SecretKeySpec deriveKey(byte[] salt) throws GeneralSecurityException {
        String machineFactor = System.getProperty("user.name", "toolbox")
                + "\u0000" + System.getProperty("user.home", "")
                + "\u0000JavaToolbox.SSH";
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(machineFactor.toCharArray(), salt,
                PBKDF2_ITERATIONS, KEY_BITS);
        try {
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), KEY_ALGORITHM);
        } finally {
            ((PBEKeySpec) spec).clearPassword();
        }
    }

    private static byte[] legacyKeyMaterial() {
        byte[] raw = "JavaToolboxSshEnc2026".getBytes(StandardCharsets.UTF_8);
        byte[] key16 = new byte[16];
        System.arraycopy(raw, 0, key16, 0, Math.min(raw.length, key16.length));
        return key16;
    }
}
