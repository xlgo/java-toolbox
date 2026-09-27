package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyHistogramTest {

    private static final double[] PERCENTILES = {50, 75, 90, 95, 99, 99.9};

    @Test
    void emptyHistogramReportsZeros() {
        LatencyHistogram histogram = new LatencyHistogram();

        assertEquals(0, histogram.count());
        assertEquals(0, histogram.min());
        assertEquals(0, histogram.max());
        assertEquals(0.0, histogram.mean());
        assertEquals(0, histogram.valueAtPercentile(99));
    }

    @Test
    void smallValuesAreExact() {
        LatencyHistogram histogram = new LatencyHistogram();
        for (int value = 1; value <= 100; value++) {
            histogram.record(value);
        }

        assertEquals(50, histogram.valueAtPercentile(50));
        assertEquals(1, histogram.min());
        assertEquals(100, histogram.max());
        assertEquals(50.5, histogram.mean(), 1e-9);
        assertEquals(Math.sqrt((100.0 * 100 - 1) / 12), histogram.stddev(), 1e-6);
        assertEquals(100, histogram.valueAtPercentile(100));
        assertEquals(1, histogram.valueAtPercentile(0));
    }

    @Test
    void uniformDistributionPercentilesWithinStatedError() {
        LatencyHistogram histogram = new LatencyHistogram();
        long[] values = new long[200_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = 1_000 + i * 10L; // 1 ms .. 3 s
            histogram.record(values[i]);
        }
        assertPercentilesClose(histogram, values);
    }

    @Test
    void logNormalDistributionPercentilesWithinStatedError() {
        Random random = new Random(42);
        LatencyHistogram histogram = new LatencyHistogram();
        long[] values = new long[100_000];
        for (int i = 0; i < values.length; i++) {
            values[i] = Math.max(1, (long) Math.exp(8 + 1.5 * random.nextGaussian()));
            histogram.record(values[i]);
        }
        assertPercentilesClose(histogram, values);
    }

    @Test
    void coversOneMicrosecondToOneHour() {
        long oneHour = 3_600_000_000L;
        for (long value : new long[]{1, 7, 63, 64, 65, 127, 128, 1_000, 999_999, 1_234_567, oneHour}) {
            // 夹在更小与更大的值之间，避免中位数被 [min, max] 收敛成精确值而掩盖分桶误差
            LatencyHistogram histogram = new LatencyHistogram();
            histogram.record(0);
            histogram.record(value);
            histogram.record(LatencyHistogram.MAX_TRACKABLE_MICROS * 2);
            long reported = histogram.valueAtPercentile(50);
            double error = Math.abs(reported - value) / (double) value;
            assertTrue(error <= LatencyHistogram.MAX_RELATIVE_ERROR,
                    value + " reported as " + reported);
        }
        assertTrue(LatencyHistogram.MAX_RELATIVE_ERROR < 0.01);
    }

    @Test
    void bucketBoundariesAreContiguous() {
        long expectedLower = 0;
        int last = LatencyHistogram.indexOf(LatencyHistogram.MAX_TRACKABLE_MICROS - 1);
        for (int index = 0; index <= last; index++) {
            assertEquals(expectedLower, LatencyHistogram.lowerBound(index), "bucket " + index);
            assertEquals(index, LatencyHistogram.indexOf(expectedLower));
            expectedLower += LatencyHistogram.bucketWidth(index);
        }
        assertEquals(LatencyHistogram.MAX_TRACKABLE_MICROS, expectedLower);
    }

    @Test
    void mergeEqualsRecordingEverythingInOne() {
        LatencyHistogram left = new LatencyHistogram();
        LatencyHistogram right = new LatencyHistogram();
        LatencyHistogram all = new LatencyHistogram();
        Random random = new Random(7);
        for (int i = 0; i < 10_000; i++) {
            long value = 1 + random.nextInt(5_000_000);
            (i % 2 == 0 ? left : right).record(value);
            all.record(value);
        }
        LatencyHistogram merged = left.copy();
        merged.merge(right);

        assertEquals(all.count(), merged.count());
        assertEquals(all.min(), merged.min());
        assertEquals(all.max(), merged.max());
        assertEquals(all.mean(), merged.mean(), 1e-6);
        assertEquals(all.stddev(), merged.stddev(), 1e-3);
        for (double p : PERCENTILES) {
            assertEquals(all.valueAtPercentile(p), merged.valueAtPercentile(p));
        }
        assertEquals(5_000, left.count(), "copy must not alias the source");
    }

    @Test
    void negativeValuesClampToZeroAndHugeValuesKeepExactMax() {
        LatencyHistogram histogram = new LatencyHistogram();
        histogram.record(-5);
        histogram.record(LatencyHistogram.MAX_TRACKABLE_MICROS * 4);

        assertEquals(0, histogram.min());
        assertEquals(LatencyHistogram.MAX_TRACKABLE_MICROS * 4, histogram.max());
        assertEquals(histogram.max(), histogram.valueAtPercentile(100));
    }

    private static void assertPercentilesClose(LatencyHistogram histogram, long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        assertEquals(sorted[0], histogram.min());
        assertEquals(sorted[sorted.length - 1], histogram.max());
        double mean = Arrays.stream(sorted).average().orElse(0);
        assertEquals(mean, histogram.mean(), mean * 1e-9);
        for (double p : PERCENTILES) {
            int rank = (int) Math.ceil(p / 100.0 * sorted.length);
            long exact = sorted[Math.max(0, rank - 1)];
            long reported = histogram.valueAtPercentile(p);
            double error = Math.abs(reported - exact) / (double) exact;
            assertTrue(error <= 0.01, "p" + p + ": exact " + exact + ", reported " + reported);
        }
    }
}
