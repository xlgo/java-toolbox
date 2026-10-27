package com.aqishi.toolbox.infra.docker;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class DockerCliRunnerTest {
    private static DockerCliRunner runner() {
        return new DockerCliRunner(
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "java.exe"
                                        : "java")
                        .toString(),
                "",
                "");
    }

    private static List<String> args(String... args) {
        List<String> result =
                new ArrayList<>(
                        List.of(
                                "-cp",
                                System.getProperty("java.class.path"),
                                Child.class.getName()));
        result.addAll(List.of(args));
        return result;
    }

    @Test
    void localArgumentsAndBothStreamsArePreservedAndExitCodeIsReal() throws Exception {
        try (var runner = runner()) {
            var result = runner.run(args("echo", "hello $(whoami); 'x'"), 10);
            assertEquals(7, result.exitCode());
            assertEquals("hello $(whoami); 'x'", result.output());
            assertEquals("stderr", result.error());
        }
    }

    @Test
    void timeoutKillsChildAndRunnerCanBeUsedAgain() throws Exception {
        try (var runner = runner()) {
            assertThrows(java.net.SocketTimeoutException.class, () -> runner.run(args("wait"), 1));
            assertEquals(7, runner.run(args("echo", "after-timeout"), 10).exitCode());
        }
    }

    @Test
    void cancellationAbortsActiveChildAndCloseRejectsFurtherCommands() throws Exception {
        var runner = runner();
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> result =
                    pool.submit(
                            () ->
                                    assertThrows(
                                            InterruptedIOException.class,
                                            () -> runner.run(args("wait"), 30)));
            Thread.sleep(1000);
            runner.cancel();
            result.get(5, TimeUnit.SECONDS);
            runner.close();
            assertThrows(InterruptedIOException.class, () -> runner.run(args("echo"), 10));
        } finally {
            runner.close();
            pool.shutdownNow();
        }
    }

    @Test
    void outputLimitAbortsInsteadOfGrowingMemoryWithoutBound() throws Exception {
        try (var runner = runner()) {
            assertThrows(Exception.class, () -> runner.run(args("flood"), 10));
        }
    }

    public static class Child {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("wait")) Thread.sleep(30000);
            else if (args[0].equals("flood")) {
                byte[] data = new byte[9 * 1024 * 1024];
                Arrays.fill(data, (byte) 'x');
                System.out.write(data);
            } else {
                System.out.print(args.length > 1 ? args[1] : "");
                System.err.print("stderr");
                System.exit(7);
            }
        }
    }
}
