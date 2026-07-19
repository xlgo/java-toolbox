package com.aqishi.toolbox.monitor;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.DatagramChannel;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * P2P 直连通道协商器 (完全基于 UDP 双向打洞)。
 * 负责通过 STUN 映射的公网反射 Candidate，在控制端和被控端之间尝试建立 UDP 直连。
 */
public class P2PConnector {

    private static final String HANDSHAKE_REQ = "P2P_HANDSHAKE_REQ";
    private static final String HANDSHAKE_RESP = "P2P_HANDSHAKE_RESP";
    private static final int PROBE_TICK_MS = 100;
    private static final int SESSION_TIMEOUT_MS = 25000;
    private static final int PREDICTED_PORT_RANGE = 8;
    private static final int PREDICTED_PROBES_PER_ROUND = 1;
    private static final int DIAGNOSTIC_INTERVAL_MS = 5000;

    private final Function<DatagramSocket, List<InetSocketAddress>> publicAddressResolver;
    private final boolean enableUpnp;

    public P2PConnector() {
        this(StunClient::getPublicAddresses, true);
    }

    P2PConnector(Function<DatagramSocket, List<InetSocketAddress>> publicAddressResolver) {
        this(publicAddressResolver, false);
    }

    private P2PConnector(Function<DatagramSocket, List<InetSocketAddress>> publicAddressResolver,
                         boolean enableUpnp) {
        this.publicAddressResolver = Objects.requireNonNull(publicAddressResolver, "publicAddressResolver");
        this.enableUpnp = enableUpnp;
    }

    /**
     * 获取本机的所有有效局域网 IPv4 和全局 IPv6 地址。
     */
    public static List<String> getLocalCandidateAddresses() {
        Set<String> ips = new LinkedHashSet<>();
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;

                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLinkLocalAddress()) {
                        ips.add(addr.getHostAddress());
                    } else if (addr instanceof Inet6Address
                            && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()
                            && !addr.isSiteLocalAddress()
                            && !addr.isMulticastAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (ips.isEmpty()) {
            ips.add("127.0.0.1");
        }
        return new ArrayList<>(ips);
    }

    /**
     * Kept for compatibility with callers outside the remote desktop panel.
     */
    public static List<String> getLocalIPv4Addresses() {
        List<String> ipv4 = new ArrayList<>();
        for (String address : getLocalCandidateAddresses()) {
            try {
                if (InetAddress.getByName(address) instanceof Inet4Address) ipv4.add(address);
            } catch (Exception ignored) {
            }
        }
        return ipv4;
    }

    // ==================== UDP 直连监听逻辑 ====================

    private volatile DatagramSocket udpSocket;
    private int localPort = -1;
    private volatile boolean isListening = false;
    private Thread hostListenThread;
    private List<InetSocketAddress> publicAddresses = Collections.emptyList();
    private UpnpPortMapper.PortMapping udpPortMapping;
    private NatPmpPortMapper.PortMapping udpNatPmpPortMapping;
    private Consumer<String> mappingLog;
    private volatile boolean portPredictionRecommended;
    private Consumer<UdpChannelImpl> hostSuccessCallback;
    private final java.util.concurrent.atomic.AtomicLong sentProbeCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong sendErrorCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong receivedPacketCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong receivedHandshakeRequestCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong receivedHandshakeResponseCount = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong unexpectedPacketCount = new java.util.concurrent.atomic.AtomicLong();
    private volatile String lastReceiveSource = "-";
    private volatile String lastSendError = "-";

    /**
     * 开启本地 UDP 端口进行握手监听。
     */
    public int startHostListener(Consumer<UdpChannelImpl> onChannelConnected, Consumer<String> log) {
        return startHostListener(onChannelConnected, log, null);
    }

