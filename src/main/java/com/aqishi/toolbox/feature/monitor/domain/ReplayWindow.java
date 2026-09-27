package com.aqishi.toolbox.feature.monitor.domain;

/**
 * Sliding anti-replay window over record sequence numbers (RFC 4303 / RFC 6347 style).
 *
 * <p>Used for the UDP transport, where records may be lost, duplicated or
 * reordered: any sequence number not seen before and no more than
 * {@link #SIZE} behind the highest authenticated one is accepted exactly
 * once. Callers must {@link #check} before decrypting and {@link #mark} only
 * after the record authenticated, so forged records cannot advance the
 * window.</p>
 */
public final class ReplayWindow {

    public static final int SIZE = 1024;
    private final long[] bits = new long[SIZE / 64];
    private long highest = -1;

    /** Whether {@code seq} would be accepted (not a replay, not too old). */
    public synchronized boolean check(long seq) {
        if (seq < 0) return false;
        if (seq > highest) return true;
        if (highest - seq >= SIZE) return false;
        return !isSet(seq);
    }

    /** Records {@code seq} as received. Only call after {@link #check} and authentication. */
    public synchronized void mark(long seq) {
        if (seq > highest) {
            long advance = seq - highest;
            if (advance >= SIZE) {
                java.util.Arrays.fill(bits, 0L);
            } else {
                for (long s = highest + 1; s < seq; s++) clear(s);
            }
            highest = seq;
            clear(seq);
        }
        set(seq);
    }

    public synchronized long highest() {
        return highest;
    }

    private boolean isSet(long seq) {
        int index = (int) (seq % SIZE);
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    private void set(long seq) {
        int index = (int) (seq % SIZE);
        bits[index >>> 6] |= 1L << (index & 63);
    }

    private void clear(long seq) {
        int index = (int) (seq % SIZE);
        bits[index >>> 6] &= ~(1L << (index & 63));
    }
}
