package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.BenchErrorKind;
import com.aqishi.toolbox.feature.network.domain.BenchSecond;
import com.aqishi.toolbox.feature.network.domain.BenchSnapshot;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 逐秒时间线：从各分片收回已封口的秒，合并后算出每秒的 p50/p99 并永久保存。
 *
 * <p>只保存算好的数字，不保存每秒的直方图——一小时的压测有 3600 秒，
 * 每秒留一份 14 KB 的直方图就是 50 MB。一秒内没有任何请求完成（服务端卡住）时补一个全零的点，
 * 图上能直接看到断档，而不是被相邻两点连线掩盖。</p>
 *
 * <p>非线程安全，调用方（{@link HttpBenchRunner}）以本对象为锁串行化快照。</p>
 */
final class BenchTimeline {

    private final TreeMap<Long, BenchStripe.SecondAccumulator> pending = new TreeMap<>();
    private final List<BenchSecond> seconds = new ArrayList<>();
    private long nextSecond;

    /**
     * 合并所有分片的累计数据，并封口序号小于 {@code sealBefore} 的秒。
     *
     * @param fillUntil 把时间线补齐到该秒（不含）；负数表示补到已收到的最大秒
     */
    void collect(BenchStripe[] stripes, BenchStripe.Totals totals, long sealBefore, long fillUntil) {
        for (BenchStripe stripe : stripes) {
            stripe.drainInto(totals, sealBefore, pending);
        }
        long until = fillUntil >= 0 ? fillUntil : (pending.isEmpty() ? nextSecond : pending.lastKey() + 1);
        while (nextSecond < until) {
            BenchStripe.SecondAccumulator entry = pending.remove(nextSecond);
            seconds.add(entry == null
                    ? new BenchSecond(nextSecond, 0, 0, 0, 0, 0)
                    : new BenchSecond(nextSecond, entry.requests, entry.errors, entry.bytes,
                    entry.hasLatency() ? entry.latency().valueAtPercentile(50) : 0L,
                    entry.hasLatency() ? entry.latency().valueAtPercentile(99) : 0L));
            nextSecond++;
        }
    }

    BenchSnapshot toSnapshot(BenchStripe.Totals totals, BenchSnapshot.Phase phase, long elapsedNanos,
                             double progress, long warmupCompleted, int inFlight, int maxInFlight) {
        Map<Integer, Long> statuses = new TreeMap<>();
        for (int status = 0; status < totals.statusCounts.length; status++) {
            if (totals.statusCounts[status] > 0) {
                statuses.put(status, totals.statusCounts[status]);
            }
        }
        Map<BenchErrorKind, Long> errors = new EnumMap<>(BenchErrorKind.class);
        for (BenchErrorKind kind : BenchErrorKind.values()) {
            long count = totals.errorCounts[kind.ordinal()];
            if (count > 0) {
                errors.put(kind, count);
            }
        }
        return new BenchSnapshot(phase, elapsedNanos, progress, totals.completed, totals.errors,
                warmupCompleted, inFlight, maxInFlight, totals.bytes, totals.latency,
                statuses, errors, seconds);
    }
}
