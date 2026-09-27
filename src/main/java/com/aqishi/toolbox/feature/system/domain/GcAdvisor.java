package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 基于规则的调优建议。每条规则只看报告里的统计值，彼此独立，便于逐条测试。
 */
final class GcAdvisor {

    /** 吞吐量低于这个值给出警告，低于 {@link #THROUGHPUT_CRITICAL} 升级为严重。 */
    static final double THROUGHPUT_TARGET = 0.95;
    static final double THROUGHPUT_CRITICAL = 0.90;
    /** 到达安全点超过这个毫秒数说明有线程长时间跑在没有安全点轮询的代码里（大循环、JNI、页错误）。 */
    static final double TIME_TO_SAFEPOINT_LIMIT_MS = 50;
    /** Parallel / Serial 的 Full GC 是常规操作，超过这个频率才算频繁。 */
    static final double FULL_GC_PER_HOUR_WARNING = 6;
    static final double FULL_GC_PER_HOUR_CRITICAL = 60;
    static final int FULL_GC_MIN_COUNT = 3;

    private GcAdvisor() {
    }

    static List<GcAdvice> advise(GcReport report) {
        GcLog log = report.log();
        List<GcAdvice> advice = new ArrayList<>();

        count(advice, log, GcAdvice.Code.EVACUATION_FAILURE, GcAdvice.Severity.CRITICAL,
                event -> event.has(GcEvent.Flag.EVACUATION_FAILURE) || event.has(GcEvent.Flag.TO_SPACE_EXHAUSTED));
        count(advice, log, GcAdvice.Code.PROMOTION_FAILED, GcAdvice.Severity.CRITICAL,
                event -> event.has(GcEvent.Flag.PROMOTION_FAILED));
        count(advice, log, GcAdvice.Code.CONCURRENT_MODE_FAILURE, GcAdvice.Severity.CRITICAL,
                event -> event.has(GcEvent.Flag.CONCURRENT_MODE_FAILURE));

        GcReport.StallStats stalls = report.stalls();
        if (stalls != null && stalls.count() > 0) {
            advice.add(new GcAdvice(GcAdvice.Code.ALLOCATION_STALL, GcAdvice.Severity.CRITICAL,
                    List.of(String.valueOf(stalls.count()), ms(stalls.totalMs()), ms(stalls.maxMs()))));
        }

        fullGc(advice, report);
        count(advice, log, GcAdvice.Code.DEGENERATED_GC, GcAdvice.Severity.WARNING,
                event -> event.category() == GcEvent.Category.DEGENERATED);
        count(advice, log, GcAdvice.Code.EXPLICIT_GC, GcAdvice.Severity.WARNING,
                event -> event.has(GcEvent.Flag.EXPLICIT));
        int humongous = GcAnalyzer.countCollections(log, event -> event.has(GcEvent.Flag.HUMONGOUS));
        if (humongous > 0) {
            String region = log.regionSizeKb() > 0 ? size(log.regionSizeKb()) : "-";
            advice.add(new GcAdvice(GcAdvice.Code.HUMONGOUS_ALLOCATION, GcAdvice.Severity.WARNING,
                    List.of(String.valueOf(humongous), region)));
        }
        count(advice, log, GcAdvice.Code.METASPACE_GC, GcAdvice.Severity.WARNING,
                event -> event.has(GcEvent.Flag.METADATA));

        pauseTarget(advice, report);
        if (Double.isFinite(report.throughput()) && report.throughput() < THROUGHPUT_TARGET) {
            advice.add(new GcAdvice(GcAdvice.Code.LOW_THROUGHPUT,
                    report.throughput() < THROUGHPUT_CRITICAL ? GcAdvice.Severity.CRITICAL : GcAdvice.Severity.WARNING,
                    List.of(String.format(Locale.ROOT, "%.2f", report.throughput() * 100),
                            String.format(Locale.ROOT, "%.0f", THROUGHPUT_TARGET * 100))));
        }
        GcReport.HeapTrend trend = report.heapTrend();
        if (trend != null && trend.rising()) {
            advice.add(new GcAdvice(GcAdvice.Code.HEAP_FLOOR_RISING, GcAdvice.Severity.WARNING,
                    List.of(String.format(Locale.ROOT, "%.1f", trend.slopeKbPerHour() / 1024),
                            String.format(Locale.ROOT, "%.1f", trend.growthPercent()),
                            String.valueOf(trend.points()))));
        }
        safepoints(advice, report);

        advice.sort(Comparator.comparing((GcAdvice item) -> item.severity()).reversed()
                .thenComparing(GcAdvice::code));
        return advice;
    }

