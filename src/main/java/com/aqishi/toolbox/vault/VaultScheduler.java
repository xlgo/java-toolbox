package com.aqishi.toolbox.vault;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public interface VaultScheduler extends AutoCloseable {
    Cancellable schedule(Runnable task, long delay, TimeUnit unit);

    Cancellable scheduleAtFixedRate(
            Runnable task, long initialDelay, long period, TimeUnit unit);

    @Override
    void close();

    static VaultScheduler daemon() {
        return new ScheduledVaultScheduler();
    }

    interface Cancellable {
        void cancel();
    }

    final class ScheduledVaultScheduler implements VaultScheduler {
        private final ScheduledExecutorService executor =
                Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable task) {
                        Thread thread = new Thread(task, "java-toolbox-vault-timer");
                        thread.setDaemon(true);
                        return thread;
                    }
                });

        @Override
        public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
            ScheduledFuture<?> future = executor.schedule(task, delay, unit);
            return () -> future.cancel(false);
        }

        @Override
        public Cancellable scheduleAtFixedRate(
                Runnable task, long initialDelay, long period, TimeUnit unit) {
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                    task, initialDelay, period, unit);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
