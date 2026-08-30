package com.aqishi.toolbox.infra.concurrency;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Named daemon-backed executors for background work owned by a panel.
 *
 * <p>Every thread created here is a daemon, so a pool that a panel forgot to
 * shut down can no longer keep the JVM alive after the window closes. Names
 * carry the owning component so a thread dump still points at the culprit.
 * Pools remain the owner's responsibility to close; this factory only removes
 * the "application never exits" failure mode.</p>
 */
public final class DaemonThreads {

    private DaemonThreads() {
    }

    public static ThreadFactory factory(String namePrefix) {
        if (namePrefix == null || namePrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("namePrefix is required");
        }
        return new NamedDaemonFactory(namePrefix);
    }

    public static ExecutorService single(String namePrefix) {
        return Executors.newSingleThreadExecutor(factory(namePrefix));
    }

    public static ExecutorService fixed(String namePrefix, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        return Executors.newFixedThreadPool(size, factory(namePrefix));
    }

    public static ScheduledExecutorService scheduled(String namePrefix) {
        return Executors.newSingleThreadScheduledExecutor(factory(namePrefix));
    }

    /**
     * Shuts a pool down without letting a failed close abort the caller's own
     * cleanup sequence.
     */
    public static void shutdownQuietly(java.util.concurrent.ExecutorService pool) {
        if (pool == null) {
            return;
        }
        try {
            pool.shutdownNow();
        } catch (RuntimeException ignored) {
            // Cleanup continues even if the pool rejects the interrupt.
        }
    }

    private static final class NamedDaemonFactory implements ThreadFactory {
        private final AtomicLong sequence = new AtomicLong();
        private final String namePrefix;

        NamedDaemonFactory(String namePrefix) {
            this.namePrefix = namePrefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, namePrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((target, error) ->
                    System.err.println("[" + target.getName() + "] 未捕获异常: " + error.getMessage()));
            return thread;
        }
    }
}
