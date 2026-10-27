package com.aqishi.toolbox.feature.cloud.application;

import com.aqishi.toolbox.infra.docker.*;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.*;

/** Docker operations are explicit argv vectors, never user-supplied shell commands. */
public final class DockerService implements AutoCloseable {
    public enum Kind {
        CONTAINER("container", new String[] {"ID", "Names", "Image", "Status", "Ports"}),
        IMAGE("image", new String[] {"ID", "Repository", "Tag", "Size", "CreatedSince"}),
        NETWORK("network", new String[] {"ID", "Name", "Driver", "Scope"}),
        VOLUME("volume", new String[] {"Name", "Driver", "Scope"});
        public final String command;
        public final List<String> columns;

        Kind(String command, String[] columns) {
            this.command = command;
            this.columns = List.of(columns);
        }

        @Override
        public String toString() {
            return I18n.get("docker.kind." + command);
        }
    }

    public record RunOptions(
            String image,
            String name,
            List<String> ports,
            List<String> environment,
            List<String> mounts,
            String restart) {}

    private final DockerCommandRunner runner;
    private final int timeout;

    public DockerService(DockerCommandRunner runner, int timeout) {
        this.runner = Objects.requireNonNull(runner);
        this.timeout = timeout;
    }

    private String execute(List<String> args) throws Exception {
        DockerCommandRunner.Result result = runner.run(args, timeout);
        if (result.exitCode() != 0)
            throw new IOException(
                    I18n.get("docker.error.exit", result.exitCode())
                            + "\n"
                            + result.error()
                            + result.output());
        return result.output();
    }

    public String info() throws Exception {
        return execute(List.of("info", "--format", "{{json .}}"));
    }

    public List<JsonNode> list(Kind kind, boolean all) throws Exception {
        List<String> args = new ArrayList<>(List.of(kind.command, "ls", "--format", "{{json .}}"));
        if (kind == Kind.CONTAINER || kind == Kind.IMAGE) args.add("--no-trunc");
        if (kind == Kind.CONTAINER && all) args.add("--all");
        List<JsonNode> result = new ArrayList<>();
        for (String line : execute(args).split("\\R")) {
            if (line.isBlank()) continue;
            JsonNode row = Json.mapper().readTree(line);
            if (row == null || !row.isObject())
                throw new IOException(I18n.get("docker.error.json"));
            result.add(row);
        }
        return List.copyOf(result);
    }

    public String inspect(Kind kind, String id) throws Exception {
        return execute(List.of(kind.command, "inspect", "--", required(id)));
    }

    public String logs(String id, int lines) throws Exception {
        if (lines < 1 || lines > 10000)
            throw new IllegalArgumentException(I18n.get("docker.error.argument"));
        // Docker writes container stderr to CLI stderr even on a successful logs command.
        var result =
                runner.run(
                        List.of(
                                "container",
                                "logs",
                                "--timestamps",
                                "--tail",
                                Integer.toString(lines),
                                "--",
                                required(id)),
                        timeout);
        if (result.exitCode() != 0)
            throw new IOException(
                    I18n.get("docker.error.exit", result.exitCode()) + "\n" + result.error());
        return result.output() + result.error();
    }

    public String stats(String id) throws Exception {
        return execute(
                List.of(
                        "container",
                        "stats",
                        "--no-stream",
                        "--format",
                        "{{json .}}",
                        "--",
                        required(id)));
    }

    public String action(String action, String id) throws Exception {
        if (!Set.of("start", "stop", "restart", "pause", "unpause").contains(action))
            throw new IllegalArgumentException(action);
        return execute(List.of("container", action, "--", required(id)));
    }

    public String delete(Kind kind, String id) throws Exception {
        return execute(List.of(kind.command, "rm", "--", required(id)));
    }

    public String pull(String image) throws Exception {
        return execute(List.of("image", "pull", "--", required(image)));
    }

    public String create(RunOptions options) throws Exception {
        return execute(runCommand(options));
    }

    public String createResource(Kind kind, String name, String driver) throws Exception {
        if (kind != Kind.NETWORK && kind != Kind.VOLUME)
            throw new IllegalArgumentException(kind.name());
        List<String> args = new ArrayList<>(List.of(kind.command, "create"));
        if (driver != null && !driver.isBlank()) args.addAll(List.of("--driver", required(driver)));
        args.addAll(List.of("--", required(name)));
        return execute(args);
    }

    public static List<String> runCommand(RunOptions o) {
        List<String> args = new ArrayList<>(List.of("run", "--detach"));
        if (!o.name().isBlank()) {
            args.add("--name");
            args.add(required(o.name()));
        }
        for (String port : o.ports()) {
            args.add("--publish");
            args.add(required(port));
        }
        for (String env : o.environment()) {
            args.add("--env");
            args.add(required(env));
        }
        for (String mount : o.mounts()) {
            args.add("--volume");
            args.add(required(mount));
        }
        if (!Set.of("no", "always", "unless-stopped", "on-failure").contains(o.restart()))
            throw new IllegalArgumentException(I18n.get("docker.error.argument"));
        args.addAll(List.of("--restart", o.restart(), "--", required(o.image())));
        return List.copyOf(args);
    }

    private static String required(String value) {
        value = DockerCliRunner.checked(value).trim();
        if (value.isBlank() || value.startsWith("-"))
            throw new IllegalArgumentException(I18n.get("docker.error.argument"));
        return value;
    }

    public void cancel() {
        runner.cancel();
    }

    @Override
    public void close() {
        runner.close();
    }
}
