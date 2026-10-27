package com.aqishi.toolbox.feature.data.application;

import com.aqishi.toolbox.feature.data.domain.EtcdModels;
import com.aqishi.toolbox.feature.data.domain.EtcdModels.*;
import com.aqishi.toolbox.infra.etcd.*;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** v2 REST and v3 gateway operations. Mutations are never retried after transport failures. */
public class EtcdClient implements AutoCloseable {
    private final Options options;
    private final EtcdTransport transport;
    private String gateway = "/v3", authorization = "", serverVersion = "";
    public EtcdClient(Options options, EtcdTransport transport) { this.options = options; this.transport = transport; }
    public static EtcdClient open(Options options) throws Exception { return new EtcdClient(options, EtcdHttpTransport.open(options)); }
    public Api api() { return options.api(); }
    public String connect() throws IOException {
        if (api() == Api.V2 && !options.username().isEmpty()) authorization = "Basic " + Base64.getEncoder().encodeToString(
                (options.username() + ":" + options.password()).getBytes(StandardCharsets.UTF_8));
        JsonNode version = request("GET", "/version", "", new byte[0]);
        serverVersion = version.path("etcdserver").asText();
        if (!serverVersion.matches("[0-9]+\\.[0-9]+.*")) throw new IOException(I18n.get("etcd.error.notEtcd"));
        if (api() == Api.V3) {
            String[] parts = serverVersion.split("\\.");
            int major = Integer.parseInt(parts[0]), minor = Integer.parseInt(parts[1]);
            if (major < 3) throw new IOException(I18n.get("etcd.error.apiUnavailable"));
            gateway = major == 3 && minor <= 2 ? "/v3alpha" : major == 3 && minor == 3 ? "/v3beta" : "/v3";
            if (!options.username().isEmpty()) authenticate();
        }
        return serverVersion;
    }

    private void authenticate() throws IOException {
        authorization = "";
        JsonNode result = post("/auth/authenticate", Map.of("name", options.username(), "password", options.password()), false);
        String token = result.path("token").asText();
        if (token.isEmpty()) throw new IOException(I18n.get("etcd.error.auth"));
        authorization = token;
    }

    public Page list(byte[] prefix, int limit, byte[] start, long revision) throws IOException {
        if (limit < 1 || limit > 10000) throw new IllegalArgumentException("etcd.error.limit");
        if (api() == Api.V2) {
            String path = EtcdModels.v2Path(prefix);
            JsonNode result;
            try { result = request("GET", v2Url(path) + "?recursive=false&sorted=true", "", new byte[0]); }
            catch (EtcdException error) { if (error.missingKey()) return new Page(List.of(), null, 0, false); throw explain(error); }
            JsonNode root = result.path("node");
            ArrayList<Entry> values = new ArrayList<>();
            if (root.path("dir").asBoolean()) {
                for (JsonNode node : root.path("nodes")) { if (values.size() == limit) break; values.add(v2Entry(node)); }
                return new Page(values, null, 0, root.path("nodes").size() > limit);
            }
            return new Page(List.of(v2Entry(root)), null, 0, false);
        }
        byte[] first = start == null ? (prefix.length == 0 ? new byte[]{0} : prefix) : start;
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("key", b64(first)); query.put("range_end", b64(EtcdModels.prefixEnd(prefix)));
        query.put("limit", Integer.toString(limit)); query.put("sort_order", "ASCEND"); query.put("sort_target", "KEY");
        if (revision > 0) query.put("revision", Long.toString(revision));
        JsonNode result = post("/kv/range", query);
        List<Entry> values = new ArrayList<>();
        for (JsonNode node : result.path("kvs")) values.add(v3Entry(node));
        boolean more = result.path("more").asBoolean();
        if (more && values.isEmpty()) throw new IOException(I18n.get("etcd.error.response"));
        long snapshot = revision > 0 ? revision : result.path("header").path("revision").asLong();
        if (snapshot <= 0) throw new IOException(I18n.get("etcd.error.response"));
        return new Page(values, more ? EtcdModels.after(values.get(values.size() - 1).key()) : null, snapshot, more);
    }

    public Entry get(byte[] key) throws IOException {
        requireKey(key);
        if (api() == Api.V2) {
            try { return v2Entry(request("GET", v2Url(EtcdModels.v2Path(key)), "", new byte[0]).path("node")); }
            catch (EtcdException error) { if (error.missingKey()) return null; throw explain(error); }
        }
        JsonNode result = post("/kv/range", Map.of("key", b64(key)));
        return result.path("kvs").isEmpty() ? null : v3Entry(result.path("kvs").get(0));
    }

