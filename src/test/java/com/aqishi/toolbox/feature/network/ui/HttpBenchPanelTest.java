package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.HttpBenchRunner;
import com.aqishi.toolbox.feature.network.domain.BenchPlan;
import com.aqishi.toolbox.feature.network.domain.BenchRequest;
import com.aqishi.toolbox.feature.network.domain.BenchResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.swing.SwingUtilities;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class HttpBenchPanelTest {

    private HttpServer server;
    private ExecutorService serverExecutor;

    @BeforeEach
    void startServer() throws Exception {
        serverExecutor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/ping";
    }

    @Test
    void buildsViewWithCatalogIdentity() {
        HttpBenchPanel panel = new HttpBenchPanel();

        assertEquals("network", panel.getGroup());
        assertEquals("http.bench", panel.getName());
        assertNotNull(panel.getView());
        panel.closeResources();
        panel.closeResources();
    }

    @Test
    void showsSnapshotAndResultPushedOnTheEdt() throws Exception {
        HttpBenchPanel panel = new HttpBenchPanel();
        SwingUtilities.invokeAndWait(panel::getView);
        BenchPlan plan = BenchPlan.builder(BenchRequest.of("GET", url(), List.of(), "", null))
                .concurrency(2).totalRequests(25).build();
        BenchResult result = new HttpBenchRunner(plan).run();

        SwingUtilities.invokeAndWait(() -> {
            panel.applySnapshot(result.snapshot());
            panel.applyResult(result);
        });

        AtomicReference<String> completed = new AtomicReference<>();
        AtomicReference<Integer> summaryRows = new AtomicReference<>();
        AtomicReference<Integer> statusRows = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            completed.set(panel.resultView().completedText());
            summaryRows.set(panel.resultView().summaryRows());
            statusRows.set(panel.resultView().statusRows());
        });
        assertEquals("25", completed.get());
        assertTrue(summaryRows.get() >= 12);
        assertEquals(1, statusRows.get());
        assertEquals(result, panel.lastResult());

        String report = BenchFormat.report(result);
        assertTrue(report.contains("p99"));
        assertTrue(report.contains("Requests/sec"));
        assertTrue(report.contains("200"));
        panel.closeResources();
    }

    @Test
    void launchRunsAgainstLoopbackAndFinishesThroughThePollTimer() throws Exception {
        HttpBenchPanel panel = new HttpBenchPanel();
        SwingUtilities.invokeAndWait(panel::getView);
        BenchPlan plan = BenchPlan.builder(BenchRequest.of("GET", url(), List.of(), "", null))
                .concurrency(2).totalRequests(10).build();

        SwingUtilities.invokeAndWait(() -> panel.launch(plan));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        while (!done.get() && System.nanoTime() < deadline) {
            Thread.sleep(50);
            SwingUtilities.invokeAndWait(() -> done.set(panel.lastResult() != null));
        }

        assertTrue(done.get(), "poll timer never delivered the result");
        assertEquals(BenchResult.Outcome.COMPLETED, panel.lastResult().outcome());
        AtomicReference<Boolean> polling = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> polling.set(panel.isPolling()));
        assertFalse(polling.get());
        panel.closeResources();
    }

    @Test
    void importsCurlIntoTheRequestForm() throws Exception {
        HttpBenchPanel panel = new HttpBenchPanel();
        AtomicReference<BenchRequest> request = new AtomicReference<>();
        AtomicReference<Boolean> rejected = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            panel.getView();
            rejected.set(!panel.requestForm().importCurl("wget http://x"));
            panel.requestForm().importCurl("curl -X PUT 'http://10.0.0.5/items/1' -H 'X-Trace: 1' -d 'v=2'");
            request.set(panel.requestForm().toRequest());
        });

        assertTrue(rejected.get());
        assertEquals("PUT", request.get().method());
        assertEquals("http://10.0.0.5/items/1", request.get().uri().toString());
        assertEquals("v=2", request.get().body());
        assertEquals("X-Trace", request.get().headers().get(0).getKey());
    }
}
