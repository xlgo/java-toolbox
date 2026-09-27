package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.feature.network.domain.SocketFrameSplitter;
import com.aqishi.toolbox.feature.network.domain.SocketOpenException;
import com.aqishi.toolbox.feature.network.domain.SocketSendException;
import com.aqishi.toolbox.feature.network.domain.SocketStats;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.util.Objects;

/**
 * TCP 客户端会话：可选本地绑定、TLS、断线自动重连（指数退避，手动断开即停止）。
 */
public final class TcpClientSession implements SocketSession {

    /** 连接参数，setter 链式调用。 */
    public static final class Options {
        private String host = "127.0.0.1";
        private int port;
        private String localHost;
        private int localPort;
        private int connectTimeoutMillis = 5000;
        private boolean noDelay = true;
        private boolean keepAlive;
        private boolean tls;
        private boolean trustAll;
        private boolean autoReconnect;
        private long reconnectInitialMillis = 1000;
        private long reconnectMaxMillis = 10_000;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private SocketFrameSplitter.Config frame = SocketFrameSplitter.Config.raw();

        public Options host(String value) { this.host = Objects.requireNonNull(value, "host").trim(); return this; }
        public Options port(int value) { this.port = value; return this; }
        /** 本地绑定地址，null 或空表示由系统选择。 */
        public Options localHost(String value) { this.localHost = value; return this; }
        /** 本地绑定端口，0 表示由系统选择。 */
        public Options localPort(int value) { this.localPort = value; return this; }
        public Options connectTimeoutMillis(int value) { this.connectTimeoutMillis = value; return this; }
        public Options noDelay(boolean value) { this.noDelay = value; return this; }
        public Options keepAlive(boolean value) { this.keepAlive = value; return this; }
        public Options tls(boolean value) { this.tls = value; return this; }
        /** 信任所有证书并跳过主机名校验，仅用于调试自签名服务。 */
        public Options trustAll(boolean value) { this.trustAll = value; return this; }
        public Options autoReconnect(boolean value) { this.autoReconnect = value; return this; }
        public Options reconnectInitialMillis(long value) { this.reconnectInitialMillis = Math.max(10, value); return this; }
        public Options reconnectMaxMillis(long value) { this.reconnectMaxMillis = Math.max(10, value); return this; }
        public Options queueCapacity(int value) { this.queueCapacity = value; return this; }
        public Options frame(SocketFrameSplitter.Config value) { this.frame = value; return this; }

        public String getHost() { return host; }
        public int getPort() { return port; }
        public boolean isTls() { return tls; }
        public boolean isAutoReconnect() { return autoReconnect; }
    }

    private final Options options;
    private final SocketSessionListener listener;
    private final SocketStats stats = new SocketStats();
    private final Object lock = new Object();
    private SocketConnection connection;
    private boolean opened;
    private volatile boolean closed;
    private volatile boolean manualClose;
    private Thread reconnectThread;
    /** 正在连接中的套接字：close() 关掉它能立即打断阻塞的 connect，而不必等满连接超时。 */
    private volatile Socket connecting;

