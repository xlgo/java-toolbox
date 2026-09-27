package com.aqishi.toolbox.feature.generation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UuidCodecTest {

    /** RFC 9562 附录 A 的示例时间：2022-02-22 14:22:22 -05:00 */
    private static final Instant RFC_EXAMPLE_TIME = Instant.parse("2022-02-22T19:22:22Z");

    @Test
    void nameBasedKnownVectors() {
        assertEquals(UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2"),
                UuidCodec.v5(UuidCodec.NAMESPACE_DNS, "www.example.com"));
        assertEquals(UUID.fromString("5df41881-3aed-3515-88a7-2f4a814cf09e"),
                UuidCodec.v3(UuidCodec.NAMESPACE_DNS, "www.example.com"));
    }

    @Test
    void rfc9562V1ExampleExposesTimestampClockSequenceAndNode() {
        UuidCodec.Info info = UuidCodec.inspect("C232AB00-9414-11EC-B3C8-9F6BDECED846");

        assertEquals(1, info.version());
        assertEquals(UuidCodec.Variant.RFC_9562, info.variant());
        assertEquals(RFC_EXAMPLE_TIME, info.timestamp());
        assertEquals(0x33C8, info.clockSequence());
        assertEquals(0x9F6BDECED846L, info.node());
        assertTrue(info.nodeMulticast());
        assertEquals("9f:6b:de:ce:d8:46", UuidCodec.formatNode(info.node()));
    }

    @Test
    void rfc9562V6AndV7Examples() {
        UuidCodec.Info v6 = UuidCodec.inspect("1EC9414C-232A-6B00-B3C8-9F6BDECED846");
        assertEquals(6, v6.version());
        assertEquals(RFC_EXAMPLE_TIME, v6.timestamp());
        assertEquals(0x33C8, v6.clockSequence());

        UuidCodec.Info v7 = UuidCodec.inspect("017F22E2-79B0-7CC3-98C4-DC0C0C07398F");
        assertEquals(7, v7.version());
        assertEquals(RFC_EXAMPLE_TIME, v7.timestamp());
        assertEquals(0xCC3, v7.randA());
        assertNull(v7.node());
    }

    @Test
    void v7IsStrictlyOrderedAndEmbedsClockMillis() {
        long[] clock = {1_700_000_000_123L};
        UuidCodec codec = new UuidCodec(new Random(7), () -> clock[0]);
        String previous = "";
        for (int i = 0; i < 10_000; i++) {
            UUID uuid = codec.v7();
            String text = uuid.toString();
            assertTrue(text.compareTo(previous) > 0, "v7 must be strictly increasing");
            previous = text;
            UuidCodec.Info info = UuidCodec.inspect(uuid);
            assertEquals(7, info.version());
            assertEquals(UuidCodec.Variant.RFC_9562, info.variant());
            // 计数器用尽后时间戳会被前推，但不应偏离太多
            assertTrue(info.timestamp().toEpochMilli() - clock[0] <= 10);
        }
        clock[0] += 1000;
        UUID later = UuidCodec.NIL;
        later = codec.v7();
        assertEquals(clock[0], UuidCodec.inspect(later).timestamp().toEpochMilli());

        // 时钟回拨时仍保持递增
        clock[0] -= 5000;
        assertTrue(codec.v7().toString().compareTo(later.toString()) > 0);
    }

    @Test
    void v1AndV6RoundTripTheClockAndStayOrdered() {
        long[] clock = {RFC_EXAMPLE_TIME.toEpochMilli()};
        UuidCodec codec = new UuidCodec(new Random(1), () -> clock[0]);
        UuidCodec.Info v1 = UuidCodec.inspect(codec.v1());
        assertEquals(1, v1.version());
        assertEquals(RFC_EXAMPLE_TIME, v1.timestamp());
        assertTrue(v1.nodeMulticast(), "random nodes must carry the multicast bit");

        String previous = "";
        for (int i = 0; i < 1000; i++) {
            UUID v6 = codec.v6();
            assertEquals(6, v6.version());
            assertTrue(v6.toString().compareTo(previous) > 0);
            previous = v6.toString();
        }
        UuidCodec.Info v6 = UuidCodec.inspect(previous);
        assertEquals(clock[0], v6.timestamp().toEpochMilli());
    }

    @Test
    void v4HasVersionAndVariant() {
        UuidCodec codec = new UuidCodec();
        for (int i = 0; i < 100; i++) {
            UUID uuid = codec.v4();
            assertEquals(4, uuid.version());
            assertEquals(2, uuid.variant());
        }
    }

    @Test
    void parsesCommonSpellings() {
        UUID expected = UUID.fromString("2ed6657d-e927-568b-95e1-2665a8aea6a2");
        assertEquals(expected, UuidCodec.parse("2ED6657D-E927-568B-95E1-2665A8AEA6A2"));
        assertEquals(expected, UuidCodec.parse("{2ed6657d-e927-568b-95e1-2665a8aea6a2}"));
        assertEquals(expected, UuidCodec.parse("urn:uuid:2ed6657d-e927-568b-95e1-2665a8aea6a2"));
        assertEquals(expected, UuidCodec.parse("URN:UUID:2ed6657de927568b95e12665a8aea6a2"));
        assertEquals(expected, UuidCodec.parse("  2ed6657de927568b95e12665a8aea6a2 "));

        assertEquals(IdCodecException.Code.INVALID_LENGTH,
                assertThrows(IdCodecException.class, () -> UuidCodec.parse("2ed6657d")).getCode());
        assertEquals(IdCodecException.Code.INVALID_CHARACTER, assertThrows(IdCodecException.class,
                () -> UuidCodec.parse("2ed6657d-e927-568b-95e1-2665a8aea6ag")).getCode());
        assertEquals(IdCodecException.Code.INVALID_CHARACTER, assertThrows(IdCodecException.class,
                () -> UuidCodec.parse("2ed6657de-927-568b-95e1-2665a8aea6a2")).getCode());
    }

    @Test
    void nilAndMaxAreRecognised() {
        UuidCodec.Info nil = UuidCodec.inspect("00000000-0000-0000-0000-000000000000");
        assertTrue(nil.isNil());
        assertFalse(nil.isMax());
        assertNull(nil.timestamp());
        UuidCodec.Info max = UuidCodec.inspect("FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF");
        assertTrue(max.isMax());
        assertEquals("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF", UuidCodec.format(UuidCodec.MAX, true, false));
    }

    @Test
    void variantsAreClassified() {
        assertEquals(UuidCodec.Variant.NCS, UuidCodec.variantOf(0x7000000000000000L));
        assertEquals(UuidCodec.Variant.RFC_9562, UuidCodec.variantOf(0xA000000000000000L));
        assertEquals(UuidCodec.Variant.MICROSOFT, UuidCodec.variantOf(0xC000000000000000L));
        assertEquals(UuidCodec.Variant.FUTURE, UuidCodec.variantOf(0xE000000000000000L));
    }
}
