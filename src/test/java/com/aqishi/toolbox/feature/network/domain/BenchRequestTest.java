package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 请求模型、cURL 导入、参数上限与错误分类。 */
class BenchRequestTest {

    @Test
    void importsCurlCommand() {
        CurlCommand curl = CurlCommand.parse("curl 'https://api.example.com/v1/orders' "
                + "-H 'Authorization: Bearer abc' -H 'Content-Type: application/json' "
                + "-H 'Content-Length: 12' --data-raw '{\"id\":1}' --compressed");
        BenchRequest request = BenchRequest.fromCurl(curl);

        assertEquals("POST", request.method());
        assertEquals("https://api.example.com/v1/orders", request.uri().toString());
        assertEquals("{\"id\":1}", request.body());
        assertEquals(List.of(Map.entry("Authorization", "Bearer abc"),
                Map.entry("Content-Type", "application/json")), request.headers());
        assertEquals(List.of("Content-Length"), request.droppedHeaders());
    }

    @Test
    void addsContentTypeOnlyWhenMissing() {
        BenchRequest added = BenchRequest.of("post", "http://127.0.0.1/", List.of(), "x=1",
                "application/x-www-form-urlencoded");
        BenchRequest kept = BenchRequest.of("POST", "http://127.0.0.1/",
                List.of("content-type: text/plain"), "x", "application/json");

        assertEquals("POST", added.method());
        assertEquals(List.of(Map.entry("Content-Type", "application/x-www-form-urlencoded")), added.headers());
        assertEquals(List.of(Map.entry("content-type", "text/plain")), kept.headers());
    }

    @Test
    void splitsHeaderTextAndRejectsMalformedLines() {
        assertEquals(List.of("A: 1", "B: two"),
                BenchRequest.splitHeaderLines("A: 1\r\n\n# comment\n  B: two  \n"));
        assertThrows(IllegalArgumentException.class,
                () -> BenchRequest.of("GET", "http://h/", List.of("no colon"), "", null));
        assertThrows(IllegalArgumentException.class,
                () -> BenchRequest.of("GET", "http://h/", List.of("Bad Name: x"), "", null));
    }

    @Test
    void rejectsNonHttpUrls() {
        assertThrows(IllegalArgumentException.class, () -> BenchRequest.of("GET", "", List.of(), "", null));
        assertThrows(IllegalArgumentException.class,
                () -> BenchRequest.of("GET", "ftp://host/", List.of(), "", null));
        assertThrows(IllegalArgumentException.class,
                () -> BenchRequest.of("GET", "http:///path", List.of(), "", null));
        assertThrows(IllegalArgumentException.class,
                () -> BenchRequest.of("GE T", "http://h/", List.of(), "", null));
        assertEquals("::1", BenchRequest.of("GET", "http://[::1]:80/", List.of(), "", null).host());
    }

    @Test
    void planEnforcesHardCaps() {
        BenchRequest request = BenchRequest.of("GET", "http://127.0.0.1/", List.of(), "", null);

        assertThrows(IllegalArgumentException.class, () -> BenchPlan.builder(request).concurrency(1001).build());
        assertThrows(IllegalArgumentException.class, () -> BenchPlan.builder(request).concurrency(0).build());
        assertThrows(IllegalArgumentException.class,
                () -> BenchPlan.builder(request).totalRequests(10_000_001).build());
        assertThrows(IllegalArgumentException.class,
                () -> BenchPlan.builder(request).duration(Duration.ofMinutes(61)).build());
        assertThrows(IllegalArgumentException.class, () -> BenchPlan.builder(request).targetRps(-1).build());
        assertThrows(IllegalArgumentException.class,
                () -> BenchPlan.builder(request).expectedStatus(300, 200).build());

        BenchPlan plan = BenchPlan.builder(request).concurrency(1000).duration(Duration.ofHours(1))
                .warmupRequests(5).warmupDuration(Duration.ofSeconds(3)).build();
        assertEquals(BenchPlan.StopMode.DURATION, plan.stopMode());
        assertEquals(0, plan.warmupRequests(), "warm-up follows the stop mode");
        assertEquals(Duration.ofSeconds(3), plan.warmupDuration());
    }

    @Test
    void parsesStatusRanges() {
        assertArrayEquals(new int[]{200, 299}, BenchPlan.parseStatusRange("200-299"));
        assertArrayEquals(new int[]{200, 299}, BenchPlan.parseStatusRange("2xx"));
        assertArrayEquals(new int[]{204, 204}, BenchPlan.parseStatusRange(" 204 "));
        assertThrows(IllegalArgumentException.class, () -> BenchPlan.parseStatusRange("abc"));
        assertThrows(IllegalArgumentException.class, () -> BenchPlan.parseStatusRange("600"));
    }

    @Test
    void classifiesTransportErrors() {
        assertEquals(BenchErrorKind.CONNECT_TIMEOUT,
                BenchErrorKind.fromThrowable(new HttpConnectTimeoutException("x")));
        assertEquals(BenchErrorKind.REQUEST_TIMEOUT,
                BenchErrorKind.fromThrowable(new HttpTimeoutException("x")));
        assertEquals(BenchErrorKind.CONNECT_REFUSED, BenchErrorKind.fromThrowable(new ConnectException()));
        ConnectException unresolved = new ConnectException();
        unresolved.initCause(new UnresolvedAddressException());
        assertEquals(BenchErrorKind.DNS_FAILURE, BenchErrorKind.fromThrowable(unresolved));
        assertEquals(BenchErrorKind.DNS_FAILURE,
                BenchErrorKind.fromThrowable(new ExecutionException(new UnknownHostException("h"))));
        assertEquals(BenchErrorKind.TLS_ERROR,
                BenchErrorKind.fromThrowable(new IOException(new javax.net.ssl.SSLHandshakeException("bad"))));
        assertEquals(BenchErrorKind.IO_ERROR, BenchErrorKind.fromThrowable(new IOException("reset")));
        assertEquals(BenchErrorKind.OTHER, BenchErrorKind.fromThrowable(new IllegalStateException()));
        assertEquals(BenchErrorKind.HTTP_4XX, BenchErrorKind.fromUnexpectedStatus(404));
        assertEquals(BenchErrorKind.HTTP_5XX, BenchErrorKind.fromUnexpectedStatus(502));
        assertEquals(BenchErrorKind.ASSERTION_FAILED, BenchErrorKind.fromUnexpectedStatus(302));
        assertTrue(BenchErrorKind.values().length >= 9);
    }
}
