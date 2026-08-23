package com.aqishi.toolbox.feature.network.domain.callbackmock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, ordered-rule entry.  Builders deliberately do not perform full
 * validation; validation is a separate domain concern so an editor can report
 * all field errors at once.
 */
public final class MockRule {
    private final String id;
    private final String name;
    private final boolean enabled;
    private final String method;
    private final PathMatchMode pathMode;
    private final String path;
    private final List<MockCondition> conditions;
    private final MockResponse response;

    private MockRule(Builder builder) {
        this.id = builder.id == null ? UUID.randomUUID().toString() : builder.id;
        this.name = builder.name;
        this.enabled = builder.enabled;
        this.method = builder.method;
        this.pathMode = builder.pathMode;
        this.path = builder.path;
        this.conditions = Collections.unmodifiableList(
                new ArrayList<MockCondition>(builder.conditions));
        this.response = builder.response;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public Builder toBuilder() {
        Builder builder = new Builder(name);
        builder.id = id;
        builder.enabled = enabled;
        builder.method = method;
        builder.pathMode = pathMode;
        builder.path = path;
        builder.conditions.addAll(conditions);
        builder.response = response;
        return builder;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getMethod() {
        return method;
    }

    public PathMatchMode getPathMode() {
        return pathMode;
    }

    public PathMatchMode getPathMatchMode() {
        return pathMode;
    }

    public String getPath() {
        return path;
    }

    public List<MockCondition> getConditions() {
        return conditions;
    }

    public MockResponse getResponse() {
        return response;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MockRule)) {
            return false;
        }
        MockRule that = (MockRule) other;
        return enabled == that.enabled
                && Objects.equals(id, that.id)
                && Objects.equals(name, that.name)
                && Objects.equals(method, that.method)
                && pathMode == that.pathMode
                && Objects.equals(path, that.path)
                && Objects.equals(conditions, that.conditions)
                && Objects.equals(response, that.response);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, enabled, method, pathMode, path,
                conditions, response);
    }

    @Override
    public String toString() {
        return "MockRule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", enabled=" + enabled +
                ", method='" + method + '\'' +
                ", pathMode=" + pathMode +
                ", path='" + path + '\'' +
                ", conditions=" + conditions.size() +
                ", response=" + response +
                '}';
    }

    public static final class Builder {
        private String id;
        private final String name;
        private boolean enabled = true;
        private String method = "ANY";
        private PathMatchMode pathMode = PathMatchMode.EXACT;
        private String path = "/";
        private final List<MockCondition> conditions = new ArrayList<MockCondition>();
        private MockResponse response;

        private Builder(String name) {
            this.name = name;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(PathMatchMode mode, String path) {
            this.pathMode = mode;
            this.path = path;
            return this;
        }

        public Builder condition(MockCondition condition) {
            this.conditions.add(condition);
            return this;
        }

        public Builder conditions(List<MockCondition> conditions) {
            this.conditions.clear();
            if (conditions != null) {
                this.conditions.addAll(conditions);
            }
            return this;
        }

        public Builder response(MockResponse response) {
            this.response = response;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public MockRule build() {
            return new MockRule(this);
        }
    }
}
