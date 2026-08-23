package com.aqishi.toolbox.feature.network.domain.callbackmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockRuleValidatorTest {
    @Test
    void acceptsValidRuleAndFallback() {
        MockRule rule = MockRule.builder("paid")
                .method("POST")
                .path(PathMatchMode.PREFIX, "/orders")
                .condition(new MockCondition(MatchSource.JSON, "items[0].sku",
                        MatchOperator.EQUALS, "A-1"))
                .response(new MockResponse(201, "application/json", "{\"id\":\"${json.items[0].id}\"}"))
                .build();

        List<String> errors = new MockRuleValidator().validate(MockRuleSet.of(
                java.util.Collections.singletonList(rule),
                new MockResponse(200, "application/json", "{}")));

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void reportsInvalidRuleFieldsAndConditionOperatorRequirements() {
        MockRule rule = MockRule.builder(" ")
                .method("bad method")
                .path(PathMatchMode.EXACT, "orders")
                .condition(new MockCondition(null, "", MatchOperator.EXISTS, "unexpected"))
                .condition(new MockCondition(MatchSource.JSON, "items[-1]", MatchOperator.EQUALS, ""))
                .response(new MockResponse(99, " ", "${query.bad"))
                .build();

        List<String> errors = new MockRuleValidator().validate(rule);

        assertFalse(errors.isEmpty());
        assertContains(errors, "name");
        assertContains(errors, "method");
        assertContains(errors, "path");
        assertContains(errors, "statusCode");
        assertContains(errors, "contentType");
        assertContains(errors, "condition");
        assertContains(errors, "template");
    }

    @Test
    void rejectsMalformedRegexAndTemplateSyntaxAndInvalidFallback() {
        MockRule regexRule = MockRule.builder("regex")
                .method("GET")
                .path(PathMatchMode.REGEX, "[")
                .response(new MockResponse(200, "application/json", "${json.ok}"))
                .build();

        List<String> errors = new MockRuleValidator().validate(MockRuleSet.of(
                java.util.Collections.singletonList(regexRule),
                new MockResponse(600, null, "${query.x}")));

        assertContains(errors, "path");
        assertContains(errors, "fallback");
    }

    @Test
    void jsonPathSyntaxAllowsDottedAndIndexedSegmentsOnly() throws Exception {
        MockRule good = MockRule.builder("good")
                .path(PathMatchMode.EXACT, "/x")
                .condition(new MockCondition(MatchSource.JSON, "order.items[0].id",
                        MatchOperator.EXISTS, null))
                .response(new MockResponse(200, "application/json", "{}"))
                .build();
        MockRule bad = MockRule.builder("bad")
                .path(PathMatchMode.EXACT, "/x")
                .condition(new MockCondition(MatchSource.JSON, "order..id",
                        MatchOperator.EXISTS, null))
                .response(new MockResponse(200, "application/json", "{}"))
                .build();

        assertTrue(new MockRuleValidator().validate(good).isEmpty());
        assertFalse(new MockRuleValidator().validate(bad).isEmpty());
    }

    private static void assertContains(List<String> messages, String fragment) {
        assertTrue(messages.stream().anyMatch(message -> message.contains(fragment)),
                "Expected '" + fragment + "' in " + messages);
    }
}
