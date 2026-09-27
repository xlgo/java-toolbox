package com.aqishi.toolbox.feature.system.domain;

import java.util.List;

/**
 * GC 日志的解析结果：事件、并发阶段、安全点与分配停顿，以及识别出的收集器和 JVM 版本族。
 *
 * @param collector      识别出的收集器
 * @param family         日志格式所属的 JVM 版本族
 * @param jvmVersion     日志里声明的 JVM 版本（JDK 17+ 的 gc,init 行或 JDK 8 的文件头），没有时为 null
 * @param events         按时间排序的事件（停顿与并发周期快照）
 * @param phases         并发阶段耗时
 * @param safepoints     安全点记录
 * @param stalls         ZGC 分配停顿
 * @param firstTime      时间轴起点（秒），没有时间戳时为 NaN
 * @param lastTime       时间轴终点（秒）
 * @param restarts       检测到的 JVM 重启次数（拼接了多次运行的日志）
 * @param regionSizeKb   G1 Region 大小，未知为 -1
 * @param totalLines     总行数
 * @param ignoredLines   认得但不参与分析的行（启动参数、明细表格等）
 * @param unparsedLines  完全不认识的行
 * @param unparsedSample 不认识的行的样本
 * @param truncated      输入超过上限被截断
 */
public record GcLog(Collector collector, Family family, String jvmVersion, List<GcEvent> events,
                    List<Phase> phases, List<Safepoint> safepoints, List<Stall> stalls,
                    double firstTime, double lastTime, int restarts, long regionSizeKb,
                    long totalLines, long ignoredLines, long unparsedLines, List<Unparsed> unparsedSample,
                    boolean truncated) {

    public GcLog {
        events = List.copyOf(events);
        phases = List.copyOf(phases);
        safepoints = List.copyOf(safepoints);
        stalls = List.copyOf(stalls);
        unparsedSample = List.copyOf(unparsedSample);
    }

    /** 垃圾收集器。 */
    public enum Collector {
        G1, PARALLEL, SERIAL, CMS, ZGC, ZGC_GENERATIONAL, SHENANDOAH, EPSILON, UNKNOWN;

        /** 回收过程主要并发进行、Full GC 本应罕见的收集器。 */
        public boolean concurrent() {
            return this == G1 || this == CMS || this == ZGC || this == ZGC_GENERATIONAL || this == SHENANDOAH;
        }
    }

    /** 日志格式族。 */
    public enum Family {
        /** JDK 8 及更早：-XX:+PrintGCDetails。 */
        JDK8_OR_EARLIER,
        /** JDK 9+ 统一日志：-Xlog:gc*。 */
        JDK9_PLUS,
        UNKNOWN
    }

    /**
     * 并发阶段（不停顿应用线程）。
     *
     * @param gcId       GC 编号，JDK 8 为 -1
     * @param time       结束时刻（秒）
     * @param name       阶段名，如 {@code Concurrent Mark}、{@code CMS-concurrent-mark}
     * @param durationMs 耗时
     */
    public record Phase(long gcId, double time, String name, double durationMs) {
    }

    /**
     * 安全点。JDK 17+ 的 {@code Safepoint "op", ...} 行有完整分段；JDK 8-16 的
     * {@code Total time for which application threads were stopped} 只有总停顿与到达耗时，操作名为 null。
     *
     * @param time       时刻（秒）
     * @param operation  VM 操作名，未知为 null
     * @param reachMs    到达安全点耗时（time-to-safepoint），未知为 NaN
     * @param atMs       安全点内耗时，未知为 NaN
     * @param totalMs    总停顿
     */
    public record Safepoint(double time, String operation, double reachMs, double atMs, double totalMs) {
    }

    /**
     * ZGC 分配停顿：应用线程等待 GC 腾出内存。
     *
     * @param time       时刻（秒）
     * @param thread     被阻塞的线程名
     * @param durationMs 阻塞时长
     */
    public record Stall(double time, String thread, double durationMs) {
    }

    /**
     * 不认识的行。
     *
     * @param lineNumber 行号（从 1 开始）
     * @param text       行内容（过长时截断）
     */
    public record Unparsed(long lineNumber, String text) {
    }

    public boolean hasTimestamps() {
        return Double.isFinite(firstTime) && Double.isFinite(lastTime);
    }

    /** 日志覆盖的时长（秒），没有时间戳时为 NaN。 */
    public double spanSeconds() {
        return hasTimestamps() ? Math.max(0, lastTime - firstTime) : Double.NaN;
    }
}
