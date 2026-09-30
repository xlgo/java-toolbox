package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.feature.network.domain.SocketOpenException;
import com.aqishi.toolbox.feature.network.domain.SocketSendException;
import com.aqishi.toolbox.feature.network.domain.SocketStats;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * UDP 会话：绑定本地端口，接收任意来源的数据报，发往默认目标或"最近发送方"，
 * 支持广播开关与组播加入/退出（IPv4 与 IPv6）。
 *
 * <p>用 {@link MulticastSocket} 而不是普通 DatagramSocket：它能直接 joinGroup，
 * 不加入任何组时行为与普通 UDP 套接字一致。</p>
 */
public final class UdpSession implements SocketSession {

    /** UDP over IPv4 单个数据报的最大载荷。 */
    public static final int MAX_DATAGRAM = 65507;

    /** 绑定参数，setter 链式调用。 */
    public static final class Options {
        private String bindHost = "0.0.0.0";
        private int bindPort;
        private String targetHost;
        private int targetPort;
        private boolean broadcast;
        private boolean echo;
        private boolean replyToLastSender;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;

        public Options bindHost(String value) { this.bindHost = value == null ? "0.0.0.0" : value.trim(); return this; }
        /** 本地端口，0 表示由系统分配。 */
        public Options bindPort(int value) { this.bindPort = value; return this; }
        public Options target(String host, int port) { this.targetHost = host; this.targetPort = port; return this; }
        public Options broadcast(boolean value) { this.broadcast = value; return this; }
        public Options echo(boolean value) { this.echo = value; return this; }
        public Options replyToLastSender(boolean value) { this.replyToLastSender = value; return this; }
        public Options queueCapacity(int value) { this.queueCapacity = value; return this; }
    }

    private static final class Outgoing {
        final InetSocketAddress target;
        final byte[] data;

        Outgoing(InetSocketAddress target, byte[] data) {
            this.target = target;
            this.data = data;
        }
    }

    private final Options options;
    private final SocketSessionListener listener;
    private final SocketStats stats = new SocketStats();
    private final LinkedBlockingQueue<Outgoing> queue;
    private final List<String> joinedGroups = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private volatile MulticastSocket socket;
    private volatile boolean closed;
    private volatile boolean echo;
    private volatile boolean replyToLastSender;
    private volatile InetSocketAddress target;
    private volatile InetSocketAddress lastSender;
    private boolean opened;
    private Thread receiver;
    private Thread sender;

    public UdpSession(Options options, SocketSessionListener listener) {
        this.options = Objects.requireNonNull(options, "options");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.queue = new LinkedBlockingQueue<>(Math.max(1, options.queueCapacity));
        this.echo = options.echo;
        this.replyToLastSender = options.replyToLastSender;
        setTarget(options.targetHost, options.targetPort);
    }

    @Override
    public Kind kind() {
        return Kind.UDP;
    }

    @Override
    public void open() throws IOException {
        synchronized (lock) {
            if (closed) throw new SocketOpenException(SocketError.NOT_CONNECTED, "Session already closed", null);
            if (opened) {
                throw new IllegalStateException("Session already opened");
            }
            opened = true;
            MulticastSocket created = new MulticastSocket(null);
            try {
                created.setReuseAddress(true);
                created.setBroadcast(options.broadcast);
                InetAddress address = InetAddress.getByName(options.bindHost.isEmpty() ? "0.0.0.0" : options.bindHost);
                created.bind(new InetSocketAddress(address, options.bindPort));
            } catch (IOException | IllegalArgumentException error) {
                closed = true;
                created.close();
                throw new SocketOpenException(SocketError.BIND_FAILED,
                        "Cannot bind " + options.bindHost + ":" + options.bindPort + ": " + error.getMessage(), error);
            }
            socket = created;
            receiver = DaemonThreads.factory(THREAD_PREFIX + "-udp-receive").newThread(this::receiveLoop);
            sender = DaemonThreads.factory(THREAD_PREFIX + "-udp-send").newThread(this::sendLoop);
            listener.onEvent(SocketEvent.connected(localAddress(), null));
            receiver.start();
            sender.start();
        }
    }

