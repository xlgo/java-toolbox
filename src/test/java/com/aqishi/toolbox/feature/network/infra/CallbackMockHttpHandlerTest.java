package com.aqishi.toolbox.feature.network.infra;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleResolver;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleSet;
import com.aqishi.toolbox.feature.network.domain.callbackmock.PathMatchMode;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockTemplateRenderer;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CallbackMockHttpHandlerTest {
    @Test
    void sendsNoBodyLengthForNoContentStatusCodes() throws Exception {
        MockRule rule = MockRule.builder("no-content")
                .path(PathMatchMode.EXACT, "/x")
                .response(new MockResponse(204, "text/plain", "must-not-be-sent"))
                .build();
        AtomicReference<MockRuleSet> rules = new AtomicReference<MockRuleSet>(
                MockRuleSet.of(Collections.singletonList(rule),
                        new MockResponse(200, "text/plain", "fallback")));
        FakeExchange exchange = new FakeExchange();
        CallbackMockHttpHandler handler = new CallbackMockHttpHandler(
                new MockRuleResolver(), new MockHttpRequestParser(),
                new MockTemplateRenderer(), rules,
                record -> { });

        handler.handle(exchange);

        assertEquals(204, exchange.responseStatus);
        assertEquals(-1L, exchange.responseLength);
        assertEquals(0, exchange.responseBody.size());
        assertTrue(exchange.closed);
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
        private int responseStatus;
        private long responseLength;
        private boolean closed;

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return URI.create("/x");
        }

        @Override
        public String getRequestMethod() {
            return "GET";
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public InputStream getRequestBody() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int status, long length) {
            responseStatus = status;
            responseLength = length;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress("127.0.0.1", 1234);
        }

        @Override
        public int getResponseCode() {
            return responseStatus;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress("127.0.0.1", 8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return null;
        }

        @Override
        public void setAttribute(String name, Object value) {
        }

        @Override
        public void setStreams(InputStream inputStream, OutputStream outputStream) {
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
    }
}
