package com.aqishi.toolbox.feature.network.domain.callbackmock;

import java.util.Objects;

/**
 * Immutable response definition.  The body is intentionally kept as a string:
 * it may be a JSON document, plain text, or a template containing request
 * variables.
 */
public final class MockResponse {
    private final int statusCode;
    private final String contentType;
    private final String body;

    public MockResponse(int statusCode, String contentType, String body) {
        this.statusCode = statusCode;
        this.contentType = contentType;
        this.body = body == null ? "" : body;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getContentType() {
        return contentType;
    }

    public String getBody() {
        return body;
    }

    // These aliases keep the value object convenient for callers that use a
    // property-style name rather than the JavaBean-style getter.
    public int statusCode() {
        return statusCode;
    }

    public String contentType() {
        return contentType;
    }

    public String body() {
        return body;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof MockResponse)) {
            return false;
        }
        MockResponse that = (MockResponse) other;
        return statusCode == that.statusCode
                && Objects.equals(contentType, that.contentType)
                && Objects.equals(body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(statusCode, contentType, body);
    }

    @Override
    public String toString() {
        return "MockResponse{" +
                "statusCode=" + statusCode +
                ", contentType='" + contentType + '\'' +
                ", bodyLength=" + body.length() +
                '}';
    }
}
