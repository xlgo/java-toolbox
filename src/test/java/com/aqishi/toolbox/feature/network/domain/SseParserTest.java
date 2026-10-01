package com.aqishi.toolbox.feature.network.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.*;

class SseParserTest {
    @Test
    void bomCommentsLineEndingsAndMultilineData() throws Exception {
        List<SseParser.Event> events = new ArrayList<>();
        SseParser parser = new SseParser("", events::add);
        parser.read(
                new StringReader(
                        "\uFEFF:keepalive\r\n"
                            + "id: 12\r"
                            + "event: update\n"
                            + "data: hello\r\n"
                            + "data: world\r\n\r\n"
                            + "data:\n\n"));
        assertEquals(
                List.of(
                        new SseParser.Event("12", "update", "hello\nworld"),
                        new SseParser.Event("12", "message", "")),
                events);
    }

    @Test
    void idOnlyEventUpdatesResumeCursorAndEmptyIdClearsIt() throws Exception {
        SseParser parser = new SseParser("old", e -> fail());
        parser.read(new StringReader("id: new\n\n"));
        assertEquals("new", parser.lastId());
        parser.read(new StringReader("id:\n\n"));
        assertEquals("", parser.lastId());
    }

    @Test
    void invalidIdAndRetryAreIgnoredAndRetryIsBounded() throws Exception {
        SseParser parser = new SseParser("old", e -> {});
        parser.read(new StringReader("id: bad\0id\nretry: -1\nretry: 100000000\n\n"));
        assertEquals("old", parser.lastId());
        assertEquals(30000, parser.retryMillis());
    }

    @Test
    void unterminatedEventsAreNotDispatched() throws Exception {
        List<SseParser.Event> events = new ArrayList<>();
        new SseParser("", events::add).read(new StringReader("data: dropped\n"));
        assertTrue(events.isEmpty());
    }

    @Test
    void unboundedDataAndLongLinesFail() {
        assertThrows(
                java.io.IOException.class,
                () ->
                        new SseParser("", e -> {})
                                .read(new StringReader("data: " + "x".repeat(70000) + "\n\n")));
    }
}
