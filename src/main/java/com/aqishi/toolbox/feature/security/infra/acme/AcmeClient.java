package com.aqishi.toolbox.feature.security.infra.acme;

import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 遵守 RFC 8555 (ACME v2 协议) 的轻量级 Java 客户端
 *
 * <p>Issuance order: trigger every pending challenge with {@code {}}, poll each
 * authorization until {@code valid} (failing with the challenge error when it turns
 * {@code invalid}), wait for the order to be {@code ready}, finalize with the CSR,
 * poll the order until {@code valid}, then download the chain. Polling honours
 * {@code Retry-After} through {@link AcmePoller}; a {@code badNonce} is retried once.</p>
 */
public class AcmeClient {

    public static final String LETSENCRYPT_PROD = "https://acme-v02.api.letsencrypt.org/directory";
    public static final String LETSENCRYPT_STAGE = "https://acme-staging-v02.api.letsencrypt.org/directory";
    public static final String ZEROSSL_PROD = "https://acme.zerossl.com/v2/DV90";

    private final String directoryUrl;
    private final ObjectMapper mapper = Json.mapper();

    private String newNonceUrl;
    private String newAccountUrl;
    private String newOrderUrl;

    private String accountKid;
    private String lastNonce;

    private Consumer<String> logger;
    private AcmePoller poller = AcmePoller.defaults();

    public AcmeClient(String directoryUrl) {
        this.directoryUrl = directoryUrl;
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
    }

    /** Replaces the polling policy (clock, sleeper, delays, timeout); mainly for tests. */
    public void setPoller(AcmePoller poller) {
        this.poller = Objects.requireNonNull(poller);
    }

    public String getAccountKid() {
        return accountKid;
    }

    private void log(String message) {
        if (logger != null) {
            logger.accept(message);
        }
    }

    /**
     * 初始化：获取 Directory 目录与首个 Nonce
     */
    public void init() throws Exception {
        log(I18n.get("tool.cert.acme.log.directory", directoryUrl));
        JsonNode dirJson = readJson(httpGet(directoryUrl), "directory");
        newNonceUrl = requiredText(dirJson, "newNonce", "directory");
        newAccountUrl = requiredText(dirJson, "newAccount", "directory");
        newOrderUrl = requiredText(dirJson, "newOrder", "directory");
        log(I18n.get("tool.cert.acme.log.directoryOk"));
        fetchNextNonce();
    }

    /**
     * 注册或获取 ACME 账户 (newAccount)
     */
    public String registerAccount(KeyPair accountKeyPair, String email) throws Exception {
        boolean hasEmail = email != null && !email.trim().isEmpty();
        log(I18n.get("tool.cert.acme.log.account", hasEmail ? email.trim() : "-"));
        ObjectNode payload = mapper.createObjectNode();
        payload.put("termsOfServiceAgreed", true);
        if (hasEmail) {
            ArrayNode contact = payload.putArray("contact");
            contact.add("mailto:" + email.trim());
        }

        HttpResponse response = sendJwsRequest(accountKeyPair, newAccountUrl, payload, null);
        requireSuccess(response, "newAccount", 200, 201);

        accountKid = response.getHeader("Location");
        if (accountKid == null || accountKid.isEmpty()) {
            throw protocol("newAccount", "newAccount response has no Location header");
        }
        log(I18n.get("tool.cert.acme.log.accountOk", accountKid));
        return accountKid;
    }

    /**
     * ACME 订单数据传输模型
     */
    public static class AcmeOrder {
        public String orderUrl;
        public String finalizeUrl;
        public String status;
        public String certificateUrl;
        /** Problem the server attached to an invalid order, or {@code null}. */
        public AcmeProblem error;
        public List<String> authorizationUrls = new ArrayList<>();
    }

    /**
     * 提交新证书订单 (newOrder)
     */
    public AcmeOrder createOrder(KeyPair accountKeyPair, List<String> domains) throws Exception {
        log(I18n.get("tool.cert.acme.log.order", String.valueOf(domains)));
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode identifiers = payload.putArray("identifiers");
        for (String domain : domains) {
            ObjectNode idNode = identifiers.addObject();
            idNode.put("type", "dns");
            idNode.put("value", domain);
        }

        HttpResponse response = sendJwsRequest(accountKeyPair, newOrderUrl, payload, accountKid);
        requireSuccess(response, "newOrder", 200, 201);

        AcmeOrder order = new AcmeOrder();
        order.orderUrl = response.getHeader("Location");
        if (order.orderUrl == null || order.orderUrl.isEmpty()) {
            throw protocol("newOrder", "newOrder response has no Location header");
        }
        updateOrder(order, readJson(response.body, "newOrder"));
        log(I18n.get("tool.cert.acme.log.orderOk", order.status));
        return order;
    }

