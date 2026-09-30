package com.aqishi.toolbox.feature.system.domain;

import com.aqishi.toolbox.util.I18n;
import java.util.*;

/** Numeric Unix-style cron, optionally extended with a leading seconds field. Not a Quartz parser. */
public final class CronSchedule {
    private CronSchedule() { }

    /** Expand a validated numeric field for the visual editor without changing its original expression. */
    public static Set<Integer> fieldValues(String field, int min, int max) {
        return Collections.unmodifiableSet(parseField(field, min, max));
    }
    public static List<Date> next(String cronExpression, int count, java.time.Instant after, java.time.ZoneId zone) {
        if (count < 1 || count > 1000) throw new IllegalArgumentException("count must be within 1..1000");
        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length != 5 && fields.length != 6) {
            throw new IllegalArgumentException(I18n.get("tool.cron.error.fields"));
        }

        boolean hasSeconds = fields.length == 6;
        String secField = hasSeconds ? fields[0] : "0";
        String minField = hasSeconds ? fields[1] : fields[0];
        String hourField = hasSeconds ? fields[2] : fields[1];
        String dayField = hasSeconds ? fields[3] : fields[2];
        String monthField = hasSeconds ? fields[4] : fields[3];
        String dowField = hasSeconds ? fields[5] : fields[4];

        for (String field : List.of(secField, minField, hourField, monthField)) {
            if (field.contains("?")) throw new IllegalArgumentException(I18n.get("tool.cron.error.question"));
        }

        Set<Integer> allowedSecs = parseField(secField, 0, 59);
        Set<Integer> allowedMins = parseField(minField, 0, 59);
        Set<Integer> allowedHours = parseField(hourField, 0, 23);
        Set<Integer> allowedDays = parseField(dayField, 1, 31);
        Set<Integer> allowedMonthsCron = parseField(monthField, 1, 12);
        Set<Integer> allowedMonths = new TreeSet<>();
        for (int m : allowedMonthsCron) allowedMonths.add(m - 1);

        Set<Integer> allowedDowsCron = parseField(dowField, 0, 7);
        Set<Integer> allowedDows = new HashSet<>();
        for (int dow : allowedDowsCron) {
            if (dow == 0 || dow == 7) {
                allowedDows.add(Calendar.SUNDAY);
            } else {
                allowedDows.add(dow + 1);
            }
        }

