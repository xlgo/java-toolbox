package com.aqishi.toolbox.feature.network.application;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.network.domain.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

class HttpBodiesTest {
    @TempDir Path temp;

    @Test
    void cancellingFileUploadInterruptsBlockedRequest() throws Exception {
        Path file = temp.resolve("large.bin");
        try (var channel =
                java.nio.channels.FileChannel.open(
                        file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(32L * 1024 * 1024 - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {0}));
        }
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        server.createContext(
                "/",
                exchange -> {
                    exchange.getRequestBody().read();
                    entered.countDown();
                    try {
                        release.await(8, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        exchange.close();
                    }
                });
        server.start();
        var failure = new AtomicReference<Throwable>();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                var prepared =
                                        HttpBodies.prepare(
                                                "",
                                                new HttpBody(
                                                        HttpBody.Mode.MULTIPART,
                                                        List.of(
                                                                new HttpBody.Part(
                                                                        "file",
                                                                        file.toString(),
                                                                        true))),
                                                null);
                                new OpenApiRequestExecutor()
                                        .executeBody(
                                                "http://127.0.0.1:" + server.getAddress().getPort(),
                                                "POST",
                                                Map.of("Content-Type", prepared.contentType()),
                                                prepared.publisher(),
                                                Duration.ofSeconds(30));
                            } catch (Throwable error) {
                                failure.set(error);
                            }
                        });
        try {
            worker.start();
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, failure.get());
            Files.delete(file);
            assertFalse(
                    Files.exists(file), "Cancelling must release the upload file before returning");
        } finally {
            worker.interrupt();
            release.countDown();
            server.stop(0);
            worker.join(3000);
        }
    }

    @Test
    void multipartStreamsFilesAndRepeatedTextWithByteAccurateProgress() throws Exception {
        Path one = temp.resolve("a.bin"), two = temp.resolve("b.txt");
        byte[] bytes = {0, 1, 2, -1};
        Files.write(one, bytes);
        Files.writeString(two, "中文");
        AtomicReference<byte[]> received = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                e -> {
                    received.set(e.getRequestBody().readAllBytes());
                    contentType.set(e.getRequestHeaders().getFirst("Content-Type"));
                    e.sendResponseHeaders(204, -1);
                    e.close();
                });
        server.start();
        try {
            AtomicLong count = new AtomicLong();
            var body =
                    new HttpBody(
                            HttpBody.Mode.MULTIPART,
                            List.of(
                                    new HttpBody.Part("tag", "one", false),
                                    new HttpBody.Part("tag", "two", false),
                                    new HttpBody.Part("files", one.toString(), true),
                                    new HttpBody.Part("files", two.toString(), true)));
            var prepared = HttpBodies.prepare("", body, count::set);
            var response =
                    new OpenApiRequestExecutor()
                            .executeBody(
                                    "http://127.0.0.1:" + server.getAddress().getPort(),
                                    "POST",
                                    Map.of("Content-Type", prepared.contentType()),
                                    prepared.publisher(),
                                    Duration.ofSeconds(5));
            assertEquals(204, response.status());
            assertEquals(received.get().length, count.get());
            assertEquals(received.get().length, prepared.publisher().contentLength());
            String text = new String(received.get(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(text.contains("name=\"tag\"\r\n\r\none"));
            assertTrue(text.contains("name=\"tag\"\r\n\r\ntwo"));
            assertTrue(text.contains("filename=\"a.bin\""));
            assertTrue(text.contains("中文"));
            assertTrue(text.endsWith("--\r\n"));
            assertEquals(prepared.contentType(), contentType.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void urlEncodedFormPreservesRepeatedKeysAndEscapes() throws Exception {
        var body =
                new HttpBody(
                        HttpBody.Mode.FORM,
                        List.of(
                                new HttpBody.Part("a b", "x&y", false),
                                new HttpBody.Part("a b", "+", false)));
        var prepared = HttpBodies.prepare("", body, null);
        var result = new java.io.ByteArrayOutputStream();
        var done = new java.util.concurrent.CompletableFuture<Void>();
        prepared.publisher()
                .subscribe(
                        new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                                s.request(Long.MAX_VALUE);
                            }

                            public void onNext(java.nio.ByteBuffer b) {
                                byte[] bytes = new byte[b.remaining()];
                                b.get(bytes);
                                result.writeBytes(bytes);
                            }

                            public void onComplete() {
                                done.complete(null);
                            }

                            public void onError(Throwable e) {
                                done.completeExceptionally(e);
                            }
                        });
        done.get();
        assertEquals("a+b=x%26y&a+b=%2B", result.toString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void rejectsHeaderInjectionAndMissingFile() {
        assertThrows(
                Exception.class,
                () ->
                        HttpBodies.prepare(
                                "",
                                new HttpBody(
                                        HttpBody.Mode.MULTIPART,
                                        List.of(new HttpBody.Part("bad\r\nX: y", "v", false))),
                                null));
        assertThrows(
                Exception.class,
                () ->
                        HttpBodies.prepare(
                                "",
                                new HttpBody(
                                        HttpBody.Mode.MULTIPART,
                                        List.of(
                                                new HttpBody.Part(
                                                        "f",
                                                        temp.resolve("missing").toString(),
                                                        true))),
                                null));
    }

    @Test
    void oldRequestJsonDefaultsToRawAndNewPartTemplatesResolve() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var old =
                mapper.readValue(
                        "{\"name\":\"x\",\"method\":\"POST\",\"url\":\"http://localhost\",\"headers\":\"\",\"body\":\"abc\",\"favorite\":false}",
                        HttpWorkspace.Request.class);
        assertEquals(HttpBody.Mode.RAW, old.bodySpec().mode());
        var req =
                new HttpWorkspace.Request(
                        "x",
                        "POST",
                        "http://localhost",
                        "",
                        "",
                        false,
                        new HttpBody(
                                HttpBody.Mode.MULTIPART,
                                List.of(new HttpBody.Part("tag", "{{value}}", false))));
        var resolved =
                HttpWorkspace.resolve(
                        req,
                        new HttpWorkspace.Environment(
                                "e", Map.of("value", new HttpWorkspace.Variable("secret", true))),
                        true);
        assertEquals("******", resolved.bodySpec().parts().get(0).value());
        assertEquals(
                req, mapper.readValue(mapper.writeValueAsString(req), HttpWorkspace.Request.class));
    }
}
