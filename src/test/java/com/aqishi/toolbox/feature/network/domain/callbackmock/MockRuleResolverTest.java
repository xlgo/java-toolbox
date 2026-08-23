package com.aqishi.toolbox.feature.network.domain.callbackmock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockRuleResolverTest {
    @Test
    void resolvesFirstEnabledRuleWhenMethodPathAndAllConditionsMatch() throws Exception {
        MockResponse paid = new MockResponse(201, "application/json", "{\"result\":\"paid\"}");
        MockRule rule = MockRule.builder("paid-order")
                .method("POST")
                .path(PathMatchMode.PREFIX, "/orders")
                .condition(new MockCondition(MatchSource.JSON, "status",
                        MatchOperator.EQUALS, "paid"))
                .condition(new MockCondition(MatchSource.HEADER, "X-Channel",
                        MatchOperator.EXISTS, null))
                .response(paid)
                .build();
        MockRequest request = MockRequest.builder()
                .method("post")
                .path("/orders/42")
                .header("X-Channel", "mobile")
                .json(new ObjectMapper().readTree("{\"status\":\"paid\"}"))
                .build();

        MockResolution resolution = new MockRuleResolver().resolve(
                MockRuleSet.of(Collections.singletonList(rule),
                        new MockResponse(200, "application/json", "fallback")),
                request);

        assertEquals("paid-order", resolution.getRuleName());
        assertFalse(resolution.isFallback());
        assertEquals(paid, resolution.getResponse());
    }

    @Test
    void skipsDisabledRuleAndUsesAnyMethodAndExactPath() {
        MockRule disabled = MockRule.builder("disabled")
                .enabled(false)
                .method("GET")
                .path(PathMatchMode.EXACT, "/health")
                .response(new MockResponse(201, "text/plain", "disabled"))
                .build();
        MockRule any = MockRule.builder("any")
                .method("ANY")
                .path(PathMatchMode.EXACT, "/health")
                .response(new MockResponse(202, "text/plain", "any"))
                .build();

        MockResolution resolution = new MockRuleResolver().resolve(
                MockRuleSet.of(Arrays.asList(disabled, any),
                        new MockResponse(500, "text/plain", "fallback")),
                MockRequest.builder().method("pOsT").path("/health").build());

        assertEquals("any", resolution.getRuleName());
        assertEquals(202, resolution.getResponse().getStatusCode());
    }

    @Test
    void supportsPrefixAndRegexPaths() {
        MockRule prefix = MockRule.builder("prefix")
                .method("GET").path(PathMatchMode.PREFIX, "/orders")
                .response(new MockResponse(201, "text/plain", "prefix")).build();
        MockRule regex = MockRule.builder("regex")
                .method("GET").path(PathMatchMode.REGEX, "/users/[0-9]+")
                .response(new MockResponse(202, "text/plain", "regex")).build();

        MockRuleResolver resolver = new MockRuleResolver();
        assertEquals("prefix", resolver.resolve(MockRuleSet.of(
                Collections.singletonList(prefix), new MockResponse(500, "text/plain", "x")),
                MockRequest.builder().method("GET").path("/orders/1").build()).getRuleName());
        assertEquals("regex", resolver.resolve(MockRuleSet.of(
                Collections.singletonList(regex), new MockResponse(500, "text/plain", "x")),
                MockRequest.builder().method("GET").path("/users/42").build()).getRuleName());
    }

    @Test
    void requiresAllConditionsAndMatchesRepeatedValuesFromEverySource() throws Exception {
        MockRule rule = MockRule.builder("all")
                .method("POST").path(PathMatchMode.PREFIX, "/callback")
                .condition(new MockCondition(MatchSource.QUERY, "id", MatchOperator.EQUALS, "two"))
                .condition(new MockCondition(MatchSource.HEADER, "X-Test", MatchOperator.CONTAINS, "es"))
                .condition(new MockCondition(MatchSource.FORM, "status", MatchOperator.REGEX, "p.*"))
                .condition(new MockCondition(MatchSource.JSON, "order.id", MatchOperator.EQUALS, "42"))
                .response(new MockResponse(204, "text/plain", "matched")).build();
        MockRequest request = MockRequest.builder().method("POST").path("/callback")
                .query("id", "one").query("id", "two")
                .header("X-Test", "yes").form("status", "paid")
                .json(new ObjectMapper().readTree("{\"order\":{\"id\":42}}"))
                .build();

        MockResolution resolution = new MockRuleResolver().resolve(MockRuleSet.of(
                Collections.singletonList(rule), new MockResponse(500, "text/plain", "fallback")), request);

        assertFalse(resolution.isFallback());
        assertEquals("all", resolution.getRuleName());
    }

    @Test
    void missingValuesMalformedJsonAndNonMatchingConditionsFallBack() {
        MockRule rule = MockRule.builder("needs-json")
                .method("POST").path(PathMatchMode.EXACT, "/callback")
                .condition(new MockCondition(MatchSource.JSON, "status", MatchOperator.EXISTS, null))
                .response(new MockResponse(201, "text/plain", "matched")).build();

        MockResolution missing = new MockRuleResolver().resolve(MockRuleSet.of(
                Collections.singletonList(rule), new MockResponse(200, "text/plain", "fallback")),
                MockRequest.builder().method("POST").path("/callback").build());
        MockResolution malformed = new MockRuleResolver().resolve(MockRuleSet.of(
                Collections.singletonList(rule), new MockResponse(200, "text/plain", "fallback")),
                MockRequest.builder().method("POST").path("/callback").body("{bad", false).build());

        assertTrue(missing.isFallback());
        assertTrue(malformed.isFallback());
        assertEquals("fallback", malformed.getResponse().getBody());
    }

    @Test
    void evaluatesExistsAndRegexOperators() {
        MockRule rule = MockRule.builder("operators")
                .method("ANY").path(PathMatchMode.EXACT, "/x")
                .condition(new MockCondition(MatchSource.QUERY, "a", MatchOperator.EXISTS, null))
                .condition(new MockCondition(MatchSource.QUERY, "b", MatchOperator.REGEX, "v[0-9]+"))
                .response(new MockResponse(200, "text/plain", "ok")).build();
        MockRequest request = MockRequest.builder().path("/x")
                .query("a", "present").query("b", "nope").query("b", "v7").build();

        MockResolution resolution = new MockRuleResolver().resolve(MockRuleSet.of(
                Collections.singletonList(rule), new MockResponse(500, "text/plain", "fallback")), request);

        assertEquals("operators", resolution.getRuleName());
    }
}
