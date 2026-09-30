package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

class TimeConversionsTest {
    @ParameterizedTest
    @ValueSource(strings={"2026-02-29 12:00:00", "2026-04-31 00:00:00", "2026-09-30 24:00:00"})
    void invalidCalendarValuesAreRejected(String text) {
        assertThrows(DateTimeException.class, () -> TimeConversions.parse(text, "yyyy-MM-dd HH:mm:ss", ZoneOffset.UTC));
    }
    @Test void dateOnlyUsesMidnight() {
        assertEquals(Instant.parse("2024-02-28T16:00:00Z"), TimeConversions.parse("2024-02-29", "yyyy-MM-dd", ZoneId.of("Asia/Shanghai")));
    }
    @Test void negativeFractionalEpochSecondsAreFloored() {
        var value = TimeConversions.parse("1969-12-31 23:59:59.500", "yyyy-MM-dd HH:mm:ss.SSS", ZoneOffset.UTC);
        assertEquals(-1, value.getEpochSecond());
        assertEquals(-500, value.toEpochMilli());
    }
    @Test void secondsCannotSilentlyWrapWhenMultiplyingBy1000() {
        assertThrows(DateTimeException.class, () -> TimeConversions.fromTimestamp(Long.MAX_VALUE, false));
        assertEquals(Instant.ofEpochSecond(10_000_000_000_000_000L), TimeConversions.fromTimestamp(10_000_000_000_000_000L, false));
    }
    @Test void daylightSavingGapAndAmbiguityAreNotSilentlyResolved() {
        ZoneId zone = ZoneId.of("America/New_York");
        assertThrows(DateTimeException.class, () -> TimeConversions.parse("2026-03-08 02:30:00", "yyyy-MM-dd HH:mm:ss", zone));
        assertThrows(DateTimeException.class, () -> TimeConversions.parse("2026-11-01 01:30:00", "yyyy-MM-dd HH:mm:ss", zone));
        assertEquals(Instant.parse("2026-11-01T06:30:00Z"), TimeConversions.parse("2026-11-01 01:30:00 -05:00", "yyyy-MM-dd HH:mm:ss XXX", zone));
    }
    @Test void prolepticYearFormatStillWorks() {
        assertEquals(Instant.EPOCH, TimeConversions.parse("1970-01-01 00:00:00", "uuuu-MM-dd HH:mm:ss", ZoneOffset.UTC));
    }
}
