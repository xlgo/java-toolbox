package com.aqishi.toolbox.feature.generation.domain;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UlidCodecTest {

    @Test
    void maximumUlidDecodesToAllOnes() {
        UlidCodec.Decoded decoded = UlidCodec.decode("7ZZZZZZZZZZZZZZZZZZZZZZZZZ");
        assertEquals(281474976710655L, decoded.timestampMillis());
        assertEquals(new UUID(-1L, -1L), decoded.uuid());
        assertEquals("ffffffffffffffffffff", decoded.randomnessHex());
        assertEquals("7ZZZZZZZZZZZZZZZZZZZZZZZZZ", UlidCodec.encode(281474976710655L, 0xFFFF, -1L));
    }

    @Test
    void rejectsOverflowAndInvalidCharacters() {
        assertEquals(IdCodecException.Code.OVERFLOW, assertThrows(IdCodecException.class,
                () -> UlidCodec.decode("8ZZZZZZZZZZZZZZZZZZZZZZZZZ")).getCode());
        assertEquals(IdCodecException.Code.INVALID_CHARACTER, assertThrows(IdCodecException.class,
                () -> UlidCodec.decode("01ARZ3NDEKTSV4RRFFQ69G5FAU")).getCode());
        assertEquals(IdCodecException.Code.INVALID_LENGTH, assertThrows(IdCodecException.class,
                () -> UlidCodec.decode("01ARZ3NDEK")).getCode());
    }

    @Test
    void timestampEncodingMatchesReferenceExample() {
        // ulid/javascript README：ulid(1469918176385) 的时间部分为 01ARYZ6S41
        String ulid = UlidCodec.encode(1469918176385L, 0, 0);
        assertEquals("01ARYZ6S41", ulid.substring(0, 10));
        assertEquals(1469918176385L, UlidCodec.decode("01ARYZ6S41TSV4RRFFQ69G5FAV").timestampMillis());
    }

    @Test
    void decodingIsCaseInsensitiveAndMapsAmbiguousLetters() {
        UlidCodec.Decoded canonical = UlidCodec.decode("01ARYZ6S41TSV4RRFFQ69G5FAV");
        UlidCodec.Decoded lower = UlidCodec.decode("01aryz6s41tsv4rrffq69g5fav");
        assertEquals(canonical.uuid(), lower.uuid());
        assertFalse(lower.lenient());

        UlidCodec.Decoded ambiguous = UlidCodec.decode("O1ARYZ6S4ITSV4RRFFQ69G5FAV");
        assertTrue(ambiguous.lenient());
        assertEquals("01ARYZ6S41TSV4RRFFQ69G5FAV", ambiguous.canonical());
        assertEquals(canonical.uuid(), UlidCodec.decode("01ARYZ6S4LTSV4RRFFQ69G5FAV").uuid());
    }

    @Test
    void convertsToAndFromUuid() {
        String ulid = "01ARYZ6S41TSV4RRFFQ69G5FAV";
        UUID uuid = UlidCodec.toUuid(ulid);
        assertEquals(ulid, UlidCodec.fromUuid(uuid));
        UUID random = UUID.randomUUID();
        assertEquals(random, UlidCodec.toUuid(UlidCodec.fromUuid(random)));
    }

    @Test
    void monotonicModeIncrementsWithinTheSameMillisecond() {
        long[] clock = {1_700_000_000_000L};
        UlidCodec codec = new UlidCodec(new Random(3), () -> clock[0]);
        UlidCodec.Decoded first = UlidCodec.decode(codec.generateMonotonic());
        UlidCodec.Decoded second = UlidCodec.decode(codec.generateMonotonic());
        assertEquals(first.timestampMillis(), second.timestampMillis());
        assertEquals(first.uuid().getLeastSignificantBits() + 1, second.uuid().getLeastSignificantBits());
        assertTrue(second.canonical().compareTo(first.canonical()) > 0);

        clock[0] -= 10;
        UlidCodec.Decoded third = UlidCodec.decode(codec.generateMonotonic());
        assertTrue(third.canonical().compareTo(second.canonical()) > 0, "clock regression keeps order");

        clock[0] += 100;
        assertEquals(clock[0], UlidCodec.decode(codec.generateMonotonic()).timestampMillis());
    }

    @Test
    void monotonicOverflowIsAnError() {
        Random allOnes = new Random() {
            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }

            @Override
            public long nextLong() {
                return -1L;
            }
        };
        UlidCodec codec = new UlidCodec(allOnes, () -> 1_700_000_000_000L);
        codec.generateMonotonic();
        assertEquals(IdCodecException.Code.MONOTONIC_OVERFLOW,
                assertThrows(IdCodecException.class, codec::generateMonotonic).getCode());
    }

    @Test
    void generatedUlidsCarryTheClock() {
        UlidCodec codec = new UlidCodec(new Random(5), () -> 1_600_000_000_000L);
        String ulid = codec.generate();
        assertEquals(26, ulid.length());
        assertEquals(1_600_000_000_000L, UlidCodec.decode(ulid).timestampMillis());
    }
}
