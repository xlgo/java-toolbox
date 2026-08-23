package com.aqishi.toolbox.feature.network.domain.callbackmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockTemplateRendererTest {
    @Test
    void rendersNestedJsonHeaderQueryAndFormValuesOnce() throws Exception {
        MockRequest request = MockRequest.builder()
                .query("orderId", "Q-7")
                .header("X-Request-Id", "trace-9")
                .form("status", "paid")
                .json(new ObjectMapper().readTree("{\"order\":{\"id\":42}}"))
                .build();

        String rendered = new MockTemplateRenderer().render(
                "${query.orderId}|${header.X-Request-Id}|${form.status}|${json.order.id}",
                request);

        assertEquals("Q-7|trace-9|paid|42", rendered);
    }

    @Test
    void rendersJsonObjectsAndArraysAsCompactJsonAndPreservesMissingVariables() throws Exception {
        MockRequest request = MockRequest.builder()
                .json(new ObjectMapper().readTree("{\"obj\":{\"b\":2,\"a\":1},\"items\":[\"x\",2]}"))
                .build();

        String rendered = new MockTemplateRenderer().render(
                "${json.obj}|${json.items}|${json.missing}", request);

        assertEquals("{\"b\":2,\"a\":1}|[\"x\",2]|${json.missing}", rendered);
    }

    @Test
    void doesNotRenderReplacementTextRecursively() {
        MockRequest request = MockRequest.builder().query("value", "${query.other}").build();

        assertEquals("${query.other}", new MockTemplateRenderer().render("${query.value}", request));
    }

    @Test
    void leavesUnknownNamespacesAndMalformedVariablesUntouched() {
        MockRequest request = MockRequest.builder().query("x", "ok").build();

        assertEquals("${unknown.x}|ok|${query}",
                new MockTemplateRenderer().render("${unknown.x}|${query.x}|${query}", request));
    }
}
