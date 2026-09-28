package com.aqishi.toolbox.feature.security.infra.acme;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Parses an HTTP {@code Retry-After} header (RFC 9110 section 10.2.3): either
 * delay-seconds or an HTTP-date.
 */
public final class RetryAfter {

    private RetryAfter() {
    }

    /**
     * Returns the delay the header asks for, or {@code null} when absent or unparsable.
     * A date in the past yields {@link Duration#ZERO}.
     */
    public static Duration parse(String header, Instant now) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                return Duration.ofSeconds(Long.parseLong(value));
            } catch (NumberFormatException tooLarge) {
                return Duration.ofSeconds(Long.MAX_VALUE / 1_000_000_000L);
            }
        }
        try {
            Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(now, at);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Clamps {@code value} (or {@code fallback} when null) into {@code [min, max]}. */
    public static Duration clamp(Duration value, Duration fallback, Duration min, Duration max) {
        Duration d = value == null ? fallback : value;
        if (d.compareTo(min) < 0) {
            return min;
        }
        if (d.compareTo(max) > 0) {
            return max;
        }
        return d;
    }
}
