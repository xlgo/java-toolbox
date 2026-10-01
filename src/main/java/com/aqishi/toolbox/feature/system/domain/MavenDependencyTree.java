package com.aqishi.toolbox.feature.system.domain;

import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.*;

import java.util.*;
import java.util.regex.*;

/**
 * Offline analysis of Maven text/JSON tree output. Omitted versions remain visible instead of being
 * treated as selected.
 */
public final class MavenDependencyTree {
    public record Dependency(
            String group,
            String artifact,
            String type,
            String classifier,
            String version,
            String scope,
            String note,
            List<String> path) {
        public String key() {
            return group
                    + ":"
                    + artifact
                    + ":"
                    + type
                    + (classifier.isEmpty() ? "" : ":" + classifier);
        }

        public String coordinate() {
            return key() + ":" + version;
        }
    }

    private static final Pattern BRANCH = Pattern.compile("(?:\\+-|\\\\-|├─|└─)[─ ]? ");
    private static final Pattern COORD =
            Pattern.compile("^([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([^\\s:]+):([^\\s]+)");

    public List<Dependency> parse(String text) throws Exception {
        if (text.length() > 2_000_000)
            throw new IllegalArgumentException(I18n.get("devtools.inputLimit"));
        List<Dependency> result = new ArrayList<>();
        if (text.stripLeading().startsWith("{")) {
            JsonNode root =
                    new ObjectMapper()
                            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .readTree(text);
            json(root, new ArrayList<>(), result, 0);
        } else {
            List<String> stack = new ArrayList<>();
            for (String raw : text.split("\\R")) {
                String line =
                        raw.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "")
                                .replaceFirst("^\\s*\\[INFO] ?", "");
                Matcher branch = BRANCH.matcher(line);
                boolean child = branch.find();
                int depth = child ? branch.start() / 3 + 1 : 0;
                String value = child ? line.substring(branch.end()).trim() : line.trim();
                if (value.startsWith("(")) value = value.substring(1);
                Matcher coordinate = COORD.matcher(value);
                if (!coordinate.find()) continue;
                String token = coordinate.group().replaceAll("[)]$", "");
                String[] pieces = token.split(":", -1);
                if (pieces.length < 4 || pieces.length > 6) throw invalid();
                if (depth > stack.size()) throw invalid();
                while (stack.size() > depth) stack.remove(stack.size() - 1);
                String scope = pieces.length >= 5 ? pieces[pieces.length - 1] : "";
                String version = pieces.length >= 5 ? pieces[pieces.length - 2] : pieces[3];
                String classifier = pieces.length == 6 ? pieces[3] : "";
                String note = value.substring(coordinate.end()).replaceAll("\\)$", "").trim();
                if (version.isBlank()) throw invalid();
                Dependency d =
                        new Dependency(
                                pieces[0],
                                pieces[1],
                                pieces[2],
                                classifier,
                                version,
                                scope,
                                note,
                                List.of());
                stack.add(d.coordinate());
                result.add(
                        new Dependency(
                                d.group(),
                                d.artifact(),
                                d.type(),
                                d.classifier(),
                                d.version(),
                                d.scope(),
                                d.note(),
                                List.copyOf(stack)));
                if (result.size() > 10000) throw invalid();
            }
        }
        if (result.isEmpty()) throw invalid();
        return List.copyOf(result);
    }

    private void json(JsonNode node, List<String> parents, List<Dependency> out, int depth) {
        if (depth > 128 || out.size() >= 10000) throw invalid();
        for (String field : List.of("groupId", "artifactId", "version"))
            if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
                throw invalid();
        String classifier = node.path("classifier").asText("");
        String note = node.path("omitted").asBoolean(false) ? "omitted" : "";
        Dependency d =
                new Dependency(
                        node.path("groupId").asText(),
                        node.path("artifactId").asText(),
                        node.path("type").asText("jar"),
                        classifier,
                        node.path("version").asText(),
                        node.path("scope").asText(""),
                        note,
                        List.of());
        List<String> path = new ArrayList<>(parents);
        path.add(d.coordinate());
        out.add(
                new Dependency(
                        d.group(),
                        d.artifact(),
                        d.type(),
                        d.classifier(),
                        d.version(),
                        d.scope(),
                        d.note(),
                        List.copyOf(path)));
        for (JsonNode child : node.path("children")) json(child, path, out, depth + 1);
    }

    public String report(List<Dependency> dependencies, String filter) {
        String search = filter.trim().toLowerCase(Locale.ROOT);
        Map<String, List<Dependency>> groups = new TreeMap<>();
        for (Dependency d : dependencies)
            if (d.path().size() > 1 && d.coordinate().toLowerCase(Locale.ROOT).contains(search))
                groups.computeIfAbsent(d.key(), k -> new ArrayList<>()).add(d);
        StringBuilder out = new StringBuilder();
        for (var entry : groups.entrySet()) {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException();
            if (out.length() > 4_000_000)
                throw new IllegalArgumentException(I18n.get("devtools.resultLimit"));
            Set<String> versions = new LinkedHashSet<>();
            for (Dependency d : entry.getValue()) versions.add(d.version());
            out.append(entry.getKey())
                    .append("\n")
                    .append(
                            I18n.get(
                                    versions.size() > 1 ? "maven.multiple" : "maven.version",
                                    String.join(", ", versions)))
                    .append('\n');
            for (Dependency d : entry.getValue())
                out.append("  ")
                        .append(String.join(" → ", d.path()))
                        .append(d.scope().isBlank() ? "" : " [" + d.scope() + "]")
                        .append(d.note().isBlank() ? "" : " (" + d.note() + ")")
                        .append('\n');
            if (versions.size() > 1) {
                Dependency d = entry.getValue().get(0);
                out.append(I18n.get("maven.exclusionHint"))
                        .append("\n<exclusions>\n  <exclusion>\n    <groupId>")
                        .append(xml(d.group()))
                        .append("</groupId>\n    <artifactId>")
                        .append(xml(d.artifact()))
                        .append("</artifactId>\n  </exclusion>\n</exclusions>\n");
            }
            out.append('\n');
        }
        return out.length() == 0 ? I18n.get("maven.noMatch") : out.toString();
    }

    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(I18n.get("maven.invalid"));
    }
}
