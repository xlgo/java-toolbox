package com.aqishi.toolbox.infra.docker;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.infra.docker.DockerConnectionOptions.SshMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

@Timeout(20)
class DockerSshTunnelRunnerTest {
    private static DockerConnectionOptions options() {
        return new DockerConnectionOptions(
                "docker", "", "saved-ssh", SshMode.TCP_TUNNEL, "docker.internal", 2375);
    }

    static class Runner implements DockerCommandRunner {
        final AtomicInteger commands = new AtomicInteger(),
                cancelled = new AtomicInteger(),
                closed = new AtomicInteger();

        public Result run(List<String> args, int timeout) {
            commands.incrementAndGet();
            return new Result(0, String.join(" ", args), "");
        }

        public void cancel() {
            cancelled.incrementAndGet();
        }

        public void close() {
            closed.incrementAndGet();
        }
    }

    @Test
    void usesOneSharedLeaseAcrossAllDockerCommandsAndReleasesOnlyItsLease() throws Exception {
        AtomicInteger opens = new AtomicInteger(), releases = new AtomicInteger();
        Runner cli = new Runner();
        try (var runner =
                new DockerSshTunnelRunner(
                        options(),
                        (id, host, port) -> {
                            assertEquals("saved-ssh", id);
                            assertEquals("docker.internal", host);
                            assertEquals(2375, port);
                            opens.incrementAndGet();
                            return new DockerSshTunnelRunner.Route(
                                    "127.0.0.1", 49123, releases::incrementAndGet);
                        },
                        endpoint -> {
                            assertEquals("tcp://127.0.0.1:49123", endpoint);
                            return cli;
                        })) {
            assertEquals("info", runner.run(List.of("info"), 5).output());
            assertEquals("container ls", runner.run(List.of("container", "ls"), 5).output());
            assertEquals(1, opens.get());
            assertEquals(0, releases.get());
            runner.close();
            runner.close();
            assertThrows(InterruptedIOException.class, () -> runner.run(List.of("info"), 5));
        }
        await(() -> releases.get() == 1);
        assertEquals(1, cli.closed.get());
        assertEquals(2, cli.commands.get());
    }

    @Test
    void closingOneConsumerDoesNotCloseAnotherConsumersLease() throws Exception {
        AtomicInteger leases = new AtomicInteger(2);
        DockerSshTunnelRunner.TunnelOpener opener =
                (id, host, port) ->
                        new DockerSshTunnelRunner.Route(
                                "127.0.0.1", 49123, leases::decrementAndGet);
        try (var first = new DockerSshTunnelRunner(options(), opener, endpoint -> new Runner());
                var second =
                        new DockerSshTunnelRunner(options(), opener, endpoint -> new Runner())) {
            first.run(List.of("info"), 5);
            second.run(List.of("info"), 5);
            first.close();
            await(() -> leases.get() == 1);
            second.run(List.of("container", "ls"), 5);
            assertEquals(1, leases.get());
        }
        await(() -> leases.get() == 0);
    }

    @Test
    void timeoutReturnsWhileSharedHandshakeContinuesAndReleasesLateLease() throws Exception {
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger(), created = new AtomicInteger();
        try (var runner =
                new DockerSshTunnelRunner(
                        options(),
                        (id, host, port) -> {
                            started.countDown();
                            gate.await();
                            return new DockerSshTunnelRunner.Route(
                                    "127.0.0.1", 49123, released::incrementAndGet);
                        },
                        endpoint -> {
                            created.incrementAndGet();
                            return new Runner();
                        })) {
            try {
                assertThrows(SocketTimeoutException.class, () -> runner.run(List.of("info"), 1));
                assertEquals(0, started.getCount());
            } finally {
                gate.countDown();
            }
            await(() -> released.get() == 1);
            assertEquals(0, created.get());
        } finally {
            gate.countDown();
        }
    }

    @Test
    void cancellingAcquisitionDoesNotInterruptSharedSshWork() throws Exception {
        CountDownLatch gate = new CountDownLatch(1), started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicInteger released = new AtomicInteger();
        ExecutorService request = Executors.newSingleThreadExecutor();
        try (var runner =
                new DockerSshTunnelRunner(
                        options(),
                        (id, host, port) -> {
                            started.countDown();
                            try {
                                gate.await();
                            } catch (InterruptedException e) {
                                interrupted.set(true);
                                throw e;
                            }
                            return new DockerSshTunnelRunner.Route(
                                    "127.0.0.1", 49123, released::incrementAndGet);
                        },
                        endpoint -> new Runner())) {
            Future<?> result =
                    request.submit(
                            () ->
                                    assertThrows(
                                            InterruptedIOException.class,
                                            () -> runner.run(List.of("info"), 15)));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            runner.cancel();
            result.get(5, TimeUnit.SECONDS);
            assertFalse(interrupted.get());
            gate.countDown();
            await(() -> released.get() == 1);
        } finally {
            gate.countDown();
            request.shutdownNow();
        }
    }

