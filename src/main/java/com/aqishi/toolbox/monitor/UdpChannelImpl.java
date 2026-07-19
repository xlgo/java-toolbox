package com.aqishi.toolbox.monitor;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 远程桌面的 UDP P2P 数据通道实现。
 *
 * <p>桌面帧和文件块经常超过 UDP 单包 65507 字节的硬限制，因此通道会把每条
 * DesktopMessage 拆成 MTU 安全的小数据报，并在接收端按消息 ID 重组。这样既避免
 * DatagramSocket 的 "Message too long"，也避免依赖容易被 NAT/防火墙丢弃的 IP 分片。</p>
 */
public class UdpChannelImpl implements DesktopChannel {

    private static final int MAGIC = 0x4A544255; // "JTBU"
    private static final byte PROTOCOL_VERSION = 1;
    private static final int HEADER_SIZE = 18;
    private static final int MAX_DATAGRAM_SIZE = 1200;
    private static final int MAX_FRAGMENT_PAYLOAD = MAX_DATAGRAM_SIZE - HEADER_SIZE;
    private static final int MAX_MESSAGE_SIZE = 16 * 1024 * 1024;
    private static final int MAX_PENDING_MESSAGES = 128;
    private static final long REASSEMBLY_TIMEOUT_MS = 5000L;
    private static final byte[] HANDSHAKE_REQ = "P2P_HANDSHAKE_REQ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HANDSHAKE_RESP = "P2P_HANDSHAKE_RESP".getBytes(StandardCharsets.UTF_8);

    private final DatagramSocket socket;
    private final InetSocketAddress peerAddress;
    private final AtomicInteger nextMessageId = new AtomicInteger();
    private final Object sendLock = new Object();
    private final Map<Integer, ReassemblyBuffer> pendingMessages = new HashMap<>();

    private volatile Consumer<DesktopMessage> messageListener;
    private volatile Runnable closeListener;
    private volatile boolean closed = false;
    private Thread receiveThread;

    public UdpChannelImpl(DatagramSocket socket, InetSocketAddress peerAddress) {
        if (socket == null) throw new IllegalArgumentException("socket must not be null");
        if (peerAddress == null) throw new IllegalArgumentException("peerAddress must not be null");
        this.socket = socket;
        this.peerAddress = peerAddress;
        startReceiver();
    }

