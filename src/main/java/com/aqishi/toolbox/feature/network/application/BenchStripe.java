package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.BenchErrorKind;
import com.aqishi.toolbox.feature.network.domain.LatencyHistogram;

import java.util.Map;
import java.util.TreeMap;

/**
 * 统计分片：一组工作线程共用一个分片，分片内部用自身的锁保护。
 *
 * <p>为什么不是一个全局记录器：上千个工作线程每完成一个请求都抢同一把锁，锁本身就会成为
 * 压测端的瓶颈，测出来的是锁而不是服务端。也不是每线程一份：一份直方图约 14 KB，
 * 一千个线程再乘上逐秒桶就太重了。固定数量的分片折中了两者，快照时逐个加锁合并。</p>
 *
 * <p>逐秒桶以<em>在锁内读取的</em>当前时刻归秒，这样快照只要在读取"当前秒"之后再逐个加锁，
 * 就能保证比"当前秒"更早的秒不会再有新记录进来，可以安全封口。</p>
 */
final class BenchStripe {

    /** 状态码按下标计数；越界的（理论上不会出现）计入 0。 */
    private static final int STATUS_SLOTS = 1000;

    private final LatencyHistogram latency = new LatencyHistogram();
    private final long[] statusCounts = new long[STATUS_SLOTS];
    private final long[] errorCounts = new long[BenchErrorKind.values().length];
    private final TreeMap<Long, SecondAccumulator> openSeconds = new TreeMap<>();
    private long completed;
    private long errors;
    private long bytes;
    private long lastEndNanos;

    /**
     * 记录一个拿到响应的计量请求。
     *
     * @param error 断言失败或状态码不符时的类别，成功为 null
     */
    synchronized void recordResponse(long latencyMicros, int status, long bodyBytes,
                                     BenchErrorKind error, long measureStartNanos) {
        long now = System.nanoTime();
        latency.record(latencyMicros);
        statusCounts[status >= 0 && status < STATUS_SLOTS ? status : 0]++;
        completed++;
        bytes += bodyBytes;
        if (error != null) {
            errors++;
            errorCounts[error.ordinal()]++;
        }
        lastEndNanos = Math.max(lastEndNanos, now);
        SecondAccumulator second = secondFor(now, measureStartNanos);
        second.requests++;
        second.bytes += bodyBytes;
        second.latency().record(latencyMicros);
        if (error != null) {
            second.errors++;
        }
    }

    /** 记录一个没有拿到响应的计量请求（连接失败、超时等）。 */
    synchronized void recordFailure(BenchErrorKind kind, long measureStartNanos) {
        long now = System.nanoTime();
        completed++;
        errors++;
        errorCounts[kind.ordinal()]++;
        lastEndNanos = Math.max(lastEndNanos, now);
        SecondAccumulator second = secondFor(now, measureStartNanos);
        second.requests++;
        second.errors++;
    }

    private SecondAccumulator secondFor(long now, long measureStartNanos) {
        long second = Math.max(0L, (now - measureStartNanos) / 1_000_000_000L);
        return openSeconds.computeIfAbsent(second, key -> new SecondAccumulator());
    }

    /**
     * 把累计数据合并进 {@code total}，并把序号小于 {@code sealBefore} 的逐秒桶移交给 {@code sealed}。
     */
    synchronized void drainInto(Totals total, long sealBefore, Map<Long, SecondAccumulator> sealed) {
        total.latency.merge(latency);
        for (int i = 0; i < STATUS_SLOTS; i++) {
            total.statusCounts[i] += statusCounts[i];
        }
        for (int i = 0; i < errorCounts.length; i++) {
            total.errorCounts[i] += errorCounts[i];
        }
        total.completed += completed;
        total.errors += errors;
        total.bytes += bytes;
        total.lastEndNanos = Math.max(total.lastEndNanos, lastEndNanos);
        while (!openSeconds.isEmpty() && openSeconds.firstKey() < sealBefore) {
            Map.Entry<Long, SecondAccumulator> entry = openSeconds.pollFirstEntry();
            sealed.merge(entry.getKey(), entry.getValue(), SecondAccumulator::mergeFrom);
        }
    }

    /** 快照期间汇总各分片的可变容器，只在快照锁内使用。 */
    static final class Totals {
        final LatencyHistogram latency = new LatencyHistogram();
        final long[] statusCounts = new long[STATUS_SLOTS];
        final long[] errorCounts = new long[BenchErrorKind.values().length];
        long completed;
        long errors;
        long bytes;
        long lastEndNanos;
    }

    /** 一秒内的累计；直方图懒创建，全是传输失败的秒不必分配。 */
    static final class SecondAccumulator {
        long requests;
        long errors;
        long bytes;
        private LatencyHistogram latency;

        LatencyHistogram latency() {
            if (latency == null) {
                latency = new LatencyHistogram();
            }
            return latency;
        }

        boolean hasLatency() {
            return latency != null && !latency.isEmpty();
        }

        SecondAccumulator mergeFrom(SecondAccumulator other) {
            requests += other.requests;
            errors += other.errors;
            bytes += other.bytes;
            if (other.hasLatency()) {
                latency().merge(other.latency);
            }
            return this;
        }
    }
}
