package com.aqishi.toolbox.monitor;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.function.Consumer;

/**
 * 远程桌面的 UDP P2P 数据通道实现。
 */
public class UdpChannelImpl implements DesktopChannel {

    private final DatagramSocket socket;
    private final InetSocketAddress peerAddress;
    private Consumer<DesktopMessage> messageListener;
    private Runnable closeListener;
    private volatile boolean closed = false;
    private Thread receiveThread;

    public UdpChannelImpl(DatagramSocket socket, InetSocketAddress peerAddress) {
        this.socket = socket;
        this.peerAddress = peerAddress;
        startReceiver();
    }

    private void startReceiver() {
        receiveThread = new Thread(() -> {
            byte[] buf = new byte[65535];
            while (!closed && !socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    
                    int len = packet.getLength();
                    if (len < 1) continue;

                    byte[] data = packet.getData();
                    byte type = data[0];
                    byte[] payload = new byte[len - 1];
                    System.arraycopy(data, 1, payload, 0, len - 1);

                    if (messageListener != null) {
                        messageListener.accept(new DesktopMessage(type, payload));
                    }
                } catch (IOException e) {
                    if (closed) break;
                    // 网络异常则自动关闭通道
                    close();
                    break;
                }
            }
        }, "UdpChannel-Receiver");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    @Override
    public void send(DesktopMessage msg) {
        if (closed || socket.isClosed()) return;
        try {
            byte[] payload = msg.getPayload();
            byte[] raw = new byte[1 + payload.length];
            raw[0] = msg.getType();
            System.arraycopy(payload, 0, raw, 1, payload.length);

            DatagramPacket packet = new DatagramPacket(raw, raw.length, peerAddress);
            socket.send(packet);
        } catch (IOException ignored) {
            // 忽略发送异常
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
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
        return "UDP 直连通道 (P2P打洞成功)";
    }

    @Override
    public void setMessageListener(Consumer<DesktopMessage> listener) {
        this.messageListener = listener;
    }

    @Override
    public void setCloseListener(Runnable listener) {
        this.closeListener = listener;
    }
}
