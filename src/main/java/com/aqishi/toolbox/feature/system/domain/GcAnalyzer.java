package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 从解析结果计算停顿统计、吞吐量、分配/晋升速率、堆底线趋势，并交给 {@link GcAdvisor} 生成建议。
 *
 * <p>无状态、线程安全；改停顿目标时只需重新分析，不必重新解析。</p>
 */
public final class GcAnalyzer {

    public static final double DEFAULT_PAUSE_TARGET_MS = 200;
    static final int LONGEST_LIMIT = 20;

    /** 趋势判定：至少这么多点、这么长的时间跨度、拟合优度与增幅都达标才算「持续上涨」。 */
    static final int TREND_MIN_POINTS = 5;
    static final double TREND_MIN_SPAN_SECONDS = 600;
    static final double TREND_MIN_R2 = 0.6;
    static final double TREND_MIN_GROWTH_PERCENT = 10;
    private static final int TREND_WINDOWS = 10;

    public GcReport analyze(GcLog log) {
        return analyze(log, DEFAULT_PAUSE_TARGET_MS);
    }

    public GcReport analyze(GcLog log, double pauseTargetMs) {
        List<GcEvent> pauses = new ArrayList<>();
        for (GcEvent event : log.events()) {
            if (event.isPause() && Double.isFinite(event.durationMs())) {
                pauses.add(event);
            }
        }
        double span = log.spanSeconds();
        GcReport.PauseStats overall = GcReport.PauseStats.of(durations(pauses));
        double throughput = Double.NaN;
        double perMinute = Double.NaN;
        if (span > 0) {
            throughput = Math.max(0, Math.min(1, 1 - overall.totalMs() / 1000 / span));
            perMinute = overall.count() / (span / 60);
        }
        int fullCount = 0;
        for (GcEvent pause : pauses) {
            if (pause.category() == GcEvent.Category.FULL) {
                fullCount++;
            }
        }
        List<GcEvent> longest = new ArrayList<>(pauses);
        longest.sort(Comparator.comparingDouble(GcEvent::durationMs).reversed());
        if (longest.size() > LONGEST_LIMIT) {
            longest = new ArrayList<>(longest.subList(0, LONGEST_LIMIT));
        }
        GcReport draft = new GcReport(log, pauseTargetMs, span, overall, typeStats(pauses), causeStats(log),
                phaseStats(log, pauses), throughput, perMinute, allocationRate(log), promotionRate(pauses),
                fullCount, longest, heapTrend(log), safepointStats(log), stallStats(log), List.of());
        return draft.withAdvice(GcAdvisor.advise(draft));
    }

    private static double[] durations(List<GcEvent> events) {
        double[] values = new double[events.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = events.get(i).durationMs();
        }
        return values;
    }

    private static List<GcReport.TypeStats> typeStats(List<GcEvent> pauses) {
        Map<String, List<GcEvent>> groups = new LinkedHashMap<>();
        for (GcEvent pause : pauses) {
            groups.computeIfAbsent(pause.type() == null ? "?" : pause.type(), key -> new ArrayList<>()).add(pause);
        }
        List<GcReport.TypeStats> result = new ArrayList<>();
        groups.forEach((type, events) -> result.add(
                new GcReport.TypeStats(type, events.get(0).category(), GcReport.PauseStats.of(durations(events)))));
        result.sort(Comparator.comparingDouble((GcReport.TypeStats stats) -> stats.stats().totalMs()).reversed());
        return result;
    }

    /** 一个 GC 编号算一次回收：ZGC 一个周期有三次停顿，不能按停顿数算三次原因。 */
    private static List<GcReport.CauseStats> causeStats(GcLog log) {
        final class Acc {
            final Set<Long> ids = new HashSet<>();
            int collections;
            int full;
            double total;
            double max = Double.NaN;
        }
        Map<String, Acc> groups = new LinkedHashMap<>();
        for (GcEvent event : log.events()) {
            Acc acc = groups.computeIfAbsent(event.cause() == null ? "" : event.cause(), key -> new Acc());
            if (event.gcId() < 0 || acc.ids.add(collectionKey(event))) {
                acc.collections++;
            }
            if (event.isPause() && Double.isFinite(event.durationMs())) {
                acc.total += event.durationMs();
                acc.max = Double.isNaN(acc.max) ? event.durationMs() : Math.max(acc.max, event.durationMs());
                if (event.category() == GcEvent.Category.FULL) {
                    acc.full++;
                }
            }
        }
        List<GcReport.CauseStats> result = new ArrayList<>();
        groups.forEach((cause, acc) -> result.add(new GcReport.CauseStats(cause.isEmpty() ? null : cause,
                acc.collections, acc.full, acc.total, acc.max)));
        result.sort(Comparator.comparingInt(GcReport.CauseStats::collections).reversed()
                .thenComparing(Comparator.comparingDouble(GcReport.CauseStats::totalPauseMs).reversed()));
        return result;
    }

