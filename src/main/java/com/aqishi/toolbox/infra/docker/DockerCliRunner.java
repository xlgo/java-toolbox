package com.aqishi.toolbox.infra.docker;

import com.aqishi.toolbox.feature.network.ssh.domain.SshHostKeyPrompt;
import com.aqishi.toolbox.feature.network.ssh.infra.*;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.infra.ssh.SshExecConnection;
import com.aqishi.toolbox.util.I18n;
import com.jcraft.jsch.ChannelExec;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local argv execution or a quoted SSH exec channel. Never opens a local shell. */
public final class DockerCliRunner implements DockerCommandRunner {
    private static final int MAX_OUTPUT = 8 * 1024 * 1024;
    private static final ScheduledExecutorService DEADLINES =
            Executors.newSingleThreadScheduledExecutor(DaemonThreads.factory("docker-deadline"));
    private final String executable, context, sshId;
    private final List<String> globalArguments;
    private final boolean isolateDockerEndpoint;
    private final ExecutorService readers =
            Executors.newFixedThreadPool(2, DaemonThreads.factory("docker-output"));
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;
    private volatile Process process;
    private volatile ChannelExec channel;
    private volatile SshExecConnection ssh;
    private final SshHostKeyPrompt hostKeyPrompt;
    private volatile Request active;

    private static final class Request {
        volatile Process process;
        volatile ChannelExec channel;
        volatile SshExecConnection connecting;
        volatile boolean expired;
        private boolean finished;

        synchronized void expire() {
            if (finished) return;
            expired = true;
            abort();
        }

        synchronized void finish() {
            finished = true;
        }

        void abort() {
            Process child = process;
            if (child != null && child.isAlive()) child.destroyForcibly();
            ChannelExec exec = channel;
            if (exec != null) exec.disconnect();
            SshExecConnection session = connecting;
            if (session != null) session.close();
        }
    }

    public DockerCliRunner(String executable, String context, String sshId) {
        this(executable, context, sshId, SshHostKeyPrompt.denyAll());
    }

    public DockerCliRunner(
            String executable, String context, String sshId, SshHostKeyPrompt prompt) {
        this(executable, context, sshId, List.of(), false, prompt);
    }

    /** The tunnel supplies the endpoint; shell environment must not redirect Docker elsewhere. */
    static DockerCliRunner forTunnel(String executable, String endpoint) {
        return new DockerCliRunner(
                executable,
                "",
                "",
                List.of("--host", endpoint, "--tls=false", "--tlsverify=false"),
                true,
                SshHostKeyPrompt.denyAll());
    }

    private DockerCliRunner(
            String executable,
            String context,
            String sshId,
            List<String> globalArguments,
            boolean isolateDockerEndpoint,
            SshHostKeyPrompt prompt) {
        this.executable = checked(executable);
        this.context = checked(context);
        this.sshId = Objects.requireNonNullElse(sshId, "");
        this.globalArguments = List.copyOf(globalArguments);
        this.isolateDockerEndpoint = isolateDockerEndpoint;
        this.hostKeyPrompt = Objects.requireNonNullElseGet(prompt, SshHostKeyPrompt::denyAll);
        if (this.executable.isBlank())
            throw new IllegalArgumentException(I18n.get("docker.error.executable"));
    }

    public static String checked(String value) {
        value = Objects.requireNonNullElse(value, "");
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException(I18n.get("docker.error.argument"));
        return value;
    }

    public static String quote(String argument) {
        return "'" + checked(argument).replace("'", "'\\''") + "'";
    }

