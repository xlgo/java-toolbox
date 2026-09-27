package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 端口查询条件：单个端口、区间或逗号列表，例如 {@code 8080}、{@code 8000-8100}、{@code 80, 443, 9000-9010}。
 *
 * <p>空白输入表示「不限端口」。解析失败抛 {@link InvalidException}，携带错误类别与出错片段，
 * 由界面翻译成提示——领域层不产生面向用户的文案。</p>
 */
public final class PortQuery {

    public static final int MIN_PORT = 0;
    public static final int MAX_PORT = 65535;

    private static final PortQuery ANY = new PortQuery(List.of());

    private final List<int[]> ranges;

    private PortQuery(List<int[]> ranges) {
        this.ranges = ranges;
    }

    public static PortQuery any() {
        return ANY;
    }

    public static PortQuery parse(String text) {
        if (text == null || text.isBlank()) {
            return ANY;
        }
        List<int[]> ranges = new ArrayList<>();
        // 分号和空格也当分隔符：用户从别处粘来的列表常用这些。
        for (String raw : text.split("[,;\\s]+")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            int dash = part.indexOf('-', 1);
            if (dash > 0) {
                int from = port(part.substring(0, dash), part);
                int to = port(part.substring(dash + 1), part);
                if (from > to) {
                    throw new InvalidException(Error.REVERSED_RANGE, part);
                }
                ranges.add(new int[]{from, to});
            } else {
                int value = port(part, part);
                ranges.add(new int[]{value, value});
            }
        }
        return ranges.isEmpty() ? ANY : new PortQuery(List.copyOf(ranges));
    }

    private static int port(String value, String part) {
        String text = value.trim();
        if (text.isEmpty() || text.length() > 6 || !text.chars().allMatch(Character::isDigit)) {
            throw new InvalidException(Error.NOT_A_NUMBER, part);
        }
        int number = Integer.parseInt(text);
        if (number < MIN_PORT || number > MAX_PORT) {
            throw new InvalidException(Error.OUT_OF_RANGE, part);
        }
        return number;
    }

    public boolean isAny() {
        return ranges.isEmpty();
    }

    public boolean matches(int port) {
        if (ranges.isEmpty()) {
            return true;
        }
        for (int[] range : ranges) {
            if (port >= range[0] && port <= range[1]) {
                return true;
            }
        }
        return false;
    }

    /** 恰好是一个端口时返回它，供「端口是否空闲」检查使用。 */
    public OptionalInt singlePort() {
        if (ranges.size() == 1 && ranges.get(0)[0] == ranges.get(0)[1]) {
            return OptionalInt.of(ranges.get(0)[0]);
        }
        return OptionalInt.empty();
    }

    public enum Error {
        NOT_A_NUMBER, OUT_OF_RANGE, REVERSED_RANGE
    }

    /** 端口条件写错了；{@link #getMessage()} 是英文诊断，界面按 {@link #error()} 翻译。 */
    public static final class InvalidException extends IllegalArgumentException {
        private final Error error;
        private final String fragment;

        public InvalidException(Error error, String fragment) {
            super("Invalid port query (" + error + "): " + fragment);
            this.error = Objects.requireNonNull(error, "error");
            this.fragment = fragment == null ? "" : fragment;
        }

        public Error error() {
            return error;
        }

        public String fragment() {
            return fragment;
        }
    }
}
