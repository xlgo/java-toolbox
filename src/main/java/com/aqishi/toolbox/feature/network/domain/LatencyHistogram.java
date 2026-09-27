package com.aqishi.toolbox.feature.network.domain;

import java.util.Arrays;

/**
 * 紧凑的对数-线性延迟直方图（思路同 HdrHistogram，按需裁剪）。
 *
 * <p>取值单位为微秒。{@code [0, 64)} 区间每个整数一个桶，精确记录；更大的值按 2 的幂分段，
 * 每段 {@code [2^e, 2^(e+1))} 再均分成 64 个等宽子桶。子桶宽度与下界之比不超过 1/64，
 * 报告时取子桶中点，因此百分位的<em>相对误差不超过 1/128（约 0.78%）</em>。
 * 覆盖到 {@link #MAX_TRACKABLE_MICROS}（2^32 µs，约 71 分钟），足以容纳 1 小时的请求；
 * 超出的值落进最高桶，但 {@link #max()} 仍是精确值。</p>
 *
 * <p>总共 1728 个 long 桶（约 14 KB），计数、最小值、最大值、均值、标准差都精确累计，
 * 不受分桶影响。合并（{@link #merge}）只是逐桶相加，所以可以让每个工作分片各记一份、
 * 快照时再汇总，记录路径上不需要全局锁。</p>
 *
 * <p>本类<em>不是</em>线程安全的：并发记录由调用方加锁或分片。</p>
 */
public final class LatencyHistogram {

    /** 每个 2 的幂分段内的子桶位数：2^6 = 64 个子桶。 */
    private static final int SUB_BUCKET_BITS = 6;
    private static final int SUB_BUCKET_COUNT = 1 << SUB_BUCKET_BITS;
    private static final int MAX_EXPONENT = 31;

    /** 能区分的最大值（不含），更大的值计入最高桶。 */
    public static final long MAX_TRACKABLE_MICROS = 1L << (MAX_EXPONENT + 1);

    /** 最坏情况下百分位的相对误差上界（取桶中点时）。 */
    public static final double MAX_RELATIVE_ERROR = 1.0 / (2 * SUB_BUCKET_COUNT);

    private static final int BUCKET_COUNT =
            SUB_BUCKET_COUNT + (MAX_EXPONENT - SUB_BUCKET_BITS + 1) * SUB_BUCKET_COUNT;

    private final long[] counts;
    private long totalCount;
    private long min = Long.MAX_VALUE;
    private long max = Long.MIN_VALUE;
    private double sum;
    private double sumOfSquares;

    public LatencyHistogram() {
        this.counts = new long[BUCKET_COUNT];
    }

    private LatencyHistogram(LatencyHistogram source) {
        this.counts = source.counts.clone();
        this.totalCount = source.totalCount;
        this.min = source.min;
        this.max = source.max;
        this.sum = source.sum;
        this.sumOfSquares = source.sumOfSquares;
    }

    /** 记录一个取值（微秒）；负数按 0 处理——时钟回拨不应让统计崩掉。 */
    public void record(long micros) {
        long value = Math.max(0L, micros);
        counts[indexOf(value)]++;
        totalCount++;
        if (value < min) {
            min = value;
        }
        if (value > max) {
            max = value;
        }
        sum += value;
        sumOfSquares += (double) value * value;
    }

    /** 把另一份直方图逐桶累加进来。 */
    public void merge(LatencyHistogram other) {
        if (other == null || other.totalCount == 0) {
            return;
        }
        for (int i = 0; i < BUCKET_COUNT; i++) {
            counts[i] += other.counts[i];
        }
        totalCount += other.totalCount;
        min = Math.min(min, other.min);
        max = Math.max(max, other.max);
        sum += other.sum;
        sumOfSquares += other.sumOfSquares;
    }

    public LatencyHistogram copy() {
        return new LatencyHistogram(this);
    }

    public void reset() {
        Arrays.fill(counts, 0L);
        totalCount = 0;
        min = Long.MAX_VALUE;
        max = Long.MIN_VALUE;
        sum = 0;
        sumOfSquares = 0;
    }

    public long count() {
        return totalCount;
    }

    public boolean isEmpty() {
        return totalCount == 0;
    }

    /** 最小值（微秒），无数据时为 0。 */
    public long min() {
        return totalCount == 0 ? 0L : min;
    }

    /** 最大值（微秒），无数据时为 0。 */
    public long max() {
        return totalCount == 0 ? 0L : max;
    }

    public double mean() {
        return totalCount == 0 ? 0.0 : sum / totalCount;
    }

    /** 总体标准差（除以 n，与 wrk 的 Stdev 口径一致）。 */
    public double stddev() {
        if (totalCount == 0) {
            return 0.0;
        }
        double mean = sum / totalCount;
        double variance = sumOfSquares / totalCount - mean * mean;
        return variance <= 0 ? 0.0 : Math.sqrt(variance);
    }

    /**
     * 第 {@code percentile} 百分位的值（微秒）。
     *
     * <p>采用 nearest-rank 口径：取排名 {@code ceil(p/100 * n)} 所在的桶，报告其中点，
     * 并收敛到 [min, max] 之内——这样 p100 恰好是精确的最大值，p0 是精确的最小值。</p>
     *
     * @param percentile 0 到 100
     */
    public long valueAtPercentile(double percentile) {
        if (totalCount == 0) {
            return 0L;
        }
        double p = Math.max(0.0, Math.min(100.0, percentile));
        if (p >= 100.0) {
            return max;
        }
        long rank = (long) Math.ceil(p / 100.0 * totalCount);
        if (rank < 1) {
            return min;
        }
        long seen = 0;
        for (int i = 0; i < BUCKET_COUNT; i++) {
            seen += counts[i];
            if (seen >= rank) {
                long representative = lowerBound(i) + bucketWidth(i) / 2;
                return Math.max(min, Math.min(max, representative));
            }
        }
        return max;
    }

    static int indexOf(long value) {
        if (value < SUB_BUCKET_COUNT) {
            return (int) value;
        }
        long capped = Math.min(value, MAX_TRACKABLE_MICROS - 1);
        int exponent = 63 - Long.numberOfLeadingZeros(capped);
        int shift = exponent - SUB_BUCKET_BITS;
        int sub = (int) ((capped >>> shift) & (SUB_BUCKET_COUNT - 1));
        return SUB_BUCKET_COUNT + shift * SUB_BUCKET_COUNT + sub;
    }

    static long lowerBound(int index) {
        if (index < SUB_BUCKET_COUNT) {
            return index;
        }
        int shift = (index - SUB_BUCKET_COUNT) / SUB_BUCKET_COUNT;
        int sub = (index - SUB_BUCKET_COUNT) % SUB_BUCKET_COUNT;
        return ((long) (SUB_BUCKET_COUNT + sub)) << shift;
    }

    static long bucketWidth(int index) {
        if (index < SUB_BUCKET_COUNT) {
            return 1L;
        }
        return 1L << ((index - SUB_BUCKET_COUNT) / SUB_BUCKET_COUNT);
    }
}
