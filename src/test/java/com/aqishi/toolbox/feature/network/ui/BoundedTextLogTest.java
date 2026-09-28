package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.BoundedLogBuffer;
import com.aqishi.toolbox.feature.network.application.MqttLogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedTextLogTest {

    private JTextArea area;
    private BoundedTextLog log;
    private final AtomicInteger statsUpdates = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            area = new JTextArea();
            log = new BoundedTextLog(area, 50, l -> statsUpdates.incrementAndGet());
        });
    }

    @Test
    void bufferedLinesAppearOnlyOnFlush() throws Exception {
        for (int i = 0; i < 10; i++) {
            log.append("line-" + i); // as if from a network thread
        }
        SwingUtilities.invokeAndWait(() -> {
            assertEquals("", area.getText(), "producers must not touch the document");
            log.flush();
            assertTrue(area.getText().startsWith("line-0\n"));
            assertEquals(10, log.shownLines());
        });
    }

    @Test
    void keepsAtMostTheConfiguredNumberOfLinesAndReportsTrimming() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < 500; i++) {
                log.append("line-" + i);
                if (i % 13 == 0) {
                    log.flush();
                    assertLinesWithinLimit();
                }
            }
            log.flush();
            assertLinesWithinLimit();
            assertEquals(500 - log.shownLines(), log.trimmedTotal());
            assertEquals("line-499", area.getText().trim().substring(area.getText().trim().lastIndexOf('\n') + 1));
            assertTrue(statsUpdates.get() > 0);
        });
    }

    @Test
    void droppedLinesAreCountedWhenTheQueueOverflows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < BoundedLogBuffer.DEFAULT_MAX_PENDING + 500; i++) {
                log.append("x" + i);
            }
            log.flush();
            assertEquals(500, log.droppedTotal());
            assertTrue(statsUpdates.get() > 0);
        });
    }

    @Test
    void clearEmptiesViewAndCounters() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            log.append("a");
            log.flush();
            assertFalse(area.getText().isEmpty());
            log.clear();
            assertEquals("", area.getText());
            assertEquals(0, log.shownLines());
            assertEquals(0, area.getDocument().getLength());
        });
    }

    @Test
    void startAndStopDriveTheFlushTimer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertFalse(log.isRunning());
            log.start();
            assertTrue(log.isRunning());
            log.stop();
            assertFalse(log.isRunning());
        });
    }

    private void assertLinesWithinLimit() {
        String text = area.getText();
        int lines = text.isEmpty() ? 0 : text.split("\n", -1).length - 1;
        assertTrue(lines <= 50, "document kept " + lines + " lines");
        assertEquals(lines, log.shownLines());
        assertEquals(lines, logForLines(text));
    }

    /** Counts the newline-separated entries the document actually shows. */
    private static int logForLines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    @Test
    void mqttEntryKeepsRecordShape() {
        MqttLogEntry entry = MqttLogEntry.of(MqttLogEntry.Direction.RECEIVED, "t", 1, true, "payload");
        assertEquals("t", entry.topic());
        assertEquals(1, entry.qos());
        assertTrue(entry.retain());
        assertEquals("payload", entry.payload());
        assertEquals(12, entry.time().length(), "HH:mm:ss.SSS");
        assertFalse(MqttLogEntry.system(null, "x").topic().isEmpty());
    }

    @Test
    void oversizedPayloadsAreCapped() {
        String huge = "a".repeat(MqttLogEntry.MAX_PAYLOAD_CHARS + 1000);
        String capped = MqttLogEntry.of(MqttLogEntry.Direction.SENT, "t", 0, false, huge).payload();
        assertTrue(capped.length() < huge.length());
        assertTrue(capped.contains("+1000 chars]"));
    }
}
