package com.aqishi.toolbox.feature.network.infra;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchSource;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRequest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockHttpRequestParserTest {
    private static final int ONE_MEBIBYTE = 1024 * 1024;

    private final MockHttpRequestParser parser = new MockHttpRequestParser();

    @Test
    void parsesDecodedQueryDuplicatesAndCaseInsensitiveHeaders() throws Exception {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("X-Request-Id", "trace-9");

        MockRequest request = parse("GET",
                "/callback?tag=one&tag=two&name=Jane+Doe&encoded=%E4%B8%AD%E6%96%87&flag",
                null, null, headers);

        assertEquals("GET", request.getMethod());
        assertEquals("/callback", request.getPath());
        assertEquals("tag=one&tag=two&name=Jane+Doe&encoded=%E4%B8%AD%E6%96%87&flag",
                request.getRawQuery());
        assertEquals(Arrays.asList("one", "two"),
                request.values(MatchSource.QUERY, "tag"));
        assertEquals(Collections.singletonList("Jane Doe"),
                request.values(MatchSource.QUERY, "name"));
        assertEquals(Collections.singletonList("中文"),
                request.values(MatchSource.QUERY, "encoded"));
        assertEquals(Collections.singletonList(""),
                request.values(MatchSource.QUERY, "flag"));
        assertEquals(Collections.singletonList("trace-9"),
                request.values(MatchSource.HEADER, "x-request-id"));
        assertEquals(Collections.singletonList("trace-9"),
                request.values(MatchSource.HEADER, "X-REQUEST-ID"));
        assertNotNull(request.getClientAddress());
        assertFalse(request.getClientAddress().isEmpty());
    }

    @Test
    void parsesUrlEncodedFormFields() throws Exception {
        MockRequest request = parse("POST", "/callback",
                "application/x-www-form-urlencoded; charset=UTF-8",
                "status=paid&tag=one&tag=two&message=hello+world&flag", noHeaders());

        assertEquals(Arrays.asList("one", "two"),
                request.values(MatchSource.FORM, "tag"));
        assertEquals(Collections.singletonList("paid"),
                request.values(MatchSource.FORM, "status"));
        assertEquals(Collections.singletonList("hello world"),
                request.values(MatchSource.FORM, "message"));
        assertEquals(Collections.singletonList(""),
                request.values(MatchSource.FORM, "flag"));
    }

    @Test
    void parsesMultipartTextFieldsAndIgnoresFiles() throws Exception {
        String boundary = "----callback-boundary";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"status\"\r\n"
                + "\r\n"
                + "paid\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"tag\"\r\n"
                + "\r\n"
                + "one\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"tag\"\r\n"
                + "\r\n"
                + "two\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"attachment\"; filename=\"proof.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "ignore me\r\n"
                + "--" + boundary + "--\r\n";

        MockRequest request = parse("POST", "/callback",
                "multipart/form-data; boundary=" + boundary, body, noHeaders());

        assertEquals(Collections.singletonList("paid"),
                request.values(MatchSource.FORM, "status"));
        assertEquals(Arrays.asList("one", "two"),
                request.values(MatchSource.FORM, "tag"));
        assertEquals(Collections.emptyList(),
                request.values(MatchSource.FORM, "attachment"));
    }

    @Test
    void keepsBoundaryLikeTextInsideMultipartFieldValues() throws Exception {
        String boundary = "----callback-boundary";
        String value = "prefix --" + boundary + " suffix";
        String body = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"message\"\r\n"
                + "\r\n"
                + value + "\r\n"
                + "--" + boundary + "--\r\n";

        MockRequest request = parse("POST", "/callback",
                "multipart/form-data; boundary=" + boundary, body, noHeaders());

        assertEquals(Collections.singletonList(value),
                request.values(MatchSource.FORM, "message"));
    }

    @Test
    void parsesJsonObjectBodies() throws Exception {
        MockRequest request = parse("POST", "/callback", "application/json; charset=UTF-8",
                "{\"order\":{\"status\":\"paid\"},\"items\":[{\"sku\":\"A\"}]}",
                noHeaders());

        assertNotNull(request.getJsonRoot());
        assertTrue(request.getJsonRoot().isObject());
        assertEquals(Collections.singletonList("paid"),
                request.values(MatchSource.JSON, "order.status"));
        assertEquals(Collections.singletonList("A"),
                request.values(MatchSource.JSON, "items[0].sku"));
    }

    @Test
    void parsesJsonArrayBodiesForPlusJsonContentTypes() throws Exception {
        MockRequest request = parse("POST", "/callback",
                "application/vnd.callback+json; charset=UTF-8",
                "[\"first\",{\"status\":\"queued\"}]", noHeaders());

        assertNotNull(request.getJsonRoot());
        assertTrue(request.getJsonRoot().isArray());
        assertEquals(Collections.singletonList("queued"),
                request.values(MatchSource.JSON, "[1].status"));
    }

    @Test
    void leavesUnsupportedContentTypesUnparsed() throws Exception {
        MockRequest request = parse("POST", "/callback", "text/plain",
                "status=paid", noHeaders());

        assertEquals("status=paid", request.getRawBody());
        assertEquals(Collections.emptyList(), request.values(MatchSource.FORM, "status"));
        assertNull(request.getJsonRoot());
    }

    @Test
    void ignoresMalformedJsonWithoutFailingTheRequest() throws Exception {
        MockRequest request = parse("POST", "/callback", "application/json",
                "{not-valid-json", noHeaders());

        assertEquals("{not-valid-json", request.getRawBody());
        assertNull(request.getJsonRoot());
    }

    @Test
    void rejectsJsonWithTrailingNonWhitespaceContent() throws Exception {
        MockRequest request = parse("POST", "/callback", "application/json",
                "{\"status\":\"paid\"} trailing", noHeaders());

        assertNull(request.getJsonRoot());
        assertEquals(Collections.emptyList(), request.values(MatchSource.JSON, "status"));
    }

    @Test
    void truncatesBodiesAtOneMiBAndCompletesTheLocalRequest() throws Exception {
        byte[] body = new byte[ONE_MEBIBYTE + 17];
        Arrays.fill(body, (byte) 'x');

        MockRequest request = parseBytes("POST", "/callback", "application/json",
                body, noHeaders());

        assertTrue(request.isBodyTruncated());
        assertEquals(ONE_MEBIBYTE,
                request.getRawBody().getBytes(StandardCharsets.UTF_8).length);
        assertNull(request.getJsonRoot());
        assertEquals(Collections.emptyList(), request.values(MatchSource.JSON, "value"));
    }

    private MockRequest parse(String method, String target, String contentType,
                              String body, Map<String, String> headers) throws Exception {
        return parseBytes(method, target, contentType,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), headers);
    }

    private MockRequest parseBytes(String method, String target, String contentType,
                                   byte[] body, Map<String, String> headers) throws Exception {
        final AtomicReference<MockRequest> parsed = new AtomicReference<MockRequest>();
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    parsed.set(parser.parse(exchange));
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1);
                } catch (Throwable error) {
                    failure.set(error);
                    exchange.sendResponseHeaders(HttpURLConnection.HTTP_INTERNAL_ERROR, -1);
                } finally {
                    exchange.close();
                }
            }
        });
        server.start();
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://127.0.0.1:"
                    + server.getAddress().getPort() + target).openConnection();
            connection.setRequestMethod(method);
            if (contentType != null) {
                connection.setRequestProperty("Content-Type", contentType);
            }
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                OutputStream output = connection.getOutputStream();
                try {
                    output.write(body);
                } finally {
                    output.close();
                }
            }
            assertEquals(HttpURLConnection.HTTP_NO_CONTENT, connection.getResponseCode());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            server.stop(0);
        }
        assertNull(failure.get(), "Parser failed: " + failure.get());
        assertNotNull(parsed.get());
        return parsed.get();
    }

    private Map<String, String> noHeaders() {
        return Collections.emptyMap();
    }
}
