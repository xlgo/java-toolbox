package com.aqishi.toolbox.feature.network.domain;

import java.math.BigInteger;
import java.net.*;
import java.util.*;

/**
 * IPv4 ranges stay as intervals until the plan has passed its target budget. No DNS lookup here.
 */
public final class ScanTargetParser {
    private ScanTargetParser() {}

    public record Range(long first, long last, String source) {
        public long count() {
            return last - first + 1;
        }
    }

    public record Target(String source, String ip) {}

    public record V6Range(BigInteger first, BigInteger last, String scope, String source) {
        public long count() {
            return last.subtract(first)
                    .add(BigInteger.ONE)
                    .min(BigInteger.valueOf(Long.MAX_VALUE))
                    .longValue();
        }
    }

    private record V6Address(BigInteger value, String scope) {}

    public record Targets(
            List<Range> ranges,
            Map<String, String> literals,
            List<String> names,
            List<V6Range> ipv6Ranges) {
        public Targets(List<Range> ranges, Map<String, String> literals, List<String> names) {
            this(ranges, literals, names, List.of());
        }

        public Targets {
            ranges = List.copyOf(ranges);
            literals = Collections.unmodifiableMap(new LinkedHashMap<>(literals));
            names = List.copyOf(names);
            ipv6Ranges = List.copyOf(ipv6Ranges);
        }

        public long numericCount() {
            long count = ranges.stream().mapToLong(Range::count).sum() + literals.size();
            for (V6Range range : ipv6Ranges)
                count =
                        range.count() > Long.MAX_VALUE - count
                                ? Long.MAX_VALUE
                                : count + range.count();
            return count;
        }

        public boolean contains(String ip) {
            if (ip.indexOf(':') >= 0) {
                if (literals.containsKey(ip)) return true;
                V6Address address = v6(ip);
                return ipv6Ranges.stream()
                        .anyMatch(
                                range ->
                                        range.scope.equals(address.scope)
                                                && address.value.compareTo(range.first) >= 0
                                                && address.value.compareTo(range.last) <= 0);
            }
            long value = ipv4(ip);
            return ranges.stream().anyMatch(range -> value >= range.first && value <= range.last);
        }

        public List<Target> expand(int budget) {
            if (numericCount() > budget)
                throw new IllegalArgumentException("portscan.error.hostBudget");
            ArrayList<Target> values = new ArrayList<>();
            for (Range range : ranges)
                for (long ip = range.first; ip <= range.last; ip++)
                    values.add(new Target(range.source, format(ip)));
            literals.forEach((ip, source) -> values.add(new Target(source, ip)));
            for (V6Range range : ipv6Ranges)
                for (BigInteger ip = range.first;
                        ip.compareTo(range.last) <= 0;
                        ip = ip.add(BigInteger.ONE))
                    values.add(new Target(range.source, formatV6(ip, range.scope)));
            return values;
        }
    }

