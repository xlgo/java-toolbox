package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultCryptoTest {

    private final VaultCrypto crypto = new VaultCrypto();

    @Test
    void createsAesGcmThroughThePackagePrivateJcaBoundary() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        VaultCrypto boundaryCrypto = new VaultCrypto(() -> {
            creations.incrementAndGet();
            return Cipher.getInstance("AES/GCM/NoPadding");
        });
        byte[] key = boundaryCrypto.randomBytes(16);
        byte[] nonce = boundaryCrypto.randomBytes(12);
        byte[] aad = new byte[0];

        byte[] ciphertext = boundaryCrypto.encrypt(new byte[0], key, nonce, aad);
        boundaryCrypto.decrypt(ciphertext, key, nonce, aad);

        assertEquals(2, creations.get());
    }

    @Test
    void mapsEncryptionJcaAvailabilityFailuresToUnsupportedFormat() {
        assertAvailabilityFailure(() -> encryptWith(factoryFailure(
                new NoSuchAlgorithmException("algorithm unavailable"))));
        assertAvailabilityFailure(() -> encryptWith(factoryFailure(
                new NoSuchPaddingException("padding unavailable"))));
        assertAvailabilityFailure(() -> encryptWith(cipherFailure(Failure.INVALID_KEY)));
        assertAvailabilityFailure(() -> encryptWith(cipherFailure(Failure.INVALID_PARAMETER)));
        assertAvailabilityFailure(() -> encryptWith(cipherFailure(Failure.PROVIDER_REJECTION)));
    }

    @Test
    void mapsDecryptionJcaAvailabilityFailuresToUnsupportedFormat() {
        assertAvailabilityFailure(() -> decryptWith(factoryFailure(
                new NoSuchAlgorithmException("algorithm unavailable"))));
        assertAvailabilityFailure(() -> decryptWith(factoryFailure(
                new NoSuchPaddingException("padding unavailable"))));
        assertAvailabilityFailure(() -> decryptWith(cipherFailure(Failure.INVALID_KEY)));
        assertAvailabilityFailure(() -> decryptWith(cipherFailure(Failure.INVALID_PARAMETER)));
        assertAvailabilityFailure(() -> decryptWith(cipherFailure(Failure.PROVIDER_REJECTION)));
    }

    @Test
    void mapsGcmTagFailuresToAuthenticationFailureWithoutProviderCause() {
        assertAuthenticationFailed(() -> decryptWith(cipherFailure(Failure.AEAD_BAD_TAG)));
        assertAuthenticationFailed(() -> decryptWith(cipherFailure(Failure.BAD_PADDING)));
    }

    @Test
    void mapsEncryptionProcessingFailureToNonRetryableWriteFailure() {
        VaultException error = assertThrows(VaultException.class,
                () -> encryptWith(cipherFailure(Failure.ILLEGAL_BLOCK_SIZE)));

        assertEquals(VaultErrorCode.WRITE_FAILED, error.getCode());
        assertFalse(error.isRetryable());
    }

    @Test
    void doesNotMislabelDecryptionProcessingFailureAsAuthenticationFailure() {
        VaultException error = assertThrows(VaultException.class,
                () -> decryptWith(cipherFailure(Failure.ILLEGAL_BLOCK_SIZE)));

        assertEquals(VaultErrorCode.READ_FAILED, error.getCode());
        assertFalse(error.isRetryable());
    }

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
        assertNull(error.getCause());
    }

    private static void assertAvailabilityFailure(ThrowingOperation operation) {
        VaultException error = assertThrows(VaultException.class, operation::run);
        assertEquals(VaultErrorCode.UNSUPPORTED_FORMAT, error.getCode());
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

    private static VaultCrypto factoryFailure(GeneralSecurityException error) {
        return new VaultCrypto(() -> {
            throw error;
        });
    }

    private static VaultCrypto cipherFailure(Failure failure) {
        return new VaultCrypto(() -> new TestCipher(new FailingCipherSpi(failure)));
    }

    private static void encryptWith(VaultCrypto target) throws VaultException {
        target.encrypt(new byte[0], new byte[16], new byte[12], new byte[0]);
    }

    private static void decryptWith(VaultCrypto target) throws VaultException {
        target.decrypt(new byte[16], new byte[16], new byte[12], new byte[0]);
    }

    private enum Failure {
        INVALID_KEY,
        INVALID_PARAMETER,
        PROVIDER_REJECTION,
        AEAD_BAD_TAG,
        BAD_PADDING,
        ILLEGAL_BLOCK_SIZE
    }

    @SuppressWarnings("deprecation")
    private static final class TestCipher extends Cipher {
        private static final Provider PROVIDER = new Provider(
                "VaultCryptoTest", 1.0, "Test-only cipher provider") {
            private static final long serialVersionUID = 1L;
        };

        private TestCipher(CipherSpi cipherSpi) {
            super(cipherSpi, PROVIDER, "AES/GCM/NoPadding");
        }
    }

    private static final class FailingCipherSpi extends CipherSpi {
        private final Failure failure;

        private FailingCipherSpi(Failure failure) {
            this.failure = failure;
        }

        @Override
        protected void engineSetMode(String mode) {
        }

        @Override
        protected void engineSetPadding(String padding) {
        }

        @Override
        protected int engineGetBlockSize() {
            return 16;
        }

        @Override
        protected int engineGetOutputSize(int inputLen) {
            return inputLen;
        }

        @Override
        protected byte[] engineGetIV() {
            return new byte[12];
        }

        @Override
        protected AlgorithmParameters engineGetParameters() {
            return null;
        }

        @Override
        protected void engineInit(int opmode, Key key, SecureRandom random)
                throws InvalidKeyException {
            failForKeyOrProvider();
        }

        @Override
        protected void engineInit(
                int opmode,
                Key key,
                AlgorithmParameterSpec params,
                SecureRandom random)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            failForInitialization();
        }

        @Override
        protected void engineInit(
                int opmode,
                Key key,
                AlgorithmParameters params,
                SecureRandom random)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            failForInitialization();
        }

        @Override
        protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) {
            return Arrays.copyOfRange(input, inputOffset, inputOffset + inputLen);
        }

        @Override
        protected int engineUpdate(
                byte[] input,
                int inputOffset,
                int inputLen,
                byte[] output,
                int outputOffset) throws ShortBufferException {
            if (output.length - outputOffset < inputLen) {
                throw new ShortBufferException();
            }
            System.arraycopy(input, inputOffset, output, outputOffset, inputLen);
            return inputLen;
        }

        @Override
        protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen)
                throws IllegalBlockSizeException, BadPaddingException {
            failForProcessing();
            return Arrays.copyOfRange(input, inputOffset, inputOffset + inputLen);
        }

        @Override
        protected int engineDoFinal(
                byte[] input,
                int inputOffset,
                int inputLen,
                byte[] output,
                int outputOffset)
                throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
            failForProcessing();
            return engineUpdate(input, inputOffset, inputLen, output, outputOffset);
        }

        @Override
        protected void engineUpdateAAD(byte[] src, int offset, int len) {
        }

        private void failForKeyOrProvider() throws InvalidKeyException {
            if (failure == Failure.INVALID_KEY) {
                throw new InvalidKeyException("provider rejected key");
            }
            if (failure == Failure.PROVIDER_REJECTION) {
                throw new ProviderException("provider unavailable");
            }
        }

        private void failForInitialization()
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            failForKeyOrProvider();
            if (failure == Failure.INVALID_PARAMETER) {
                throw new InvalidAlgorithmParameterException("provider rejected parameters");
            }
        }

        private void failForProcessing()
                throws IllegalBlockSizeException, BadPaddingException {
            if (failure == Failure.AEAD_BAD_TAG) {
                throw new AEADBadTagException("provider tag detail");
            }
            if (failure == Failure.BAD_PADDING) {
                throw new BadPaddingException("provider tag detail");
            }
            if (failure == Failure.ILLEGAL_BLOCK_SIZE) {
                throw new IllegalBlockSizeException("provider processing detail");
            }
        }
    }

    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
