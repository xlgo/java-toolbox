package com.aqishi.toolbox.feature.network.domain;

import java.io.*;
import java.util.function.Consumer;

/** WHATWG event-stream parser; dispatches only at blank lines, preserving IDs across reconnects. */
public final class SseParser {
    public record Event(String id, String type, String data) {}

    private String lastId;
    private long retryMillis = 3000;
    private String type = "";
    private final StringBuilder data = new StringBuilder();
    private final Consumer<Event> listener;

    public SseParser(String lastId, Consumer<Event> listener) {
        this.lastId = lastId == null ? "" : lastId;
        this.listener = listener;
    }

    public SseParser(String lastId, long retryMillis, Consumer<Event> listener) {
        this(lastId, listener);
        this.retryMillis = retryMillis;
    }

    public String lastId() {
        return lastId;
    }

    public long retryMillis() {
        return retryMillis;
    }

    public void read(Reader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean first = true, afterCr = false;
        int ch;
        while ((ch = reader.read()) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (first) {
                first = false;
                if (ch == 0xfeff) continue;
            }
            if (afterCr) {
                afterCr = false;
                if (ch == '\n') continue;
            }
            if (ch == '\r' || ch == '\n') {
                line(line.toString());
                line.setLength(0);
                afterCr = ch == '\r';
            } else {
                if (line.length() >= 65536) throw new IOException("SSE line exceeds 64 KiB");
                line.append((char) ch);
            }
        }
        // A trailing, unterminated event is deliberately discarded, as required by the event-stream
        // format.
    }

    private void line(String line) throws IOException {
        if (line.isEmpty()) {
            if (data.length() > 0) {
                data.setLength(data.length() - 1);
                listener.accept(
                        new Event(lastId, type.isEmpty() ? "message" : type, data.toString()));
            }
            data.setLength(0);
            type = "";
            return;
        }
        if (line.startsWith(":")) return;
        int colon = line.indexOf(':');
        String name = colon < 0 ? line : line.substring(0, colon),
                value = colon < 0 ? "" : line.substring(colon + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        switch (name) {
            case "data" -> {
                if (data.length() + value.length() > 65536)
                    throw new IOException("SSE event exceeds 64 KiB");
                data.append(value).append('\n');
            }
            case "event" -> type = value;
            case "id" -> {
                if (value.indexOf('\0') < 0) lastId = value;
            }
            case "retry" -> {
                if (value.matches("[0-9]+")) {
                    try {
                        retryMillis = Math.max(100, Math.min(30000, Long.parseLong(value)));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            default -> {}
        }
    }
}