    private static List<GcReport.PhaseStats> phaseStats(GcLog log, List<GcEvent> pauses) {
        Map<String, double[]> concurrent = new LinkedHashMap<>();
        for (GcLog.Phase phase : log.phases()) {
            accumulate(concurrent, phase.name(), phase.durationMs());
        }
        Map<String, double[]> steps = new LinkedHashMap<>();
        for (GcEvent pause : pauses) {
            pause.subPhases().forEach((name, ms) -> accumulate(steps, name, ms));
        }
        List<GcReport.PhaseStats> result = new ArrayList<>();
        addPhases(result, concurrent, GcReport.PhaseKind.CONCURRENT);
        addPhases(result, steps, GcReport.PhaseKind.PAUSE_STEP);
        return result;
    }

    /** {count, total, max} */
    private static void accumulate(Map<String, double[]> map, String name, double ms) {
        if (!Double.isFinite(ms)) {
            return;
        }
        double[] acc = map.computeIfAbsent(name, key -> new double[]{0, 0, Double.NEGATIVE_INFINITY});
        acc[0]++;
        acc[1] += ms;
        acc[2] = Math.max(acc[2], ms);
    }

    private static void addPhases(List<GcReport.PhaseStats> out, Map<String, double[]> map, GcReport.PhaseKind kind) {
        List<GcReport.PhaseStats> part = new ArrayList<>();
        map.forEach((name, acc) -> part.add(new GcReport.PhaseStats(name, kind, (int) acc[0], acc[1],
                acc[1] / acc[0], acc[2])));
        part.sort(Comparator.comparingDouble(GcReport.PhaseStats::totalMs).reversed());
        out.addAll(part);
    }

    /** 相邻两次回收之间堆的增量就是这段时间的分配量（回收前 - 上一次回收后）。 */
    static double allocationRate(GcLog log) {
        GcEvent previous = null;
        GcEvent first = null;
        double allocated = 0;
        for (GcEvent event : log.events()) {
            if (!event.hasHeap() || !Double.isFinite(event.time())) {
                continue;
            }
            if (previous != null) {
                long delta = event.heapBeforeKb() - previous.heapAfterKb();
                if (delta > 0) {
                    allocated += delta;
                }
            } else {
                first = event;
            }
            previous = event;
        }
        if (first == null || previous == first) {
            return Double.NaN;
        }
        double seconds = previous.time() - first.time();
        return seconds > 0 ? allocated / seconds : Double.NaN;
    }

    /** 年轻代回收时老年代（整堆减年轻代）的增量就是晋升量。 */
    static double promotionRate(List<GcEvent> pauses) {
        GcEvent first = null;
        GcEvent last = null;
        double promoted = 0;
        for (GcEvent event : pauses) {
            boolean young = event.category() == GcEvent.Category.YOUNG || event.category() == GcEvent.Category.MIXED;
            if (!young || !event.hasHeap() || event.youngBeforeKb() < 0 || event.youngAfterKb() < 0
                    || !Double.isFinite(event.time())) {
                continue;
            }
            long oldBefore = event.heapBeforeKb() - event.youngBeforeKb();
            long oldAfter = event.heapAfterKb() - event.youngAfterKb();
            if (oldAfter > oldBefore) {
                promoted += oldAfter - oldBefore;
            }
            if (first == null) {
                first = event;
            }
            last = event;
        }
        if (first == null || last == first) {
            return Double.NaN;
        }
        double seconds = last.time() - first.time();
        return seconds > 0 ? promoted / seconds : Double.NaN;
    }

    static GcReport.HeapTrend heapTrend(GcLog log) {
        List<double[]> floor = new ArrayList<>();
        List<double[]> all = new ArrayList<>();
        long reference = -1;
        for (GcEvent event : log.events()) {
            if (!event.hasHeap() || !Double.isFinite(event.time())) {
                continue;
            }
            reference = Math.max(reference, Math.max(event.heapCommittedKb(), event.heapAfterKb()));
            double[] point = {event.time(), event.heapAfterKb()};
            all.add(point);
            if (event.category() == GcEvent.Category.FULL || event.category() == GcEvent.Category.MIXED
                    || event.isFullHeapCycle()) {
                floor.add(point);
            }
        }
        GcReport.TrendBasis basis = GcReport.TrendBasis.FLOOR_EVENTS;
        List<double[]> points = floor;
        if (floor.size() < TREND_MIN_POINTS) {
            basis = GcReport.TrendBasis.WINDOW_MINIMA;
            points = windowMinima(all);
        }
        if (points.size() < TREND_MIN_POINTS) {
            return null;
        }
        return regression(points, basis, reference);
    }