    /** expected=0 means create only, positive values mean compare-and-swap. ttl=-1 preserves the current lease. */
    public void put(byte[] key, byte[] value, long expected, long ttl, boolean directory) throws IOException {
        requireKey(key);
        if (expected < 0 || ttl < -1 || ttl > 31536000 || value.length > 1024 * 1024)
            throw new IllegalArgumentException("etcd.error.value");
        if (api() == Api.V2) {
            String path = EtcdModels.v2Path(key);
            if (directory && expected != 0) throw new IOException(I18n.get("etcd.error.directory"));
            if (ttl == -1 && expected != 0) {
                Entry current = get(key);
                if (current == null || current.modified() != expected) throw conflict();
                ttl = Math.max(0, current.ttl());
            }
            String form = directory ? "dir=true" : "value=" + form(EtcdModels.encode(value, Encoding.UTF8));
            form += expected == 0 ? "&prevExist=false" : "&prevIndex=" + expected;
            if (ttl > 0) form += "&ttl=" + ttl;
            // v2 update without ttl clears a previous TTL.
            try { request("PUT", v2Url(path), "application/x-www-form-urlencoded", form.getBytes(StandardCharsets.UTF_8)); }
            catch (EtcdException error) { throw explain(error); }
            return;
        }
        if (directory) throw new IOException(I18n.get("etcd.error.v3Directory"));
        Map<String,Object> put = new LinkedHashMap<>();
        String grantedLease = null;
        put.put("key", b64(key)); put.put("value", b64(value));
        if (ttl == -1 && expected > 0) put.put("ignore_lease", true);
        if (ttl > 0) {
            JsonNode lease = post("/lease/grant", Map.of("TTL", Long.toString(ttl)));
            String id = lease.path("ID").asText();
            if (id.isEmpty() || id.equals("0")) throw new IOException(I18n.get("etcd.error.response"));
            put.put("lease", id);
            grantedLease = id;
        }
        // A transport timeout is ambiguous: the lease may already belong to the newly written key.
        JsonNode result = transaction(key, expected, "request_put", put);
        if (!result.path("succeeded").asBoolean()) {
            if (grantedLease != null) {
                try { post("/lease/revoke", Map.of("ID", grantedLease)); } catch (IOException ignored) { }
            }
            throw conflict();
        }
    }

    public void delete(Entry entry) throws IOException {
        requireKey(entry.key());
        if (api() == Api.V2) {
            if (entry.directory()) throw new IOException(I18n.get("etcd.error.directory"));
            try { request("DELETE", v2Url(EtcdModels.v2Path(entry.key())) + "?prevIndex=" + entry.modified(), "", new byte[0]); }
            catch (EtcdException error) { throw explain(error); }
        } else {
            JsonNode result = transaction(entry.key(), entry.modified(), "request_delete_range", Map.of("key", b64(entry.key())));
            if (!result.path("succeeded").asBoolean()) throw conflict();
        }
    }
    private JsonNode transaction(byte[] key, long expected, String operation, Map<String,Object> body) throws IOException {
        Map<String,Object> compare = Map.of("key", b64(key), "result", "EQUAL", "target", expected == 0 ? "VERSION" : "MOD",
                expected == 0 ? "version" : "mod_revision", Long.toString(expected));
        return post("/kv/txn", Map.of("compare", List.of(compare), "success", List.of(Map.of(operation, body)), "failure", List.of()));
    }
    private JsonNode post(String path, Object body) throws IOException { return post(path, body, true); }
    private JsonNode post(String path, Object body, boolean refreshAuth) throws IOException {
        byte[] data = Json.mapper().writeValueAsBytes(body);
        try { return request("POST", gateway + path, "application/json", data); }
        catch (EtcdException error) {
            if (refreshAuth && (error.status() == 401 || error.code() == 16) && !options.username().isEmpty()) {
                authenticate(); return request("POST", gateway + path, "application/json", data);
            }
            throw explain(error);
        }
    }
    private JsonNode request(String method, String path, String contentType, byte[] data) throws IOException {
        return transport.request(method, path, contentType, data, authorization);
    }
    private static IOException explain(EtcdException error) {
        if (error.conflict()) return conflict();
        if (error.status() == 404 && !error.missingKey()) return new IOException(I18n.get("etcd.error.apiUnavailable"), error);
        if (error.code() == 11) return new IOException(I18n.get("etcd.error.compacted"), error);
        if (error.status() == 401 || error.status() == 403 || error.code() == 16 || error.code() == 7)
            return new IOException(I18n.get("etcd.error.auth"), error);
        return error;
    }
    private static IOException conflict() { return new IOException(I18n.get("etcd.error.conflict")); }
    private static Entry v2Entry(JsonNode node) throws IOException {
        if (!node.hasNonNull("key") || !node.hasNonNull("modifiedIndex")) throw new IOException(I18n.get("etcd.error.response"));
        return new Entry(node.path("key").asText().getBytes(StandardCharsets.UTF_8), node.path("value").asText("").getBytes(StandardCharsets.UTF_8),
                node.path("createdIndex").asLong(), node.path("modifiedIndex").asLong(), 0, 0,
                node.path("ttl").asLong(0), node.path("dir").asBoolean());
    }
    private static Entry v3Entry(JsonNode node) throws IOException {
        try {
            byte[] key = Base64.getDecoder().decode(node.path("key").asText());
            if (key.length == 0 || node.path("mod_revision").asLong() <= 0) throw new IllegalArgumentException();
            return new Entry(key, Base64.getDecoder().decode(node.path("value").asText()), node.path("create_revision").asLong(),
                    node.path("mod_revision").asLong(), node.path("version").asLong(), node.path("lease").asLong(), -1, false);
        } catch (IllegalArgumentException error) { throw new IOException(I18n.get("etcd.error.response")); }
    }
    private static void requireKey(byte[] key) { if (key.length == 0) throw new IllegalArgumentException("etcd.error.key"); }
    private static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static String form(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String v2Url(String path) {
        return "/v2/keys" + Arrays.stream(path.split("/", -1)).map(EtcdClient::form).map(part -> part.replace("+", "%20"))
                .reduce((left, right) -> left + "/" + right).orElse("");
    }
    public void cancel() { transport.cancel(); }
    @Override public void close() { authorization = ""; transport.close(); }
}
