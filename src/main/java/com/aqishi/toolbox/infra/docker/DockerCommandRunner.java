package com.aqishi.toolbox.infra.docker;

import java.util.List;

/** Process boundary; tests supply an in-memory daemon transcript. */
public interface DockerCommandRunner extends AutoCloseable {
    record Result(int exitCode, String output, String error) {}

    Result run(List<String> arguments, int timeoutSeconds) throws Exception;

    void cancel();

    @Override
    void close();
}
