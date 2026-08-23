package com.aqishi.toolbox.feature.network.domain.callbackmock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable snapshot of ordered rules and the fallback response. */
public final class MockRuleSet {
    public static final int CURRENT_VERSION = 1;

    private final int version;
    private final List<MockRule> rules;
    private final MockResponse fallbackResponse;

    private MockRuleSet(int version, List<MockRule> rules,
                        MockResponse fallbackResponse) {
        this.version = version;
        this.rules = Collections.unmodifiableList(new ArrayList<MockRule>(
                rules == null ? Collections.<MockRule>emptyList() : rules));
        this.fallbackResponse = fallbackResponse;
    }

    public static MockRuleSet of(List<MockRule> rules,
                                 MockResponse fallbackResponse) {
        return new MockRuleSet(CURRENT_VERSION, rules, fallbackResponse);
    }

    public static MockRuleSet ofVersion(int version, List<MockRule> rules,
                                        MockResponse fallbackResponse) {
        return new MockRuleSet(version, rules, fallbackResponse);
    }

    public int getVersion() {
        return version;
    }

    public List<MockRule> getRules() {
        return rules;
    }

    public MockResponse getFallbackResponse() {
        return fallbackResponse;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MockRuleSet)) {
            return false;
        }
        MockRuleSet that = (MockRuleSet) other;
        return version == that.version
                && Objects.equals(rules, that.rules)
                && Objects.equals(fallbackResponse, that.fallbackResponse);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, rules, fallbackResponse);
    }
}
