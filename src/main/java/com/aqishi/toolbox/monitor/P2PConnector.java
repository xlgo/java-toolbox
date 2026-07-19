package com.aqishi.toolbox.monitor;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * P2P 直连通道协商器 (完全基于 UDP 双向打洞)。
 * 负责通过 STUN 映射的公网反射 Candidate，在控制端和被控端之间尝试建立 UDP 直连。
 */
public class P2PConnector {

    private static final int HANDSHAKE_TIMEOUT_MS = 2500;
    private static final String HANDSHAKE_REQ = "P2P_HANDSHAKE_REQ";
    private static final String HANDSHAKE_RESP = "P2P_HANDSHAKE_RESP";

    /**
     * 获取本机的所有有效局域网 IPv4 地址。
     */
    public static List<String> getLocalIPv4Addresses() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (nif.isLoopback() || !nif.isUp()) continue;
                
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
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
        return ips;
    }

    // ==================== UDP 直连监听逻辑 ====================

    private DatagramSocket udpSocket;
    private int localPort = -1;
    private volatile boolean isListening = false;
    private Thread hostListenThread;
    private InetSocketAddress publicAddress;

    /**
     * 开启本地 UDP 端口进行握手监听。
     */
    public int startHostListener(Consumer<UdpChannelImpl> onChannelConnected, Consumer<String> log) {
        try {
            stopHostListener();
            
            udpSocket = new DatagramSocket(0); // 绑定随机 UDP 端口
            localPort = udpSocket.getLocalPort();
            
            // 在启动接收监听线程前，同步完成 STUN 探测，避免多线程 receive 抢包
            publicAddress = StunClient.getPublicAddress(udpSocket);
            
            isListening = true;
            hostListenThread = new Thread(() -> {
                log.accept("P2P 直连 UDP 监听已启动，本地端口: " + localPort);
                byte[] buf = new byte[1024];
                while (isListening && !udpSocket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        udpSocket.receive(packet);
                        
                        String msgStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).trim();
                        InetSocketAddress remoteAddr = (InetSocketAddress) packet.getSocketAddress();
                        
                        if (HANDSHAKE_REQ.equals(msgStr)) {
                            // 收到对端的握手请求，回执响应
                            byte[] respBytes = HANDSHAKE_RESP.getBytes(StandardCharsets.UTF_8);
                            DatagramPacket respPacket = new DatagramPacket(respBytes, respBytes.length, remoteAddr);
                            udpSocket.send(respPacket);
                            
                            // 触发直连成功
                            if (handshakeDone.compareAndSet(false, true)) {
                                log.accept("P2P UDP 打洞握手成功 (作为接收端)! 远端通道: " + remoteAddr);
                                UdpChannelImpl channel = new UdpChannelImpl(udpSocket, remoteAddr);
                                if (activeSuccessCallback != null) {
                                    activeSuccessCallback.accept(channel);
                                }
                                stopConnectorSession();
                            }
                        } else if (HANDSHAKE_RESP.equals(msgStr)) {
                            // 收到对端的确认响应
                            if (handshakeDone.compareAndSet(false, true)) {
                                log.accept("P2P UDP 打洞握手成功 (作为发起端)! 远端通道: " + remoteAddr);
                                UdpChannelImpl channel = new UdpChannelImpl(udpSocket, remoteAddr);
                                if (activeSuccessCallback != null) {
                                    activeSuccessCallback.accept(channel);
                                }
                                stopConnectorSession();
                            }
                        }
                    } catch (Exception e) {
                        if (!isListening) break;
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

    public void stopHostListener() {
        isListening = false;
        if (udpSocket != null) {
            try {
                udpSocket.close();
            } catch (Exception ignored) {}
            udpSocket = null;
        }
        localPort = -1;
        publicAddress = null;
    }

    public InetSocketAddress getPublicAddress() {
        return publicAddress;
    }

    public DatagramSocket getUdpSocket() {
        return udpSocket;
    }

    // ==================== UDP 增量打洞连接会话逻辑 ====================

    private ExecutorService clientConnectorExecutor;
    private ScheduledFuture<?> keepAliveFuture;
    private final java.util.concurrent.atomic.AtomicBoolean handshakeDone = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final Set<String> processedCandidates = ConcurrentHashMap.newKeySet();
    private final Set<InetSocketAddress> candidateAddresses = ConcurrentHashMap.newKeySet();

    private Consumer<UdpChannelImpl> activeSuccessCallback;
    private Runnable activeFailCallback;
    private Consumer<String> activeLogCallback;
    private ScheduledExecutorService sessionTimeoutScheduler;

    /**
     * 开启一个增量的打洞连接会话
     */
    public synchronized void startConnectorSession(Consumer<UdpChannelImpl> onSuccess, Runnable onFail, Consumer<String> log) {
        stopConnectorSession();
        
        this.handshakeDone.set(false);
        this.candidateAddresses.clear();
        this.processedCandidates.clear();
        this.activeSuccessCallback = onSuccess;
        this.activeFailCallback = onFail;
        this.activeLogCallback = log;

        // 启动一个后台定时任务，每隔 300 毫秒向目前所有候选地址循环发送握手探测包
        this.clientConnectorExecutor = Executors.newSingleThreadScheduledExecutor();
        this.keepAliveFuture = ((ScheduledExecutorService) this.clientConnectorExecutor).scheduleAtFixedRate(() -> {
            if (handshakeDone.get()) return;
            if (udpSocket == null || udpSocket.isClosed()) return;

            byte[] reqBytes = HANDSHAKE_REQ.getBytes(StandardCharsets.UTF_8);
            for (InetSocketAddress addr : candidateAddresses) {
                try {
                    DatagramPacket packet = new DatagramPacket(reqBytes, reqBytes.length, addr);
                    udpSocket.send(packet);
                } catch (Exception ignored) {}
            }
        }, 0, 300, TimeUnit.MILLISECONDS);

        this.sessionTimeoutScheduler = Executors.newSingleThreadScheduledExecutor();
        this.sessionTimeoutScheduler.schedule(() -> {
            synchronized (P2PConnector.this) {
                if (!handshakeDone.get()) {
                    log.accept("P2P 直连打洞会话结束 (均尝试超时)。");
                    if (activeFailCallback != null) {
                        activeFailCallback.run();
                    }
                    stopConnectorSession();
                }
            }
        }, 12000, TimeUnit.MILLISECONDS); // 12秒打洞超时
    }

    /**
     * 停止并清理连接会话
     */
    public synchronized void stopConnectorSession() {
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

        InetSocketAddress addr = new InetSocketAddress(ip, port);
        candidateAddresses.add(addr);

        if (activeLogCallback != null) {
            activeLogCallback.accept("加入打洞候选地址: " + ip + ":" + port);
        }
    }
}
