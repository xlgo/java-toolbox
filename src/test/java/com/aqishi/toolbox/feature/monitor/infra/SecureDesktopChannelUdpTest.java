package com.aqishi.toolbox.feature.monitor.infra;

import com.aqishi.toolbox.feature.monitor.domain.DesktopMessage;
import com.aqishi.toolbox.feature.monitor.domain.HandshakeException;
import com.aqishi.toolbox.feature.monitor.domain.SecureChannelHandshake;
import com.aqishi.toolbox.feature.monitor.domain.SecureSessionConfig;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Secure channel over a simulated lossy, duplicating, reordering datagram transport. */
class SecureDesktopChannelUdpTest {

    private static SecureSessionConfig config(SecureChannelHandshake.Role role, String password) {
        return new SecureSessionConfig(role, "RD-C", "RD-H", password == null ? null : password.toCharArray());
    }

    @Test
    void handshakeAndRecordsSurviveLossDuplicationAndReordering() throws Exception {
        LossyPipe[] pipe = LossyPipe.pair(7, 0.25, 0.15, 40);
        SecureDesktopChannel controller = SecureDesktopChannel.start(pipe[0],
                config(SecureChannelHandshake.Role.CONTROLLER, "pw"));
        SecureDesktopChannel host = SecureDesktopChannel.start(pipe[1],
                config(SecureChannelHandshake.Role.HOST, "pw"));
        try {
            controller.awaitEstablished(15_000);
            host.awaitEstablished(15_000);
            assertEquals(controller.getSas(), host.getSas());

            Set<Integer> received = ConcurrentHashMap.newKeySet();
            AtomicInteger duplicates = new AtomicInteger();
            host.setMessageListener(message -> {
                if (!received.add(ByteBuffer.wrap(message.getPayload()).getInt())) duplicates.incrementAndGet();
            });
            int sent = 400;
            for (int i = 0; i < sent; i++) {
                controller.send(new DesktopMessage(DesktopMessage.TYPE_HEARTBEAT,
                        ByteBuffer.allocate(4).putInt(i).array()));
            }
            Thread.sleep(500);
            assertEquals(0, duplicates.get(), "a record was delivered twice");
            assertTrue(received.size() > sent / 2, "too few records delivered: " + received.size());
            assertTrue(received.size() < sent, "the simulation should have lost some records");
            assertTrue(host.isEstablished());
            assertTrue(controller.isEstablished());
        } finally {
            controller.close();
            host.close();
        }
    }

    @Test
    void forgedDatagramsAreDroppedWithoutKillingTheSession() throws Exception {
        LossyPipe[] pipe = LossyPipe.pair(11, 0, 0, 0);
        SecureDesktopChannel controller = SecureDesktopChannel.start(pipe[0],
                config(SecureChannelHandshake.Role.CONTROLLER, null));
        SecureDesktopChannel host = SecureDesktopChannel.start(pipe[1],
                config(SecureChannelHandshake.Role.HOST, null));
        try {
            host.awaitEstablished(5_000);
            controller.awaitEstablished(5_000);
            AtomicInteger delivered = new AtomicInteger();
            host.setMessageListener(message -> delivered.incrementAndGet());
            for (int i = 0; i < 10; i++) {
                pipe[1].inject(new DesktopMessage(DesktopMessage.TYPE_SECURE_RECORD, new byte[40]));
                pipe[1].inject(new DesktopMessage(DesktopMessage.TYPE_CONTROL_EVENT, new byte[3]));
            }
            controller.send(new DesktopMessage(DesktopMessage.TYPE_HEARTBEAT, new byte[1]));
            Thread.sleep(300);
            assertEquals(1, delivered.get());
            assertTrue(host.isEstablished());

            for (int i = 0; i <= SecureDesktopChannel.MAX_UNRELIABLE_REJECTS; i++) {
                pipe[1].inject(new DesktopMessage(DesktopMessage.TYPE_SECURE_RECORD, new byte[40]));
            }
            Thread.sleep(300);
            assertTrue(host.isClosed(), "a flood of forged records should end the session");
        } finally {
            controller.close();
            host.close();
        }
    }

    @Test
    void floodOfDuplicateHandshakeFlightsHitsThePreAuthLimit() throws Exception {
        LossyPipe[] pipe = LossyPipe.pair(3, 0, 0, 0);
        // Controller never answers the host's HELLO: only its COMMIT is replayed.
        SecureChannelHandshake raw = new SecureChannelHandshake(SecureChannelHandshake.Role.CONTROLLER,
                "RD-C", "RD-H", null, new java.security.SecureRandom());
        byte[] commit = raw.start();
        pipe[0].setMessageListener(message -> { });
        SecureDesktopChannel host = SecureDesktopChannel.start(pipe[1], config(SecureChannelHandshake.Role.HOST, null));
        for (int i = 0; i < 400; i++) {
            pipe[0].send(new DesktopMessage(DesktopMessage.TYPE_SECURE_HANDSHAKE, commit));
        }
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> host.handshakeFuture().get(5, TimeUnit.SECONDS));
        assertInstanceOf(HandshakeException.class, error.getCause());
        assertEquals(HandshakeException.Reason.TOO_LARGE, ((HandshakeException) error.getCause()).getReason());
        assertFalse(host.isEstablished());
        pipe[0].close();
    }
}
