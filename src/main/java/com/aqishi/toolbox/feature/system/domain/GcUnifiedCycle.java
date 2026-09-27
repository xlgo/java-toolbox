package com.aqishi.toolbox.feature.system.domain;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 统一日志里同一个 {@code GC(n)} 编号的累积状态。
 *
 * <p>G1 的 gc,start 行给出原因与开始时刻，随后的 gc,phases / gc,heap / gc,metaspace 明细在汇总行之前，
 * gc,cpu 在汇总行之后；ZGC 的周期汇总在各个 Pause 之后；Shenandoah 的堆数值散落在各个并发阶段行上。
 * 这些都先记在这里，到汇总行（或周期结束）时再组装成事件。</p>
 */
final class GcUnifiedCycle {

    final long id;
    /** gc,start 或 ZGC 周期起始行给出的原因。 */
    String cause;
    /** Shenandoah 在周期开始前打印的 {@code Trigger: ...}。 */
    String trigger;

    /** gc,start 行的停顿类型；汇总行类型相同时用它的时间作为停顿开始时刻。 */
    String startType;
    double startTime = Double.NaN;
    double startUptime = Double.NaN;
    String startDate;

    /** ZGC 周期起点。 */
    double cycleStart = Double.NaN;
    double cycleStartUptime = Double.NaN;
    String cycleStartDate;
    long capacityKb = -1;

    int edenBefore = -1;
    int edenAfter = -1;
    int survivorBefore = -1;
    int survivorAfter = -1;
    long youngBeforeKb = -1;
    long youngAfterKb = -1;
    long metaBeforeKb = -1;
    long metaAfterKb = -1;
    double userSec = Double.NaN;
    double sysSec = Double.NaN;
    double realSec = Double.NaN;
    private Map<String, Double> subPhases;
    private Set<GcEvent.Flag> flags;

    /** 本编号最近一次落地的停顿：汇总行之后的 gc,cpu 行挂到它上面。 */
    GcEvent last;

    /** Shenandoah：并发阶段行上的堆数值，第一次的回收前、最后一次的回收后。 */
    long cycleBeforeKb = -1;
    long cycleAfterKb = -1;
    long cycleCommittedKb = -1;
    double cycleTime = Double.NaN;
    double cycleUptime = Double.NaN;
    String cycleDate;
    long cycleLine;
    boolean pauseHadHeap;
    boolean summarized;

    GcUnifiedCycle(long id) {
        this.id = id;
    }

    void putSubPhase(String name, double ms) {
        if (subPhases == null) {
            subPhases = new LinkedHashMap<>();
        }
        subPhases.merge(name, ms, Double::sum);
    }

    void flag(GcEvent.Flag flag) {
        if (flags == null) {
            flags = EnumSet.noneOf(GcEvent.Flag.class);
        }
        flags.add(flag);
    }

    /** 把累积的明细交给刚落地的停顿，并清空，免得同编号的下一个停顿（Remark 之后的 Cleanup）继承。 */
    void drainInto(GcEvent event, long regionSizeKb) {
        if (youngBeforeKb >= 0) {
            event.youngBeforeKb = youngBeforeKb;
            event.youngAfterKb = youngAfterKb;
        } else if (edenBefore >= 0 && regionSizeKb > 0) {
            event.youngBeforeKb = (edenBefore + Math.max(0, survivorBefore)) * regionSizeKb;
            event.youngAfterKb = (Math.max(0, edenAfter) + Math.max(0, survivorAfter)) * regionSizeKb;
        }
        if (metaBeforeKb >= 0) {
            event.metaBeforeKb = metaBeforeKb;
            event.metaAfterKb = metaAfterKb;
        }
        if (!Double.isNaN(userSec)) {
            event.userSec = userSec;
            event.sysSec = sysSec;
            event.realSec = realSec;
        }
        event.putSubPhases(subPhases);
        event.addFlags(flags);
        edenBefore = edenAfter = survivorBefore = survivorAfter = -1;
        youngBeforeKb = youngAfterKb = metaBeforeKb = metaAfterKb = -1;
        userSec = sysSec = realSec = Double.NaN;
        subPhases = null;
        flags = null;
        startType = null;
    }

    void observeCycleHeap(GcText.Heap heap, GcParseState.Stamp stamp, long line) {
        if (cycleBeforeKb < 0) {
            cycleBeforeKb = heap.beforeKb();
        }
        cycleAfterKb = heap.afterKb();
        if (heap.committedKb() >= 0) {
            cycleCommittedKb = heap.committedKb();
        }
        cycleTime = stamp.time;
        cycleUptime = stamp.uptime;
        cycleDate = stamp.date;
        cycleLine = line;
    }
}