    /**
     * 设置默认目标。只记录未解析的地址：DNS 解析放到发送线程上，界面线程调用也不会卡住。
     */
    public void setTarget(String host, int port) {
        if (host == null || host.trim().isEmpty() || port <= 0 || port > 65535) {
            target = null;
        } else {
            target = InetSocketAddress.createUnresolved(host.trim(), port);
        }
    }

    public InetSocketAddress getTarget() {
        return target;
    }

    public void setReplyToLastSender(boolean value) {
        this.replyToLastSender = value;
    }

    public void setEcho(boolean value) {
        this.echo = value;
    }

    /** 最近一个数据报的来源；尚未收到时为 null。 */
    public InetSocketAddress lastSender() {
        return lastSender;
    }

    /** 运行期切换 SO_BROADCAST。 */
    public void setBroadcast(boolean enabled) throws SocketOpenException {
        try {
            requireSocket().setBroadcast(enabled);
        } catch (SocketException error) {
            throw new SocketOpenException(SocketError.OPTION_FAILED, error.getMessage(), error);
        }
    }

    public boolean isBroadcast() {
        try {
            MulticastSocket current = socket;
            return current != null && current.getBroadcast();
        } catch (SocketException error) {
            return false;
        }
    }

    /**
     * 加入组播组。
     *
     * @param group         组播地址（IPv4 224.0.0.0/4 或 IPv6 ff00::/8）
     * @param interfaceName 网卡名或网卡上的 IP；为空则交给系统选择
     */
    public void joinGroup(String group, String interfaceName) throws SocketOpenException {
        changeMembership(group, interfaceName, true);
    }

    public void leaveGroup(String group, String interfaceName) throws SocketOpenException {
        changeMembership(group, interfaceName, false);
    }

    /** 已加入的组播组（"组地址" 或 "组地址%网卡"）。 */
    public List<String> joinedGroups() {
        return new ArrayList<>(joinedGroups);
    }

    private void changeMembership(String group, String interfaceName, boolean join) throws SocketOpenException {
        String key = interfaceName == null || interfaceName.trim().isEmpty()
                ? group.trim() : group.trim() + "%" + interfaceName.trim();
        try {
            MulticastSocket current = requireSocket();
            InetAddress address = InetAddress.getByName(group.trim());
            if (!address.isMulticastAddress()) {
                throw new SocketOpenException(SocketError.MULTICAST_FAILED, "Not a multicast address: " + group, null);
            }
            NetworkInterface nif = resolveInterface(interfaceName);
            InetSocketAddress groupAddress = new InetSocketAddress(address, 0);
            if (join) {
                current.joinGroup(groupAddress, nif);
                if (!joinedGroups.contains(key)) {
                    joinedGroups.add(key);
                }
            } else {
                current.leaveGroup(groupAddress, nif);
                joinedGroups.remove(key);
            }
        } catch (SocketOpenException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new SocketOpenException(SocketError.MULTICAST_FAILED,
                    (join ? "Cannot join " : "Cannot leave ") + key + ": " + error.getMessage(), error);
        }
    }

    private static NetworkInterface resolveInterface(String name) throws IOException {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String trimmed = name.trim();
        NetworkInterface byName = NetworkInterface.getByName(trimmed);
        if (byName != null) {
            return byName;
        }
        NetworkInterface byAddress = NetworkInterface.getByInetAddress(InetAddress.getByName(trimmed));
        if (byAddress == null) {
            throw new IOException("No such network interface: " + trimmed);
        }
        return byAddress;
    }

    private MulticastSocket requireSocket() throws SocketOpenException {
        MulticastSocket current = socket;
        if (current == null || closed) {
            throw new SocketOpenException(SocketError.NOT_CONNECTED, "Socket not bound", null);
        }
        return current;
    }

