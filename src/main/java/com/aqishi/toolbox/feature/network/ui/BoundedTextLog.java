package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.BoundedLogBuffer;

import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.util.ArrayDeque;
import java.util.function.Consumer;

/**
 * Line log for a {@link JTextArea} that stays bounded and responsive under a
 * message flood.
 *
 * <p>{@link #append(String)} may be called from any thread: it only queues the
 * line. A Swing timer drains the queue every {@link #FLUSH_INTERVAL_MS} ms,
 * appends the batch with a single document insert and removes the oldest lines
 * in chunks once more than {@code maxLines} are shown.</p>
 */
final class BoundedTextLog {

    static final int FLUSH_INTERVAL_MS = 100;

    private final JTextArea area;
    private final BoundedLogBuffer<String> buffer;
    private final ArrayDeque<Integer> lineLengths = new ArrayDeque<>();
    private final Timer timer;
    private final Consumer<BoundedTextLog> onStatsChanged;

    BoundedTextLog(JTextArea area, int maxLines, Consumer<BoundedTextLog> onStatsChanged) {
        this.area = area;
        this.buffer = new BoundedLogBuffer<>(maxLines, BoundedLogBuffer.DEFAULT_MAX_PER_FLUSH,
                BoundedLogBuffer.DEFAULT_MAX_PENDING);
        this.onStatsChanged = onStatsChanged == null ? log -> { } : onStatsChanged;
        this.timer = new Timer(FLUSH_INTERVAL_MS, e -> flush());
        this.timer.setCoalesce(true);
    }

    void start() {
        timer.start();
    }

    void stop() {
        timer.stop();
    }

    boolean isRunning() {
        return timer.isRunning();
    }

    /** Queues one line (without trailing newline). Thread-safe, never blocks. */
    void append(String line) {
        buffer.offer(line + "\n");
    }

    /** Drains pending lines into the text area. EDT only; the timer calls it. */
    void flush() {
        BoundedLogBuffer.Flush<String> flush = buffer.drain();
        if (flush.isEmpty()) {
            return;
        }
        Document document = area.getDocument();
        boolean follow = area.getCaretPosition() >= document.getLength();
        if (!flush.added().isEmpty()) {
            StringBuilder text = new StringBuilder();
            for (String line : flush.added()) {
                text.append(line);
                lineLengths.addLast(line.length());
            }
            area.append(text.toString());
        }
        int evictChars = 0;
        for (int i = 0; i < flush.evictedCount() && !lineLengths.isEmpty(); i++) {
            evictChars += lineLengths.pollFirst();
        }
        if (evictChars > 0) {
            try {
                document.remove(0, Math.min(evictChars, document.getLength()));
            } catch (BadLocationException impossible) {
                area.setText("");
                lineLengths.clear();
            }
        }
        if (follow) {
            area.setCaretPosition(document.getLength());
        }
        if (flush.evictedCount() > 0 || flush.droppedCount() > 0) {
            onStatsChanged.accept(this);
        }
    }

    /** Empties the view and the queue. EDT only. */
    void clear() {
        buffer.clear();
        lineLengths.clear();
        area.setText("");
        onStatsChanged.accept(this);
    }

    int shownLines() {
        return buffer.retainedSize();
    }

    long trimmedTotal() {
        return buffer.trimmedTotal();
    }

    long droppedTotal() {
        return buffer.droppedTotal();
    }

    int maxLines() {
        return buffer.maxRetained();
    }
}
