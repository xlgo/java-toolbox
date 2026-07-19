package com.aqishi.toolbox.monitor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 轻量级 STUN 客户端（RFC 5389），用于获取 UDP socket 的公网反射地址。
 */
public class StunClient {

    private static final int MAGIC_COOKIE = 0x2112A442;
    private static final int DISCOVERY_TIMEOUT_MS = 2600;
    private static final int RECEIVE_SLICE_MS = 250;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 使用不同运营商的公开 STUN 服务，降低单一节点在特定网络不可达造成的失败率。
     */
    private static final StunServer[] STUN_SERVERS = {
        new StunServer("stun.cloudflare.com", 3478),
        new StunServer("global.stun.twilio.com", 3478),
        new StunServer("stun.l.google.com", 19302),
        new StunServer("stun1.l.google.com", 19302)
    };

    /**
     * 兼容旧调用：返回第一个探测成功的公网地址。
     */
    public static InetSocketAddress getPublicAddress(DatagramSocket socket) {
        List<InetSocketAddress> addresses = getPublicAddresses(socket);
        return addresses.isEmpty() ? null : addresses.get(0);
    }

    /**
     * 从同一个本地 UDP socket 同时向多个 STUN 服务发送 Binding Request。
     *
     * <p>所有请求先发出，再在一个总超时窗口内收集响应，避免串行超时拖慢整个
     * 打洞流程。返回多个映射还能让上层识别并尝试端口会随目标变化的 NAT。</p>
     */
    public static List<InetSocketAddress> getPublicAddresses(DatagramSocket socket) {
        return discoverPublicAddresses(socket).getMappedAddresses();
    }

    public static DiscoveryResult discoverPublicAddresses(DatagramSocket socket) {
        List<InetSocketAddress> servers = new ArrayList<>();
        for (StunServer server : STUN_SERVERS) {
            InetAddress address = resolveIPv4(server.host);
            if (address != null) {
                servers.add(new InetSocketAddress(address, server.port));
            }
        }
        return discoverPublicAddresses(socket, servers, DISCOVERY_TIMEOUT_MS);
    }

    static List<InetSocketAddress> getPublicAddresses(DatagramSocket socket,
                                                      List<InetSocketAddress> servers,
                                                      int discoveryTimeoutMs) {
        return discoverPublicAddresses(socket, servers, discoveryTimeoutMs).getMappedAddresses();
    }

    static DiscoveryResult discoverPublicAddresses(DatagramSocket socket,
                                                    List<InetSocketAddress> servers,
                                                    int discoveryTimeoutMs) {
        if (socket == null || socket.isClosed() || servers == null || servers.isEmpty()) {
            return new DiscoveryResult(0, 0, Collections.<InetSocketAddress, InetSocketAddress>emptyMap());
        }

        int originalTimeout = 0;
        Map<TransactionId, PendingRequest> pending = new LinkedHashMap<>();
        Map<InetSocketAddress, InetSocketAddress> observations = new LinkedHashMap<>();
        int datagramsSent = 0;

        try {
            originalTimeout = socket.getSoTimeout();

            for (InetSocketAddress serverAddress : servers) {
                if (serverAddress == null || serverAddress.isUnresolved()) continue;
                byte[] transactionBytes = new byte[12];
                RANDOM.nextBytes(transactionBytes);
                TransactionId transactionId = new TransactionId(transactionBytes);
                byte[] request = createBindingRequest(transactionBytes);

                try {
                    socket.send(new DatagramPacket(request, request.length, serverAddress));
                    pending.put(transactionId, new PendingRequest(serverAddress, request));
                    datagramsSent++;
                } catch (Exception ignored) {
                    // 单个 STUN 节点失败不影响其它节点
                }
            }

            long deadline = System.currentTimeMillis() + Math.max(1, discoveryTimeoutMs);
            long nextRetryAt = System.currentTimeMillis() + 600;
            byte[] responseBuffer = new byte[1024];
            while (!pending.isEmpty()) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;

                long untilRetry = Math.max(1, nextRetryAt - System.currentTimeMillis());
                socket.setSoTimeout((int) Math.min(Math.min(RECEIVE_SLICE_MS, remaining), untilRetry));
                DatagramPacket packet = new DatagramPacket(responseBuffer, responseBuffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    // Retry below.
                }

                if (packet.getLength() > 0) {
                    StunResponse response = parseBindingResponse(packet);
                    if (response != null) {
                        PendingRequest expected = pending.get(response.transactionId);
                        if (expected != null
                                && expected.server.equals(packet.getSocketAddress())
                                && response.mappedAddress != null) {
                            pending.remove(response.transactionId);
                            observations.put(expected.server, response.mappedAddress);
                        }
                    }
                }

                long now = System.currentTimeMillis();
                if (now >= nextRetryAt && !pending.isEmpty()) {
                    for (PendingRequest request : pending.values()) {
                        if (request.sendCount >= 3) continue;
                        try {
                            socket.send(new DatagramPacket(
                                    request.bytes, request.bytes.length, request.server));
                            request.sendCount++;
                            datagramsSent++;
                        } catch (Exception ignored) {
                        }
                    }
                    nextRetryAt = now + 700;
                }
            }
        } catch (Exception ignored) {
            // 返回已经收集到的结果
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (Exception ignored) {
            }
        }

