package com.aqishi.toolbox.feature.network.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeartbeatSchedulerTest {

    @Test
    void startsOnlyWhenEnabledAndConnected() {
        FakeTicker ticker = new FakeTicker();
        HeartbeatScheduler heartbeat = new HeartbeatScheduler(ticker, () -> { });

        heartbeat.setEnabled(true);
        assertFalse(heartbeat.isRunning(), "no timer before the connection opens");
        heartbeat.connectionOpened();
        assertTrue(heartbeat.isRunning());
        assertEquals(1, ticker.active());
        assertEquals(1, heartbeat.startCount());

        heartbeat.connectionClosed();
        assertFalse(heartbeat.isRunning());
        assertEquals(0, ticker.active());
    }

    @Test
    void restartsExactlyOnceAfterEachReconnectAndNeverDuplicates() {
        FakeTicker ticker = new FakeTicker();
        HeartbeatScheduler heartbeat = new HeartbeatScheduler(ticker, () -> { });
        heartbeat.setEnabled(true);

        for (int i = 1; i <= 5; i++) {
            heartbeat.connectionOpened();
            assertEquals(1, ticker.active(), "exactly one timer after reconnect #" + i);
            assertEquals(i, heartbeat.startCount());
            heartbeat.connectionClosed();
            assertEquals(0, ticker.active());
        }
        // Duplicate open events (e.g. a stale callback) still leave one timer.
        heartbeat.connectionOpened();
        heartbeat.connectionOpened();
        assertEquals(1, ticker.active());
    }

    @Test
    void toggleAndIntervalChangesApplyWhileConnected() {
        FakeTicker ticker = new FakeTicker();
        HeartbeatScheduler heartbeat = new HeartbeatScheduler(ticker, () -> { });
        heartbeat.connectionOpened();
        assertFalse(heartbeat.isRunning(), "disabled heartbeat never runs");

        heartbeat.setEnabled(true);
        assertEquals(1, ticker.active());
        heartbeat.setIntervalMs(2000);
        assertEquals(1, ticker.active());
        assertEquals(2000, ticker.lastPeriod);
        heartbeat.setIntervalMs(2000);
        assertEquals(2, heartbeat.startCount(), "unchanged interval does not restart");

        heartbeat.setEnabled(false);
        assertEquals(0, ticker.active());
    }

    @Test
    void failingBeatDoesNotEscapeAndShutdownIsFinal() {
        FakeTicker ticker = new FakeTicker();
        AtomicInteger beats = new AtomicInteger();
        HeartbeatScheduler heartbeat = new HeartbeatScheduler(ticker, () -> {
            beats.incrementAndGet();
            throw new IllegalStateException("socket closed");
        });
        heartbeat.setEnabled(true);
        heartbeat.connectionOpened();
        ticker.tick();
        ticker.tick();
        assertEquals(2, beats.get());

        heartbeat.shutdown();
        heartbeat.shutdown();
        assertTrue(ticker.shutdown);
        assertEquals(0, ticker.active());
        heartbeat.connectionOpened();
        assertFalse(heartbeat.isRunning(), "no restart after shutdown");
    }

    @Test
    void daemonTickerFiresPeriodicallyAndStops() throws Exception {
        HeartbeatScheduler.Ticker ticker = HeartbeatScheduler.daemonTicker("heartbeat-test");
        CountDownLatch beats = new CountDownLatch(3);
        HeartbeatScheduler heartbeat = new HeartbeatScheduler(ticker, beats::countDown);
        heartbeat.setIntervalMs(20);
        heartbeat.setEnabled(true);
        heartbeat.connectionOpened();
        assertTrue(beats.await(5, TimeUnit.SECONDS));
        heartbeat.shutdown();
        assertFalse(heartbeat.isRunning());
    }
}
