package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.feature.network.domain.SocketFrameSplitter;
import com.aqishi.toolbox.feature.network.domain.SocketOpenException;
import com.aqishi.toolbox.feature.network.domain.SocketSendException;
import com.aqishi.toolbox.feature.network.domain.SocketStats;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP 服务端会话：监听、接入多个客户端（有上限）、定向或广播发送、踢出客户端、回显模式。
 */
public final class TcpServerSession implements SocketSession {

    /** 默认最大客户端数。 */
    public static final int DEFAULT_MAX_CLIENTS = 64;

    /** 监听参数，setter 链式调用。 */
    public static final class Options {
        private String bindHost = "0.0.0.0";
        private int port;
        private int maxClients = DEFAULT_MAX_CLIENTS;
        private boolean echo;
        private boolean noDelay = true;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private SocketFrameSplitter.Config frame = SocketFrameSplitter.Config.raw();

        /** 监听地址：0.0.0.0、::、127.0.0.1 或某块网卡的地址。 */
        public Options bindHost(String value) { this.bindHost = value == null ? "0.0.0.0" : value.trim(); return this; }
        /** 监听端口，0 表示由系统分配（实际端口见 {@link TcpServerSession#actualPort()}）。 */
        public Options port(int value) { this.port = value; return this; }
        public Options maxClients(int value) { this.maxClients = Math.max(1, value); return this; }
        public Options echo(boolean value) { this.echo = value; return this; }
        public Options noDelay(boolean value) { this.noDelay = value; return this; }
        public Options queueCapacity(int value) { this.queueCapacity = value; return this; }
        public Options frame(SocketFrameSplitter.Config value) { this.frame = value; return this; }
    }

    /** 客户端快照，供界面表格展示。 */
    public static final class ClientInfo {
        private final int id;
        private final InetSocketAddress address;
        private final long connectedAt;
        private final long bytesIn;
        private final long bytesOut;

        ClientInfo(int id, InetSocketAddress address, long connectedAt, long bytesIn, long bytesOut) {
            this.id = id;
            this.address = address;
            this.connectedAt = connectedAt;
            this.bytesIn = bytesIn;
            this.bytesOut = bytesOut;
        }

        public int getId() { return id; }
        public InetSocketAddress getAddress() { return address; }
        public long getConnectedAt() { return connectedAt; }
        public long getBytesIn() { return bytesIn; }
        public long getBytesOut() { return bytesOut; }
    }

    private final Options options;
    private final SocketSessionListener listener;
    private final SocketStats stats = new SocketStats();
    private final ConcurrentSkipListMap<Integer, SocketConnection> clients = new ConcurrentSkipListMap<>();
    private final AtomicInteger nextId = new AtomicInteger();
    private final Object lock = new Object();
    private volatile ServerSocket serverSocket;
    private volatile boolean closed;
    private volatile boolean echo;
    private boolean opened;
    private Thread acceptThread;

    public TcpServerSession(Options options, SocketSessionListener listener) {
        this.options = Objects.requireNonNull(options, "options");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.echo = options.echo;
    }

    @Override
    public Kind kind() {
        return Kind.TCP_SERVER;
    }

    @Override
    public void open() throws IOException {
        ServerSocket server;
        synchronized (lock) {
            if (closed) throw new SocketOpenException(SocketError.NOT_CONNECTED, "Session already closed", null);
            if (opened) {
                throw new IllegalStateException("Session already opened");
            }
            opened = true;
            server = new ServerSocket();
            try {
                server.setReuseAddress(true);
                InetAddress address = InetAddress.getByName(options.bindHost.isEmpty() ? "0.0.0.0" : options.bindHost);
                server.bind(new InetSocketAddress(address, options.port), 128);
            } catch (IOException | IllegalArgumentException error) {
                closed = true;
                server.close();
                throw new SocketOpenException(SocketError.BIND_FAILED,
                        "Cannot listen on " + options.bindHost + ":" + options.port + ": " + error.getMessage(), error);
            }
            serverSocket = server;
            acceptThread = DaemonThreads.factory(THREAD_PREFIX + "-tcp-server-accept").newThread(this::acceptLoop);
            listener.onEvent(SocketEvent.connected(localAddress(), null));
            acceptThread.start();
        }
    }

    /** 实际监听端口；未打开时为 -1。 */
    public int actualPort() {
        ServerSocket server = serverSocket;
        return server == null ? -1 : server.getLocalPort();
    }

