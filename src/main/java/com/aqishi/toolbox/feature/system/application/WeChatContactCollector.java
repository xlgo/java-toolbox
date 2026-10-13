package com.aqishi.toolbox.feature.system.application;

import com.aqishi.toolbox.feature.system.domain.WeChatContactReader.ContactInfo;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Owns a single read-only contacts helper; results are installed only after successful completion. */
public class WeChatContactCollector implements AutoCloseable {
    private final AtomicReference<Process> process = new AtomicReference<>();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public List<ContactInfo> collect(Consumer<String> progress) throws Exception {
        Path directory = Files.createTempDirectory("toolbox-wechat-reader-");
        try {
            for (String name : List.of("wechat_export.py", "wechat_contacts.py")) {
                try (InputStream source = script(name)) {
                    Files.copy(source, directory.resolve(name));
                }
            }
            checkStopped();
            Process helper = startHelper(directory);
            process.set(helper);
            if (stopped.get()) {
                terminate(helper);
                throw new CancellationException();
            }
            List<ContactInfo> contacts;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8))) {
                contacts = readProtocol(input, progress);
            }
            if (!helper.waitFor(10, TimeUnit.SECONDS)) throw new IOException(I18n.get("wechat.collect.exitTimeout"));
            checkStopped();
            if (helper.exitValue() != 0) throw new IOException(I18n.get("wechat.collect.failed"));
            return contacts;
        } finally {
            Process helper = process.getAndSet(null);
            if (helper != null && helper.isAlive()) terminate(helper);
            for (String name : List.of("wechat_export.py", "wechat_contacts.py")) Files.deleteIfExists(directory.resolve(name));
            // Python may create bytecode files; keep cleanup limited to this known private directory.
            Path cache = directory.resolve("__pycache__");
            if (Files.isDirectory(cache)) {
                try (var files = Files.list(cache)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(cache);
            }
            Files.deleteIfExists(directory);
        }
    }

    protected Process startHelper(Path directory) throws Exception {
        return WeChatPythonRuntime.start(directory, "wechat_export.py", stopped::get);
    }

    static List<ContactInfo> readProtocol(BufferedReader input, Consumer<String> progress) throws IOException {
        Map<String, ContactInfo> contacts = new LinkedHashMap<>();
        boolean complete = false;
        String line;
        while ((line = input.readLine()) != null) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            if (line.isBlank()) continue;
            if (line.length() > 128 * 1024) throw new IOException(I18n.get("wechat.collect.protocol"));
            JsonNode event;
            try { event = Json.mapper().readTree(line); }
            catch (Exception invalid) { throw new IOException(I18n.get("wechat.collect.protocol")); }
            String type = event.path("type").asText();
            switch (type) {
                case "status" -> progress.accept(event.path("status").asText());
                case "error" -> throw new IOException(event.path("error").asText(I18n.get("wechat.collect.failed")));
                case "cancelled" -> throw new CancellationException();
                case "contact" -> {
                    if (complete) throw new IOException(I18n.get("wechat.collect.protocol"));
                    String username = event.path("username").asText().trim();
                    if (username.isEmpty() || contacts.containsKey(username))
                        throw new IOException(I18n.get("wechat.collect.protocol"));
                    ContactInfo contact = new ContactInfo();
                    contact.username = username;
                    contact.alias = event.path("wechat_id").asText();
                    contact.nickname = event.path("nickname").asText();
                    contact.remark = event.path("remark").asText();
                    contact.tag = event.path("tag").asText();
                    contact.gender = event.path("gender").asInt(0);
                    contact.type = event.path("contact_type").asInt(3);
                    contact.avatarUrl = event.path("avatar_url").asText();
                    contacts.put(username, contact);
                }
                case "complete" -> {
                    if (complete || event.path("count").asInt(-1) != contacts.size())
                        throw new IOException(I18n.get("wechat.collect.protocol"));
                    complete = true;
                }
                default -> throw new IOException(I18n.get("wechat.collect.protocol"));
            }
        }
        if (!complete) throw new IOException(I18n.get("wechat.collect.incomplete"));
        return List.copyOf(contacts.values());
    }

    private static InputStream script(String name) throws IOException {
        InputStream resource = WeChatContactCollector.class.getResourceAsStream("/tools/" + name);
        return resource != null ? resource : Files.newInputStream(Path.of("tools", name));
    }

    private void checkStopped() { if (stopped.get()) throw new CancellationException(); }

    private static void terminate(Process helper) {
        helper.descendants().forEach(ProcessHandle::destroyForcibly);
        helper.destroyForcibly();
    }

    @Override public void close() {
        stopped.set(true);
        Process helper = process.getAndSet(null);
        if (helper != null && helper.isAlive()) terminate(helper);
    }
}
