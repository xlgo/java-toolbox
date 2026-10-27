package com.aqishi.toolbox.feature.network.domain;

import java.util.*;
import java.util.regex.Pattern;

/** Strict, deterministic port-set parsing shared by presets, exclusions and user groups. */
public final class PortExpressionParser {
    private PortExpressionParser() {}

    public static List<String> tokens(String input) {
        if (input == null || input.isBlank()) return List.of();
        if (input.length() > 262144) throw new IllegalArgumentException("portscan.error.inputSize");
        return Arrays.stream(input.trim().split("[\\s,;，；]+", -1))
                .filter(s -> !s.isBlank())
                .toList();
    }

    public static SortedSet<Integer> parse(String input) {
        java.util.BitSet selected = new java.util.BitSet(65536);
        for (String token : tokens(input)) {
            if (!Pattern.matches("[0-9]+(?:-[0-9]+)?", token)) throw invalid(token);
            String[] parts = token.split("-", -1);
            int first = number(parts[0], token),
                    last = parts.length == 1 ? first : number(parts[1], token);
            if (last < first) throw invalid(token);
            selected.set(first, last + 1);
        }
        SortedSet<Integer> ports = new TreeSet<>();
        for (int port = selected.nextSetBit(1); port >= 0; port = selected.nextSetBit(port + 1))
            ports.add(port);
        return ports;
    }

    public static List<Integer> combine(
            Collection<String> presets, String custom, String excluded) {
        SortedSet<Integer> ports = new TreeSet<>();
        for (String preset : presets) ports.addAll(parse(preset));
        ports.addAll(parse(custom));
        ports.removeAll(parse(excluded));
        if (ports.isEmpty()) throw new IllegalArgumentException("portscan.error.noPorts");
        return List.copyOf(ports);
    }

    public static String format(Collection<Integer> values) {
        List<Integer> ports = new ArrayList<>(new TreeSet<>(values));
        StringJoiner text = new StringJoiner(",");
        for (int i = 0; i < ports.size(); i++) {
            int start = ports.get(i), end = start;
            while (i + 1 < ports.size() && ports.get(i + 1) == end + 1) end = ports.get(++i);
            text.add(start == end ? Integer.toString(start) : start + "-" + end);
        }
        return text.toString();
    }

    private static int number(String part, String token) {
        try {
            int value = Integer.parseInt(part);
            if (value >= 1 && value <= 65535) return value;
        } catch (NumberFormatException ignored) {
        }
        throw invalid(token);
    }

    private static IllegalArgumentException invalid(String token) {
        return new IllegalArgumentException("portscan.error.port|" + token);
    }
}
