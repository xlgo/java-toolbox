package com.aqishi.toolbox.feature.monitor.infra;

import com.aqishi.toolbox.feature.monitor.domain.DesktopChannel;
import com.aqishi.toolbox.feature.monitor.domain.HandshakeException;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * UDP 打洞失败后的纯 TCP 直连兜底。
 *
 * <p>被控端监听并发布局域网/公网候选，控制端只负责主动连接，因此不会出现双方
 * 同时建立两条 TCP 连接后选择了不同连接的问题。公网候选仅在端口转发、直连公网
 * 或 NAT 恰好保持 TCP 端口时可用；该实现不会经过信令服务器转发桌面数据。</p>
 *
 * <p>Security: a {@link ChannelGate} (the secure channel handshake) runs on every
 * accepted or connected socket before it is reported as a success. The listener
 * keeps accepting until one socket passes the gate, so a stray or hostile
 * connection to a UPnP-exposed port can neither take the session slot nor reach
 * any application handler. Addresses with repeated failed handshakes are refused
 * by a {@link HandshakeRateLimiter}; at most {@value #MAX_PENDING_HANDSHAKES}
 * unauthenticated sockets are served concurrently.</p>
 */
public class TcpDirectConnector {

    /**
     * Authenticates a freshly connected raw channel. Runs on a worker thread and
     * may block (bounded by the handshake timeout). Must return an authenticated
     * channel or throw; a thrown exception counts as a failed handshake.
     */
    public interface ChannelGate {
        DesktopChannel authenticate(SocketChannelImpl raw) throws Exception;
    }

    static final int MAX_PENDING_HANDSHAKES = 4;

    private static final int CONNECT_TIMEOUT_MS = 700;
    private static final int RETRY_INTERVAL_MS = 800;
    private static final int SESSION_TIMEOUT_SECONDS = 15;
    private static final int CONNECT_WORKER_COUNT = 4;
    private static final int CONNECT_QUEUE_CAPACITY = 128;

    private final boolean enableUpnp;
    private final Set<InetSocketAddress> candidates = ConcurrentHashMap.newKeySet();
    private final Set<InetSocketAddress> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong connectAttempts = new AtomicLong();
    private final AtomicLong connectErrors = new AtomicLong();
    private final AtomicLong lifecycleGeneration = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService workerExecutor;
    private volatile ScheduledExecutorService scheduler;
    private final AtomicInteger pendingHandshakes = new AtomicInteger();
    private volatile ChannelGate channelGate;
    private volatile HandshakeRateLimiter rateLimiter = new HandshakeRateLimiter();
    private volatile Consumer<DesktopChannel> successCallback;
    private volatile Runnable failCallback;
    private volatile Consumer<String> logCallback;
    private volatile String lastError = "-";
    private volatile UpnpPortMapper.PortMapping tcpPortMapping;
    private volatile NatPmpPortMapper.PortMapping tcpNatPmpPortMapping;

    public TcpDirectConnector() {
        this(true);
    }

    TcpDirectConnector(boolean enableUpnp) {
        this.enableUpnp = enableUpnp;
    }

    /** Gate applied to every socket; null reports raw channels (tests, diagnostics). */
    public void setChannelGate(ChannelGate gate) {
        this.channelGate = gate;
    }

    /** Shares one limiter across sessions so a new offer does not reset the counters. */
    public void setRateLimiter(HandshakeRateLimiter limiter) {
        this.rateLimiter = limiter == null ? new HandshakeRateLimiter() : limiter;
    }

    public synchronized void reset() {
        stopInternal(null);
        candidates.clear();
        inFlight.clear();
        completed.set(false);
        connectAttempts.set(0);
        connectErrors.set(0);
        lastError = "-";
    }

    public synchronized int startListener(Consumer<DesktopChannel> onSuccess,
                                          Runnable onFail,
                                          Consumer<String> log) {
        return startListener(onSuccess, onFail, log, Collections.<InetSocketAddress>emptyList());
    }

    public synchronized int startListener(Consumer<DesktopChannel> onSuccess,
                                          Runnable onFail,
                                          Consumer<String> log,
                                          Collection<InetSocketAddress> stunAddresses) {
        stopInternal(null);
        completed.set(false);
        active.set(true);
        successCallback = onSuccess;
        failCallback = onFail;
        logCallback = log;

        try {
            ServerSocket listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(0));
            serverSocket = listener;
            log.accept("TCP 直连监听已启动: " + listener.getLocalSocketAddress());

            if (enableUpnp) {
                tcpPortMapping = UpnpPortMapper.map(
                        "TCP", listener.getLocalPort(), stunAddresses, log);
                if (tcpPortMapping == null) {
                    tcpNatPmpPortMapping = NatPmpPortMapper.map(
                            "TCP", listener.getLocalPort(), stunAddresses, log);
                }
            }

            ExecutorService workers = new ThreadPoolExecutor(
                    1 + MAX_PENDING_HANDSHAKES,
                    1 + MAX_PENDING_HANDSHAKES,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(MAX_PENDING_HANDSHAKES),
                    DaemonThreads.factory("tcp-direct-listener"),
                    new ThreadPoolExecutor.AbortPolicy());
            workerExecutor = workers;
            pendingHandshakes.set(0);
            workers.submit(() -> acceptLoop(listener, workers));
            startTimeout();
            return listener.getLocalPort();
        } catch (IOException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.accept("TCP 直连监听启动失败: " + lastError);
            active.set(false);
            return -1;
        }
    }

    public synchronized void startConnector(Consumer<DesktopChannel> onSuccess,
                                            Runnable onFail,
                                            Consumer<String> log) {
        stopInternal(null);
        completed.set(false);
        active.set(true);
        successCallback = onSuccess;
        failCallback = onFail;
        logCallback = log;
        workerExecutor = new ThreadPoolExecutor(
                CONNECT_WORKER_COUNT,
                CONNECT_WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(CONNECT_QUEUE_CAPACITY),
                DaemonThreads.factory("tcp-direct-connect"),
                new ThreadPoolExecutor.AbortPolicy());
        scheduler = DaemonThreads.scheduled("tcp-direct-scheduler");
        scheduler.scheduleAtFixedRate(this::connectRound, 0, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.schedule(this::timeout, SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        log.accept("开始 TCP 直连候选检查，等待被控端 TCP 候选...");
    }

    public void addCandidate(String candidateString) {
        if (candidateString == null) return;
        String[] parts = candidateString.trim().split("\\s+");
        if (parts.length < 6 || !"tcp".equalsIgnoreCase(parts[2])) return;

        int port;
        try {
            port = Integer.parseInt(parts[5]);
        } catch (NumberFormatException e) {
            return;
        }
        if (port < 1 || port > 65535) return;

        InetSocketAddress address = new InetSocketAddress(parts[4], port);
        if (address.isUnresolved() || !candidates.add(address)) return;
        Consumer<String> log = logCallback;
        if (log != null) log.accept("加入 TCP 直连候选: " + address);
        if (active.get()) submitConnect(address);
    }

    public synchronized void stop() {
        stopInternal(null);
    }

    public InetSocketAddress getMappedPublicAddress() {
        UpnpPortMapper.PortMapping mapping = tcpPortMapping;
        if (mapping != null) return mapping.getExternalAddress();
        NatPmpPortMapper.PortMapping natPmpMapping = tcpNatPmpPortMapping;
        return natPmpMapping == null ? null : natPmpMapping.getExternalAddress();
    }

    private void connectRound() {
        if (!active.get() || completed.get()) return;
        for (InetSocketAddress candidate : new ArrayList<>(candidates)) {
            submitConnect(candidate);
        }
    }

    private synchronized void submitConnect(InetSocketAddress candidate) {
        ExecutorService workers = workerExecutor;
        if (workers == null || workers.isShutdown() || !inFlight.add(candidate)) return;
        long generation = lifecycleGeneration.get();
        try {
            workers.submit(() -> {
                Socket socket = new Socket();
                openSockets.add(socket);
                connectAttempts.incrementAndGet();
                boolean retryable = true;
                try {
                    socket.connect(candidate, CONNECT_TIMEOUT_MS);
                    configure(socket);
                    retryable = authenticateAndComplete(socket, "outbound " + candidate, false);
                } catch (IOException e) {
                    connectErrors.incrementAndGet();
                    lastError = candidate + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage();
                    closeQuietly(socket);
                } finally {
                    openSockets.remove(socket);
                    // A definitive handshake verdict (wrong password, other version)
                    // stays in inFlight: retrying would only burn the peer's
                    // failed-handshake budget for this address.
                    if (generation == lifecycleGeneration.get() && retryable) {
                        inFlight.remove(candidate);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // A stopped/full bounded pool must not leave this candidate stuck in
            // inFlight, otherwise a later connector session cannot retry it.
            inFlight.remove(candidate);
        }
    }

    private void acceptLoop(ServerSocket listener, ExecutorService workers) {
        while (active.get() && !completed.get() && !listener.isClosed()) {
            Socket socket;
            try {
                socket = listener.accept();
            } catch (IOException e) {
                if (active.get() && !completed.get()) {
                    lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                return;
            }
            InetAddress address = socket.getInetAddress();
            if (!rateLimiter.allow(address)) {
                log("Refusing TCP connection from " + address + ": too many failed handshakes");
                closeQuietly(socket);
                continue;
            }
            if (pendingHandshakes.incrementAndGet() > MAX_PENDING_HANDSHAKES) {
                pendingHandshakes.decrementAndGet();
                log("Refusing TCP connection from " + address + ": too many pending handshakes");
                closeQuietly(socket);
                continue;
            }
            openSockets.add(socket);
            try {
                workers.submit(() -> {
                    try {
                        configure(socket);
                        authenticateAndComplete(socket, "inbound " + socket.getRemoteSocketAddress(), true);
                    } catch (IOException e) {
                        openSockets.remove(socket);
                        closeQuietly(socket);
                    } finally {
                        pendingHandshakes.decrementAndGet();
                    }
                });
            } catch (RejectedExecutionException e) {
                pendingHandshakes.decrementAndGet();
                openSockets.remove(socket);
                closeQuietly(socket);
            }
        }
    }

    /**
     * Runs the gate on a connected socket and completes the session if it passes.
     *
     * @return false if the handshake failed for a reason that retrying cannot fix
     */
    private boolean authenticateAndComplete(Socket socket, String mode, boolean inbound) throws IOException {
        if (completed.get()) {
            closeQuietly(socket);
            return true;
        }
        SocketChannelImpl raw = new SocketChannelImpl(socket);
        ChannelGate gate = channelGate;
        DesktopChannel channel;
        try {
            channel = gate == null ? raw : gate.authenticate(raw);
        } catch (Exception e) {
            raw.close();
            openSockets.remove(socket);
            lastError = mode + " -> handshake " + e.getMessage();
            if (inbound && rateLimiter.recordFailure(socket.getInetAddress())) {
                log("Blocking " + socket.getInetAddress() + " after repeated failed handshakes");
            }
            log("TCP handshake failed (" + mode + "): " + e.getMessage());
            return !(e instanceof HandshakeException && ((HandshakeException) e).getReason().isUserActionable());
        }
        if (channel == null) {
            raw.close();
            openSockets.remove(socket);
            return true;
        }
        complete(socket, channel, mode);
        return true;
    }

    private void log(String message) {
        Consumer<String> log = logCallback;
        if (log != null) log.accept(message);
    }

    private void complete(Socket selected, DesktopChannel channel, String mode) {
        if (!completed.compareAndSet(false, true)) {
            channel.close();
            closeQuietly(selected);
            return;
        }

        // The selected socket is now owned by SocketChannelImpl. Remove it
        // before invoking the callback so callback-side connector cleanup
        // cannot accidentally close the live data channel.
        openSockets.remove(selected);
        active.set(false);
        closeServer();
        closePortMapping();
        closeOtherSockets(selected);
        shutdownExecutors();
        // shutdownNow() above interrupted this worker; the success callback
        // must not inherit that interrupt.
        Thread.interrupted();
        log("TCP direct connection established (" + mode + "): local=" + selected.getLocalSocketAddress()
                + ", remote=" + selected.getRemoteSocketAddress());
        Consumer<DesktopChannel> callback = successCallback;
        clearCallbacks();
        if (callback != null) callback.accept(channel);
        else channel.close();
    }

    private synchronized void startTimeout() {
        scheduler = DaemonThreads.scheduled("tcp-direct-scheduler");
        scheduler.schedule(this::timeout, SESSION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void timeout() {
        if (!completed.compareAndSet(false, true)) return;
        active.set(false);
        Runnable callback = failCallback;
        Consumer<String> log = logCallback;
        String summary = "attempts=" + connectAttempts.get() + ", errors=" + connectErrors.get()
                + ", candidates=" + candidates.size() + ", lastError=" + lastError;
        stopInternal(null);
        if (log != null) log.accept("TCP 直连超时: " + summary);
        if (callback != null) callback.run();
    }

    private synchronized void stopInternal(Socket selected) {
        lifecycleGeneration.incrementAndGet();
        active.set(false);
        closeServer();
        closePortMapping();
        closeOtherSockets(selected);
        shutdownExecutors();
        inFlight.clear();
        clearCallbacks();
    }

    private void closeServer() {
        ServerSocket listener = serverSocket;
        serverSocket = null;
        if (listener != null) {
            try {
                listener.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closePortMapping() {
        UpnpPortMapper.PortMapping mapping = tcpPortMapping;
        tcpPortMapping = null;
        if (mapping != null) mapping.close(logCallback);
        NatPmpPortMapper.PortMapping natPmpMapping = tcpNatPmpPortMapping;
        tcpNatPmpPortMapping = null;
        if (natPmpMapping != null) natPmpMapping.close(logCallback);
    }

    private void closeOtherSockets(Socket selected) {
        for (Socket socket : new ArrayList<>(openSockets)) {
            if (socket != selected) closeQuietly(socket);
        }
        openSockets.clear();
    }

    private void shutdownExecutors() {
        ScheduledExecutorService timer = scheduler;
        scheduler = null;
        if (timer != null) timer.shutdownNow();
        ExecutorService workers = workerExecutor;
        workerExecutor = null;
        if (workers != null) workers.shutdownNow();
    }

    private void clearCallbacks() {
        successCallback = null;
        failCallback = null;
        logCallback = null;
    }

    private static void configure(Socket socket) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