    private void updateOrder(AcmeOrder order, JsonNode json) {
        order.status = requiredText(json, "status", "order");
        if (json.hasNonNull("finalize")) {
            order.finalizeUrl = json.get("finalize").asText();
        }
        if (json.hasNonNull("certificate")) {
            order.certificateUrl = json.get("certificate").asText();
        }
        order.error = AcmeProblem.parse(json.get("error"));
        JsonNode auths = json.get("authorizations");
        if (auths != null && auths.isArray()) {
            order.authorizationUrls.clear();
            for (JsonNode auth : auths) {
                order.authorizationUrls.add(auth.asText());
            }
        }
    }

    /**
     * Challenge 信息模型
     */
    public static class AcmeChallenge {
        /** Identifier value; wildcard authorizations are shown as {@code *.example.com}. */
        public String domain;
        public String type; // http-01 / dns-01
        public String challengeUrl;
        public String authorizationUrl;
        /** Authorization status when fetched; {@code valid} means nothing has to be provisioned. */
        public String authorizationStatus;
        public boolean wildcard;
        public String token;
        public String status;
        public String keyAuthorization;
        public String dnsTxtRecordName;
        public String dnsTxtRecordValue;

        /** {@code true} when the CA already considers this identifier authorized (reused authorization). */
        public boolean isAlreadyValid() {
            return "valid".equalsIgnoreCase(authorizationStatus);
        }
    }

    /**
     * 获取订单的验证 Challenge 详情
     */
    public List<AcmeChallenge> getChallenges(KeyPair accountKeyPair, AcmeOrder order, String preferType) throws Exception {
        List<AcmeChallenge> result = new ArrayList<>();
        String thumbprint = calculateJwkThumbprint((RSAPublicKey) accountKeyPair.getPublic());

        for (String authUrl : order.authorizationUrls) {
            HttpResponse resp = sendJwsRequest(accountKeyPair, authUrl, null, accountKid);
            requireSuccess(resp, "authorization", 200);
            JsonNode authJson = readJson(resp.body, "authorization");

            String value = authJson.path("identifier").path("value").asText("");
            boolean wildcard = authJson.path("wildcard").asBoolean(false);
            String authStatus = authJson.path("status").asText("");
            String domain = wildcard && !value.startsWith("*.") ? "*." + value : value;

            JsonNode chNode = findChallenge(authJson, preferType);
            if (chNode == null && "valid".equalsIgnoreCase(authStatus)) {
                chNode = findChallenge(authJson, null);
            }
            if (chNode == null) {
                throw new AcmeException(AcmeException.Reason.CHALLENGE_UNAVAILABLE, domain,
                        "No " + (preferType == null ? "" : preferType + " ") + "challenge offered for " + domain);
            }

            AcmeChallenge ch = new AcmeChallenge();
            ch.domain = domain;
            ch.wildcard = wildcard;
            ch.authorizationUrl = authUrl;
            ch.authorizationStatus = authStatus;
            ch.type = chNode.path("type").asText();
            ch.challengeUrl = chNode.path("url").asText();
            ch.token = chNode.path("token").asText();
            ch.status = chNode.path("status").asText();
            ch.keyAuthorization = ch.token + "." + thumbprint;
            if ("dns-01".equalsIgnoreCase(ch.type)) {
                ch.dnsTxtRecordName = "_acme-challenge." + domain.replaceAll("^\\*\\.", "");
                ch.dnsTxtRecordValue = base64UrlEncode(sha256(ch.keyAuthorization.getBytes(StandardCharsets.UTF_8)));
            }
            result.add(ch);
        }
        return result;
    }

    private static JsonNode findChallenge(JsonNode authJson, String type) {
        JsonNode challenges = authJson.get("challenges");
        if (challenges == null || !challenges.isArray()) {
            return null;
        }
        for (JsonNode chNode : challenges) {
            if (type == null || type.equalsIgnoreCase(chNode.path("type").asText())) {
                return chNode;
            }
        }
        return null;
    }

