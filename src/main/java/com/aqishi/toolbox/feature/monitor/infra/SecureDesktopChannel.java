package com.aqishi.toolbox.feature.monitor.infra;

import com.aqishi.toolbox.feature.monitor.domain.DesktopChannel;
import com.aqishi.toolbox.feature.monitor.domain.DesktopMessage;
import com.aqishi.toolbox.feature.monitor.domain.HandshakeException;
import com.aqishi.toolbox.feature.monitor.domain.RecordCipher;
import com.aqishi.toolbox.feature.monitor.domain.SecureChannelHandshake;
import com.aqishi.toolbox.feature.monitor.domain.SecureSessionConfig;
import com.aqishi.toolbox.feature.monitor.domain.SessionKeys;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.Errors;

import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Encrypting wrapper around a raw transport ({@link SocketChannelImpl}, {@link UdpChannelImpl}
 * or the ICE channel).
 *
 * <p>Runs {@link SecureChannelHandshake} over {@link DesktopMessage#TYPE_SECURE_HANDSHAKE}
 * frames, then carries every application message as a {@link RecordCipher} record in a
 * {@link DesktopMessage#TYPE_SECURE_RECORD} frame. Nothing is delivered to the message
 * listener and nothing is sent before the handshake completed; {@link #send} drops
 * messages until then rather than ever sending plaintext.</p>
 *
 * <p>Pre-authentication limits: handshake timeout (default 10 s), inbound frames capped
 * at {@link #MAX_HANDSHAKE_FRAME} bytes and {@link #MAX_PRE_AUTH_BYTES} in total. Any
 * other frame type before authentication means the peer runs the old plaintext protocol
 * and fails with {@link HandshakeException.Reason#INCOMPATIBLE_VERSION}.</p>
 *
 * <p>Reliable transports (TCP) use strict record ordering and close on any rejected
 * record. On UDP the existing fragmentation layer stays beneath the crypto: one record
 * is one reassembled UDP message, so a lost fragment loses exactly one record. Records
 * use a sliding replay window, handshake flights are retransmitted every
 * {@value #RETRANSMIT_INTERVAL_MS} ms, and rejected records are dropped silently (a
 * forged datagram must not be able to kill the session) until
 * {@value #MAX_UNRELIABLE_REJECTS} of them have been seen.</p>
 */
public final class SecureDesktopChannel implements DesktopChannel {

    public static final int MAX_HANDSHAKE_FRAME = 256;
    public static final int MAX_PRE_AUTH_BYTES = 8 * 1024;
    public static final int MAX_APPLICATION_PAYLOAD = 16 * 1024 * 1024 - 64;
    static final int MAX_RECORD_FRAME = MAX_APPLICATION_PAYLOAD + RecordCipher.OVERHEAD;
    static final long RETRANSMIT_INTERVAL_MS = 400;
    static final int MAX_UNRELIABLE_REJECTS = 64;

    private static final ScheduledExecutorService TIMER = DaemonThreads.scheduled("secure-channel-timer");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DesktopChannel inner;
    private final SecureChannelHandshake handshake;
    private final boolean reliable;
    private final CompletableFuture<SecureDesktopChannel> ready = new CompletableFuture<>();
    private final Object handshakeLock = new Object();
    private final Object sendLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 握手失败的真实原因；关闭时用它完成 {@link #ready}，否则会被笼统的 CLOSED 覆盖。 */
    private volatile HandshakeException handshakeFailure;
    private final List<byte[]> processedPeerFlights = new ArrayList<>();

    private volatile RecordCipher cipher;
    private volatile Consumer<DesktopMessage> messageListener;
    private volatile Runnable closeListener;
    private volatile String sas;
    private volatile boolean passwordUsed;
    private volatile long lastAuthenticatedReceive = System.currentTimeMillis();
    private byte[] lastFlight;
    private long preAuthBytes;
    private int rejectedRecords;
    private volatile ScheduledFuture<?> timeoutTask;
    private volatile ScheduledFuture<?> retransmitTask;

    private SecureDesktopChannel(DesktopChannel inner, SecureSessionConfig config) {
        this.inner = inner;
        this.reliable = inner.isReliable();
        this.handshake = config.newHandshake(RANDOM);
    }

    /** Starts the handshake; completion is reported through {@link #handshakeFuture()}. */
    public static SecureDesktopChannel start(DesktopChannel inner, SecureSessionConfig config) {
        SecureDesktopChannel channel = new SecureDesktopChannel(inner, config);
        channel.begin(config.handshakeTimeoutMillis());
        return channel;
    }

    /** Starts the handshake and blocks until it completed; the transport is closed on failure. */
    public static SecureDesktopChannel establish(DesktopChannel inner, SecureSessionConfig config)
            throws HandshakeException {
        return start(inner, config).awaitEstablished(config.handshakeTimeoutMillis() + 2_000L);
    }

    public SecureDesktopChannel awaitEstablished(long timeoutMillis) throws HandshakeException {
        try {
            return ready.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HandshakeException) throw (HandshakeException) cause;
            throw new HandshakeException(HandshakeException.Reason.PROTOCOL_ERROR, String.valueOf(cause));
        } catch (TimeoutException e) {
            HandshakeException timeout = new HandshakeException(HandshakeException.Reason.TIMEOUT, "no answer");
            fail(timeout);
            throw timeout;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new HandshakeException(HandshakeException.Reason.CLOSED, "interrupted");
        }
    }

    private void begin(long timeoutMillis) {
        inner.setMaxInboundMessageSize(MAX_HANDSHAKE_FRAME);
        inner.setCloseListener(this::onInnerClosed);
        HandshakeException startFailure = null;
        synchronized (handshakeLock) {
            timeoutTask = TIMER.schedule(() -> fail(new HandshakeException(
                    HandshakeException.Reason.TIMEOUT, "no handshake within " + timeoutMillis + " ms")),
                    timeoutMillis, TimeUnit.MILLISECONDS);
            if (!reliable) {
                retransmitTask = TIMER.scheduleAtFixedRate(this::retransmit,
                        RETRANSMIT_INTERVAL_MS, RETRANSMIT_INTERVAL_MS, TimeUnit.MILLISECONDS);
            }
            try {
                byte[] first = handshake.start();
                inner.setMessageListener(this::onInnerMessage);
                if (first != null) sendFlight(first);
            } catch (HandshakeException e) {
                startFailure = e;
            }
        }
        if (startFailure != null) fail(startFailure);
    }

    private void onInnerMessage(DesktopMessage message) {
        if (closed.get()) return;
        RecordCipher established = cipher;
        if (established != null) {
            onEstablishedMessage(message, established);
            return;
        }
        HandshakeException error = null;
        boolean completed = false;
        synchronized (handshakeLock) {
            if (cipher != null) {
                established = cipher;
            } else {
                preAuthBytes += message.getPayload().length + 5L;
                byte type = message.getType();
                if (preAuthBytes > MAX_PRE_AUTH_BYTES) {
                    error = new HandshakeException(HandshakeException.Reason.TOO_LARGE, "pre-auth byte limit");
                } else if (type == DesktopMessage.TYPE_SECURE_HANDSHAKE) {
                    try {
                        completed = processHandshake(message.getPayload());
                    } catch (HandshakeException e) {
                        error = e;
                    }
                } else if (type == DesktopMessage.TYPE_SECURE_RECORD) {
                    if (reliable) {
                        error = new HandshakeException(HandshakeException.Reason.PROTOCOL_ERROR,
                                "record before handshake completed");
                    }
                } else {
                    error = new HandshakeException(HandshakeException.Reason.INCOMPATIBLE_VERSION,
                            "peer sent unencrypted message type " + type
                                    + "; it runs an older version without the secure channel");
                }
            }
        }
        if (established != null) {
            onEstablishedMessage(message, established);
        } else if (error != null) {
            fail(error);
        } else if (completed) {
            ready.complete(this);
        }
    }

    /** Returns true when this message completed the handshake. Caller holds handshakeLock. */
    private boolean processHandshake(byte[] payload) throws HandshakeException {
        if (!reliable && isDuplicate(payload)) {
            resendLastFlight();
            return false;
        }
        byte[] reply = handshake.receive(payload);
        if (processedPeerFlights.size() < 4) processedPeerFlights.add(payload.clone());
        if (reply != null) sendFlight(reply);
        if (!handshake.isComplete()) return false;
        try {
            SessionKeys keys = handshake.keys();
            RecordCipher created = RecordCipher.create(keys, handshake.role(),
                    reliable ? RecordCipher.Ordering.STRICT : RecordCipher.Ordering.WINDOWED);
            sas = keys.sas();
            passwordUsed = keys.isPasswordUsed();
            keys.destroy();
            inner.setMaxInboundMessageSize(MAX_RECORD_FRAME);
            cancelTimers();
            lastAuthenticatedReceive = System.currentTimeMillis();
            cipher = created;
            return true;
        } catch (GeneralSecurityException e) {
            throw new HandshakeException(HandshakeException.Reason.PROTOCOL_ERROR, e.getMessage());
        }
    }

    private void onEstablishedMessage(DesktopMessage message, RecordCipher established) {
        byte type = message.getType();
        if (type == DesktopMessage.TYPE_SECURE_RECORD) {
            DesktopMessage plain;
            try {
                plain = established.open(message.getPayload());
            } catch (GeneralSecurityException | RuntimeException e) {
                onRejectedRecord(e);
                return;
            }
            lastAuthenticatedReceive = System.currentTimeMillis();
            deliver(plain);
        } else if (type == DesktopMessage.TYPE_SECURE_HANDSHAKE && !reliable) {
            synchronized (handshakeLock) {
                if (isDuplicate(message.getPayload())) resendLastFlight();
            }
        } else if (reliable) {
            close();
        }
    }

    private void onRejectedRecord(Exception e) {
        boolean closeNow;
        synchronized (handshakeLock) {
            rejectedRecords++;
            closeNow = reliable || rejectedRecords > MAX_UNRELIABLE_REJECTS;
        }
        if (closeNow) {
            Errors.ignored("secure channel rejected a record; closing: " + e.getMessage(), e);
            close();
        }
    }

    private void deliver(DesktopMessage message) {
        Consumer<DesktopMessage> listener = messageListener;
        if (listener == null) return;
        try {
            listener.accept(message);
        } catch (RuntimeException e) {
            Errors.log("remote desktop message handler failed", e);
        }
    }

    @Override
    public void send(DesktopMessage message) {
        RecordCipher established = cipher;
        if (message == null || established == null || closed.get()) return;
        if (message.getPayload().length > MAX_APPLICATION_PAYLOAD) {
            throw new IllegalArgumentException("message too large: " + message.getPayload().length);
        }
        synchronized (sendLock) {
            try {
                byte[] record = established.seal(message.getType(), message.getPayload());
                inner.send(new DesktopMessage(DesktopMessage.TYPE_SECURE_RECORD, record));
            } catch (GeneralSecurityException | IllegalStateException e) {
                Errors.ignored("secure channel cannot seal record; closing", e);
                close();
            }
        }
    }

    private void fail(HandshakeException error) {
        synchronized (handshakeLock) {
            if (cipher != null || ready.isDone()) return;
            byte[] alert = error.isReportedByPeer() ? null : SecureChannelHandshake.alert(error.getReason());
            if (alert != null && !closed.get()) {
                try {
                    inner.send(new DesktopMessage(DesktopMessage.TYPE_SECURE_HANDSHAKE, alert));
                } catch (RuntimeException ignored) {
                    // Best effort: the peer learns the reason if the alert arrives.
                }
            }
            cancelTimers();
        }
        // 先关闭再通知：等待方一醒来就能看到通道已关闭。反过来的顺序下，
        // 调用方拿到"认证失败"时连接可能还开着（JDK 17 上的测试就撞到了这个窗口）。
        handshakeFailure = error;
        close();
        ready.completeExceptionally(error);
    }

    private void retransmit() {
        synchronized (handshakeLock) {
            if (cipher == null && !closed.get()) resendLastFlight();
        }
    }

    private void sendFlight(byte[] flight) {
        lastFlight = flight;
        inner.send(new DesktopMessage(DesktopMessage.TYPE_SECURE_HANDSHAKE, flight));
    }

    private void resendLastFlight() {
        if (lastFlight != null) inner.send(new DesktopMessage(DesktopMessage.TYPE_SECURE_HANDSHAKE, lastFlight));
    }

    private boolean isDuplicate(byte[] payload) {
        for (byte[] seen : processedPeerFlights) {
            if (Arrays.equals(seen, payload)) return true;
        }
        return false;
    }

    private void cancelTimers() {
        if (timeoutTask != null) timeoutTask.cancel(false);
        if (retransmitTask != null) retransmitTask.cancel(false);
    }

    private void onInnerClosed() {
        if (!closed.compareAndSet(false, true)) return;
        // No handshakeLock here: this runs from inner.close(), which may be
        // reached while another thread holds the transport's send monitor.
        cancelTimers();
        HandshakeException failure = handshakeFailure;
        ready.completeExceptionally(failure != null ? failure
                : new HandshakeException(HandshakeException.Reason.CLOSED, "transport closed"));
        RecordCipher established = cipher;
        if (established != null) established.destroy();
        Runnable listener = closeListener;
        if (listener != null) listener.run();
    }

    @Override
    public void close() {
        try {
            inner.close();
        } finally {
            onInnerClosed();
        }
    }

    /** Completes with this channel once authenticated, or exceptionally with a {@link HandshakeException}. */
    public CompletableFuture<SecureDesktopChannel> handshakeFuture() {
        return ready;
    }

    public boolean isEstablished() {
        return cipher != null && !closed.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** Short authentication string both users compare, e.g. {@code "123 456"}; null before the handshake. */
    public String getSas() {
        return sas;
    }

    /**
     * Milliseconds since the last authenticated record (or the handshake). UDP
     * has no close notification, so this is how an end notices a vanished peer.
     */
    public long millisSinceLastReceive() {
        return System.currentTimeMillis() - lastAuthenticatedReceive;
    }

    public boolean isPasswordUsed() {
        return passwordUsed;
    }

    public SecureChannelHandshake.Role role() {
        return handshake.role();
    }

    @Override
    public boolean isP2P() {
        return inner.isP2P();
    }

    @Override
    public String getStatusDescription() {
        return inner.getStatusDescription() + " + AES-256-GCM";
    }

    @Override
    public void setMessageListener(Consumer<DesktopMessage> listener) {
        this.messageListener = listener;
    }

    @Override
    public void setCloseListener(Runnable listener) {
        this.closeListener = listener;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return inner.remoteAddress();
    }

    @Override
    public boolean isReliable() {
        return reliable;
    }
}
