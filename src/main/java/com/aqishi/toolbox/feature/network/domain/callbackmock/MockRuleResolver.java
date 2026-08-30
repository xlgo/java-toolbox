package com.aqishi.toolbox.feature.network.domain.callbackmock;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Resolves the first enabled rule whose method, path, and conditions match. */
public final class MockRuleResolver {
    /** Compiled-pattern cache keyed by regex source; avoids recompiling on every request. */
    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    /** Upper bound so user-edited rules cannot grow the cache without limit. */
    private static final int PATTERN_CACHE_MAX = 256;

    public MockResolution resolve(MockRuleSet ruleSet, MockRequest request) {
        if (ruleSet == null) {
            return MockResolution.fallback(null);
        }
        MockRequest safeRequest = request == null
                ? MockRequest.builder().build() : request;
        List<MockRule> rules = ruleSet.getRules();
        if (rules == null) {
            rules = Collections.emptyList();
        }
        for (MockRule rule : rules) {
            if (rule != null && rule.isEnabled() && matches(rule, safeRequest)) {
                return MockResolution.matched(rule);
            }
        }
        return MockResolution.fallback(ruleSet.getFallbackResponse());
    }

    private boolean matches(MockRule rule, MockRequest request) {
        if (!methodMatches(rule.getMethod(), request.getMethod())
                || !pathMatches(rule.getPathMode(), rule.getPath(), request.getPath())) {
            return false;
        }
        for (MockCondition condition : rule.getConditions()) {
            if (!conditionMatches(condition, request)) {
                return false;
            }
        }
        return true;
    }

    private boolean methodMatches(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        String expectedMethod = expected.trim();
        String actualMethod = actual.trim();
        return "ANY".equalsIgnoreCase(expectedMethod)
                || expectedMethod.equalsIgnoreCase(actualMethod);
    }

    private boolean pathMatches(PathMatchMode mode, String expected, String actual) {
        if (mode == null || expected == null || actual == null) {
            return false;
        }
        switch (mode) {
            case EXACT:
                return expected.equals(actual);
            case PREFIX:
                return actual.startsWith(expected);
            case REGEX:
                try {
                    return compiled(expected).matcher(actual).matches();
                } catch (PatternSyntaxException e) {
                    // Invalid rules are rejected by MockRuleValidator. Treat a
                    // stale/externally loaded invalid rule as a no-match so it
                    // cannot take down the callback server.
                    return false;
                }
            default:
                return false;
        }
    }

    private boolean conditionMatches(MockCondition condition, MockRequest request) {
        if (condition == null || condition.getSource() == null
                || condition.getOperator() == null || condition.getField() == null) {
            return false;
        }
        List<String> values = request.values(condition.getSource(), condition.getField());
        MatchOperator operator = condition.getOperator();
        if (operator == MatchOperator.EXISTS) {
            return !values.isEmpty();
        }
        String expected = condition.getExpected();
        if (expected == null || values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            switch (operator) {
                case EQUALS:
                    if (expected.equals(value)) {
                        return true;
                    }
                    break;
                case CONTAINS:
                    if (value.contains(expected)) {
                        return true;
                    }
                    break;
                case REGEX:
                    try {
                        if (compiled(expected).matcher(value).matches()) {
                            return true;
                        }
                    } catch (PatternSyntaxException e) {
                        return false;
                    }
                    break;
                case EXISTS:
                    return true;
                default:
                    break;
            }
        }
        return false;
    }

    /**
     * Returns a cached {@link Pattern} for the given regex source. Failed
     * compilations are not cached, so an invalid rule keeps failing fast
     * without occupying cache slots.
     */
    private static Pattern compiled(String regex) {
        if (PATTERN_CACHE.size() >= PATTERN_CACHE_MAX) {
            PATTERN_CACHE.clear();
        }
        return PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
    }
}