    /**
     * 绑定 socket 后、STUN 探测前执行回调。调用方可在这里发送 Offer，使双方并行收集候选。
     */
    public synchronized int startHostListener(Consumer<UdpChannelImpl> onChannelConnected,
                                              Consumer<String> log,
                                              Runnable onSocketBound) {
        try {
            stopHostListener();

            // Browser ICE gathers IPv4 and IPv6 on separate sockets. The
            // public STUN mapping in this connector is IPv4, so bind an
            // explicit IPv4 socket instead of a dual-stack [::] socket.
            final DatagramChannel listenerChannel =
                    DatagramChannel.open(StandardProtocolFamily.INET);
            final DatagramSocket listenerSocket = listenerChannel.socket();
            listenerSocket.bind(new InetSocketAddress(
                    InetAddress.getByName("0.0.0.0"), 0));
            try {
                listenerSocket.setReceiveBufferSize(4 * 1024 * 1024);
                listenerSocket.setSendBufferSize(4 * 1024 * 1024);
            } catch (SocketException ignored) {
                // 系统可能限制缓冲区大小，不影响继续建立连接
            }

            udpSocket = listenerSocket;
            localPort = listenerSocket.getLocalPort();
            handshakeDone.set(false);
            candidateAddresses.clear();
            predictedCandidateAddresses.clear();
            processedCandidates.clear();
            probePackets.clear();
            probeTransactionTargets.clear();
            portPredictionRecommended = false;
            hostSuccessCallback = onChannelConnected;
            mappingLog = log;
            resetDiagnostics();

            if (onSocketBound != null) {
                onSocketBound.run();
            }

            // 在启动接收监听线程前，同步完成 STUN 探测，避免多线程 receive 抢包
            try {
                List<InetSocketAddress> resolved;
                if (enableUpnp) {
                    StunClient.DiscoveryResult discovery =
                            StunClient.discoverPublicAddresses(listenerSocket);
                    resolved = discovery.getMappedAddresses();
                    log.accept("STUN 明细: " + discovery.toDiagnosticString());
                } else {
                    resolved = publicAddressResolver.apply(listenerSocket);
                }
                publicAddresses = resolved == null
                        ? Collections.emptyList()
                        : Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(resolved)));
                if (publicAddresses.isEmpty()) {
                    log.accept("STUN 未获取到公网映射，将继续尝试局域网候选。");
                } else {
                    log.accept("STUN 已获取 " + publicAddresses.size() + " 个公网映射: " + publicAddresses);
                    if (hasVaryingPublicPorts(publicAddresses)) {
                        portPredictionRecommended = true;
                        log.accept("检测到公网映射端口随 STUN 目标变化，将通知对端低频检查邻近端口。");
                    }
                }
            } catch (Exception e) {
                publicAddresses = Collections.emptyList();
                log.accept("STUN 公网地址探测失败: " + e.getMessage());
            }

            if (enableUpnp) {
                udpPortMapping = UpnpPortMapper.map("UDP", localPort, publicAddresses, log);
                if (udpPortMapping == null) {
                    udpNatPmpPortMapping =
                            NatPmpPortMapper.map("UDP", localPort, publicAddresses, log);
                }
                InetSocketAddress mappedAddress = udpPortMapping != null
                        ? udpPortMapping.getExternalAddress()
                        : (udpNatPmpPortMapping == null
                            ? null : udpNatPmpPortMapping.getExternalAddress());
                if (mappedAddress != null) {
                    List<InetSocketAddress> merged = new ArrayList<>(publicAddresses);
                    merged.add(mappedAddress);
                    publicAddresses = Collections.unmodifiableList(
                            new ArrayList<>(new LinkedHashSet<>(merged)));
                }
            }

            List<String> allLocalCandidates = getLocalCandidateAddresses();
            List<String> localCandidates = getLocalUdpCandidateAddresses();
            long globalIpv6Count = allLocalCandidates.stream()
                    .filter(P2PConnector::isIpv6Literal)
                    .count();
            log.accept("本机 UDP 直连能力: localCandidates=" + localCandidates
                    + ", globalIPv6Available=" + globalIpv6Count
                    + ", socketFamily=IPv4"
                    + ", verifiedPortMapping="
                    + (udpPortMapping != null || udpNatPmpPortMapping != null)
                    + ", java=" + System.getProperty("java.home"));

