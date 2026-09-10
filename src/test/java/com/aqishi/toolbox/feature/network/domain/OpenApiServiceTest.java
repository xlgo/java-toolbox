package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiServiceTest {

    private final OpenApiService service = new OpenApiService();

    @Test
    void parsesSampleSpecSuccessfully() throws Exception {
        OpenApiSpec spec = service.parse(OpenApiService.SAMPLE_SPEC);
        assertNotNull(spec);
        assertTrue(spec.getTitle().contains("Swagger Petstore Sample"));
        assertEquals("1.0.0", spec.getVersion());
        assertEquals(1, spec.getServers().size());
        assertEquals("https://httpbin.org", spec.getServers().get(0));

        assertFalse(spec.getEndpoints().isEmpty());
        OpenApiSpec.ApiEndpoint getEp = spec.getEndpoints().stream()
                .filter(e -> "GET".equals(e.getMethod()) && "/get".equals(e.getPath()))
                .findFirst()
                .orElse(null);
        assertNotNull(getEp);
        assertEquals("基础查询", getEp.getTag());
        assertEquals(2, getEp.getParameters().size());

        OpenApiSpec.ApiEndpoint postEp = spec.getEndpoints().stream()
                .filter(e -> "POST".equals(e.getMethod()) && "/post".equals(e.getPath()))
                .findFirst()
                .orElse(null);
        assertNotNull(postEp);
        assertNotNull(postEp.getRequestBodyExample());
        assertTrue(postEp.getRequestBodyExample().contains("orderId"));
    }

    @Test
    void parsesSwagger2Json() throws Exception {
        String swagger2Json = "{\n" +
                "  \"swagger\": \"2.0\",\n" +
                "  \"info\": {\n" +
                "    \"title\": \"Swagger 2.0 API\",\n" +
                "    \"version\": \"2.0.0\"\n" +
                "  },\n" +
                "  \"host\": \"api.example.com\",\n" +
                "  \"basePath\": \"/v1\",\n" +
                "  \"schemes\": [\"https\"],\n" +
                "  \"paths\": {\n" +
                "    \"/users\": {\n" +
                "      \"get\": {\n" +
                "        \"summary\": \"List users\",\n" +
                "        \"responses\": {\n" +
                "          \"200\": {\"description\": \"OK\"}\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        OpenApiSpec spec = service.parse(swagger2Json);
        assertNotNull(spec);
        assertEquals("Swagger 2.0 API", spec.getTitle());
        assertEquals("https://api.example.com/v1", spec.getServers().get(0));
        assertEquals(1, spec.getEndpoints().size());
        assertEquals("GET", spec.getEndpoints().get(0).getMethod());
        assertEquals("/users", spec.getEndpoints().get(0).getPath());
    }

    @Test
    void buildsCurlCommandCorrectly() {
        OpenApiSpec.ApiEndpoint ep = new OpenApiSpec.ApiEndpoint();
        ep.setMethod("POST");
        ep.setPath("/orders/{id}");
        ep.setRequestContentType("application/json");

        Map<String, String> pathParams = Collections.singletonMap("id", "1001");
        Map<String, String> queryParams = Collections.singletonMap("notify", "true");
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer token123");

        String body = "{\"status\": \"paid\"}";
        String curl = service.buildCurl("https://api.test.com", ep, pathParams, queryParams, headers, body);

        assertNotNull(curl);
        assertTrue(curl.contains("curl -X POST"));
        assertTrue(curl.contains("https://api.test.com/orders/1001?notify=true"));
        assertTrue(curl.contains("-H \"Authorization: Bearer token123\""));
        assertTrue(curl.contains("-d \"{\\\"status\\\": \\\"paid\\\"}\""));
    }

    @Test
    void throwsOnEmptyContent() {
        assertThrows(IllegalArgumentException.class, () -> service.parse(""));
        assertThrows(IllegalArgumentException.class, () -> service.parse("   "));
    }

    @Test
    void encodesPathAndQueryInputsBeforeBuildingRequestUrl() {
        OpenApiSpec.ApiEndpoint ep = new OpenApiSpec.ApiEndpoint();
        ep.setPath("/orders/{id}");

        Map<String, String> pathParams = Collections.singletonMap("id", "summer sale/2026");
        Map<String, String> queryParams = new LinkedHashMap<>();
        queryParams.put("filter name", "books & magazines");
        queryParams.put("tag", "a+b/c?");

        String requestUrl = service.buildRequestUrl(
                "https://api.test.com/v1?tenant=team-a", ep.getPath(), pathParams, queryParams);

        assertEquals("https://api.test.com/v1/orders/summer%20sale%2F2026"
                        + "?tenant=team-a&filter%20name=books%20%26%20magazines&tag=a%2Bb%2Fc%3F",
                requestUrl);
    }

    @Test
    void escapesShellMetacharactersInCurlArguments() {
        OpenApiSpec.ApiEndpoint ep = new OpenApiSpec.ApiEndpoint();
        ep.setMethod("POST");
        ep.setPath("/orders");

        Map<String, String> headers = Collections.singletonMap("X-Note", "O'Reilly $HOME");
        String curl = service.buildCurl("https://api.test.com", ep,
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(),
                headers, "{\"note\":\"it's $safe\"}");

        assertTrue(curl.contains("-H \"X-Note: O'Reilly \\$HOME\""));
        assertTrue(curl.contains("-d \"{\\\"note\\\":\\\"it's \\$safe\\\"}\""));
    }

    @Test
    void preservesNewlinesInCurlRequestBodies() {
        OpenApiSpec.ApiEndpoint ep = new OpenApiSpec.ApiEndpoint();
        ep.setMethod("POST");
        ep.setPath("/orders");

        String curl = service.buildCurl("https://api.test.com", ep,
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(), "{\n  \"status\": \"paid\"\n}");

        assertTrue(curl.contains("{\n  \\\"status\\\": \\\"paid\\\"\n}"));
    }

    @Test
    void rejectsNonHttpSpecUrlsBeforeOpeningConnection() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> service.fetchRemoteSpec(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> service.fetchRemoteSpec("")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.fetchRemoteSpec("file:///tmp/api.yaml")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.fetchRemoteSpec("ftp://example.com/api.yaml")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.fetchRemoteSpec("httpx://example.com/api.yaml")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.fetchRemoteSpec("https:///api.yaml")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.fetchRemoteSpec("https://example.com:65536/api.yaml")),
                () -> assertThrows(IllegalArgumentException.class, () -> service.fetchRemoteSpec("https://example.com/api.yaml#part"))
        );
    }

    @Test
    void rejectsHeaderLineBreaksInGeneratedCurl() {
        OpenApiSpec.ApiEndpoint ep = new OpenApiSpec.ApiEndpoint();
        ep.setMethod("GET");
        ep.setPath("/orders");

        assertThrows(IllegalArgumentException.class, () -> service.buildCurl("https://api.test.com", ep,
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(),
                Collections.singletonMap("X-Test", "one\r\ntwo"), null));
    }

    @Test
    void disconnectsSpecConnectionsAfterSuccessfulAndFailedReads() throws Exception {
        TrackingOpenApiService successService = new TrackingOpenApiService(200, "openapi: 3.0.0");

        assertEquals("openapi: 3.0.0\n", successService.fetchRemoteSpec("https://example.test/openapi.yaml"));
        assertTrue(successService.wasDisconnected());

        TrackingOpenApiService failureService = new TrackingOpenApiService(500, null);
        assertThrows(IllegalStateException.class,
                () -> failureService.fetchRemoteSpec("https://example.test/openapi.yaml"));
        assertTrue(failureService.wasDisconnected());
    }

    private static final class TrackingOpenApiService extends OpenApiService {
        private final TrackingHttpURLConnection connection;

        private TrackingOpenApiService(int statusCode, String body) throws Exception {
            this.connection = new TrackingHttpURLConnection(statusCode, body);
        }

        @Override
        protected HttpURLConnection openConnection(URI uri) {
            return connection;
        }

        private boolean wasDisconnected() {
            return connection.disconnected;
        }
    }

    private static final class TrackingHttpURLConnection extends HttpURLConnection {
        private final int statusCode;
        private final byte[] body;
        private boolean disconnected;

        private TrackingHttpURLConnection(int statusCode, String body) throws Exception {
            super(new URL("http://example.test/openapi.yaml"));
            this.statusCode = statusCode;
            this.body = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void disconnect() {
            disconnected = true;
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
            // The test connection does not perform I/O.
        }

        @Override
        public int getResponseCode() {
            return statusCode;
        }

        @Override
        public InputStream getInputStream() {
            return body == null ? null : new ByteArrayInputStream(body);
        }

        @Override
        public InputStream getErrorStream() {
            return body == null ? null : new ByteArrayInputStream(body);
        }
    }
}