    public TcpClientSession(Options options, SocketSessionListener listener) {
        this.options = Objects.requireNonNull(options, "options");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public Kind kind() {
        return Kind.TCP_CLIENT;
    }

    public Options options() {
        return options;
    }

    @Override
    public void open() throws IOException {
        synchronized (lock) {
            if (opened) {
                throw new IllegalStateException("Session already opened");
            }
            opened = true;
        }
        Socket[] pair;
        try {
            pair = connectSocket();
        } catch (IOException error) {
            closed = true;
            throw error;
        }
        install(pair);
    }

    /** 建立连接；返回 {明文套接字, 实际读写套接字}。 */
    private Socket[] connectSocket() throws IOException {
        Socket plain = new Socket();
        connecting = plain;
        try {
            if (closed) {
                throw new SocketOpenException(SocketError.NOT_CONNECTED, "Session closed", null);
            }
            plain.setTcpNoDelay(options.noDelay);
            plain.setKeepAlive(options.keepAlive);
            if ((options.localHost != null && !options.localHost.trim().isEmpty()) || options.localPort > 0) {
                try {
                    InetAddress address = options.localHost == null || options.localHost.trim().isEmpty()
                            ? null : InetAddress.getByName(options.localHost.trim());
                    plain.bind(new InetSocketAddress(address, options.localPort));
                } catch (IOException bindError) {
                    throw new SocketOpenException(SocketError.BIND_FAILED,
                            "Cannot bind local address: " + bindError.getMessage(), bindError);
                }
            }
            try {
                plain.connect(new InetSocketAddress(options.host, options.port),
                        Math.max(0, options.connectTimeoutMillis));
            } catch (IOException | IllegalArgumentException connectError) {
                throw new SocketOpenException(SocketError.CONNECT_FAILED,
                        "Cannot connect to " + options.host + ":" + options.port + ": "
                                + connectError.getMessage(), connectError);
            }
            if (!options.tls) {
                return new Socket[]{plain, plain};
            }
            try {
                Socket ssl = SocketTls.handshake(plain, options.host, options.port,
                        options.trustAll, options.connectTimeoutMillis);
                return new Socket[]{plain, ssl};
            } catch (SSLException | GeneralSecurityException tlsError) {
                throw new SocketOpenException(SocketError.TLS_FAILED,
                        "TLS handshake failed: " + tlsError.getMessage(), tlsError);
            } catch (IOException tlsIo) {
                throw new SocketOpenException(SocketError.TLS_FAILED,
                        "TLS handshake failed: " + tlsIo.getMessage(), tlsIo);
            }
        } catch (IOException error) {
            try {
                plain.close();
            } catch (IOException ignored) {
                // 连接失败后的清理
            }
            throw error;
        } finally {
            connecting = null;
        }
    }

    private void install(Socket[] pair) {
        SocketConnection created = new SocketConnection(1, pair[0], pair[1], options.queueCapacity,
                options.frame, "tcp-client", new ConnectionCallback());
        synchronized (lock) {
            if (closed) {
                created.close(SocketEvent.Reason.LOCAL_CLOSE, "Closed during connect");
                return;
            }
            connection = created;
        }
        created.start();
        listener.onEvent(SocketEvent.connected(created.local(), created.remote()));
    }

    @Override
    public boolean isOpen() {
        SocketConnection current = connection;
        return !closed && current != null && !current.isClosed();
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void send(byte[] data) {
        current().enqueue(data);
    }

    @Override
    public void sendAwait(byte[] data, long timeoutMillis) throws InterruptedException {
        current().enqueueAwait(data, timeoutMillis);
    }

    private SocketConnection current() {
        SocketConnection current = connection;
        if (closed || current == null || current.isClosed()) {
            throw new SocketSendException(SocketError.NOT_CONNECTED, "Not connected");
        }
        return current;
    }

    /** 运行期修改分帧方式，对当前与后续重连的连接生效。 */
    public void setFrameConfig(SocketFrameSplitter.Config config) {
        options.frame(config);
        SocketConnection current = connection;
        if (current != null) {
            current.setFrameConfig(config);
        }
    }

    @Override
    public InetSocketAddress localAddress() {
        SocketConnection current = connection;
        return current == null ? null : current.local();
    }

    /** 当前连接的对端地址；未连接时为 null。 */
    public InetSocketAddress remoteAddress() {
        SocketConnection current = connection;
        return current == null ? null : current.remote();
    }

    @Override
    public SocketStats stats() {
        return stats;
    }

    @Override
    public void close() {
        SocketConnection current;
        Thread reconnect;
        synchronized (lock) {
            if (closed && manualClose) {
                return;
            }
            manualClose = true;
            closed = true;
            current = connection;
            reconnect = reconnectThread;
            lock.notifyAll();
        }
        if (reconnect != null) {
            reconnect.interrupt();
        }
        Socket pending = connecting;
        if (pending != null) {
            try {
                pending.close();
            } catch (IOException ignored) {
                // 打断进行中的连接
            }
        }
        if (current != null && !current.isClosed()) {
            current.close(SocketEvent.Reason.LOCAL_CLOSE, "Closed by user");
        }
    }

    // ------------------------------------------------------------------
    // 自动重连
    // ------------------------------------------------------------------

    private void onConnectionClosed(SocketEvent.Reason reason, String detail) {
        boolean reconnect;
        synchronized (lock) {
            reconnect = options.autoReconnect && !manualClose && !closed;
            if (!reconnect) {
                closed = true;
            }
        }
        listener.onEvent(SocketEvent.disconnected(reason, detail));
        if (reconnect) {
            Thread thread = DaemonThreads.factory(THREAD_PREFIX + "-tcp-client-reconnect")
                    .newThread(this::reconnectLoop);
            synchronized (lock) {
                if (closed) {
                    return;
                }
                reconnectThread = thread;
            }
            thread.start();
        }
    }

    private void reconnectLoop() {
        long delay = options.reconnectInitialMillis;
        int attempt = 0;
        while (true) {
            attempt++;
            listener.onEvent(SocketEvent.reconnecting(attempt, delay));
            if (!waitOrClosed(delay)) {
                return;
            }
            try {
                Socket[] pair = connectSocket();
                synchronized (lock) {
                    reconnectThread = null;
                }
                install(pair);
                return;
            } catch (IOException error) {
                if (closed) {
                    return;
                }
                SocketError code = error instanceof SocketOpenException
                        ? ((SocketOpenException) error).getError() : SocketError.CONNECT_FAILED;
                listener.onEvent(SocketEvent.error(code, null, error.getMessage()));
                delay = Math.min(options.reconnectMaxMillis, delay * 2);
            }
        }
    }

    /** 等待 delay 毫秒；期间会话被关闭则返回 false。用条件等待而非轮询。 */
    private boolean waitOrClosed(long delay) {
        long end = System.nanoTime() + delay * 1_000_000L;
        synchronized (lock) {
            try {
                while (!closed) {
                    long remaining = (end - System.nanoTime()) / 1_000_000L;
                    if (remaining <= 0) {
                        return true;
                    }
                    lock.wait(remaining);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private final class ConnectionCallback implements SocketConnection.Callback {
        @Override
        public void onChunk(SocketConnection source, byte[] data, int length) {
            stats.recordReceived(length);
        }

        @Override
        public void onFrame(SocketConnection source, byte[] frame) {
            listener.onEvent(SocketEvent.received(frame, source.remote(), 0));
        }

        @Override
        public void onSent(SocketConnection source, byte[] data) {
            stats.recordSent(data.length);
            listener.onEvent(SocketEvent.sent(data, source.remote(), 0));
        }

        @Override
        public void onClosed(SocketConnection source, SocketEvent.Reason reason, String detail) {
            onConnectionClosed(reason, detail);
        }
    }
}
