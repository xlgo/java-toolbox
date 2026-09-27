package com.aqishi.toolbox.feature.monitor.infra;

import com.aqishi.toolbox.feature.monitor.domain.DesktopChannel;
import com.aqishi.toolbox.feature.monitor.domain.DesktopMessage;
import com.aqishi.toolbox.feature.monitor.domain.HandshakeException;
import com.aqishi.toolbox.feature.monitor.domain.SecureChannelHandshake;
import com.aqishi.toolbox.feature.monitor.domain.SecureSessionConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Secure channel over real loopback TCP sockets. */
class SecureDesktopChannelTcpTest {

    private final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    void closeSockets() {
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private Socket[] socketPair() throws IOException {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (ServerSocket server = new ServerSocket(0, 1, loopback)) {
            Socket client = new Socket(loopback, server.getLocalPort());
            Socket accepted = server.accept();
            sockets.add(client);
            sockets.add(accepted);
            return new Socket[]{client, accepted};
        }
    }

    static SecureSessionConfig config(SecureChannelHandshake.Role role, String password, long timeoutMs) {
        return new SecureSessionConfig(role, "RD-CONTROL", "RD-HOST",
                password == null ? null : password.toCharArray(), timeoutMs);
    }

    private static HandshakeException failureOf(SecureDesktopChannel channel) {
        return assertThrows(HandshakeException.class, () -> channel.awaitEstablished(5_000));
    }

    @Test
    void encryptedChannelRoundTripsDesktopMessages() throws Exception {
        Socket[] pair = socketPair();
        SecureDesktopChannel controller = SecureDesktopChannel.start(new SocketChannelImpl(pair[0]),
                config(SecureChannelHandshake.Role.CONTROLLER, "s3cret", 5_000));
        SecureDesktopChannel host = SecureDesktopChannel.start(new SocketChannelImpl(pair[1]),
                config(SecureChannelHandshake.Role.HOST, "s3cret", 5_000));
        controller.awaitEstablished(5_000);
        host.awaitEstablished(5_000);
        assertEquals(controller.getSas(), host.getSas());
        assertTrue(host.isPasswordUsed());

        LinkedBlockingQueue<DesktopMessage> atHost = new LinkedBlockingQueue<>();
        LinkedBlockingQueue<DesktopMessage> atController = new LinkedBlockingQueue<>();
        host.setMessageListener(atHost::add);
        controller.setMessageListener(atController::add);

        byte[] frame = new byte[1_500_000];
        new Random(1).nextBytes(frame);
        for (int i = 0; i < 50; i++) {
            controller.send(new DesktopMessage(DesktopMessage.TYPE_CONTROL_EVENT, new byte[]{(byte) i}));
        }
        host.send(new DesktopMessage(DesktopMessage.TYPE_SCREEN_FRAME, frame));
        for (int i = 0; i < 50; i++) {
            DesktopMessage message = atHost.poll(5, TimeUnit.SECONDS);
            assertNotNull(message);
            assertEquals(DesktopMessage.TYPE_CONTROL_EVENT, message.getType());
            assertEquals((byte) i, message.getPayload()[0]);
        }
        DesktopMessage screen = atController.poll(5, TimeUnit.SECONDS);
        assertNotNull(screen);
        assertEquals(DesktopMessage.TYPE_SCREEN_FRAME, screen.getType());
        assertArrayEquals(frame, screen.getPayload());
        controller.close();
        host.close();
    }

    @Test
    void wrongPasswordFailsOnBothSides() throws Exception {
        Socket[] pair = socketPair();
        SecureDesktopChannel controller = SecureDesktopChannel.start(new SocketChannelImpl(pair[0]),
                config(SecureChannelHandshake.Role.CONTROLLER, "guess", 5_000));
        SecureDesktopChannel host = SecureDesktopChannel.start(new SocketChannelImpl(pair[1]),
                config(SecureChannelHandshake.Role.HOST, "secret", 5_000));
        assertEquals(HandshakeException.Reason.AUTH_FAILED, failureOf(host).getReason());
        HandshakeException controllerError = failureOf(controller);
        assertEquals(HandshakeException.Reason.AUTH_FAILED, controllerError.getReason());
        assertTrue(controllerError.isReportedByPeer());
        assertTrue(host.isClosed());
    }

    @Test
    void oldProtocolControllerGetsIncompatibleVersionAndIsDisconnected() throws Exception {
        Socket[] pair = socketPair();
        SecureDesktopChannel host = SecureDesktopChannel.start(new SocketChannelImpl(pair[1]),
                config(SecureChannelHandshake.Role.HOST, null, 5_000));
        DataOutputStream legacy = new DataOutputStream(pair[0].getOutputStream());
        byte[] event = "{\"type\":\"mouse\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        legacy.writeInt(event.length);
        legacy.writeByte(DesktopMessage.TYPE_CONTROL_EVENT);
        legacy.write(event);
        legacy.flush();

        assertEquals(HandshakeException.Reason.INCOMPATIBLE_VERSION, failureOf(host).getReason());
        DataInputStream in = new DataInputStream(pair[0].getInputStream());
        pair[0].setSoTimeout(3_000);
        int length = in.readInt();
        assertEquals(DesktopMessage.TYPE_SECURE_HANDSHAKE, in.readByte(), "host explains with an ALERT");
        byte[] alert = new byte[length];
        in.readFully(alert);
        assertEquals(HandshakeException.Reason.INCOMPATIBLE_VERSION.alertCode(), alert[alert.length - 1]);
        assertEquals(-1, in.read(), "host must close the connection");
    }

    @Test
    void oldProtocolHostPushingPlainFramesIsRejectedByTheController() throws Exception {
        Socket[] pair = socketPair();
        SecureDesktopChannel controller = SecureDesktopChannel.start(new SocketChannelImpl(pair[0]),
                config(SecureChannelHandshake.Role.CONTROLLER, null, 5_000));
        SocketChannelImpl legacyHost = new SocketChannelImpl(pair[1]);
        legacyHost.setMessageListener(message -> { });
        legacyHost.send(new DesktopMessage(DesktopMessage.TYPE_SCREEN_FRAME, new byte[100]));
        assertEquals(HandshakeException.Reason.INCOMPATIBLE_VERSION, failureOf(controller).getReason());
        legacyHost.close();
    }

    @Test
    void handshakeTimeoutClosesTheSocket() throws Exception {
        Socket[] pair = socketPair();
        SecureDesktopChannel host = SecureDesktopChannel.start(new SocketChannelImpl(pair[1]),
                config(SecureChannelHandshake.Role.HOST, null, 300));
        pair[0].setSoTimeout(3_000);
        assertEquals(-1, pair[0].getInputStream().read(), "silent peer must be disconnected");
        assertEquals(HandshakeException.Reason.TIMEOUT, failureOf(host).getReason());
    }

    @Test
    void oversizePreAuthFrameClosesTheSocket() throws Exception {
        Socket[] pair = socketPair();
        SecureDesktopChannel host = SecureDesktopChannel.start(new SocketChannelImpl(pair[1]),
                config(SecureChannelHandshake.Role.HOST, null, 5_000));
        DataOutputStream out = new DataOutputStream(pair[0].getOutputStream());
        out.writeInt(SecureDesktopChannel.MAX_HANDSHAKE_FRAME + 1);
        out.writeByte(DesktopMessage.TYPE_SECURE_HANDSHAKE);
        out.write(new byte[SecureDesktopChannel.MAX_HANDSHAKE_FRAME + 1]);
        out.flush();
        pair[0].setSoTimeout(3_000);
        assertEquals(-1, pair[0].getInputStream().read());
        assertFalse(host.isEstablished());
        failureOf(host);
    }

    @Test
    void tamperedReplayedOrReorderedRecordsCloseTheTcpChannel() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Socket[] pair = socketPair();
            Intercepting interceptor = new Intercepting(new SocketChannelImpl(pair[0]));
            SecureDesktopChannel controller = SecureDesktopChannel.start(interceptor,
                    config(SecureChannelHandshake.Role.CONTROLLER, null, 5_000));
            SecureDesktopChannel host = SecureDesktopChannel.start(new SocketChannelImpl(pair[1]),
                    config(SecureChannelHandshake.Role.HOST, null, 5_000));
            controller.awaitEstablished(5_000);
            host.awaitEstablished(5_000);
            CountDownLatch closed = new CountDownLatch(1);
            List<DesktopMessage> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
            host.setCloseListener(closed::countDown);
            host.setMessageListener(delivered::add);

            final int currentMode = mode;
            List<DesktopMessage> held = new ArrayList<>();
            interceptor.hook = message -> {
                if (currentMode == 0) {             // tamper
                    byte[] payload = message.getPayload().clone();
                    payload[payload.length - 1] ^= 1;
                    interceptor.inner.send(new DesktopMessage(message.getType(), payload));
                } else if (currentMode == 1) {      // replay
                    interceptor.inner.send(message);
                    interceptor.inner.send(message);
                } else {                            // reorder: hold the first, send it after the second
                    if (held.isEmpty()) {
                        held.add(message);
                    } else {
                        interceptor.inner.send(message);
                        interceptor.inner.send(held.get(0));
                    }
                }
            };
            controller.send(new DesktopMessage(DesktopMessage.TYPE_HEARTBEAT, new byte[]{1}));
            controller.send(new DesktopMessage(DesktopMessage.TYPE_HEARTBEAT, new byte[]{2}));
            assertTrue(closed.await(5, TimeUnit.SECONDS), "mode " + mode + " did not close the channel");
            assertTrue(delivered.size() <= 1, "mode " + mode + " delivered " + delivered.size());
            controller.close();
        }
    }

    /** Transport decorator whose outbound hook can rewrite frames after the handshake. */
    static final class Intercepting implements DesktopChannel {
        final SocketChannelImpl inner;
        volatile Consumer<DesktopMessage> hook;

        Intercepting(SocketChannelImpl inner) {
            this.inner = inner;
        }

        @Override
        public void send(DesktopMessage message) {
            Consumer<DesktopMessage> current = hook;
            if (current == null || message.getType() != DesktopMessage.TYPE_SECURE_RECORD) inner.send(message);
            else current.accept(message);
        }

        @Override
        public void close() {
            inner.close();
        }

        @Override
        public boolean isP2P() {
            return true;
        }

        @Override
        public String getStatusDescription() {
            return "intercepting";
        }

        @Override
        public void setMessageListener(Consumer<DesktopMessage> listener) {
            inner.setMessageListener(listener);
        }

        @Override
        public void setCloseListener(Runnable listener) {
            inner.setCloseListener(listener);
        }

        @Override
        public void setMaxInboundMessageSize(int bytes) {
            inner.setMaxInboundMessageSize(bytes);
        }
    }
}
