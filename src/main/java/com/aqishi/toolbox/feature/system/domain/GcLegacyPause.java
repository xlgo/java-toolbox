package com.aqishi.toolbox.feature.system.domain;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * 解析 JDK 8 的一条停顿记录，例如
 * {@code [GC (Allocation Failure) [PSYoungGen: 524800K->12345K(611840K)] 524800K->12353K(2010112K), 0.0123456 secs]}、
 * {@code [GC pause (G1 Evacuation Pause) (young), 0.0123 secs]} 及其后的 {@code [Eden: ... Heap: ...]} 明细。
 */
final class GcLegacyPause {

    /** G1 暂停行里描述类型而非原因的括号组。 */
    private static final Set<String> G1_QUALIFIERS = Set.of("young", "mixed", "initial-mark", "to-space exhausted",
            "to-space overflow");

    private GcLegacyPause() {
    }

    /** @return 是否成功解析出一次停顿 */
    static boolean parse(GcParseState state, String body, GcParseState.Stamp stamp, long line) {
        boolean full = body.startsWith("[Full GC");
        int position = full ? "[Full GC".length() : "[GC".length();
        String variant = null;
        if (!full) {
            for (String candidate : new String[]{"pause", "remark", "cleanup"}) {
                if (body.startsWith("[GC " + candidate)) {
                    variant = candidate;
                    position = ("[GC " + candidate).length();
                    break;
                }
            }
        }
        String withoutTimes = GcLegacyHandler.times().matcher(body).replaceAll("");
        Matcher duration = GcLegacyHandler.duration().matcher(withoutTimes);
        double seconds = Double.NaN;
        while (duration.find()) {
            seconds = GcText.num(duration.group(1));
        }
        if (Double.isNaN(seconds)) {
            return false;
        }

        List<String> groups = GcText.parenGroups(body, position);
        String cause = null;
        for (String group : groups) {
            if (!G1_QUALIFIERS.contains(group)) {
                cause = group;
                break;
            }
        }
        GcEvent event = state.newEvent(stamp, line);
        event.pause = true;
        event.durationMs = seconds * 1000;
        event.cause = state.intern(cause);
        classify(state, event, variant, full, groups, body);
        heap(state, event, body);
        Matcher times = GcLegacyHandler.times().matcher(body);
        if (times.find()) {
            event.userSec = GcText.num(times.group(1));
            event.sysSec = GcText.num(times.group(2));
            event.realSec = GcText.num(times.group(3));
        }
        String lower = body.toLowerCase(Locale.ROOT);
        if (lower.contains("promotion failed")) {
            event.addFlag(GcEvent.Flag.PROMOTION_FAILED);
        }
        if (lower.contains("concurrent mode failure")) {
            event.addFlag(GcEvent.Flag.CONCURRENT_MODE_FAILURE);
        }
        if (lower.contains("to-space exhausted") || lower.contains("to-space overflow")) {
            event.addFlag(GcEvent.Flag.TO_SPACE_EXHAUSTED);
        }
        event.applyCauseFlags();
        state.addEvent(event);
        return true;
    }

    private static void classify(GcParseState state, GcEvent event, String variant, boolean full,
                                 List<String> groups, String body) {
        if ("pause".equals(variant)) {
            state.hint(GcLog.Collector.G1);
            if (groups.contains("mixed")) {
                event.type = "Pause Young (Mixed)";
                event.category = GcEvent.Category.MIXED;
            } else {
                event.type = groups.contains("initial-mark") ? "Pause Young (Concurrent Start)" : "Pause Young (Normal)";
                event.category = GcEvent.Category.YOUNG;
            }
            return;
        }
        if ("remark".equals(variant) || "cleanup".equals(variant)) {
            state.hint(GcLog.Collector.G1);
            event.type = "remark".equals(variant) ? "Pause Remark" : "Pause Cleanup";
            event.category = GcEvent.Category.CYCLE;
            return;
        }
        if (full) {
            event.type = "Pause Full";
            event.category = GcEvent.Category.FULL;
            return;
        }
        String cause = event.cause;
        if ("CMS Initial Mark".equals(cause) || "CMS Final Remark".equals(cause)) {
            state.hint(GcLog.Collector.CMS);
            event.type = "CMS Initial Mark".equals(cause) ? "Pause Initial Mark" : "Pause Remark";
            event.category = GcEvent.Category.CYCLE;
            event.cause = null;
            return;
        }
        event.type = "Pause Young";
        event.category = GcEvent.Category.YOUNG;
        // promotion failed 后年轻代回收会升级为整堆回收：[ParNew (promotion failed): ...][CMS: ...]。
        Matcher gen = GcLegacyHandler.generation().matcher(body);
        while (gen.find()) {
            String name = gen.group(1);
            if (name.equals("CMS") || name.equals("Tenured") || name.equals("ParOldGen") || name.equals("PSOldGen")) {
                event.type = "Pause Full";
                event.category = GcEvent.Category.FULL;
                break;
            }
        }
    }

