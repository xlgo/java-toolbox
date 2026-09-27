package com.aqishi.toolbox.feature.monitor.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * HKDF-SHA256 (RFC 5869) built on the JDK's HmacSHA256.
 */
public final class Hkdf {

    private static final String HMAC = "HmacSHA256";
    public static final int HASH_LEN = 32;

    private Hkdf() {
    }

    /** HKDF-Extract: PRK = HMAC(salt, ikm). An empty salt is replaced by 32 zero bytes. */
    public static byte[] extract(byte[] salt, byte[] ikm) throws GeneralSecurityException {
        byte[] effectiveSalt = salt == null || salt.length == 0 ? new byte[HASH_LEN] : salt;
        return hmac(effectiveSalt, ikm);
    }

    /** HKDF-Expand with a UTF-8 label as info. */
    public static byte[] expand(byte[] prk, String label, int length) throws GeneralSecurityException {
        return expand(prk, label.getBytes(StandardCharsets.US_ASCII), length);
    }

    /** HKDF-Expand: T(1) || T(2) ... truncated to {@code length} bytes. */
    public static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        if (length < 1 || length > 255 * HASH_LEN) {
            throw new IllegalArgumentException("invalid HKDF output length: " + length);
        }
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(prk, HMAC));
        byte[] out = new byte[length];
        byte[] previous = new byte[0];
        int written = 0;
        for (int counter = 1; written < length; counter++) {
            mac.update(previous);
            mac.update(info);
            mac.update((byte) counter);
            byte[] block = mac.doFinal();
            int take = Math.min(block.length, length - written);
            System.arraycopy(block, 0, out, written, take);
            written += take;
            Arrays.fill(previous, (byte) 0);
            previous = block;
        }
        Arrays.fill(previous, (byte) 0);
        return out;
    }

    /** HMAC-SHA256(key, data). */
    public static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance(HMAC);
        mac.init(new SecretKeySpec(key, HMAC));
        return mac.doFinal(data);
    }
}
