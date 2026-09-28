package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Keep-alive timer whose lifecycle follows a connection.
 *
 * <p>The timer runs only while the heartbeat is enabled <em>and</em> the
 * connection is up: {@link #connectionOpened()} starts it, {@link #connectionClosed()}
 * stops it, and toggling {@link #setEnabled(boolean)} or changing the interval
 * while connected restarts it. Every start cancels the previous task first, so
 * no sequence of reconnects can leave two timers running. All methods are
 * thread-safe; the beat itself runs on the injected scheduler's thread.</p>
 */
public final class HeartbeatScheduler {

    /** Minimal scheduling seam so tests can drive ticks by hand. */
    public interface Ticker {
        Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs);

        void shutdown();
    }

    /** Handle for one scheduled task. */
    public interface Cancellable {
        void cancel();
    }

    private final Ticker ticker;
    private final Runnable beat;
    private boolean enabled;
    private long intervalMs;
    private boolean connected;
    private boolean shutdown;
    private Cancellable current;
    private int starts;

    public HeartbeatScheduler(Ticker ticker, Runnable beat) {
        if (ticker == null || beat == null) {
            throw new NullPointerException("ticker/beat");
        }
        this.ticker = ticker;
        this.beat = beat;
        this.intervalMs = 5000;
    }

    /** Ticker backed by a daemon single-thread scheduled executor. */
    public static Ticker daemonTicker(String name) {
        ScheduledExecutorService pool = DaemonThreads.scheduled(name);
        return new Ticker() {
            @Override
            public Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs) {
                ScheduledFuture<?> future = pool.scheduleAtFixedRate(
                        task, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            }

            @Override
            public void shutdown() {
                DaemonThreads.shutdownQuietly(pool);
            }
        };
    }

    public synchronized void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return; // re-applying unchanged settings must not reset the running timer
        }
        this.enabled = enabled;
        reschedule();
    }

    public synchronized void setIntervalMs(long intervalMs) {
        if (intervalMs < 1) {
            throw new IllegalArgumentException("intervalMs must be >= 1");
        }
        if (this.intervalMs == intervalMs) {
            return;
        }
        this.intervalMs = intervalMs;
        reschedule();
    }

    public synchronized void connectionOpened() {
        connected = true;
        reschedule();
    }

    public synchronized void connectionClosed() {
        connected = false;
        reschedule();
    }

    /** Stops the timer for good and releases the ticker thread. Idempotent. */
    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        connected = false;
        cancelCurrent();
        ticker.shutdown();
    }

    public synchronized boolean isRunning() {
        return current != null;
    }

    public synchronized boolean isEnabled() {
        return enabled;
    }

    /** Number of times a timer task has been started; lets tests prove "exactly once". */
    public synchronized int startCount() {
        return starts;
    }

    private void reschedule() {
        cancelCurrent();
        if (shutdown || !enabled || !connected) {
            return;
        }
        starts++;
        current = ticker.scheduleAtFixedRate(this::safeBeat, intervalMs, intervalMs);
    }

    private void cancelCurrent() {
        Cancellable task = current;
        current = null;
        if (task != null) {
            task.cancel();
        }
    }

    private void safeBeat() {
        try {
            beat.run();
        } catch (RuntimeException ignored) {
            // A failed beat (socket just closed) must not kill the periodic task.
        }
    }
}
