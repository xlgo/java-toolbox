package com.aqishi.toolbox.feature.generation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdDetectorTest {

    private static final Instant NOW = Instant.parse("2026-09-27T08:00:00Z");

    private static IdDetector.Interpretation best(String input) {
        List<IdDetector.Interpretation> result = IdDetector.detect(input, NOW);
        assertTrue(!result.isEmpty(), "no interpretation for " + input);
        return result.get(0);
    }

    private static Object detail(IdDetector.Interpretation interpretation, String key) {
        for (IdDetector.Detail detail : interpretation.details()) {
            if (detail.key().equals(key)) {
                return detail.value();
            }
        }
        return null;
    }

    @Test
    void recognisesUuidWithEmbeddedTime() {
        IdDetector.Interpretation uuid = best("017F22E2-79B0-7CC3-98C4-DC0C0C07398F");
        assertEquals(IdDetector.Kind.UUID, uuid.kind());
        assertEquals("v7", uuid.subtype());
        assertEquals(Instant.parse("2022-02-22T19:22:22Z"), uuid.timestamp());
        assertEquals(java.util.UUID.fromString("017F22E2-79B0-7CC3-98C4-DC0C0C07398F"),
                UlidCodec.toUuid((String) detail(uuid, IdDetector.D_ULID)));

        IdDetector.Interpretation v4 = best("{f47ac10b-58cc-4372-a567-0e02b2c3d479}");
        assertEquals("v4", v4.subtype());
    }

    @Test
    void recognisesUlidObjectIdAndKsuid() {
        assertEquals(IdDetector.Kind.ULID, best("01ARYZ6S41TSV4RRFFQ69G5FAV").kind());
        IdDetector.Interpretation objectId = best("ObjectId(\"507f1f77bcf86cd799439011\")");
        assertEquals(IdDetector.Kind.OBJECT_ID, objectId.kind());
        assertEquals(Instant.parse("2012-10-17T21:13:27Z"), objectId.timestamp());
        assertEquals(IdDetector.Kind.KSUID, best("0ujtsYcgvSTl8PAuAdqWYSMnLOv").kind());
    }

    @Test
    void plainTimestampsBeatSnowflakeReadings() {
        IdDetector.Interpretation millis = best("1790000000000");
        assertEquals(IdDetector.Kind.UNIX_MILLIS, millis.kind());
        assertEquals(Instant.ofEpochMilli(1790000000000L), millis.timestamp());

        IdDetector.Interpretation seconds = best("1790000000");
        assertEquals(IdDetector.Kind.UNIX_SECONDS, seconds.kind());
    }

    @Test
    void recentTwitterStyleIdIsGroupedWithMybatisPlusAndHutool() {
        long id = SnowflakeCodec.encode(SnowflakeLayout.Preset.MYBATIS_PLUS.layout(),
                NOW.minusSeconds(86400), Map.of("datacenter", 1L, "worker", 3L, "sequence", 42L));
        IdDetector.Interpretation top = best("\"" + id + "\",");
        assertEquals(IdDetector.Kind.SNOWFLAKE, top.kind());
        assertEquals("twitter,mybatisPlus,hutool", top.subtype());
        assertEquals(3L, detail(top, "worker"));
        assertEquals(42L, detail(top, "sequence"));
    }

    @Test
    void discordExampleIsAmongTheSnowflakeReadings() {
        List<IdDetector.Interpretation> all = IdDetector.detect("175928847299117063", NOW);
        IdDetector.Interpretation discord = all.stream()
                .filter(i -> i.kind() == IdDetector.Kind.SNOWFLAKE && i.subtype().equals("discord"))
                .findFirst().orElse(null);
        assertNotNull(discord);
        assertEquals(Instant.parse("2016-04-30T11:18:25.796Z"), discord.timestamp());
        assertEquals(1L, detail(discord, "worker"));
        // 每种结构只出现一次
        assertEquals(4, all.stream().filter(i -> i.kind() == IdDetector.Kind.SNOWFLAKE).count());
    }

    @Test
    void hexSnowflakeAndGarbage() {
        long id = SnowflakeCodec.encode(SnowflakeLayout.Preset.TWITTER.layout(), NOW.minusSeconds(60), Map.of());
        IdDetector.Interpretation hex = best("0x" + Long.toHexString(id));
        assertEquals(IdDetector.Kind.SNOWFLAKE, hex.kind());

        assertTrue(IdDetector.detect("hello world", NOW).isEmpty());
        assertTrue(IdDetector.detect("   ", NOW).isEmpty());
        assertTrue(IdDetector.detect("99999999999999999999", NOW).isEmpty());
    }

    @Test
    void futureTimesAreFlagged() {
        IdDetector.Interpretation max = IdDetector.detect("7ZZZZZZZZZZZZZZZZZZZZZZZZZ", NOW).get(0);
        assertEquals(IdDetector.Kind.ULID, max.kind());
        assertTrue(max.warnings().contains(IdDetector.W_FUTURE));
        assertTrue(max.score() < 0.6);
    }
}
