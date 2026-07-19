package com.aqishi.toolbox.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UdpChannelImplTest {

    @Test
    void bindsPublicStunSocketToIpv4InsteadOfDualStackWildcard() {
        P2PConnector connector =
                new P2PConnector(socket -> java.util.Collections.emptyList());
        try {
            int port = connector.startHostListener(channel -> { }, ignored -> { });
            assertTrue(port > 0);
            assertTrue(connector.getUdpSocket().getLocalAddress() instanceof Inet4Address);
        } finally {
            connector.stopHostListener();
        }
    }

    @Test
    void dataChannelAnswersRetransmittedIceCheckAfterSocketHandoff() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        DatagramSocket channelSocket =
                new DatagramSocket(new InetSocketAddress(loopback, 0));
        DatagramSocket peerSocket =
                new DatagramSocket(new InetSocketAddress(loopback, 0));
        peerSocket.setSoTimeout(1500);
        InetSocketAddress peerAddress =
                new InetSocketAddress(loopback, peerSocket.getLocalPort());
        UdpChannelImpl channel = new UdpChannelImpl(channelSocket, peerAddress);

        try {
            byte[] transactionId = IceProbeCodec.newTransactionId();
            byte[] request = IceProbeCodec.createBindingRequest(transactionId);
            peerSocket.send(new DatagramPacket(
                    request, request.length,
                    new InetSocketAddress(loopback, channelSocket.getLocalPort())));

            DatagramPacket response = new DatagramPacket(new byte[512], 512);
            peerSocket.receive(response);
            IceProbeCodec.ParsedMessage parsed = IceProbeCodec.parse(
                    response.getData(), response.getOffset(), response.getLength());
            assertNotNull(parsed);
            assertEquals(IceProbeCodec.BINDING_SUCCESS, parsed.getType());
            assertArrayEquals(transactionId, parsed.getTransactionId());
        } finally {
            channel.close();
            peerSocket.close();
        }
    }

    @Test
    void transfersMessageLargerThanSingleUdpDatagram() throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        DatagramSocket leftSocket = new DatagramSocket(new InetSocketAddress(loopback, 0));
        DatagramSocket rightSocket = new DatagramSocket(new InetSocketAddress(loopback, 0));
        leftSocket.setSendBufferSize(4 * 1024 * 1024);
        rightSocket.setReceiveBufferSize(4 * 1024 * 1024);

        UdpChannelImpl left = new UdpChannelImpl(leftSocket,
                new InetSocketAddress(loopback, rightSocket.getLocalPort()));
        UdpChannelImpl right = new UdpChannelImpl(rightSocket,
                new InetSocketAddress(loopback, leftSocket.getLocalPort()));

        try {
            byte[] expected = new byte[256 * 1024 + 37];
            new Random(20260719L).nextBytes(expected);

            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<DesktopMessage> actual = new AtomicReference<>();
            right.setMessageListener(message -> {
                actual.set(message);
                received.countDown();
            });

            left.send(new DesktopMessage(DesktopMessage.TYPE_SCREEN_FRAME, expected));

            assertTrue(received.await(5, TimeUnit.SECONDS), "large UDP message was not reassembled");
            assertNotNull(actual.get());
            assertEquals(DesktopMessage.TYPE_SCREEN_FRAME, actual.get().getType());
            assertArrayEquals(expected, actual.get().getPayload());
        } finally {
            left.close();
            right.close();
        }
    }

    @RepeatedTest(5)
    void handsSocketFromPunchListenerToDataChannelWithoutStealingPackets() throws Exception {
        P2PConnector leftConnector = new P2PConnector(socket -> java.util.Collections.emptyList());
        P2PConnector rightConnector = new P2PConnector(socket -> java.util.Collections.emptyList());
        AtomicReference<UdpChannelImpl> leftChannel = new AtomicReference<>();
        AtomicReference<UdpChannelImpl> rightChannel = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(2);
        Consumer<String> noLog = ignored -> { };

        Consumer<UdpChannelImpl> onLeftConnected = channel -> {
            if (leftChannel.compareAndSet(null, channel)) connected.countDown();
        };
        Consumer<UdpChannelImpl> onRightConnected = channel -> {
            if (rightChannel.compareAndSet(null, channel)) connected.countDown();
        };

        try {
            int leftPort = leftConnector.startHostListener(onLeftConnected, noLog);
            int rightPort = rightConnector.startHostListener(onRightConnected, noLog);
            assertTrue(leftPort > 0);
            assertTrue(rightPort > 0);

            leftConnector.startConnectorSession(onLeftConnected, () -> { }, noLog);
            rightConnector.startConnectorSession(onRightConnected, () -> { }, noLog);
            leftConnector.addCandidate(candidateFor(rightPort));
            rightConnector.addCandidate(candidateFor(leftPort));

            assertTrue(connected.await(5, TimeUnit.SECONDS), "loopback UDP punching did not complete");
            assertNotNull(leftChannel.get());
            assertNotNull(rightChannel.get());

            CountDownLatch received = new CountDownLatch(1);
            byte[] expected = "channel-ready".getBytes("UTF-8");
            AtomicReference<DesktopMessage> actual = new AtomicReference<>();
            rightChannel.get().setMessageListener(message -> {
                actual.set(message);
                received.countDown();
            });

            leftChannel.get().send(new DesktopMessage(DesktopMessage.TYPE_HEARTBEAT, expected));

            assertTrue(received.await(3, TimeUnit.SECONDS),
                    "handshake listener continued consuming application packets");
            assertEquals(DesktopMessage.TYPE_HEARTBEAT, actual.get().getType());
            assertArrayEquals(expected, actual.get().getPayload());
        } finally {
            if (leftChannel.get() != null) leftChannel.get().close();
            if (rightChannel.get() != null) rightChannel.get().close();
            leftConnector.stopConnectorSession();
            rightConnector.stopConnectorSession();
            leftConnector.stopHostListener();
            rightConnector.stopHostListener();
        }
    }

    @Test
    void predictsPortsOnlyWhenRemoteReportsVaryingStunMappings() {
        P2PConnector connector = new P2PConnector(socket -> java.util.Collections.emptyList());
        try {
            connector.startConnectorSession(channel -> { }, () -> { }, ignored -> { });
            connector.addCandidate("candidate:1 1 udp 2113937151 8.8.8.8 40000 typ srflx");
            connector.addCandidate("candidate:2 1 udp 2113937151 192.168.1.20 50000 typ host");

            assertEquals(2, connector.getExactCandidateCount());
            assertEquals(0, connector.getPredictedCandidateCount());

            connector.addCandidate(
                    "candidate:3 1 udp 2113937151 8.8.4.4 41000 typ srflx port-predict 1");
            assertEquals(16, connector.getPredictedCandidateCount());
        } finally {
            connector.stopConnectorSession();
        }
    }

    @Test
    void preservesRemoteCandidatesThatArriveDuringStunDiscovery() throws Exception {
        CountDownLatch stunStarted = new CountDownLatch(1);
        CountDownLatch finishStun = new CountDownLatch(1);
        AtomicBoolean socketBoundCallbackRan = new AtomicBoolean(false);
        P2PConnector connector = new P2PConnector(socket -> {
            stunStarted.countDown();
            try {
                finishStun.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return java.util.Collections.emptyList();
        });

        AtomicReference<Integer> localPort = new AtomicReference<>();
        Thread starter = new Thread(() -> localPort.set(connector.startHostListener(
                channel -> { },
                ignored -> { },
                () -> socketBoundCallbackRan.set(true))));
        starter.start();

        try {
            assertTrue(stunStarted.await(1, TimeUnit.SECONDS));
            assertTrue(socketBoundCallbackRan.get(), "offer callback must run before STUN discovery");
            connector.addCandidate(candidateFor(45678));
            assertEquals(1, connector.getExactCandidateCount());

            finishStun.countDown();
            starter.join(2000);
            assertNotNull(localPort.get());
            assertTrue(localPort.get() > 0);

            connector.startConnectorSession(channel -> { }, () -> { }, ignored -> { });
            assertEquals(1, connector.getExactCandidateCount(),
                    "starting connectivity checks must not discard early remote candidates");
        } finally {
            finishStun.countDown();
            connector.stopConnectorSession();
            connector.stopHostListener();
            starter.join(1000);
        }
    }

    private static String candidateFor(int port) {
        return "candidate:1 1 udp 2113937151 127.0.0.1 " + port + " typ host";
    }
}
