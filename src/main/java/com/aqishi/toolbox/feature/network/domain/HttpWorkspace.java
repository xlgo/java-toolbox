package com.aqishi.toolbox.feature.network.domain;

import com.aqishi.toolbox.util.I18n;
import java.util.*;
import java.util.regex.*;

/** Saved templates stay unresolved; environment secrets are substituted only for one send operation. */
public final class HttpWorkspace {
    private HttpWorkspace() { }
    public record Request(String name, String method, String url, String headers, String body, boolean favorite) {
        public Request {
            name = Objects.requireNonNullElse(name, "");
            method = Objects.requireNonNullElse(method, "GET");
            url = Objects.requireNonNullElse(url, "");
            headers = Objects.requireNonNullElse(headers, "");
            body = Objects.requireNonNullElse(body, "");
        }
        @Override public String toString() { return (favorite ? "★ " : "") + name; }
    }
    public record Variable(String value, boolean secret) {
        public Variable { Objects.requireNonNull(value); }
    }
    public record Environment(String name, Map<String, Variable> variables) {
        public Environment {
            if (name == null || name.isBlank()) throw new IllegalArgumentException(I18n.get("http.workspace.nameRequired"));
            variables = Collections.unmodifiableMap(new LinkedHashMap<>(variables));
            for (var entry : variables.entrySet()) {
                if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_.-]*") || entry.getValue() == null)
                    throw new IllegalArgumentException(I18n.get("http.workspace.variableName"));
            }
        }
        @Override public String toString() { return name; }
    }
    public record Document(int version, List<Request> requests, List<Environment> environments, List<Request> history) {
        public Document {
            if (version != 1) throw new IllegalArgumentException(I18n.get("http.workspace.version"));
            requests = List.copyOf(requests); environments = List.copyOf(environments); history = List.copyOf(history);
            if (requests.size() > 500 || environments.size() > 100 || history.size() > 50)
                throw new IllegalArgumentException(I18n.get("http.workspace.limit"));
        }
        public static Document empty() { return new Document(1, List.of(), List.of(), List.of()); }
        public Document save(Request request) {
            if (request.name().isBlank()) throw new IllegalArgumentException(I18n.get("http.workspace.nameRequired"));
            var list = new ArrayList<>(requests); list.removeIf(r -> r.name().equals(request.name())); list.add(request);
            return new Document(version, list, environments, history);
        }
        public Document environment(Environment env) {
            var list = new ArrayList<>(environments); list.removeIf(e -> e.name().equals(env.name())); list.add(env);
            return new Document(version, requests, list, history);
        }
        public Document remember(Request request) {
            var list = new ArrayList<>(history); list.remove(request); list.add(0, request);
            return new Document(version, requests, environments, list.subList(0, Math.min(50, list.size())));
        }
        public Document deleteRequest(String name) {
            return new Document(version, requests.stream().filter(r -> !r.name().equals(name)).toList(), environments, history);
        }
        public Document deleteEnvironment(String name) {
            return new Document(version, requests, environments.stream().filter(e -> !e.name().equals(name)).toList(), history);
        }
    }
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z_][A-Za-z0-9_.-]*)}}");
    public static Request resolve(Request request, Environment env, boolean redact) {
        Map<String, Variable> vars = env == null ? Map.of() : env.variables();
        String url = expand(request.url(), vars, redact);
        // Validate substitutions, not template line separators. A header variable must never add a new header.
        Matcher matcher = PLACEHOLDER.matcher(request.headers());
        while (matcher.find()) {
            Variable variable = vars.get(matcher.group(1));
            if (variable != null && (variable.value().contains("\r") || variable.value().contains("\n")))
                throw new IllegalArgumentException(I18n.get("http.workspace.headerLine"));
        }
        return new Request(request.name(), request.method(), url, expand(request.headers(), vars, redact),
                expand(request.body(), vars, redact), request.favorite());
    }
    private static String expand(String template, Map<String, Variable> variables, boolean redact) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            Variable value = variables.get(matcher.group(1));
            if (value == null) throw new IllegalArgumentException(I18n.get("http.workspace.undefined", matcher.group(1)));
            matcher.appendReplacement(result, Matcher.quoteReplacement(redact && value.secret() ? "******" : value.value()));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
