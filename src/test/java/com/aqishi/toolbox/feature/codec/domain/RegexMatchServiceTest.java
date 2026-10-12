package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.I18n;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.*;

class RegexMatchServiceTest {
    private final RegexMatchService service = new RegexMatchService();

    @Test void preservesJavaLookbehindAndBackreferenceSemantics() {
        String output = service.match("(?<=ID:)(\\d+)\\s+\\1", "ID:12 12 ID:34 34", 0, true);
        assertTrue(output.contains("ID:【12 12】 ID:【34 34】"));
        assertTrue(output.contains("$1=12"));
        assertTrue(output.contains("$1=34"));
        assertTrue(output.contains(I18n.get("regex.count", 2)));
    }

    @Test void nonGlobalModeStopsAfterFirstMatch() {
        String output = service.match("(\\d+)", "1 2", 0, false);
        assertTrue(output.startsWith("【1】 2"));
        assertFalse(output.contains("#1"));
    }

    @Test void flagsAndZeroWidthMatchesStillWork() {
        assertTrue(service.match("^a", "a\nA", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE, true).startsWith("【a】\n【A】"));
        assertTrue(service.match("", "ab", 0, true).startsWith("【】a【】b【】"));
    }

    @Test void catastrophicBacktrackingActuallyReturnsWithinBudget() {
        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
            var bounded = new RegexMatchService(Duration.ofMillis(25));
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> bounded.match("(a+)+$", "a".repeat(40_000) + "!", 0, true));
            assertEquals(I18n.get("regex.timeout"), error.getMessage());
        });
        assertTrue(service.match("a", "a", 0, true).startsWith("【a】"));
    }

    @Test void cancellationIsObservedInsideRegexEngine() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> service.match("(a+)+$", "a".repeat(40_000) + "!", 0, true));
        } finally { Thread.interrupted(); }
    }

    @Test void resultCountIsBoundedAndClearlyReported() {
        String output = service.match("a", "a".repeat(RegexMatchService.MAX_MATCHES + 10), 0, true);
        assertTrue(output.contains(I18n.get("regex.matchLimit", RegexMatchService.MAX_MATCHES)));
        assertFalse(output.contains("#" + RegexMatchService.MAX_MATCHES + " =>"));
    }

    @Test void rejectsOversizedInputAndInvalidSyntax() {
        assertThrows(IllegalArgumentException.class, () -> service.match("a", "a".repeat(RegexMatchService.MAX_INPUT + 1), 0, true));
        assertThrows(IllegalArgumentException.class, () -> service.match("a".repeat(RegexMatchService.MAX_PATTERN + 1), "", 0, true));
        assertThrows(PatternSyntaxException.class, () -> service.match("[", "a", 0, true));
    }

    @Test void boundsEmptyCountedRepetitionsThatDoNotReadInputCharacters() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (String pattern : new String[]{"(?:){2147483647}", "(?:(?:(?:){10000}){10000}){10000}"}) {
                var error = assertThrows(IllegalArgumentException.class, () -> service.match(pattern, "", 0, true));
                assertEquals(I18n.get("regex.repetitionLimit"), error.getMessage());
            }
        });
        assertTrue(service.match("\\Q{2147483647}\\E", "{2147483647}", 0, true).startsWith("【{2147483647}】"));
        assertTrue(service.match("[{2147483647}]", "{", 0, true).startsWith("【{】"));
        assertTrue(service.match("a{20000}", "a".repeat(20000), 0, true).startsWith("【"));
        assertTrue(service.match("(\\x{12345}){1000}", new String(Character.toChars(0x12345)).repeat(1000), 0, true).startsWith("【"));
        assertTrue(service.match("[](){2147483647}]", ")", 0, true).startsWith("【)】"));
    }
}
