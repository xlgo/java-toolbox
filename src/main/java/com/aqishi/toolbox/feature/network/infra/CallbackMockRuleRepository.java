package com.aqishi.toolbox.feature.network.infra;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchOperator;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MatchSource;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockCondition;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleSet;
import com.aqishi.toolbox.feature.network.domain.callbackmock.PathMatchMode;
import com.aqishi.toolbox.vault.AtomicFiles;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Stores callback-mock rules as a versioned JSON file. */
public final class CallbackMockRuleRepository {
    private static final String DEFAULT_CONTENT_TYPE = "application/json";
    private static final String DEFAULT_BODY = "{\n"
            + "  \"status\": \"success\",\n"
            + "  \"message\": \"Callback received\"\n"
            + "}";

    private final Path file;
    private final AtomicFiles atomicFiles;
    private final ObjectMapper mapper;

    public CallbackMockRuleRepository(Path file, AtomicFiles atomicFiles,
                                      ObjectMapper mapper) {
        this.file = Objects.requireNonNull(file, "file");
        this.atomicFiles = Objects.requireNonNull(atomicFiles, "atomicFiles");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Loads the persisted snapshot without ever modifying its source file.
     * Missing, corrupt, or unsupported files return the built-in fallback
     * snapshot; callers can surface the non-null warning to the user.
     */
    public LoadResult load() {
        try {
            if (!Files.exists(file)) {
                return new LoadResult(defaultRuleSet(), null);
            }

            JsonNode root = mapper.readTree(Files.readAllBytes(file));
            int version = requiredInt(root, "version");
            if (version != MockRuleSet.CURRENT_VERSION) {
                return new LoadResult(defaultRuleSet(),
                        "Unsupported callback mock rule version " + version
                                + "; built-in defaults are in use.");
            }
            return new LoadResult(readRuleSet(root), null);
        } catch (Exception error) {
            return new LoadResult(defaultRuleSet(),
                    "Unable to load callback mock rules; built-in defaults are in use.");
        }
    }

    /** Serializes version 1 rules in order and installs them atomically. */
    public void save(MockRuleSet ruleSet) throws IOException {
        Objects.requireNonNull(ruleSet, "ruleSet");
        byte[] bytes = mapper.writeValueAsBytes(writeRuleSet(ruleSet));
        atomicFiles.write(file, bytes);
    }

    private ObjectNode writeRuleSet(MockRuleSet ruleSet) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("version", MockRuleSet.CURRENT_VERSION);
        ArrayNode rules = root.putArray("rules");
        for (MockRule rule : ruleSet.getRules()) {
            rules.add(writeRule(rule));
        }
        root.set("fallbackResponse", writeResponse(
                ruleSet.getFallbackResponse(), "fallbackResponse"));
        return root;
    }

    private ObjectNode writeRule(MockRule rule) throws IOException {
        if (rule == null) {
            throw new IOException("Cannot save a null callback mock rule");
        }
        ObjectNode node = mapper.createObjectNode();
        requiredValue(rule.getId(), "rule.id");
        requiredValue(rule.getName(), "rule.name");
        requiredValue(rule.getMethod(), "rule.method");
        if (rule.getPathMode() == null) {
            throw new IOException("Cannot save a callback mock rule without pathMode");
        }
        requiredValue(rule.getPath(), "rule.path");
        node.put("id", rule.getId());
        node.put("name", rule.getName());
        node.put("enabled", rule.isEnabled());
        node.put("method", rule.getMethod());
        node.put("pathMode", rule.getPathMode().name());
        node.put("path", rule.getPath());

        ArrayNode conditions = node.putArray("conditions");
        for (MockCondition condition : rule.getConditions()) {
            conditions.add(writeCondition(condition));
        }
        node.set("response", writeResponse(rule.getResponse(), "rule.response"));
        return node;
    }

    private ObjectNode writeCondition(MockCondition condition) throws IOException {
        if (condition == null || condition.getSource() == null
                || condition.getOperator() == null) {
            throw new IOException("Cannot save an incomplete callback mock condition");
        }
        requiredValue(condition.getField(), "condition.field");
        ObjectNode node = mapper.createObjectNode();
        node.put("source", condition.getSource().name());
        node.put("field", condition.getField());
        node.put("operator", condition.getOperator().name());
        if (condition.getExpected() == null) {
            node.putNull("expected");
        } else {
            node.put("expected", condition.getExpected());
        }
        return node;
    }

