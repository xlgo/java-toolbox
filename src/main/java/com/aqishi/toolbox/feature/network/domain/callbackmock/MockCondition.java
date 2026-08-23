package com.aqishi.toolbox.feature.network.domain.callbackmock;

import java.util.Objects;

/** One request-value predicate belonging to a rule. */
public final class MockCondition {
    private final MatchSource source;
    private final String field;
    private final MatchOperator operator;
    private final String expected;

    public MockCondition(MatchSource source, String field,
                         MatchOperator operator, String expected) {
        this.source = source;
        this.field = field;
        this.operator = operator;
        this.expected = expected;
    }

    public MatchSource getSource() {
        return source;
    }

    public String getField() {
        return field;
    }

    public MatchOperator getOperator() {
        return operator;
    }

    public String getExpected() {
        return expected;
    }

    public String getExpectedValue() {
        return expected;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MockCondition)) {
            return false;
        }
        MockCondition that = (MockCondition) other;
        return source == that.source
                && Objects.equals(field, that.field)
                && operator == that.operator
                && Objects.equals(expected, that.expected);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, field, operator, expected);
    }

    @Override
    public String toString() {
        return "MockCondition{" +
                "source=" + source +
                ", field='" + field + '\'' +
                ", operator=" + operator +
                ", expected='" + expected + '\'' +
                '}';
    }
}
