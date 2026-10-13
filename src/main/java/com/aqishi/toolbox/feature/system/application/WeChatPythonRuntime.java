package com.aqishi.toolbox.feature.system.application;

import com.aqishi.toolbox.util.I18n;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class WeChatPythonRuntime {
    private WeChatPythonRuntime() { }

    static Process start(Path directory, String entry, BooleanSupplier stopped) throws Exception {
        for (List<String> command : List.of(List.of("python"), List.of("py", "-3"), List.of("python3"))) {
            if (stopped.getAsBoolean()) throw new CancellationException();
            List<String> probe = new ArrayList<>(command);
            probe.addAll(List.of("-c", "import sys,struct,sqlite3;sys.exit(0 if sys.version_info>=(3,11) and struct.calcsize('P')==8 and hasattr(sqlite3.Connection,'deserialize') else 1)"));
            Process test;
            try {
                test = new ProcessBuilder(probe).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException unavailable) { continue; }
            try {
                if (!test.waitFor(5, TimeUnit.SECONDS) || test.exitValue() != 0) continue;
            } finally { if (test.isAlive()) test.destroyForcibly(); }
            if (stopped.getAsBoolean()) throw new CancellationException();
            List<String> invocation = new ArrayList<>(command);
            invocation.addAll(List.of("-u", "-B", directory.resolve(entry).toString()));
            ProcessBuilder builder = new ProcessBuilder(invocation).directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("PYTHONIOENCODING", "utf-8");
            return builder.start();
        }
        throw new IOException(I18n.get("wechat.collect.python"));
    }
}