            isListening = true;
            hostListenThread = new Thread(() -> {
                log.accept("P2P 直连 UDP 监听已启动，本地端口: " + localPort);
                byte[] buf = new byte[1024];
                while (isListening && listenerSocket == udpSocket && !listenerSocket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        listenerSocket.receive(packet);
                        receivedPacketCount.incrementAndGet();
                        lastReceiveSource = String.valueOf(packet.getSocketAddress());

                        String msgStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                        InetSocketAddress remoteAddr = (InetSocketAddress) packet.getSocketAddress();

                        IceProbeCodec.ParsedMessage iceProbe = IceProbeCodec.parse(
                                packet.getData(), packet.getOffset(), packet.getLength());
                        if (iceProbe != null
                                && iceProbe.getType() == IceProbeCodec.BINDING_REQUEST) {
                            receivedHandshakeRequestCount.incrementAndGet();
                            byte[] respBytes = IceProbeCodec.createBindingSuccess(
                                    iceProbe.getTransactionId(), remoteAddr);
                            DatagramPacket respPacket = new DatagramPacket(respBytes, respBytes.length, remoteAddr);
                            listenerSocket.send(respPacket);
                            completeHandshake(listenerSocket, remoteAddr, "ICE 接收端", log);
                        } else if (iceProbe != null
                                && iceProbe.getType() == IceProbeCodec.BINDING_SUCCESS) {
                            String transactionKey =
                                    IceProbeCodec.transactionKey(iceProbe.getTransactionId());
                            InetSocketAddress expected =
                                    probeTransactionTargets.get(transactionKey);
                            if (!remoteAddr.equals(expected)) {
                                unexpectedPacketCount.incrementAndGet();
                                continue;
                            }
                            receivedHandshakeResponseCount.incrementAndGet();
                            completeHandshake(listenerSocket, remoteAddr, "ICE 发起端", log);
                        } else if (HANDSHAKE_REQ.equals(msgStr)) {
                            // direct/4 and earlier compatibility
                            receivedHandshakeRequestCount.incrementAndGet();
                            byte[] respBytes = HANDSHAKE_RESP.getBytes(StandardCharsets.UTF_8);
                            listenerSocket.send(new DatagramPacket(
                                    respBytes, respBytes.length, remoteAddr));
                            completeHandshake(listenerSocket, remoteAddr, "兼容接收端", log);
                        } else if (HANDSHAKE_RESP.equals(msgStr)) {
                            receivedHandshakeResponseCount.incrementAndGet();
                            completeHandshake(listenerSocket, remoteAddr, "兼容发起端", log);
                        } else {
                            unexpectedPacketCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        if (!isListening || listenerSocket != udpSocket || listenerSocket.isClosed()) break;
                        lastSendError = "receive: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                }
            }, "P2PConnector-UDPListener");
            hostListenThread.setDaemon(true);
            hostListenThread.start();

            return localPort;
        } catch (IOException e) {
            log.accept("启动 P2P 直连监听失败: " + e.getMessage());
            return -1;
        }
    }

    /**
     * 完成握手并把 UDP socket 的唯一接收权交给数据通道。
     * 握手监听线程必须先停止继续 receive，否则它会随机吞掉桌面数据包。
     */
    private void completeHandshake(DatagramSocket listenerSocket, InetSocketAddress remoteAddr,
                                   String role, Consumer<String> log) {
        if (listenerSocket != udpSocket || listenerSocket.isClosed()) return;
        if (!handshakeDone.compareAndSet(false, true)) return;

        isListening = false;
        Consumer<UdpChannelImpl> callback = activeSuccessCallback != null
                ? activeSuccessCallback : hostSuccessCallback;
        hostSuccessCallback = null;

        log.accept("P2P UDP 打洞握手成功 (作为" + role + ")! 远端通道: " + remoteAddr
                + "；" + buildDiagnosticSummary());
        stopConnectorSession();

        UdpChannelImpl channel = new UdpChannelImpl(listenerSocket, remoteAddr);
        if (callback != null) {
            callback.accept(channel);
        } else {
            channel.close();
        }
    }

    public synchronized void stopHostListener() {
        isListening = false;
        UpnpPortMapper.PortMapping mapping = udpPortMapping;
        udpPortMapping = null;
        if (mapping != null) mapping.close(mappingLog);
        NatPmpPortMapper.PortMapping natPmpMapping = udpNatPmpPortMapping;
        udpNatPmpPortMapping = null;
        if (natPmpMapping != null) natPmpMapping.close(mappingLog);
        mappingLog = null;
        DatagramSocket socket = udpSocket;
        udpSocket = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
        localPort = -1;
        publicAddresses = Collections.emptyList();
        hostSuccessCallback = null;
    }