    @Override
    public boolean isOpen() {
        return !closed && socket != null;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** 发往"最近发送方"（勾选且已收到过数据时）或默认目标。 */
    @Override
    public void send(byte[] data) {
        sendTo(resolveDefaultTarget(), data);
    }

    @Override
    public void sendAwait(byte[] data, long timeoutMillis) throws InterruptedException {
        Outgoing outgoing = prepare(resolveDefaultTarget(), data);
        if (!queue.offer(outgoing, Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS)) {
            throw new SocketSendException(SocketError.SEND_QUEUE_FULL,
                    "Send queue still full after " + timeoutMillis + " ms");
        }
    }

    /** 发往指定地址。 */
    public void sendTo(InetSocketAddress destination, byte[] data) {
        if (!queue.offer(prepare(destination, data))) {
            throw new SocketSendException(SocketError.SEND_QUEUE_FULL,
                    "Send queue full (" + queue.size() + " pending)");
        }
    }

    private InetSocketAddress resolveDefaultTarget() {
        InetSocketAddress last = lastSender;
        if (replyToLastSender && last != null) {
            return last;
        }
        return target;
    }

    private Outgoing prepare(InetSocketAddress destination, byte[] data) {
        if (closed || socket == null) {
            throw new SocketSendException(SocketError.NOT_CONNECTED, "Socket not bound");
        }
        if (destination == null) {
            throw new SocketSendException(SocketError.NO_TARGET, "No target address");
        }
        if (data.length > MAX_DATAGRAM) {
            throw new SocketSendException(SocketError.DATAGRAM_TOO_LARGE,
                    "Datagram of " + data.length + " bytes exceeds " + MAX_DATAGRAM);
        }
        return new Outgoing(destination, data);
    }

    private void receiveLoop() {
        MulticastSocket current = socket;
        byte[] buffer = new byte[65536];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        int consecutiveErrors = 0;
        while (!closed) {
            try {
                packet.setLength(buffer.length);
                current.receive(packet);
            } catch (IOException error) {
                if (!closed && ++consecutiveErrors < 16) {
                    // ICMP 端口不可达等偶发错误会让下一次 receive 抛异常，报告后继续收；
                    // 连续出错说明套接字已不可用，停止接收以免空转
                    listener.onEvent(SocketEvent.error(SocketError.RECEIVE_FAILED, null, error.getMessage()));
                    continue;
                }
                if (!closed) {
                    listener.onEvent(SocketEvent.error(SocketError.RECEIVE_FAILED, null, error.getMessage()));
                    close();
                }
                return;
            }
            consecutiveErrors = 0;
            byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                    packet.getOffset() + packet.getLength());
            InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
            lastSender = from;
            stats.recordReceived(data.length);
            listener.onEvent(SocketEvent.received(data, from, 0));
            if (echo) {
                try {
                    sendTo(from, data);
                } catch (SocketSendException full) {
                    listener.onEvent(SocketEvent.error(full.getError(), from, full.getMessage()));
                }
            }
        }
    }

    private void sendLoop() {
        MulticastSocket current = socket;
        try {
            while (!closed) {
                Outgoing outgoing = queue.take();
                InetSocketAddress destination = outgoing.target;
                try {
                    if (destination.isUnresolved()) {
                        destination = new InetSocketAddress(destination.getHostString(), destination.getPort());
                        if (destination.isUnresolved()) {
                            throw new IOException("Cannot resolve " + outgoing.target.getHostString());
                        }
                    }
                    current.send(new DatagramPacket(outgoing.data, outgoing.data.length, destination));
                    stats.recordSent(outgoing.data.length);
                    listener.onEvent(SocketEvent.sent(outgoing.data, destination, 0));
                } catch (IOException | RuntimeException error) {
                    if (closed) {
                        return;
                    }
                    listener.onEvent(SocketEvent.error(SocketError.SEND_FAILED, destination, error.getMessage()));
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public InetSocketAddress localAddress() {
        MulticastSocket current = socket;
        return current == null ? null : (InetSocketAddress) current.getLocalSocketAddress();
    }

    @Override
    public SocketStats stats() {
        return stats;
    }

    @Override
    public void close() {
        MulticastSocket current;
        Thread sendThread;
        synchronized (lock) {
            if (closed && socket == null) {
                return;
            }
            closed = true;
            current = socket;
            socket = null;
            sendThread = sender;
        }
        if (current != null) {
            current.close();
        }
        if (sendThread != null) {
            sendThread.interrupt();
        }
        queue.clear();
        joinedGroups.clear();
        if (current != null) {
            listener.onEvent(SocketEvent.disconnected(SocketEvent.Reason.LOCAL_CLOSE, "Socket closed"));
        }
    }
}
