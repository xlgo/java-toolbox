package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GC 日志解析共用的文本小工具：数值、容量、括号组、日期。
 *
 * <p>数值允许逗号小数点：JDK 8 在德语、法语等区域设置下会打印 {@code 0,0123456 secs}。</p>
 */
final class GcText {

    static final String NUM = "\\d+(?:[.,]\\d+)?";
    private static final String SIZE = "(" + NUM + ")\\s?([BKMGT])";

    /** {@code 524800K->12345K(611840K)}、{@code 24.0M(256.0M)->21.5M(256.0M)}、{@code 0.0B->3072.0K}。 */
    static final Pattern HEAP_TRIPLE = Pattern.compile(
            SIZE + "(?:\\(" + SIZE + "\\))?\\s*->\\s*" + SIZE + "(?:\\(" + SIZE + "\\))?");
    /** ZGC：{@code 800M(78%)->200M(20%)}。 */
    static final Pattern PERCENT_HEAP = Pattern.compile(
            SIZE + "\\((\\d+)%\\)\\s*->\\s*" + SIZE + "\\((\\d+)%\\)");
    private static final Pattern ISO_DATE = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})?");

    /**
     * 一次回收前后的容量。
     *
     * @param beforeKb    回收前
     * @param afterKb     回收后
     * @param committedKb 回收后的提交量（缺失时退回回收前的提交量），未知为 -1
     */
    record Heap(long beforeKb, long afterKb, long committedKb) {
    }

    private GcText() {
    }

    static double num(String text) {
        // 快速路径：纯 "123" / "12.345" 手工换算。每行 uptime 都要解析一次，Double.parseDouble 在几百万行上很显眼。
        int length = text.length();
        if (length > 0 && length <= 15) {
            long whole = 0;
            long fraction = 0;
            int fractionDigits = -1;
            boolean simple = true;
            for (int i = 0; i < length; i++) {
                char ch = text.charAt(i);
                if (ch >= '0' && ch <= '9') {
                    if (fractionDigits < 0) {
                        whole = whole * 10 + (ch - '0');
                    } else {
                        fraction = fraction * 10 + (ch - '0');
                        fractionDigits++;
                    }
                } else if ((ch == '.' || ch == ',') && fractionDigits < 0 && i > 0 && i < length - 1) {
                    fractionDigits = 0;
                } else {
                    simple = false;
                    break;
                }
            }
            if (simple) {
                return fractionDigits <= 0 ? whole : whole + fraction / POWERS[fractionDigits];
            }
        }
        return Double.parseDouble(text.indexOf(',') >= 0 ? text.replace(',', '.') : text);
    }

    private static final double[] POWERS = {1, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13,
            1e14, 1e15};

    static long kb(String number, String unit) {
        double value = num(number);
        switch (unit) {
            case "B":
                return Math.round(value / 1024.0);
            case "M":
                return Math.round(value * 1024);
            case "G":
                return Math.round(value * 1024 * 1024);
            case "T":
                return Math.round(value * 1024 * 1024 * 1024);
            default:
                return Math.round(value);
        }
    }

    /** 由 {@link #HEAP_TRIPLE} 的匹配结果换算。 */
    static Heap heap(Matcher triple) {
        return heap(triple, 0);
    }

    /**
     * 同 {@link #heap(Matcher)}，用于把 {@link #HEAP_TRIPLE} 嵌进更大的正则：{@code base} 是它前面的分组数。
     */
    static Heap heap(Matcher triple, int base) {
        long before = kb(triple.group(base + 1), triple.group(base + 2));
        long after = kb(triple.group(base + 5), triple.group(base + 6));
        long committed = -1;
        if (triple.group(base + 7) != null) {
            committed = kb(triple.group(base + 7), triple.group(base + 8));
        } else if (triple.group(base + 3) != null) {
            committed = kb(triple.group(base + 3), triple.group(base + 4));
        }
        return new Heap(before, after, committed);
    }

    static Heap firstHeap(String text) {
        Matcher matcher = HEAP_TRIPLE.matcher(text);
        return matcher.find() ? heap(matcher) : null;
    }

    /** ZGC 的百分比写法：百分比相对最大堆，据此反推出容量作为提交量的近似。 */
    static Heap percentHeap(String text) {
        Matcher matcher = PERCENT_HEAP.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        long before = kb(matcher.group(1), matcher.group(2));
        long after = kb(matcher.group(4), matcher.group(5));
        int percent = Integer.parseInt(matcher.group(3));
        long capacity = percent > 0 ? Math.round(before * 100.0 / percent) : -1;
        return new Heap(before, after, capacity);
    }

    /** 行尾的 {@code 4.678ms} 或 {@code 0.030s}，换算为毫秒；没有时 NaN。 */
    static double trailingMs(String text) {
        int start = trailingDurationStart(text);
        if (start < 0) {
            return Double.NaN;
        }
        String token = text.strip();
        boolean millis = token.endsWith("ms");
        String number = token.substring(start, token.length() - (millis ? 2 : 1)).strip();
        double value = num(number);
        return millis ? value : value * 1000;
    }

    /** 去掉行尾耗时后的文本。 */
    static String withoutTrailingDuration(String text) {
        int start = trailingDurationStart(text);
        return start < 0 ? text : text.strip().substring(0, start).strip();
    }

    /**
     * 行尾耗时（{@code 4.678ms} / {@code 0.030s}，数字与单位间可有一个空格）在 {@code text.strip()} 中的起点；
     * 没有时 -1。从尾部手工回扫而不用 {@code find()}：后者会在每个位置都尝试一遍。
     */
    private static int trailingDurationStart(String text) {
        String token = text.strip();
        int end;
        if (token.endsWith("ms")) {
            end = token.length() - 2;
        } else if (token.endsWith("s")) {
            end = token.length() - 1;
        } else {
            return -1;
        }
        if (end > 0 && token.charAt(end - 1) == ' ') {
            end--;
        }
        int index = end;
        boolean digits = false;
        while (index > 0) {
            char ch = token.charAt(index - 1);
            if (Character.isDigit(ch)) {
                digits = true;
            } else if (ch != '.' && ch != ',') {
                break;
            }
            index--;
        }
        if (!digits || !Character.isDigit(token.charAt(index))) {
            return -1;
        }
        // 数字前必须是边界，避免把 "Warmup2s" 之类误当耗时。
        if (index > 0 && !Character.isWhitespace(token.charAt(index - 1)) && token.charAt(index - 1) != '(') {
            return -1;
        }
        return index;
    }

    /**
     * 从 {@code from} 起连续的顶层括号组，如 {@code (Normal) (System.gc())} 得到 {@code Normal}、
     * {@code System.gc()}；遇到括号以外的非空白字符就停。
     */
    static List<String> parenGroups(String text, int from) {
        List<String> groups = new ArrayList<>();
        int index = from;
        while (true) {
            while (index < text.length() && text.charAt(index) == ' ') {
                index++;
            }
            if (index >= text.length() || text.charAt(index) != '(') {
                return groups;
            }
            int depth = 0;
            int start = index + 1;
            int end = -1;
            for (int i = index; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end < 0) {
                return groups;
            }
            groups.add(text.substring(start, end).strip());
            index = end + 1;
        }
    }

    /** 与 {@link #parenGroups} 相同，但返回停下的位置。 */
    static int afterParenGroups(String text, int from) {
        int index = from;
        while (true) {
            int probe = index;
            while (probe < text.length() && text.charAt(probe) == ' ') {
                probe++;
            }
            if (probe >= text.length() || text.charAt(probe) != '(') {
                return index;
            }
            int depth = 0;
            int end = -1;
            for (int i = probe; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end < 0) {
                return index;
            }
            index = end + 1;
        }
    }

    /**
     * ISO-8601 日期换算为纪元毫秒；不带时区按 UTC 处理（只用于求相对间隔，时区不影响差值）。
     *
     * <p>手写解析而不走 {@code DateTimeFormatter}：几百万行日志每行一次格式化解析会明显拖慢。</p>
     *
     * @return 纪元毫秒，无法识别时 {@link Long#MIN_VALUE}
     */
    static long epochMillis(String text) {
        if (text == null) {
            return Long.MIN_VALUE;
        }
        Matcher matcher = ISO_DATE.matcher(text);
        if (!matcher.lookingAt()) {
            return Long.MIN_VALUE;
        }
        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        long days;
        try {
            days = java.time.LocalDate.of(year, month, day).toEpochDay();
        } catch (java.time.DateTimeException invalid) {
            return Long.MIN_VALUE;
        }
        long seconds = days * 86_400L + Integer.parseInt(matcher.group(4)) * 3600L
                + Integer.parseInt(matcher.group(5)) * 60L + Integer.parseInt(matcher.group(6));
        long millis = 0;
        if (matcher.group(7) != null) {
            String fraction = (matcher.group(7) + "00").substring(0, 3);
            millis = Integer.parseInt(fraction);
        }
        String zone = matcher.group(8);
        if (zone != null && !"Z".equals(zone)) {
            String digits = zone.replace(":", "");
            int sign = digits.charAt(0) == '-' ? -1 : 1;
            int offset = Integer.parseInt(digits.substring(1, 3)) * 3600 + Integer.parseInt(digits.substring(3, 5)) * 60;
            seconds -= sign * offset;
        }
        return seconds * 1000 + millis;
    }

    /** 截断到指定长度，避免超长行撑爆样本。 */
    static String clip(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "...";
    }
}