    /**
     * 触发指定 Challenge 进行验证: RFC 8555 section 7.5.1 asks for an empty JSON object.
     */
    public void triggerChallenge(KeyPair accountKeyPair, AcmeChallenge challenge) throws Exception {
        log(I18n.get("tool.cert.acme.log.trigger", challenge.type, challenge.domain));
        HttpResponse resp = sendJwsRequest(accountKeyPair, challenge.challengeUrl, mapper.createObjectNode(), accountKid);
        requireSuccess(resp, challenge.domain, 200);
        JsonNode json = readJson(resp.body, "challenge");
        challenge.status = json.path("status").asText(challenge.status);
    }

    /**
     * Triggers every challenge whose authorization is still pending, then polls each
     * authorization until it is {@code valid}. Throws {@link AcmeException} with
     * {@link AcmeException.Reason#AUTHORIZATION_INVALID} and the challenge's error as
     * soon as one fails; nothing is finalized in that case.
     */
    public void validateAuthorizations(KeyPair accountKeyPair, List<AcmeChallenge> challenges,
                                       BooleanSupplier cancelled) throws Exception {
        List<AcmeChallenge> waiting = new ArrayList<>();
        for (AcmeChallenge ch : challenges) {
            AcmePoller.checkCancelled(cancelled, ch.domain);
            JsonNode authz = postAsGetJson(accountKeyPair, ch.authorizationUrl, "authorization").json;
            String status = authz.path("status").asText("");
            ch.authorizationStatus = status;
            if ("valid".equalsIgnoreCase(status)) {
                log(I18n.get("tool.cert.acme.log.authzReused", ch.domain));
                continue;
            }
            if (!"pending".equalsIgnoreCase(status)) {
                throw authorizationFailure(ch, authz);
            }
            JsonNode current = findChallengeByUrl(authz, ch.challengeUrl);
            String chStatus = current == null ? ch.status : current.path("status").asText(ch.status);
            if ("pending".equalsIgnoreCase(chStatus)) {
                triggerChallenge(accountKeyPair, ch);
            }
            waiting.add(ch);
        }
        for (AcmeChallenge ch : waiting) {
            waitForAuthorization(accountKeyPair, ch, cancelled);
        }
    }

    /** Polls the authorization of {@code challenge} until {@code valid}; fails on any other final state. */
    public void waitForAuthorization(KeyPair accountKeyPair, AcmeChallenge challenge,
                                     BooleanSupplier cancelled) throws Exception {
        log(I18n.get("tool.cert.acme.log.authzWait", challenge.domain));
        poller.poll("authorization " + challenge.domain, cancelled, () -> {
            Resource res = postAsGetJson(accountKeyPair, challenge.authorizationUrl, "authorization");
            String status = res.json.path("status").asText("");
            challenge.authorizationStatus = status;
            if ("valid".equalsIgnoreCase(status)) {
                log(I18n.get("tool.cert.acme.log.authzValid", challenge.domain));
                return AcmePoller.Attempt.done(status);
            }
            if ("pending".equalsIgnoreCase(status) || "processing".equalsIgnoreCase(status)) {
                log(I18n.get("tool.cert.acme.log.authzStatus", challenge.domain, status));
                return AcmePoller.Attempt.again(res.retryAfter);
            }
            throw authorizationFailure(challenge, res.json);
        });
    }

    private static JsonNode findChallengeByUrl(JsonNode authz, String url) {
        JsonNode challenges = authz.get("challenges");
        if (challenges != null && challenges.isArray()) {
            for (JsonNode node : challenges) {
                if (url != null && url.equals(node.path("url").asText())) {
                    return node;
                }
            }
        }
        return null;
    }

    private AcmeException authorizationFailure(AcmeChallenge ch, JsonNode authz) {
        String status = authz.path("status").asText("");
        AcmeProblem problem = null;
        JsonNode ours = findChallengeByUrl(authz, ch.challengeUrl);
        if (ours != null) {
            problem = AcmeProblem.parse(ours.get("error"));
        }
        JsonNode challenges = authz.get("challenges");
        if (problem == null && challenges != null && challenges.isArray()) {
            for (JsonNode node : challenges) {
                problem = AcmeProblem.parse(node.get("error"));
                if (problem != null) {
                    break;
                }
            }
        }
        String message = "Authorization for " + ch.domain + " is " + status
                + (problem == null ? "" : ": " + problem.describe());
        return new AcmeException(AcmeException.Reason.AUTHORIZATION_INVALID, ch.domain, problem, 0, null, message);
    }

