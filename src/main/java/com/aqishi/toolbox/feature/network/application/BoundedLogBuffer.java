package com.aqishi.toolbox.feature.network.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe producer side plus a bounded, consumer-owned retention window for
 * a live message log.
 *
 * <p>Network threads call {@link #offer(Object)}; it never blocks and never
 * touches Swing. A single consumer (the EDT, driven by a Swing timer) calls
 * {@link #drain()} periodically and applies the returned {@link Flush} to its
 * view: append {@link Flush#added()} at the tail, then remove
 * {@link Flush#evictedCount()} entries from the head. The view therefore mirrors
 * {@link #retained()} exactly and never holds more than {@code maxRetained}
 * entries.</p>
 *
 * <p>Two bounds protect memory: the pending queue drops the newest entries once
 * {@code maxPending} are waiting (counted in {@link #droppedTotal()}), and the
 * retained window evicts the oldest entries in chunks of {@code trimChunk} once
 * it exceeds {@code maxRetained} (counted in {@link #trimmedTotal()}). Trimming
 * in chunks keeps the number of document/table edits low under a flood.</p>
 *
 * @param <T> entry type
 */
public final class BoundedLogBuffer<T> {

    public static final int DEFAULT_MAX_RETAINED = 5000;
    public static final int DEFAULT_MAX_PER_FLUSH = 1000;
    public static final int DEFAULT_MAX_PENDING = 20_000;

    private final int maxRetained;
    private final int trimChunk;
    private final int maxPerFlush;
    private final int maxPending;

    private final ConcurrentLinkedQueue<T> pending = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();
    private final AtomicLong droppedSinceDrain = new AtomicLong();
    private final ArrayDeque<T> retained = new ArrayDeque<>();
    private long droppedTotal;
    private long trimmedTotal;

    public BoundedLogBuffer() {
        this(DEFAULT_MAX_RETAINED, DEFAULT_MAX_PER_FLUSH, DEFAULT_MAX_PENDING);
    }

    public BoundedLogBuffer(int maxRetained, int maxPerFlush, int maxPending) {
        if (maxRetained < 1 || maxPerFlush < 1 || maxPending < 1) {
            throw new IllegalArgumentException("limits must be >= 1");
        }
        this.maxRetained = maxRetained;
        this.trimChunk = Math.max(1, maxRetained / 10);
        this.maxPerFlush = maxPerFlush;
        this.maxPending = maxPending;
    }

    /**
     * Queues an entry from any thread. Returns {@code false} when the pending
     * queue is full and the entry was dropped.
     */
    public boolean offer(T entry) {
        if (entry == null) {
            return false;
        }
        if (pendingCount.incrementAndGet() > maxPending) {
            pendingCount.decrementAndGet();
            droppedSinceDrain.incrementAndGet();
            return false;
        }
        pending.add(entry);
        return true;
    }

    /**
     * Moves up to {@code maxPerFlush} pending entries into the retained window
     * and reports what the view must change. Consumer thread only.
     */
    public Flush<T> drain() {
        List<T> added = new ArrayList<>();
        T entry;
        while (added.size() < maxPerFlush && (entry = pending.poll()) != null) {
            pendingCount.decrementAndGet();
            added.add(entry);
        }
        long dropped = droppedSinceDrain.getAndSet(0);
        droppedTotal += dropped;
        retained.addAll(added);
        int evicted = 0;
        if (retained.size() > maxRetained) {
            int target = Math.max(0, maxRetained - trimChunk);
            evicted = retained.size() - target;
            for (int i = 0; i < evicted; i++) {
                retained.pollFirst();
            }
            trimmedTotal += evicted;
        }
        return new Flush<>(Collections.unmodifiableList(added), evicted, dropped);
    }

    /** Forgets retained and pending entries and resets the counters. Consumer thread only. */
    public void clear() {
        T ignored;
        while ((ignored = pending.poll()) != null) {
            pendingCount.decrementAndGet();
        }
        droppedSinceDrain.set(0);
        retained.clear();
        droppedTotal = 0;
        trimmedTotal = 0;
    }

    /** Snapshot of what the view currently shows, oldest first. Consumer thread only. */
    public List<T> retained() {
        return new ArrayList<>(retained);
    }

    public int retainedSize() {
        return retained.size();
    }

    public int pendingSize() {
        return Math.max(0, pendingCount.get());
    }

    public long droppedTotal() {
        return droppedTotal;
    }

    public long trimmedTotal() {
        return trimmedTotal;
    }

    public int maxRetained() {
        return maxRetained;
    }

    /**
     * Result of one {@link #drain()}.
     *
     * @param added        entries to append, oldest first
     * @param evictedCount entries to remove from the head after appending
     * @param droppedCount entries dropped at the producer since the previous drain
     */
    public record Flush<T>(List<T> added, int evictedCount, long droppedCount) {
        public boolean isEmpty() {
            return added.isEmpty() && evictedCount == 0 && droppedCount == 0;
        }
    }
}
