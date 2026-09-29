package com.aqishi.toolbox.feature.network.domain;

import com.aqishi.toolbox.util.ShellQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiRegressionTest {
    private final OpenApiService service = new OpenApiService();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void examplesRemainValidJsonWithEscapedKeysAndValues() throws Exception {
        var schema = mapper.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("a\"b").put("type", "string")
                .put("example", "line1\nline2\"\\");
        var root = mapper.createObjectNode();
        root.put("openapi", "3.0.3");
        root.putObject("paths").putObject("/test").putObject("post")
                .putObject("requestBody").putObject("content").putObject("application/json")
                .set("schema", schema);
        String example = service.parse(root.toString()).getEndpoints().get(0).getRequestBodyExample();
        assertEquals("line1\nline2\"\\", mapper.readTree(example).get("a\"b").asText());
    }

    @Test
    void recursiveArrayIsBoundedAndProducesValidJson() throws Exception {
        String spec = """
                {"openapi":"3.0.3","paths":{"/test":{"post":{"requestBody":{"content":{
                  "application/json":{"schema":{"$ref":"#/components/schemas/Loop"}}
                }}}}},"components":{"schemas":{"Loop":{"type":"array","items":{
                  "$ref":"#/components/schemas/Loop"
                }}}}}
                """;
        String example = service.parse(spec).getEndpoints().get(0).getRequestBodyExample();
        assertNotNull(mapper.readTree(example));
        assertTrue(example.length() < 1000);
    }

    @Test
    void operationParametersOverridePathParametersByNameAndLocation() throws Exception {
        var spec = service.parse("""
                openapi: 3.0.3
                paths:
                  /test:
                    parameters:
                      - {name: id, in: query, example: old}
                      - {name: id, in: header, example: header}
                    get:
                      parameters:
                        - {name: id, in: query, example: new}
                """);
        var params = spec.getEndpoints().get(0).getParameters();
        assertEquals(2, params.size());
        assertEquals("new", params.stream().filter(p -> p.getIn().equals("query")).findFirst().orElseThrow().getExample());
    }

    @Test
    void resolvesEscapedReferencesAndNestedObjects() throws Exception {
        var spec = service.parse("""
                openapi: 3.0.3
                components:
                  schemas:
                    'A/B':
                      type: object
                      properties:
                        nested:
                          type: object
                          properties:
                            count: {type: integer, default: 7}
                paths:
                  /test:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema: {$ref: '#/components/schemas/A~1B'}
                """);
        JsonNode example = mapper.readTree(spec.getEndpoints().get(0).getRequestBodyExample());
        assertEquals(7, example.path("nested").path("count").asInt());
    }

    @Test
    void curlArgumentsRoundTripWithoutChangingPayload() {
        var ep = new OpenApiSpec.ApiEndpoint();
        ep.setMethod("PATCH");
        ep.setPath("/test");
        ep.setRequestContentType("application/json");
        String body = "{\"message\":\"O'Reilly! $HOME `whoami`\"}";
        List<String> args = ShellQuote.split(service.buildCurl("https://example.test", ep,
                Map.of(), Map.of(), Map.of(), body));
        assertEquals(body, args.get(args.indexOf("--data-raw") + 1));
        assertTrue(args.contains("Content-Type: application/json"));
    }
}
