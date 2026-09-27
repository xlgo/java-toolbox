package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.BenchPlan;
import com.aqishi.toolbox.feature.network.domain.BenchRequest;
import com.aqishi.toolbox.feature.network.domain.BenchResult;
import com.aqishi.toolbox.feature.network.domain.BenchSecond;
import com.aqishi.toolbox.feature.network.domain.BenchSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 压测执行器的计数、并发、停止条件、限速与预热。 */
@Timeout(20)
class HttpBenchRunnerTest {

    private BenchTestServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new BenchTestServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private BenchPlan.Builder plan() {
        return BenchPlan.builder(BenchRequest.of("GET", server.url(), List.of(), "", null));
    }

    @Test
    void sendsExactlyTheRequestedTotalWithConcurrency() throws Exception {
        BenchResult result = new HttpBenchRunner(plan().concurrency(8).totalRequests(200).build()).run();

        BenchSnapshot snapshot = result.snapshot();
        assertEquals(BenchResult.Outcome.COMPLETED, result.outcome());
        assertEquals(200, snapshot.completed());
        assertEquals(200, server.hits.get());
        assertEquals(0, snapshot.errors());
        assertEquals(200L, snapshot.statusCounts().get(200));
        assertEquals(200, snapshot.latency().count());
        assertEquals(200L * 2, snapshot.bytesReceived());
        assertEquals(1.0, snapshot.progress(), 1e-9);
        assertEquals(BenchSnapshot.Phase.FINISHED, snapshot.phase());
        long timelineTotal = snapshot.timeline().stream().mapToLong(BenchSecond::requests).sum();
        assertEquals(200, timelineTotal, "every measured request lands in exactly one second");
    }

    @Test
    void neverHasMoreThanConcurrencyRequestsInFlight() throws Exception {
        server.delay(15);
        BenchResult result = new HttpBenchRunner(plan().concurrency(3).totalRequests(30).build()).run();

        assertEquals(30, result.snapshot().completed());
        assertTrue(server.peak.get() <= 3, "server saw " + server.peak.get() + " concurrent requests");
        assertTrue(server.peak.get() >= 2, "concurrency should actually be used");
        assertTrue(result.snapshot().maxInFlight() <= 3);
    }

    @Test
    void durationModeStopsNearTheDeadline() throws Exception {
        long started = System.nanoTime();
        BenchResult result = new HttpBenchRunner(plan().concurrency(4)
                .duration(Duration.ofMillis(700)).build()).run();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(BenchResult.Outcome.COMPLETED, result.outcome());
        assertTrue(elapsedMs >= 650 && elapsedMs < 2500, "took " + elapsedMs + " ms");
        assertTrue(result.snapshot().completed() > 10);
        assertEquals(result.snapshot().completed(), server.hits.get());
        assertTrue(result.snapshot().elapsedSeconds() > 0.6 && result.snapshot().elapsedSeconds() < 1.5);
        assertFalse(result.snapshot().timeline().isEmpty());
    }

    @Test
    void rateLimitHoldsTheTargetRate() throws Exception {
        long started = System.nanoTime();
        BenchResult result = new HttpBenchRunner(plan().concurrency(4).targetRps(100)
                .totalRequests(50).build()).run();
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(50, result.snapshot().completed());
        // 50 个节拍间隔 10 ms，最后一个在 490 ms 处发出
        assertTrue(elapsedMs >= 450, "rate limit ignored, took " + elapsedMs + " ms");
        double rps = result.snapshot().requestsPerSecond();
        assertTrue(rps > 80 && rps < 125, "achieved " + rps + " rps");
    }

    @Test
    void rateLimitedLatencyIsMeasuredFromTheIntendedSendTime() throws Exception {
        // 目标 20 rps（每 50 ms 一个），但单线程每个请求要 100 ms：第 k 个请求实际在 100k ms 发出，
        // 从计划时刻 50k ms 算起延迟约为 100 + 50k ms。只按实际发送算就只有 100 ms。
        server.delay(100);
        BenchResult result = new HttpBenchRunner(plan().concurrency(1).targetRps(20)
                .totalRequests(8).build()).run();

        BenchSnapshot snapshot = result.snapshot();
        assertEquals(8, snapshot.completed());
        assertTrue(snapshot.latency().min() >= 95_000, "min " + snapshot.latency().min());
        assertTrue(snapshot.latency().max() >= 400_000,
                "queueing delay missing from latency, max " + snapshot.latency().max());
    }

    @Test
    void warmupRequestsAreSentButExcludedFromStats() throws Exception {
        BenchResult result = new HttpBenchRunner(plan().concurrency(2).warmupRequests(10)
                .totalRequests(20).build()).run();

        assertEquals(30, server.hits.get());
        assertEquals(20, result.snapshot().completed());
        assertEquals(10, result.snapshot().warmupCompleted());
        assertEquals(20, result.snapshot().latency().count());
    }

    @Test
    void warmupDurationIsExcludedInDurationMode() throws Exception {
        BenchResult result = new HttpBenchRunner(plan().concurrency(2)
                .warmupDuration(Duration.ofMillis(300)).duration(Duration.ofMillis(300)).build()).run();

        BenchSnapshot snapshot = result.snapshot();
        assertTrue(snapshot.warmupCompleted() > 0);
        assertTrue(snapshot.completed() > 0);
        assertEquals(server.hits.get(), snapshot.completed() + snapshot.warmupCompleted());
        assertTrue(snapshot.elapsedSeconds() < 1.0, "warm-up leaked into elapsed: " + snapshot.elapsedSeconds());
    }

    @Test
    void postBodyAndHeadersReachTheServer() throws Exception {
        BenchRequest request = BenchRequest.of("POST", server.url(),
                List.of("X-Bench: probe-1", "Host: ignored.example"), "{\"a\":1}", "application/json");
        BenchResult result = new HttpBenchRunner(BenchPlan.builder(request)
                .concurrency(2).totalRequests(3).build()).run();

        assertEquals(3, result.snapshot().successes());
        assertEquals(3, server.captured.size());
        for (BenchTestServer.Captured captured : server.captured) {
            assertEquals("POST", captured.method);
            assertEquals("probe-1", captured.header);
            assertEquals("{\"a\":1}", captured.body);
        }
        assertEquals(List.of("Host"), request.droppedHeaders());
    }

    @Test
    void http2FallsBackToHttp11AgainstPlainServer() throws Exception {
        BenchResult result = new HttpBenchRunner(plan().httpVersion(HttpClient.Version.HTTP_2)
                .concurrency(2).totalRequests(6).build()).run();

        assertEquals(6, result.snapshot().successes());
        assertEquals(0, result.snapshot().errors());
    }
}
