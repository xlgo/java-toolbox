package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcLogParserTest {

    private static final double EPS = 1e-6;

    static GcLog parse(String resource) throws IOException {
        try (InputStream in = GcLogParserTest.class.getResourceAsStream("/gclog/" + resource)) {
            String text = new String(Objects.requireNonNull(in, resource).readAllBytes(), StandardCharsets.UTF_8);
            return new GcLogParser().parse(text);
        }
    }

    private static List<GcEvent> pauses(GcLog log) {
        return log.events().stream().filter(GcEvent::isPause).collect(Collectors.toList());
    }

    @Test
    void parsesG1UnifiedJdk17() throws IOException {
        GcLog log = parse("g1-jdk17.txt");

        assertEquals(GcLog.Collector.G1, log.collector());
        assertEquals(GcLog.Family.JDK9_PLUS, log.family());
        assertEquals("17.0.9+9", log.jvmVersion());
        assertEquals(1024, log.regionSizeKb());
        assertEquals(0, log.unparsedLines(), () -> log.unparsedSample().toString());

        List<GcEvent> pauses = pauses(log);
        assertEquals(11, pauses.size());
        GcEvent first = pauses.get(0);
        assertEquals(0, first.gcId());
        assertEquals("Pause Young (Normal)", first.type());
        assertEquals("G1 Evacuation Pause", first.cause());
        assertEquals(GcEvent.Category.YOUNG, first.category());
        assertEquals(4.012, first.durationMs(), EPS);
        // 以 gc,start 行的时刻为停顿开始。
        assertEquals(1.000, first.uptime(), EPS);
        assertEquals("2024-05-01T10:00:01.000+0800", first.date());
        assertEquals(26 * 1024, first.heapBeforeKb());
        assertEquals(6 * 1024, first.heapAfterKb());
        assertEquals(256 * 1024, first.heapCommittedKb());
        assertEquals(24 * 1024, first.youngBeforeKb());
        assertEquals(3 * 1024, first.youngAfterKb());
        assertEquals(5000, first.metaBeforeKb());
        assertEquals(0.02, first.userSec(), EPS);
        assertEquals(3.2, first.subPhases().get("Evacuate Collection Set"), EPS);
        assertEquals(5, first.subPhases().size());

        GcEvent humongous = pauses.get(1);
        assertEquals("Pause Young (Concurrent Start)", humongous.type());
        assertTrue(humongous.has(GcEvent.Flag.HUMONGOUS));

        GcEvent remark = pauses.get(2);
        assertEquals("Pause Remark", remark.type());
        assertEquals(GcEvent.Category.CYCLE, remark.category());
        assertEquals(2.0, remark.durationMs(), EPS);
        // Remark 不应继承 GC(1) 或同编号之前的明细。
        assertEquals(-1, remark.youngBeforeKb());
        assertEquals("Pause Cleanup", pauses.get(3).type());
        assertEquals(GcEvent.Category.MIXED, pauses.get(5).category());

        GcEvent failure = pauses.get(6);
        assertTrue(failure.has(GcEvent.Flag.EVACUATION_FAILURE));
        assertEquals("G1 Evacuation Pause", failure.cause());

        GcEvent full = pauses.get(7);
        assertEquals(GcEvent.Category.FULL, full.category());
        assertEquals("G1 Compaction Pause", full.cause());
        assertEquals(100.0, full.subPhases().get("Phase 1: Mark live objects"), EPS);
        GcEvent explicit = pauses.get(8);
        assertEquals("System.gc()", explicit.cause());
        assertTrue(explicit.has(GcEvent.Flag.EXPLICIT));
        assertTrue(pauses.get(9).has(GcEvent.Flag.METADATA));
        // 没有 gc,start 行时从汇总行倒推开始时刻。
        assertEquals(12.0 - 0.0025, pauses.get(10).uptime(), EPS);

        List<String> phases = log.phases().stream().map(GcLog.Phase::name).collect(Collectors.toList());
        assertTrue(phases.contains("Concurrent Mark"), phases::toString);
        assertTrue(phases.contains("Concurrent Mark Cycle"), phases::toString);
        assertEquals(6, phases.size(), phases::toString);
        assertEquals(0.004, log.firstTime(), EPS);
        assertEquals(20.0, log.lastTime(), EPS);
    }

    @Test
    void parsesParallelUnifiedJdk17() throws IOException {
        GcLog log = parse("parallel-jdk17.txt");
        assertEquals(GcLog.Collector.PARALLEL, log.collector());
        List<GcEvent> pauses = pauses(log);
        assertEquals(4, pauses.size());
        GcEvent young = pauses.get(0);
        assertEquals("Pause Young", young.type());
        assertEquals("Allocation Failure", young.cause());
        assertEquals(65536, young.youngBeforeKb());
        assertEquals(10720, young.youngAfterKb());
        assertEquals(64 * 1024, young.heapBeforeKb());
        GcEvent full = pauses.get(2);
        assertEquals(GcEvent.Category.FULL, full.category());
        assertEquals("Ergonomics", full.cause());
        assertEquals(60.0, full.subPhases().get("Marking Phase"), EPS);
        assertEquals(0.60, full.userSec(), EPS);
        assertTrue(pauses.get(3).has(GcEvent.Flag.METADATA));
        assertEquals(0, log.unparsedLines(), () -> log.unparsedSample().toString());
    }

    @Test
    void parsesParallelJdk8() throws IOException {
        GcLog log = parse("parallel-jdk8.txt");
        assertEquals(GcLog.Collector.PARALLEL, log.collector());
        assertEquals(GcLog.Family.JDK8_OR_EARLIER, log.family());
        assertEquals("1.8.0_301-b09", log.jvmVersion());
        List<GcEvent> pauses = pauses(log);
        assertEquals(5, pauses.size());
        GcEvent young = pauses.get(0);
        assertEquals("Pause Young", young.type());
        assertEquals(12.3456, young.durationMs(), EPS);
        assertEquals(1.234, young.uptime(), EPS);
        assertEquals(524800, young.heapBeforeKb());
        assertEquals(12353, young.heapAfterKb());
        assertEquals(2010112, young.heapCommittedKb());
        assertEquals(524800, young.youngBeforeKb());
        assertEquals(12345, young.youngAfterKb());
        assertEquals(0.05, young.userSec(), EPS);
        assertTrue(pauses.get(2).has(GcEvent.Flag.EXPLICIT));
        GcEvent full = pauses.get(4);
        assertEquals(GcEvent.Category.FULL, full.category());
        assertEquals("Ergonomics", full.cause());
        assertEquals(1410345, full.heapBeforeKb());
        assertEquals(200000, full.heapAfterKb());
        assertEquals(30000, full.metaBeforeKb());
        assertEquals(1, log.safepoints().size());
        assertEquals(513.0, log.safepoints().get(0).totalMs(), EPS);
        assertEquals(0.1, log.safepoints().get(0).reachMs(), EPS);
        assertEquals(0, log.unparsedLines(), () -> log.unparsedSample().toString());
    }

    @Test
    void parsesCmsJdk8WithFailures() throws IOException {
        GcLog log = parse("cms-jdk8.txt");
        assertEquals(GcLog.Collector.CMS, log.collector());
        List<GcEvent> pauses = pauses(log);
        assertEquals(5, pauses.size(), pauses.stream().map(GcEvent::type).collect(Collectors.toList())::toString);

        GcEvent parNew = pauses.get(0);
        assertEquals("Pause Young", parNew.type());
        assertEquals(20.1, parNew.durationMs(), EPS);
        assertEquals(78656, parNew.youngBeforeKb());
        assertEquals(900000, parNew.heapBeforeKb());
        assertEquals(850000, parNew.heapAfterKb());

        assertEquals("Pause Initial Mark", pauses.get(1).type());
        assertNull(pauses.get(1).cause());
        assertEquals(520000, pauses.get(1).heapAfterKb());
        GcEvent remark = pauses.get(2);
        assertEquals("Pause Remark", remark.type());
        assertEquals(15.0, remark.durationMs(), EPS);

        GcEvent promotion = pauses.get(3);
        assertTrue(promotion.has(GcEvent.Flag.PROMOTION_FAILED));
        assertEquals(GcEvent.Category.FULL, promotion.category());
        assertEquals(1068656, promotion.heapBeforeKb());
        assertEquals(500000, promotion.heapAfterKb());

        GcEvent failure = pauses.get(4);
        assertTrue(failure.has(GcEvent.Flag.CONCURRENT_MODE_FAILURE));
        assertEquals(GcEvent.Category.FULL, failure.category());
        assertEquals(2000.1, failure.durationMs(), EPS);
        assertEquals(1077000, failure.heapBeforeKb());
        assertEquals(600000, failure.heapAfterKb());
        assertEquals(1078656, failure.heapCommittedKb());

        long marks = log.phases().stream().filter(p -> p.name().equals("CMS-concurrent-mark")).count();
        assertEquals(2, marks);
        // 末尾的 OutOfMemoryError 是应用输出，不是 GC 日志。
        assertEquals(1, log.unparsedLines(), () -> log.unparsedSample().toString());
        assertTrue(log.unparsedSample().get(0).text().startsWith("Exception in thread"));
    }

    @Test
    void parsesG1Jdk8MultiLineDetails() throws IOException {
        GcLog log = parse("g1-jdk8.txt");
        assertEquals(GcLog.Collector.G1, log.collector());
        List<GcEvent> pauses = pauses(log);
        assertEquals(7, pauses.size());
        GcEvent young = pauses.get(0);
        assertEquals("Pause Young (Normal)", young.type());
        assertEquals("G1 Evacuation Pause", young.cause());
        assertEquals(24 * 1024, young.heapBeforeKb());
        assertEquals(4096, young.heapAfterKb());
        assertEquals(24 * 1024, young.youngBeforeKb());
        assertEquals(3072, young.youngAfterKb());
        assertEquals(0.05, young.userSec(), EPS);
        GcEvent initialMark = pauses.get(1);
        assertEquals("Pause Young (Concurrent Start)", initialMark.type());
        assertTrue(initialMark.has(GcEvent.Flag.HUMONGOUS));
        assertEquals("Pause Remark", pauses.get(2).type());
        assertEquals(5.0, pauses.get(2).durationMs(), EPS);
        assertEquals("Pause Cleanup", pauses.get(3).type());
        assertEquals(52 * 1024, pauses.get(3).heapBeforeKb());
        assertEquals(GcEvent.Category.MIXED, pauses.get(4).category());
        assertTrue(pauses.get(5).has(GcEvent.Flag.TO_SPACE_EXHAUSTED));
        assertEquals(1, log.events().stream().filter(e -> e.category() == GcEvent.Category.FULL).count());
        GcEvent full = log.events().get(log.events().size() - 1);
        assertEquals(250 * 1024, full.heapBeforeKb());
        assertEquals(100 * 1024, full.heapAfterKb());
        assertEquals(3000, full.metaBeforeKb());
        assertEquals(2, log.phases().size());
        assertEquals(89.0, log.phases().get(1).durationMs(), EPS);
        assertEquals(1, log.safepoints().size());
        assertEquals(0, log.unparsedLines(), () -> log.unparsedSample().toString());
    }
}