    /** Polls the order until it can be finalized ({@code ready}) or is already past that point. */
    public AcmeOrder waitForOrderReady(KeyPair accountKeyPair, AcmeOrder order, BooleanSupplier cancelled) throws Exception {
        return pollOrder(accountKeyPair, order, cancelled, "ready", "processing", "valid");
    }

    /** Polls the order until {@code valid} (the certificate is available). */
    public AcmeOrder waitForOrderValid(KeyPair accountKeyPair, AcmeOrder order, BooleanSupplier cancelled) throws Exception {
        log(I18n.get("tool.cert.acme.log.orderWait"));
        return pollOrder(accountKeyPair, order, cancelled, "valid");
    }

    private AcmeOrder pollOrder(KeyPair accountKeyPair, AcmeOrder order, BooleanSupplier cancelled,
                                String... targets) throws Exception {
        return poller.poll("order", cancelled, () -> {
            Resource res = postAsGetJson(accountKeyPair, order.orderUrl, "order");
            updateOrder(order, res.json);
            if ("invalid".equalsIgnoreCase(order.status)) {
                String message = "Order is invalid" + (order.error == null ? "" : ": " + order.error.describe());
                throw new AcmeException(AcmeException.Reason.ORDER_INVALID, "order", order.error, 0, null, message);
            }
            for (String target : targets) {
                if (target.equalsIgnoreCase(order.status)) {
                    return AcmePoller.Attempt.done(order);
                }
            }
            log(I18n.get("tool.cert.acme.log.orderStatus", order.status));
            return AcmePoller.Attempt.again(res.retryAfter);
        });
    }

    /** Full step 2: validate every authorization, then finalize and download the chain. */
    public String issueCertificate(KeyPair accountKeyPair, KeyPair domainKeyPair, List<String> domains,
                                   AcmeOrder order, List<AcmeChallenge> challenges,
                                   BooleanSupplier cancelled) throws Exception {
        validateAuthorizations(accountKeyPair, challenges, cancelled);
        return finalizeOrder(accountKeyPair, domainKeyPair, domains, order, cancelled);
    }

    /**
     * 提交 CSR (Finalize) 签发证书
     */
    public String finalizeOrder(KeyPair accountKeyPair, KeyPair domainKeyPair, List<String> domains, AcmeOrder order) throws Exception {
        return finalizeOrder(accountKeyPair, domainKeyPair, domains, order, null);
    }

    /**
     * Waits for the order to be {@code ready}, sends the CSR, polls until {@code valid}
     * and downloads the certificate chain. Never finalizes a {@code pending} order.
     */
    public String finalizeOrder(KeyPair accountKeyPair, KeyPair domainKeyPair, List<String> domains,
                                AcmeOrder order, BooleanSupplier cancelled) throws Exception {
        waitForOrderReady(accountKeyPair, order, cancelled);
        if ("ready".equalsIgnoreCase(order.status)) {
            AcmePoller.checkCancelled(cancelled, "finalize");
            log(I18n.get("tool.cert.acme.log.finalize"));
            ObjectNode payload = mapper.createObjectNode();
            payload.put("csr", base64UrlEncode(generateCsr(domainKeyPair, domains)));
            HttpResponse resp = sendJwsRequest(accountKeyPair, order.finalizeUrl, payload, accountKid);
            requireSuccess(resp, "finalize", 200);
            updateOrder(order, readJson(resp.body, "finalize"));
        }
        if (!"valid".equalsIgnoreCase(order.status) || order.certificateUrl == null) {
            waitForOrderValid(accountKeyPair, order, cancelled);
        }
        if (order.certificateUrl == null || order.certificateUrl.isEmpty()) {
            throw protocol("order", "Valid order has no certificate URL");
        }
        log(I18n.get("tool.cert.acme.log.download", order.certificateUrl));
        return downloadCertificate(accountKeyPair, order.certificateUrl);
    }

    /**
     * 下载证书 FullChain (PEM 格式)
     */
    public String downloadCertificate(KeyPair accountKeyPair, String certUrl) throws Exception {
        HttpResponse resp = sendJwsRequest(accountKeyPair, certUrl, null, accountKid, "application/pem-certificate-chain");
        requireSuccess(resp, "certificate", 200);
        if (!resp.body.contains("-----BEGIN CERTIFICATE-----")) {
            throw protocol("certificate", "Certificate response is not a PEM chain");
        }
        log(I18n.get("tool.cert.acme.log.downloadOk"));
        return resp.body;
    }

