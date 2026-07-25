package com.aqishi.toolbox.vault;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Non-sensitive, authenticated metadata around an encrypted vault payload. */
public class VaultEnvelope {
    public static final String FORMAT = "java-toolbox-vault";
    public static final int FORMAT_VERSION = 1;
    public static final String KDF = "PBKDF2WithHmacSHA256";
    public static final String CIPHER = "AES/GCM/NoPadding";
    public static final int MIN_ITERATIONS = 100_000;
    public static final int MAX_ITERATIONS = 5_000_000;
    public static final int NEW_FILE_ITERATIONS = 600_000;
    public static final int SALT_BYTES = 16;
    public static final int NONCE_BYTES = 12;
    public static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;

    private String format;
    private int formatVersion;
    private String kdfAlgorithm;
    private int iterations;
    private String salt;
    private String cipherAlgorithm;
    private String nonce;
    private String ciphertext;

    public VaultEnvelope() {
    }

    public static VaultEnvelope newEnvelope(int iterations, byte[] salt, byte[] nonce,
                                            byte[] ciphertext) {
        VaultEnvelope envelope = new VaultEnvelope();
        envelope.format = FORMAT;
        envelope.formatVersion = FORMAT_VERSION;
        envelope.kdfAlgorithm = KDF;
        envelope.iterations = iterations;
        envelope.salt = encode(salt);
        envelope.cipherAlgorithm = CIPHER;
        envelope.nonce = encode(nonce);
        envelope.ciphertext = encode(ciphertext);
        return envelope;
    }

    public byte[] aad() {
        String value = format + "|" + formatVersion + "|" + kdfAlgorithm + "|"
                + iterations + "|" + salt + "|" + cipherAlgorithm + "|" + nonce;
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public void validate() throws VaultException {
        if (!FORMAT.equals(format)) {
            throw invalid("Unrecognized vault format");
        }
        if (formatVersion > FORMAT_VERSION) {
            throw new VaultException(VaultErrorCode.UNSUPPORTED_FORMAT,
                    "Vault format version is newer than this application", false);
        }
        if (formatVersion != FORMAT_VERSION) {
            throw invalid("Invalid vault format version");
        }
        if (!KDF.equals(kdfAlgorithm) || !CIPHER.equals(cipherAlgorithm)) {
            throw invalid("Vault algorithm metadata is invalid");
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw invalid("Vault iteration count is outside the safe range");
        }

        byte[] decodedSalt = decode(salt);
        byte[] decodedNonce = decode(nonce);
        byte[] decodedCiphertext = decode(ciphertext);
        if (decodedSalt.length != SALT_BYTES || decodedNonce.length != NONCE_BYTES
                || decodedCiphertext.length == 0) {
            throw invalid("Vault binary metadata has an invalid length");
        }
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getKdfAlgorithm() {
        return kdfAlgorithm;
    }

    public void setKdfAlgorithm(String kdfAlgorithm) {
        this.kdfAlgorithm = kdfAlgorithm;
    }

    public int getIterations() {
        return iterations;
    }

    public void setIterations(int iterations) {
        this.iterations = iterations;
    }

    public String getSalt() {
        return salt;
    }

    public void setSalt(String salt) {
        this.salt = salt;
    }

    public String getCipherAlgorithm() {
        return cipherAlgorithm;
    }

    public void setCipherAlgorithm(String cipherAlgorithm) {
        this.cipherAlgorithm = cipherAlgorithm;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(String ciphertext) {
        this.ciphertext = ciphertext;
    }

    private static String encode(byte[] value) {
        return value == null ? null : Base64.getEncoder().encodeToString(value);
    }

    private static byte[] decode(String value) throws VaultException {
        if (value == null) {
            throw invalid("Vault Base64 field is missing");
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new VaultException(VaultErrorCode.INVALID_ENVELOPE,
                    "Vault Base64 field is invalid", false, error);
        }
    }

    private static VaultException invalid(String message) {
        return new VaultException(VaultErrorCode.INVALID_ENVELOPE, message, false);
    }
}
