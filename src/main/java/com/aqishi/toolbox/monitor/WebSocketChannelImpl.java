package com.aqishi.toolbox.monitor;

import java.util.Base64;
import java.util.function.Consumer;

/**
 * 远程桌面的中转 WebSocket 通道实现 (带防重合拢标记)。
 */
public class WebSocketChannelImpl implements DesktopChannel {

    private final DesktopSignalClient client;
    private Consumer<DesktopMessage> messageListener;
    private Runnable closeListener;
    private final String peerId;
    private volatile boolean closed = false;

    public WebSocketChannelImpl(DesktopSignalClient client, String peerId) {
        this.client = client;
        this.peerId = peerId;
    }

    @Override
    public void send(DesktopMessage msg) {
        if (client == null || !client.isOpen()) {
            return;
        }
        byte[] payload = msg.getPayload();
        byte[] raw = new byte[1 + payload.length];
        raw[0] = msg.getType();
        System.arraycopy(payload, 0, raw, 1, payload.length);
        
        String base64 = Base64.getEncoder().encodeToString(raw);
        client.sendToPeer(peerId, base64);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        
        if (closeListener != null) {
            closeListener.run();
        }
    }

    @Override
    public boolean isP2P() {
        return false;
    }

    @Override
    public String getStatusDescription() {
        return "中转连接 (WebSocket)";
    }

    @Override
    public void setMessageListener(Consumer<DesktopMessage> listener) {
        this.messageListener = listener;
    }

    @Override
    public void setCloseListener(Runnable listener) {
        this.closeListener = listener;
    }

    /**
     * 收到底层 WebSocket client 投递过来的二进制数据包。
     */
    public void handleRawMessage(byte[] raw) {
        if (raw == null || raw.length < 1) return;
        byte type = raw[0];
        byte[] payload = new byte[raw.length - 1];
        System.arraycopy(raw, 1, payload, 0, payload.length);
        
        if (messageListener != null) {
            messageListener.accept(new DesktopMessage(type, payload));
        }
    }

    public void handleClose() {
        if (closed) return;
        closed = true;
        
        if (closeListener != null) {
            closeListener.run();
        }
    }
}
