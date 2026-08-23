package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchOperator;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchSource;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockCondition;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleResolver;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleSet;
import com.aqishi.toolbox.feature.network.domain.callbackmock.PathMatchMode;
import com.aqishi.toolbox.feature.network.infra.MockHttpRequestParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CallbackMockEndToEndTest {
    @Test
    void returnsDistinctTemplatedResponsesForEveryRequestInputAndFallback()
            throws Exception {
        CallbackMockService service = new CallbackMockService(
                new MockRuleResolver(), new MockHttpRequestParser());
        service.replaceRuleSet(ruleSet());
        service.start(0);
        int port = service.getPort();
        try {
            assertResponse(request(port, "POST", "/callback", "application/json",
                    "{\"status\":\"paid\"}", Collections.<String, String>emptyMap()),
                    201, "application/json", "json-paid");
            assertResponse(request(port, "POST", "/callback",
                    "application/x-www-form-urlencoded", "status=refunded",
                    Collections.<String, String>emptyMap()),
                    202, "text/plain", "form-refunded");
            assertResponse(request(port, "GET", "/callback?mode=preview", null, null,
                    Collections.<String, String>emptyMap()),
                    203, "text/plain", "query-preview");
            Map<String, String> header = new LinkedHashMap<String, String>();
            header.put("X-Channel", "mobile");
            assertResponse(request(port, "GET", "/callback", null, null, header),
                    206, "text/plain", "header-mobile");
            assertResponse(request(port, "GET", "/unmatched", null, null,
                    Collections.<String, String>emptyMap()),
                    200, "application/json", "fallback");
        } finally {
            service.closeResources();
        }
        assertFalse(service.isRunning());
        HttpServer verificationServer = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), port), 0);
        verificationServer.stop(0);
    }

    private static MockRuleSet ruleSet() {
        MockRule json = rule("json", "POST", "/callback",
                new MockCondition(MatchSource.JSON, "status", MatchOperator.EQUALS, "paid"),
                new MockResponse(201, "application/json", "json-${json.status}"));
        MockRule form = rule("form", "POST", "/callback",
                new MockCondition(MatchSource.FORM, "status", MatchOperator.EQUALS, "refunded"),
                new MockResponse(202, "text/plain", "form-${form.status}"));
        MockRule query = rule("query", "GET", "/callback",
                new MockCondition(MatchSource.QUERY, "mode", MatchOperator.EQUALS, "preview"),
                new MockResponse(203, "text/plain", "query-${query.mode}"));
        MockRule header = rule("header", "GET", "/callback",
                new MockCondition(MatchSource.HEADER, "X-Channel", MatchOperator.EQUALS,
                        "mobile"),
                new MockResponse(206, "text/plain", "header-${header.X-Channel}"));
        return MockRuleSet.of(Arrays.asList(json, form, query, header),
                new MockResponse(200, "application/json", "fallback"));
    }

    private static MockRule rule(String name, String method, String path,
                                 MockCondition condition, MockResponse response) {
        return MockRule.builder(name).method(method)
                .path(PathMatchMode.EXACT, path)
                .condition(condition).response(response).build();
    }

    private static HttpResult request(int port, String method, String path,
                                      String contentType, String body,
                                      Map<String, String> headers) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod(method);
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType);
        }
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400 ? connection.getErrorStream()
                : connection.getInputStream();
        try {
            return new HttpResult(status, connection.getHeaderField("Content-Type"),
                    read(input));
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void assertResponse(HttpResult response, int status,
                                       String contentType, String body) {
        assertEquals(status, response.status);
        assertEquals(contentType, response.contentType);
        assertEquals(body, response.body);
    }

    private static final class HttpResult {
        private final int status;
        private final String contentType;
        private final String body;

        private HttpResult(int status, String contentType, String body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
