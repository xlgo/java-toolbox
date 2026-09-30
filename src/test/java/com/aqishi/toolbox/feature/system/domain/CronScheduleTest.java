package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CronScheduleTest {
    private List<Instant> next(String expr, String start, int count) {
        return CronSchedule.next(expr, count, Instant.parse(start), ZoneId.of("UTC"))
                .stream().map(java.util.Date::toInstant).toList();
    }
    @Test void crossesMonthAndLeapYearWithoutSkippingDates() {
        assertEquals(Instant.parse("2024-02-29T00:00:00Z"), next("0 0 29 2 *", "2024-01-31T23:59:59Z", 1).get(0));
        assertEquals(Instant.parse("2026-02-01T00:00:00Z"), next("0 0 1 2 *", "2026-01-31T23:59:59Z", 1).get(0));
    }
    @Test void sundayAliasesAreEquivalent() {
        assertEquals(next("0 12 * * 7", "2026-09-29T00:00:00Z", 3), next("0 12 * * 0", "2026-09-29T00:00:00Z", 3));
    }
    @Test void fiveAndSixFieldsMatch() {
        assertEquals(next("*/5 * * * *", "2026-09-29T00:00:00Z", 15), next("0 */5 * * * *", "2026-09-29T00:00:00Z", 15));
    }
    @ParameterizedTest
    @ValueSource(strings={"0, * * * *", "0,,1 * * * *", "? * * * *", "0 0 * ? *", "0 0 ?/2 * *"})
    void rejectsMalformedOrMisplacedWildcards(String expr) {
        assertThrows(IllegalArgumentException.class, () -> next(expr, "2026-09-29T00:00:00Z", 1));
    }
    @Test void enormousStepCannotOverflowIntoInvalidValues() {
        assertEquals(List.of(Instant.parse("2026-09-29T00:01:00Z"), Instant.parse("2026-09-29T01:01:00Z")),
                next("1/2147483647 * * * *", "2026-09-29T00:00:00Z", 2));
    }
    @Test void wildcardStepUsesAndForDayAndWeekday() {
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), next("0 0 */2 * 4", "2026-09-28T00:00:00Z", 1).get(0));
    }
}
