package com.aqishi.toolbox.feature.cloud.application;

import static org.junit.jupiter.api.Assertions.*;

import com.aqishi.toolbox.feature.cloud.application.DockerService.*;
import com.aqishi.toolbox.infra.docker.*;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

class DockerServiceTest {
    @Test
    void networkAndVolumeCreationUsesScopedNonShellCommands() throws Exception {
        Runner runner = new Runner();
        DockerService service = new DockerService(runner, 30);
        service.createResource(Kind.NETWORK, "app-net", "bridge");
        assertEquals(
                List.of("network", "create", "--driver", "bridge", "--", "app-net"), runner.args);
        service.createResource(Kind.VOLUME, "app-data", "local");
        assertEquals(
                List.of("volume", "create", "--driver", "local", "--", "app-data"), runner.args);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createResource(Kind.IMAGE, "image", ""));
    }

    static class Runner implements DockerCommandRunner {
        List<String> args;
        Result response = new Result(0, "", "");
        int timeout;
        boolean cancelled, closed;

        public Result run(List<String> args, int timeout) {
            this.args = List.copyOf(args);
            this.timeout = timeout;
            return response;
        }

        public void cancel() {
            cancelled = true;
        }

        public void close() {
            closed = true;
        }
    }

    @Test
    void listsAllResourceKindsAndKeepsFullIdentifiers() throws Exception {
        Runner runner = new Runner();
        DockerService service = new DockerService(runner, 37);
        for (Kind kind : Kind.values()) {
            runner.response =
                    new DockerCommandRunner.Result(
                            0,
                            "{\"ID\":\"0123456789abcdef\",\"Name\":\"data\"}\n"
                                    + "{\"ID\":\"second\"}\n",
                            "");
            var rows = service.list(kind, true);
            assertEquals(2, rows.size());
            assertEquals("0123456789abcdef", rows.get(0).path("ID").asText());
            assertEquals(kind.command, runner.args.get(0));
            assertEquals(37, runner.timeout);
            assertEquals(kind == Kind.CONTAINER, runner.args.contains("--all"));
            assertEquals(
                    kind == Kind.IMAGE || kind == Kind.CONTAINER,
                    runner.args.contains("--no-trunc"));
        }
    }

    @Test
    void nonzeroExitIsNotReportedAsSuccessAndLogsIncludeBothStreams() throws Exception {
        Runner runner = new Runner();
        DockerService service = new DockerService(runner, 30);
        runner.response = new DockerCommandRunner.Result(1, "", "permission denied");
        assertTrue(
                assertThrows(IOException.class, () -> service.action("start", "container-id"))
                        .getMessage()
                        .contains("permission denied"));
        runner.response = new DockerCommandRunner.Result(0, "stdout-line\n", "stderr-line\n");
        assertEquals("stdout-line\nstderr-line\n", service.logs("container-id", 123));
        assertTrue(runner.args.contains("123"));
        assertThrows(IllegalArgumentException.class, () -> service.logs("id", 10001));
    }

    @Test
    void mutationArgumentsStayDistinctAndNoImplicitForceOrVolumeDeletion() throws Exception {
        Runner runner = new Runner();
        DockerService service = new DockerService(runner, 30);
        service.delete(Kind.CONTAINER, "id;touch /tmp/nope");
        assertEquals(List.of("container", "rm", "--", "id;touch /tmp/nope"), runner.args);
        assertThrows(IllegalArgumentException.class, () -> service.action("prune", "id"));
        assertThrows(IllegalArgumentException.class, () -> service.delete(Kind.IMAGE, "--force"));
        assertThrows(IllegalArgumentException.class, () -> service.pull("nginx\nwhoami"));
        service.cancel();
        service.close();
        assertTrue(runner.cancelled);
        assertTrue(runner.closed);
    }

    @Test
    void createSupportsPortsEnvironmentVolumesAndRestartWithoutShellParsing() {
        var args =
                DockerService.runCommand(
                        new RunOptions(
                                "nginx:latest",
                                "web",
                                List.of("127.0.0.1:8080:80"),
                                List.of("MESSAGE=hello $(whoami) 'world'"),
                                List.of("C:\\data with spaces:/data:ro"),
                                "unless-stopped"));
        assertTrue(args.contains("MESSAGE=hello $(whoami) 'world'"));
        assertTrue(args.contains("C:\\data with spaces:/data:ro"));
        assertEquals(List.of("--", "nginx:latest"), args.subList(args.size() - 2, args.size()));
        assertFalse(args.contains("--privileged"));
    }

    @Test
    void rejectsInvalidListingRatherThanSilentlyShowingEmptyResources() {
        Runner runner = new Runner();
        runner.response = new DockerCommandRunner.Result(0, "[]", "");
        assertThrows(
                IOException.class, () -> new DockerService(runner, 30).list(Kind.CONTAINER, true));
    }

    @Test
    void shellQuotingHandlesSingleQuotesAndCommandSubstitutionLiterally() {
        assertEquals("'a'\\''b $(whoami); x'", DockerCliRunner.quote("a'b $(whoami); x"));
        assertThrows(IllegalArgumentException.class, () -> DockerCliRunner.quote("a\0b"));
    }
}
