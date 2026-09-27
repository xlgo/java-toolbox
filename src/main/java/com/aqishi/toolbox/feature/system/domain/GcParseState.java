package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次解析的共享状态：输出列表、计数、时间轴换算与收集器证据。统一日志与 JDK 8 两个处理器共用它，
 * 因为轮转后拼接的日志里两种格式可能交替出现。
 */
final class GcParseState {

    private static final int SAMPLE_LIMIT = 50;
    private static final int SAMPLE_TEXT_LIMIT = 300;
    /** uptime 回退超过这个秒数才算 JVM 重启；轮转边界的时间戳可能有毫秒级抖动。 */
    private static final double RESTART_TOLERANCE = 1.0;
    private static final Pattern MAJOR_VERSION = Pattern.compile("^(?:1\\.(\\d+)|(\\d+))");

    /** 一行的装饰：日期、uptime、标签。 */
    static final class Stamp {
        String date;
        long epochMs = Long.MIN_VALUE;
        double uptime = Double.NaN;
        String level;
        String tags;
        /** 解析到的连续时间轴秒数。 */
        double time = Double.NaN;
        /** 剥掉装饰后的正文。 */
        String body;

        boolean hasTime() {
            return !Double.isNaN(uptime) || epochMs != Long.MIN_VALUE;
        }
    }

    final List<GcEvent> events = new ArrayList<>();
    final List<GcLog.Phase> phases = new ArrayList<>();
    final List<GcLog.Safepoint> safepoints = new ArrayList<>();
    final List<GcLog.Stall> stalls = new ArrayList<>();
    private final List<GcLog.Unparsed> unparsedSample = new ArrayList<>();
    private final Map<GcLog.Collector, Integer> hints = new EnumMap<>(GcLog.Collector.class);
    private GcLog.Collector declared;

    long totalLines;
    long ignoredLines;
    long unparsedLines;
    long unifiedLines;
    long legacyLines;
    String jvmVersion;
    long regionSizeKb = -1;
    boolean truncated;

    private boolean uptimeMode;
    private boolean modeChosen;
    private long firstEpochMs = Long.MIN_VALUE;
    private double lastRawUptime = Double.NaN;
    private double offset;
    private double firstTime = Double.NaN;
    private double lastTime = Double.NaN;
    private int restarts;

    // ------------------------------------------------------------ 时间轴

    /**
     * 把装饰换算到连续时间轴。优先用 uptime（精度高、不受时钟回拨影响）；uptime 明显回退说明拼接了
     * 另一次 JVM 运行的日志，把新一段接在上一段末尾，时间轴保持单调。
     */
    void resolve(Stamp stamp) {
        if (!stamp.hasTime()) {
            stamp.time = Double.NaN;
            return;
        }
        if (!modeChosen) {
            modeChosen = true;
            uptimeMode = !Double.isNaN(stamp.uptime);
        }
        double time;
        if (uptimeMode) {
            if (Double.isNaN(stamp.uptime)) {
                stamp.time = Double.NaN;
                return;
            }
            if (!Double.isNaN(lastRawUptime) && stamp.uptime < lastRawUptime - RESTART_TOLERANCE) {
                restarts++;
                offset = lastTime - stamp.uptime;
            }
            lastRawUptime = stamp.uptime;
            time = stamp.uptime + offset;
        } else {
            if (stamp.epochMs == Long.MIN_VALUE) {
                stamp.time = Double.NaN;
                return;
            }
            if (firstEpochMs == Long.MIN_VALUE) {
                firstEpochMs = stamp.epochMs;
            }
            time = (stamp.epochMs - firstEpochMs) / 1000.0;
        }
        stamp.time = time;
        if (Double.isNaN(firstTime) || time < firstTime) {
            firstTime = time;
        }
        if (Double.isNaN(lastTime) || time > lastTime) {
            lastTime = time;
        }
    }

    int restarts() {
        return restarts;
    }

    // ------------------------------------------------------------ 输出

    GcEvent newEvent(Stamp stamp, long line) {
        GcEvent event = new GcEvent();
        event.time = stamp.time;
        event.uptime = stamp.uptime;
        event.date = stamp.date;
        event.line = line;
        event.segment = restarts;
        return event;
    }

    void addEvent(GcEvent event) {
        events.add(event);
    }

    void addPhase(long gcId, double time, String name, double ms) {
        if (!Double.isNaN(ms) && name != null && !name.isEmpty()) {
            phases.add(new GcLog.Phase(gcId, time, intern(name), ms));
        }
    }

    /** 类型、原因、阶段名在几十万个事件里反复出现：共享同一个实例，内存占用能省下一大截。 */
    private static final int INTERN_LIMIT = 10_000;
    private final Map<String, String> strings = new java.util.HashMap<>();

    String intern(String text) {
        if (text == null) {
            return null;
        }
        String existing = strings.get(text);
        if (existing != null) {
            return existing;
        }
        if (strings.size() < INTERN_LIMIT) {
            strings.put(text, text);
        }
        return text;
    }

    void ignored(long lines) {
        ignoredLines += lines;
    }