    private ObjectNode writeResponse(MockResponse response, String name)
            throws IOException {
        if (response == null) {
            throw new IOException("Cannot save a callback mock response without " + name);
        }
        requiredValue(response.getContentType(), name + ".contentType");
        ObjectNode node = mapper.createObjectNode();
        node.put("statusCode", response.getStatusCode());
        node.put("contentType", response.getContentType());
        node.put("body", response.getBody());
        return node;
    }

    private MockRuleSet readRuleSet(JsonNode root) throws IOException {
        requireObject(root, "root");
        JsonNode rulesNode = requiredNode(root, "rules");
        if (!rulesNode.isArray()) {
            throw invalid("rules must be an array");
        }
        List<MockRule> rules = new ArrayList<MockRule>();
        for (JsonNode ruleNode : rulesNode) {
            rules.add(readRule(ruleNode));
        }
        return MockRuleSet.of(rules,
                readResponse(requiredNode(root, "fallbackResponse"), "fallbackResponse"));
    }

    private MockRule readRule(JsonNode node) throws IOException {
        requireObject(node, "rule");
        MockRule.Builder builder = MockRule.builder(requiredText(node, "name"))
                .id(requiredText(node, "id"))
                .enabled(requiredBoolean(node, "enabled"))
                .method(requiredText(node, "method"))
                .path(requiredEnum(node, "pathMode", PathMatchMode.class),
                        requiredText(node, "path"));

        JsonNode conditions = requiredNode(node, "conditions");
        if (!conditions.isArray()) {
            throw invalid("rule.conditions must be an array");
        }
        for (JsonNode condition : conditions) {
            builder.condition(readCondition(condition));
        }
        builder.response(readResponse(requiredNode(node, "response"), "rule.response"));
        return builder.build();
    }

    private MockCondition readCondition(JsonNode node) throws IOException {
        requireObject(node, "condition");
        JsonNode expected = requiredNode(node, "expected");
        if (!expected.isNull() && !expected.isTextual()) {
            throw invalid("condition.expected must be a string or null");
        }
        return new MockCondition(
                requiredEnum(node, "source", MatchSource.class),
                requiredText(node, "field"),
                requiredEnum(node, "operator", MatchOperator.class),
                expected.isNull() ? null : expected.asText());
    }

    private MockResponse readResponse(JsonNode node, String name) throws IOException {
        requireObject(node, name);
        return new MockResponse(requiredInt(node, "statusCode"),
                requiredText(node, "contentType"), requiredText(node, "body"));
    }

    private static JsonNode requiredNode(JsonNode object, String name)
            throws IOException {
        requireObject(object, "parent of " + name);
        JsonNode node = object.get(name);
        if (node == null) {
            throw invalid(name + " is required");
        }
        return node;
    }

    private static String requiredText(JsonNode object, String name)
            throws IOException {
        JsonNode node = requiredNode(object, name);
        if (!node.isTextual()) {
            throw invalid(name + " must be a string");
        }
        return node.asText();
    }

    private static int requiredInt(JsonNode object, String name) throws IOException {
        JsonNode node = requiredNode(object, name);
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(name + " must be an integer");
        }
        return node.intValue();
    }

    private static boolean requiredBoolean(JsonNode object, String name)
            throws IOException {
        JsonNode node = requiredNode(object, name);
        if (!node.isBoolean()) {
            throw invalid(name + " must be a boolean");
        }
        return node.booleanValue();
    }

    private static <T extends Enum<T>> T requiredEnum(JsonNode object,
                                                        String name,
                                                        Class<T> type)
            throws IOException {
        String value = requiredText(object, name);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException error) {
            throw invalid(name + " has an unsupported value");
        }
    }

    private static void requireObject(JsonNode node, String name) throws IOException {
        if (node == null || !node.isObject()) {
            throw invalid(name + " must be an object");
        }
    }

    private static void requiredValue(String value, String name) throws IOException {
        if (value == null) {
            throw new IOException("Cannot save callback mock rules: " + name
                    + " must not be null");
        }
    }

    private static IOException invalid(String message) {
        return new IOException("Invalid callback mock rule file: " + message);
    }

    private static MockRuleSet defaultRuleSet() {
        return MockRuleSet.of(new ArrayList<MockRule>(),
                new MockResponse(200, DEFAULT_CONTENT_TYPE, DEFAULT_BODY));
    }

    /** Snapshot plus a non-fatal warning when persisted data could not be used. */
    public static final class LoadResult {
        private final MockRuleSet ruleSet;
        private final String warning;

        private LoadResult(MockRuleSet ruleSet, String warning) {
            this.ruleSet = ruleSet;
            this.warning = warning;
        }

        public MockRuleSet getRuleSet() {
            return ruleSet;
        }

        public String getWarning() {
            return warning;
        }
    }
}
