package com.aqishi.toolbox.feature.data.application;

import org.junit.jupiter.api.Test;
import redis.clients.jedis.ScanResult;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RedisKeyScannerTest {
    @Test void continuesThroughEmptyBatchesAndDeduplicatesKeys() {
        var scanner = new RedisKeyScanner("*", 1);
        var first = scanner.load((cursor, params) -> { assertEquals("0", cursor); return new ScanResult<>("7", List.of()); }, () -> false);
        assertTrue(first.hasMore());
        var second = scanner.load((cursor, params) -> { assertEquals("7", cursor); return new ScanResult<>("9", List.of("b", "a", "a")); }, () -> false);
        assertEquals(List.of("a", "b"), second.keys());
        var last = scanner.load((cursor, params) -> { assertEquals("9", cursor); return new ScanResult<>("0", List.of("a", "c")); }, () -> false);
        assertEquals(List.of("a", "b", "c"), last.keys());
        assertTrue(last.complete());
        assertFalse(last.hasMore());
        scanner.load((cursor, params) -> { fail("completed scan must not restart"); return null; }, () -> false);
    }

    @Test void oversizedServerBatchIsBufferedWithoutDroppingKeys() {
        var scanner = new RedisKeyScanner("*");
        AtomicInteger calls = new AtomicInteger();
        var source = (RedisKeyScanner.Source) (cursor, params) -> {
            calls.incrementAndGet();
            return new ScanResult<>("0", List.of("a", "b", "a", "c", "d", "e"));
        };
        var page = scanner.load(source, () -> false, 3);
        assertEquals(3, page.keys().size());
        assertTrue(page.hasMore());
        assertFalse(page.complete());
        var last = scanner.load(source, () -> false, 3);
        assertEquals(List.of("a", "b", "c", "d", "e"), last.keys());
        assertTrue(last.complete());
        assertEquals(1, calls.get());
    }

    @Test void exactBatchAtEndOfScanIsReportedAsComplete() {
        var scanner = new RedisKeyScanner("*");
        var page = scanner.load((cursor, params) -> new ScanResult<>("0", List.of("c", "a", "b")), () -> false, 3);
        assertTrue(page.complete());
        assertFalse(page.hasMore());
    }

    @Test void sparsePatternsReturnCursorAfterBoundedNumberOfCommands() {
        var scanner = new RedisKeyScanner("rare:*", 4);
        AtomicInteger calls = new AtomicInteger();
        var page = scanner.load((cursor, params) -> new ScanResult<>(Integer.toString(calls.incrementAndGet()), List.of()), () -> false);
        assertEquals(4, calls.get());
        assertTrue(page.hasMore());
    }

    @Test void cancellationStopsBetweenNetworkCalls() {
        var scanner = new RedisKeyScanner("*");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(CancellationException.class, () -> scanner.load((cursor, params) -> {
            calls.incrementAndGet(); cancelled.set(true); return new ScanResult<>("1", List.of("a"));
        }, cancelled::get));
        assertEquals(1, calls.get());
    }

    @Test void defaultLoadsOneThousandAndCanContinueBeyondFiveThousand() {
        var scanner = new RedisKeyScanner("*");
        var allKeys = java.util.stream.IntStream.range(0, 7001).mapToObj(i -> "key:" + i).toList();
        AtomicInteger calls = new AtomicInteger();
        var source = (RedisKeyScanner.Source) (cursor, params) -> {
            calls.incrementAndGet();
            return new ScanResult<>("0", allKeys);
        };
        var page = scanner.load(source, () -> false);
        assertEquals(1000, page.keys().size());
        assertTrue(page.hasMore());
        for (int count = 2000; count <= 7000; count += 1000) {
            page = scanner.load(source, () -> false);
            assertEquals(count, page.keys().size());
            assertTrue(page.hasMore());
        }
        page = scanner.load(source, () -> false);
        assertEquals(7001, page.keys().size());
        assertTrue(page.complete());
        assertEquals(1, calls.get());
    }

    @Test void changingBatchSizeContinuesFromBufferedResultsAndExistingCursor() {
        var scanner = new RedisKeyScanner("*");
        AtomicInteger calls = new AtomicInteger();
        var source = (RedisKeyScanner.Source) (cursor, params) -> {
            int call = calls.incrementAndGet();
            assertEquals(call == 1 ? "0" : "5", cursor);
            return call == 1 ? new ScanResult<>("5", List.of("a", "b", "c", "d"))
                    : new ScanResult<>("0", List.of("d", "e", "f"));
        };
        assertEquals(2, scanner.load(source, () -> false, 2).keys().size());
        assertEquals(3, scanner.load(source, () -> false, 1).keys().size());
        var last = scanner.load(source, () -> false, 3);
        assertEquals(List.of("a", "b", "c", "d", "e", "f"), last.keys());
        assertTrue(last.complete());
        assertEquals(2, calls.get());
    }
}