    private void startReceiver() {
        receiveThread = new Thread(() -> {
            byte[] buf = new byte[MAX_DATAGRAM_SIZE];
            while (!closed && !socket.isClosed()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    if (!isExpectedPeer((InetSocketAddress) packet.getSocketAddress())) {
                        continue;
                    }
                    handleDatagram(packet.getData(), packet.getOffset(), packet.getLength());
                } catch (IOException e) {
                    if (closed || socket.isClosed()) break;
                    close();
                    break;
                } catch (RuntimeException ignored) {
                    // 丢弃格式错误的数据报，保持通道继续工作
                }
            }
        }, "UdpChannel-Receiver");
        receiveThread.setDaemon(true);
        receiveThread.start();
    }

    private boolean isExpectedPeer(InetSocketAddress source) {
        return source != null
                && source.getPort() == peerAddress.getPort()
                && source.getAddress() != null
                && source.getAddress().equals(peerAddress.getAddress());
    }

    private void handleDatagram(byte[] data, int offset, int length) {
        IceProbeCodec.ParsedMessage iceProbe = IceProbeCodec.parse(data, offset, length);
        if (iceProbe != null) {
            if (iceProbe.getType() == IceProbeCodec.BINDING_REQUEST) {
                sendIceBindingResponse(iceProbe.getTransactionId());
            }
            return;
        }
        if (isStunPacket(data, offset, length)) {
            return;
        }

        // 打洞定时器停止前可能仍有少量握手尾包在网络中。数据通道需要继续确认请求，
        // 但绝不能把 ASCII 首字节 'P' 当成 DesktopMessage 类型交给业务层。
        if (matches(data, offset, length, HANDSHAKE_REQ)) {
            sendHandshakeResponse();
            return;
        }
        if (matches(data, offset, length, HANDSHAKE_RESP)) {
            return;
        }

        if (length < HEADER_SIZE || ByteBuffer.wrap(data, offset, 4).getInt() != MAGIC) {
            // 兼容旧版单数据报格式：[type][payload]
            if (length >= 1) {
                byte type = data[offset];
                byte[] payload = Arrays.copyOfRange(data, offset + 1, offset + length);
                deliver(new DesktopMessage(type, payload));
            }
            return;
        }

        ByteBuffer header = ByteBuffer.wrap(data, offset, HEADER_SIZE);
        int magic = header.getInt();
        byte version = header.get();
        byte type = header.get();
        int messageId = header.getInt();
        int fragmentIndex = header.getShort() & 0xFFFF;
        int fragmentCount = header.getShort() & 0xFFFF;
        int totalLength = header.getInt();

        int fragmentLength = length - HEADER_SIZE;
        if (magic != MAGIC || version != PROTOCOL_VERSION
                || fragmentCount < 1 || fragmentIndex >= fragmentCount
                || totalLength < 0 || totalLength > MAX_MESSAGE_SIZE
                || fragmentLength < 0 || fragmentLength > MAX_FRAGMENT_PAYLOAD) {
            return;
        }
        int expectedFragmentCount = Math.max(1,
                (totalLength + MAX_FRAGMENT_PAYLOAD - 1) / MAX_FRAGMENT_PAYLOAD);
        int expectedFragmentLength = fragmentIndex < fragmentCount - 1
                ? MAX_FRAGMENT_PAYLOAD
                : totalLength - fragmentIndex * MAX_FRAGMENT_PAYLOAD;
        if (fragmentCount != expectedFragmentCount || fragmentLength != expectedFragmentLength) return;

        cleanupExpiredMessages(System.currentTimeMillis());
        ReassemblyBuffer assembly = pendingMessages.get(messageId);
        if (assembly == null) {
            if (pendingMessages.size() >= MAX_PENDING_MESSAGES) {
                removeOldestPendingMessage();
            }
            assembly = new ReassemblyBuffer(type, fragmentCount, totalLength, System.currentTimeMillis());
            pendingMessages.put(messageId, assembly);
        } else if (assembly.type != type
                || assembly.fragments.length != fragmentCount
                || assembly.totalLength != totalLength) {
            pendingMessages.remove(messageId);
            return;
        }

        if (assembly.fragments[fragmentIndex] == null) {
            byte[] fragment = Arrays.copyOfRange(data, offset + HEADER_SIZE, offset + length);
            assembly.fragments[fragmentIndex] = fragment;
            assembly.receivedFragments++;
            assembly.receivedBytes += fragment.length;
        }

        if (assembly.receivedFragments == fragmentCount) {
            pendingMessages.remove(messageId);
            if (assembly.receivedBytes != totalLength) return;

            byte[] payload = new byte[totalLength];
            int position = 0;
            for (byte[] fragment : assembly.fragments) {
                if (fragment == null || position + fragment.length > payload.length) return;
                System.arraycopy(fragment, 0, payload, position, fragment.length);
                position += fragment.length;
            }
            if (position == payload.length) {
                deliver(new DesktopMessage(type, payload));
            }
        }
    }

    private static boolean isStunPacket(byte[] data, int offset, int length) {
        return length >= 20
                && data[offset + 4] == 0x21
                && data[offset + 5] == 0x12
                && (data[offset + 6] & 0xFF) == 0xA4
                && data[offset + 7] == 0x42;
    }

    private static boolean matches(byte[] data, int offset, int length, byte[] expected) {
        if (length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) return false;
        }
        return true;
    }

    private void sendHandshakeResponse() {
        synchronized (sendLock) {
            if (closed || socket.isClosed()) return;
            try {
                socket.send(new DatagramPacket(HANDSHAKE_RESP, HANDSHAKE_RESP.length, peerAddress));
            } catch (IOException ignored) {
                // 对端通常已经完成握手；尾包确认失败不应关闭已建立的数据通道
            }
        }
    }

    private void sendIceBindingResponse(byte[] transactionId) {
        synchronized (sendLock) {
            if (closed || socket.isClosed()) return;
            try {
                byte[] response =
                        IceProbeCodec.createBindingSuccess(transactionId, peerAddress);
                socket.send(new DatagramPacket(
                        response, response.length, peerAddress));
            } catch (IOException | IllegalArgumentException ignored) {
                // The selected data channel remains valid even if a duplicate
                // connectivity-check response is lost during handoff.
            }
        }
    }

    private void cleanupExpiredMessages(long now) {
        Iterator<Map.Entry<Integer, ReassemblyBuffer>> it = pendingMessages.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().createdAt > REASSEMBLY_TIMEOUT_MS) {
                it.remove();
            }
        }
    }

    private void removeOldestPendingMessage() {
        Integer oldestId = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<Integer, ReassemblyBuffer> entry : pendingMessages.entrySet()) {
            if (entry.getValue().createdAt < oldestTime) {
                oldestTime = entry.getValue().createdAt;
                oldestId = entry.getKey();
            }
        }
        if (oldestId != null) pendingMessages.remove(oldestId);
    }

    private void deliver(DesktopMessage message) {
        Consumer<DesktopMessage> listener = messageListener;
        if (listener != null) {
            listener.accept(message);
        }
    }

    @Override
    public void send(DesktopMessage msg) {
        if (msg == null || closed || socket.isClosed()) return;

        byte[] payload = msg.getPayload();
        if (payload.length > MAX_MESSAGE_SIZE) {
            throw new IllegalArgumentException("UDP message is too large: " + payload.length);
        }

        int fragmentCount = Math.max(1, (payload.length + MAX_FRAGMENT_PAYLOAD - 1) / MAX_FRAGMENT_PAYLOAD);
        if (fragmentCount > 0xFFFF) {
            throw new IllegalArgumentException("UDP message has too many fragments: " + fragmentCount);
        }

        int messageId = nextMessageId.incrementAndGet();
        synchronized (sendLock) {
            for (int fragmentIndex = 0; fragmentIndex < fragmentCount; fragmentIndex++) {
                if (closed || socket.isClosed()) return;

                int payloadOffset = fragmentIndex * MAX_FRAGMENT_PAYLOAD;
                int fragmentLength = Math.min(MAX_FRAGMENT_PAYLOAD, payload.length - payloadOffset);
                byte[] raw = new byte[HEADER_SIZE + fragmentLength];
                ByteBuffer header = ByteBuffer.wrap(raw);
                header.putInt(MAGIC);
                header.put(PROTOCOL_VERSION);
                header.put(msg.getType());
                header.putInt(messageId);
                header.putShort((short) fragmentIndex);
                header.putShort((short) fragmentCount);
                header.putInt(payload.length);
                if (fragmentLength > 0) {
                    System.arraycopy(payload, payloadOffset, raw, HEADER_SIZE, fragmentLength);
                }

                try {
                    socket.send(new DatagramPacket(raw, raw.length, peerAddress));
                } catch (IOException e) {
                    if (!closed && !socket.isClosed()) {
                        System.err.println("UDP 数据发送失败: " + e.getMessage());
                    }
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        pendingMessages.clear();

        if (!socket.isClosed()) {
            socket.close();
        }

        Runnable listener = closeListener;
        if (listener != null) {
            listener.run();
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

    private static final class ReassemblyBuffer {
        private final byte type;
        private final byte[][] fragments;
        private final int totalLength;
        private final long createdAt;
        private int receivedFragments;
        private int receivedBytes;

        private ReassemblyBuffer(byte type, int fragmentCount, int totalLength, long createdAt) {
            this.type = type;
            this.fragments = new byte[fragmentCount][];
            this.totalLength = totalLength;
            this.createdAt = createdAt;
        }
    }
}
