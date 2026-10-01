package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.feature.network.domain.SocketFrameSplitter;
import com.aqishi.toolbox.feature.network.domain.SocketSendException;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一条已建立的 TCP 连接：一个读线程、一个写线程、一个有界发送队列。
 *
 * <p>写线程独占输出流，调用方只入队，因此界面永远不会被慢对端卡住；队列满时立即报错而不是无限堆积。
 * 读线程负责分帧：空闲超时模式借助 SO_TIMEOUT 在无数据时醒来冲刷，不需要额外的定时线程。</p>
 */
final class SocketConnection {

    /** 连接的回调，都在本连接的读/写线程上调用。 */
    interface Callback {
        /** 读到原始数据块（用于统计与回显）；data 在返回后会被复用，需要保留时自行复制。 */
        void onChunk(SocketConnection connection, byte[] data, int length);

        /** 分帧后得到完整一帧。 */
        void onFrame(SocketConnection connection, byte[] frame);

        /** 一条数据已完整写出。 */
        void onSent(SocketConnection connection, byte[] data);

        /** 连接关闭，恰好调用一次。 */
        void onClosed(SocketConnection connection, SocketEvent.Reason reason, String detail);
    }

    private static final int READ_BUFFER = 64 * 1024;

    private final int id;
    private final Socket plain;
    private final Socket socket;
    private final InetSocketAddress remote;
    private final InetSocketAddress local;
    private final long connectedAt = System.currentTimeMillis();
    private final AtomicLong bytesIn = new AtomicLong();
    private final AtomicLong bytesOut = new AtomicLong();
    private final LinkedBlockingQueue<byte[]> queue;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Callback callback;
    private final Thread reader;
    private final Thread writer;
    private volatile SocketFrameSplitter.Config frameConfig;

    /**
     * @param plain  底层明文套接字；TLS 时关闭它能立即打断阻塞中的 SSL 读，避免 SSLSocket.close 等锁
     * @param socket 实际读写的套接字（明文或 TLS）
     */
    SocketConnection(int id, Socket plain, Socket socket, int queueCapacity,
                     SocketFrameSplitter.Config frameConfig, String threadTag, Callback callback) {
        this.id = id;
        this.plain = plain;
        this.socket = socket;
        this.remote = (InetSocketAddress) socket.getRemoteSocketAddress();
        this.local = (InetSocketAddress) socket.getLocalSocketAddress();
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
        this.frameConfig = frameConfig == null ? SocketFrameSplitter.Config.raw() : frameConfig;
        this.callback = callback;
        String prefix = SocketSession.THREAD_PREFIX + "-" + threadTag + "-" + id;
        this.reader = DaemonThreads.factory(prefix + "-read").newThread(this::readLoop);
        this.writer = DaemonThreads.factory(prefix + "-write").newThread(this::writeLoop);
    }

    void start() {
        reader.start();
        writer.start();
    }

    int id() {
        return id;
    }

    InetSocketAddress remote() {
        return remote;
    }

    InetSocketAddress local() {
        return local;
    }

    long connectedAt() {
        return connectedAt;
    }

    long bytesIn() {
        return bytesIn.get();
    }

    long bytesOut() {
        return bytesOut.get();
    }

    boolean isClosed() {
        return closed.get();
    }

    void setFrameConfig(SocketFrameSplitter.Config config) {
        this.frameConfig = config == null ? SocketFrameSplitter.Config.raw() : config;
    }

    /** 立即入队；队列满或已关闭时抛出。 */
    void enqueue(byte[] data) {
        ensureOpen();
        if (!queue.offer(data)) {
            throw new SocketSendException(SocketError.SEND_QUEUE_FULL,
                    "Send queue full (" + queue.size() + " pending) for " + SocketEvent.format(remote));
        }
    }

    /** 队列满时最多等待 timeoutMillis；超时仍满则抛出。 */
    void enqueueAwait(byte[] data, long timeoutMillis) throws InterruptedException {
        ensureOpen();
        if (!queue.offer(data, Math.max(0, timeoutMillis), TimeUnit.MILLISECONDS)) {
            ensureOpen();
            throw new SocketSendException(SocketError.SEND_QUEUE_FULL,
                    "Send queue still full after " + timeoutMillis + " ms for " + SocketEvent.format(remote));
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new SocketSendException(SocketError.NOT_CONNECTED,
                    "Connection closed: " + SocketEvent.format(remote));
        }
    }

    /** 关闭连接；只有第一次调用生效并触发 onClosed。 */
    void close(SocketEvent.Reason reason, String detail) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly(plain);
        if (socket != plain) {
            closeQuietly(socket);
        }
        writer.interrupt();
        queue.clear();
        callback.onClosed(this, reason, detail);
    }

    private void readLoop() {
        byte[] buffer = new byte[READ_BUFFER];
        SocketFrameSplitter.Config activeConfig = frameConfig;
        SocketFrameSplitter splitter = new SocketFrameSplitter(activeConfig);
        int currentTimeout = -1;
        try {
            InputStream in = socket.getInputStream();
            while (!closed.get()) {
                if (activeConfig != frameConfig) {
                    // 分帧方式在连接期间被修改：先把旧缓冲吐出来，再换新的切分器
                    emitRemaining(splitter);
                    activeConfig = frameConfig;
                    splitter = new SocketFrameSplitter(activeConfig);
                }
                long deadline = splitter.nextDeadline();
                int timeout = deadline < 0 ? 0 : (int) Math.max(1, deadline - nowMillis());
                if (timeout != currentTimeout) {
                    socket.setSoTimeout(timeout);
                    currentTimeout = timeout;
                }
                int read;
                try {
                    read = in.read(buffer);
                } catch (SocketTimeoutException idle) {
                    emit(splitter.poll(nowMillis()));
                    continue;
                }
                if (read < 0) {
                    emitRemaining(splitter);
                    close(SocketEvent.Reason.PEER_CLOSED, "Connection closed by peer");
                    return;
                }
                if (read == 0) {
                    continue;
                }
                // The user may have changed framing while read() was blocked with no pending data.
                if (activeConfig != frameConfig) {
                    emitRemaining(splitter);
                    activeConfig = frameConfig;
                    splitter = new SocketFrameSplitter(activeConfig);
                }
                bytesIn.addAndGet(read);
                callback.onChunk(this, buffer, read);
                emit(splitter.feed(buffer, 0, read, nowMillis()));
            }
        } catch (IOException | IllegalArgumentException error) {
            if (!closed.get()) {
                emitRemaining(splitter);
                close(SocketEvent.Reason.IO_ERROR, String.valueOf(error.getMessage()));
            }
        }
    }

    private void writeLoop() {
        try {
            OutputStream out = socket.getOutputStream();
            while (!closed.get()) {
                byte[] data = queue.take();
                out.write(data);
                out.flush();
                bytesOut.addAndGet(data.length);
                callback.onSent(this, data);
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch (IOException error) {
            close(SocketEvent.Reason.IO_ERROR, String.valueOf(error.getMessage()));
        }
    }

    private void emit(List<byte[]> frames) {
        for (byte[] frame : frames) {
            callback.onFrame(this, frame);
        }
    }

    private void emitRemaining(SocketFrameSplitter splitter) {
        byte[] rest = splitter.drain();
        if (rest != null) {
            callback.onFrame(this, rest);
        }
    }

    private static long nowMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private static void closeQuietly(Socket target) {
        try {
            target.close();
        } catch (IOException ignored) {
            // 关闭路径上的异常没有可做的补救
        }
    }
}
