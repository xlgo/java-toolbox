package com.aqishi.toolbox.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits Kafka client properties text into public lines and credential lines
 * ({@code sasl.jaas.config}, {@code ssl.key.password}, OAuth client secrets ...).
 *
 * <p>Works on logical {@code .properties} lines, so a JAAS value continued with trailing
 * backslashes moves as one unit. Comments and blank lines stay public.</p>
 */
public final class KafkaSensitiveProperties {
    private static final String[] MARKERS = {"password", "secret", "jaas", "token", "credential"};

    private KafkaSensitiveProperties() {
    }

    /** Lines that carry credentials, joined with {@code \n}; empty when none. */
    public static String sensitivePart(String text) {
        return join(split(text, true));
    }

    /** Everything except the credential lines. */
    public static String publicPart(String text) {
        return join(split(text, false));
    }

    /** Appends credential lines back onto the public text. */
    public static String merge(String publicText, String sensitiveText) {
        String base = publicText == null ? "" : publicText.trim();
        String extra = sensitiveText == null ? "" : sensitiveText.trim();
        if (extra.isEmpty()) return base;
        return base.isEmpty() ? extra : base + "\n" + extra;
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null) return false;
        String lower = key.trim().toLowerCase(Locale.ROOT);
        for (String marker : MARKERS) {
            if (lower.contains(marker)) return true;
        }
        // ssl.keystore.key carries a PEM private key.
        return lower.endsWith(".key");
    }

    private static List<String> split(String text, boolean sensitive) {
        List<String> selected = new ArrayList<>();
        if (text == null || text.isEmpty()) return selected;
        String[] physical = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder logical = new StringBuilder();
        for (int i = 0; i < physical.length; i++) {
            String line = physical[i];
            if (logical.length() > 0) logical.append('\n');
            logical.append(line);
            if (continues(line) && i < physical.length - 1) continue;
            String entry = logical.toString();
            logical.setLength(0);
            if (isSensitiveKey(keyOf(entry)) == sensitive) {
                if (!sensitive || !entry.trim().isEmpty()) selected.add(entry);
            }
        }
        return selected;
    }

    private static boolean continues(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) backslashes++;
        return backslashes % 2 == 1;
    }

    /** The key of a logical line, or null for blank lines and comments. */
    static String keyOf(String entry) {
        String trimmed = entry.replaceFirst("^[ \\t\\f]+", "");
        if (trimmed.isEmpty() || trimmed.charAt(0) == '#' || trimmed.charAt(0) == '!') return null;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '\\' && i + 1 < trimmed.length()) {
                key.append(trimmed.charAt(++i));
                continue;
            }
            if (c == '=' || c == ':' || c == ' ' || c == '\t' || c == '\f' || c == '\n') break;
            key.append(c);
        }
        return key.toString();
    }

    private static String join(List<String> lines) {
        return String.join("\n", lines).trim();
    }
}