    public InetSocketAddress getPublicAddress() {
        return publicAddresses.isEmpty() ? null : publicAddresses.get(0);
    }

    public List<InetSocketAddress> getPublicAddresses() {
        return new ArrayList<>(publicAddresses);
    }

    public boolean isPortPredictionRecommended() {
        return portPredictionRecommended;
    }

    public DatagramSocket getUdpSocket() {
        return udpSocket;
    }

    public List<String> getLocalUdpCandidateAddresses() {
        DatagramSocket socket = udpSocket;
        boolean ipv4 = socket == null || socket.getLocalAddress() instanceof Inet4Address;
        List<String> matching = new ArrayList<>();
        for (String value : getLocalCandidateAddresses()) {
            try {
                InetAddress address = InetAddress.getByName(value);
                if ((ipv4 && address instanceof Inet4Address)
                        || (!ipv4 && address instanceof Inet6Address)) {
                    matching.add(value);
                }
            } catch (Exception ignored) {
            }
        }
        return matching;
    }

    // ==================== UDP 增量打洞连接会话逻辑 ====================

    private ScheduledExecutorService clientConnectorExecutor;
    private ScheduledFuture<?> keepAliveFuture;
    private final java.util.concurrent.atomic.AtomicBoolean handshakeDone = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean sessionActive = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong sessionGeneration = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong probeRound = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger predictedProbeCursor = new java.util.concurrent.atomic.AtomicInteger();
    private final Set<String> processedCandidates = ConcurrentHashMap.newKeySet();
    private final Set<InetSocketAddress> candidateAddresses = ConcurrentHashMap.newKeySet();
    private final Set<InetSocketAddress> predictedCandidateAddresses = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<InetSocketAddress, byte[]> probePackets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, InetSocketAddress> probeTransactionTargets =
            new ConcurrentHashMap<>();
    private volatile long sessionStartedAt;
    private final java.util.concurrent.atomic.AtomicLong nextDiagnosticAt = new java.util.concurrent.atomic.AtomicLong();

    private volatile Consumer<UdpChannelImpl> activeSuccessCallback;
    private volatile Runnable activeFailCallback;
    private volatile Consumer<String> activeLogCallback;
    private ScheduledExecutorService sessionTimeoutScheduler;

