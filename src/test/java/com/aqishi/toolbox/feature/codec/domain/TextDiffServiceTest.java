package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;

import static com.aqishi.toolbox.feature.codec.domain.TextDiffService.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class TextDiffServiceTest {
    private final TextDiffService service = new TextDiffService();

    @Test void preservesTextAndTrailingEmptyLines() {
        var changes = service.compare("a\nb\n", "a\nc\n", false, false);
        assertEquals("a\nb\n", reconstruct(changes, INSERT));
        assertEquals("a\nc\n", reconstruct(changes, DELETE));
        assertEquals(2, changes.stream().filter(e -> e.kind() != EQUAL).count());
    }

    @Test void normalizesWhitespaceOnceAndKeepsOriginalLine() {
        var changes = service.compare(" A \tB\nΣ", "ab\nσ", true, true);
        assertTrue(changes.stream().allMatch(e -> e.kind() == EQUAL));
        assertEquals(" A \tB", changes.get(0).line());
    }

    @Test void largeSimilarInputsKeepAllLinesWithSmallEdit() {
        String before = "same\n".repeat(5999) + "before";
        String after = "same\n".repeat(5999) + "after";
        var changes = service.compare(before, after, false, false);
        assertEquals(6001, changes.size());
        assertEquals(before, reconstruct(changes, INSERT));
        assertEquals(after, reconstruct(changes, DELETE));
    }

    @Test void findsMinimalEditScriptForRepeatedLines() {
        Random random = new Random(417);
        for (int run = 0; run < 50; run++) {
            String[] a = randomLines(random), b = randomLines(random);
            var changes = service.compare(String.join("\n", a), String.join("\n", b), false, false);
            assertEquals(String.join("\n", a), reconstruct(changes, INSERT));
            assertEquals(String.join("\n", b), reconstruct(changes, DELETE));
            int[][] lcs = new int[a.length + 1][b.length + 1];
            for (int i = 1; i <= a.length; i++) for (int j = 1; j <= b.length; j++)
                lcs[i][j] = a[i - 1].equals(b[j - 1]) ? lcs[i - 1][j - 1] + 1 : Math.max(lcs[i - 1][j], lcs[i][j - 1]);
            assertEquals(lcs[a.length][b.length], changes.stream().filter(e -> e.kind() == EQUAL).count());
        }
    }

    @Test void rejectsOversizedInputsBeforeBuildingLineArrays() {
        assertThrows(IllegalArgumentException.class, () -> service.compare("x".repeat(TextDiffService.MAX_CHARACTERS + 1), "", false, false));
        assertThrows(IllegalArgumentException.class, () -> service.compare("\n".repeat(TextDiffService.MAX_LINES), "", false, false));
    }

    @Test void refusesExcessiveWorkInsteadOfAllocatingQuadraticMemory() {
        assertThrows(IllegalArgumentException.class, () -> service.compare("a\n".repeat(10_000), "b\n".repeat(10_000), false, false));
    }

    @Test void interruptedWorkStops() {
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> service.compare("a", "b", false, false));
        } finally { Thread.interrupted(); }
    }

    private static String[] randomLines(Random random) {
        return random.ints(1 + random.nextInt(25), 0, 5).mapToObj(Integer::toString).toArray(String[]::new);
    }
    private static String reconstruct(List<TextDiffService.Entry> entries, TextDiffService.Kind omitted) {
        return String.join("\n", entries.stream().filter(e -> e.kind() != omitted).map(TextDiffService.Entry::line).toList());
    }
}
