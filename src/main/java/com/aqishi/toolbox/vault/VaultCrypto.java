package com.aqishi.toolbox.vault;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/** PBKDF2 and authenticated-encryption primitives for the vault. */
public final class VaultCrypto {
    private static final int KEY_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();

    public byte[] deriveKey(char[] password, byte[] salt, int iterations)
            throws VaultException {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(salt, "salt");
        if (salt.length == 0) {
            throw new IllegalArgumentException("salt must not be empty");
        }
        if (iterations <= 0) {
            throw new IllegalArgumentException("iterations must be positive");
        }

        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } catch (GeneralSecurityException error) {
            throw new VaultException(
                    VaultErrorCode.UNSUPPORTED_FORMAT,
                    "PBKDF2WithHmacSHA256 is unavailable",
                    false,
                    error);
        } finally {
            spec.clearPassword();
        }
    }

    public byte[] encrypt(byte[] plaintext, byte[] key, byte[] nonce, byte[] aad)
            throws VaultException {
        validateCipherParameters(plaintext, key, nonce, aad, "plaintext");
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(plaintext);
        } catch (GeneralSecurityException error) {
            throw new VaultException(
                    VaultErrorCode.WRITE_FAILED,
                    "Unable to encrypt vault",
                    true,
                    error);
        }
    }

    public byte[] decrypt(byte[] ciphertext, byte[] key, byte[] nonce, byte[] aad)
            throws VaultException {
        validateCipherParameters(ciphertext, key, nonce, aad, "ciphertext");
        if (ciphertext.length < TAG_BITS / 8) {
            throw new VaultException(
                    VaultErrorCode.AUTHENTICATION_FAILED,
                    "Unable to authenticate vault",
                    false);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException error) {
            throw new VaultException(
                    VaultErrorCode.AUTHENTICATION_FAILED,
                    "Unable to authenticate vault",
                    false,
                    error);
        }
    }

    public byte[] randomBytes(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        byte[] result = new byte[length];
        secureRandom.nextBytes(result);
        return result;
    }

    public static void wipe(byte[] value) {
        if (value != null) {
            Arrays.fill(value, (byte) 0);
        }
    }

    public static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    private static void validateCipherParameters(
            byte[] input, byte[] key, byte[] nonce, byte[] aad, String inputName) {
        Objects.requireNonNull(input, inputName);
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(aad, "aad");
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("key must be 16 bytes");
        }
        if (nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("nonce must be 12 bytes");
        }
    }
}