    private void acceptLoop() {
        ServerSocket server = serverSocket;
        while (!closed) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException error) {
                if (!closed) {
                    listener.onEvent(SocketEvent.error(SocketError.RECEIVE_FAILED, null, error.getMessage()));
                    close();
                }
                return;
            }
            InetSocketAddress peer = (InetSocketAddress) socket.getRemoteSocketAddress();
            if (clients.size() >= options.maxClients || closed) {
                closeQuietly(socket);
                listener.onEvent(SocketEvent.error(SocketError.CLIENT_REJECTED, peer,
                        "Max clients reached (" + options.maxClients + ")"));
                continue;
            }
            try {
                socket.setTcpNoDelay(options.noDelay);
            } catch (IOException ignored) {
                // 选项失败不影响收发
            }
            int id = nextId.incrementAndGet();
            SocketConnection connection = new SocketConnection(id, socket, socket, options.queueCapacity,
                    options.frame, "tcp-server", new ClientCallback());
            clients.put(id, connection);
            listener.onEvent(SocketEvent.clientJoined(id, peer));
            connection.start();
            if (closed) {
                connection.close(SocketEvent.Reason.SERVER_STOPPED, "Server stopped");
            }
        }
    }

    @Override
    public boolean isOpen() {
        return !closed && serverSocket != null;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    public boolean isEcho() {
        return echo;
    }

    /** 回显模式可在运行期切换。 */
    public void setEcho(boolean value) {
        this.echo = value;
    }

    /** 运行期修改分帧方式，对现有与新接入的客户端生效。 */
    public void setFrameConfig(SocketFrameSplitter.Config config) {
        options.frame(config);
        for (SocketConnection connection : clients.values()) {
            connection.setFrameConfig(config);
        }
    }

    /** 当前客户端快照，按编号升序。 */
    public List<ClientInfo> clients() {
        List<ClientInfo> out = new ArrayList<>(clients.size());
        for (SocketConnection c : clients.values()) {
            out.add(new ClientInfo(c.id(), c.remote(), c.connectedAt(), c.bytesIn(), c.bytesOut()));
        }
        return out;
    }

    /** 发给全部客户端。 */
    @Override
    public void send(byte[] data) {
        sendTo(new ArrayList<>(clients.keySet()), data);
    }

    @Override
    public void sendAwait(byte[] data, long timeoutMillis) throws InterruptedException {
        sendToAwait(new ArrayList<>(clients.keySet()), data, timeoutMillis);
    }

    /** 发给一个客户端。 */
    public void sendTo(int clientId, byte[] data) {
        sendTo(Arrays.asList(clientId), data);
    }

    /**
     * 发给指定客户端。逐个入队，某个失败不影响其余；全部尝试后若有失败则抛出第一个错误并列出失败的编号。
     */
    public void sendTo(Collection<Integer> clientIds, byte[] data) {
        List<SocketConnection> targets = resolve(clientIds);
        SocketSendException first = null;
        List<Integer> failed = new ArrayList<>();
        for (SocketConnection target : targets) {
            try {
                target.enqueue(data);
            } catch (SocketSendException error) {
                if (first == null) {
                    first = error;
                }
                failed.add(target.id());
            }
        }
        if (first != null) {
            throw new SocketSendException(first.getError(), first.getMessage() + " (clients " + failed + ")");
        }
    }

    /** 发给指定客户端，队列满时等待（后台线程调用）。 */
    public void sendToAwait(Collection<Integer> clientIds, byte[] data, long timeoutMillis)
            throws InterruptedException {
        for (SocketConnection target : resolve(clientIds)) {
            target.enqueueAwait(data, timeoutMillis);
        }
    }

    private List<SocketConnection> resolve(Collection<Integer> clientIds) {
        if (closed) {
            throw new SocketSendException(SocketError.NOT_CONNECTED, "Server stopped");
        }
        if (clientIds.isEmpty()) {
            throw new SocketSendException(SocketError.NO_SUCH_CLIENT, "No client connected or selected");
        }
        List<SocketConnection> targets = new ArrayList<>(clientIds.size());
        for (Integer id : clientIds) {
            SocketConnection connection = clients.get(id);
            if (connection == null) {
                throw new SocketSendException(SocketError.NO_SUCH_CLIENT, "No such client #" + id);
            }
            targets.add(connection);
        }
        return targets;
    }

    /** 断开一个客户端；不存在时返回 false。 */
    public boolean disconnect(int clientId) {
        SocketConnection connection = clients.get(clientId);
        if (connection == null) {
            return false;
        }
        connection.close(SocketEvent.Reason.KICKED, "Disconnected by server");
        return true;
    }

    @Override
    public InetSocketAddress localAddress() {
        ServerSocket server = serverSocket;
        return server == null ? null : (InetSocketAddress) server.getLocalSocketAddress();
    }

    @Override
    public SocketStats stats() {
        return stats;
    }

    @Override
    public void close() {
        ServerSocket server;
        synchronized (lock) {
            if (closed && serverSocket == null) {
                return;
            }
            closed = true;
            server = serverSocket;
            serverSocket = null;
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 关闭监听套接字失败时仍继续断开客户端
            }
        }
        for (SocketConnection connection : new ArrayList<>(clients.values())) {
            connection.close(SocketEvent.Reason.SERVER_STOPPED, "Server stopped");
        }
        if (server != null) {
            listener.onEvent(SocketEvent.disconnected(SocketEvent.Reason.LOCAL_CLOSE, "Server stopped"));
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 拒绝连接时的清理
        }
    }

    private final class ClientCallback implements SocketConnection.Callback {
        @Override
        public void onChunk(SocketConnection source, byte[] data, int length) {
            stats.recordReceived(length);
            if (echo) {
                try {
                    source.enqueue(Arrays.copyOf(data, length));
                } catch (SocketSendException full) {
                    listener.onEvent(SocketEvent.error(full.getError(), source.remote(), full.getMessage()));
                }
            }
        }

        @Override
        public void onFrame(SocketConnection source, byte[] frame) {
            listener.onEvent(SocketEvent.received(frame, source.remote(), source.id()));
        }

        @Override
        public void onSent(SocketConnection source, byte[] data) {
            stats.recordSent(data.length);
            listener.onEvent(SocketEvent.sent(data, source.remote(), source.id()));
        }

        @Override
        public void onClosed(SocketConnection source, SocketEvent.Reason reason, String detail) {
            clients.remove(source.id(), source);
            listener.onEvent(SocketEvent.clientLeft(source.id(), source.remote(), reason, detail));
        }
    }
}
