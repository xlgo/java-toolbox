package com.aqishi.toolbox.infra.network.scan;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.network.domain.*;
import com.aqishi.toolbox.util.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

class ScanResultRepositoryTest {
    @TempDir Path temp;

    private ProbeOutcome row(int port) {
        return new ProbeOutcome(
                "local",
                "::1",
                port,
                port % 2 == 0 ? ProbeOutcome.State.OPEN : ProbeOutcome.State.TIMEOUT,
                1500,
                "=formula\nquoted \" value",
                123);
    }

    @Test
    void paginationFiltersAndFullStreamingExportUseAllStoredRows() throws Exception {
        Path file;
        try (var store = new ScanResultRepository(temp, 16)) {
            file = store.path();
            List<ProbeOutcome> rows = new ArrayList<>();
            for (int i = 1; i <= 1200; i++) rows.add(row(i));
            store.append(rows);
            assertEquals(1200, store.page(ScanResultRepository.Filter.all(), 0, 500).total());
            assertEquals(
                    500, store.page(ScanResultRepository.Filter.all(), 500, 500).rows().size());
            assertEquals(
                    200, store.page(ScanResultRepository.Filter.all(), 1000, 500).rows().size());
            assertEquals(
                    600,
                    store.page(
                                    new ScanResultRepository.Filter(
                                            "::1", ProbeOutcome.State.OPEN, ""),
                                    0,
                                    500)
                            .total());
            assertEquals(
                    0, store.page(new ScanResultRepository.Filter("", null, "%"), 0, 500).total());
            Path json = temp.resolve("scan.json");
            assertEquals(
                    1200,
                    store.export(
                            json,
                            true,
                            ScanResultRepository.Filter.all(),
                            Map.of("total", 1200),
                            () -> false));
            var data = Json.mapper().readTree(json.toFile());
            assertEquals(1200, data.path("results").size());
            assertEquals(1200, data.path("scan").path("total").asInt());
            Path csv = temp.resolve("scan.csv");
            store.export(
                    csv,
                    false,
                    new ScanResultRepository.Filter("", ProbeOutcome.State.OPEN, ""),
                    Map.of(),
                    () -> false);
            assertTrue(Files.readString(csv).contains("'=formula"));
            assertTrue(
                    store.copyOpen(ScanResultRepository.Filter.all(), 50000)
                            .startsWith("[::1]:2\n"));
            assertEquals(600, store.hosts().get(0).open());
        }
        assertFalse(Files.exists(file));
    }

    @Test
    void dnsFailuresCanBeFilteredWithoutMatchingAllHosts() throws Exception {
        try (var store = new ScanResultRepository(temp, 16)) {
            store.append(
                    List.of(
                            row(80),
                            new ProbeOutcome(
                                    "missing.invalid",
                                    "",
                                    0,
                                    ProbeOutcome.State.DNS_FAILED,
                                    0,
                                    "UnknownHostException",
                                    0)));
            var filter = new ScanResultRepository.Filter("dns:missing.invalid", null, "");
            assertEquals(
                    0,
                    store.hosts().stream()
                            .filter(host -> host.ip().equals("dns:missing.invalid"))
                            .findFirst()
                            .orElseThrow()
                            .probed());
            assertEquals(1, store.page(filter, 0, 500).total());
            assertEquals("missing.invalid", store.page(filter, 0, 500).rows().get(0).source());
            assertThrows(
                    java.io.InterruptedIOException.class,
                    () ->
                            store.export(
                                    temp.resolve("cancelled.json"),
                                    true,
                                    ScanResultRepository.Filter.all(),
                                    Map.of(),
                                    () -> true));
        }
    }

    @Test
    void cacheBudgetFailureDoesNotEraseEarlierResults() throws Exception {
        try (var store = new ScanResultRepository(temp, 1)) {
            store.append(List.of(row(80)));
            var huge =
                    new ProbeOutcome(
                            "local",
                            "127.0.0.1",
                            81,
                            ProbeOutcome.State.ERROR,
                            0,
                            "x".repeat(600000),
                            0);
            assertThrows(java.io.IOException.class, () -> store.append(List.of(huge)));
            assertEquals(1, store.page(ScanResultRepository.Filter.all(), 0, 500).total());
        }
    }
}
