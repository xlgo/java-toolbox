package com.aqishi.toolbox.feature.security.infra.acme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-memory ACME server on 127.0.0.1 with scripted state transitions. Verifies each
 * JWS (alg, single-use nonce, url, kid/jwk, RSA signature, POST-as-GET payloads) and
 * records protocol violations instead of failing silently.
 */
final class FakeAcmeServer implements AutoCloseable {

    // ---- script (set before the flow starts) ----
    /** Authorization polls after the challenge was triggered before it turns valid (or invalid). */
    int pollsUntilFinal = 2;
    /** Retry-After header sent on pending authorization polls, or null. */
    String authzRetryAfter = "2";
    /** Index of the authorization that fails, -1 for none. */
    int failAuthzIndex = -1;
    /** Challenge error object for the failing authorization. */
    String failError = "{\"type\":\"urn:ietf:params:acme:error:unauthorized\","
            + "\"detail\":\"Incorrect TXT record found at _acme-challenge.example.com\",\"status\":403}";
    /** Authorizations that are already valid when the order is created (reused authz). */
    boolean preValid = false;
    /** Order polls answered with "processing" after finalize. */
    int orderProcessingPolls = 1;
    String orderRetryAfter = "1";
    /** When set, the order turns invalid after finalize with this error. */
    String orderError = null;
    /** Reject the next N requests to this path with badNonce. */
    String badNoncePath = null;
    int badNonceCount = 0;
    /** When set, newOrder answers 429 with this Retry-After. */
    String rateLimitRetryAfter = null;

    // ---- observations ----
    final List<String> violations = Collections.synchronizedList(new ArrayList<>());
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger finalizeCalls = new AtomicInteger();
    final AtomicInteger requests = new AtomicInteger();

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpServer server;
    private final String base;
    private final Set<String> nonces = new HashSet<>();
    private int nonceSeq;
    private PublicKey accountKey;
    private final List<String> identifiers = new ArrayList<>();
    private final List<String> authzStatus = new ArrayList<>();
    private final List<Boolean> triggered = new ArrayList<>();
    private final List<Integer> pollsSinceTrigger = new ArrayList<>();
    private boolean finalized;
    private int orderPollsAfterFinalize;

    FakeAcmeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", this::handle);
        server.start();
    }

    String directoryUrl() {
        return base + "/directory";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private synchronized void handle(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        try {
            if (path.equals("/directory")) {
                ObjectNode dir = mapper.createObjectNode();
                dir.put("newNonce", base + "/new-nonce");
                dir.put("newAccount", base + "/new-account");
                dir.put("newOrder", base + "/new-order");
                send(ex, 200, dir, null);
                return;
            }
            if (path.equals("/new-nonce")) {
                ex.getResponseHeaders().set("Replay-Nonce", newNonce());
                ex.getResponseHeaders().set("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, -1);
                ex.close();
                return;
            }
            if (!"POST".equals(ex.getRequestMethod())) {
                violations.add("non-POST request to " + path);
                send(ex, 405, problem("malformed", "POST required"), null);
                return;
            }
            JsonNode jws = mapper.readTree(ex.getRequestBody().readAllBytes());
            JsonNode prot = mapper.readTree(Base64.getUrlDecoder().decode(jws.path("protected").asText()));
            String payloadB64 = jws.path("payload").asText();
            String nonce = prot.path("nonce").asText(null);
            if (badNoncePath != null && badNoncePath.equals(path) && badNonceCount > 0) {
                badNonceCount--;
                nonces.remove(nonce);
                events.add("badNonce " + path);
                send(ex, 400, problem("badNonce", "JWS has an invalid anti-replay nonce"), null);
                return;
            }
            if (nonce == null || !nonces.remove(nonce)) {
                violations.add("unknown or reused nonce on " + path);
                send(ex, 400, problem("badNonce", "unknown nonce"), null);
                return;
            }
            checkHeader(path, prot, jws, payloadB64);
            route(ex, path, payloadB64);
        } catch (RuntimeException e) {
            violations.add("server error on " + path + ": " + e);
            send(ex, 500, problem("serverInternal", String.valueOf(e)), null);
        }
    }

    private void checkHeader(String path, JsonNode prot, JsonNode jws, String payloadB64) {
        if (!"RS256".equals(prot.path("alg").asText())) {
            violations.add("alg is not RS256 on " + path);
        }
        if (!(base + path).equals(prot.path("url").asText())) {
            violations.add("url header " + prot.path("url").asText() + " != " + base + path);
        }
        boolean hasJwk = prot.has("jwk");
        boolean hasKid = prot.has("kid");
        if (path.equals("/new-account")) {
            if (!hasJwk || hasKid) {
                violations.add("newAccount must use jwk, not kid");
            }
            JsonNode jwk = prot.get("jwk");
            try {
                BigInteger n = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()));
                BigInteger e = new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()));
                accountKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            } catch (Exception bad) {
                violations.add("bad jwk: " + bad);
            }
        } else if (hasJwk || !(base + "/acct/1").equals(prot.path("kid").asText())) {
            violations.add("request to " + path + " must use kid of the account");
        }
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(accountKey);
            sig.update((jws.path("protected").asText() + "." + payloadB64).getBytes(StandardCharsets.US_ASCII));
            if (!sig.verify(Base64.getUrlDecoder().decode(jws.path("signature").asText()))) {
                violations.add("bad signature on " + path);
            }
        } catch (Exception bad) {
            violations.add("signature check failed on " + path + ": " + bad);
        }
    }

    private void route(HttpExchange ex, String path, String payloadB64) throws IOException {
        if (path.equals("/new-account")) {
            ObjectNode acct = mapper.createObjectNode().put("status", "valid");
            send(ex, 201, acct, base + "/acct/1");
        } else if (path.equals("/new-order")) {
            if (rateLimitRetryAfter != null) {
                ex.getResponseHeaders().set("Retry-After", rateLimitRetryAfter);
                send(ex, 429, problem("rateLimited", "too many certificates already issued for example.com"), null);
                return;
            }
            JsonNode req = payload(payloadB64);
            for (JsonNode id : req.path("identifiers")) {
                identifiers.add(id.path("value").asText());
                authzStatus.add(preValid ? "valid" : "pending");
                triggered.add(false);
                pollsSinceTrigger.add(0);
            }
            send(ex, 201, order(), base + "/order/1");
        } else if (path.startsWith("/authz/")) {
            requireEmpty(path, payloadB64);
            int i = Integer.parseInt(path.substring(7));
            if (triggered.get(i) && "pending".equals(authzStatus.get(i))) {
                int polls = pollsSinceTrigger.get(i) + 1;
                pollsSinceTrigger.set(i, polls);
                if (polls > pollsUntilFinal) {
                    authzStatus.set(i, i == failAuthzIndex ? "invalid" : "valid");
                }
            }
            events.add("authz " + i + " " + authzStatus.get(i));
            if ("pending".equals(authzStatus.get(i)) && authzRetryAfter != null) {
                ex.getResponseHeaders().set("Retry-After", authzRetryAfter);
            }
            send(ex, 200, authz(i), null);
        } else if (path.startsWith("/chall/")) {
            if (!"e30".equals(payloadB64)) {
                violations.add("challenge must be triggered with {} but got " + payloadB64);
            }
            int index = Integer.parseInt(path.substring(7));
            triggered.set(index % 100, true);
            events.add("trigger " + (index % 100));
            send(ex, 200, challenge(index), null);
        } else if (path.equals("/finalize/1")) {
            finalizeCalls.incrementAndGet();
            events.add("finalize");
            if (!authzStatus.stream().allMatch("valid"::equals)) {
                violations.add("finalize before all authorizations were valid: " + authzStatus);
                send(ex, 403, problem("orderNotReady", "Order's status (\"pending\") is not acceptable for finalization"), null);
                return;
            }
            if (payload(payloadB64).path("csr").asText().isEmpty()) {
                violations.add("finalize without csr");
            }
            finalized = true;
            send(ex, 200, order(), null);
        } else if (path.equals("/order/1")) {
            requireEmpty(path, payloadB64);
            if (finalized) {
                orderPollsAfterFinalize++;
            }
            ObjectNode order = order();
            if ("processing".equals(order.path("status").asText()) && orderRetryAfter != null) {
                ex.getResponseHeaders().set("Retry-After", orderRetryAfter);
            }
            events.add("order " + order.path("status").asText());
            send(ex, 200, order, null);
        } else if (path.equals("/cert/1")) {
            requireEmpty(path, payloadB64);
            events.add("certificate");
            byte[] pem = ("-----BEGIN CERTIFICATE-----\nMIIBfake\n-----END CERTIFICATE-----\n")
                    .getBytes(StandardCharsets.US_ASCII);
            ex.getResponseHeaders().set("Content-Type", "application/pem-certificate-chain");
            ex.getResponseHeaders().set("Replay-Nonce", newNonce());
            ex.sendResponseHeaders(200, pem.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(pem);
            }
        } else {
            send(ex, 404, problem("malformed", "no such resource " + path), null);
        }
    }

    private void requireEmpty(String path, String payloadB64) {
        if (!payloadB64.isEmpty()) {
            violations.add("POST-as-GET to " + path + " must have an empty payload");
        }
    }

    private JsonNode payload(String payloadB64) throws IOException {
        return mapper.readTree(Base64.getUrlDecoder().decode(payloadB64));
    }

    private ObjectNode order() throws IOException {
        ObjectNode order = mapper.createObjectNode();
        String status;
        if (authzStatus.contains("invalid")) {
            status = "invalid";
        } else if (finalized) {
            if (orderError != null) {
                status = "invalid";
                order.set("error", mapper.readTree(orderError));
            } else if (orderPollsAfterFinalize <= orderProcessingPolls) {
                status = "processing";
            } else {
                status = "valid";
                order.put("certificate", base + "/cert/1");
            }
        } else {
            status = authzStatus.stream().allMatch("valid"::equals) ? "ready" : "pending";
        }
        order.put("status", status);
        ArrayNode ids = order.putArray("identifiers");
        ArrayNode auths = order.putArray("authorizations");
        for (int i = 0; i < identifiers.size(); i++) {
            ids.addObject().put("type", "dns").put("value", identifiers.get(i));
            auths.add(base + "/authz/" + i);
        }
        order.put("finalize", base + "/finalize/1");
        return order;
    }

    private ObjectNode authz(int i) throws IOException {
        String id = identifiers.get(i);
        boolean wildcard = id.startsWith("*.");
        ObjectNode authz = mapper.createObjectNode();
        authz.putObject("identifier").put("type", "dns").put("value", wildcard ? id.substring(2) : id);
        authz.put("status", authzStatus.get(i));
        if (wildcard) {
            authz.put("wildcard", true);
        }
        ArrayNode challenges = authz.putArray("challenges");
        challenges.add(challenge(i));
        ObjectNode http = challenges.addObject();
        http.put("type", "http-01").put("url", base + "/chall/" + (i + 100))
                .put("token", "http-token-" + i).put("status", "pending");
        return authz;
    }

    private ObjectNode challenge(int index) throws IOException {
        int i = index % 100;
        ObjectNode ch = mapper.createObjectNode();
        ch.put("type", index >= 100 ? "http-01" : "dns-01");
        ch.put("url", base + "/chall/" + index);
        ch.put("token", (index >= 100 ? "http-token-" : "dns-token-") + i);
        String status = authzStatus.get(i);
        if ("pending".equals(status)) {
            status = triggered.get(i) ? "processing" : "pending";
        }
        ch.put("status", status);
        if ("invalid".equals(status)) {
            ch.set("error", mapper.readTree(failError));
        }
        return ch;
    }

    private ObjectNode problem(String type, String detail) {
        return mapper.createObjectNode().put("type", AcmeProblem.ACME_PREFIX + type).put("detail", detail);
    }

    private String newNonce() {
        String nonce = "nonce-" + (++nonceSeq);
        nonces.add(nonce);
        return nonce;
    }

    private void send(HttpExchange ex, int status, JsonNode body, String location) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().set("Replay-Nonce", newNonce());
        ex.getResponseHeaders().set("Content-Type",
                status >= 400 ? "application/problem+json" : "application/json");
        if (location != null) {
            ex.getResponseHeaders().set("Location", location);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
