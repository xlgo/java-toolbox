package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VaultEnvelopeTest {

    @Test
    void newEnvelopeUsesFixedFormatAndDeterministicAad() throws Exception {
        byte[] salt = new byte[VaultEnvelope.SALT_BYTES];
        byte[] nonce = new byte[VaultEnvelope.NONCE_BYTES];
        byte[] ciphertext = new byte[]{1, 2, 3};
        Arrays.fill(salt, (byte) 1);
        Arrays.fill(nonce, (byte) 2);

        VaultEnvelope envelope = VaultEnvelope.newEnvelope(
                VaultEnvelope.NEW_FILE_ITERATIONS, salt, nonce, ciphertext);
        String expected = VaultEnvelope.FORMAT + "|" + VaultEnvelope.FORMAT_VERSION + "|"
                + VaultEnvelope.KDF + "|" + VaultEnvelope.NEW_FILE_ITERATIONS + "|"
                + Base64.getEncoder().encodeToString(salt) + "|" + VaultEnvelope.CIPHER + "|"
                + Base64.getEncoder().encodeToString(nonce);

        envelope.validate();
        assertEquals(VaultEnvelope.FORMAT, envelope.getFormat());
        assertEquals(VaultEnvelope.FORMAT_VERSION, envelope.getFormatVersion());
        assertEquals(VaultEnvelope.KDF, envelope.getKdfAlgorithm());
        assertEquals(VaultEnvelope.CIPHER, envelope.getCipherAlgorithm());
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), envelope.aad());

        salt[0] = 9;
        nonce[0] = 9;
        ciphertext[0] = 9;
        envelope.validate();
        assertArrayEquals(expected.getBytes(StandardCharsets.UTF_8), envelope.aad());
        assertEquals("AQID", envelope.getCiphertext());
    }

    @Test
    void acceptsIterationBounds() throws Exception {
        validEnvelope(VaultEnvelope.MIN_ITERATIONS).validate();
        validEnvelope(VaultEnvelope.MAX_ITERATIONS).validate();
    }

    @Test
    void rejectsUnsafeIterationCounts() {
        assertInvalid(VaultEnvelope.newEnvelope(
                VaultEnvelope.MIN_ITERATIONS - 1, new byte[16], new byte[12], new byte[32]));
        assertInvalid(VaultEnvelope.newEnvelope(
                VaultEnvelope.MAX_ITERATIONS + 1, new byte[16], new byte[12], new byte[32]));
    }

    @Test
    void rejectsMalformedEnvelopeFields() {
        VaultEnvelope wrongFormat = validEnvelope();
        wrongFormat.setFormat("other");
        assertInvalid(wrongFormat);

        VaultEnvelope oldVersion = validEnvelope();
        oldVersion.setFormatVersion(0);
        assertInvalid(oldVersion);

        VaultEnvelope wrongKdf = validEnvelope();
        wrongKdf.setKdfAlgorithm("PBKDF2WithHmacSHA1");
        assertInvalid(wrongKdf);

        VaultEnvelope wrongCipher = validEnvelope();
        wrongCipher.setCipherAlgorithm("AES/CBC/PKCS5Padding");
        assertInvalid(wrongCipher);

        VaultEnvelope invalidSaltBase64 = validEnvelope();
        invalidSaltBase64.setSalt("not base64!");
        assertInvalid(invalidSaltBase64);

        VaultEnvelope invalidNonceBase64 = validEnvelope();
        invalidNonceBase64.setNonce("not base64!");
        assertInvalid(invalidNonceBase64);

        VaultEnvelope invalidCiphertextBase64 = validEnvelope();
        invalidCiphertextBase64.setCiphertext("not base64!");
        assertInvalid(invalidCiphertextBase64);

        VaultEnvelope shortSalt = validEnvelope();
        shortSalt.setSalt(Base64.getEncoder().encodeToString(new byte[15]));
        assertInvalid(shortSalt);

        VaultEnvelope longNonce = validEnvelope();
        longNonce.setNonce(Base64.getEncoder().encodeToString(new byte[13]));
        assertInvalid(longNonce);

        VaultEnvelope emptyCiphertext = validEnvelope();
        emptyCiphertext.setCiphertext("");
        assertInvalid(emptyCiphertext);
    }

    @Test
    void classifiesOnlyRecognizedFutureVersionsAsUnsupported() {
        VaultEnvelope future = validEnvelope();
        future.setFormatVersion(VaultEnvelope.FORMAT_VERSION + 1);
        assertCode(VaultErrorCode.UNSUPPORTED_FORMAT, future);

        VaultEnvelope unrecognizedFuture = validEnvelope();
        unrecognizedFuture.setFormat("other");
        unrecognizedFuture.setFormatVersion(VaultEnvelope.FORMAT_VERSION + 1);
        assertCode(VaultErrorCode.INVALID_ENVELOPE, unrecognizedFuture);
    }

    @Test
    void jacksonStyleBeanCanBePopulatedAndValidated() throws Exception {
        VaultEnvelope source = validEnvelope();
        VaultEnvelope envelope = new VaultEnvelope();
        envelope.setFormat(source.getFormat());
        envelope.setFormatVersion(source.getFormatVersion());
        envelope.setKdfAlgorithm(source.getKdfAlgorithm());
        envelope.setIterations(source.getIterations());
        envelope.setSalt(source.getSalt());
        envelope.setCipherAlgorithm(source.getCipherAlgorithm());
        envelope.setNonce(source.getNonce());
        envelope.setCiphertext(source.getCiphertext());

        envelope.validate();
        assertArrayEquals(source.aad(), envelope.aad());
    }

    private static VaultEnvelope validEnvelope() {
        return validEnvelope(VaultEnvelope.NEW_FILE_ITERATIONS);
    }

    private static VaultEnvelope validEnvelope(int iterations) {
        return VaultEnvelope.newEnvelope(
                iterations,
                new byte[VaultEnvelope.SALT_BYTES],
                new byte[VaultEnvelope.NONCE_BYTES],
                new byte[]{1});
    }

    private static void assertInvalid(VaultEnvelope envelope) {
        assertCode(VaultErrorCode.INVALID_ENVELOPE, envelope);
    }

    private static void assertCode(VaultErrorCode expected, VaultEnvelope envelope) {
        VaultException error = assertThrows(VaultException.class, envelope::validate);
        assertEquals(expected, error.getCode());
    }
}
