package com.aqishi.toolbox.feature.security.infra.acme;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Polling loop for ACME resources (authorizations, orders): honours the server's
 * {@code Retry-After} within {@code [minDelay, maxDelay]}, enforces an overall
 * timeout per wait and stops promptly on cancellation. Clock and sleeper are
 * injectable so tests run without real waiting.
 */
public final class AcmePoller {

    /** Blocks the calling thread; the default is {@link Thread#sleep(long)}. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** Outcome of one poll attempt. */
    public static final class Attempt<T> {
        final boolean done;
        final T value;
        final Duration retryAfter;

        private Attempt(boolean done, T value, Duration retryAfter) {
            this.done = done;
            this.value = value;
            this.retryAfter = retryAfter;
        }

        public static <T> Attempt<T> done(T value) {
            return new Attempt<>(true, value, null);
        }

        /** Not finished yet; {@code retryAfter} is the server hint, may be {@code null}. */
        public static <T> Attempt<T> again(Duration retryAfter) {
            return new Attempt<>(false, null, retryAfter);
        }
    }

    /** One poll attempt; returns {@link Attempt#done} or {@link Attempt#again}, or throws to fail. */
    @FunctionalInterface
    public interface Step<T> {
        Attempt<T> run() throws Exception;
    }

    /** Longest single uninterrupted sleep, so cancellation is noticed quickly. */
    private static final Duration SLICE = Duration.ofMillis(250);

    private final Clock clock;
    private final Sleeper sleeper;
    private final Duration minDelay;
    private final Duration maxDelay;
    private final Duration defaultDelay;
    private final Duration timeout;

    public AcmePoller(Clock clock, Sleeper sleeper, Duration minDelay, Duration maxDelay,
                      Duration defaultDelay, Duration timeout) {
        this.clock = clock;
        this.sleeper = sleeper;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        this.defaultDelay = defaultDelay;
        this.timeout = timeout;
    }

    /** 1 s minimum, 30 s maximum, 3 s without a hint, 5 minutes per wait. */
    public static AcmePoller defaults() {
        return new AcmePoller(Clock.systemUTC(), d -> Thread.sleep(Math.max(0, d.toMillis())),
                Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(3), Duration.ofMinutes(5));
    }

    /** DNS propagation check: every 5 s, give up (and let the CA try anyway) after 2 minutes. */
    public static AcmePoller propagationDefaults() {
        return new AcmePoller(Clock.systemUTC(), d -> Thread.sleep(Math.max(0, d.toMillis())),
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(2));
    }

    public Clock clock() {
        return clock;
    }

    public Duration timeout() {
        return timeout;
    }

    /** Throws {@link AcmeException.Reason#CANCELLED} when the supplier says so or the thread is interrupted. */
    public static void checkCancelled(BooleanSupplier cancelled, String subject) {
        if ((cancelled != null && cancelled.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
            throw new AcmeException(AcmeException.Reason.CANCELLED, subject, "ACME operation cancelled: " + subject);
        }
    }

    /**
     * Runs {@code step} until it reports done, the timeout passes (TIMEOUT) or the
     * operation is cancelled (CANCELLED). Exceptions from the step propagate.
     */
    public <T> T poll(String subject, BooleanSupplier cancelled, Step<T> step) throws Exception {
        Instant deadline = clock.instant().plus(timeout);
        while (true) {
            checkCancelled(cancelled, subject);
            Attempt<T> attempt = step.run();
            if (attempt.done) {
                return attempt.value;
            }
            Duration delay = RetryAfter.clamp(attempt.retryAfter, defaultDelay, minDelay, maxDelay);
            if (clock.instant().plus(delay).isAfter(deadline)) {
                throw new AcmeException(AcmeException.Reason.TIMEOUT, subject,
                        "Timed out after " + timeout.getSeconds() + "s waiting for " + subject);
            }
            sleepCancellable(delay, cancelled, subject);
        }
    }

    private void sleepCancellable(Duration delay, BooleanSupplier cancelled, String subject) {
        Duration remaining = delay;
        try {
            while (!remaining.isZero() && !remaining.isNegative()) {
                checkCancelled(cancelled, subject);
                Duration slice = remaining.compareTo(SLICE) > 0 ? SLICE : remaining;
                sleeper.sleep(slice);
                remaining = remaining.minus(slice);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AcmeException(AcmeException.Reason.CANCELLED, subject, "ACME operation cancelled: " + subject);
        }
    }
}
