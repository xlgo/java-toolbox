package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcAnalyzerTest {

    private static final double EPS = 1e-6;
    private static final long MB = 1024;

    static GcLog log(GcLog.Collector collector, List<GcEvent> events, double first, double last) {
        return log(collector, events, first, last, List.of(), List.of());
    }

    static GcLog log(GcLog.Collector collector, List<GcEvent> events, double first, double last,
                     List<GcLog.Safepoint> safepoints, List<GcLog.Stall> stalls) {
        return new GcLog(collector, GcLog.Family.JDK9_PLUS, null, events, List.of(), safepoints, stalls, first, last,
                0, -1, 0, 0, 0, List.of(), false);
    }

    static GcEvent young(double time, double ms, long beforeMb, long afterMb) {
        return GcEvent.pause(time, "Pause Young", GcEvent.Category.YOUNG, "Allocation Failure", ms,
                beforeMb * MB, afterMb * MB, 1024 * MB);
    }

    @Test
    void percentilesUseNearestRank() {
        double[] values = new double[1000];
        for (int i = 0; i < values.length; i++) {
            values[values.length - 1 - i] = i + 1;
        }
        GcReport.PauseStats stats = GcReport.PauseStats.of(values);
        assertEquals(1000, stats.count());
        assertEquals(500500, stats.totalMs(), EPS);
        assertEquals(500.5, stats.avgMs(), EPS);
        assertEquals(1, stats.minMs(), EPS);
        assertEquals(1000, stats.maxMs(), EPS);
        assertEquals(500, stats.p50(), EPS);
        assertEquals(900, stats.p90(), EPS);
        assertEquals(950, stats.p95(), EPS);
        assertEquals(990, stats.p99(), EPS);
        assertEquals(999, stats.p999(), EPS);

        GcReport.PauseStats single = GcReport.PauseStats.of(new double[]{7, Double.NaN});
        assertEquals(1, single.count());
        assertEquals(7, single.p999(), EPS);
        assertEquals(0, GcReport.PauseStats.of(new double[0]).count());
    }

    @Test
    void computesThroughputFrequencyAndRates() {
        List<GcEvent> events = List.of(young(0, 500, 100, 20), young(10, 500, 120, 30), young(20, 1000, 130, 40));
        GcReport report = new GcAnalyzer().analyze(log(GcLog.Collector.PARALLEL, events, 0, 100));

        assertEquals(100, report.spanSeconds(), EPS);
        assertEquals(2000, report.pauses().totalMs(), EPS);
        // 1 - 2 s / 100 s
        assertEquals(0.98, report.throughput(), EPS);
        assertEquals(1.8, report.pausesPerMinute(), EPS);
        // (120-20) + (130-30) = 200 MB over 20 s
        assertEquals(200.0 * MB / 20, report.allocationRateKbPerSec(), EPS);
        assertEquals(1000, report.longestPauses().get(0).durationMs(), EPS);
        assertEquals(1, report.byType().size());
        assertEquals(3, report.causes().get(0).collections());
        assertEquals(0, report.fullGcCount());
    }

    @Test
    void computesPromotionRateFromYoungGenerationDeltas() {
        GcEvent first = young(0, 5, 100, 30);
        first.youngBeforeKb = 90 * MB;
        first.youngAfterKb = 10 * MB;
        GcEvent second = young(10, 5, 110, 45);
        second.youngBeforeKb = 80 * MB;
        second.youngAfterKb = 10 * MB;
        // 老年代：第一次 10 -> 20（+10 MB），第二次 30 -> 35（+5 MB），共 15 MB / 10 s。
        GcReport report = new GcAnalyzer().analyze(log(GcLog.Collector.PARALLEL, List.of(first, second), 0, 10));
        assertEquals(15.0 * MB / 10, report.promotionRateKbPerSec(), EPS);
    }

    @Test
    void detectsRisingHeapFloorFromFullGcs() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            double time = i * 1800.0;
            events.add(young(time - 60, 5, 900, 700));
            events.add(GcEvent.pause(time, "Pause Full", GcEvent.Category.FULL, "Allocation Failure", 300,
                    900 * MB, (200 + i * 10) * MB, 1024 * MB));
        }
        GcReport.HeapTrend trend = GcAnalyzer.heapTrend(log(GcLog.Collector.PARALLEL, events, -60, 36000));
        assertNotNull(trend);
        assertEquals(GcReport.TrendBasis.FLOOR_EVENTS, trend.basis());
        assertEquals(21, trend.points());
        // 每半小时 +10 MB = 20 MB/h
        assertEquals(20.0 * MB, trend.slopeKbPerHour(), 1e-3);
        assertEquals(1.0, trend.r2(), 1e-9);
        assertEquals(200.0 * MB / (1024 * MB) * 100, trend.growthPercent(), 1e-6);
        assertTrue(trend.rising());
    }

    @Test
    void flatOrNoisyFloorIsNotALeak() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            long after = 300 + (i % 2 == 0 ? 15 : -15);
            events.add(GcEvent.pause(i * 1800.0, "Pause Full", GcEvent.Category.FULL, "Allocation Failure", 300,
                    900 * MB, after * MB, 1024 * MB));
        }
        GcReport.HeapTrend trend = GcAnalyzer.heapTrend(log(GcLog.Collector.PARALLEL, events, 0, 36000));
        assertNotNull(trend);
        assertFalse(trend.rising());

        // 上涨但时间跨度太短（启动预热）也不算。
        List<GcEvent> warmup = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            warmup.add(GcEvent.pause(i * 10.0, "Pause Full", GcEvent.Category.FULL, null, 50, 900 * MB,
                    (100 + i * 50) * MB, 1024 * MB));
        }
        assertFalse(GcAnalyzer.heapTrend(log(GcLog.Collector.PARALLEL, warmup, 0, 90)).rising());
    }

    @Test
    void fallsBackToWindowMinimaWithoutFloorEvents() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            double time = i * 60.0;
            // 锯齿：每 10 次年轻代回收底线整体抬高 20 MB。
            long floor = 100 + (i / 10) * 20L;
            events.add(young(time, 5, floor + 300, floor + (i % 10) * 5));
        }
        GcReport.HeapTrend trend = GcAnalyzer.heapTrend(log(GcLog.Collector.G1, events, 0, 12000));
        assertNotNull(trend);
        assertEquals(GcReport.TrendBasis.WINDOW_MINIMA, trend.basis());
        assertEquals(10, trend.points());
        assertTrue(trend.slopeKbPerHour() > 0);
        assertTrue(trend.rising());

        assertNull(GcAnalyzer.heapTrend(log(GcLog.Collector.G1, List.of(young(0, 1, 10, 5)), 0, 1)));
    }

    @Test
    void analyzesG1FixtureEndToEnd() throws IOException {
        GcReport report = new GcAnalyzer().analyze(GcLogParserTest.parse("g1-jdk17.txt"));
        assertEquals(11, report.pauses().count());
        assertEquals(300, report.pauses().maxMs(), EPS);
        assertEquals(2, report.fullGcCount());
        assertEquals("G1 Evacuation Pause", report.causes().get(0).cause());
        assertEquals(4, report.causes().get(0).collections());
        assertTrue(report.throughput() > 0.97 && report.throughput() < 0.98, () -> "" + report.throughput());
        assertTrue(report.phases().stream().anyMatch(p -> p.kind() == GcReport.PhaseKind.PAUSE_STEP
                && p.name().equals("Evacuate Collection Set")));
        List<GcAdvice.Code> codes = report.advice().stream().map(GcAdvice::code).collect(Collectors.toList());
        assertTrue(codes.containsAll(List.of(GcAdvice.Code.EVACUATION_FAILURE, GcAdvice.Code.EXPLICIT_GC,
                GcAdvice.Code.HUMONGOUS_ALLOCATION, GcAdvice.Code.METASPACE_GC, GcAdvice.Code.FREQUENT_FULL_GC,
                GcAdvice.Code.PAUSE_ABOVE_TARGET)), codes::toString);
        assertFalse(codes.contains(GcAdvice.Code.LOW_THROUGHPUT));
        assertEquals(GcAdvice.Severity.CRITICAL, report.advice().get(0).severity());
        GcAdvice humongous = report.advice().stream()
                .filter(a -> a.code() == GcAdvice.Code.HUMONGOUS_ALLOCATION).findFirst().orElseThrow();
        assertEquals(List.of("2", "1M"), humongous.params());
        // 只把 pause 目标调高，无需重新解析。
        GcReport relaxed = new GcAnalyzer().analyze(report.log(), 500);
        assertFalse(relaxed.advice().stream().anyMatch(a -> a.code() == GcAdvice.Code.PAUSE_ABOVE_TARGET));
    }

    @Test
    void zgcCausesCountCyclesNotPauses() throws IOException {
        GcReport report = new GcAnalyzer().analyze(GcLogParserTest.parse("zgc-jdk17.txt"));
        GcReport.CauseStats warmup = report.causes().stream().filter(c -> "Warmup".equals(c.cause()))
                .findFirst().orElseThrow();
        assertEquals(1, warmup.collections());
        assertNotNull(report.stalls());
        assertEquals(16.0, report.stalls().totalMs(), EPS);
        assertEquals(GcAdvice.Code.ALLOCATION_STALL, report.advice().get(0).code());
    }
}
