package com.aqishi.toolbox.feature.monitor.infra;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.aqishi.toolbox.feature.monitor.domain.DesktopChannel;
import com.aqishi.toolbox.feature.monitor.domain.DesktopMessage;
import com.aqishi.toolbox.util.Errors;

/**
 * 远程桌面的 P2P TCP 直连通道实现。
 *
 * <p>Frame: {@code int32 length | byte type | payload[length]}. This is the raw
 * transport only; application data is protected by
 * {@link SecureDesktopChannel}, which keeps {@link #setMaxInboundMessageSize}
 * small until the handshake authenticated the peer.</p>
 *
 * <p>The reader thread starts when the first message listener is set, so no
 * frame can be read (and dropped) before someone is listening.</p>
 */
public class SocketChannelImpl implements DesktopChannel {

    /** Hard upper bound for any frame, even after authentication. */
    public static final int MAX_FRAME_SIZE = 20 * 1024 * 1024;

    private final Socket socket;
    private final DataOutputStream dos;
    private final AtomicBoolean readerStarted = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile Consumer<DesktopMessage> messageListener;
    private volatile Runnable closeListener;
    private volatile int maxFrameSize = MAX_FRAME_SIZE;

    public SocketChannelImpl(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true); // 启用 TCP_NODELAY 以减少屏幕图像和控制事件延迟
        this.dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    @Override
    public synchronized void send(DesktopMessage msg) {
        if (closed.get()) return;
        try {
            byte[] payload = msg.getPayload();
            dos.writeInt(payload.length);
            dos.writeByte(msg.getType());
            if (payload.length > 0) {
                dos.write(payload);
            }
            dos.flush();
        } catch (IOException e) {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            socket.close();
        } catch (Exception ignored) {
            Errors.ignored("socket close failed; channel already marked closed", ignored);
        }

        Runnable listener = closeListener;
        if (listener != null) {
            listener.run();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isP2P() {
        return true;
    }

    @Override
    public String getStatusDescription() {
        return "直连连接 (P2P TCP)";
    }

    @Override
    public void setMessageListener(Consumer<DesktopMessage> listener) {
        this.messageListener = listener;
        if (listener != null && readerStarted.compareAndSet(false, true)) {
            startReadThread();
        }
    }

    @Override
    public void setCloseListener(Runnable listener) {
        this.closeListener = listener;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        SocketAddress address = socket.getRemoteSocketAddress();
        return address instanceof InetSocketAddress ? (InetSocketAddress) address : null;
    }

    @Override
    public void setMaxInboundMessageSize(int bytes) {
        maxFrameSize = Math.max(0, Math.min(bytes, MAX_FRAME_SIZE));
    }

    private void startReadThread() {
        Thread readThread = new Thread(() -> {
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                while (!closed.get()) {
                    int len = dis.readInt();
                    if (len < 0 || len > maxFrameSize) {
                        throw new IOException("frame length " + len + " exceeds limit " + maxFrameSize);
                    }
                    byte type = dis.readByte();
                    byte[] payload = new byte[len];
                    if (len > 0) {
                        dis.readFully(payload);
                    }

                    Consumer<DesktopMessage> listener = messageListener;
                    if (listener != null) {
                        listener.accept(new DesktopMessage(type, payload));
                    }
                }
            } catch (IOException | RuntimeException e) {
                close();
            }
        }, "SocketChannel-Reader");
        readThread.setDaemon(true);
        readThread.start();
    }
}
