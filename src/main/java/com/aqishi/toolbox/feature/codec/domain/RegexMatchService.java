package com.aqishi.toolbox.feature.codec.domain;

import com.aqishi.toolbox.util.I18n;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps Java regex syntax while making backtracking respect a shared execution budget. */
public final class RegexMatchService {
    public static final int MAX_INPUT = 512 * 1024;
    public static final int MAX_PATTERN = 4096;
    public static final int MAX_MATCHES = 2000;
    private static final int MAX_OUTPUT = 2 * 1024 * 1024;
    private final long timeoutNanos;

    public RegexMatchService() { this(Duration.ofMillis(500)); }

    public RegexMatchService(Duration timeout) {
        timeoutNanos = timeout.toNanos();
        if (timeoutNanos <= 0) throw new IllegalArgumentException("timeout must be positive");
    }

    public String match(String pattern, String text, int flags, boolean global) {
        if (pattern.length() > MAX_PATTERN || text.length() > MAX_INPUT)
            throw new IllegalArgumentException(I18n.get("regex.inputLimit"));
        checkRepetitions(pattern);
        Budget budget = new Budget(timeoutNanos);
        try {
            Matcher matcher = Pattern.compile(pattern, flags).matcher(new CheckedText(text, 0, text.length(), budget));
            if (matcher.groupCount() > 256) throw new IllegalArgumentException(I18n.get("regex.groupLimit"));
            StringBuilder highlighted = new StringBuilder(), details = new StringBuilder();
            int last = 0, count = 0;
            while (matcher.find()) {
                budget.check();
                append(highlighted, text.substring(last, matcher.start()));
                append(highlighted, "【" + matcher.group() + "】");
                append(details, "\n  #" + count + " => " + matcher.group());
                for (int g = 1; g <= matcher.groupCount(); g++)
                    append(details, "  $" + g + "=" + matcher.group(g));
                last = matcher.end();
                count++;
                if (!global || count == MAX_MATCHES) break;
            }
            append(highlighted, text.substring(last));
            append(highlighted, "\n\n" + I18n.get("regex.count", count));
            if (global && count == MAX_MATCHES) append(highlighted, "\n" + I18n.get("regex.matchLimit", MAX_MATCHES));
            if (count > 0) append(highlighted, "\n" + I18n.get("regex.groups") + details);
            budget.check();
            return highlighted.toString();
        } catch (StackOverflowError overflow) {
            throw new IllegalArgumentException(I18n.get("regex.complexity"), overflow);
        }
    }

    private static void append(StringBuilder output, String text) {
        if ((long) output.length() + text.length() > MAX_OUTPUT)
            throw new IllegalArgumentException(I18n.get("regex.outputLimit"));
        output.append(text);
    }

    /** Empty repetitions need not touch CharSequence, so bound counted expansion before evaluation. */
    private static void checkRepetitions(String pattern) {
        final long limit = 8_000_000;
        Deque<Long> groups = new ArrayDeque<>();
        Deque<Integer> classes = new ArrayDeque<>();
        long cost = 0, atom = 0;
        boolean quoted = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                char escaped = pattern.charAt(i + 1);
                if (quoted) {
                    if (escaped == 'E') { quoted = false; i++; continue; }
                } else {
                    i++;
                    if (escaped == 'Q') { quoted = true; continue; }
                    if ((escaped == 'x' || escaped == 'p' || escaped == 'P' || escaped == 'N')
                            && i + 1 < pattern.length() && pattern.charAt(i + 1) == '{') {
                        int end = pattern.indexOf('}', i + 2);
                        if (end != -1) i = end;
                    }
                    if (classes.isEmpty()) { cost++; atom = 1; }
                    continue;
                }
            }
            if (quoted) { cost++; atom = 1; continue; }
            if (c == '[') { classes.push(i); continue; }
            if (!classes.isEmpty()) {
                int start = classes.peek();
                boolean first = i == start + 1 || (i == start + 2 && pattern.charAt(start + 1) == '^');
                if (c == ']' && !first) {
                    classes.pop();
                    if (classes.isEmpty()) { cost++; atom = 1; }
                }
                continue;
            }
            if (c == '(') {
                groups.push(cost);
                cost = 0; atom = 0;
            } else if (c == ')' && !groups.isEmpty()) {
                atom = cost + 1;
                cost = groups.pop() + atom;
            } else if (c == '{' && i + 1 < pattern.length() && Character.isDigit(pattern.charAt(i + 1))) {
                int end = i + 1;
                long repetitions = 0, maximum = 0;
                while (end < pattern.length() && Character.isDigit(pattern.charAt(end))) {
                    repetitions = Math.min(limit + 1, repetitions * 10 + pattern.charAt(end++) - '0');
                }
                maximum = repetitions;
                if (end < pattern.length() && pattern.charAt(end) == ',') {
                    end++;
                    int start = end;
                    maximum = 0;
                    while (end < pattern.length() && Character.isDigit(pattern.charAt(end))) {
                        maximum = Math.min(limit + 1, maximum * 10 + pattern.charAt(end++) - '0');
                    }
                    if (start == end) maximum = Math.max(1, repetitions);
                }
                if (end < pattern.length() && pattern.charAt(end) == '}') {
                    if (maximum > MAX_INPUT || atom * maximum > limit)
                        throw new IllegalArgumentException(I18n.get("regex.repetitionLimit"));
                    cost = cost - atom + atom * maximum;
                    atom *= maximum;
                    i = end;
                } else { cost++; atom = 1; }
            } else if (c != '*' && c != '+' && c != '?') {
                cost++; atom = 1;
            }
            if (cost > limit) throw new IllegalArgumentException(I18n.get("regex.repetitionLimit"));
        }
    }

    private static final class Budget {
        private final long started = System.nanoTime(), timeout;
        private int accesses;
        Budget(long timeout) { this.timeout = timeout; }
        void accessed() { if ((++accesses & 255) == 0) check(); }
        void check() {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (System.nanoTime() - started > timeout)
                throw new IllegalArgumentException(I18n.get("regex.timeout"));
        }
    }

    private record CheckedText(String text, int start, int end, Budget budget) implements CharSequence {
        @Override public int length() { budget.accessed(); return end - start; }
        @Override public char charAt(int index) {
            budget.accessed();
            if (index < 0 || index >= end - start) throw new IndexOutOfBoundsException(index);
            return text.charAt(start + index);
        }
        @Override public CharSequence subSequence(int from, int to) {
            budget.check();
            if (from < 0 || to < from || to > end - start) throw new IndexOutOfBoundsException();
            return new CheckedText(text, start + from, start + to, budget);
        }
        @Override public String toString() { budget.check(); return text.substring(start, end); }
    }
}
