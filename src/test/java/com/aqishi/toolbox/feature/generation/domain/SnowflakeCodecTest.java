package com.aqishi.toolbox.feature.generation.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeCodecTest {

    private static final Instant NOW = Instant.parse("2026-09-27T08:00:00Z");

    @Test
    void decodesDiscordDocumentationExample() {
        SnowflakeCodec.Decoded decoded = SnowflakeCodec.decode(175928847299117063L,
                SnowflakeLayout.Preset.DISCORD.layout(), NOW);

        assertEquals(Instant.parse("2016-04-30T11:18:25.796Z"), decoded.timestamp());
        assertEquals(1L, decoded.fields().get("worker"));
        assertEquals(0L, decoded.fields().get("process"));
        assertEquals(7L, decoded.fields().get("increment"));
        assertTrue(decoded.warnings().isEmpty());
    }

    @Test
    void twitterLayoutMatchesReferenceShiftFormula() {
        SnowflakeLayout layout = SnowflakeLayout.Preset.TWITTER.layout();
        Instant instant = Instant.parse("2020-01-01T00:00:00.123Z");
        long id = SnowflakeCodec.encode(layout, instant, Map.of("datacenter", 3L, "worker", 17L, "sequence", 4095L));

        long expected = ((instant.toEpochMilli() - 1288834974657L) << 22) | (3L << 17) | (17L << 12) | 4095L;
        assertEquals(expected, id);
        SnowflakeCodec.Decoded decoded = SnowflakeCodec.decode(id, layout, NOW);
        assertEquals(instant, decoded.timestamp());
        assertEquals(List.of("datacenter", "worker", "sequence"), new ArrayList<>(decoded.fields().keySet()));
    }

    @Test
    void decodesMybatisPlusStyleId() {
        // MyBatis-Plus IdWorker.getId() 生成的典型 19 位 ID（2024-04 前后）
        long id = 1780483424564006913L;
        SnowflakeCodec.Decoded decoded = SnowflakeCodec.decode(id, SnowflakeLayout.Preset.MYBATIS_PLUS.layout(), NOW);

        long ticks = id >>> 22;
        assertEquals(1288834974657L + ticks, decoded.timestampMillis());
        assertEquals((id >>> 17) & 31, decoded.fields().get("datacenter"));
        assertEquals((id >>> 12) & 31, decoded.fields().get("worker"));
        assertEquals(id & 4095, decoded.fields().get("sequence"));
        assertEquals(2024, decoded.timestamp().atZone(ZoneId.of("UTC")).getYear());
        assertTrue(SnowflakeLayout.Preset.MYBATIS_PLUS.layout()
                .sameStructure(SnowflakeLayout.Preset.HUTOOL.layout()));
        assertTrue(SnowflakeLayout.Preset.TWITTER.layout()
                .sameStructure(SnowflakeLayout.Preset.MYBATIS_PLUS.layout()));
    }

    @Test
    void sonyflakePutsSequenceAboveMachineIdInTenMillisecondUnits() {
        SnowflakeLayout layout = SnowflakeLayout.Preset.SONYFLAKE.layout();
        assertEquals(Instant.parse("2014-09-01T00:00:00Z"), layout.epoch());
        Instant instant = Instant.parse("2024-06-01T12:00:00.370Z");
        long id = SnowflakeCodec.encode(layout, instant, Map.of("sequence", 5L, "machine", 0xBEEFL));

        long elapsed = (instant.toEpochMilli() - 1409529600000L) / 10;
        assertEquals((elapsed << 24) | (5L << 16) | 0xBEEFL, id);
        SnowflakeCodec.Decoded decoded = SnowflakeCodec.decode(id, layout, NOW);
        assertEquals(instant, decoded.timestamp());
        assertEquals(0xBEEFL, decoded.fields().get("machine"));
        assertEquals(5L, decoded.fields().get("sequence"));
    }

    @Test
    void baiduUidUsesSecondsSinceShanghaiMidnight() {
        SnowflakeLayout layout = SnowflakeLayout.Preset.BAIDU_UID.layout();
        assertEquals(1463673600000L, layout.epochMillis());
        assertEquals(64, 1 + layout.totalBits());
        Instant instant = Instant.parse("2020-03-01T10:20:30Z");
        long id = SnowflakeCodec.encode(layout, instant, Map.of("worker", 123456L, "sequence", 8000L));

        long delta = instant.getEpochSecond() - 1463673600L;
        assertEquals((delta << 35) | (123456L << 13) | 8000L, id);
        assertEquals(instant, SnowflakeCodec.decode(id, layout, NOW).timestamp());
        // 28 位秒只够约 8.5 年，默认布局已经用尽
        assertTrue(layout.exhaustedAt().isBefore(NOW));
        assertEquals(2024, layout.exhaustedAt().atZone(ZoneId.of("UTC")).getYear());
    }

    @Test
    void everyPresetRoundTrips() {
        for (SnowflakeLayout.Preset preset : SnowflakeLayout.Preset.values()) {
            SnowflakeLayout layout = preset.layout();
            Instant instant = Instant.ofEpochMilli(layout.epochMillis() + 123_456_000L);
            Map<String, Long> values = new java.util.LinkedHashMap<>();
            for (SnowflakeLayout.Field field : layout.fields()) {
                values.put(field.name(), field.maxValue() / 3);
            }
            long id = SnowflakeCodec.encode(layout, instant, values);
            SnowflakeCodec.Decoded decoded = SnowflakeCodec.decode(id, layout, NOW);
            assertEquals(instant, decoded.timestamp(), preset.name());
            assertEquals(values, decoded.fields(), preset.name());
            assertTrue(id > 0, preset.name());
        }
    }

    @Test
    void encodeRejectsOutOfRangeInput() {
        SnowflakeLayout layout = SnowflakeLayout.Preset.TWITTER.layout();
        IdCodecException before = assertThrows(IdCodecException.class,
                () -> SnowflakeCodec.encode(layout, Instant.parse("2000-01-01T00:00:00Z"), Map.of()));
        assertEquals(IdCodecException.Code.BEFORE_EPOCH, before.getCode());

        IdCodecException range = assertThrows(IdCodecException.class,
                () -> SnowflakeCodec.encode(layout, NOW, Map.of("worker", 32L)));
        assertEquals(IdCodecException.Code.OUT_OF_RANGE, range.getCode());
        assertEquals("worker", range.getArgs()[0]);

        IdCodecException unknown = assertThrows(IdCodecException.class,
                () -> SnowflakeCodec.encode(layout, NOW, Map.of("machine", 1L)));
        assertEquals(IdCodecException.Code.UNKNOWN_FIELD, unknown.getCode());

        IdCodecException overflow = assertThrows(IdCodecException.class,
                () -> SnowflakeCodec.encode(SnowflakeLayout.Preset.BAIDU_UID.layout(), NOW, Map.of()));
        assertEquals(IdCodecException.Code.TIMESTAMP_OVERFLOW, overflow.getCode());
    }

    @Test
    void decodeFlagsSuspiciousValues() {
        SnowflakeLayout twitter = SnowflakeLayout.Preset.TWITTER.layout();
        assertTrue(SnowflakeCodec.decode(-5L, twitter, NOW).warnings()
                .contains(SnowflakeCodec.Warning.SIGN_BIT_SET));
        assertTrue(SnowflakeCodec.decode(Long.MAX_VALUE, twitter, NOW).warnings()
                .contains(SnowflakeCodec.Warning.FUTURE_TIMESTAMP));

        SnowflakeLayout narrow = new SnowflakeLayout("custom", 0L, SnowflakeLayout.TickUnit.SECONDS, 32,
                List.of(new SnowflakeLayout.Field("sequence", 8, true)));
        assertTrue(SnowflakeCodec.decode(1L << 50, narrow, NOW).warnings()
                .contains(SnowflakeCodec.Warning.UNUSED_BITS_SET));
    }

    @Test
    void layoutValidationRejectsMoreThan63Bits() {
        IdCodecException error = assertThrows(IdCodecException.class, () -> new SnowflakeLayout("custom", 0L,
                SnowflakeLayout.TickUnit.MILLIS, 42, List.of(
                new SnowflakeLayout.Field("worker", 10, false),
                new SnowflakeLayout.Field("sequence", 12, true))));
        assertEquals(IdCodecException.Code.INVALID_LAYOUT, error.getCode());
        assertThrows(IdCodecException.class, () -> new SnowflakeLayout("custom", 0L,
                SnowflakeLayout.TickUnit.MILLIS, 41, List.of(
                new SnowflakeLayout.Field("worker", 5, false),
                new SnowflakeLayout.Field("worker", 5, false))));
    }

    @Test
    void guessPrefersTheLayoutThatYieldsARecentTime() {
        Instant recent = NOW.minusSeconds(3600);
        long twitter = SnowflakeCodec.encode(SnowflakeLayout.Preset.TWITTER.layout(), recent,
                Map.of("worker", 1L, "sequence", 9L));
        assertEquals(SnowflakeLayout.Preset.TWITTER, SnowflakeCodec.guessPreset(twitter, NOW).get(0).preset());

        long sonyflake = SnowflakeCodec.encode(SnowflakeLayout.Preset.SONYFLAKE.layout(), recent,
                Map.of("machine", 42L));
        assertEquals(SnowflakeLayout.Preset.SONYFLAKE, SnowflakeCodec.guessPreset(sonyflake, NOW).get(0).preset());

        long discord = SnowflakeCodec.encode(SnowflakeLayout.Preset.DISCORD.layout(), recent,
                Map.of("worker", 2L, "increment", 3L));
        assertEquals(SnowflakeLayout.Preset.DISCORD, SnowflakeCodec.guessPreset(discord, NOW).get(0).preset());
    }

    @Test
    void parseInstantAcceptsCommonForms() {
        ZoneId shanghai = ZoneId.of("Asia/Shanghai");
        assertEquals(Instant.ofEpochMilli(1288834974657L), SnowflakeCodec.parseInstant("1288834974657", shanghai));
        assertEquals(Instant.parse("2016-05-19T16:00:00Z"), SnowflakeCodec.parseInstant("2016-05-20", shanghai));
        assertEquals(Instant.parse("2016-05-19T16:00:00Z"),
                SnowflakeCodec.parseInstant("2016-05-20 00:00:00", shanghai));
        assertEquals(Instant.parse("2016-05-19T16:00:00Z"),
                SnowflakeCodec.parseInstant("2016-05-20T00:00:00+08:00", ZoneId.of("UTC")));
        assertEquals(Instant.parse("2014-09-01T00:00:00Z"),
                SnowflakeCodec.parseInstant("2014-09-01T00:00:00Z", shanghai));
        IdCodecException error = assertThrows(IdCodecException.class,
                () -> SnowflakeCodec.parseInstant("yesterday", shanghai));
        assertEquals(IdCodecException.Code.INVALID_TIME, error.getCode());
    }

    @Test
    void generatorIsUniqueAndMonotonicAcrossThreads() throws Exception {
        SnowflakeCodec.Generator generator = new SnowflakeCodec.Generator(
                SnowflakeLayout.Preset.TWITTER.layout(), Map.of("datacenter", 1L, "worker", 2L));
        int threads = 8;
        int perThread = 20_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<long[]>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                Callable<long[]> task = () -> {
                    long[] ids = new long[perThread];
                    for (int i = 0; i < perThread; i++) {
                        ids[i] = generator.next();
                    }
                    return ids;
                };
                futures.add(pool.submit(task));
            }
            Set<Long> all = new HashSet<>();
            for (Future<long[]> future : futures) {
                long[] ids = future.get(60, TimeUnit.SECONDS);
                for (int i = 0; i < ids.length; i++) {
                    if (i > 0) {
                        assertTrue(ids[i] > ids[i - 1], "ids must increase within a thread");
                    }
                    assertTrue(all.add(ids[i]), "duplicate id");
                    SnowflakeCodec.Decoded decoded = SnowflakeCodec.decode(ids[i], generator.layout());
                    assertEquals(1L, decoded.fields().get("datacenter"));
                    assertEquals(2L, decoded.fields().get("worker"));
                }
            }
            assertEquals(threads * perThread, all.size());
        } finally {
            pool.shutdownNow();
        }
    }

    /** 可控时钟：等待时按提示前进，至少 1ms */
    private static final class FakeTime implements SnowflakeCodec.TimeSource {
        long now;
        long waited;

        FakeTime(long now) {
            this.now = now;
        }

        @Override
        public long currentMillis() {
            return now;
        }

        @Override
        public void waitMillis(long hintMillis) {
            long step = Math.max(1, hintMillis);
            now += step;
            waited += step;
        }
    }

    @Test
    void generatorWaitsOutSmallClockRegressionAndRejectsLargeOne() {
        SnowflakeLayout layout = SnowflakeLayout.Preset.TWITTER.layout();
        FakeTime time = new FakeTime(NOW.toEpochMilli());
        SnowflakeCodec.Generator generator = new SnowflakeCodec.Generator(layout, Map.of(), time, 10);
        long first = generator.next();

        time.now -= 5;
        long second = generator.next();
        assertTrue(second > first);
        assertTrue(time.waited >= 5, "generator should wait for the clock to catch up");

        time.now -= 1000;
        IdCodecException error = assertThrows(IdCodecException.class, generator::next);
        assertEquals(IdCodecException.Code.CLOCK_BACKWARDS, error.getCode());

        // 时钟恢复后可继续生成，且仍大于回拨前的 ID
        time.now += 2000;
        assertTrue(generator.next() > second);
    }

    @Test
    void sequenceOverflowWaitsForNextTick() {
        SnowflakeLayout layout = new SnowflakeLayout("custom", SnowflakeLayout.TWITTER_EPOCH,
                SnowflakeLayout.TickUnit.MILLIS, 41, List.of(new SnowflakeLayout.Field("sequence", 2, true)));
        FakeTime time = new FakeTime(NOW.toEpochMilli());
        SnowflakeCodec.Generator generator = new SnowflakeCodec.Generator(layout, Map.of(), time, 0);
        long previous = -1;
        for (int i = 0; i < 4; i++) {
            long id = generator.next();
            assertEquals(i, id & 3);
            assertTrue(id > previous);
            previous = id;
        }
        assertEquals(0, time.waited);
        long fifth = generator.next();
        assertEquals(0, fifth & 3);
        assertEquals((previous >>> 2) + 1, fifth >>> 2);
        assertTrue(time.waited >= 1);
        assertFalse(fifth <= previous);
    }

    @Test
    void generatorRejectsNodeValuesOutsideTheirBits() {
        IdCodecException error = assertThrows(IdCodecException.class, () -> new SnowflakeCodec.Generator(
                SnowflakeLayout.Preset.SONYFLAKE.layout(), Map.of("machine", 70000L)));
        assertEquals(IdCodecException.Code.OUT_OF_RANGE, error.getCode());
    }
}
