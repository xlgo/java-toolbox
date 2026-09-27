package com.aqishi.toolbox.feature.monitor.domain;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Host-side gate between the secure channel and the handlers that inject
 * input, write files or run a shell.
 *
 * <p>Installed as the channel's message listener. Until the host user grants
 * consent every inbound message is dropped; afterwards each message is
 * checked against the granted {@link RemotePermissions} by
 * {@link HostMessageGuard}. Enforcement lives here, on the host, because the
 * controller's UI cannot be trusted to hide what it was not granted.</p>
 */
public final class HostSessionAccess implements Consumer<DesktopMessage> {

    public enum State { AWAITING_CONSENT, ACTIVE, DENIED, CLOSED }

    public enum Outcome { GRANTED, DENIED, TIMED_OUT, CLOSED }

    private final Consumer<DesktopMessage> handler;
    private final AtomicLong dropped = new AtomicLong();
    private final RemotePermissions timeoutMarker = RemotePermissions.of();
    private volatile State state = State.AWAITING_CONSENT;
    private volatile RemotePermissions granted = RemotePermissions.none();
    private CompletableFuture<RemotePermissions> pendingPrompt;

    /** @param handler receives only messages the current grant allows */
    public HostSessionAccess(Consumer<DesktopMessage> handler) {
        if (handler == null) throw new IllegalArgumentException("handler is required");
        this.handler = handler;
    }

    /**
     * Asks {@code strategy} for consent. The prompt is denied automatically
     * after {@code timeoutMillis}, and withdrawn if the session closes first.
     * Can be called once per session.
     */
    public CompletableFuture<Outcome> requestConsent(HostConsent.Strategy strategy,
                                                     HostConsent.Request request,
                                                     long timeoutMillis) {
        CompletableFuture<RemotePermissions> prompt;
        try {
            prompt = strategy.requestConsent(request);
        } catch (RuntimeException e) {
            prompt = null;
        }
        if (prompt == null) prompt = CompletableFuture.completedFuture(RemotePermissions.none());
        synchronized (this) {
            if (state != State.AWAITING_CONSENT || pendingPrompt != null) {
                prompt.complete(RemotePermissions.none());
                return CompletableFuture.completedFuture(state == State.CLOSED ? Outcome.CLOSED : Outcome.DENIED);
            }
            pendingPrompt = prompt;
        }
        prompt.completeOnTimeout(timeoutMarker, timeoutMillis, TimeUnit.MILLISECONDS);
        return prompt.handle((permissions, error) -> decide(error == null ? permissions : null));
    }

    private synchronized Outcome decide(RemotePermissions permissions) {
        pendingPrompt = null;
        if (state != State.AWAITING_CONSENT) {
            return state == State.CLOSED ? Outcome.CLOSED : Outcome.DENIED;
        }
        if (permissions != null && permissions != timeoutMarker && permissions.isGranted()) {
            granted = permissions;
            state = State.ACTIVE;
            return Outcome.GRANTED;
        }
        granted = RemotePermissions.none();
        state = State.DENIED;
        return permissions == timeoutMarker ? Outcome.TIMED_OUT : Outcome.DENIED;
    }

    @Override
    public void accept(DesktopMessage message) {
        if (message == null) return;
        RemotePermissions current = granted;
        if (state == State.ACTIVE && HostMessageGuard.allows(message.getType(), current)) {
            handler.accept(message);
        } else {
            dropped.incrementAndGet();
        }
    }

    /** Ends the session: revokes every permission and withdraws a pending prompt. */
    public void close() {
        CompletableFuture<RemotePermissions> prompt;
        synchronized (this) {
            state = State.CLOSED;
            granted = RemotePermissions.none();
            prompt = pendingPrompt;
        }
        if (prompt != null) prompt.complete(RemotePermissions.none());
    }

    public State state() {
        return state;
    }

    public RemotePermissions granted() {
        return granted;
    }

    /** Number of inbound messages refused so far (before consent or without permission). */
    public long droppedCount() {
        return dropped.get();
    }
}
