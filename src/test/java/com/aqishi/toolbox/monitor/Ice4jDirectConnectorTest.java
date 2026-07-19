package com.aqishi.toolbox.monitor;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ice4jDirectConnectorTest {

    @Test
    void completesFullIceChecksAndTransfersApplicationData() throws Exception {
        Ice4jDirectConnector left =
                new Ice4jDirectConnector(Collections.emptyList());
        Ice4jDirectConnector right =
                new Ice4jDirectConnector(Collections.emptyList());
        AtomicReference<DesktopChannel> leftChannel = new AtomicReference<>();
        AtomicReference<DesktopChannel> rightChannel = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(2);

        try {
            String leftDescription = left.prepare(
                    true,
                    channel -> {
                        leftChannel.set(channel);
                        connected.countDown();
                    },
                    () -> failure.compareAndSet(null, "left"),
                    ignored -> { });
            String rightDescription = right.prepare(
                    false,
                    channel -> {
                        rightChannel.set(channel);
                        connected.countDown();
                    },
                    () -> failure.compareAndSet(null, "right"),
                    ignored -> { });

            assertTrue(Ice4jDirectConnector.isDescription(leftDescription));
            assertTrue(Ice4jDirectConnector.isDescription(rightDescription));
            assertDescriptionHasNoRelayCandidate(leftDescription);
            assertDescriptionHasNoRelayCandidate(rightDescription);

            left.setRemoteDescription(rightDescription);
            right.setRemoteDescription(leftDescription);
            left.startConnectivityChecks();
            right.startConnectivityChecks();

            assertTrue(connected.await(15, TimeUnit.SECONDS),
                    "full ICE loopback checks did not complete; failed side=" + failure.get());
            assertNotNull(leftChannel.get());
            assertNotNull(rightChannel.get());

            byte[] expected = "ice4j-application-data".getBytes(StandardCharsets.UTF_8);
            AtomicReference<byte[]> received = new AtomicReference<>();
            CountDownLatch delivered = new CountDownLatch(1);
            rightChannel.get().setMessageListener(message -> {
                received.set(message.getPayload());
                delivered.countDown();
            });
            leftChannel.get().send(
                    new DesktopMessage(DesktopMessage.TYPE_HEARTBEAT, expected));

            assertTrue(delivered.await(5, TimeUnit.SECONDS),
                    "selected ICE socket did not deliver application data");
            assertArrayEquals(expected, received.get());
        } finally {
            if (leftChannel.get() != null) leftChannel.get().close();
            if (rightChannel.get() != null) rightChannel.get().close();
            left.stop();
            right.stop();
        }
    }

    private static void assertDescriptionHasNoRelayCandidate(String description) {
        int colon = description.indexOf(':');
        assertTrue(colon > 0);
        byte[] json = Base64.getUrlDecoder().decode(description.substring(colon + 1));
        String value = new String(json, StandardCharsets.UTF_8);
        assertFalse(value.contains("\"type\":\"relay\""), value);
    }
}
