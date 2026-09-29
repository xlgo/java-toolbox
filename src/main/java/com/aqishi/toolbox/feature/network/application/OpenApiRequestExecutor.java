package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.util.I18n;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Executes OpenAPI requests, including PATCH, with cancellation and bounded response buffering. */
public final class OpenApiRequestExecutor {
    public record Response(int status, String body, Map<String, List<String>> headers) { }

    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final class ClientHolder {
        // Reuse connections. Keep redirects visible so credentials are not forwarded to another origin.
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Response execute(String url, String method, Map<String, String> headers, String body)
            throws IOException, InterruptedException {
        return execute(url, method, headers, body, Duration.ofSeconds(15));
    }

    Response execute(String url, String method, Map<String, String> headers, String body, Duration timeout)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        headers.forEach(builder::header);
        var publisher = body == null || body.isEmpty() || "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        var request = builder.method(method, publisher).build();
        var future = ClientHolder.CLIENT.sendAsync(request, info -> new LimitedBody(
                HttpResponse.BodyHandlers.ofString().apply(info)));
        try {
            // Also bounds body consumption: servers that send headers then stall cannot occupy a worker forever.
            var response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.body(), response.headers().map());
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        } catch (TimeoutException timedOut) {
            future.cancel(true);
            throw new java.net.http.HttpTimeoutException(I18n.get("tool.openapi.error.timeout"));
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IOException(failed.getCause());
        }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate;
        private Flow.Subscription subscription;
        private long received;
        private boolean done;

        private LimitedBody(HttpResponse.BodySubscriber<String> delegate) { this.delegate = delegate; }
        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            delegate.onSubscribe(value);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) received += buffer.remaining();
            if (received > MAX_RESPONSE_BYTES) {
                done = true;
                subscription.cancel();
                delegate.onError(new IOException(I18n.get("tool.openapi.error.responseTooLarge")));
            } else {
                delegate.onNext(buffers);
            }
        }
        @Override public void onError(Throwable error) {
            if (!done) { done = true; delegate.onError(error); }
        }
        @Override public void onComplete() {
            if (!done) { done = true; delegate.onComplete(); }
        }
    }
}
