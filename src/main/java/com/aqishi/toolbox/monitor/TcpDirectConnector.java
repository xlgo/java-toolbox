package com.aqishi.toolbox.monitor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * UDP 打洞失败后的纯 TCP 直连兜底。
 *
 * <p>被控端监听并发布局域网/公网候选，控制端只负责主动连接，因此不会出现双方
 * 同时建立两条 TCP 连接后选择了不同连接的问题。公网候选仅在端口转发、直连公网
 * 或 NAT 恰好保持 TCP 端口时可用；该实现不会经过信令服务器转发桌面数据。</p>
 */
public class TcpDirectConnector {

    private static final int CONNECT_TIMEOUT_MS = 700;
    private static final int RETRY_INTERVAL_MS = 800;
    private static final int SESSION_TIMEOUT_SECONDS = 15;

    private final boolean enableUpnp;
    private final Set<InetSocketAddress> candidates = ConcurrentHashMap.newKeySet();
    private final Set<InetSocketAddress> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<Socket> openSockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicLong connectAttempts = new AtomicLong();
    private final AtomicLong connectErrors = new AtomicLong();

    private volatile ServerSocket serverSocket;
    private volatile ExecutorService workerExecutor;
    private volatile ScheduledExecutorService scheduler;
    private volatile Consumer<SocketChannelImpl> successCallback;
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

    public synchronized void reset() {
        stopInternal(null);
        candidates.clear();
        inFlight.clear();
        completed.set(false);
        connectAttempts.set(0);
        connectErrors.set(0);
        lastError = "-";
    }

    public synchronized int startListener(Consumer<SocketChannelImpl> onSuccess,
                                          Runnable onFail,
                                          Consumer<String> log) {
        return startListener(onSuccess, onFail, log, Collections.<InetSocketAddress>emptyList());
    }

    public synchronized int startListener(Consumer<SocketChannelImpl> onSuccess,
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

            workerExecutor = Executors.newSingleThreadExecutor();
            workerExecutor.submit(() -> {
                try {
                    Socket socket = listener.accept();
                    configure(socket);
                    complete(socket, "接受入站连接");
                } catch (IOException e) {
                    if (active.get() && !completed.get()) {
                        lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                }
            });
            startTimeout();
            return listener.getLocalPort();
        } catch (IOException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.accept("TCP 直连监听启动失败: " + lastError);
            active.set(false);
            return -1;
        }
    }

    public synchronized void startConnector(Consumer<SocketChannelImpl> onSuccess,
                                            Runnable onFail,
                                            Consumer<String> log) {
        stopInternal(null);
        completed.set(false);
        active.set(true);
        successCallback = onSuccess;
        failCallback = onFail;
        logCallback = log;
        workerExecutor = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
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

    private void submitConnect(InetSocketAddress candidate) {
        ExecutorService workers = workerExecutor;
        if (workers == null || workers.isShutdown() || !inFlight.add(candidate)) return;
        workers.submit(() -> {
            Socket socket = new Socket();
            openSockets.add(socket);
            connectAttempts.incrementAndGet();
            try {
                socket.connect(candidate, CONNECT_TIMEOUT_MS);
                configure(socket);
                complete(socket, "主动连接 " + candidate);
            } catch (IOException e) {
                connectErrors.incrementAndGet();
                lastError = candidate + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage();
                closeQuietly(socket);
            } finally {
                openSockets.remove(socket);
                inFlight.remove(candidate);
            }
        });
    }

    private void complete(Socket selected, String mode) {
        if (!completed.compareAndSet(false, true)) {
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
        try {
            SocketChannelImpl channel = new SocketChannelImpl(selected);
            Consumer<String> log = logCallback;
            if (log != null) {
                log.accept("TCP 直连成功 (" + mode + "): local=" + selected.getLocalSocketAddress()
                        + ", remote=" + selected.getRemoteSocketAddress());
            }
            Consumer<SocketChannelImpl> callback = successCallback;
            clearCallbacks();
            if (callback != null) callback.accept(channel);
            else channel.close();
        } catch (IOException e) {
            closeQuietly(selected);
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Runnable callback = failCallback;
            Consumer<String> log = logCallback;
            stopInternal(null);
            if (log != null) log.accept("TCP 数据通道初始化失败: " + lastError);
            if (callback != null) callback.run();
        }
    }

    private synchronized void startTimeout() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
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
        active.set(false);
        closeServer();
        closePortMapping();
        closeOtherSockets(selected);
        shutdownExecutors();
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
