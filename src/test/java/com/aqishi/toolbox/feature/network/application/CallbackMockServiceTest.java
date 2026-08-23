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
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackMockServiceTest {
    @Test
    void returnsConfiguredJsonResponseAndPublishesMatchedRule() throws Exception {
        CallbackMockService service = new CallbackMockService(
                new MockRuleResolver(), new MockHttpRequestParser());
        AtomicReference<MockRequestRecord> record =
                new AtomicReference<MockRequestRecord>();
        service.setRequestListener(record::set);
        service.replaceRuleSet(ruleSetFor("paid", 201,
                "{\"result\":\"${json.status}\"}"));
        service.start(0);
        try {
            HttpResult result = postJson(service.getPort(), "/orders",
                    "{\"status\":\"paid\"}");

            assertEquals(201, result.status);
            assertEquals("application/json", result.contentType);
            assertEquals("{\"result\":\"paid\"}", result.body);
            assertNotNull(record.get());
            assertEquals("paid", record.get().getRuleName());
            assertFalse(record.get().isFallback());
            assertEquals(201, record.get().getResponseStatus());
            assertEquals("{\"result\":\"paid\"}", record.get().getResponseBody());
        } finally {
            service.closeResources();
            service.closeResources();
        }
        assertFalse(service.isRunning());
    }

    @Test
    void servesFallbackWhenNoRuleMatches() throws Exception {
        CallbackMockService service = new CallbackMockService(
                new MockRuleResolver(), new MockHttpRequestParser());
        AtomicReference<MockRequestRecord> record =
                new AtomicReference<MockRequestRecord>();
        service.setRequestListener(record::set);
        service.replaceRuleSet(ruleSetFor("paid", 201, "paid"));
        service.start(0);
        try {
            HttpResult result = postJson(service.getPort(), "/orders",
                    "{\"status\":\"pending\"}");

            assertEquals(200, result.status);
            assertEquals("fallback", result.body);
            assertNotNull(record.get());
            assertTrue(record.get().isFallback());
            assertEquals(200, record.get().getResponseStatus());
        } finally {
            service.closeResources();
        }
    }

    private static MockRuleSet ruleSetFor(String status, int responseStatus,
                                          String responseBody) {
        MockRule rule = MockRule.builder(status)
                .method("POST")
                .path(PathMatchMode.EXACT, "/orders")
                .condition(new MockCondition(MatchSource.JSON, "status",
                        MatchOperator.EQUALS, status))
                .response(new MockResponse(responseStatus, "application/json",
                        responseBody))
                .build();
        return MockRuleSet.of(Collections.singletonList(rule),
                new MockResponse(200, "application/json", "fallback"));
    }

    private static HttpResult postJson(int port, String path, String body)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                "http://127.0.0.1:" + port + path).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        byte[] requestBody = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(requestBody.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(requestBody);
        }
        int status = connection.getResponseCode();
        InputStream input = status >= 400
                ? connection.getErrorStream() : connection.getInputStream();
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
