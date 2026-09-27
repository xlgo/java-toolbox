package com.aqishi.toolbox.feature.monitor.infra;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-address limit on failed secure-channel handshakes.
 *
 * <p>A listener exposed through UPnP/NAT-PMP can be reached by anyone. After
 * {@link #DEFAULT_MAX_FAILURES} failed handshakes within the window an address
 * is refused (connections closed right after accept, before any crypto work)
 * until the block expires. This bounds online password guessing and the
 * PBKDF2/X25519 CPU cost an attacker can impose.</p>
 */
public final class HandshakeRateLimiter {

    public static final int DEFAULT_MAX_FAILURES = 5;
    public static final long DEFAULT_WINDOW_MS = 10 * 60_000L;
    public static final long DEFAULT_BLOCK_MS = 10 * 60_000L;
    private static final int MAX_TRACKED_ADDRESSES = 1024;

    private final int maxFailures;
    private final long windowMillis;
    private final long blockMillis;
    private final LongSupplier clock;
    private final Map<InetAddress, Deque<Long>> failures = new HashMap<>();
    private final Map<InetAddress, Long> blockedUntil = new HashMap<>();

    public HandshakeRateLimiter() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW_MS, DEFAULT_BLOCK_MS, System::currentTimeMillis);
    }

    public HandshakeRateLimiter(int maxFailures, long windowMillis, long blockMillis, LongSupplier clock) {
        this.maxFailures = maxFailures;
        this.windowMillis = windowMillis;
        this.blockMillis = blockMillis;
        this.clock = clock;
    }

    /** Whether a new handshake from {@code address} may start. Null addresses are allowed. */
    public synchronized boolean allow(InetAddress address) {
        if (address == null) return true;
        Long until = blockedUntil.get(address);
        if (until == null) return true;
        if (clock.getAsLong() >= until) {
            blockedUntil.remove(address);
            failures.remove(address);
            return true;
        }
        return false;
    }

    /** Records a failed handshake; returns true if the address is now blocked. */
    public synchronized boolean recordFailure(InetAddress address) {
        if (address == null) return false;
        long now = clock.getAsLong();
        prune(now);
        Deque<Long> times = failures.computeIfAbsent(address, ignored -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > windowMillis) times.removeFirst();
        if (times.size() >= maxFailures) {
            blockedUntil.put(address, now + blockMillis);
            return true;
        }
        return false;
    }

    /** A successful, consented session clears the address's history. */
    public synchronized void recordSuccess(InetAddress address) {
        if (address == null) return;
        failures.remove(address);
        blockedUntil.remove(address);
    }

    private void prune(long now) {
        if (failures.size() + blockedUntil.size() < MAX_TRACKED_ADDRESSES) return;
        blockedUntil.values().removeIf(until -> until <= now);
        Iterator<Map.Entry<InetAddress, Deque<Long>>> it = failures.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> times = it.next().getValue();
            if (times.isEmpty() || now - times.peekLast() > windowMillis) it.remove();
        }
        if (failures.size() >= MAX_TRACKED_ADDRESSES) failures.clear();
    }
}