        return new DiscoveryResult(servers.size(), datagramsSent, observations);
    }

    private static InetAddress resolveIPv4(String host) {
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address instanceof Inet4Address) return address;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static byte[] createBindingRequest(byte[] transactionId) {
        byte[] request = new byte[20];
        request[0] = 0x00;
        request[1] = 0x01; // Binding Request
        request[2] = 0x00;
        request[3] = 0x00; // Message Length
        request[4] = 0x21;
        request[5] = 0x12;
        request[6] = (byte) 0xA4;
        request[7] = 0x42;
        System.arraycopy(transactionId, 0, request, 8, 12);
        return request;
    }

    private static StunResponse parseBindingResponse(DatagramPacket packet) {
        byte[] response = packet.getData();
        int base = packet.getOffset();
        int packetLength = packet.getLength();
        if (packetLength < 20) return null;

        int messageType = readUnsignedShort(response, base);
        int cookie = readInt(response, base + 4);
        if (messageType != 0x0101 || cookie != MAGIC_COOKIE) return null;

        byte[] transactionBytes = Arrays.copyOfRange(response, base + 8, base + 20);
        TransactionId transactionId = new TransactionId(transactionBytes);
        int messageLength = readUnsignedShort(response, base + 2);
        int offset = base + 20;
        int limit = Math.min(base + packetLength, offset + messageLength);

        while (offset + 4 <= limit) {
            int attrType = readUnsignedShort(response, offset);
            int attrLength = readUnsignedShort(response, offset + 2);
            offset += 4;
            if (offset + attrLength > limit) break;

            InetSocketAddress mapped = null;
            if (attrType == 0x0020) {
                mapped = parseMappedAddress(response, offset, attrLength, true);
            } else if (attrType == 0x0001) {
                mapped = parseMappedAddress(response, offset, attrLength, false);
            }
            if (mapped != null) {
                return new StunResponse(transactionId, mapped);
            }

            offset += attrLength;
            int padding = attrLength % 4;
            if (padding != 0) offset += 4 - padding;
        }
        return new StunResponse(transactionId, null);
    }

    private static InetSocketAddress parseMappedAddress(byte[] data, int offset, int length, boolean xor) {
        if (length < 8 || (data[offset + 1] & 0xFF) != 0x01) return null;

        int port = readUnsignedShort(data, offset + 2);
        int ip = readInt(data, offset + 4);
        if (xor) {
            port ^= (MAGIC_COOKIE >>> 16);
            ip ^= MAGIC_COOKIE;
        }

        byte[] ipBytes = {
            (byte) (ip >>> 24),
            (byte) (ip >>> 16),
            (byte) (ip >>> 8),
            (byte) ip
        };
        try {
            return new InetSocketAddress(InetAddress.getByAddress(ipBytes), port);
        } catch (Exception e) {
            return null;
        }
    }

    private static int readUnsignedShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static final class StunServer {
        private final String host;
        private final int port;

        private StunServer(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static final class DiscoveryResult {
        private final int serverCount;
        private final int datagramsSent;
        private final Map<InetSocketAddress, InetSocketAddress> observations;
        private final List<InetSocketAddress> mappedAddresses;

        private DiscoveryResult(int serverCount,
                                int datagramsSent,
                                Map<InetSocketAddress, InetSocketAddress> observations) {
            this.serverCount = serverCount;
            this.datagramsSent = datagramsSent;
            this.observations = Collections.unmodifiableMap(new LinkedHashMap<>(observations));
            this.mappedAddresses = Collections.unmodifiableList(
                    new ArrayList<>(new LinkedHashSet<>(observations.values())));
        }

        public List<InetSocketAddress> getMappedAddresses() {
            return new ArrayList<>(mappedAddresses);
        }

        public int getResponseCount() {
            return observations.size();
        }

        public String toDiagnosticString() {
            return "servers=" + serverCount
                    + ", datagramsSent=" + datagramsSent
                    + ", responses=" + observations.size()
                    + ", noResponse=" + Math.max(0, serverCount - observations.size())
                    + ", observations=" + observations;
        }
    }

    private static final class PendingRequest {
        private final InetSocketAddress server;
        private final byte[] bytes;
        private int sendCount = 1;

        private PendingRequest(InetSocketAddress server, byte[] bytes) {
            this.server = server;
            this.bytes = bytes;
        }
    }

    private static final class StunResponse {
        private final TransactionId transactionId;
        private final InetSocketAddress mappedAddress;

        private StunResponse(TransactionId transactionId, InetSocketAddress mappedAddress) {
            this.transactionId = transactionId;
            this.mappedAddress = mappedAddress;
        }
    }

    private static final class TransactionId {
        private final byte[] value;

        private TransactionId(byte[] value) {
            this.value = Arrays.copyOf(value, value.length);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TransactionId
                    && Arrays.equals(value, ((TransactionId) other).value);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(value);
        }
    }
}
