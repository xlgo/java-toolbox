package com.aqishi.toolbox.feature.monitor.infra;

import com.aqishi.toolbox.feature.monitor.domain.DesktopChannel;
import com.aqishi.toolbox.feature.monitor.domain.DesktopMessage;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * In-memory datagram-like channel pair for tests: drops, duplicates and
 * reorders messages (random delivery delay), delivering on one thread per end
 * like a UDP receiver. Closing one end does not notify the other (as with UDP).
 */
final class LossyPipe implements DesktopChannel {

    private final Random random;
    private final double loss;
    private final double duplicate;
    private final int maxDelayMillis;
    private final ScheduledExecutorService receiver = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "lossy-pipe");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile LossyPipe peer;
    private volatile Consumer<DesktopMessage> listener;
    private volatile Runnable closeListener;
    private volatile int maxInbound = Integer.MAX_VALUE;

    private LossyPipe(long seed, double loss, double duplicate, int maxDelayMillis) {
        this.random = new Random(seed);
        this.loss = loss;
        this.duplicate = duplicate;
        this.maxDelayMillis = maxDelayMillis;
    }

    static LossyPipe[] pair(long seed, double loss, double duplicate, int maxDelayMillis) {
        LossyPipe a = new LossyPipe(seed, loss, duplicate, maxDelayMillis);
        LossyPipe b = new LossyPipe(seed + 1, loss, duplicate, maxDelayMillis);
        a.peer = b;
        b.peer = a;
        return new LossyPipe[]{a, b};
    }

    /** Delivers a message to this end as if it arrived from the network. */
    void inject(DesktopMessage message) {
        receiver.execute(() -> deliver(message));
    }

    @Override
    public void send(DesktopMessage message) {
        LossyPipe target = peer;
        if (closed.get() || target == null) return;
        synchronized (random) {
            if (random.nextDouble() < loss) return;
            int copies = random.nextDouble() < duplicate ? 2 : 1;
            for (int i = 0; i < copies; i++) {
                int delay = maxDelayMillis == 0 ? 0 : random.nextInt(maxDelayMillis);
                target.schedule(message, delay);
            }
        }
    }

    private void schedule(DesktopMessage message, int delay) {
        if (closed.get()) return;
        try {
            receiver.schedule(() -> deliver(message), delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // receiver shut down
        }
    }

    private void deliver(DesktopMessage message) {
        Consumer<DesktopMessage> current = listener;
        if (closed.get() || current == null || message.getPayload().length > maxInbound) return;
        current.accept(message);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        receiver.shutdownNow();
        Runnable current = closeListener;
        if (current != null) current.run();
    }

    boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isP2P() {
        return true;
    }

    @Override
    public String getStatusDescription() {
        return "lossy-pipe";
    }

    @Override
    public void setMessageListener(Consumer<DesktopMessage> listener) {
        this.listener = listener;
    }

    @Override
    public void setCloseListener(Runnable listener) {
        this.closeListener = listener;
    }

    @Override
    public boolean isReliable() {
        return false;
    }

    @Override
    public void setMaxInboundMessageSize(int bytes) {
        maxInbound = bytes;
    }
}