    // =========================================================================
    //  密码学 & JWS RFC 8555 算法支持
    // =========================================================================

    private byte[] generateCsr(KeyPair keyPair, List<String> domains) throws Exception {
        X500Name subject = new X500Name("CN=" + domains.get(0));
        JcaPKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(subject, keyPair.getPublic());

        List<GeneralName> sanList = new ArrayList<>();
        for (String domain : domains) {
            sanList.add(new GeneralName(GeneralName.dNSName, domain));
        }
        GeneralNames subjectAltNames = new GeneralNames(sanList.toArray(new GeneralName[0]));
        builder.addAttribute(Extension.subjectAlternativeName, subjectAltNames);

        String sigAlg = keyPair.getPublic().getAlgorithm().equalsIgnoreCase("EC") ? "SHA256withECDSA" : "SHA256withRSA";
        return builder.build(new JcaContentSignerBuilder(sigAlg).build(keyPair.getPrivate())).getEncoded();
    }

    private String calculateJwkThumbprint(RSAPublicKey publicKey) throws Exception {
        Map<String, String> jwk = new TreeMap<>();
        jwk.put("e", base64UrlEncode(publicKey.getPublicExponent().toByteArray()));
        jwk.put("kty", "RSA");
        jwk.put("n", base64UrlEncode(toUnsignedByteArray(publicKey.getModulus())));

        String json = mapper.writeValueAsString(jwk);
        return base64UrlEncode(sha256(json.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] toUnsignedByteArray(BigInteger bi) {
        byte[] array = bi.toByteArray();
        if (array[0] == 0) {
            byte[] tmp = new byte[array.length - 1];
            System.arraycopy(array, 1, tmp, 0, tmp.length);
            return tmp;
        }
        return array;
    }

    private String base64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private byte[] sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    // =========================================================================
    //  Transport, nonces and problem documents
    // =========================================================================

    private void fetchNextNonce() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(newNonceUrl).openConnection();
        conn.setRequestMethod("HEAD");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try {
            conn.connect();
            int code = conn.getResponseCode();
            lastNonce = conn.getHeaderField("Replay-Nonce");
            if (lastNonce == null || lastNonce.isEmpty()) {
                throw new AcmeException(AcmeException.Reason.HTTP_ERROR, "newNonce", null, code, null,
                        "newNonce returned no Replay-Nonce [HTTP " + code + "]");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static class HttpResponse {
        int statusCode;
        Map<String, List<String>> headers;
        String body;

        public String getHeader(String name) {
            if (headers == null) return null;
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = entry.getValue();
                    if (values != null && !values.isEmpty()) {
                        return values.get(0);
                    }
                }
            }
            return null;
        }
    }

    /** A polled resource: its JSON body plus the server's Retry-After hint. */
    private static final class Resource {
        JsonNode json;
        Duration retryAfter;
    }

    private Resource postAsGetJson(KeyPair accountKeyPair, String url, String subject) throws Exception {
        HttpResponse resp = sendJwsRequest(accountKeyPair, url, null, accountKid);
        requireSuccess(resp, subject, 200);
        Resource res = new Resource();
        res.json = readJson(resp.body, subject);
        res.retryAfter = RetryAfter.parse(resp.getHeader("Retry-After"), poller.clock().instant());
        return res;
    }

    private void requireSuccess(HttpResponse resp, String subject, int... accepted) {
        for (int code : accepted) {
            if (resp.statusCode == code) {
                return;
            }
        }
        AcmeProblem problem = parseProblem(resp);
        Duration retryAfter = RetryAfter.parse(resp.getHeader("Retry-After"), poller.clock().instant());
        StringBuilder message = new StringBuilder("ACME ").append(subject)
                .append(" failed [HTTP ").append(resp.statusCode).append("]: ");
        if (problem != null) {
            message.append(problem.describe());
            if (problem.is("rateLimited") && retryAfter != null) {
                message.append(" (retry after ").append(retryAfter.getSeconds()).append("s)");
            }
        } else {
            message.append(resp.body == null ? "" : resp.body.trim());
        }
        throw new AcmeException(problem != null ? AcmeException.Reason.SERVER_PROBLEM : AcmeException.Reason.HTTP_ERROR,
                subject, problem, resp.statusCode, retryAfter, message.toString());
    }

    private AcmeProblem parseProblem(HttpResponse resp) {
        if (resp.body == null || resp.body.trim().isEmpty()) {
            return null;
        }
        try {
            return AcmeProblem.parse(mapper.readTree(resp.body));
        } catch (IOException notJson) {
            return null;
        }
    }

    private JsonNode readJson(String body, String subject) {
        try {
            JsonNode node = body == null ? null : mapper.readTree(body);
            if (node == null || !node.isObject()) {
                throw protocol(subject, "Expected a JSON object from " + subject);
            }
            return node;
        } catch (IOException e) {
            throw protocol(subject, "Malformed JSON from " + subject + ": " + e.getMessage());
        }
    }

    private String requiredText(JsonNode node, String field, String subject) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw protocol(subject, "Missing '" + field + "' in " + subject);
        }
        return value.asText();
    }

