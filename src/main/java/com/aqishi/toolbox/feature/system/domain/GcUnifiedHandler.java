package com.aqishi.toolbox.feature.system.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK 9+ 统一日志（{@code -Xlog:gc*}）的正文处理：G1、Parallel、Serial、ZGC（含 JDK 21 分代）、
 * Shenandoah、CMS（JDK 9-13），以及 safepoint 标签。
 */
final class GcUnifiedHandler {

    /** 同时跟踪的 GC 编号上限：正常日志里只有当前周期与一个并发周期在进行，留足余量即可。 */
    private static final int MAX_OPEN_CYCLES = 64;

    private static final Pattern GENERATION = Pattern.compile("^([YyO]):\\s*(.*)$");
    private static final Pattern PAUSE_NAME = Pattern.compile("^Pause ([A-Z][A-Za-z0-9]*(?: [A-Z][A-Za-z0-9]*)*)");
    private static final Pattern STALL = Pattern.compile(
            "^Allocation Stall \\((.*)\\)\\s+(" + GcText.NUM + ")\\s?ms");
    private static final Pattern VERSION = Pattern.compile("^Version:\\s*(\\S+)");
    private static final Pattern REGION_SIZE = Pattern.compile(
            "(?i)^Heap Region Size:\\s*(" + GcText.NUM + ")\\s?([BKMGT])");
    private static final Pattern DIGIT_GROUP = Pattern.compile("\\s*\\([^()]*\\d[^()]*\\)");
    private static final Set<String> SUBTYPES = Set.of("Normal", "Concurrent Start", "Prepare Mixed", "Mixed");
    private static final Set<String> ZGC_PAUSES = Set.of("Mark Start", "Mark End", "Relocate Start");
    private static final Set<String> SHENANDOAH_PAUSES = Set.of("Init Mark", "Final Mark", "Init Update Refs",
            "Final Update Refs", "Final Roots", "Init Evac", "Final Evac", "Degenerated GC");
    private static final Map<String, GcLog.Collector> DECLARATIONS = Map.of(
            "Using G1", GcLog.Collector.G1,
            "Using Parallel", GcLog.Collector.PARALLEL,
            "Using Serial", GcLog.Collector.SERIAL,
            "Using Concurrent Mark Sweep", GcLog.Collector.CMS,
            "Using Shenandoah", GcLog.Collector.SHENANDOAH,
            "Using The Z Garbage Collector", GcLog.Collector.ZGC,
            "Using Epsilon", GcLog.Collector.EPSILON);

