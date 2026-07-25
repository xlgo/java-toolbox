package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultCryptoTest {

    private final VaultCrypto crypto = new VaultCrypto();

    @Test
    void derivesKnownPbkdf2HmacSha256Vector() throws Exception {
        byte[] key = crypto.deriveKey(
                "password".toCharArray(),
                "salt".getBytes(StandardCharsets.US_ASCII),
                1);

        assertEquals(16, key.length);
        assertEquals("120fb6cffcf8b32c43e7225256c4f837", hex(key));
    }

    @Test
    void leavesEmptyPasswordPolicyToTheServiceLayer() throws Exception {
        byte[] key = crypto.deriveKey(
                new char[0],
                "salt".getBytes(StandardCharsets.US_ASCII),
                1);

        assertEquals(16, key.length);
    }

    @Test
    void encryptsAndDecryptsWithAesGcm() throws Exception {
        byte[] plaintext = "vault payload".getBytes(StandardCharsets.UTF_8);
        byte[] key = crypto.randomBytes(16);
        byte[] nonce = crypto.randomBytes(12);
        byte[] aad = "authenticated header".getBytes(StandardCharsets.UTF_8);

        byte[] ciphertext = crypto.encrypt(plaintext, key, nonce, aad);

        assertEquals(plaintext.length + 16, ciphertext.length);
        assertFalse(Arrays.equals(plaintext, ciphertext));
        assertArrayEquals(plaintext, crypto.decrypt(ciphertext, key, nonce, aad));
    }

    @Test
    void mapsWrongKeyToAuthenticationFailure() throws Exception {
        byte[] key = crypto.randomBytes(16);
        byte[] wrongKey = crypto.randomBytes(16);
        byte[] nonce = crypto.randomBytes(12);
        byte[] aad = "header".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = crypto.encrypt(
                "payload".getBytes(StandardCharsets.UTF_8), key, nonce, aad);

        assertAuthenticationFailed(() -> crypto.decrypt(ciphertext, wrongKey, nonce, aad));
    }

    @Test
    void mapsChangedAadToAuthenticationFailure() throws Exception {
        byte[] key = crypto.randomBytes(16);
        byte[] nonce = crypto.randomBytes(12);
        byte[] ciphertext = crypto.encrypt(
                "payload".getBytes(StandardCharsets.UTF_8),
                key,
                nonce,
                "header".getBytes(StandardCharsets.UTF_8));

        assertAuthenticationFailed(() -> crypto.decrypt(
                ciphertext,
                key,
                nonce,
                "changed".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void mapsCiphertextTamperingToAuthenticationFailure() throws Exception {
        byte[] key = crypto.randomBytes(16);
        byte[] nonce = crypto.randomBytes(12);
        byte[] aad = "header".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = crypto.encrypt(
                "payload".getBytes(StandardCharsets.UTF_8), key, nonce, aad);
        ciphertext[0] ^= 1;

        assertAuthenticationFailed(() -> crypto.decrypt(ciphertext, key, nonce, aad));
    }

    @Test
    void mapsCiphertextShorterThanTheTagToAuthenticationFailure() {
        assertAuthenticationFailed(() -> crypto.decrypt(
                new byte[15], new byte[16], new byte[12], new byte[0]));
    }

    @Test
    void randomBytesReturnsRequestedLengthsAndFreshValues() {
        byte[] first = crypto.randomBytes(32);
        byte[] second = crypto.randomBytes(32);

        assertEquals(32, first.length);
        assertEquals(32, second.length);
        assertFalse(Arrays.equals(first, second));
        assertEquals(0, crypto.randomBytes(0).length);
    }

    @Test
    void rejectsNegativeRandomLength() {
        assertThrows(IllegalArgumentException.class, () -> crypto.randomBytes(-1));
    }

    @Test
    void wipesByteAndCharacterArrays() {
        byte[] bytes = {1, 2, 3};
        char[] characters = {'s', 'e', 'c', 'r', 'e', 't'};

        VaultCrypto.wipe(bytes);
        VaultCrypto.wipe(characters);

        assertArrayEquals(new byte[3], bytes);
        assertArrayEquals(new char[6], characters);
        assertDoesNotThrow(() -> VaultCrypto.wipe((byte[]) null));
        assertDoesNotThrow(() -> VaultCrypto.wipe((char[]) null));
    }

    @Test
    void rejectsInvalidDerivationParametersWithoutEchoingValues() {
        NullPointerException nullPassword = assertThrows(NullPointerException.class,
                () -> crypto.deriveKey(null, new byte[]{1}, 1));
        NullPointerException nullSalt = assertThrows(NullPointerException.class,
                () -> crypto.deriveKey("sensitive-password".toCharArray(), null, 1));
        IllegalArgumentException emptySalt = assertThrows(IllegalArgumentException.class,
                () -> crypto.deriveKey("sensitive-password".toCharArray(), new byte[0], 1));
        IllegalArgumentException zeroIterations = assertThrows(IllegalArgumentException.class,
                () -> crypto.deriveKey("sensitive-password".toCharArray(), new byte[]{1}, 0));

        assertDoesNotContain(nullPassword, "sensitive-password");
        assertDoesNotContain(nullSalt, "sensitive-password");
        assertDoesNotContain(emptySalt, "sensitive-password");
        assertDoesNotContain(zeroIterations, "sensitive-password");
    }

    @Test
    void rejectsNullEncryptionParameters() {
        byte[] key = new byte[16];
        byte[] nonce = new byte[12];
        byte[] aad = new byte[0];

        assertThrows(NullPointerException.class, () -> crypto.encrypt(null, key, nonce, aad));
        assertThrows(NullPointerException.class,
                () -> crypto.encrypt(new byte[0], null, nonce, aad));
        assertThrows(NullPointerException.class,
                () -> crypto.encrypt(new byte[0], key, null, aad));
        assertThrows(NullPointerException.class,
                () -> crypto.encrypt(new byte[0], key, nonce, null));
    }

    @Test
    void rejectsNullDecryptionParameters() {
        byte[] key = new byte[16];
        byte[] nonce = new byte[12];
        byte[] aad = new byte[0];

        assertThrows(NullPointerException.class, () -> crypto.decrypt(null, key, nonce, aad));
        assertThrows(NullPointerException.class,
                () -> crypto.decrypt(new byte[16], null, nonce, aad));
        assertThrows(NullPointerException.class,
                () -> crypto.decrypt(new byte[16], key, null, aad));
        assertThrows(NullPointerException.class,
                () -> crypto.decrypt(new byte[16], key, nonce, null));
    }

    @Test
    void rejectsNon128BitKeysAndNonStandardNonces() {
        byte[] plaintext = "private-payload".getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[0];

        IllegalArgumentException shortKey = assertThrows(IllegalArgumentException.class,
                () -> crypto.encrypt(plaintext, new byte[15], new byte[12], aad));
        IllegalArgumentException longKey = assertThrows(IllegalArgumentException.class,
                () -> crypto.decrypt(new byte[16], new byte[17], new byte[12], aad));
        IllegalArgumentException shortNonce = assertThrows(IllegalArgumentException.class,
                () -> crypto.encrypt(plaintext, new byte[16], new byte[11], aad));
        IllegalArgumentException longNonce = assertThrows(IllegalArgumentException.class,
                () -> crypto.decrypt(new byte[16], new byte[16], new byte[13], aad));

        assertDoesNotContain(shortKey, "private-payload");
        assertDoesNotContain(longKey, "private-payload");
        assertDoesNotContain(shortNonce, "private-payload");
        assertDoesNotContain(longNonce, "private-payload");
    }

    @Test
    void authenticationErrorsDoNotExposeSensitiveInputs() throws Exception {
        byte[] key = crypto.randomBytes(16);
        byte[] nonce = crypto.randomBytes(12);
        byte[] aad = "private-header".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = crypto.encrypt(
                "private-payload".getBytes(StandardCharsets.UTF_8), key, nonce, aad);

        VaultException error = assertThrows(VaultException.class,
                () -> crypto.decrypt(ciphertext, new byte[16], nonce, aad));

        assertDoesNotContain(error, "private-payload");
        assertDoesNotContain(error, "private-header");
        assertDoesNotContain(error, hex(key));
    }

    private static void assertAuthenticationFailed(ThrowingOperation operation) {
        VaultException error = assertThrows(VaultException.class, operation::run);
        assertEquals(VaultErrorCode.AUTHENTICATION_FAILED, error.getCode());
        assertFalse(error.isRetryable());
    }

    private static void assertDoesNotContain(Throwable error, String sensitiveValue) {
        Throwable current = error;
        while (current != null) {
            String description = current.toString();
            assertFalse(description.contains(sensitiveValue));
            current = current.getCause();
        }
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }

    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