    private static AcmeException protocol(String subject, String message) {
        return new AcmeException(AcmeException.Reason.PROTOCOL, subject, message);
    }

    private HttpResponse sendJwsRequest(KeyPair accountKeyPair, String url, Object payload, String kid) throws Exception {
        return sendJwsRequest(accountKeyPair, url, payload, kid, null);
    }

    /** Sends a JWS request; a {@code badNonce} rejection is retried once with a fresh nonce (RFC 8555 section 6.5). */
    private HttpResponse sendJwsRequest(KeyPair accountKeyPair, String url, Object payload, String kid, String acceptHeader) throws Exception {
        HttpResponse response = sendJwsOnce(accountKeyPair, url, payload, kid, acceptHeader);
        if (response.statusCode == 400) {
            AcmeProblem problem = parseProblem(response);
            if (problem != null && problem.is("badNonce")) {
                log(I18n.get("tool.cert.acme.log.badNonce"));
                response = sendJwsOnce(accountKeyPair, url, payload, kid, acceptHeader);
            }
        }
        return response;
    }

    private HttpResponse sendJwsOnce(KeyPair accountKeyPair, String url, Object payload, String kid, String acceptHeader) throws Exception {
        if (lastNonce == null) {
            fetchNextNonce();
        }

        ObjectNode protectedHeader = mapper.createObjectNode();
        protectedHeader.put("alg", "RS256");
        if (kid != null) {
            protectedHeader.put("kid", kid);
        } else {
            RSAPublicKey rsaPub = (RSAPublicKey) accountKeyPair.getPublic();
            ObjectNode jwk = protectedHeader.putObject("jwk");
            jwk.put("e", base64UrlEncode(rsaPub.getPublicExponent().toByteArray()));
            jwk.put("kty", "RSA");
            jwk.put("n", base64UrlEncode(toUnsignedByteArray(rsaPub.getModulus())));
        }
        protectedHeader.put("nonce", lastNonce);
        protectedHeader.put("url", url);
        // A nonce is single-use: never resend it, even when this request dies without a response.
        lastNonce = null;

        String protectedB64 = base64UrlEncode(mapper.writeValueAsString(protectedHeader).getBytes(StandardCharsets.UTF_8));
        String payloadB64 = payload == null ? "" : base64UrlEncode(mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));

        String signingInput = protectedB64 + "." + payloadB64;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(accountKeyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String signatureB64 = base64UrlEncode(signature.sign());

        ObjectNode jwsObj = mapper.createObjectNode();
        jwsObj.put("protected", protectedB64);
        jwsObj.put("payload", payloadB64);
        jwsObj.put("signature", signatureB64);

        byte[] requestBody = mapper.writeValueAsString(jwsObj).getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Content-Type", "application/jose+json");
            if (acceptHeader != null) {
                conn.setRequestProperty("Accept", acceptHeader);
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBody);
            }

            HttpResponse response = new HttpResponse();
            response.statusCode = conn.getResponseCode();
            response.headers = conn.getHeaderFields();
            lastNonce = response.getHeader("Replay-Nonce");
            InputStream is = (response.statusCode >= 200 && response.statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();
            response.body = readAll(is);
            return response;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                String body = readAll(conn.getErrorStream());
                throw new AcmeException(AcmeException.Reason.HTTP_ERROR, "directory", null, code, null,
                        "ACME directory failed [HTTP " + code + "]: " + body.trim());
            }
            return readAll(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }
}
