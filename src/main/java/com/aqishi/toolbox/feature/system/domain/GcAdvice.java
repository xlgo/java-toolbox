package com.aqishi.toolbox.feature.system.domain;

import java.util.List;

/**
 * 一条调优建议。领域层只给出代码与参数，文案由界面按代码本地化。
 *
 * @param code     建议代码
 * @param severity 严重程度
 * @param params   代入文案的参数，已按 {@link java.util.Locale#ROOT} 格式化为字符串
 */
public record GcAdvice(Code code, Severity severity, List<String> params) {

    public GcAdvice {
        params = List.copyOf(params);
    }

    /** 严重程度。 */
    public enum Severity {
        INFO, WARNING, CRITICAL
    }

    /** 建议代码；各自的参数顺序见注释。 */
    public enum Code {
        /** 疏散失败 / to-space exhausted：{次数}。 */
        EVACUATION_FAILURE,
        /** 晋升失败：{次数}。 */
        PROMOTION_FAILED,
        /** CMS 并发模式失败：{次数}。 */
        CONCURRENT_MODE_FAILURE,
        /** ZGC 分配停顿：{次数, 总时长 ms, 最长 ms}。 */
        ALLOCATION_STALL,
        /** Full GC 频繁：{次数, 每小时次数}。 */
        FREQUENT_FULL_GC,
        /** Shenandoah 退化 GC：{次数}。 */
        DEGENERATED_GC,
        /** 显式 System.gc()：{次数}。 */
        EXPLICIT_GC,
        /** G1 巨型对象分配：{次数, Region 大小}。 */
        HUMONGOUS_ALLOCATION,
        /** 元空间触发 GC：{次数}。 */
        METASPACE_GC,
        /** 停顿超过目标：{最长 ms, 目标 ms, 超标次数, p99 ms}。 */
        PAUSE_ABOVE_TARGET,
        /** 吞吐量偏低：{吞吐量 %, 阈值 %}。 */
        LOW_THROUGHPUT,
        /** 回收后堆底线持续上涨：{斜率 MB/h, 增幅 %, 点数}。 */
        HEAP_FLOOR_RISING,
        /** 到达安全点耗时过长：{最长 ms, 操作名, 超标次数}。 */
        LONG_TIME_TO_SAFEPOINT
    }
}