    public static Targets parse(String input, boolean includeEdges) {
        List<Range> ranges = new ArrayList<>();
        Map<String, String> literals = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        List<V6Range> v6Ranges = new ArrayList<>();
        List<String> tokens = PortExpressionParser.tokens(input);
        if (tokens.size() > 4096) throw new IllegalArgumentException("portscan.error.inputSize");
        for (String token : tokens) {
            try {
                if (token.contains("/")) {
                    String[] parts = token.split("/", -1);
                    if (parts.length != 2 || !parts[1].matches("[0-9]{1,3}")) throw invalid(token);
                    int bits = Integer.parseInt(parts[1]);
                    if (parts[0].contains(":")) {
                        if (bits > 128) throw invalid(token);
                        V6Address address = v6(parts[0]);
                        BigInteger size = BigInteger.ONE.shiftLeft(128 - bits);
                        BigInteger first = address.value.divide(size).multiply(size);
                        addUniqueV6(
                                v6Ranges,
                                new V6Range(
                                        first,
                                        first.add(size).subtract(BigInteger.ONE),
                                        address.scope,
                                        token));
                        continue;
                    }
                    if (bits > 32) throw invalid(token);
                    long ip = ipv4(parts[0]),
                            size = 1L << (32 - bits),
                            first = ip & (0xffffffffL ^ (size - 1)),
                            last = first + size - 1;
                    if (!includeEdges && bits < 31) {
                        first++;
                        last--;
                    }
                    addUnique(ranges, new Range(first, last, token));
                } else if (token.contains(":")) {
                    String[] ends = token.split("-", -1);
                    if (ends.length == 2 && ends[0].contains(":") && ends[1].contains(":")) {
                        V6Address first = v6(ends[0]), last = v6(ends[1]);
                        if (!first.scope.equals(last.scope)
                                || first.value.compareTo(last.value) > 0) throw invalid(token);
                        addUniqueV6(
                                v6Ranges, new V6Range(first.value, last.value, first.scope, token));
                        continue;
                    }
                    String ip =
                            token.startsWith("[") && token.endsWith("]")
                                    ? token.substring(1, token.length() - 1)
                                    : token;
                    if (!ip.matches("[0-9a-fA-F:.]+(?:%[a-zA-Z0-9_.-]+)?")) throw invalid(token);
                    InetAddress address = InetAddress.getByName(ip);
                    if (address instanceof Inet4Address) {
                        long value = ipv4(address.getHostAddress());
                        addUnique(ranges, new Range(value, value, token));
                    } else {
                        V6Address number = v6(address.getHostAddress());
                        addUniqueV6(
                                v6Ranges,
                                new V6Range(number.value, number.value, number.scope, token));
                    }
                } else if (token.contains("*")) {
                    String[] parts = token.split("\\.", -1);
                    if (parts.length != 4) throw invalid(token);
                    boolean wildcard = false;
                    long first = 0, last = 0;
                    for (String part : parts) {
                        first <<= 8;
                        last <<= 8;
                        if (part.equals("*")) {
                            wildcard = true;
                            last |= 255;
                        } else {
                            if (wildcard) throw invalid(token);
                            int number = octet(part);
                            first |= number;
                            last |= number;
                        }
                    }
                    addUnique(ranges, new Range(first, last, token));
                } else if (token.matches("[0-9.]+-.*")) {
                    String[] parts = token.split("-", -1);
                    if (parts.length != 2) throw invalid(token);
                    long first = ipv4(parts[0]);
                    long last =
                            parts[1].contains(".")
                                    ? ipv4(parts[1])
                                    : (first & 0xffffff00L) | octet(parts[1]);
                    if (last < first) throw invalid(token);
                    addUnique(ranges, new Range(first, last, token));
                } else if (token.matches("[0-9.]+")) {
                    long ip = ipv4(token);
                    addUnique(ranges, new Range(ip, ip, token));
                } else {
                    String host =
                            IDN.toASCII(token, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
                    if (host.length() > 253
                            || !host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.?"))
                        throw invalid(token);
                    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
                    for (String label : host.split("\\.", -1))
                        if (label.isEmpty()
                                || label.length() > 63
                                || label.startsWith("-")
                                || label.endsWith("-")) throw invalid(token);
                    names.add(host);
                }
            } catch (Exception error) {
                throw invalid(token);
            }
        }
        ranges.sort(Comparator.comparingLong(Range::first));
        return new Targets(ranges, literals, List.copyOf(names), v6Ranges);
    }

    public static Targets subtract(Targets included, Targets excluded) {
        List<Range> ranges = new ArrayList<>(included.ranges);
        for (Range exclusion : excluded.ranges) ranges = subtractRanges(ranges, exclusion);
        Map<String, String> literals = new LinkedHashMap<>(included.literals);
        excluded.literals.keySet().forEach(literals::remove);
        literals.keySet().removeIf(excluded::contains);
        List<V6Range> v6Ranges = new ArrayList<>(included.ipv6Ranges);
        for (V6Range exclusion : excluded.ipv6Ranges) v6Ranges = subtractV6(v6Ranges, exclusion);
        List<String> names =
                included.names.stream().filter(name -> !excluded.names.contains(name)).toList();
        return new Targets(ranges, literals, names, v6Ranges);
    }

    private static void addUniqueV6(List<V6Range> ranges, V6Range added) {
        List<V6Range> remaining = new ArrayList<>(List.of(added));
        for (V6Range previous : ranges) remaining = subtractV6(remaining, previous);
        ranges.addAll(remaining);
    }

    private static List<V6Range> subtractV6(List<V6Range> included, V6Range excluded) {
        List<V6Range> values = new ArrayList<>();
        for (V6Range range : included) {
            if (!range.scope.equals(excluded.scope)
                    || range.last.compareTo(excluded.first) < 0
                    || range.first.compareTo(excluded.last) > 0) {
                values.add(range);
                continue;
            }
            if (range.first.compareTo(excluded.first) < 0)
                values.add(
                        new V6Range(
                                range.first,
                                excluded.first.subtract(BigInteger.ONE),
                                range.scope,
                                range.source));
            if (range.last.compareTo(excluded.last) > 0)
                values.add(
                        new V6Range(
                                excluded.last.add(BigInteger.ONE),
                                range.last,
                                range.scope,
                                range.source));
        }
        return values;
    }

    private static V6Address v6(String input) {
        String ip =
                input.startsWith("[") && input.endsWith("]")
                        ? input.substring(1, input.length() - 1)
                        : input;
        if (!ip.matches("[0-9a-fA-F:.]+(?:%[a-zA-Z0-9_.-]+)?")) throw invalid(input);
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (!(address instanceof Inet6Address ipv6)) throw invalid(input);
            return new V6Address(
                    new BigInteger(1, address.getAddress()),
                    ipv6.getScopeId() > 0 ? "%" + ipv6.getScopeId() : "");
        } catch (UnknownHostException error) {
            throw invalid(input);
        }
    }

    public static String formatV6(BigInteger value, String scope) {
        byte[] encoded = value.toByteArray(), bytes = new byte[16];
        System.arraycopy(
                encoded,
                Math.max(0, encoded.length - 16),
                bytes,
                Math.max(0, 16 - encoded.length),
                Math.min(16, encoded.length));
        try {
            return Inet6Address.getByAddress(null, bytes, -1).getHostAddress() + scope;
        } catch (UnknownHostException impossible) {
            throw new IllegalArgumentException(impossible);
        }
    }

    private static void addUnique(List<Range> ranges, Range added) {
        List<Range> remaining = new ArrayList<>(List.of(added));
        for (Range previous : ranges) remaining = subtractRanges(remaining, previous);
        ranges.addAll(remaining);
    }

    private static List<Range> subtractRanges(List<Range> included, Range excluded) {
        ArrayList<Range> result = new ArrayList<>();
        for (Range range : included) {
            if (excluded.last < range.first || excluded.first > range.last) {
                result.add(range);
                continue;
            }
            if (range.first < excluded.first)
                result.add(new Range(range.first, excluded.first - 1, range.source));
            if (range.last > excluded.last)
                result.add(new Range(excluded.last + 1, range.last, range.source));
        }
        return result;
    }

    public static long ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) throw invalid(text);
        long value = 0;
        for (String part : parts) value = (value << 8) | octet(part);
        return value;
    }

    private static int octet(String text) {
        if (!text.matches("0|[1-9][0-9]{0,2}")) throw invalid(text);
        int value = Integer.parseInt(text);
        if (value > 255) throw invalid(text);
        return value;
    }

    public static String format(long ip) {
        return (ip >>> 24)
                + "."
                + ((ip >>> 16) & 255)
                + "."
                + ((ip >>> 8) & 255)
                + "."
                + (ip & 255);
    }

    private static IllegalArgumentException invalid(String token) {
        return new IllegalArgumentException("portscan.error.target|" + token);
    }
}