    private static List<double[]> windowMinima(List<double[]> all) {
        if (all.size() < TREND_MIN_POINTS) {
            return List.of();
        }
        double start = all.get(0)[0];
        double end = all.get(all.size() - 1)[0];
        if (end <= start) {
            return List.of();
        }
        double[][] minima = new double[TREND_WINDOWS][];
        for (double[] point : all) {
            int window = (int) Math.min(TREND_WINDOWS - 1, (point[0] - start) / (end - start) * TREND_WINDOWS);
            if (minima[window] == null || point[1] < minima[window][1]) {
                minima[window] = point;
            }
        }
        List<double[]> result = new ArrayList<>();
        for (double[] point : minima) {
            if (point != null) {
                result.add(point);
            }
        }
        return result;
    }

    /** 最小二乘拟合 y = a + b·x，x 为秒、y 为 KB。 */
    static GcReport.HeapTrend regression(List<double[]> points, GcReport.TrendBasis basis, long referenceKb) {
        int n = points.size();
        double sumX = 0;
        double sumY = 0;
        for (double[] point : points) {
            sumX += point[0];
            sumY += point[1];
        }
        double meanX = sumX / n;
        double meanY = sumY / n;
        double sxx = 0;
        double sxy = 0;
        double syy = 0;
        for (double[] point : points) {
            double dx = point[0] - meanX;
            double dy = point[1] - meanY;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        if (sxx == 0) {
            return null;
        }
        double slope = sxy / sxx;
        double intercept = meanY - slope * meanX;
        double r2 = syy == 0 ? 0 : (sxy * sxy) / (sxx * syy);
        double startTime = points.get(0)[0];
        double endTime = points.get(n - 1)[0];
        double startKb = intercept + slope * startTime;
        double endKb = intercept + slope * endTime;
        double reference = referenceKb > 0 ? referenceKb : Math.max(1, meanY);
        double growth = (endKb - startKb) / reference * 100;
        boolean rising = slope > 0 && r2 >= TREND_MIN_R2 && endTime - startTime >= TREND_MIN_SPAN_SECONDS
                && growth >= TREND_MIN_GROWTH_PERCENT;
        return new GcReport.HeapTrend(basis, n, slope * 3600, r2, startTime, endTime, startKb, endKb, growth, rising);
    }

    private static GcReport.SafepointStats safepointStats(GcLog log) {
        if (log.safepoints().isEmpty()) {
            return null;
        }
        double total = 0;
        double[] reach = new double[log.safepoints().size()];
        double maxReach = Double.NEGATIVE_INFINITY;
        String maxOperation = null;
        Map<String, double[]> operations = new LinkedHashMap<>();
        for (int i = 0; i < reach.length; i++) {
            GcLog.Safepoint safepoint = log.safepoints().get(i);
            total += Double.isFinite(safepoint.totalMs()) ? safepoint.totalMs() : 0;
            reach[i] = safepoint.reachMs();
            if (Double.isFinite(safepoint.reachMs()) && safepoint.reachMs() > maxReach) {
                maxReach = safepoint.reachMs();
                maxOperation = safepoint.operation();
            }
            double[] acc = operations.computeIfAbsent(safepoint.operation() == null ? "" : safepoint.operation(),
                    key -> new double[]{0, 0, Double.NaN});
            acc[0]++;
            acc[1] += Double.isFinite(safepoint.totalMs()) ? safepoint.totalMs() : 0;
            if (Double.isFinite(safepoint.reachMs())) {
                acc[2] = Double.isNaN(acc[2]) ? safepoint.reachMs() : Math.max(acc[2], safepoint.reachMs());
            }
        }
        List<GcReport.OperationStats> ops = new ArrayList<>();
        operations.forEach((name, acc) -> ops.add(new GcReport.OperationStats(name.isEmpty() ? null : name,
                (int) acc[0], acc[1], acc[2])));
        ops.sort(Comparator.comparingDouble(GcReport.OperationStats::totalMs).reversed());
        return new GcReport.SafepointStats(reach.length, total, GcReport.PauseStats.of(reach), maxOperation, ops);
    }

    private static GcReport.StallStats stallStats(GcLog log) {
        if (log.stalls().isEmpty()) {
            return null;
        }
        double total = 0;
        double max = 0;
        for (GcLog.Stall stall : log.stalls()) {
            total += stall.durationMs();
            max = Math.max(max, stall.durationMs());
        }
        return new GcReport.StallStats(log.stalls().size(), total, max);
    }

    private static long collectionKey(GcEvent event) {
        return ((long) event.segment << 40) | event.gcId();
    }

    /** 满足条件的回收次数：同一 GC 编号只算一次，JDK 8 没有编号时逐事件计数。 */
    static int countCollections(GcLog log, Predicate<GcEvent> filter) {
        Set<Long> ids = new HashSet<>();
        int count = 0;
        for (GcEvent event : log.events()) {
            if (filter.test(event) && (event.gcId() < 0 || ids.add(collectionKey(event)))) {
                count++;
            }
        }
        return count;
    }
}