    private static void count(List<GcAdvice> out, GcLog log, GcAdvice.Code code, GcAdvice.Severity severity,
                              java.util.function.Predicate<GcEvent> filter) {
        int count = GcAnalyzer.countCollections(log, filter);
        if (count > 0) {
            out.add(new GcAdvice(code, severity, List.of(String.valueOf(count))));
        }
    }

    /**
     * 并发收集器（G1/CMS/ZGC/Shenandoah）的 Full GC 意味着并发回收没跟上，出现即提示；
     * Parallel/Serial 靠 Full GC 回收老年代，只有频率过高才提示。显式 System.gc() 另有规则，这里不计。
     */
    private static void fullGc(List<GcAdvice> out, GcReport report) {
        int count = 0;
        for (GcEvent event : report.log().events()) {
            if (event.isPause() && event.category() == GcEvent.Category.FULL && !event.has(GcEvent.Flag.EXPLICIT)) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        double span = report.spanSeconds();
        double perHour = span > 0 ? count / (span / 3600) : Double.NaN;
        String perHourText = Double.isFinite(perHour) ? String.format(Locale.ROOT, "%.1f", perHour) : "-";
        GcAdvice.Severity severity;
        if (report.log().collector().concurrent()) {
            severity = count >= FULL_GC_MIN_COUNT ? GcAdvice.Severity.CRITICAL : GcAdvice.Severity.WARNING;
        } else {
            if (count < FULL_GC_MIN_COUNT || !(perHour >= FULL_GC_PER_HOUR_WARNING)) {
                return;
            }
            severity = perHour >= FULL_GC_PER_HOUR_CRITICAL ? GcAdvice.Severity.CRITICAL : GcAdvice.Severity.WARNING;
        }
        out.add(new GcAdvice(GcAdvice.Code.FREQUENT_FULL_GC, severity, List.of(String.valueOf(count), perHourText)));
    }

    private static void pauseTarget(List<GcAdvice> out, GcReport report) {
        GcReport.PauseStats stats = report.pauses();
        double target = report.pauseTargetMs();
        if (stats.count() == 0 || !(stats.maxMs() > target)) {
            return;
        }
        int above = 0;
        for (GcEvent event : report.log().events()) {
            if (event.isPause() && event.durationMs() > target) {
                above++;
            }
        }
        GcAdvice.Severity severity = stats.p99() > target ? GcAdvice.Severity.CRITICAL : GcAdvice.Severity.WARNING;
        out.add(new GcAdvice(GcAdvice.Code.PAUSE_ABOVE_TARGET, severity,
                List.of(ms(stats.maxMs()), ms(target), String.valueOf(above), ms(stats.p99()))));
    }

    private static void safepoints(List<GcAdvice> out, GcReport report) {
        GcReport.SafepointStats stats = report.safepoints();
        if (stats == null || !(stats.reach().maxMs() > TIME_TO_SAFEPOINT_LIMIT_MS)) {
            return;
        }
        int above = 0;
        for (GcLog.Safepoint safepoint : report.log().safepoints()) {
            if (safepoint.reachMs() > TIME_TO_SAFEPOINT_LIMIT_MS) {
                above++;
            }
        }
        out.add(new GcAdvice(GcAdvice.Code.LONG_TIME_TO_SAFEPOINT, GcAdvice.Severity.WARNING,
                List.of(ms(stats.reach().maxMs()), stats.maxReachOperation() == null ? "-" : stats.maxReachOperation(),
                        String.valueOf(above))));
    }

    private static String ms(double value) {
        return String.format(Locale.ROOT, value >= 100 ? "%.0f" : value >= 1 ? "%.1f" : "%.3f", value);
    }

    private static String size(long kb) {
        if (kb >= 1024 && kb % 1024 == 0) {
            return (kb / 1024) + "M";
        }
        return kb + "K";
    }
}
