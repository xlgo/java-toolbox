package com.aqishi.toolbox.feature.network.infra;

import com.aqishi.toolbox.feature.network.application.MockRequestRecord;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRequest;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResolution;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleResolver;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleSet;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockTemplateRenderer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Handles one exchange against the current immutable callback rule snapshot. */
public final class CallbackMockHttpHandler implements HttpHandler {
    private static final MockResponse EMPTY_RESPONSE =
            new MockResponse(200, "application/json", "");

    private final MockRuleResolver resolver;
    private final MockHttpRequestParser parser;
    private final MockTemplateRenderer renderer;
    private final AtomicReference<MockRuleSet> ruleSet;
    private final Consumer<MockRequestRecord> recordListener;

    public CallbackMockHttpHandler(MockRuleResolver resolver,
                                   MockHttpRequestParser parser,
                                   MockTemplateRenderer renderer,
                                   AtomicReference<MockRuleSet> ruleSet,
                                   Consumer<MockRequestRecord> recordListener) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.ruleSet = Objects.requireNonNull(ruleSet, "ruleSet");
        this.recordListener = Objects.requireNonNull(recordListener, "recordListener");
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            MockRequest request = parser.parse(exchange);
            MockRuleSet snapshot = ruleSet.get();
            MockResolution resolution = resolver.resolve(snapshot, request);
            MockResponse configured = resolution.getResponse();
            MockResponse response = configured == null
                    ? EMPTY_RESPONSE : new MockResponse(configured.getStatusCode(),
                    configured.getContentType(),
                    renderer.render(configured.getBody(), request));

            publish(new MockRequestRecord(Instant.now(), request, resolution, response));
            writeResponse(exchange, response);
        } finally {
            exchange.close();
        }
    }

    private void publish(MockRequestRecord record) {
        try {
            recordListener.accept(record);
        } catch (RuntimeException ignored) {
            // A UI observer must never prevent the callback response from being sent.
        }
    }

    private static void writeResponse(HttpExchange exchange, MockResponse response)
            throws IOException {
        byte[] bytes = response.getBody().getBytes(StandardCharsets.UTF_8);
        if (response.getContentType() != null && response.getContentType().trim().length() > 0) {
            exchange.getResponseHeaders().set("Content-Type", response.getContentType());
        }
        exchange.sendResponseHeaders(response.getStatusCode(), bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            if (bytes.length > 0) {
                output.write(bytes);
            }
        }
    }
}
