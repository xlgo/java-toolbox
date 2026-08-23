package com.aqishi.toolbox.feature.network.domain.callbackmock;

import java.util.Objects;

/** Result of resolving one request against a rule snapshot. */
public final class MockResolution {
    private final MockResponse response;
    private final String ruleId;
    private final String ruleName;
    private final boolean fallback;

    public MockResolution(MockResponse response, String ruleId,
                          String ruleName, boolean fallback) {
        this.response = response;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.fallback = fallback;
    }

    public static MockResolution matched(MockRule rule) {
        if (rule == null) {
            return new MockResolution(null, null, null, false);
        }
        return new MockResolution(rule.getResponse(), rule.getId(),
                rule.getName(), false);
    }

    public static MockResolution fallback(MockResponse response) {
        return new MockResolution(response, null, null, true);
    }

    public MockResponse getResponse() {
        return response;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public boolean isFallback() {
        return fallback;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MockResolution)) {
            return false;
        }
        MockResolution that = (MockResolution) other;
        return fallback == that.fallback
                && Objects.equals(response, that.response)
                && Objects.equals(ruleId, that.ruleId)
                && Objects.equals(ruleName, that.ruleName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(response, ruleId, ruleName, fallback);
    }
}
