package com.aqishi.toolbox.feature.network.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * 压测进行中或结束时某一刻的统计视图。每次快照都是独立副本，可以放心跨线程传给界面。
 *
 * <p>只统计计量请求；预热请求只体现在 {@link #warmupCompleted()} 与进度里。
 * 延迟直方图只包含拿到了 HTTP 响应的请求（含 4xx/5xx 与断言失败）——连接被拒、超时这类
 * 传输层失败没有有意义的"延迟"，只计入错误分类。</p>
 */
public final class BenchSnapshot {

    /** 运行阶段。 */
    public enum Phase {
        /** 尚未开始。 */
        IDLE,
        /** 预热中，统计尚未开始。 */
        WARMUP,
        /** 计量中。 */
        RUNNING,
        /** 已结束（正常完成、被取消或失败，见 {@link BenchResult}）。 */
        FINISHED
    }

    private final Phase phase;
    private final long elapsedNanos;
    private final double progress;
    private final long completed;
    private final long errors;
    private final long warmupCompleted;
    private final int inFlight;
    private final int maxInFlight;
    private final long bytesReceived;
    private final LatencyHistogram latency;
    private final SortedMap<Integer, Long> statusCounts;
    private final Map<BenchErrorKind, Long> errorCounts;
    private final List<BenchSecond> timeline;

    public BenchSnapshot(Phase phase, long elapsedNanos, double progress, long completed, long errors,
                         long warmupCompleted, int inFlight, int maxInFlight, long bytesReceived,
                         LatencyHistogram latency, Map<Integer, Long> statusCounts,
                         Map<BenchErrorKind, Long> errorCounts, List<BenchSecond> timeline) {
        this.phase = phase;
        this.elapsedNanos = Math.max(0L, elapsedNanos);
        this.progress = Math.max(0.0, Math.min(1.0, progress));
        this.completed = completed;
        this.errors = errors;
        this.warmupCompleted = warmupCompleted;
        this.inFlight = inFlight;
        this.maxInFlight = maxInFlight;
        this.bytesReceived = bytesReceived;
        this.latency = latency == null ? new LatencyHistogram() : latency;
        this.statusCounts = Collections.unmodifiableSortedMap(new TreeMap<>(statusCounts));
        EnumMap<BenchErrorKind, Long> errorsCopy = new EnumMap<>(BenchErrorKind.class);
        errorsCopy.putAll(errorCounts);
        this.errorCounts = Collections.unmodifiableMap(errorsCopy);
        this.timeline = List.copyOf(timeline);
    }

    /** 尚未开跑时的空快照。 */
    public static BenchSnapshot idle() {
        return new BenchSnapshot(Phase.IDLE, 0, 0, 0, 0, 0, 0, 0, 0, null,
                Map.of(), Map.of(), List.of());
    }

    public Phase phase() {
        return phase;
    }

    /** 计量开始至今（结束后为至最后一个请求完成）的纳秒数，预热期间为 0。 */
    public long elapsedNanos() {
        return elapsedNanos;
    }

    public double elapsedSeconds() {
        return elapsedNanos / 1_000_000_000.0;
    }

    /** 0 到 1 的整体进度（含预热）。 */
    public double progress() {
        return progress;
    }

    /** 已完成的计量请求数（成功 + 失败）。 */
    public long completed() {
        return completed;
    }

    public long errors() {
        return errors;
    }

    public long successes() {
        return completed - errors;
    }

    public long warmupCompleted() {
        return warmupCompleted;
    }

    public int inFlight() {
        return inFlight;
    }

    /** 整个运行期间同时在途请求数的峰值。 */
    public int maxInFlight() {
        return maxInFlight;
    }

    public long bytesReceived() {
        return bytesReceived;
    }

    /** 实际达到的吞吐（完成数 / 计量时长）。 */
    public double requestsPerSecond() {
        double seconds = elapsedSeconds();
        return seconds <= 0 ? 0.0 : completed / seconds;
    }

    public double bytesPerSecond() {
        double seconds = elapsedSeconds();
        return seconds <= 0 ? 0.0 : bytesReceived / seconds;
    }

    /** 失败占比，0 到 1。 */
    public double errorRate() {
        return completed == 0 ? 0.0 : (double) errors / completed;
    }

    /** 本快照独有的直方图副本；调用方不要修改。 */
    public LatencyHistogram latency() {
        return latency;
    }

    public SortedMap<Integer, Long> statusCounts() {
        return statusCounts;
    }

    public Map<BenchErrorKind, Long> errorCounts() {
        return errorCounts;
    }

    /** 已封口的逐秒统计，按秒序号升序；正在进行的最近一两秒尚未出现在这里。 */
    public List<BenchSecond> timeline() {
        return timeline;
    }
}
