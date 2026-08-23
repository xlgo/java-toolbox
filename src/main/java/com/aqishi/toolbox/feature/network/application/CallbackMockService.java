package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.callbackmock.MockResponse;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRule;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleResolver;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockRuleSet;
import com.aqishi.toolbox.feature.network.domain.callbackmock.MockTemplateRenderer;
import com.aqishi.toolbox.feature.network.infra.CallbackMockHttpHandler;
import com.aqishi.toolbox.feature.network.infra.MockHttpRequestParser;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns the loopback HTTP server and atomically publishes whole rule snapshots. */
public final class CallbackMockService implements ManagedResourceOwner {
    private final MockRuleResolver resolver;
    private final MockHttpRequestParser parser;
    private final AtomicReference<MockRuleSet> ruleSet =
            new AtomicReference<MockRuleSet>(emptyRuleSet());
    private final AtomicReference<Consumer<MockRequestRecord>> requestListener =
            new AtomicReference<Consumer<MockRequestRecord>>();

    private HttpServer server;

    public CallbackMockService(MockRuleResolver resolver,
                               MockHttpRequestParser parser) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.parser = Objects.requireNonNull(parser, "parser");
    }

    public synchronized void start(int port) throws IOException {
        if (server != null) {
            return;
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        HttpServer created = HttpServer.create(new InetSocketAddress(
                InetAddress.getByName("127.0.0.1"), port), 0);
        try {
            created.createContext("/", new CallbackMockHttpHandler(resolver, parser,
                    new MockTemplateRenderer(), ruleSet, this::publish));
            created.setExecutor(null);
            created.start();
            server = created;
        } catch (RuntimeException error) {
            created.stop(0);
            throw error;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized int getPort() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    public MockRuleSet getRuleSet() {
        return ruleSet.get();
    }

    public void replaceRuleSet(MockRuleSet newRuleSet) {
        ruleSet.set(Objects.requireNonNull(newRuleSet, "ruleSet"));
    }

    public void setRequestListener(Consumer<MockRequestRecord> listener) {
        requestListener.set(listener);
    }

    @Override
    public void closeResources() {
        stop();
    }

    private void publish(MockRequestRecord record) {
        Consumer<MockRequestRecord> listener = requestListener.get();
        if (listener != null) {
            listener.accept(record);
        }
    }

    private static MockRuleSet emptyRuleSet() {
        return MockRuleSet.of(Collections.<MockRule>emptyList(),
                new MockResponse(200, "application/json", ""));
    }
}
