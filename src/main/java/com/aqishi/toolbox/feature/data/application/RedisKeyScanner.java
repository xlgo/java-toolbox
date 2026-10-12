package com.aqishi.toolbox.feature.data.application;

import redis.clients.jedis.ScanParams;
import redis.clients.jedis.ScanResult;

import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** A cursor session owned by one worker at a time. Excess SCAN results are kept for the next load. */
public final class RedisKeyScanner {
    public static final int DEFAULT_BATCH_SIZE = 1000;
    public static final int MAX_BATCH_SIZE = 10_000;
    private final String pattern;
    private final int maxCalls;
    private final TreeSet<String> keys = new TreeSet<>();
    private final Deque<String> pending = new ArrayDeque<>();
    private String cursor = ScanParams.SCAN_POINTER_START;
    private boolean exhausted;

    @FunctionalInterface public interface Source {
        ScanResult<String> scan(String cursor, ScanParams params);
    }
    public record Page(List<String> keys, boolean complete) {
        public boolean hasMore() { return !complete; }
    }

    public RedisKeyScanner(String pattern) { this(pattern, 16); }

    public RedisKeyScanner(String pattern, int maxCalls) {
        if (maxCalls < 1) throw new IllegalArgumentException("maxCalls must be positive");
        this.pattern = pattern == null || pattern.isBlank() ? "*" : pattern;
        this.maxCalls = maxCalls;
    }

    public Page load(Source source, BooleanSupplier cancelled) {
        return load(source, cancelled, DEFAULT_BATCH_SIZE);
    }

    public Page load(Source source, BooleanSupplier cancelled, int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE)
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAX_BATCH_SIZE);
        int added = 0, calls = 0;
        ScanParams params = new ScanParams().match(pattern).count(Math.min(batchSize, 1000));
        while (added < batchSize) {
            checkCancelled(cancelled);
            while (!pending.isEmpty() && added < batchSize) {
                checkCancelled(cancelled);
                if (keys.add(pending.removeFirst())) added++;
            }
            if (added == batchSize || exhausted || calls == maxCalls) break;
            ScanResult<String> batch = source.scan(cursor, params);
            checkCancelled(cancelled);
            calls++;
            cursor = batch.getCursor();
            exhausted = ScanParams.SCAN_POINTER_START.equals(cursor);
            for (String key : new LinkedHashSet<>(batch.getResult())) {
                checkCancelled(cancelled);
                if (!keys.contains(key)) pending.addLast(key);
            }
        }
        return new Page(List.copyOf(keys), exhausted && pending.isEmpty());
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
}