    /**
     * 开启一个增量的打洞连接会话
     */
    public synchronized void startConnectorSession(Consumer<UdpChannelImpl> onSuccess, Runnable onFail, Consumer<String> log) {
        stopConnectorSession();

        this.handshakeDone.set(false);
        this.activeSuccessCallback = onSuccess;
        this.activeFailCallback = onFail;
        this.activeLogCallback = log;
        this.probeRound.set(0);
        this.predictedProbeCursor.set(0);
        this.sessionStartedAt = System.currentTimeMillis();
        this.nextDiagnosticAt.set(this.sessionStartedAt + DIAGNOSTIC_INTERVAL_MS);
        this.sessionActive.set(true);
        final long generation = sessionGeneration.incrementAndGet();

        // Match ICE-style pacing: modest initial checks followed by
        // progressively slower retransmission. This avoids triggering
        // UDP flood/port-scan protection in consumer and enterprise routers.
        this.clientConnectorExecutor = Executors.newSingleThreadScheduledExecutor();
        this.keepAliveFuture = this.clientConnectorExecutor.scheduleAtFixedRate(
                () -> runProbeRound(generation), 0, PROBE_TICK_MS, TimeUnit.MILLISECONDS);

        this.sessionTimeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        this.sessionTimeoutScheduler.schedule(() -> {
            synchronized (P2PConnector.this) {
                if (generation != sessionGeneration.get() || handshakeDone.get()) return;

                log.accept("P2P 直连打洞超时：已尝试 " + candidateAddresses.size()
                        + " 个精确候选和 " + predictedCandidateAddresses.size() + " 个预测端口；"
                        + buildDiagnosticSummary());
                if (receivedPacketCount.get() == 0) {
                    log.accept("UDP 失败判定: 本 socket 未收到任何对端数据包。"
                            + "信令候选已到达，成功发送=" + sentProbeCount.get()
                            + "，发送错误=" + sendErrorCount.get()
                            + "；可达地址族上的数据在网络路径中被丢弃，"
                            + "不是 ICE/STUN 响应解析失败。");
                }
                Runnable failCallback = activeFailCallback;
                stopConnectorSession();
                if (failCallback != null) {
                    failCallback.run();
                }
            }
        }, SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void runProbeRound(long generation) {
        if (generation != sessionGeneration.get() || handshakeDone.get() || !sessionActive.get()) return;

        long round = probeRound.getAndIncrement();
        long now = System.currentTimeMillis();
        maybeLogDiagnostic(now);
        long elapsed = now - sessionStartedAt;
        int divisor = elapsed < 3000 ? 1 : (elapsed < 10000 ? 3 : 10);
        if (round % divisor != 0) return;

        for (InetSocketAddress address : candidateAddresses) {
            sendProbe(address);
        }

        List<InetSocketAddress> predicted = new ArrayList<>(predictedCandidateAddresses);
        if (predicted.isEmpty()) return;
        predicted.sort(Comparator.comparing(InetSocketAddress::getHostString)
                .thenComparingInt(InetSocketAddress::getPort));

        int count = Math.min(PREDICTED_PROBES_PER_ROUND, predicted.size());
        int start = Math.floorMod(predictedProbeCursor.getAndAdd(count), predicted.size());
        for (int i = 0; i < count; i++) {
            sendProbe(predicted.get((start + i) % predicted.size()));
        }
    }

    private void sendProbe(InetSocketAddress address) {
        if (address == null || handshakeDone.get() || !sessionActive.get()) return;
        DatagramSocket socket = udpSocket;
        if (socket == null || socket.isClosed()) return;

        try {
            byte[] request = probePackets.get(address);
            if (request == null) {
                byte[] transactionId = IceProbeCodec.newTransactionId();
                byte[] created = IceProbeCodec.createBindingRequest(transactionId);
                byte[] raced = probePackets.putIfAbsent(address, created);
                request = raced == null ? created : raced;
                if (raced == null) {
                    probeTransactionTargets.put(
                            IceProbeCodec.transactionKey(transactionId), address);
                }
            }
            socket.send(new DatagramPacket(request, request.length, address));
            sentProbeCount.incrementAndGet();
        } catch (Exception e) {
            sendErrorCount.incrementAndGet();
            lastSendError = e.getClass().getSimpleName() + ": " + e.getMessage();
            // 单个候选失败不应中断其它候选检查
        }
    }

    private void maybeLogDiagnostic(long now) {
        long due = nextDiagnosticAt.get();
        if (now < due || !nextDiagnosticAt.compareAndSet(due, now + DIAGNOSTIC_INTERVAL_MS)) return;
        Consumer<String> log = activeLogCallback;
        if (log != null) {
            log.accept("UDP 打洞进度: " + buildDiagnosticSummary());
        }
    }

    private String buildDiagnosticSummary() {
        DatagramSocket socket = udpSocket;
        String local = socket == null ? "-" : String.valueOf(socket.getLocalSocketAddress());
        return "local=" + local
                + ", sent=" + sentProbeCount.get()
                + ", sendErrors=" + sendErrorCount.get()
                + ", received=" + receivedPacketCount.get()
                + ", req=" + receivedHandshakeRequestCount.get()
                + ", resp=" + receivedHandshakeResponseCount.get()
                + ", unexpected=" + unexpectedPacketCount.get()
                + ", lastSource=" + lastReceiveSource
                + ", lastError=" + lastSendError;
    }

    private void resetDiagnostics() {
        sentProbeCount.set(0);
        sendErrorCount.set(0);
        receivedPacketCount.set(0);
        receivedHandshakeRequestCount.set(0);
        receivedHandshakeResponseCount.set(0);
        unexpectedPacketCount.set(0);
        lastReceiveSource = "-";
        lastSendError = "-";
    }

    /**
     * 停止并清理连接会话
     */
    public synchronized void stopConnectorSession() {
        sessionActive.set(false);
        sessionGeneration.incrementAndGet();
        if (sessionTimeoutScheduler != null) {
            sessionTimeoutScheduler.shutdownNow();
            sessionTimeoutScheduler = null;
        }
        if (keepAliveFuture != null) {
            keepAliveFuture.cancel(true);
            keepAliveFuture = null;
        }
        if (clientConnectorExecutor != null) {
            clientConnectorExecutor.shutdownNow();
            clientConnectorExecutor = null;
        }
        activeSuccessCallback = null;
        activeFailCallback = null;
        activeLogCallback = null;
        probePackets.clear();
        probeTransactionTargets.clear();
    }

    /**
     * 增量添加并尝试连接一个 ICE 候选地址
     */
    public void addCandidate(String candidateStr) {
        if (candidateStr == null || handshakeDone.get()) return;
        if (!processedCandidates.add(candidateStr)) return;

        // 解析 WebRTC Candidate
        // 示例：candidate:12345 1 udp 2113937151 101.68.80.82 59164 typ srflx
        String[] parts = candidateStr.trim().split("\\s+");
        if (parts.length < 6) return;

        String ip = parts[4];
        String portStr = parts[5];
        String candidateType = parseCandidateType(parts);

        if (ip.endsWith(".local")) {
            try {
                InetAddress addr = InetAddress.getByName(ip);
                ip = addr.getHostAddress();
            } catch (Exception e) {
                if (activeLogCallback != null) {
                    activeLogCallback.accept("跳过无法解析的 mDNS 候选: " + ip);
                }
                return;
            }
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return;
        }
        if (port < 1 || port > 65535) return;

        InetSocketAddress addr = new InetSocketAddress(ip, port);
        if (addr.isUnresolved()) {
            if (activeLogCallback != null) {
                activeLogCallback.accept("跳过无法解析的候选地址: " + ip);
            }
            return;
        }
        candidateAddresses.add(addr);

        // RFC 8445 的 triggered check 思路：候选一到达就立即探测，不等待下一轮定时器。
        sendProbe(addr);

        int predictedAdded = 0;
        if ("srflx".equalsIgnoreCase(candidateType)
                && isPublicAddress(addr.getAddress())
                && hasCandidateExtension(parts, "port-predict", "1")) {
            for (int distance = 1; distance <= PREDICTED_PORT_RANGE; distance++) {
                predictedAdded += addPredictedAddress(addr.getAddress(), port + distance);
                predictedAdded += addPredictedAddress(addr.getAddress(), port - distance);
            }
        }

        if (activeLogCallback != null) {
            activeLogCallback.accept("加入打洞候选地址: " + ip + ":" + port
                    + " (" + candidateType + ")"
                    + (predictedAdded > 0 ? "，追加 " + predictedAdded + " 个端口预测" : ""));
        }
    }

    private int addPredictedAddress(InetAddress address, int port) {
        if (port < 1 || port > 65535) return 0;
        InetSocketAddress predicted = new InetSocketAddress(address, port);
        if (candidateAddresses.contains(predicted)) return 0;
        return predictedCandidateAddresses.add(predicted) ? 1 : 0;
    }

    private static String parseCandidateType(String[] parts) {
        for (int i = 6; i + 1 < parts.length; i++) {
            if ("typ".equalsIgnoreCase(parts[i])) return parts[i + 1];
        }
        return "unknown";
    }

    private static boolean hasCandidateExtension(String[] parts,
                                                 String name,
                                                 String expectedValue) {
        for (int i = 6; i + 1 < parts.length; i++) {
            if (name.equalsIgnoreCase(parts[i])
                    && expectedValue.equalsIgnoreCase(parts[i + 1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPublicAddress(InetAddress address) {
        return address != null
                && !address.isAnyLocalAddress()
                && !address.isLoopbackAddress()
                && !address.isLinkLocalAddress()
                && !address.isSiteLocalAddress()
                && !address.isMulticastAddress();
    }

    private static boolean hasVaryingPublicPorts(List<InetSocketAddress> addresses) {
        if (addresses.size() < 2) return false;
        int firstPort = addresses.get(0).getPort();
        for (int i = 1; i < addresses.size(); i++) {
            if (addresses.get(i).getPort() != firstPort) return true;
        }
        return false;
    }

    private static boolean isIpv6Literal(String value) {
        if (value == null || value.indexOf(':') < 0) return false;
        try {
            return InetAddress.getByName(value) instanceof Inet6Address;
        } catch (Exception e) {
            return false;
        }
    }

    int getExactCandidateCount() {
        return candidateAddresses.size();
    }

    int getPredictedCandidateCount() {
        return predictedCandidateAddresses.size();
    }
}