    @Test
    void timeoutAlsoCancelsAnAlreadyRunningCliCommand() throws Exception {
        AtomicInteger releases = new AtomicInteger();
        CountDownLatch ended = new CountDownLatch(1);
        DockerCommandRunner blocked =
                new DockerCommandRunner() {
                    public Result run(List<String> args, int timeout) throws Exception {
                        ended.await();
                        return new Result(0, "", "");
                    }

                    public void cancel() {
                        ended.countDown();
                    }

                    public void close() {
                        ended.countDown();
                    }
                };
        try (var runner =
                new DockerSshTunnelRunner(
                        options(),
                        (id, host, port) ->
                                new DockerSshTunnelRunner.Route(
                                        "127.0.0.1", 49123, releases::incrementAndGet),
                        endpoint -> blocked)) {
            assertThrows(SocketTimeoutException.class, () -> runner.run(List.of("info"), 1));
            await(() -> releases.get() == 1);
        }
    }

    @Test
    void localCliSetupFailureAndInvalidLocalRouteReleaseAcquiredLease() throws Exception {
        AtomicInteger releases = new AtomicInteger();
        try (var runner =
                new DockerSshTunnelRunner(
                        options(),
                        (id, host, port) ->
                                new DockerSshTunnelRunner.Route(
                                        "127.0.0.1", 49123, releases::incrementAndGet),
                        endpoint -> {
                            throw new IOException("missing CLI");
                        })) {
            assertEquals(
                    "missing CLI",
                    assertThrows(IOException.class, () -> runner.run(List.of("info"), 5))
                            .getMessage());
        }
        assertEquals(1, releases.get());
        try (var runner =
                new DockerSshTunnelRunner(
                        options(),
                        (id, host, port) ->
                                new DockerSshTunnelRunner.Route(
                                        "0.0.0.0", 49123, releases::incrementAndGet),
                        endpoint -> new Runner())) {
            assertThrows(IOException.class, () -> runner.run(List.of("info"), 5));
        }
        assertEquals(2, releases.get());
    }

    @Test
    void optionsKeepRemoteCommandCompatibilityAndRejectConflictingTunnelContext() throws Exception {
        try (var local =
                        new DockerConnectionOptions(
                                        "docker", "desktop-linux", "", SshMode.COMMAND, "", 0)
                                .createRunner();
                var remote =
                        new DockerConnectionOptions("docker", "", "saved", SshMode.COMMAND, "", 0)
                                .createRunner();
                var tunnel = options().createRunner()) {
            assertInstanceOf(DockerCliRunner.class, local);
            assertInstanceOf(DockerCliRunner.class, remote);
            assertInstanceOf(DockerSshTunnelRunner.class, tunnel);
        }
        assertEquals(
                "::1",
                new DockerConnectionOptions(
                                "docker", "", "saved", SshMode.TCP_TUNNEL, "[::1]", 2375)
                        .remoteHost());
        for (String host : List.of("", "a/b", "user@host", "bad\nhost"))
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new DockerConnectionOptions(
                                    "docker", "", "saved", SshMode.TCP_TUNNEL, host, 2375));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DockerConnectionOptions(
                                "docker", "other", "saved", SshMode.TCP_TUNNEL, "localhost", 2375));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new DockerConnectionOptions(
                                "docker", "", "saved", SshMode.TCP_TUNNEL, "localhost", 0));
    }

    @Test
    void tunnelCliCannotInheritAnEndpointOrTlsOverrideFromTheShell() {
        ProcessBuilder builder = new ProcessBuilder("docker");
        for (String key :
                List.of(
                        "DOCKER_HOST",
                        "DOCKER_CONTEXT",
                        "DOCKER_TLS",
                        "DOCKER_TLS_VERIFY",
                        "DOCKER_CERT_PATH")) builder.environment().put(key, "other-daemon");
        builder.environment().put("DOCKER_CONFIG", "fixture-config");
        DockerCliRunner.configureTunnelEnvironment(builder);
        assertFalse(builder.environment().containsKey("DOCKER_CONTEXT"));
        assertFalse(builder.environment().containsValue("other-daemon"));
        assertEquals("fixture-config", builder.environment().get("DOCKER_CONFIG"));
        assertTrue(builder.environment().get("NO_PROXY").contains("127.0.0.1"));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(condition.getAsBoolean());
    }
}
