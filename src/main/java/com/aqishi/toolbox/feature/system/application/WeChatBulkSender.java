package com.aqishi.toolbox.feature.system.application;

import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A single sender process owns recipient checks, window state, and receipt verification. */
public class WeChatBulkSender implements AutoCloseable {
    public record Request(String recipient, String text) { }
    public record Result(int index, String outcome, String reason, String serverId) { }
    public interface Listener {
        void progress(int index, String status);
        void result(Result result);
    }
    private static final List<String> SCRIPTS = List.of("wechat_send.py", "wechat_send_ui.py", "wechat_send_store.py", "wechat_contacts.py");
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final Object inputLock = new Object();

    public void send(List<Request> jobs, int intervalMillis, Listener listener) throws Exception {
        if (jobs.isEmpty() || jobs.size() > 1000 || intervalMillis < 500 || intervalMillis > 60000)
            throw new IllegalArgumentException(I18n.get("wechat.send.invalid"));
        Path directory = Files.createTempDirectory("toolbox-wechat-send-");
        try {
            for (String name : SCRIPTS) {
                InputStream resource = getClass().getResourceAsStream("/tools/" + name);
                try (InputStream source = resource == null ? Files.newInputStream(Path.of("tools", name)) : resource) {
                    Files.copy(source, directory.resolve(name));
                }
            }
            if (stopped.get()) throw new CancellationException();
            Process helper = startHelper(directory);
            process.set(helper);
            synchronized (inputLock) {
                helper.getOutputStream().write((Json.mapper().writeValueAsString(Map.of("jobs", jobs, "interval_ms", intervalMillis)) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                helper.getOutputStream().flush();
            }
            if (stopped.get()) requestStop(helper);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8))) {
                readProtocol(reader, jobs.size(), listener);
            }
            if (!helper.waitFor(10, TimeUnit.SECONDS)) throw new IOException(I18n.get("wechat.collect.exitTimeout"));
            if (helper.exitValue() != 0) throw new IOException(I18n.get("wechat.send.failed"));
        } finally {
            Process helper = process.getAndSet(null);
            if (helper != null && helper.isAlive()) terminate(helper);
            for (String name : SCRIPTS) Files.deleteIfExists(directory.resolve(name));
            Files.deleteIfExists(directory);
        }
    }

    protected Process startHelper(Path directory) throws Exception {
        return WeChatPythonRuntime.start(directory, "wechat_send.py", stopped::get);
    }

    static void readProtocol(BufferedReader reader, int count, Listener listener) throws IOException {
        boolean completed = false;
        int confirmed = 0;
        Set<Integer> results = new HashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode event;
            try { event = Json.mapper().readTree(line); }
            catch (Exception invalid) { throw new IOException(I18n.get("wechat.send.protocol")); }
            if (completed) throw new IOException(I18n.get("wechat.send.protocol"));
            switch (event.path("type").asText()) {
                case "status" -> listener.progress(-1, event.path("status").asText());
                case "progress" -> {
                    int index = event.path("index").asInt(-1);
                    if (index < 0 || index >= count || results.contains(index)) throw new IOException(I18n.get("wechat.send.protocol"));
                    listener.progress(index, event.path("status").asText());
                }
                case "result" -> {
                    int index = event.path("index").asInt(-1);
                    String outcome = event.path("outcome").asText();
                    if (index < 0 || index >= count || !results.add(index)
                            || !Set.of("acknowledged", "pending", "uncertain", "failed", "cancelled").contains(outcome))
                        throw new IOException(I18n.get("wechat.send.protocol"));
                    String serverId = event.path("server_id").asText();
                    if ("acknowledged".equals(outcome)) {
                        if (event.path("status").asInt(-1) != 2 || !serverId.matches("[1-9][0-9]*"))
                            throw new IOException(I18n.get("wechat.send.protocol"));
                        confirmed++;
                    }
                    listener.result(new Result(index, outcome, event.path("reason").asText(), serverId));
                }
                case "error" -> throw new IOException(event.path("error").asText(I18n.get("wechat.send.failed")));
                case "complete" -> {
                    if (event.path("total").asInt(-1) != count || event.path("confirmed").asInt(-1) != confirmed
                            || (!event.path("cancelled").asBoolean() && results.size() != count))
                        throw new IOException(I18n.get("wechat.send.protocol"));
                    completed = true;
                }
                default -> throw new IOException(I18n.get("wechat.send.protocol"));
            }
        }
        if (!completed) throw new IOException(I18n.get("wechat.send.incomplete"));
    }

    private void requestStop(Process helper) {
        try {
            synchronized (inputLock) {
                helper.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                helper.getOutputStream().flush();
            }
        } catch (IOException ignored) { }
    }

    private static void terminate(Process helper) {
        helper.descendants().forEach(ProcessHandle::destroyForcibly);
        helper.destroyForcibly();
    }

    @Override public void close() {
        if (!stopped.compareAndSet(false, true)) return;
        Process helper = process.get();
        if (helper == null || !helper.isAlive()) return;
        Thread stopper = DaemonThreads.factory("wechat-send-stop").newThread(() -> {
            requestStop(helper);
            try { if (!helper.waitFor(5, TimeUnit.SECONDS)) terminate(helper); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); terminate(helper); }
        });
        stopper.start();
    }
}
