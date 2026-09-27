package com.aqishi.toolbox.feature.monitor.domain;

import javax.crypto.KeyAgreement;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.NamedParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Ephemeral X25519 (RFC 7748) helpers on top of the JDK's XDH provider.
 *
 * <p>Public keys travel as the raw 32-byte u-coordinate. The JDK only accepts
 * X.509 SubjectPublicKeyInfo, which for X25519 is a fixed 12-byte prefix
 * followed by the raw key, so conversion is a simple prefix add/strip.</p>
 */
final class X25519Keys {

    static final int KEY_LEN = 32;
    private static final byte[] X509_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
    };

    private X25519Keys() {
    }

    static KeyPair generate(SecureRandom random) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("X25519");
        generator.initialize(NamedParameterSpec.X25519, random);
        return generator.generateKeyPair();
    }

    static byte[] rawPublicKey(PublicKey key) {
        byte[] encoded = key.getEncoded();
        if (encoded.length != X509_PREFIX.length + KEY_LEN) {
            throw new IllegalStateException("unexpected X25519 public key encoding");
        }
        return Arrays.copyOfRange(encoded, X509_PREFIX.length, encoded.length);
    }

    static PublicKey decodePublicKey(byte[] raw) throws GeneralSecurityException {
        if (raw == null || raw.length != KEY_LEN) {
            throw new GeneralSecurityException("X25519 public key must be 32 bytes");
        }
        byte[] encoded = new byte[X509_PREFIX.length + KEY_LEN];
        System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, encoded, X509_PREFIX.length, KEY_LEN);
        return KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    /**
     * Computes the shared secret and rejects the all-zero output produced by
     * small-order public keys (RFC 7748 section 6.1).
     */
    static byte[] agree(PrivateKey privateKey, byte[] peerRawPublicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("XDH");
        agreement.init(privateKey);
        agreement.doPhase(decodePublicKey(peerRawPublicKey), true);
        byte[] secret = agreement.generateSecret();
        int accumulator = 0;
        for (byte b : secret) accumulator |= b;
        if (accumulator == 0) {
            throw new GeneralSecurityException("X25519 produced an all-zero shared secret");
        }
        return secret;
    }
}
