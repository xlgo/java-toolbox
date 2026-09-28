package com.aqishi.toolbox.feature.network.application;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedLogBufferTest {

    @Test
    void drainsInBatchesAndKeepsOrder() {
        BoundedLogBuffer<Integer> buffer = new BoundedLogBuffer<>(100, 10, 1000);
        for (int i = 0; i < 25; i++) {
            buffer.offer(i);
        }
        BoundedLogBuffer.Flush<Integer> first = buffer.drain();
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), first.added());
        assertEquals(0, first.evictedCount());
        assertEquals(10, buffer.drain().added().size());
        assertEquals(5, buffer.drain().added().size());
        assertTrue(buffer.drain().isEmpty());
        assertEquals(25, buffer.retainedSize());
    }

    @Test
    void trimsOldestInChunksAndNeverExceedsTheLimit() {
        BoundedLogBuffer<Integer> buffer = new BoundedLogBuffer<>(100, 1000, 100_000);
        // Mirror the buffer the way a view does: append added, then evict from the head.
        List<Integer> view = new ArrayList<>();
        int trimEvents = 0;
        for (int i = 0; i < 1000; i++) {
            buffer.offer(i);
            if (i % 7 == 0) {
                trimEvents += apply(buffer.drain(), view);
                assertTrue(view.size() <= 100, "view exceeded limit: " + view.size());
            }
        }
        trimEvents += apply(buffer.drain(), view);

        assertEquals(buffer.retained(), view);
        assertTrue(view.size() <= 100);
        assertEquals(999, view.get(view.size() - 1));
        assertEquals(1000 - view.size(), buffer.trimmedTotal());
        // Chunked trimming: far fewer trims than entries beyond the limit.
        assertTrue(trimEvents < 100, "trimmed too often: " + trimEvents);
    }

    @Test
    void floodLargerThanTheWindowStillMirrorsCorrectly() {
        BoundedLogBuffer<Integer> buffer = new BoundedLogBuffer<>(50, 1000, 100_000);
        List<Integer> view = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            buffer.offer(i);
        }
        apply(buffer.drain(), view);
        assertEquals(buffer.retained(), view);
        assertTrue(view.size() <= 50);
        assertEquals(499, view.get(view.size() - 1));
    }

    @Test
    void dropsNewestWhenPendingQueueIsFullAndCountsThem() {
        BoundedLogBuffer<Integer> buffer = new BoundedLogBuffer<>(1000, 1000, 10);
        int accepted = 0;
        for (int i = 0; i < 25; i++) {
            if (buffer.offer(i)) {
                accepted++;
            }
        }
        assertEquals(10, accepted);
        BoundedLogBuffer.Flush<Integer> flush = buffer.drain();
        assertEquals(10, flush.added().size());
        assertEquals(15, flush.droppedCount());
        assertEquals(15, buffer.droppedTotal());
        assertEquals(0, buffer.drain().droppedCount());
        assertTrue(buffer.offer(99), "draining frees pending capacity");
    }

    @Test
    void clearResetsEverything() {
        BoundedLogBuffer<Integer> buffer = new BoundedLogBuffer<>(10, 100, 5);
        for (int i = 0; i < 30; i++) {
            buffer.offer(i);
            buffer.drain();
        }
        buffer.offer(1);
        buffer.clear();
        assertEquals(0, buffer.retainedSize());
        assertEquals(0, buffer.pendingSize());
        assertEquals(0, buffer.trimmedTotal());
        assertEquals(0, buffer.droppedTotal());
        assertTrue(buffer.drain().isEmpty());
        assertFalse(buffer.offer(null));
    }

    @Test
    void concurrentProducersLoseNothingBelowThePendingCap() throws Exception {
        BoundedLogBuffer<Integer> buffer = new BoundedLogBuffer<>(100_000, 100_000, 100_000);
        int threads = 4;
        int perThread = 5000;
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread producer = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    buffer.offer(i);
                }
                done.countDown();
            });
            producer.setDaemon(true);
            producer.start();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(threads * perThread, buffer.drain().added().size());
        assertEquals(0, buffer.droppedTotal());
    }

    @Test
    void rejectsInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedLogBuffer<>(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedLogBuffer<>(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedLogBuffer<>(1, 1, 0));
    }

    private static int apply(BoundedLogBuffer.Flush<Integer> flush, List<Integer> view) {
        view.addAll(flush.added());
        int evict = flush.evictedCount();
        if (evict > 0) {
            view.subList(0, evict).clear();
            return 1;
        }
        return 0;
    }
}
