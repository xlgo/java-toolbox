package com.aqishi.toolbox.monitor;

import java.io.*;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * 远程桌面的 P2P TCP 直连通道实现。
 */
public class SocketChannelImpl implements DesktopChannel {

    private final Socket socket;
    private final DataOutputStream dos;
    private Consumer<DesktopMessage> messageListener;
    private Runnable closeListener;
    private volatile boolean running = true;
    private Thread readThread;

    public SocketChannelImpl(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true); // 启用 TCP_NODELAY 以减少屏幕图像和控制事件延迟
        this.dos = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        startReadThread();
    }

    @Override
    public synchronized void send(DesktopMessage msg) {
        if (!running) return;
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
        if (!running) return;
        running = false;
        try {
            socket.close();
        } catch (Exception ignored) {}

        if (closeListener != null) {
            closeListener.run();
        }
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
    }

    @Override
    public void setCloseListener(Runnable listener) {
        this.closeListener = listener;
    }

    private void startReadThread() {
        readThread = new Thread(() -> {
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                while (running) {
                    int len = dis.readInt();
                    if (len < 0 || len > 20 * 1024 * 1024) { // 限制包最大 20MB
                        throw new IOException("非法包长度: " + len);
                    }
                    byte type = dis.readByte();
                    byte[] payload = new byte[len];
                    if (len > 0) {
                        dis.readFully(payload);
                    }

                    if (messageListener != null) {
                        messageListener.accept(new DesktopMessage(type, payload));
                    }
                }
            } catch (IOException e) {
                close();
            }
        }, "SocketChannel-Reader");
        readThread.setDaemon(true);
        readThread.start();
    }
}
