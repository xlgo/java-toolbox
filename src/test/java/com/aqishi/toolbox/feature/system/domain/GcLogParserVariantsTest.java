package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static com.aqishi.toolbox.feature.system.domain.GcLogParserTest.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcLogParserVariantsTest {

    private static final double EPS = 1e-6;

    private static List<GcEvent> pauses(GcLog log) {
        return log.events().stream().filter(GcEvent::isPause).collect(Collectors.toList());
    }

    private static List<GcEvent> cycles(GcLog log) {
        return log.events().stream().filter(event -> !event.isPause()).collect(Collectors.toList());
    }

    @Test
    void parsesZgcJdk17() throws IOException {
        GcLog log = parse("zgc-jdk17.txt");
        assertEquals(GcLog.Collector.ZGC, log.collector());
        assertEquals("17.0.2+8-86", log.jvmVersion());
        List<GcEvent> pauses = pauses(log);
        assertEquals(9, pauses.size());
        GcEvent markStart = pauses.get(0);
        assertEquals("Pause Mark Start", markStart.type());
        assertEquals("Warmup", markStart.cause());
        assertEquals(GcEvent.Category.CYCLE, markStart.category());
        assertEquals(0.012, markStart.durationMs(), EPS);

        List<GcEvent> cycles = cycles(log);
        assertEquals(3, cycles.size());
        GcEvent warmup = cycles.get(0);
        assertEquals("Garbage Collection", warmup.type());
        assertTrue(warmup.isFullHeapCycle());
        assertEquals(1.000, warmup.time(), EPS);
        assertEquals(40.0, warmup.durationMs(), 1e-3);
        assertEquals(200 * 1024, warmup.heapBeforeKb());
        assertEquals(100 * 1024, warmup.heapAfterKb());
        assertEquals(1024 * 1024, warmup.heapCommittedKb());
        assertEquals("Allocation Rate", cycles.get(1).cause());

        assertEquals(2, log.stalls().size());
        assertEquals("http-nio-8080-exec-1", log.stalls().get(0).thread());
        assertEquals(12.5, log.stalls().get(0).durationMs(), EPS);
        assertEquals(0, log.unparsedLines(), () -> log.unparsedSample().toString());
    }

    @Test
    void parsesGenerationalZgcJdk21() throws IOException {
        GcLog log = parse("zgc-generational-jdk21.txt");
        assertEquals(GcLog.Collector.ZGC_GENERATIONAL, log.collector());
        List<GcEvent> pauses = pauses(log);
        assertEquals(7, pauses.size());
        assertEquals("Pause Mark Start (Young)", pauses.get(0).type());
        assertEquals("Warmup", pauses.get(0).cause());
        assertEquals("Pause Mark End (Old)", pauses.get(3).type());
        List<GcEvent> cycles = cycles(log);
        assertEquals(3, cycles.size());
        GcEvent major = cycles.get(0);
        assertEquals("Major Collection", major.type());
        assertTrue(major.isFullHeapCycle());
        assertEquals(30.0, major.durationMs(), EPS);
        assertEquals(100 * 1024, major.heapBeforeKb());
        assertEquals(38 * 1024, major.heapAfterKb());
        GcEvent minor = cycles.get(1);
        assertEquals("Minor Collection", minor.type());
        assertFalse(minor.isFullHeapCycle());
        assertEquals(1, log.stalls().size());
        List<String> phases = log.phases().stream().map(GcLog.Phase::name).collect(Collectors.toList());
        assertTrue(phases.contains("Concurrent Mark (Young)"), phases::toString);
        assertTrue(phases.contains("Young Generation"), phases::toString);
        assertTrue(phases.contains("Major Collection"), phases::toString);
    }

    @Test
    void parsesShenandoahWithTriggersAndDegeneratedGc() throws IOException {
        GcLog log = parse("shenandoah-jdk17.txt");
        assertEquals(GcLog.Collector.SHENANDOAH, log.collector());
        List<GcEvent> pauses = pauses(log);
        assertEquals(6, pauses.size());
        GcEvent initMark = pauses.get(0);
        assertEquals("Pause Init Mark", initMark.type());
        assertEquals("Free is below minimum threshold", initMark.cause());
        GcEvent degenerated = pauses.get(4);
        assertEquals(GcEvent.Category.DEGENERATED, degenerated.category());
        assertEquals("Handle Allocation Failure", degenerated.cause());
        assertEquals(2000 * 1024, degenerated.heapBeforeKb());
        GcEvent full = pauses.get(5);
        assertEquals(GcEvent.Category.FULL, full.category());
        assertEquals(1000.0, full.durationMs(), EPS);

        // 停顿不带堆数值时，用并发阶段上的数值补一个周期快照。
        List<GcEvent> cycles = cycles(log);
        assertEquals(1, cycles.size());
        assertEquals(1800 * 1024, cycles.get(0).heapBeforeKb());
        assertEquals(600 * 1024, cycles.get(0).heapAfterKb());
        List<String> phases = log.phases().stream().map(GcLog.Phase::name).collect(Collectors.toList());
        assertTrue(phases.contains("Concurrent marking"), phases::toString);
        assertTrue(phases.contains("Concurrent evacuation"), phases::toString);
    }

    @Test
    void parsesSafepointsWithPidAndTidDecorations() throws IOException {
        GcLog log = parse("safepoint-pid-tid.txt");
        assertEquals(GcLog.Collector.G1, log.collector());
        assertEquals(1, pauses(log).size());
        assertEquals(4, log.safepoints().size());
        GcLog.Safepoint first = log.safepoints().get(0);
        assertEquals("G1CollectForAllocation", first.operation());
        assertEquals(0.12, first.reachMs(), EPS);
        assertEquals(10.1, first.atMs(), EPS);
        assertEquals(10.223, first.totalMs(), EPS);
        assertEquals(80.0, log.safepoints().get(1).reachMs(), EPS);
        GcLog.Safepoint legacy = log.safepoints().get(3);
        assertNull(legacy.operation());
        assertEquals(2.0, legacy.totalMs(), EPS);
        assertEquals(0.5, legacy.reachMs(), EPS);
    }

    @Test
    void acceptsDecorationVariants() {
        String text = String.join("\n",
                "[1714528800000ms][123ms][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 10M->2M(64M) 1.000ms",
                "[2024-05-01T02:00:01.000Z][warning][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 12M->3M(64M) 2.000ms",
                "GC(2) Pause Young (Normal) (G1 Evacuation Pause) 14M->4M(64M) 3.000ms",
                "[myhost][0.500s][debug][gc,phases] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 16M->5M(64M) 4.000ms");
        GcLog log = new GcLogParser().parse(text);
        List<GcEvent> pauses = pauses(log);
        assertEquals(4, pauses.size(), () -> log.unparsedSample().toString());
        assertEquals(0.123 - 0.001, pauses.get(0).uptime(), EPS);
        assertEquals(0, log.unparsedLines());
    }

    @Test
    void toleratesNoiseCrlfAndConcatenatedRestarts() throws IOException {
        GcLog log = parse("noisy-concatenated.txt");
        assertEquals(GcLog.Collector.SERIAL, log.collector());
        List<GcEvent> pauses = pauses(log);
        assertEquals(5, pauses.size());
        assertEquals(1, log.restarts());
        // 第二次运行接在第一次之后，时间轴单调。
        for (int i = 1; i < pauses.size(); i++) {
            assertTrue(pauses.get(i).time() >= pauses.get(i - 1).time());
        }
        assertEquals(3, log.unparsedLines(), () -> log.unparsedSample().toString());

        String crlf = "[1.000s][info][gc] GC(0) Pause Young (Allocation Failure) 20M->5M(64M) 3.000ms\r\n"
                + "[2.000s][info][gc] GC(1) Pause Young (Allocation Failure) 20M->5M(64M) 3.000ms\r\n";
        assertEquals(2, pauses(new GcLogParser().parse(crlf)).size());
    }

    @Test
    void opensFilesWithBomAndUtf16(@TempDir Path dir) throws IOException {
        String text = "[1.000s][info][gc] GC(0) Pause Young (Allocation Failure) 20M->5M(64M) 3.000ms\n";
        Path utf16 = dir.resolve("gc16.txt");
        byte[] body = text.getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withBom, 2, body.length);
        Files.write(utf16, withBom);
        GcLogInput.Opened opened = GcLogInput.open(utf16, GcLogParser.MAX_INPUT_BYTES);
        assertEquals(StandardCharsets.UTF_16LE, opened.charset());
        try (Reader reader = opened.reader()) {
            assertEquals(1, pauses(new GcLogParser().parse(reader)).size());
        }

        Path limited = dir.resolve("gc8.txt");
        Files.writeString(limited, text + text.replace("GC(0)", "GC(1)"), StandardCharsets.UTF_8);
        GcLogInput.Opened cut = GcLogInput.open(limited, text.length());
        assertEquals(StandardCharsets.UTF_8, cut.charset());
        try (Reader reader = cut.reader()) {
            assertEquals(1, pauses(new GcLogParser().parse(reader)).size());
        }
        assertTrue(GcLogInput.preview(limited, 10).text().startsWith("[1.000s]"));
    }

    @Test
    void normalizesShenandoahTriggers() {
        assertEquals("Average GC time is above the time for average allocation rate to deplete free headroom",
                GcUnifiedHandler.normalizeTrigger(" Average GC time (12.34 ms) is above the time for average "
                        + "allocation rate (123 MB/s) to deplete free headroom (45M) (margin of error = 1.80)"));
    }
}
