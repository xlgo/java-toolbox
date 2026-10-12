package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.I18n;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** Line diff using Hirschberg's linear-space LCS, with bounded work and cancellation. */
public final class TextDiffService {
    public static final int MAX_CHARACTERS = 2 * 1024 * 1024;
    public static final int MAX_LINES = 20_000;
    private static final long MAX_COMPARISONS = 40_000_000;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public enum Kind { EQUAL, INSERT, DELETE }
    public record Entry(Kind kind, String line) { }

    public List<Entry> compare(String before, String after, boolean ignoreCase, boolean ignoreSpace) {
        String[] a = lines(before), b = lines(after);
        State state = new State(a, b, ignoreCase, ignoreSpace);
        List<Entry> entries = new ArrayList<>();
        state.diff(0, a.length, 0, b.length, entries);
        return List.copyOf(entries);
    }

    private static String[] lines(String text) {
        if (text.length() > MAX_CHARACTERS) throw new IllegalArgumentException(I18n.get("diff.inputLimit"));
        int count = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && ++count > MAX_LINES)
                throw new IllegalArgumentException(I18n.get("diff.inputLimit"));
        }
        return text.split("\n", -1);
    }

    private static final class State {
        private final String[] a, b, normalizedA, normalizedB;
        private final boolean ignoreCase;
        private long remaining = MAX_COMPARISONS;
        private final long started = System.nanoTime();

        State(String[] a, String[] b, boolean ignoreCase, boolean ignoreSpace) {
            this.a = a;
            this.b = b;
            this.ignoreCase = ignoreCase;
            normalizedA = normalize(a, ignoreSpace);
            normalizedB = normalize(b, ignoreSpace);
        }

        private static String[] normalize(String[] lines, boolean ignoreSpace) {
            if (!ignoreSpace) return lines;
            String[] normalized = new String[lines.length];
            for (int i = 0; i < lines.length; i++) {
                checkCancelled();
                normalized[i] = WHITESPACE.matcher(lines[i]).replaceAll("");
            }
            return normalized;
        }

        private boolean equal(int i, int j) {
            return ignoreCase ? normalizedA[i].equalsIgnoreCase(normalizedB[j])
                    : normalizedA[i].equals(normalizedB[j]);
        }

        private void diff(int a0, int a1, int b0, int b1, List<Entry> result) {
            checkCancelled();
            while (a0 < a1 && b0 < b1 && equal(a0, b0)) {
                checkCancelled();
                result.add(new Entry(Kind.EQUAL, a[a0++]));
                b0++;
            }
            int suffix = 0;
            while (a0 < a1 && b0 < b1 && equal(a1 - 1, b1 - 1)) {
                checkCancelled();
                a1--; b1--; suffix++;
            }
            if (a0 == a1) {
                for (int j = b0; j < b1; j++) result.add(new Entry(Kind.INSERT, b[j]));
            } else if (b0 == b1) {
                for (int i = a0; i < a1; i++) result.add(new Entry(Kind.DELETE, a[i]));
            } else if (a1 - a0 == 1) {
                int match = b0;
                while (match < b1 && !equal(a0, match)) match++;
                if (match == b1) result.add(new Entry(Kind.DELETE, a[a0]));
                for (int j = b0; j < b1; j++)
                    result.add(j == match ? new Entry(Kind.EQUAL, a[a0]) : new Entry(Kind.INSERT, b[j]));
            } else {
                int middle = (a0 + a1) >>> 1;
                // Row arrays live only in split(), and are released before either recursive call.
                int split = split(a0, middle, a1, b0, b1);
                diff(a0, middle, b0, split, result);
                diff(middle, a1, split, b1, result);
            }
            for (int i = 0; i < suffix; i++) result.add(new Entry(Kind.EQUAL, a[a1 + i]));
        }

        private int split(int a0, int middle, int a1, int b0, int b1) {
            remaining -= (long) (a1 - a0) * (b1 - b0);
            if (remaining < 0) throw new IllegalArgumentException(I18n.get("diff.workLimit"));
            int width = b1 - b0;
            int[] forward = new int[width + 1], backward = new int[width + 1];
            for (int i = a0; i < middle; i++) {
                checkBudget();
                int diagonal = 0;
                for (int j = 1; j <= width; j++) {
                    int previous = forward[j];
                    forward[j] = equal(i, b0 + j - 1) ? diagonal + 1 : Math.max(forward[j], forward[j - 1]);
                    diagonal = previous;
                }
            }
            for (int i = a1 - 1; i >= middle; i--) {
                checkBudget();
                int diagonal = 0;
                for (int j = width - 1; j >= 0; j--) {
                    int previous = backward[j];
                    backward[j] = equal(i, b0 + j) ? diagonal + 1 : Math.max(backward[j], backward[j + 1]);
                    diagonal = previous;
                }
            }
            int best = -1, split = 0;
            for (int j = 0; j <= width; j++) {
                int score = forward[j] + backward[j];
                if (score > best) { best = score; split = j; }
            }
            return b0 + split;
        }

        private void checkBudget() {
            checkCancelled();
            if (System.nanoTime() - started > 5_000_000_000L)
                throw new IllegalArgumentException(I18n.get("diff.workLimit"));
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
