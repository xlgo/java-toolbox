package com.aqishi.toolbox.feature.network.domain;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
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
}