    void unparsed(long lineNumber, String text) {
        unparsedLines++;
        if (unparsedSample.size() < SAMPLE_LIMIT) {
            unparsedSample.add(new GcLog.Unparsed(lineNumber, GcText.clip(text, SAMPLE_TEXT_LIMIT)));
        }
    }

    // ------------------------------------------------------------ 安全点

    private static final Pattern SAFEPOINT_OP = Pattern.compile("^Safepoint \"([^\"]*)\"");
    private static final Pattern SAFEPOINT_PART = Pattern.compile(
            "(Reaching safepoint|At safepoint|Total):\\s*(\\d+)\\s*ns");
    private static final Pattern THREADS_STOPPED = Pattern.compile(
            "Total time for which application threads were stopped:\\s*(" + GcText.NUM + ") seconds"
                    + "(?:,\\s*Stopping threads took:\\s*(" + GcText.NUM + ") seconds)?");

    /**
     * JDK 17+ 的 {@code Safepoint "op", ... Reaching safepoint: n ns, ... Total: n ns}，或 JDK 8-16 的
     * {@code Total time for which application threads were stopped: s seconds, Stopping threads took: s seconds}。
     *
     * @return 是否识别为安全点行
     */
    boolean trySafepoint(String body, double time) {
        if (body.startsWith("Safepoint \"")) {
            Matcher op = SAFEPOINT_OP.matcher(body);
            if (!op.find()) {
                return false;
            }
            double reach = Double.NaN;
            double at = Double.NaN;
            double total = Double.NaN;
            Matcher part = SAFEPOINT_PART.matcher(body);
            while (part.find()) {
                double ms = Long.parseLong(part.group(2)) / 1e6;
                switch (part.group(1)) {
                    case "Reaching safepoint" -> reach = ms;
                    case "At safepoint" -> at = ms;
                    default -> total = ms;
                }
            }
            if (Double.isNaN(total)) {
                return false;
            }
            safepoints.add(new GcLog.Safepoint(time, op.group(1), reach, at, total));
            return true;
        }
        if (!body.startsWith("Total time for which")) {
            return false;
        }
        Matcher stopped = THREADS_STOPPED.matcher(body);
        if (stopped.find()) {
            double total = GcText.num(stopped.group(1)) * 1000;
            double reach = stopped.group(2) == null ? Double.NaN : GcText.num(stopped.group(2)) * 1000;
            safepoints.add(new GcLog.Safepoint(time, null, reach, Double.NaN, total));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------ 收集器与版本

    void declare(GcLog.Collector collector) {
        if (declared == null || declared == GcLog.Collector.ZGC && collector == GcLog.Collector.ZGC_GENERATIONAL) {
            declared = collector;
        }
    }

    void hint(GcLog.Collector collector) {
        hints.merge(collector, 1, Integer::sum);
    }

    private GcLog.Collector collector() {
        int generational = hints.getOrDefault(GcLog.Collector.ZGC_GENERATIONAL, 0);
        if (declared != null) {
            return declared == GcLog.Collector.ZGC && generational > 0 ? GcLog.Collector.ZGC_GENERATIONAL : declared;
        }
        if (generational > 0) {
            return GcLog.Collector.ZGC_GENERATIONAL;
        }
        GcLog.Collector best = GcLog.Collector.UNKNOWN;
        int bestCount = 0;
        for (Map.Entry<GcLog.Collector, Integer> entry : hints.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private GcLog.Family family() {
        if (jvmVersion != null) {
            Matcher matcher = MAJOR_VERSION.matcher(jvmVersion);
            if (matcher.find()) {
                int major = Integer.parseInt(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
                return major <= 8 ? GcLog.Family.JDK8_OR_EARLIER : GcLog.Family.JDK9_PLUS;
            }
        }
        if (unifiedLines == 0 && legacyLines == 0) {
            return GcLog.Family.UNKNOWN;
        }
        return unifiedLines >= legacyLines ? GcLog.Family.JDK9_PLUS : GcLog.Family.JDK8_OR_EARLIER;
    }

    // ------------------------------------------------------------ 收尾

    GcLog finish() {
        // 个别行缺时间戳时沿用前一个事件的时间，再按时间稳定排序：并发周期快照在周期结束才落地，
        // 比它之后开始的停顿更晚写出。
        double previous = Double.NaN;
        boolean anyTime = false;
        for (GcEvent event : events) {
            if (Double.isNaN(event.time)) {
                event.time = previous;
            } else {
                previous = event.time;
                anyTime = true;
            }
        }
        if (anyTime) {
            double first = Double.NaN;
            for (GcEvent event : events) {
                if (!Double.isNaN(event.time)) {
                    first = event.time;
                    break;
                }
            }
            for (GcEvent event : events) {
                if (Double.isNaN(event.time)) {
                    event.time = first;
                }
            }
            events.sort(Comparator.comparingDouble(GcEvent::time));
            phases.sort(Comparator.comparingDouble(phase -> Double.isNaN(phase.time()) ? 0 : phase.time()));
        }
        return new GcLog(collector(), family(), jvmVersion, events, phases, safepoints, stalls, firstTime, lastTime,
                restarts, regionSizeKb, totalLines, ignoredLines, unparsedLines, unparsedSample, truncated);
    }
}
