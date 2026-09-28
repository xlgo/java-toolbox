package com.aqishi.toolbox.feature.network.application;

import java.util.ArrayList;
import java.util.List;

/** Hand-driven {@link HeartbeatScheduler.Ticker}: counts live tasks, runs them on {@link #tick()}. */
public final class FakeTicker implements HeartbeatScheduler.Ticker {

    private final List<Task> tasks = new ArrayList<>();
    public volatile long lastPeriod;
    public volatile boolean shutdown;

    @Override
    public synchronized HeartbeatScheduler.Cancellable scheduleAtFixedRate(Runnable task, long initialDelayMs,
                                                                          long periodMs) {
        Task t = new Task(task);
        tasks.add(t);
        lastPeriod = periodMs;
        return () -> {
            synchronized (FakeTicker.this) {
                t.cancelled = true;
            }
        };
    }

    @Override
    public synchronized void shutdown() {
        shutdown = true;
    }

    /** Number of scheduled, not yet cancelled tasks. */
    public synchronized int active() {
        int n = 0;
        for (Task t : tasks) {
            if (!t.cancelled) {
                n++;
            }
        }
        return n;
    }

    public synchronized int scheduledTotal() {
        return tasks.size();
    }

    /** Runs every live task once on the calling thread. */
    public void tick() {
        List<Runnable> live = new ArrayList<>();
        synchronized (this) {
            for (Task t : tasks) {
                if (!t.cancelled) {
                    live.add(t.body);
                }
            }
        }
        live.forEach(Runnable::run);
    }

    private static final class Task {
        final Runnable body;
        boolean cancelled;

        Task(Runnable body) {
            this.body = body;
        }
    }
}
