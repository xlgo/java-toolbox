package com.aqishi.toolbox.feature.monitor.domain;

import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordCipherTest {

    private static RecordCipher[] pair(RecordCipher.Ordering ordering) throws Exception {
        SecureChannelHandshake[] ends = SecureChannelHandshakeTest.run(
                new SecureChannelHandshake(SecureChannelHandshake.Role.CONTROLLER, "c", "h", null, new SecureRandom()),
                new SecureChannelHandshake(SecureChannelHandshake.Role.HOST, "c", "h", null, new SecureRandom()),
                (i, b) -> b);
        return new RecordCipher[]{
                RecordCipher.create(ends[0].keys(), SecureChannelHandshake.Role.CONTROLLER, ordering),
                RecordCipher.create(ends[1].keys(), SecureChannelHandshake.Role.HOST, ordering)};
    }

    @Test
    void roundTripsInBothDirections() throws Exception {
        RecordCipher[] ends = pair(RecordCipher.Ordering.STRICT);
        byte[] payload = "move 0.5 0.5".getBytes(StandardCharsets.UTF_8);
        DesktopMessage atHost = ends[1].open(ends[0].seal(DesktopMessage.TYPE_CONTROL_EVENT, payload));
        assertEquals(DesktopMessage.TYPE_CONTROL_EVENT, atHost.getType());
        assertArrayEquals(payload, atHost.getPayload());
        DesktopMessage atController = ends[0].open(ends[1].seal(DesktopMessage.TYPE_SCREEN_FRAME, new byte[0]));
        assertEquals(DesktopMessage.TYPE_SCREEN_FRAME, atController.getType());
        assertEquals(0, atController.getPayload().length);
    }

    @Test
    void tamperedRecordsAreRejected() throws Exception {
        RecordCipher[] ends = pair(RecordCipher.Ordering.STRICT);
        byte[] record = ends[0].seal(DesktopMessage.TYPE_CMD_REQUEST, new byte[40]);
        for (int i = RecordCipher.SEQ_LEN; i < record.length; i++) {
            byte[] copy = record.clone();
            copy[i] ^= 0x40;
            assertThrows(AEADBadTagException.class, () -> ends[1].open(copy), "byte " + i);
        }
        assertEquals(DesktopMessage.TYPE_CMD_REQUEST, ends[1].open(record).getType());
    }

    @Test
    void ownRecordsCannotBeReflected() throws Exception {
        RecordCipher[] ends = pair(RecordCipher.Ordering.STRICT);
        byte[] record = ends[1].seal(DesktopMessage.TYPE_SCREEN_FRAME, new byte[8]);
        assertThrows(AEADBadTagException.class, () -> ends[1].open(record));
    }

    @Test
    void strictOrderingRejectsReplayReorderAndGaps() throws Exception {
        RecordCipher[] ends = pair(RecordCipher.Ordering.STRICT);
        byte[] first = ends[0].seal((byte) 1, new byte[1]);
        byte[] second = ends[0].seal((byte) 1, new byte[1]);
        byte[] third = ends[0].seal((byte) 1, new byte[1]);
        assertThrows(RecordCipher.RecordRejectedException.class, () -> ends[1].open(second));
        ends[1].open(first);
        assertThrows(RecordCipher.RecordRejectedException.class, () -> ends[1].open(first));
        assertThrows(RecordCipher.RecordRejectedException.class, () -> ends[1].open(third));
        ends[1].open(second);
        ends[1].open(third);
    }

    @Test
    void forgedSequenceNumberDoesNotAuthenticate() throws Exception {
        RecordCipher[] ends = pair(RecordCipher.Ordering.WINDOWED);
        byte[] record = ends[0].seal((byte) 1, new byte[4]);
        ByteBuffer.wrap(record).putLong(7);
        assertThrows(AEADBadTagException.class, () -> ends[1].open(record));
    }

    @Test
    void sequenceNumbersNeverRepeat() throws Exception {
        RecordCipher[] ends = pair(RecordCipher.Ordering.STRICT);
        Set<Long> seen = new HashSet<>();
        long previous = -1;
        for (int i = 0; i < 2000; i++) {
            long seq = ByteBuffer.wrap(ends[0].seal((byte) 7, new byte[0])).getLong();
            assertTrue(seen.add(seq), "sequence reused: " + seq);
            assertTrue(seq > previous);
            previous = seq;
        }
        assertEquals(2000, ends[0].nextSendSequence());
        ends[0].destroy();
        assertThrows(IllegalStateException.class, () -> ends[0].seal((byte) 7, new byte[0]));
    }

    @Test
    void windowedOrderingSurvivesLossReorderAndDuplicates() throws Exception {
        RecordCipher[] ends = pair(RecordCipher.Ordering.WINDOWED);
        List<byte[]> sent = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            sent.add(ends[0].seal((byte) 1, ByteBuffer.allocate(4).putInt(i).array()));
        }
        Random random = new Random(42);
        List<byte[]> network = new ArrayList<>();
        Set<Integer> expected = new HashSet<>();
        for (int i = 0; i < sent.size(); i++) {
            if (random.nextInt(10) == 0) continue;              // 10% loss
            network.add(sent.get(i));
            expected.add(i);
            if (random.nextInt(8) == 0) network.add(sent.get(i)); // duplicates
        }
        // Local reordering within blocks of 50 (well inside the replay window).
        for (int start = 0; start < network.size(); start += 50) {
            Collections.shuffle(network.subList(start, Math.min(network.size(), start + 50)), random);
        }
        Set<Integer> delivered = new HashSet<>();
        int rejected = 0;
        for (byte[] record : network) {
            try {
                int value = ByteBuffer.wrap(ends[1].open(record).getPayload()).getInt();
                assertTrue(delivered.add(value), "delivered twice: " + value);
            } catch (RecordCipher.RecordRejectedException duplicate) {
                rejected++;
            }
        }
        assertEquals(expected, delivered);
        assertEquals(network.size() - expected.size(), rejected);
    }

    @Test
    void replayWindowRejectsRecordsOlderThanTheWindow() {
        ReplayWindow window = new ReplayWindow();
        window.mark(5000);
        assertFalse(window.check(5000));
        assertFalse(window.check(5000 - ReplayWindow.SIZE));
        assertTrue(window.check(5000 - ReplayWindow.SIZE + 1));
        assertTrue(window.check(6000));
        window.mark(4990);
        assertFalse(window.check(4990));
        window.mark(5000 + 3 * ReplayWindow.SIZE);
        assertFalse(window.check(5000));
        assertTrue(window.check(5000 + 3 * ReplayWindow.SIZE - 1));
    }
}