    @Override
    public Result run(List<String> arguments, int timeoutSeconds) throws Exception {
        if (timeoutSeconds < 1 || timeoutSeconds > 600)
            throw new IllegalArgumentException(I18n.get("docker.error.timeout"));
        long request = generation.get(),
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(globalArguments);
        if (!context.isBlank()) command.addAll(List.of("--context", context));
        for (String arg : arguments) command.add(checked(arg));
        Future<String> stdout = null, stderr = null;
        Process child = null;
        ChannelExec exec = null;
        Request operation = new Request();
        active = operation;
        ScheduledFuture<?> expiry =
                DEADLINES.schedule(operation::expire, timeoutSeconds, TimeUnit.SECONDS);
        try {
            check(request, deadline);
            if (sshId.isBlank()) {
                child = processBuilder(command, isolateDockerEndpoint).start();
                process = child;
                operation.process = child;
                child.getOutputStream().close();
                stdout = read(child.getInputStream());
                stderr = read(child.getErrorStream());
            } else {
                if (ssh == null) {
                    var config =
                            SshConfigStore.getInstance().getAll().stream()
                                    .filter(c -> sshId.equals(c.getId()))
                                    .findFirst()
                                    .orElse(null);
                    if (config == null) throw new IOException(I18n.get("docker.error.ssh"));
                    SshExecConnection candidate = new SshExecConnection(config, hostKeyPrompt);
                    ssh = candidate;
                    operation.connecting = candidate;
                    check(request, deadline);
                    candidate.connect();
                    check(request, deadline);
                    operation.connecting = null;
                }
                exec = ssh.openExecChannel();
                channel = exec;
                operation.channel = exec;
                exec.setCommand(
                        command.stream()
                                .map(DockerCliRunner::quote)
                                .collect(java.util.stream.Collectors.joining(" ")));
                exec.setInputStream(null);
                exec.setPty(false);
                stdout = read(exec.getInputStream());
                stderr = read(exec.getExtInputStream());
                check(request, deadline);
                exec.connect(
                        (int)
                                Math.max(
                                        1,
                                        Math.min(
                                                10000,
                                                TimeUnit.NANOSECONDS.toMillis(
                                                        deadline - System.nanoTime()))));
            }
            check(request, deadline);
            while (child != null ? child.isAlive() : !exec.isClosed()) {
                check(request, deadline);
                if (stdout.isDone()) stdout.get();
                if (stderr.isDone()) stderr.get();
                Thread.sleep(30);
            }
            int code = child != null ? child.exitValue() : exec.getExitStatus();
            String out =
                    stdout.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            String err =
                    stderr.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            check(request, deadline);
            return new Result(code, out, err);
        } catch (Exception error) {
            if (operation.expired
                    || error instanceof TimeoutException
                    || System.nanoTime() > deadline) {
                java.net.SocketTimeoutException timeout =
                        new java.net.SocketTimeoutException(I18n.get("docker.error.timeout"));
                timeout.initCause(error);
                throw timeout;
            }
            throw error;
        } finally {
            operation.finish();
            expiry.cancel(false);
            if (child != null && child.isAlive()) child.destroyForcibly();
            if (exec != null) exec.disconnect();
            if (stdout != null) stdout.cancel(true);
            if (stderr != null) stderr.cancel(true);
            if (process == child) process = null;
            if (channel == exec) channel = null;
            if (closed && ssh != null) ssh.close();
            if (operation.expired && operation.connecting != null && ssh == operation.connecting)
                ssh = null;
            if (active == operation) active = null;
        }
    }

    private void check(long request, long deadline) throws IOException {
        if (closed || generation.get() != request || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException(I18n.get("docker.cancelled"));
        if (System.nanoTime() > deadline)
            throw new java.net.SocketTimeoutException(I18n.get("docker.error.timeout"));
    }

    static ProcessBuilder processBuilder(List<String> command, boolean isolateEndpoint) {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (isolateEndpoint) configureTunnelEnvironment(builder);
        return builder;
    }

    static void configureTunnelEnvironment(ProcessBuilder builder) {
        for (String key :
                List.of(
                        "DOCKER_HOST",
                        "DOCKER_CONTEXT",
                        "DOCKER_TLS",
                        "DOCKER_TLS_VERIFY",
                        "DOCKER_CERT_PATH")) builder.environment().remove(key);
        // The destination is a local SSH listener, independent of an HTTP proxy environment.
        builder.environment().put("NO_PROXY", "127.0.0.1,localhost,::1");
        builder.environment().put("no_proxy", "127.0.0.1,localhost,::1");
    }

    private Future<String> read(InputStream stream) {
        return readers.submit(
                () -> {
                    try (InputStream in = stream;
                            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            if (out.size() + count > MAX_OUTPUT)
                                throw new IOException(I18n.get("docker.error.output"));
                            out.write(buffer, 0, count);
                        }
                        return out.toString(StandardCharsets.UTF_8);
                    }
                });
    }

    @Override
    public void cancel() {
        generation.incrementAndGet();
        Request operation = active;
        if (operation != null) operation.abort();
        Process child = process;
        if (child != null) child.destroyForcibly();
        ChannelExec exec = channel;
        if (exec != null) exec.disconnect();
    }

    @Override
    public void close() {
        closed = true;
        cancel();
        readers.shutdownNow();
        SshExecConnection session = ssh;
        if (session != null) session.close();
    }
}
