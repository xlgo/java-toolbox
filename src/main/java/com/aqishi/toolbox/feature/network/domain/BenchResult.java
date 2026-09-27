package com.aqishi.toolbox.feature.network.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次压测的最终结果：参数、结束方式与最后一份快照。
 */
public final class BenchResult {

    /** 结束方式。 */
    public enum Outcome {
        /** 达到停止条件正常结束。 */
        COMPLETED,
        /** 用户取消或面板关闭。 */
        CANCELLED,
        /** 运行器自身出错（不是请求失败——请求失败只计入错误统计）。 */
        FAILED
    }

    private final BenchPlan plan;
    private final Outcome outcome;
    private final BenchSnapshot snapshot;
    private final Instant startedAt;
    private final String failure;

    public BenchResult(BenchPlan plan, Outcome outcome, BenchSnapshot snapshot,
                       Instant startedAt, String failure) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
        this.failure = failure == null ? "" : failure;
    }

    public BenchPlan plan() {
        return plan;
    }

    public Outcome outcome() {
        return outcome;
    }

    public BenchSnapshot snapshot() {
        return snapshot;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** {@link Outcome#FAILED} 时的英文原因，其余情况为空串。 */
    public String failure() {
        return failure;
    }
}
