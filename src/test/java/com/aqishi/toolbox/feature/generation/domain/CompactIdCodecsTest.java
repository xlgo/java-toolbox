package com.aqishi.toolbox.feature.generation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ObjectId、KSUID、NanoID 这三种较小的格式 */
class CompactIdCodecsTest {

    @Test
    void objectIdKnownVector() {
        ObjectIdCodec.Decoded decoded = ObjectIdCodec.decode("507f1f77bcf86cd799439011");
        assertEquals(Instant.parse("2012-10-17T21:13:27Z"), decoded.timestamp());
        assertEquals("bcf86cd799", decoded.randomHex());
        assertEquals(0x439011, decoded.counter());
        assertEquals("507f1f77bcf86cd799439011", ObjectIdCodec.decode("507F1F77BCF86CD799439011").hex());
        assertEquals(IdCodecException.Code.INVALID_CHARACTER, assertThrows(IdCodecException.class,
                () -> ObjectIdCodec.decode("507f1f77bcf86cd79943901z")).getCode());
    }

    @Test
    void objectIdGenerationSharesProcessRandomAndIncrementsCounter() {
        ObjectIdCodec codec = new ObjectIdCodec(new Random(11), () -> 1_700_000_000_999L);
        ObjectIdCodec.Decoded first = ObjectIdCodec.decode(codec.generate());
        ObjectIdCodec.Decoded second = ObjectIdCodec.decode(codec.generate());
        assertEquals(1_700_000_000L, first.seconds());
        assertEquals(first.randomHex(), second.randomHex());
        assertEquals((first.counter() + 1) & 0xFFFFFF, second.counter());
    }

    @Test
    void ksuidReferenceExample() {
        // segmentio/ksuid README 中 ksuid -f inspect 的示例
        KsuidCodec.Decoded decoded = KsuidCodec.decode("0ujtsYcgvSTl8PAuAdqWYSMnLOv");
        assertEquals(107608047L, decoded.rawSeconds());
        assertEquals("b5a1cd34b5f99d1154fb6853345c9735", decoded.payloadHex());
        assertEquals(Instant.parse("2017-10-10T04:00:47Z"), decoded.timestamp());

        byte[] payload = new java.math.BigInteger("00b5a1cd34b5f99d1154fb6853345c9735", 16).toByteArray();
        byte[] sixteen = new byte[16];
        System.arraycopy(payload, payload.length - 16, sixteen, 0, 16);
        assertEquals("0ujtsYcgvSTl8PAuAdqWYSMnLOv", KsuidCodec.encode(1507608047L, sixteen));
    }

    @Test
    void ksuidBoundsAndGeneration() {
        KsuidCodec.Decoded max = KsuidCodec.decode("aWgEPTl1tmebfsQzFP4bxwgy80V");
        assertEquals(0xFFFFFFFFL, max.rawSeconds());
        assertEquals("ffffffffffffffffffffffffffffffff", max.payloadHex());
        assertEquals(IdCodecException.Code.OVERFLOW, assertThrows(IdCodecException.class,
                () -> KsuidCodec.decode("aWgEPTl1tmebfsQzFP4bxwgy80W")).getCode());
        assertEquals("000000000000000000000000000", KsuidCodec.encode(KsuidCodec.EPOCH_SECONDS, new byte[16]));

        KsuidCodec codec = new KsuidCodec(new Random(2), () -> 1_750_000_000_500L);
        String ksuid = codec.generate();
        assertEquals(27, ksuid.length());
        assertEquals(Instant.ofEpochSecond(1_750_000_000L), KsuidCodec.decode(ksuid).timestamp());
    }

    @Test
    void nanoIdDefaultsAndCustomAlphabet() {
        NanoIdGenerator generator = new NanoIdGenerator(new Random(9));
        String id = generator.generate();
        assertEquals(21, id.length());
        for (char c : id.toCharArray()) {
            assertTrue(NanoIdGenerator.DEFAULT_ALPHABET.indexOf(c) >= 0);
        }
        assertEquals(64, NanoIdGenerator.DEFAULT_ALPHABET.chars().distinct().count());

        String hex = generator.generate("0123456789abcdef", 32);
        assertTrue(hex.matches("[0-9a-f]{32}"));
        assertEquals(126.0, NanoIdGenerator.entropyBits(64, 21), 1e-9);
    }

    @Test
    void nanoIdIsUnbiasedForNonPowerOfTwoAlphabets() {
        NanoIdGenerator generator = new NanoIdGenerator(new Random(42));
        String sample = generator.generate("abc", 1000);
        int[] counts = new int[3];
        for (int round = 0; round < 30; round++) {
            for (char c : generator.generate("abc", 1000).toCharArray()) {
                counts[c - 'a']++;
            }
        }
        assertEquals(1000, sample.length());
        for (int count : counts) {
            // 期望 10000，允许 ±5%
            assertTrue(Math.abs(count - 10_000) < 500, "biased distribution: " + count);
        }
    }

    @Test
    void nanoIdValidatesParameters() {
        NanoIdGenerator generator = new NanoIdGenerator();
        assertEquals(IdCodecException.Code.INVALID_ALPHABET,
                assertThrows(IdCodecException.class, () -> generator.generate("aab", 5)).getCode());
        assertEquals(IdCodecException.Code.INVALID_ALPHABET,
                assertThrows(IdCodecException.class, () -> generator.generate("a", 5)).getCode());
        assertEquals(IdCodecException.Code.INVALID_SIZE,
                assertThrows(IdCodecException.class, () -> generator.generate("ab", 0)).getCode());
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(unique.add(generator.generate()));
        }
    }
}
