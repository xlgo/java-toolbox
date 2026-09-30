package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.I18n;
import java.time.*;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalQueries;

/** Strict timestamp conversions. A date-only input denotes midnight; a time-only input needs a date. */
public final class TimeConversions {
    private TimeConversions() { }

    public static Instant fromTimestamp(long value, boolean milliseconds) {
        return milliseconds ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
    }

    public static Instant parse(String text, String pattern, ZoneId zone) {
        var formatter = new DateTimeFormatterBuilder().appendPattern(pattern)
                .parseDefaulting(ChronoField.ERA, 1).toFormatter().withResolverStyle(ResolverStyle.STRICT);
        var parsed = formatter.parse(text);
        LocalDate date = LocalDate.from(parsed);
        LocalTime time = parsed.query(TemporalQueries.localTime());
        var local = date.atTime(time == null ? LocalTime.MIDNIGHT : time);
        ZoneOffset explicitOffset = parsed.query(TemporalQueries.offset());
        ZoneId parsedZone = parsed.query(TemporalQueries.zoneId());
        ZoneId effectiveZone = parsedZone == null ? zone : parsedZone;
        if (explicitOffset != null && parsedZone == null) return local.toInstant(explicitOffset);
        var offsets = effectiveZone.getRules().getValidOffsets(local);
        if (offsets.isEmpty()) throw new DateTimeException(I18n.get("tool.time.error.gap"));
        if (explicitOffset != null) {
            if (!offsets.contains(explicitOffset)) throw new DateTimeException(I18n.get("tool.time.error.offset"));
            return local.toInstant(explicitOffset);
        }
        if (offsets.size() > 1) throw new DateTimeException(I18n.get("tool.time.error.overlap"));
        return local.toInstant(offsets.get(0));
    }
}
