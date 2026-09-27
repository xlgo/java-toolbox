package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.aqishi.toolbox.feature.system.domain.GcAnalyzerTest.log;
import static com.aqishi.toolbox.feature.system.domain.GcAnalyzerTest.young;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 每条建议规则：满足条件时触发，不满足时不触发。 */
class GcAdvisorTest {

    private static final long MB = 1024;

    private static GcReport analyze(GcLog log) {
        return new GcAnalyzer().analyze(log);
    }

    private static Optional<GcAdvice> find(GcReport report, GcAdvice.Code code) {
        return report.advice().stream().filter(advice -> advice.code() == code).findFirst();
    }

    /** 一份健康的基线：每 10 秒一次 5 ms 的年轻代回收，吞吐量 99.95%。 */
    private static List<GcEvent> healthy() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(young(i * 10.0, 5, 300, 100));
        }
        return events;
    }

    private static GcEvent flagged(double time, GcEvent.Flag flag) {
        GcEvent event = young(time, 5, 300, 100);
        event.addFlag(flag);
        return event;
    }

    @Test
    void healthyLogHasNoAdvice() {
        assertEquals(List.of(), analyze(log(GcLog.Collector.G1, healthy(), 0, 100)).advice());
    }

    @Test
    void failureFlagsProduceCriticalAdvice() {
        for (Object[] rule : new Object[][]{
                {GcEvent.Flag.EVACUATION_FAILURE, GcAdvice.Code.EVACUATION_FAILURE},
                {GcEvent.Flag.TO_SPACE_EXHAUSTED, GcAdvice.Code.EVACUATION_FAILURE},
                {GcEvent.Flag.PROMOTION_FAILED, GcAdvice.Code.PROMOTION_FAILED},
                {GcEvent.Flag.CONCURRENT_MODE_FAILURE, GcAdvice.Code.CONCURRENT_MODE_FAILURE}}) {
            List<GcEvent> events = healthy();
            events.add(flagged(95, (GcEvent.Flag) rule[0]));
            GcAdvice advice = find(analyze(log(GcLog.Collector.G1, events, 0, 100)), (GcAdvice.Code) rule[1])
                    .orElseThrow(() -> new AssertionError(rule[1]));
            assertEquals(GcAdvice.Severity.CRITICAL, advice.severity());
            assertEquals(List.of("1"), advice.params());
        }
        GcReport clean = analyze(log(GcLog.Collector.G1, healthy(), 0, 100));
        assertTrue(find(clean, GcAdvice.Code.EVACUATION_FAILURE).isEmpty());
        assertTrue(find(clean, GcAdvice.Code.PROMOTION_FAILED).isEmpty());
        assertTrue(find(clean, GcAdvice.Code.CONCURRENT_MODE_FAILURE).isEmpty());
    }

    @Test
    void causeBasedRules() {
        List<GcEvent> events = healthy();
        events.add(GcEvent.pause(91, "Pause Young (Normal)", GcEvent.Category.YOUNG, "G1 Humongous Allocation", 5,
                300 * MB, 100 * MB, 1024 * MB));
        events.add(GcEvent.pause(92, "Pause Young (Concurrent Start)", GcEvent.Category.YOUNG,
                "Metadata GC Threshold", 5, 300 * MB, 100 * MB, 1024 * MB));
        events.add(GcEvent.pause(93, "Pause Full", GcEvent.Category.FULL, "System.gc()", 50,
                300 * MB, 100 * MB, 1024 * MB));
        events.add(GcEvent.pause(94, "Pause Degenerated GC", GcEvent.Category.DEGENERATED, null, 20,
                300 * MB, 100 * MB, 1024 * MB));
        GcReport report = analyze(log(GcLog.Collector.G1, events, 0, 100));
        assertEquals(List.of("1", "-"), find(report, GcAdvice.Code.HUMONGOUS_ALLOCATION).orElseThrow().params());
        assertEquals(List.of("1"), find(report, GcAdvice.Code.METASPACE_GC).orElseThrow().params());
        assertEquals(List.of("1"), find(report, GcAdvice.Code.EXPLICIT_GC).orElseThrow().params());
        assertEquals(List.of("1"), find(report, GcAdvice.Code.DEGENERATED_GC).orElseThrow().params());
        // 显式 System.gc() 的 Full GC 不算「频繁 Full GC」。
        assertTrue(find(report, GcAdvice.Code.FREQUENT_FULL_GC).isEmpty());
    }

    @Test
    void fullGcOnConcurrentCollectorAlwaysReported() {
        List<GcEvent> events = healthy();
        events.add(GcEvent.pause(95, "Pause Full", GcEvent.Category.FULL, "G1 Compaction Pause", 100,
                1000 * MB, 300 * MB, 1024 * MB));
        GcAdvice advice = find(analyze(log(GcLog.Collector.G1, events, 0, 3600)), GcAdvice.Code.FREQUENT_FULL_GC)
                .orElseThrow();
        assertEquals(GcAdvice.Severity.WARNING, advice.severity());
        assertEquals(List.of("1", "1.0"), advice.params());

        // Parallel 偶尔 Full GC 属正常。
        assertTrue(find(analyze(log(GcLog.Collector.PARALLEL, events, 0, 3600)), GcAdvice.Code.FREQUENT_FULL_GC)
                .isEmpty());
    }

    @Test
    void frequentFullGcOnParallelNeedsRate() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            events.add(GcEvent.pause(i * 300.0, "Pause Full", GcEvent.Category.FULL, "Ergonomics", 100,
                    1000 * MB, 300 * MB, 1024 * MB));
        }
        GcAdvice advice = find(analyze(log(GcLog.Collector.PARALLEL, events, 0, 3600)), GcAdvice.Code.FREQUENT_FULL_GC)
                .orElseThrow();
        assertEquals(List.of("12", "12.0"), advice.params());
        assertEquals(GcAdvice.Severity.WARNING, advice.severity());
        // 同样 12 次分散在 10 小时里（每小时 1.2 次）则不提示。
        assertTrue(find(analyze(log(GcLog.Collector.PARALLEL, events, 0, 36000)), GcAdvice.Code.FREQUENT_FULL_GC)
                .isEmpty());
    }

    @Test
    void pauseTargetAndThroughput() {
        List<GcEvent> events = healthy();
        events.add(young(95, 250, 300, 100));
        GcReport report = analyze(log(GcLog.Collector.G1, events, 0, 100));
        GcAdvice pause = find(report, GcAdvice.Code.PAUSE_ABOVE_TARGET).orElseThrow();
        assertEquals(List.of("250", "200", "1", "250"), pause.params());
        assertEquals(GcAdvice.Severity.CRITICAL, pause.severity());
        assertTrue(find(new GcAnalyzer().analyze(report.log(), 300), GcAdvice.Code.PAUSE_ABOVE_TARGET).isEmpty());

        // 停顿 300 ms / 100 s = 99.7%，未低于 95%。
        assertTrue(find(report, GcAdvice.Code.LOW_THROUGHPUT).isEmpty());
        List<GcEvent> heavy = healthy();
        heavy.add(young(95, 8000, 300, 100));
        GcAdvice throughput = find(analyze(log(GcLog.Collector.G1, heavy, 0, 100)), GcAdvice.Code.LOW_THROUGHPUT)
                .orElseThrow();
        assertEquals(List.of("91.95", "95"), throughput.params());
        assertEquals(GcAdvice.Severity.WARNING, throughput.severity());
    }

    @Test
    void allocationStallsAndSafepoints() {
        List<GcLog.Stall> stalls = List.of(new GcLog.Stall(5, "main", 12.5), new GcLog.Stall(6, "worker", 30));
        GcAdvice stall = find(analyze(log(GcLog.Collector.ZGC, healthy(), 0, 100, List.of(), stalls)),
                GcAdvice.Code.ALLOCATION_STALL).orElseThrow();
        assertEquals(List.of("2", "42.5", "30.0"), stall.params());

        List<GcLog.Safepoint> slow = List.of(new GcLog.Safepoint(1, "Cleanup", 80, 0.1, 80.1),
                new GcLog.Safepoint(2, "G1CollectForAllocation", 0.1, 5, 5.1));
        GcAdvice safepoint = find(analyze(log(GcLog.Collector.G1, healthy(), 0, 100, slow, List.of())),
                GcAdvice.Code.LONG_TIME_TO_SAFEPOINT).orElseThrow();
        assertEquals(List.of("80.0", "Cleanup", "1"), safepoint.params());

        List<GcLog.Safepoint> fast = List.of(new GcLog.Safepoint(1, "Cleanup", 0.2, 0.1, 0.3));
        assertTrue(find(analyze(log(GcLog.Collector.G1, healthy(), 0, 100, fast, List.of())),
                GcAdvice.Code.LONG_TIME_TO_SAFEPOINT).isEmpty());
    }

    @Test
    void risingHeapFloorAdvice() {
        List<GcEvent> events = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            events.add(GcEvent.pause(i * 1800.0, "Pause Young (Mixed)", GcEvent.Category.MIXED, "G1 Evacuation Pause",
                    20, 900 * MB, (200 + i * 10) * MB, 1024 * MB));
        }
        GcAdvice advice = find(analyze(log(GcLog.Collector.G1, events, 0, 36000)), GcAdvice.Code.HEAP_FLOOR_RISING)
                .orElseThrow();
        assertEquals(List.of("20.0", "19.5", "21"), advice.params());
        assertTrue(find(analyze(log(GcLog.Collector.G1, healthy(), 0, 100)), GcAdvice.Code.HEAP_FLOOR_RISING).isEmpty());
    }
}