    private static void heap(GcParseState state, GcEvent event, String body) {
        StringBuilder rest = new StringBuilder(body);
        Matcher meta = GcLegacyHandler.meta().matcher(body);
        if (meta.find()) {
            GcText.Heap heap = GcText.heap(meta, 0);
            event.metaBeforeKb = heap.beforeKb();
            event.metaAfterKb = heap.afterKb();
            blank(rest, meta.start(), meta.end());
        }
        Matcher gen = GcLegacyHandler.generation().matcher(body);
        while (gen.find()) {
            String name = gen.group(1);
            GcText.Heap heap = GcText.heap(gen, 1);
            switch (name) {
                case "PSYoungGen", "ParOldGen", "PSOldGen" -> state.hint(GcLog.Collector.PARALLEL);
                case "DefNew", "Tenured" -> state.hint(GcLog.Collector.SERIAL);
                default -> state.hint(GcLog.Collector.CMS);
            }
            boolean young = name.equals("PSYoungGen") || name.equals("ParNew") || name.equals("DefNew")
                    || name.equals("ASParNew");
            if (young && event.youngBeforeKb < 0) {
                event.youngBeforeKb = heap.beforeKb();
                event.youngAfterKb = heap.afterKb();
            }
            blank(rest, gen.start(), gen.end());
        }
        Matcher g1Heap = GcLegacyHandler.g1Heap().matcher(body);
        if (g1Heap.find()) {
            setTotal(event, GcText.heap(g1Heap, 0));
            state.hint(GcLog.Collector.G1);
            Matcher eden = GcLegacyHandler.g1Eden().matcher(body);
            if (eden.find()) {
                GcText.Heap edenHeap = GcText.heap(eden, 0);
                long survivorBefore = GcText.kb(eden.group(9), eden.group(10));
                long survivorAfter = GcText.kb(eden.group(11), eden.group(12));
                event.youngBeforeKb = edenHeap.beforeKb() + survivorBefore;
                event.youngAfterKb = edenHeap.afterKb() + survivorAfter;
            }
            return;
        }
        // 去掉分代与元空间后，剩下的最后一个三元组就是整堆（CMS 的 concurrent mode failure 里分代段被截断，
        // 取最后一个才不会误拿到 CMS 老年代的数值）。
        Matcher triple = GcText.HEAP_TRIPLE.matcher(rest);
        GcText.Heap total = null;
        while (triple.find()) {
            total = GcText.heap(triple);
        }
        if (total != null) {
            setTotal(event, total);
            return;
        }
        Matcher occupancy = GcLegacyHandler.occupancy().matcher(GcLegacyHandler.times().matcher(body).replaceAll(""));
        if (occupancy.find()) {
            long used = GcText.kb(occupancy.group(1), occupancy.group(2));
            setTotal(event, new GcText.Heap(used, used, GcText.kb(occupancy.group(3), occupancy.group(4))));
        }
    }

    private static void setTotal(GcEvent event, GcText.Heap heap) {
        event.heapBeforeKb = heap.beforeKb();
        event.heapAfterKb = heap.afterKb();
        event.heapCommittedKb = heap.committedKb();
    }

    private static void blank(StringBuilder text, int start, int end) {
        for (int i = start; i < end && i < text.length(); i++) {
            text.setCharAt(i, ' ');
        }
    }
}
