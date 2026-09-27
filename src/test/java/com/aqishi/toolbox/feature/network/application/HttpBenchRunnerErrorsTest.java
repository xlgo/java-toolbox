package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.BenchErrorKind;
import com.aqishi.toolbox.feature.network.domain.BenchPlan;
import com.aqishi.toolbox.feature.network.domain.BenchRequest;
import com.aqishi.toolbox.feature.network.domain.BenchResult;
import com.aqishi.toolbox.feature.network.domain.BenchSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 失败分类、断言与取消。 */
@Timeout(20)
class HttpBenchRunnerErrorsTest {

    private BenchTestServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new BenchTestServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private BenchPlan.Builder plan(String url) {
        return BenchPlan.builder(BenchRequest.of("GET", url, List.of(), "", null));
    }

    @Test
    void serverErrorsAreCountedAs5xx() throws Exception {
        server.status(500);
        BenchSnapshot snapshot = new HttpBenchRunner(plan(server.url()).concurrency(2)
                .totalRequests(10).build()).run().snapshot();

        assertEquals(10, snapshot.completed());
        assertEquals(10, snapshot.errors());
        assertEquals(10L, snapshot.errorCounts().get(BenchErrorKind.HTTP_5XX));
        assertEquals(10L, snapshot.statusCounts().get(500));
        assertEquals(10, snapshot.latency().count(), "responses with bad status still have latency");
        assertEquals(1.0, snapshot.errorRate(), 1e-9);
    }

    @Test
    void widenedStatusRangeAcceptsServerErrors() throws Exception {
        server.status(503);
        BenchSnapshot snapshot = new HttpBenchRunner(plan(server.url()).expectedStatus(200, 599)
                .totalRequests(4).build()).run().snapshot();

        assertEquals(0, snapshot.errors());
    }

    @Test
    void clientErrorsAreCountedAs4xx() throws Exception {
        server.status(404);
        BenchSnapshot snapshot = new HttpBenchRunner(plan(server.url()).totalRequests(3).build())
                .run().snapshot();

        assertEquals(3L, snapshot.errorCounts().get(BenchErrorKind.HTTP_4XX));
    }

    @Test
    void bodyAssertionFailuresAreClassified() throws Exception {
        server.body("hello world");
        BenchSnapshot failing = new HttpBenchRunner(plan(server.url()).bodyContains("missing")
                .totalRequests(5).build()).run().snapshot();
        BenchSnapshot passing = new HttpBenchRunner(plan(server.url()).bodyContains("world")
                .totalRequests(5).build()).run().snapshot();

        assertEquals(5L, failing.errorCounts().get(BenchErrorKind.ASSERTION_FAILED));
        assertEquals(0, passing.errors());
        assertEquals(5L * "hello world".length(), passing.bytesReceived());
    }

    @Test
    void requestTimeoutsAreClassified() throws Exception {
        server.blockForever();
        BenchSnapshot snapshot = new HttpBenchRunner(plan(server.url()).concurrency(3)
                .requestTimeout(Duration.ofMillis(200)).totalRequests(3).build()).run().snapshot();

        assertEquals(3, snapshot.completed());
        assertEquals(3L, snapshot.errorCounts().get(BenchErrorKind.REQUEST_TIMEOUT));
        assertEquals(0, snapshot.latency().count(), "transport failures carry no latency sample");
    }

    @Test
    void refusedConnectionsAreClassified() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        BenchSnapshot snapshot = new HttpBenchRunner(plan("http://127.0.0.1:" + port + "/")
                .concurrency(2).connectTimeout(Duration.ofSeconds(5)).totalRequests(2).build())
                .run().snapshot();

        assertEquals(2, snapshot.errors());
        assertEquals(2L, snapshot.errorCounts().get(BenchErrorKind.CONNECT_REFUSED),
                "classified as " + snapshot.errorCounts());
    }

    @Test
    void cancelStopsPromptlyAndReleasesThreads() throws Exception {
        server.blockForever();
        HttpBenchRunner runner = new HttpBenchRunner(plan(server.url()).concurrency(5)
                .duration(Duration.ofSeconds(30)).build());
        runner.start();
        long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (runner.snapshot().inFlight() < 5 && System.nanoTime() < waitUntil) {
            Thread.sleep(10);
        }
        assertEquals(5, runner.snapshot().inFlight());

        long cancelledAt = System.nanoTime();
        runner.cancel();
        BenchResult result = runner.completion().get(3, TimeUnit.SECONDS);
        long stopMs = (System.nanoTime() - cancelledAt) / 1_000_000L;

        assertEquals(BenchResult.Outcome.CANCELLED, result.outcome());
        assertTrue(stopMs < 2000, "cancel took " + stopMs + " ms");
        assertEquals(0, result.snapshot().completed(), "cancelled in-flight requests are not counted");
        assertTrue(runner.isFinished());
        assertTrue(waitForBenchThreadsToExit(), "bench threads still alive: " + benchThreads());
    }

    @Test
    void cannotStartTwice() throws Exception {
        HttpBenchRunner runner = new HttpBenchRunner(plan(server.url()).totalRequests(1).build());
        runner.run();
        boolean rejected = false;
        try {
            runner.start();
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        assertTrue(rejected);
        assertFalse(runner.isCancelled());
    }

    private static boolean waitForBenchThreadsToExit() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (benchThreads().isEmpty()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static List<String> benchThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("http-bench-worker") || name.startsWith("http-bench-coordinator"))
                .toList();
    }
}
