package com.aqishi.toolbox.feature.network.domain.callbackmock;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Performs one non-recursive pass over a response-body template. */
public final class MockTemplateRenderer {
    private static final Pattern VARIABLE =
            Pattern.compile("\\$\\{([a-zA-Z]+)\\.([^}]+)}");

    public String render(String template, MockRequest request) {
        if (template == null || request == null) {
            return template;
        }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            String replacement = lookup(matcher.group(1), matcher.group(2), request);
            if (replacement == null) {
                // appendReplacement with the original token leaves malformed
                // or missing variables visible to the caller.
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String lookup(String namespace, String field, MockRequest request) {
        MatchSource source;
        try {
            source = MatchSource.valueOf(namespace.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (source == MatchSource.JSON) {
            JsonNode node = request.jsonValue(field);
            return node == null ? null : jsonText(node);
        }
        List<String> values = request.values(source, field);
        return values.isEmpty() ? null : values.get(0);
    }

    private String jsonText(JsonNode node) {
        return node.isTextual() ? node.textValue() : node.toString();
    }
}
