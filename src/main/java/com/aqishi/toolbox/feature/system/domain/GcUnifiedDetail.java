package com.aqishi.toolbox.feature.system.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一日志里挂在某个 GC 编号下的明细行：并发阶段、分区/分代容量、元空间、CPU、停顿子阶段。
 * 这些行本身不构成事件，只往 {@link GcUnifiedCycle} 里记账，等汇总行落地时一并交出。
 */
final class GcUnifiedDetail {

    private static final Pattern REGIONS = Pattern.compile("^(Eden|Survivor) regions:\\s*(\\d+)->(\\d+)");
    private static final Pattern GEN_LINE = Pattern.compile(
            "^(PSYoungGen|ParOldGen|PSOldGen|DefNew|Tenured|ParNew|CMS|Metaspace)\\s*:\\s*(.*)$");
    private static final Pattern CPU = Pattern.compile(
            "^User=(" + GcText.NUM + ")s\\s+Sys=(" + GcText.NUM + ")s\\s+Real=(" + GcText.NUM + ")s");
    private static final Pattern CAPACITY = Pattern.compile("^Capacity:\\s+(" + GcText.NUM + ")([BKMGT])");
    private static final Pattern SUB_PHASE = Pattern.compile(
            "^([A-Z][A-Za-z0-9 /()'-]*?(?:: [A-Za-z][A-Za-z0-9 /'-]*?)?):?\\s+(" + GcText.NUM + ")\\s?ms$");

    private GcUnifiedDetail() {
    }

    static void concurrent(GcParseState state, GcUnifiedCycle cycle, String message, String generation,
                           GcParseState.Stamp stamp, long line) {
        double ms = GcText.trailingMs(message);
        if (Double.isNaN(ms)) {
            state.ignored(1);
            return;
        }
        String text = GcText.withoutTrailingDuration(message);
        GcText.Heap heap = GcText.firstHeap(text);
        if (heap != null) {
            cycle.observeCycleHeap(heap, stamp, line);
        }
        int cut = text.length();
        int paren = text.indexOf('(');
        if (paren > 0) {
            cut = paren;
        }
        Matcher heapAt = GcText.HEAP_TRIPLE.matcher(text);
        if (heapAt.find() && heapAt.start() < cut) {
            cut = heapAt.start();
        }
        Matcher percentAt = GcText.PERCENT_HEAP.matcher(text);
        if (percentAt.find() && percentAt.start() < cut) {
            cut = percentAt.start();
        }
        String name = text.substring(0, cut).strip();
        if (name.endsWith(":")) {
            name = name.substring(0, name.length() - 1).strip();
        }
        if (name.startsWith("Concurrent ") && name.length() > 11 && Character.isLowerCase(name.charAt(11))) {
            // Shenandoah 的阶段名第二个词小写（Concurrent marking），G1/ZGC 大写。
            state.hint(GcLog.Collector.SHENANDOAH);
        }
        state.addPhase(cycle.id, stamp.time, name.endsWith("Generation") ? name : name + GcUnifiedHandler.generationSuffix(generation),
                ms);
    }

    static void detail(GcParseState state, GcUnifiedCycle cycle, String message, GcParseState.Stamp stamp) {
        state.ignored(1);
        Matcher regions = message.startsWith("Eden regions") || message.startsWith("Survivor regions")
                ? REGIONS.matcher(message) : null;
        if (regions != null && regions.find()) {
            int before = Integer.parseInt(regions.group(2));
            int after = Integer.parseInt(regions.group(3));
            if ("Eden".equals(regions.group(1))) {
                cycle.edenBefore = before;
                cycle.edenAfter = after;
            } else {
                cycle.survivorBefore = before;
                cycle.survivorAfter = after;
            }
            state.hint(GcLog.Collector.G1);
            return;
        }
        Matcher gen = GEN_LINE.matcher(message);
        if (gen.matches()) {
            generationLine(state, cycle, gen.group(1), gen.group(2));
            return;
        }
        Matcher cpu = CPU.matcher(message);
        if (cpu.find()) {
            GcEvent target = cycle.last;
            double user = GcText.num(cpu.group(1));
            double sys = GcText.num(cpu.group(2));
            double real = GcText.num(cpu.group(3));
            if (target != null && Double.isNaN(target.userSec)) {
                target.userSec = user;
                target.sysSec = sys;
                target.realSec = real;
            } else {
                cycle.userSec = user;
                cycle.sysSec = sys;
                cycle.realSec = real;
            }
            return;
        }
        Matcher capacity = CAPACITY.matcher(message);
        if (capacity.find()) {
            if (cycle.capacityKb < 0) {
                cycle.capacityKb = GcText.kb(capacity.group(1), capacity.group(2));
            }
            return;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("to-space exhausted")) {
            flagOrLast(cycle, GcEvent.Flag.TO_SPACE_EXHAUSTED);
            return;
        }
        if (lower.startsWith("evacuation failure")) {
            flagOrLast(cycle, GcEvent.Flag.EVACUATION_FAILURE);
            return;
        }
        if (cycle.startType != null && (stamp.tags == null || GcDecorations.hasTag(stamp, "phases"))) {
            Matcher sub = SUB_PHASE.matcher(message);
            if (sub.matches()) {
                cycle.putSubPhase(state.intern(sub.group(1).strip()), GcText.num(sub.group(2)));
            }
        }
    }

    private static void flagOrLast(GcUnifiedCycle cycle, GcEvent.Flag flag) {
        if (cycle.startType == null && cycle.last != null) {
            cycle.last.addFlag(flag);
        } else {
            cycle.flag(flag);
        }
    }

    private static void generationLine(GcParseState state, GcUnifiedCycle cycle, String generation, String rest) {
        GcText.Heap heap = GcText.firstHeap(rest);
        switch (generation) {
            case "PSYoungGen", "ParOldGen", "PSOldGen" -> state.hint(GcLog.Collector.PARALLEL);
            case "DefNew", "Tenured" -> state.hint(GcLog.Collector.SERIAL);
            case "ParNew", "CMS" -> state.hint(GcLog.Collector.CMS);
            default -> {
                // Metaspace 不指示收集器
            }
        }
        if (heap == null) {
            return;
        }
        if ("Metaspace".equals(generation)) {
            cycle.metaBeforeKb = heap.beforeKb();
            cycle.metaAfterKb = heap.afterKb();
        } else if ("PSYoungGen".equals(generation) || "DefNew".equals(generation) || "ParNew".equals(generation)) {
            cycle.youngBeforeKb = heap.beforeKb();
            cycle.youngAfterKb = heap.afterKb();
        }
    }
}
