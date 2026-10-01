package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.HttpBody;
import com.aqishi.toolbox.util.I18n;

import java.io.*;
import java.net.URLEncoder;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.function.LongConsumer;

/** Multipart bytes are streamed from files; user-supplied names cannot inject part headers. */
public final class HttpBodies {
    private HttpBodies() {}

    public record Prepared(BodyPublisher publisher, String contentType) {}

    public static Prepared prepare(String raw, HttpBody specification, LongConsumer progress)
            throws Exception {
        if (specification == null || specification.mode() == HttpBody.Mode.RAW)
            return new Prepared(
                    count(BodyPublishers.ofString(raw == null ? "" : raw), progress), null);
        if (specification.parts().size() > 100)
            throw new IllegalArgumentException(I18n.get("upload.limit"));
        if (specification.mode() == HttpBody.Mode.FORM) {
            StringJoiner form = new StringJoiner("&");
            for (var part : specification.parts()) {
                if (part.file()) throw new IllegalArgumentException(I18n.get("upload.formFile"));
                form.add(encode(part.name()) + "=" + encode(part.value()));
            }
            return new Prepared(
                    count(BodyPublishers.ofString(form.toString()), progress),
                    "application/x-www-form-urlencoded; charset=UTF-8");
        }
        String boundary = "toolbox-" + UUID.randomUUID();
        List<BodyPublisher> publishers = new ArrayList<>();
        FileStreams files = new FileStreams();
        for (var part : specification.parts()) {
            String name = headerValue(part.name());
            StringBuilder header =
                    new StringBuilder(
                                    "--" + boundary + "\r\nContent-Disposition: form-data; name=\"")
                            .append(name)
                            .append('"');
            BodyPublisher body;
            if (part.file()) {
                Path path = Path.of(part.value());
                if (!Files.isRegularFile(path) || !Files.isReadable(path))
                    throw new IOException(I18n.get("upload.fileUnavailable", path.getFileName()));
                if (Files.size(path) > 1024L * 1024 * 1024)
                    throw new IOException(I18n.get("upload.limit"));
                String filename = headerValue(path.getFileName().toString());
                header.append("; filename=\"")
                        .append(filename)
                        .append("\"\r\nContent-Type: application/octet-stream");
                body =
                        BodyPublishers.fromPublisher(
                                BodyPublishers.ofInputStream(() -> files.open(path)),
                                Files.size(path));
            } else body = BodyPublishers.ofString(part.value());
            header.append("\r\n\r\n");
            publishers.add(BodyPublishers.ofString(header.toString()));
            publishers.add(body);
            publishers.add(BodyPublishers.ofString("\r\n"));
        }
        publishers.add(BodyPublishers.ofString("--" + boundary + "--\r\n"));
        return new Prepared(
                new ManagedPublisher(
                        count(
                                BodyPublishers.concat(publishers.toArray(BodyPublisher[]::new)),
                                progress),
                        files),
                "multipart/form-data; boundary=" + boundary);
    }

    /**
     * Own file descriptors explicitly: cancellation of a concatenated JDK publisher can finish
     * asynchronously.
     */
    private static final class FileStreams {
        private final List<InputStream> streams = new ArrayList<>();
        private boolean closed;

        synchronized InputStream open(Path path) {
            if (closed) throw new UncheckedIOException(new IOException("Upload already closed"));
            try {
                InputStream stream = Files.newInputStream(path);
                streams.add(stream);
                return stream;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }

        synchronized void close() {
            closed = true;
            for (InputStream stream : streams) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    /* Keep releasing the other files. */
                }
            }
            streams.clear();
        }
    }

    private record ManagedPublisher(BodyPublisher delegate, FileStreams files)
            implements BodyPublisher {
        public long contentLength() {
            return delegate.contentLength();
        }

        public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
            delegate.subscribe(downstream);
        }
    }

    static void release(BodyPublisher publisher) {
        if (publisher instanceof ManagedPublisher managed) managed.files().close();
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static String headerValue(String value) {
        if (value.codePoints().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException(I18n.get("upload.headerInvalid"));
        return value.replace("\\", "\\\\").replace("\"", "%22");
    }

    private static BodyPublisher count(BodyPublisher source, LongConsumer progress) {
        return new BodyPublisher() {
            public long contentLength() {
                return source.contentLength();
            }

            public void subscribe(Flow.Subscriber<? super ByteBuffer> downstream) {
                source.subscribe(
                        new Flow.Subscriber<>() {
                            long sent;

                            public void onSubscribe(Flow.Subscription subscription) {
                                downstream.onSubscribe(subscription);
                            }

                            public void onNext(ByteBuffer bytes) {
                                sent += bytes.remaining();
                                if (progress != null) progress.accept(sent);
                                downstream.onNext(bytes);
                            }

                            public void onError(Throwable error) {
                                downstream.onError(error);
                            }

                            public void onComplete() {
                                downstream.onComplete();
                            }
                        });
            }
        };
    }
}
