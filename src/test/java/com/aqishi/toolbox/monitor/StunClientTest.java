package com.aqishi.toolbox.monitor;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StunClientTest {

    private static final int MAGIC_COOKIE = 0x2112A442;

    @Test
    void collectsMultipleMappedAddressesWithinOneDiscoveryWindow() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        DatagramSocket serverOne = new DatagramSocket(new InetSocketAddress(loopback, 0));
        DatagramSocket serverTwo = new DatagramSocket(new InetSocketAddress(loopback, 0));
        DatagramSocket client = new DatagramSocket(new InetSocketAddress(loopback, 0));
        InetSocketAddress mappedOne = new InetSocketAddress("203.0.113.10", 41001);
        InetSocketAddress mappedTwo = new InetSocketAddress("203.0.113.10", 41002);
        CountDownLatch responded = new CountDownLatch(2);

        Thread first = startFakeStunServer(serverOne, mappedOne, responded);
        Thread second = startFakeStunServer(serverTwo, mappedTwo, responded);

        try {
            List<InetSocketAddress> servers = Arrays.asList(
                    new InetSocketAddress(loopback, serverOne.getLocalPort()),
                    new InetSocketAddress(loopback, serverTwo.getLocalPort()));

            StunClient.DiscoveryResult result =
                    StunClient.discoverPublicAddresses(client, servers, 1000);
            List<InetSocketAddress> actual = result.getMappedAddresses();

            assertTrue(responded.await(1, TimeUnit.SECONDS));
            assertEquals(2, actual.size());
            assertEquals(2, result.getResponseCount());
            assertTrue(actual.contains(mappedOne));
            assertTrue(actual.contains(mappedTwo));
        } finally {
            client.close();
            serverOne.close();
            serverTwo.close();
            first.join(1000);
            second.join(1000);
        }
    }

    private static Thread startFakeStunServer(DatagramSocket server,
                                               InetSocketAddress mappedAddress,
                                               CountDownLatch responded) {
        Thread thread = new Thread(() -> {
            try {
                byte[] requestData = new byte[512];
                DatagramPacket request = new DatagramPacket(requestData, requestData.length);
                server.receive(request);

                byte[] response = createBindingResponse(request, mappedAddress);
                server.send(new DatagramPacket(response, response.length, request.getSocketAddress()));
                responded.countDown();
            } catch (Exception ignored) {
            }
        }, "Fake-STUN-" + server.getLocalPort());
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static byte[] createBindingResponse(DatagramPacket request,
                                                 InetSocketAddress mappedAddress) {
        byte[] response = new byte[32];
        response[0] = 0x01;
        response[1] = 0x01; // Binding Success Response
        response[2] = 0x00;
        response[3] = 0x0C; // one 12-byte XOR-MAPPED-ADDRESS attribute
        writeInt(response, 4, MAGIC_COOKIE);
        System.arraycopy(request.getData(), request.getOffset() + 8, response, 8, 12);

        response[20] = 0x00;
        response[21] = 0x20; // XOR-MAPPED-ADDRESS
        response[22] = 0x00;
        response[23] = 0x08;
        response[24] = 0x00;
        response[25] = 0x01; // IPv4

        int xorPort = mappedAddress.getPort() ^ (MAGIC_COOKIE >>> 16);
        response[26] = (byte) (xorPort >>> 8);
        response[27] = (byte) xorPort;

        byte[] ip = mappedAddress.getAddress().getAddress();
        int rawIp = ((ip[0] & 0xFF) << 24)
                | ((ip[1] & 0xFF) << 16)
                | ((ip[2] & 0xFF) << 8)
                | (ip[3] & 0xFF);
        writeInt(response, 28, rawIp ^ MAGIC_COOKIE);
        return response;
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
