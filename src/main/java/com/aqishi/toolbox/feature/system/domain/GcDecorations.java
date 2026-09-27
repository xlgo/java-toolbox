package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * JDK 9+ 统一日志的行首装饰：{@code [2024-05-01T10:00:00.123+0800][12.345s][1234][5678][info][gc,start]}。
 *
 * <p>{@code -Xlog} 的装饰器可以任意组合与排序（time、utctime、uptime、timemillis、uptimemillis、
 * timenanos、uptimenanos、hostname、pid、tid、level、tags），所以不写死位置，而是逐组识别；
 * 任何一组认不出来就判定这行不是统一日志——JDK 8 的 {@code [GC (Allocation Failure) ...}
 * 与 {@code [Times: ...]} 也以方括号开头，靠这一点与装饰区分。</p>
 */
final class GcDecorations {

    private static final Pattern DATE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?");
    private static final Pattern MILLIS = Pattern.compile("\\d+ms");
    private static final Pattern NANOS = Pattern.compile("\\d+ns");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> LEVELS = Set.of("trace", "debug", "info", "warning", "error", "off");
    /** timemillis 与 uptimemillis 都以 ms 结尾：大于这个值的只可能是纪元毫秒（约 2001 年之后）。 */
    private static final long EPOCH_MILLIS_FLOOR = 1_000_000_000_000L;

    private GcDecorations() {
    }

    /**
     * @return 识别出装饰时返回填好 {@code body} 的 Stamp；不是统一日志行时返回 null
     */
    static GcParseState.Stamp parse(String line) {
        if (line.isEmpty() || line.charAt(0) != '[') {
            return null;
        }
        List<String> groups = new ArrayList<>(6);
        int index = 0;
        while (index < line.length() && line.charAt(index) == '[') {
            int close = line.indexOf(']', index);
            if (close < 0) {
                return null;
            }
            groups.add(line.substring(index + 1, close).strip());
            index = close + 1;
        }
        GcParseState.Stamp stamp = new GcParseState.Stamp();
        boolean anchored = false;
        for (int i = 0; i < groups.size(); i++) {
            String group = groups.get(i);
            boolean last = i == groups.size() - 1;
            if (group.isEmpty()) {
                return null;
            }
            // 按首字符分流并先做廉价的字符判断：每行三五个装饰组，逐个跑全部正则在几百万行的日志上很可观。
            char first = group.charAt(0);
            if (first < '0' || first > '9') {
                if (LEVELS.contains(group)) {
                    stamp.level = group;
                    anchored = true;
                } else if (last && isTags(group)) {
                    stamp.tags = group;
                } else if (LEVELS.contains(group.toLowerCase(Locale.ROOT))) {
                    stamp.level = group.toLowerCase(Locale.ROOT);
                    anchored = true;
                } else if (last || !HOST.matcher(group).matches()) {
                    return null;
                }
                continue;
            }
            if (isSeconds(group)) {
                stamp.uptime = GcText.num(group.substring(0, group.length() - 1));
                anchored = true;
                continue;
            }
            if (isDigits(group, 0, group.length())) {
                // pid / tid
                continue;
            }
            if (group.length() >= 19 && group.charAt(4) == '-' && DATE.matcher(group).matches()) {
                if (stamp.date == null) {
                    stamp.date = group;
                    stamp.epochMs = GcText.epochMillis(group);
                }
                anchored = true;
            } else if (MILLIS.matcher(group).matches()) {
                long value = Long.parseLong(group.substring(0, group.length() - 2));
                if (value >= EPOCH_MILLIS_FLOOR) {
                    if (stamp.epochMs == Long.MIN_VALUE) {
                        stamp.epochMs = value;
                    }
                } else if (Double.isNaN(stamp.uptime)) {
                    stamp.uptime = value / 1000.0;
                }
                anchored = true;
            } else if (NANOS.matcher(group).matches()) {
                long value = Long.parseLong(group.substring(0, group.length() - 2));
                if (value >= EPOCH_MILLIS_FLOOR * 1_000_000L) {
                    if (stamp.epochMs == Long.MIN_VALUE) {
                        stamp.epochMs = value / 1_000_000L;
                    }
                } else if (Double.isNaN(stamp.uptime)) {
                    stamp.uptime = value / 1e9;
                }
                anchored = true;
            } else if (!last && HOST.matcher(group).matches()) {
                // 以数字开头的 hostname：只可能出现在级别与标签之前
                continue;
            } else {
                return null;
            }
        }
        // 只有一个 [xxx] 前缀（如应用日志的 [main]）不算统一日志：必须有时间或级别。
        if (!anchored) {
            return null;
        }
        stamp.body = line.substring(index).strip();
        return stamp;
    }

    private static boolean isDigits(String text, int from, int to) {
        if (from >= to) {
            return false;
        }
        for (int i = from; i < to; i++) {
            char ch = text.charAt(i);
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /** {@code 12.345s}：数字、至多一个小数点、以 s 结尾（不是 ms / ns）。 */
    private static boolean isSeconds(String group) {
        int end = group.length() - 1;
        if (end < 1 || group.charAt(end) != 's') {
            return false;
        }
        int dot = -1;
        for (int i = 0; i < end; i++) {
            char ch = group.charAt(i);
            if (ch == '.' || ch == ',') {
                if (dot >= 0 || i == 0 || i == end - 1) {
                    return false;
                }
                dot = i;
            } else if (ch < '0' || ch > '9') {
                return false;
            }
        }
        return true;
    }

    /** {@code gc,start}：小写字母、数字、下划线，逗号分隔且不以逗号开头或结尾。 */
    private static boolean isTags(String group) {
        char previous = ',';
        for (int i = 0; i < group.length(); i++) {
            char ch = group.charAt(i);
            boolean word = ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '_';
            if (!word && !(ch == ',' && previous != ',')) {
                return false;
            }
            previous = ch;
        }
        return previous != ',';
    }

    /** 标签是否与 GC 有关（没有标签装饰时一律按相关处理）。 */
    static boolean gcRelated(GcParseState.Stamp stamp) {
        String tags = stamp.tags;
        return tags == null || tags.startsWith("gc") || tags.contains("safepoint");
    }

    static boolean hasTag(GcParseState.Stamp stamp, String tag) {
        String tags = stamp.tags;
        if (tags == null) {
            return false;
        }
        int from = 0;
        while (from <= tags.length()) {
            int comma = tags.indexOf(',', from);
            int end = comma < 0 ? tags.length() : comma;
            if (end - from == tag.length() && tags.startsWith(tag, from)) {
                return true;
            }
            if (comma < 0) {
                return false;
            }
            from = comma + 1;
        }
        return false;
    }
}
