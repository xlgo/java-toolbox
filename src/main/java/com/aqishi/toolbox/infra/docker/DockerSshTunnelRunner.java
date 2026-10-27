package com.aqishi.toolbox.infra.docker;

import com.aqishi.toolbox.feature.network.ssh.infra.SshTunnelBridge;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.I18n;

import java.io.*;
import java.net.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Local Docker CLI over the same leased TCP forwards used by Kubernetes, Redis, and Kafka. */
public final class DockerSshTunnelRunner implements DockerCommandRunner {
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(
                    DaemonThreads.factory("docker-tunnel-deadline"));

    @FunctionalInterface
    interface TunnelOpener {
        Route open(String id, String host, int port) throws Exception;
    }

    @FunctionalInterface
    interface LocalRunnerFactory {
        DockerCommandRunner create(String endpoint) throws Exception;
    }

    record Route(String host, int port, AutoCloseable lease) {}

    private final DockerConnectionOptions options;
    private final TunnelOpener opener;
    private final LocalRunnerFactory factory;
    private final ExecutorService opening =
            Executors.newSingleThreadExecutor(DaemonThreads.factory("docker-tunnel-open"));
    private final AtomicBoolean closed = new AtomicBoolean(), executing = new AtomicBoolean();
    private final AtomicReference<Connection> active = new AtomicReference<>();
    private CompletableFuture<Connection> pending;

    private static final class Connection {
        final Route route;
        final DockerCommandRunner runner;
        final AtomicBoolean released = new AtomicBoolean();

        Connection(Route route, DockerCommandRunner runner) {
            this.route = route;
            this.runner = runner;
        }

        void release() {
            if (!released.compareAndSet(false, true)) return;
            try {
                runner.cancel();
            } finally {
                // Bridge release may wait behind another tool's in-flight SSH handshake.
                DaemonThreads.factory("docker-tunnel-release")
                        .newThread(
                                () -> {
                                    try {
                                        runner.close();
                                    } finally {
                                        closeLease(route);
                                    }
                                })
                        .start();
            }
        }
    }

    public DockerSshTunnelRunner(DockerConnectionOptions options) {
        this(
                options,
                (id, host, port) -> {
                    var bridge = SshTunnelBridge.bridgeEphemeral(id, host, port);
                    return new Route(bridge.getLocalHost(), bridge.getLocalPort(), bridge);
                },
                endpoint -> DockerCliRunner.forTunnel(options.executable(), endpoint));
    }

    DockerSshTunnelRunner(
            DockerConnectionOptions options, TunnelOpener opener, LocalRunnerFactory factory) {
        if (options.sshId().isEmpty()
                || options.sshMode() != DockerConnectionOptions.SshMode.TCP_TUNNEL)
            throw new IllegalArgumentException(I18n.get("docker.ssh.invalidTarget"));
        this.options = options;
        this.opener = opener;
        this.factory = factory;
    }

    private synchronized CompletableFuture<Connection> connection() throws InterruptedIOException {
        checkOpen();
        if (pending != null) return pending;
        CompletableFuture<Connection> future = new CompletableFuture<>();
        pending = future;
        // Exactly one acquisition is submitted for the lifetime of this connection.
        opening.execute(
                () -> {
                    Route route = null;
                    try {
                        route =
                                opener.open(
                                        options.sshId(),
                                        options.remoteHost(),
                                        options.remotePort());
                        if (closed.get()) {
                            closeLease(route);
                            route = null;
                            throw cancelled();
                        }
                        if (!List.of("127.0.0.1", "::1", "localhost").contains(route.host())
                                || route.port() < 1
                                || route.port() > 65535)
                            throw new IOException(I18n.get("docker.ssh.invalidRoute"));
                        String endpoint =
                                new URI("tcp", null, route.host(), route.port(), null, null, null)
                                        .toString();
                        Connection connection = new Connection(route, factory.create(endpoint));
                        route = null;
                        active.set(connection);
                        if (closed.get()) connection.release();
                        future.complete(connection);
                    } catch (Exception error) {
                        if (route != null) closeLease(route);
                        future.completeExceptionally(error);
                    }
                });
        return future;
    }

    @Override
    public Result run(List<String> arguments, int timeoutSeconds) throws Exception {
        if (timeoutSeconds < 1 || timeoutSeconds > 600)
            throw new IllegalArgumentException(I18n.get("docker.error.timeout"));
        if (!executing.compareAndSet(false, true))
            throw new IllegalStateException("Docker tunnel command already running");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        AtomicInteger state = new AtomicInteger();
        ScheduledFuture<?> timer =
                DEADLINES.schedule(
                        () -> {
                            if (state.compareAndSet(0, 2)) cancel();
                        },
                        timeoutSeconds,
                        TimeUnit.SECONDS);
        try {
            Connection connection =
                    connection()
                            .get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            checkOpen();
            int remaining =
                    (int) Math.max(1, (deadline - System.nanoTime() + 999999999L) / 1000000000L);
            Result result = connection.runner.run(arguments, Math.min(timeoutSeconds, remaining));
            checkOpen();
            return result;
        } catch (Exception error) {
            if (state.get() == 2
                    || error instanceof TimeoutException
                    || System.nanoTime() >= deadline) {
                cancel();
                SocketTimeoutException timeout =
                        new SocketTimeoutException(I18n.get("docker.error.timeout"));
                timeout.initCause(error);
                throw timeout;
            }
            if (error instanceof ExecutionException e && e.getCause() instanceof Exception cause)
                throw cause;
            throw error;
        } finally {
            state.compareAndSet(0, 1);
            timer.cancel(false);
            executing.set(false);
        }
    }

    private static void closeLease(Route route) {
        try {
            route.lease().close();
        } catch (Exception ignored) {
        }
    }

    private static InterruptedIOException cancelled() {
        return new InterruptedIOException(I18n.get("docker.cancelled"));
    }

    private void checkOpen() throws InterruptedIOException {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw cancelled();
    }

    @Override
    public void cancel() {
        if (!closed.compareAndSet(false, true)) return;
        CompletableFuture<Connection> future;
        synchronized (this) {
            future = pending;
        }
        if (future != null) future.completeExceptionally(cancelled());
        Connection connection = active.get();
        if (connection != null) connection.release();
        // Do not interrupt a shared SSH handshake; its eventual lease is released by the opener.
        opening.shutdown();
    }

    @Override
    public void close() {
        cancel();
    }
}