    private final GcParseState state;
    private final Map<Long, GcUnifiedCycle> cycles = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, GcUnifiedCycle> eldest) {
            if (size() > MAX_OPEN_CYCLES) {
                close(eldest.getValue());
                return true;
            }
            return false;
        }
    };
    private String pendingTrigger;
    private int seenRestarts;

    GcUnifiedHandler(GcParseState state) {
        this.state = state;
    }

    /** 没有装饰的统一日志正文（{@code -Xlog:gc:file::none}）也要认得。 */
    static boolean looksBare(String line) {
        return line.startsWith("GC(") || line.startsWith("Safepoint \"") || line.startsWith("Using G1")
                || line.startsWith("Using The Z") || line.startsWith("Using Shenandoah")
                || line.startsWith("Using Parallel") || line.startsWith("Using Serial");
    }

    void handle(GcParseState.Stamp stamp, long line) {
        state.unifiedLines++;
        if (state.restarts() != seenRestarts) {
            // 新的一次 JVM 运行：GC 编号从 0 重来，上一段未收尾的周期先落地。
            seenRestarts = state.restarts();
            finish();
            pendingTrigger = null;
        }
        String body = stamp.body;
        if (body.isEmpty()) {
            state.ignored(1);
            return;
        }
        if (!GcDecorations.gcRelated(stamp)) {
            // 其它标签（class+load、os 等）：认得是统一日志，但与 GC 无关。
            state.ignored(1);
            return;
        }
        if (state.trySafepoint(body, stamp.time)) {
            return;
        }
        int close = gcIdEnd(body);
        if (close > 0) {
            long gcId = Long.parseLong(body, 3, close, 10);
            GcUnifiedCycle cycle = cycle(gcId);
            String message = body.substring(close + 1).strip();
            String generation = null;
            Matcher gen = message.length() > 2 && message.charAt(1) == ':' ? GENERATION.matcher(message) : null;
            if (gen != null && gen.matches()) {
                generation = gen.group(1);
                message = gen.group(2).strip();
                state.hint(GcLog.Collector.ZGC_GENERATIONAL);
            }
            withId(cycle, message, generation, stamp, line);
            return;
        }
        withoutId(body, stamp, line);
    }

    /** {@code GC(123) ...} 的右括号位置；不是这种开头时返回 -1。手写而非正则：每行都要判断一次。 */
    private static int gcIdEnd(String body) {
        if (!body.startsWith("GC(")) {
            return -1;
        }
        int index = 3;
        while (index < body.length() && Character.isDigit(body.charAt(index))) {
            index++;
        }
        return index > 3 && index < body.length() && body.charAt(index) == ')' && index - 3 <= 18 ? index : -1;
    }

    private GcUnifiedCycle cycle(long gcId) {
        GcUnifiedCycle cycle = cycles.get(gcId);
        if (cycle == null) {
            cycle = new GcUnifiedCycle(gcId);
            cycle.trigger = pendingTrigger;
            pendingTrigger = null;
            cycles.put(gcId, cycle);
        }
        return cycle;
    }

    private void withoutId(String body, GcParseState.Stamp stamp, long line) {
        for (Map.Entry<String, GcLog.Collector> entry : DECLARATIONS.entrySet()) {
            if (body.startsWith(entry.getKey())) {
                state.declare(entry.getValue());
                state.ignored(1);
                return;
            }
        }
        Matcher version = VERSION.matcher(body);
        if (version.find()) {
            state.jvmVersion = version.group(1);
            state.ignored(1);
            return;
        }
        Matcher region = REGION_SIZE.matcher(body);
        if (region.find()) {
            state.regionSizeKb = GcText.kb(region.group(1), region.group(2));
            state.ignored(1);
            return;
        }
        if (stall(body, stamp)) {
            return;
        }
        if (body.startsWith("Trigger:")) {
            pendingTrigger = normalizeTrigger(body.substring("Trigger:".length()));
            state.hint(GcLog.Collector.SHENANDOAH);
            state.ignored(1);
            return;
        }
        if (body.startsWith("Pause ")) {
            withId(cycle(-1), body, null, stamp, line);
            return;
        }
        state.ignored(1);
    }

    private boolean stall(String body, GcParseState.Stamp stamp) {
        if (!body.startsWith("Allocation Stall")) {
            return false;
        }
        Matcher matcher = STALL.matcher(body);
        if (!matcher.find()) {
            return false;
        }
        state.stalls.add(new GcLog.Stall(stamp.time, matcher.group(1), GcText.num(matcher.group(2))));
        state.hint(GcLog.Collector.ZGC);
        return true;
    }

    /** Shenandoah 的触发说明带着具体数值，去掉括号里的数值才能在原因直方图里归并。 */
    static String normalizeTrigger(String raw) {
        String text = DIGIT_GROUP.matcher(raw).replaceAll("").replaceAll("\\s+", " ").strip();
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.isEmpty() ? null : text;
    }

    private void withId(GcUnifiedCycle cycle, String message, String generation, GcParseState.Stamp stamp,
                        long line) {
        if (message.startsWith("Pause ")) {
            pause(cycle, message, generation, stamp, line);
            return;
        }
        if (message.startsWith("Garbage Collection") || message.startsWith("Major Collection")
                || message.startsWith("Minor Collection")) {
            zgcCycle(cycle, message, stamp, line);
            return;
        }
        if (message.startsWith("Concurrent ") || message.startsWith("Young Generation")
                || message.startsWith("Old Generation")) {
            GcUnifiedDetail.concurrent(state, cycle, message, generation, stamp, line);
            return;
        }
        if (stall(message, stamp)) {
            return;
        }
        if (message.startsWith("Trigger:")) {
            cycle.trigger = normalizeTrigger(message.substring("Trigger:".length()));
            state.hint(GcLog.Collector.SHENANDOAH);
            state.ignored(1);
            return;
        }
        GcUnifiedDetail.detail(state, cycle, message, stamp);
    }

    // ---------------------------------------------------------------- 停顿

    private void pause(GcUnifiedCycle cycle, String message, String generation, GcParseState.Stamp stamp,
                       long line) {
        Matcher name = PAUSE_NAME.matcher(message);
        if (!name.find()) {
            state.ignored(1);
            return;
        }
        String pauseName = name.group(1);
        List<String> groups = GcText.parenGroups(message, name.end());
        String rest = message.substring(GcText.afterParenGroups(message, name.end()));
        double ms = GcText.trailingMs(rest);
        String subtype = null;
        String cause = null;
        for (int i = 0; i < groups.size(); i++) {
            String group = groups.get(i);
            if (i == 0 && SUBTYPES.contains(group)) {
                subtype = group;
            } else if (group.startsWith("Evacuation Failure")) {
                cycle.flag(GcEvent.Flag.EVACUATION_FAILURE);
            } else if (group.equalsIgnoreCase("To-space exhausted")) {
                cycle.flag(GcEvent.Flag.TO_SPACE_EXHAUSTED);
            } else if (cause == null && isCause(group, pauseName)) {
                cause = group;
            }
        }
        hintFromPause(pauseName, subtype, cause, generation);
        String type = "Pause " + pauseName + (subtype == null ? "" : " (" + subtype + ")") + generationSuffix(generation);
        if (Double.isNaN(ms)) {
            // gc,start：记下开始时刻与原因，等汇总行。
            cycle.startType = type;
            cycle.startTime = stamp.time;
            cycle.startUptime = stamp.uptime;
            cycle.startDate = stamp.date;
            if (cause != null) {
                cycle.cause = cause;
            }
            state.ignored(1);
            return;
        }
        GcEvent event = state.newEvent(stamp, line);
        if (type.equals(cycle.startType) && !Double.isNaN(cycle.startTime)) {
            event.time = cycle.startTime;
            event.uptime = cycle.startUptime;
            event.date = cycle.startDate;
        } else {
            // 只有汇总行（-Xlog:gc）：日志在停顿结束时写出，倒推开始时刻。
            event.time = stamp.time - ms / 1000;
            event.uptime = stamp.uptime - ms / 1000;
        }
        event.gcId = cycle.id;
        event.type = state.intern(type);
        event.pause = true;
        event.durationMs = ms;
        event.cause = state.intern(cause != null ? cause : cycle.cause != null ? cycle.cause : cycle.trigger);
        event.category = category(pauseName, subtype, cause);
        GcText.Heap heap = GcText.firstHeap(rest);
        if (heap != null) {
            event.heapBeforeKb = heap.beforeKb();
            event.heapAfterKb = heap.afterKb();
            event.heapCommittedKb = heap.committedKb();
        }
        cycle.drainInto(event, state.regionSizeKb);
        event.applyCauseFlags();
        cycle.last = event;
        cycle.pauseHadHeap |= event.hasHeap();
        state.addEvent(event);
    }

    /** Shenandoah 的 {@code (unload classes)}、分代 ZGC 的 {@code (Major)}、退化点 {@code (Mark)} 都不是原因。 */
    private static boolean isCause(String group, String pauseName) {
        if (group.isEmpty() || pauseName.startsWith("Degenerated")) {
            return false;
        }
        if (Character.isLowerCase(group.charAt(0))) {
            return false;
        }
        return !group.equals("Major") && !group.equals("Minor");
    }

    private void hintFromPause(String pauseName, String subtype, String cause, String generation) {
        if (ZGC_PAUSES.contains(pauseName)) {
            state.hint(generation != null ? GcLog.Collector.ZGC_GENERATIONAL : GcLog.Collector.ZGC);
        } else if (SHENANDOAH_PAUSES.contains(pauseName)) {
            state.hint(GcLog.Collector.SHENANDOAH);
        } else if (subtype != null || (cause != null && cause.startsWith("G1"))
                || pauseName.equals("Remark") || pauseName.equals("Cleanup")) {
            state.hint(GcLog.Collector.G1);
        }
    }

    private static GcEvent.Category category(String pauseName, String subtype, String cause) {
        switch (pauseName) {
            case "Young":
                return "Mixed".equals(subtype) ? GcEvent.Category.MIXED : GcEvent.Category.YOUNG;
            case "Mixed":
                return GcEvent.Category.MIXED;
            case "Full":
                return GcEvent.Category.FULL;
            case "Initial Mark":
                // JDK 9/10 的 G1 把 Concurrent Start 叫 Initial Mark，本质是一次年轻代回收。
                return cause != null && cause.startsWith("G1") ? GcEvent.Category.YOUNG : GcEvent.Category.CYCLE;
            default:
                return pauseName.startsWith("Degenerated") ? GcEvent.Category.DEGENERATED : GcEvent.Category.CYCLE;
        }
    }

    static String generationSuffix(String generation) {
        if (generation == null) {
            return "";
        }
        return "O".equals(generation) ? " (Old)" : " (Young)";
    }

    // ---------------------------------------------------------------- ZGC 周期

    private void zgcCycle(GcUnifiedCycle cycle, String message, GcParseState.Stamp stamp, long line) {
        String kind = message.startsWith("Major") ? "Major Collection"
                : message.startsWith("Minor") ? "Minor Collection" : "Garbage Collection";
        state.hint("Garbage Collection".equals(kind) ? GcLog.Collector.ZGC : GcLog.Collector.ZGC_GENERATIONAL);
        List<String> groups = GcText.parenGroups(message, kind.length());
        String cause = groups.isEmpty() ? null : groups.get(0);
        String rest = message.substring(GcText.afterParenGroups(message, kind.length()));
        GcText.Heap heap = GcText.percentHeap(rest);
        if (heap == null) {
            heap = GcText.firstHeap(rest);
        }
        double ms = GcText.trailingMs(rest);
        if (heap == null && Double.isNaN(ms)) {
            cycle.cause = cause;
            cycle.cycleStart = stamp.time;
            cycle.cycleStartUptime = stamp.uptime;
            cycle.cycleStartDate = stamp.date;
            state.ignored(1);
            return;
        }
        GcEvent event = state.newEvent(stamp, line);
        if (!Double.isNaN(cycle.cycleStart)) {
            event.time = cycle.cycleStart;
            event.uptime = cycle.cycleStartUptime;
            event.date = cycle.cycleStartDate;
            if (Double.isNaN(ms) && !Double.isNaN(stamp.time)) {
                ms = (stamp.time - cycle.cycleStart) * 1000;
            }
        }
        event.gcId = cycle.id;
        event.pause = false;
        event.category = GcEvent.Category.CYCLE;
        event.type = kind;
        event.cause = state.intern(cause != null ? cause : cycle.cause);
        event.fullHeapCycle = !"Minor Collection".equals(kind);
        event.durationMs = ms;
        if (heap != null) {
            event.heapBeforeKb = heap.beforeKb();
            event.heapAfterKb = heap.afterKb();
            event.heapCommittedKb = cycle.capacityKb >= 0 ? cycle.capacityKb : heap.committedKb();
        }
        event.applyCauseFlags();
        cycle.summarized = true;
        state.addEvent(event);
        state.addPhase(cycle.id, event.time, kind, ms);
    }

    // ---------------------------------------------------------------- 并发阶段与明细

    // ---------------------------------------------------------------- 收尾

    /** Shenandoah 的停顿行不带堆数值：周期结束时用并发阶段上的数值补一个快照，堆曲线才连得上。 */
    private void close(GcUnifiedCycle cycle) {
        if (cycle.summarized || cycle.pauseHadHeap || cycle.cycleBeforeKb < 0 || cycle.cycleAfterKb < 0) {
            return;
        }
        GcParseState.Stamp stamp = new GcParseState.Stamp();
        stamp.time = cycle.cycleTime;
        stamp.uptime = cycle.cycleUptime;
        stamp.date = cycle.cycleDate;
        GcEvent event = state.newEvent(stamp, cycle.cycleLine);
        event.gcId = cycle.id;
        event.pause = false;
        event.category = GcEvent.Category.CYCLE;
        event.type = "Concurrent Cycle";
        event.cause = state.intern(cycle.trigger != null ? cycle.trigger : cycle.cause);
        event.fullHeapCycle = true;
        event.heapBeforeKb = cycle.cycleBeforeKb;
        event.heapAfterKb = cycle.cycleAfterKb;
        event.heapCommittedKb = cycle.cycleCommittedKb;
        event.applyCauseFlags();
        state.addEvent(event);
    }

    void finish() {
        for (GcUnifiedCycle cycle : cycles.values()) {
            close(cycle);
        }
        cycles.clear();
    }
}
