package com.aqishi.toolbox.feature.system.domain;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 一次 GC 事件：STW 停顿，或并发收集器（ZGC / Shenandoah）一轮周期的堆快照。
 *
 * <p>解析时逐行补全字段（G1 的明细行在汇总行之前，CPU 行在之后），所以字段对同包的解析器可写；
 * 解析结束交给分析器后不再修改，对外只暴露读取方法。未知的数值统一用 {@code -1} / {@code NaN}，
 * 避免为二十来个可选字段各包一层 Optional。</p>
 */
public final class GcEvent {

    /** 事件大类：决定配色、Full GC 统计以及「堆底线」的取点。 */
    public enum Category {
        /** 年轻代停顿（含 G1 Concurrent Start、Parallel/Serial/CMS 的 Minor GC）。 */
        YOUNG,
        /** G1 混合回收。 */
        MIXED,
        /** Full GC。 */
        FULL,
        /** 并发周期里的短停顿（Remark、Cleanup、ZGC/Shenandoah 的各个 Pause）或周期快照。 */
        CYCLE,
        /** Shenandoah 退化 GC。 */
        DEGENERATED,
        /** 其它无法归类的停顿。 */
        OTHER
    }

    /** 事件上的异常标记，供建议规则使用。 */
    public enum Flag {
        TO_SPACE_EXHAUSTED,
        EVACUATION_FAILURE,
        PROMOTION_FAILED,
        CONCURRENT_MODE_FAILURE,
        HUMONGOUS,
        EXPLICIT,
        METADATA
    }

    long gcId = -1;
    double time = Double.NaN;
    double uptime = Double.NaN;
    String date;
    String type;
    Category category = Category.OTHER;
    String cause;
    boolean pause = true;
    /** ZGC 非分代周期、分代 ZGC 的 Major、Shenandoah 周期：回收整个堆，其回收后堆大小可作为「底线」。 */
    boolean fullHeapCycle;
    double durationMs = Double.NaN;
    long heapBeforeKb = -1;
    long heapAfterKb = -1;
    long heapCommittedKb = -1;
    long youngBeforeKb = -1;
    long youngAfterKb = -1;
    long metaBeforeKb = -1;
    long metaAfterKb = -1;
    double userSec = Double.NaN;
    double sysSec = Double.NaN;
    double realSec = Double.NaN;
    long line;
    /** 第几次 JVM 运行（拼接日志里 GC 编号会从 0 重新开始）。 */
    int segment;
    private EnumSet<Flag> flags;
    /** 子阶段用两个并行数组保存：十万级事件各带一个 LinkedHashMap 会多占几十 MB。 */
    private String[] subNames;
    private double[] subValues;

    GcEvent() {
    }

    /** 测试与分析器用：构造一个带堆数据的停顿。 */
    static GcEvent pause(double time, String type, Category category, String cause, double durationMs,
                         long heapBeforeKb, long heapAfterKb, long heapCommittedKb) {
        GcEvent event = new GcEvent();
        event.time = time;
        event.uptime = time;
        event.type = type;
        event.category = category;
        event.cause = cause;
        event.durationMs = durationMs;
        event.heapBeforeKb = heapBeforeKb;
        event.heapAfterKb = heapAfterKb;
        event.heapCommittedKb = heapCommittedKb;
        event.applyCauseFlags();
        return event;
    }

    void addFlag(Flag flag) {
        if (flags == null) {
            flags = EnumSet.noneOf(Flag.class);
        }
        flags.add(flag);
    }

    void addFlags(Set<Flag> more) {
        if (more != null) {
            for (Flag flag : more) {
                addFlag(flag);
            }
        }
    }

    void putSubPhase(String name, double ms) {
        if (subNames == null) {
            subNames = new String[]{name};
            subValues = new double[]{ms};
            return;
        }
        for (int i = 0; i < subNames.length; i++) {
            if (subNames[i].equals(name)) {
                subValues[i] += ms;
                return;
            }
        }
        subNames = Arrays.copyOf(subNames, subNames.length + 1);
        subValues = Arrays.copyOf(subValues, subValues.length + 1);
        subNames[subNames.length - 1] = name;
        subValues[subValues.length - 1] = ms;
    }

    void putSubPhases(Map<String, Double> phases) {
        if (phases != null) {
            phases.forEach(this::putSubPhase);
        }
    }

    /** 由触发原因推导的标记：显式 GC、元空间触发、巨型对象分配。 */
    void applyCauseFlags() {
        if (cause == null) {
            return;
        }
        // 只认 System.gc()：jcmd GC.run（Diagnostic Command）是运维主动触发，DisableExplicitGC 管不到它。
        if (cause.startsWith("System.gc")) {
            addFlag(Flag.EXPLICIT);
        }
        if (cause.startsWith("Metadata GC") || cause.contains("Metaspace")) {
            addFlag(Flag.METADATA);
        }
        if (cause.contains("Humongous")) {
            addFlag(Flag.HUMONGOUS);
        }
    }

    public long gcId() {
        return gcId;
    }

    /** 统一时间轴上的秒数（跨 JVM 重启已接续）；没有时间戳时为 NaN。 */
    public double time() {
        return time;
    }

    public double uptime() {
        return uptime;
    }

    public String date() {
        return date;
    }

    public String type() {
        return type;
    }

    public Category category() {
        return category;
    }

    public String cause() {
        return cause;
    }

    /** true 为 STW 停顿；false 为并发周期快照（只贡献堆数据，不计入停顿时间）。 */
    public boolean isPause() {
        return pause;
    }

    public boolean isFullHeapCycle() {
        return fullHeapCycle;
    }

    public double durationMs() {
        return durationMs;
    }

    public long heapBeforeKb() {
        return heapBeforeKb;
    }

    public long heapAfterKb() {
        return heapAfterKb;
    }

    public long heapCommittedKb() {
        return heapCommittedKb;
    }

    public long youngBeforeKb() {
        return youngBeforeKb;
    }

    public long youngAfterKb() {
        return youngAfterKb;
    }

    public long metaBeforeKb() {
        return metaBeforeKb;
    }

    public long metaAfterKb() {
        return metaAfterKb;
    }

    public double userSec() {
        return userSec;
    }

    public double sysSec() {
        return sysSec;
    }

    public double realSec() {
        return realSec;
    }

    /** 事件所在的源行号（从 1 开始）。 */
    public long line() {
        return line;
    }

    public boolean hasHeap() {
        return heapBeforeKb >= 0 && heapAfterKb >= 0;
    }

    public boolean has(Flag flag) {
        return flags != null && flags.contains(flag);
    }

    public Set<Flag> flags() {
        return flags == null ? Set.of() : Collections.unmodifiableSet(flags);
    }

    /** 停顿内的子阶段耗时（毫秒），如 G1 的 Evacuate Collection Set；按日志出现顺序。 */
    public Map<String, Double> subPhases() {
        if (subNames == null) {
            return Map.of();
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < subNames.length; i++) {
            result.put(subNames[i], subValues[i]);
        }
        return Collections.unmodifiableMap(result);
    }
}
