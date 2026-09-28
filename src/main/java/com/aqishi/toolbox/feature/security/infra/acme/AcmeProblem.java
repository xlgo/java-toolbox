package com.aqishi.toolbox.feature.security.infra.acme;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An RFC 7807 problem document as returned by an ACME server (RFC 8555 section 6.7).
 *
 * <p>Carries the raw {@code type}, {@code detail}, HTTP {@code status}, an optional
 * {@code identifier} (for subproblems) and nested {@code subproblems}. The UI maps
 * {@link #shortType()} to a localized message; {@link #describe()} is the technical,
 * language-neutral text that always contains the server-provided detail.</p>
 */
public final class AcmeProblem {

    public static final String ACME_PREFIX = "urn:ietf:params:acme:error:";

    private final String type;
    private final String detail;
    private final int status;
    private final String identifier;
    private final List<AcmeProblem> subproblems;

    public AcmeProblem(String type, String detail, int status, String identifier, List<AcmeProblem> subproblems) {
        this.type = type == null ? "" : type;
        this.detail = detail == null ? "" : detail;
        this.status = status;
        this.identifier = identifier;
        this.subproblems = subproblems == null ? Collections.emptyList() : Collections.unmodifiableList(subproblems);
    }

    /** Parses a problem document; returns {@code null} when the node does not look like one. */
    public static AcmeProblem parse(JsonNode node) {
        if (node == null || !node.isObject() || (!node.has("type") && !node.has("detail"))) {
            return null;
        }
        List<AcmeProblem> subs = new ArrayList<>();
        JsonNode subNodes = node.get("subproblems");
        if (subNodes != null && subNodes.isArray()) {
            for (JsonNode sub : subNodes) {
                AcmeProblem parsed = parse(sub);
                if (parsed != null) {
                    subs.add(parsed);
                }
            }
        }
        String identifier = null;
        JsonNode id = node.get("identifier");
        if (id != null && id.isObject() && id.hasNonNull("value")) {
            identifier = id.get("value").asText();
        }
        return new AcmeProblem(text(node, "type"), text(node, "detail"),
                node.path("status").asInt(0), identifier, subs);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText();
    }

    public String type() {
        return type;
    }

    public String detail() {
        return detail;
    }

    public int status() {
        return status;
    }

    public String identifier() {
        return identifier;
    }

    public List<AcmeProblem> subproblems() {
        return subproblems;
    }

    /** {@code true} for {@code urn:ietf:params:acme:error:*} types. */
    public boolean isAcmeError() {
        return type.startsWith(ACME_PREFIX);
    }

    /** The ACME error name without its URN prefix (e.g. {@code badNonce}); the full type otherwise. */
    public String shortType() {
        return isAcmeError() ? type.substring(ACME_PREFIX.length()) : type;
    }

    public boolean is(String acmeShortType) {
        return isAcmeError() && shortType().equals(acmeShortType);
    }

    /** Technical one-line description including every subproblem, e.g. for logs and exception messages. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (identifier != null && !identifier.isEmpty()) {
            sb.append(identifier).append(": ");
        }
        sb.append(type.isEmpty() ? "problem" : shortType());
        if (!detail.isEmpty()) {
            sb.append(" - ").append(detail);
        }
        for (AcmeProblem sub : subproblems) {
            sb.append("; ").append(sub.describe());
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return describe();
    }
}
