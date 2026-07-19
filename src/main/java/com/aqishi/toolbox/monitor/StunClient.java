package com.aqishi.toolbox.monitor;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;

/**
 * 轻量级 STUN 客户端 (符合 RFC 5389)，用于获取机器的公网反射出口 IP 及映射端口。
 */
public class StunClient {

    private static final String[] STUN_SERVERS = {
        "stun.aliyun.com",
        "stun.l.google.com",
        "stun1.l.google.com",
        "stun2.l.google.com",
        "stun.xten.com",
        "stun.ideasip.com"
    };

    /**
     * 利用给定的本地 UDP 套接字进行探测，获取其映射的公网地址及端口。
     * 保持套接字不被关闭，以便后续复用该端口进行 NAT 打洞。
     */
    public static InetSocketAddress getPublicAddress(DatagramSocket socket) {
        if (socket == null) return null;
        for (String server : STUN_SERVERS) {
            int port = server.contains("google") ? 19302 : 3478;
            InetSocketAddress addr = getPublicAddressFromStun(socket, server, port);
            if (addr != null) {
                return addr;
            }
        }
        return null;
    }

    private static InetSocketAddress getPublicAddressFromStun(DatagramSocket socket, String host, int port) {
        int originalTimeout = 0;
        try {
            originalTimeout = socket.getSoTimeout();
            socket.setSoTimeout(1500); // 1.5 秒超时
            InetAddress address = InetAddress.getByName(host);

            byte[] request = new byte[20];
            // Message Type: Binding Request (0x0001)
            request[0] = 0x00;
            request[1] = 0x01;
            // Message Length: 0x0000
            request[2] = 0x00;
            request[3] = 0x00;
            // Magic Cookie: 0x2112A442
            request[4] = 0x21;
            request[5] = 0x12;
            request[6] = (byte) 0xA4;
            request[7] = 0x42;
            
            // Transaction ID: 12 random bytes
            byte[] transactionId = new byte[12];
            new Random().nextBytes(transactionId);
            System.arraycopy(transactionId, 0, request, 8, 12);

            DatagramPacket sendPacket = new DatagramPacket(request, request.length, address, port);
            socket.send(sendPacket);

            byte[] response = new byte[512];
            DatagramPacket receivePacket = new DatagramPacket(response, response.length);
            socket.receive(receivePacket);

            if (receivePacket.getLength() < 20) return null;

            // 检查 Message Type 是否为 Binding Success Response (0x0101)
            int messageType = ((response[0] & 0xFF) << 8) | (response[1] & 0xFF);
            if (messageType != 0x0101) return null;

            // 检查 Magic Cookie
            if (response[4] != 0x21 || response[5] != 0x12 || (response[6] & 0xFF) != 0xA4 || response[7] != 0x42) {
                return null;
            }

            int messageLength = ((response[2] & 0xFF) << 8) | (response[3] & 0xFF);
            int offset = 20;
            int limit = offset + messageLength;
            if (limit > receivePacket.getLength()) {
                limit = receivePacket.getLength();
            }

            while (offset + 4 <= limit) {
                int attrType = ((response[offset] & 0xFF) << 8) | (response[offset + 1] & 0xFF);
                int attrLen = ((response[offset + 2] & 0xFF) << 8) | (response[offset + 3] & 0xFF);
                offset += 4;

                if (offset + attrLen > limit) break;

                if (attrType == 0x0001) { // MAPPED-ADDRESS
                    if (attrLen >= 8) {
                        int protocolFamily = response[offset + 1] & 0xFF;
                        if (protocolFamily == 0x01) { // IPv4
                            int mappedPort = ((response[offset + 2] & 0xFF) << 8) | (response[offset + 3] & 0xFF);
                            int ip1 = response[offset + 4] & 0xFF;
                            int ip2 = response[offset + 5] & 0xFF;
                            int ip3 = response[offset + 6] & 0xFF;
                            int ip4 = response[offset + 7] & 0xFF;
                            String ip = ip1 + "." + ip2 + "." + ip3 + "." + ip4;
                            return new InetSocketAddress(ip, mappedPort);
                        }
                    }
                } else if (attrType == 0x0020) { // XOR-MAPPED-ADDRESS
                    if (attrLen >= 8) {
                        int protocolFamily = response[offset + 1] & 0xFF;
                        if (protocolFamily == 0x01) { // IPv4
                            int xport = ((response[offset + 2] & 0xFF) << 8) | (response[offset + 3] & 0xFF);
                            int mappedPort = xport ^ 0x2112;
                            int ip1 = (response[offset + 4] & 0xFF) ^ 0x21;
                            int ip2 = (response[offset + 5] & 0xFF) ^ 0x12;
                            int ip3 = (response[offset + 6] & 0xFF) ^ 0xA4;
                            int ip4 = (response[offset + 7] & 0xFF) ^ 0x42;
                            String ip = ip1 + "." + ip2 + "." + ip3 + "." + ip4;
                            return new InetSocketAddress(ip, mappedPort);
                        }
                    }
                }
                offset += attrLen;
                if (attrLen % 4 != 0) {
                    offset += (4 - (attrLen % 4));
                }
            }
        } catch (Exception e) {
            // 忽略异常，尝试其它 STUN 节点
        } finally {
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (Exception ignored) {}
        }
        return null;
    }
}
