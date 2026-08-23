package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRequest;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResolution;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable event published after one callback-mock request has been handled. */
public final class MockRequestRecord {
    private final Instant receivedAt;
    private final String method;
    private final String path;
    private final String query;
    private final Map<String, List<String>> headers;
    private final String body;
    private final boolean bodyTruncated;
    private final String clientAddress;
    private final String ruleId;
    private final String ruleName;
    private final boolean fallback;
    private final int responseStatus;
    private final String responseContentType;
    private final String responseBody;

    public MockRequestRecord(Instant receivedAt, MockRequest request,
                             MockResolution resolution, MockResponse response) {
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(response, "response");
        this.method = request.getMethod();
        this.path = request.getPath();
        this.query = request.getRawQuery();
        this.headers = copyValues(request.getHeaders());
        this.body = request.getRawBody();
        this.bodyTruncated = request.isBodyTruncated();
        this.clientAddress = request.getClientAddress();
        this.ruleId = resolution.getRuleId();
        this.ruleName = resolution.getRuleName();
        this.fallback = resolution.isFallback();
        this.responseStatus = response.getStatusCode();
        this.responseContentType = response.getContentType();
        this.responseBody = response.getBody();
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getQuery() {
        return query;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public boolean isBodyTruncated() {
        return bodyTruncated;
    }

    public String getClientAddress() {
        return clientAddress;
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

    public int getResponseStatus() {
        return responseStatus;
    }

    public String getResponseContentType() {
        return responseContentType;
    }

    public String getResponseBody() {
        return responseBody;
    }

    private static Map<String, List<String>> copyValues(
            Map<String, List<String>> source) {
        Map<String, List<String>> copied =
                new LinkedHashMap<String, List<String>>();
        if (source != null) {
            for (Map.Entry<String, List<String>> entry : source.entrySet()) {
                List<String> values = entry.getValue() == null
                        ? Collections.<String>emptyList()
                        : new ArrayList<String>(entry.getValue());
                copied.put(entry.getKey(), Collections.unmodifiableList(values));
            }
        }
        return Collections.unmodifiableMap(copied);
    }
}
