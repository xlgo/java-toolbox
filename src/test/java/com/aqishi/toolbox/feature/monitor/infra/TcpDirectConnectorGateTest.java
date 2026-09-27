package com.aqishi.toolbox.feature.monitor.infra;

import com.aqishi.toolbox.feature.monitor.domain.DesktopChannel;
import com.aqishi.toolbox.feature.monitor.domain.DesktopMessage;
import com.aqishi.toolbox.feature.monitor.domain.SecureChannelHandshake;
import com.aqishi.toolbox.feature.monitor.domain.SecureSessionConfig;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpDirectConnectorGateTest {

    private static TcpDirectConnector.ChannelGate gate(SecureChannelHandshake.Role role) {
        SecureSessionConfig config = SecureDesktopChannelTcpTest.config(role, "pw", 2_000);
        return raw -> SecureDesktopChannel.establish(raw, config);
    }

    /** Opens a socket to the listener, sends one legacy plaintext frame and waits for the close. */
    private static void rogueConnection(int port) throws Exception {
        try (Socket rogue = new Socket(InetAddress.getLoopbackAddress(), port)) {
            rogue.setSoTimeout(3_000);
            DataOutputStream out = new DataOutputStream(rogue.getOutputStream());
            out.writeInt(1);
            out.writeByte(DesktopMessage.TYPE_CONTROL_EVENT);
            out.writeByte(0);
            out.flush();
            while (rogue.getInputStream().read() != -1) {
                // drain the ALERT until the listener closes the socket
            }
        }
    }

    @Test
    void listenerSurvivesRogueConnectionsAndOnlyReportsAuthenticatedChannels() throws Exception {
        TcpDirectConnector host = new TcpDirectConnector(false);
        TcpDirectConnector controller = new TcpDirectConnector(false);
        host.setChannelGate(gate(SecureChannelHandshake.Role.HOST));
        controller.setChannelGate(gate(SecureChannelHandshake.Role.CONTROLLER));
        AtomicReference<DesktopChannel> hostChannel = new AtomicReference<>();
        AtomicReference<DesktopChannel> controllerChannel = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(2);
        try {
            int port = host.startListener(channel -> {
                hostChannel.set(channel);
                connected.countDown();
            }, () -> { }, ignored -> { });
            rogueConnection(port);
            rogueConnection(port);
            assertEquals(2, connected.getCount());
            assertTrue(hostChannel.get() == null, "a rogue socket must not be reported");

            controller.addCandidate("candidate:tcp-host 1 tcp 1518280447 127.0.0.1 " + port + " typ host");
            controller.startConnector(channel -> {
                controllerChannel.set(channel);
                connected.countDown();
            }, () -> { }, ignored -> { });
            assertTrue(connected.await(5, TimeUnit.SECONDS));
            assertInstanceOf(SecureDesktopChannel.class, hostChannel.get());
            assertInstanceOf(SecureDesktopChannel.class, controllerChannel.get());
            assertEquals(((SecureDesktopChannel) hostChannel.get()).getSas(),
                    ((SecureDesktopChannel) controllerChannel.get()).getSas());
        } finally {
            if (hostChannel.get() != null) hostChannel.get().close();
            if (controllerChannel.get() != null) controllerChannel.get().close();
            host.stop();
            controller.stop();
        }
    }

    @Test
    void repeatedFailuresBlockTheAddressBeforeAnyHandshake() throws Exception {
        TcpDirectConnector host = new TcpDirectConnector(false);
        host.setRateLimiter(new HandshakeRateLimiter(2, 60_000, 60_000, System::currentTimeMillis));
        host.setChannelGate(gate(SecureChannelHandshake.Role.HOST));
        TcpDirectConnector controller = new TcpDirectConnector(false);
        controller.setChannelGate(gate(SecureChannelHandshake.Role.CONTROLLER));
        AtomicReference<DesktopChannel> hostChannel = new AtomicReference<>();
        CountDownLatch controllerConnected = new CountDownLatch(1);
        try {
            int port = host.startListener(hostChannel::set, () -> { }, ignored -> { });
            rogueConnection(port);
            rogueConnection(port);
            Thread.sleep(200); // the failure is recorded right after the rogue socket closes
            controller.addCandidate("candidate:tcp-host 1 tcp 1518280447 127.0.0.1 " + port + " typ host");
            controller.startConnector(channel -> controllerConnected.countDown(), () -> { }, ignored -> { });
            assertFalse(controllerConnected.await(1500, TimeUnit.MILLISECONDS),
                    "a blocked address must not complete a handshake");
            assertEquals(null, hostChannel.get());
        } finally {
            host.stop();
            controller.stop();
        }
    }

    @Test
    void rateLimiterBlocksAfterMaxFailuresAndExpires() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        HandshakeRateLimiter limiter = new HandshakeRateLimiter(3, 10_000, 5_000, now::get);
        InetAddress attacker = InetAddress.getByName("198.51.100.7");
        InetAddress other = InetAddress.getByName("198.51.100.8");
        assertFalse(limiter.recordFailure(attacker));
        assertFalse(limiter.recordFailure(attacker));
        assertTrue(limiter.allow(attacker));
        assertTrue(limiter.recordFailure(attacker));
        assertFalse(limiter.allow(attacker));
        assertTrue(limiter.allow(other));
        now.addAndGet(5_001);
        assertTrue(limiter.allow(attacker));

        // Failures spread beyond the window do not accumulate.
        for (int i = 0; i < 5; i++) {
            now.addAndGet(20_000);
            assertFalse(limiter.recordFailure(other));
        }
        limiter.recordFailure(other);
        limiter.recordSuccess(other);
        assertTrue(limiter.allow(other));
    }
}
