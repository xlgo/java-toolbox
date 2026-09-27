package com.aqishi.toolbox.feature.system.domain;

import java.util.Arrays;
import java.util.List;

/**
 * GC 日志的分析结果。所有耗时单位为毫秒，容量单位为 KB，无法计算的数值为 NaN。
 *
 * @param log                    解析结果
 * @param pauseTargetMs          停顿目标
 * @param spanSeconds            日志覆盖时长
 * @param pauses                 全部 STW 停顿的统计
 * @param byType                 按停顿类型的统计，按总耗时降序
 * @param causes                 按触发原因的统计，按次数降序
 * @param phases                 并发阶段与停顿内子阶段的统计
 * @param throughput             吞吐量 = 1 - 停顿总时长 / 日志时长，取值 0..1
 * @param pausesPerMinute        每分钟停顿次数
 * @param allocationRateKbPerSec 分配速率
 * @param promotionRateKbPerSec  晋升速率（年轻代回收时老年代的增量）
 * @param fullGcCount            Full GC 次数
 * @param longestPauses          最长的若干次停顿，按耗时降序
 * @param heapTrend              回收后堆底线的趋势，数据不足时为 null
 * @param safepoints             安全点统计，日志里没有时为 null
 * @param stalls                 ZGC 分配停顿统计，没有时为 null
 * @param advice                 调优建议，按严重程度降序
 */
public record GcReport(GcLog log, double pauseTargetMs, double spanSeconds, PauseStats pauses,
                       List<TypeStats> byType, List<CauseStats> causes, List<PhaseStats> phases,
                       double throughput, double pausesPerMinute, double allocationRateKbPerSec,
                       double promotionRateKbPerSec, int fullGcCount, List<GcEvent> longestPauses,
                       HeapTrend heapTrend, SafepointStats safepoints, StallStats stalls, List<GcAdvice> advice) {

    public GcReport {
        byType = List.copyOf(byType);
        causes = List.copyOf(causes);
        phases = List.copyOf(phases);
        longestPauses = List.copyOf(longestPauses);
        advice = List.copyOf(advice);
    }

    GcReport withAdvice(List<GcAdvice> newAdvice) {
        return new GcReport(log, pauseTargetMs, spanSeconds, pauses, byType, causes, phases, throughput,
                pausesPerMinute, allocationRateKbPerSec, promotionRateKbPerSec, fullGcCount, longestPauses,
                heapTrend, safepoints, stalls, newAdvice);
    }

    /**
     * 一组耗时的统计。百分位用最近秩法（nearest-rank）：p99 就是排序后第 ⌈0.99·n⌉ 个值，
     * 一定是真实出现过的停顿，不会插值出日志里没有的数。
     */
    public record PauseStats(int count, double totalMs, double avgMs, double minMs, double maxMs, double p50,
                             double p90, double p95, double p99, double p999) {

        public static final PauseStats EMPTY = new PauseStats(0, 0, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

        public static PauseStats of(double[] values) {
            double[] finite = Arrays.stream(values).filter(Double::isFinite).sorted().toArray();
            if (finite.length == 0) {
                return EMPTY;
            }
            double total = 0;
            for (double value : finite) {
                total += value;
            }
            return new PauseStats(finite.length, total, total / finite.length, finite[0], finite[finite.length - 1],
                    percentile(finite, 0.50), percentile(finite, 0.90), percentile(finite, 0.95),
                    percentile(finite, 0.99), percentile(finite, 0.999));
        }

        /** @param sorted 升序且非空 */
        static double percentile(double[] sorted, double quantile) {
            int rank = (int) Math.ceil(quantile * sorted.length);
            return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))];
        }
    }

    /** 按停顿类型的统计。 */
    public record TypeStats(String type, GcEvent.Category category, PauseStats stats) {
    }

    /**
     * 按触发原因的统计。
     *
     * @param cause        原因，日志没给出时为 null
     * @param collections  回收次数（同一 GC 编号的多个停顿只算一次）
     * @param fullCount    其中 Full GC 次数
     * @param totalPauseMs 停顿总时长
     * @param maxPauseMs   最长停顿
     */
    public record CauseStats(String cause, int collections, int fullCount, double totalPauseMs, double maxPauseMs) {
    }

    /** 阶段种类。 */
    public enum PhaseKind {
        /** 与应用线程并发执行。 */
        CONCURRENT,
        /** STW 停顿内部的子阶段。 */
        PAUSE_STEP
    }

    /** 阶段统计。 */
    public record PhaseStats(String name, PhaseKind kind, int count, double totalMs, double avgMs, double maxMs) {
    }

    /** 趋势取点方式。 */
    public enum TrendBasis {
        /** Full GC、混合回收与整堆并发周期之后的堆大小：最接近存活对象量。 */
        FLOOR_EVENTS,
        /** 这类事件太少时，把时间轴等分成若干窗口，取每个窗口里回收后堆的最小值。 */
        WINDOW_MINIMA
    }

    /**
     * 回收后堆底线的线性回归。
     *
     * @param basis           取点方式
     * @param points          参与回归的点数
     * @param slopeKbPerHour  斜率
     * @param r2              决定系数，越接近 1 趋势越稳定
     * @param startTime       首个点的时刻（秒）
     * @param endTime         末个点的时刻（秒）
     * @param startKb         回归线在首点时刻的值
     * @param endKb           回归线在末点时刻的值
     * @param growthPercent   回归线首尾差占参考容量（最大提交量）的百分比
     * @param rising          是否判定为持续上涨（疑似泄漏）
     */
    public record HeapTrend(TrendBasis basis, int points, double slopeKbPerHour, double r2, double startTime,
                            double endTime, double startKb, double endKb, double growthPercent, boolean rising) {
    }

    /** 安全点统计。 */
    public record SafepointStats(int count, double totalMs, PauseStats reach, String maxReachOperation,
                                 List<OperationStats> operations) {
        public SafepointStats {
            operations = List.copyOf(operations);
        }
    }

    /** 按 VM 操作的安全点统计；操作名未知（JDK 8-16 格式）时为 null。 */
    public record OperationStats(String operation, int count, double totalMs, double maxReachMs) {
    }

    /** ZGC 分配停顿统计。 */
    public record StallStats(int count, double totalMs, double maxMs) {
    }
}
