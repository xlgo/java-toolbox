package com.aqishi.toolbox.feature.monitor.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Short authentication string shown on both ends of a remote-desktop session.
 *
 * <p>Derived from the handshake transcript hash, which covers both ephemeral
 * public keys and both nonces. A man in the middle necessarily runs two
 * handshakes with two different transcripts, so the two users see different
 * codes. The controller commits to its key before seeing the host's (see
 * {@link SecureChannelHandshake}), which stops the attacker from grinding keys
 * until the two 6-digit codes collide: each attack attempt succeeds with
 * probability 10^-6.</p>
 */
public final class Sas {

    private static final byte[] LABEL =
            "java-toolbox remote-desktop sas v1".getBytes(StandardCharsets.US_ASCII);
    private static final int MODULUS = 1_000_000;

    private Sas() {
    }

    /** Returns six digits formatted as {@code "123 456"}. */
    public static String fromTranscript(byte[] transcriptHash) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(LABEL);
            byte[] digest = sha.digest(transcriptHash);
            long value = ((digest[0] & 0xFFL) << 24) | ((digest[1] & 0xFFL) << 16)
                    | ((digest[2] & 0xFFL) << 8) | (digest[3] & 0xFFL);
            int code = (int) (value % MODULUS);
            String digits = String.format(java.util.Locale.ROOT, "%06d", code);
            return digits.substring(0, 3) + " " + digits.substring(3);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
