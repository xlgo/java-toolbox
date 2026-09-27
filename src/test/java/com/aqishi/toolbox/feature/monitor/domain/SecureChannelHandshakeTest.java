package com.aqishi.toolbox.feature.monitor.domain;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SecureChannelHandshakeTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CONTROLLER_ID = "RD-CONTROLLER1";
    private static final String HOST_ID = "RD-HOST000001";

    /** Runs the four flights; {@code tamper(flightIndex, bytes)} may rewrite any of them. */
    static SecureChannelHandshake[] run(SecureChannelHandshake controller, SecureChannelHandshake host,
                                        BiFunction<Integer, byte[], byte[]> tamper) throws HandshakeException {
        assertNull(host.start());
        byte[] commit = tamper.apply(0, controller.start());
        byte[] hostHello = tamper.apply(1, host.receive(commit));
        byte[] controllerHello = tamper.apply(2, controller.receive(hostHello));
        byte[] hostFinished = tamper.apply(3, host.receive(controllerHello));
        assertNull(controller.receive(hostFinished));
        return new SecureChannelHandshake[]{controller, host};
    }

    private static SecureChannelHandshake controller(String password) {
        return new SecureChannelHandshake(SecureChannelHandshake.Role.CONTROLLER, CONTROLLER_ID, HOST_ID,
                password == null ? null : password.toCharArray(), RANDOM);
    }

    private static SecureChannelHandshake host(String password) {
        return new SecureChannelHandshake(SecureChannelHandshake.Role.HOST, CONTROLLER_ID, HOST_ID,
                password == null ? null : password.toCharArray(), RANDOM);
    }

    @Test
    void bothSidesDeriveTheSameKeysAndSasWithoutPassword() throws Exception {
        SecureChannelHandshake[] ends = run(controller(null), host(null), (i, b) -> b);
        assertSameKeys(ends[0].keys(), ends[1].keys());
        assertFalse(ends[0].keys().isPasswordUsed());
    }

    @Test
    void bothSidesDeriveTheSameKeysAndSasWithPassword() throws Exception {
        SecureChannelHandshake[] ends = run(controller("correct horse"), host("correct horse"), (i, b) -> b);
        assertSameKeys(ends[0].keys(), ends[1].keys());
        assertTrue(ends[1].keys().isPasswordUsed());
    }

    @Test
    void sessionsProduceFreshKeys() throws Exception {
        SessionKeys first = run(controller(null), host(null), (i, b) -> b)[0].keys();
        SessionKeys second = run(controller(null), host(null), (i, b) -> b)[0].keys();
        assertFalse(java.util.Arrays.equals(first.controllerToHostKey(), second.controllerToHostKey()));
        assertFalse(java.util.Arrays.equals(first.transcriptHash(), second.transcriptHash()));
    }

    @Test
    void sasIsSixGroupedDigits() throws Exception {
        String sas = run(controller(null), host(null), (i, b) -> b)[0].keys().sas();
        assertTrue(sas.matches("\\d{3} \\d{3}"), sas);
    }

    @Test
    void flippingAnyBitOfAnyFlightFailsTheHandshake() {
        int[] lengths = {HandshakeWire.COMMIT_LEN, HandshakeWire.HELLO_LEN,
                HandshakeWire.CONTROLLER_HELLO_LEN, HandshakeWire.HOST_FINISHED_LEN};
        for (int flight = 0; flight < lengths.length; flight++) {
            for (int position = 0; position < lengths[flight]; position++) {
                final int targetFlight = flight;
                final int targetByte = position;
                try {
                    run(controller(null), host(null), (i, bytes) -> {
                        if (i != targetFlight) return bytes;
                        byte[] copy = bytes.clone();
                        copy[targetByte] ^= 0x01;
                        return copy;
                    });
                    fail("tampered flight " + flight + " byte " + position + " was accepted");
                } catch (HandshakeException expected) {
                    // Every modification must be detected by one of the two ends.
                }
            }
        }
    }

    @Test
    void mismatchedSessionIdsFailKeyConfirmation() {
        SecureChannelHandshake host = new SecureChannelHandshake(SecureChannelHandshake.Role.HOST,
                "RD-SOMEONEELSE", HOST_ID, null, RANDOM);
        HandshakeException error = assertThrows(HandshakeException.class,
                () -> run(controller(null), host, (i, b) -> b));
        assertEquals(HandshakeException.Reason.AUTH_FAILED, error.getReason());
    }

    @Test
    void wrongPasswordFailsKeyConfirmation() {
        HandshakeException error = assertThrows(HandshakeException.class,
                () -> run(controller("guess"), host("secret"), (i, b) -> b));
        assertEquals(HandshakeException.Reason.AUTH_FAILED, error.getReason());
    }

    @Test
    void missingPasswordFailsFastOnTheController() {
        HandshakeException error = assertThrows(HandshakeException.class,
                () -> run(controller(null), host("secret"), (i, b) -> b));
        assertEquals(HandshakeException.Reason.PASSWORD_REQUIRED, error.getReason());
    }

    @Test
    void unsupportedVersionIsReportedAsIncompatible() throws Exception {
        SecureChannelHandshake host = host(null);
        host.start();
        byte[] commit = controller(null).start();
        commit[4] = 2;
        HandshakeException error = assertThrows(HandshakeException.class, () -> host.receive(commit));
        assertEquals(HandshakeException.Reason.INCOMPATIBLE_VERSION, error.getReason());
    }

    @Test
    void alertFromAnyVersionCarriesThePeerReason() throws Exception {
        SecureChannelHandshake controller = controller(null);
        controller.start();
        byte[] alert = SecureChannelHandshake.alert(HandshakeException.Reason.INCOMPATIBLE_VERSION);
        assertNotNull(alert);
        alert[4] = 9;
        HandshakeException error = assertThrows(HandshakeException.class, () -> controller.receive(alert));
        assertEquals(HandshakeException.Reason.INCOMPATIBLE_VERSION, error.getReason());
        assertTrue(error.isReportedByPeer());
    }

    @Test
    void reflectedHostHelloIsRejected() throws Exception {
        SecureChannelHandshake host = host(null);
        host.start();
        byte[] hostHello = host.receive(controller(null).start());
        HandshakeException error = assertThrows(HandshakeException.class, () -> host.receive(hostHello));
        assertEquals(HandshakeException.Reason.PROTOCOL_ERROR, error.getReason());
    }

    @Test
    void smallOrderPublicKeyIsRejected() throws Exception {
        SecureChannelHandshake controller = controller(null);
        controller.start();
        byte[] hostHello = HandshakeWire.hello(HandshakeWire.KIND_HOST_HELLO, (byte) 0,
                new byte[32], new byte[32]);
        assertThrows(HandshakeException.class, () -> controller.receive(hostHello));
    }

    private static void assertSameKeys(SessionKeys controller, SessionKeys host) {
        assertArrayEquals(controller.controllerToHostKey(), host.controllerToHostKey());
        assertArrayEquals(controller.controllerToHostIv(), host.controllerToHostIv());
        assertArrayEquals(controller.hostToControllerKey(), host.hostToControllerKey());
        assertArrayEquals(controller.hostToControllerIv(), host.hostToControllerIv());
        assertArrayEquals(controller.transcriptHash(), host.transcriptHash());
        assertEquals(controller.sas(), host.sas());
        assertFalse(java.util.Arrays.equals(controller.controllerToHostKey(), controller.hostToControllerKey()));
    }
}