        List<Date> results = new ArrayList<>();
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(zone));
        cal.setTime(Date.from(after));
        cal.add(Calendar.SECOND, 1);
        cal.set(Calendar.MILLISECOND, 0);

        int maxSearches = 100000;
        int searches = 0;

        while (results.size() < count && searches < maxSearches) {
            searches++;

            int sec = cal.get(Calendar.SECOND);
            if (!allowedSecs.contains(sec)) {
                int nextSec = getNextAllowed(sec, allowedSecs);
                if (nextSec < sec) {
                    cal.add(Calendar.MINUTE, 1);
                }
                cal.set(Calendar.SECOND, nextSec);
                continue;
            }

            int min = cal.get(Calendar.MINUTE);
            if (!allowedMins.contains(min)) {
                int nextMin = getNextAllowed(min, allowedMins);
                if (nextMin < min) {
                    cal.add(Calendar.HOUR_OF_DAY, 1);
                }
                cal.set(Calendar.MINUTE, nextMin);
                cal.set(Calendar.SECOND, getMin(allowedSecs));
                continue;
            }

            int hour = cal.get(Calendar.HOUR_OF_DAY);
            if (!allowedHours.contains(hour)) {
                int nextHour = getNextAllowed(hour, allowedHours);
                if (nextHour < hour) {
                    cal.add(Calendar.DAY_OF_MONTH, 1);
                }
                cal.set(Calendar.HOUR_OF_DAY, nextHour);
                cal.set(Calendar.MINUTE, getMin(allowedMins));
                cal.set(Calendar.SECOND, getMin(allowedSecs));
                continue;
            }

            int day = cal.get(Calendar.DAY_OF_MONTH);
            int month = cal.get(Calendar.MONTH);
            int dow = cal.get(Calendar.DAY_OF_WEEK);

            if (!allowedMonths.contains(month)) {
                int nextMonth = getNextAllowed(month, allowedMonths);
                if (nextMonth < month) {
                    cal.add(Calendar.YEAR, 1);
                }
                cal.set(Calendar.MONTH, nextMonth);
                cal.set(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR_OF_DAY, getMin(allowedHours));
                cal.set(Calendar.MINUTE, getMin(allowedMins));
                cal.set(Calendar.SECOND, getMin(allowedSecs));
                continue;
            }

            boolean dayMatches = allowedDays.contains(day);
            boolean dowMatches = allowedDows.contains(dow);

            boolean dayIsWildcard = dayField.startsWith("*") || dayField.equals("?");
            boolean dowIsWildcard = dowField.startsWith("*") || dowField.equals("?");

            boolean dateMatches;
            if (!dayIsWildcard && !dowIsWildcard) {
                dateMatches = dayMatches || dowMatches;
            } else {
                dateMatches = dayMatches && dowMatches;
            }

            if (!dateMatches) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
                cal.set(Calendar.HOUR_OF_DAY, getMin(allowedHours));
                cal.set(Calendar.MINUTE, getMin(allowedMins));
                cal.set(Calendar.SECOND, getMin(allowedSecs));
                continue;
            }

            results.add(cal.getTime());
            cal.add(Calendar.SECOND, 1);
        }

        if (results.size() < count) {
            throw new IllegalStateException(I18n.get("tool.cron.error.searchLimit"));
        }

        return results;
    }

    /**
     * 解析单个 cron 字段为允许值集合。
     *
     * <p>步长必须 ≥ 1、所有数值必须落在字段范围内，否则抛出带说明的
     * {@link IllegalArgumentException}。早先 {@code *}{@code /0} 会让循环永不前进，
     * 而这个解析在每次按键时都跑在界面线程上——输入 {@code 1/0} 就能把整个程序卡死。</p>
     */
    private static Set<Integer> parseField(String field, int min, int max) {
        Set<Integer> values = new TreeSet<>();
        if (field.contains("?") && !field.equals("?")) {
            throw new IllegalArgumentException(I18n.get("tool.cron.error.question"));
        }
        if (field.equals("*") || field.equals("?")) {
            for (int i = min; i <= max; i++) values.add(i);
            return values;
        }
        for (String part : field.split(",", -1)) {
            int start;
            int end;
            int step = 1;
            String range = part;
            if (part.contains("/")) {
                String[] stepParts = part.split("/", -1);
                if (stepParts.length != 2) {
                    throw new IllegalArgumentException(I18n.get("tool.cron.error.stepSyntax", part));
                }
                range = stepParts[0];
                step = parseNumber(stepParts[1], part);
                if (step < 1) {
                    throw new IllegalArgumentException(I18n.get("tool.cron.error.stepPositive", part));
                }
            }
            if (range.equals("*") || range.equals("?")) {
                start = min;
                end = max;
            } else if (range.contains("-")) {
                String[] rangeParts = range.split("-", -1);
                if (rangeParts.length != 2) {
                    throw new IllegalArgumentException(I18n.get("tool.cron.error.rangeSyntax", part));
                }
                start = parseNumber(rangeParts[0], part);
                end = parseNumber(rangeParts[1], part);
            } else {
                start = parseNumber(range, part);
                // "5/15" 表示从 5 开始每 15 个单位一次；单独的 "5" 只匹配 5。
                end = part.contains("/") ? max : start;
            }
            if (start < min || end > max || start > end) {
                throw new IllegalArgumentException(I18n.get("tool.cron.error.outOfRange", min, max, part));
            }
            for (long i = start; i <= end; i += step) {
                values.add((int) i);
            }
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException(I18n.get("tool.cron.error.empty", field));
        }
        return values;
    }

    private static int parseNumber(String text, String part) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notNumeric) {
            throw new IllegalArgumentException(I18n.get("tool.cron.error.unsupported", part), notNumeric);
        }
    }

    private static int getNextAllowed(int current, Set<Integer> allowed) {
        for (int val : allowed) {
            if (val >= current) return val;
        }
        return getMin(allowed);
    }

    private static int getMin(Set<Integer> allowed) {
        return allowed.iterator().next();
    }
}
