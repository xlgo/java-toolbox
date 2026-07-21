package com.aqishi.toolbox.crypto.acme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

import java.io.*;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.function.Consumer;

/**
 * 遵守 RFC 8555 (ACME v2 协议) 的轻量级 Java 客户端
 */
public class AcmeClient {

    public static final String LETSENCRYPT_PROD = "https://acme-v02.api.letsencrypt.org/directory";
    public static final String LETSENCRYPT_STAGE = "https://acme-staging-v02.api.letsencrypt.org/directory";
    public static final String ZEROSSL_PROD = "https://acme.zerossl.com/v2/DV90";

    private final String directoryUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    private String newNonceUrl;
    private String newAccountUrl;
    private String newOrderUrl;

    private String accountKid;
    private String lastNonce;

    private Consumer<String> logger;

    public AcmeClient(String directoryUrl) {
        this.directoryUrl = directoryUrl;
    }

    public void setLogger(Consumer<String> logger) {
        this.logger = logger;
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
        log("正在连接 ACME Directory: " + directoryUrl);
        String dirJsonStr = httpGet(directoryUrl);
        JsonNode dirJson = mapper.readTree(dirJsonStr);

        newNonceUrl = dirJson.get("newNonce").asText();
        newAccountUrl = dirJson.get("newAccount").asText();
        newOrderUrl = dirJson.get("newOrder").asText();

        log("成功解析 ACME 接口指引 (Directory)");
        fetchNextNonce();
    }

    /**
     * 注册或获取 ACME 账户 (newAccount)
     */
    public String registerAccount(KeyPair accountKeyPair, String email) throws Exception {
        log("正在注册/登录 ACME 账户 (Email: " + (email.isEmpty() ? "未提供" : email) + ")...");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("termsOfServiceAgreed", true);
        if (email != null && !email.trim().isEmpty()) {
            ArrayNode contact = payload.putArray("contact");
            contact.add("mailto:" + email.trim());
        }

        HttpResponse response = sendJwsRequest(accountKeyPair, newAccountUrl, payload, null);
        if (response.statusCode != 201 && response.statusCode != 200) {
            throw new RuntimeException("创建账户失败 [HTTP " + response.statusCode + "]: " + response.body);
        }

        accountKid = response.getHeader("Location");
        log("账户注册/验证成功！Account KID: " + accountKid);
        return accountKid;
    }

    /**
     * ACME 订单数据传输模型
     */
    public static class AcmeOrder {
        public String orderUrl;
        public String finalizeUrl;
        public String status;
        public List<String> authorizationUrls = new ArrayList<>();
    }

    /**
     * 提交新证书订单 (newOrder)
     */
    public AcmeOrder createOrder(KeyPair accountKeyPair, List<String> domains) throws Exception {
        log("正在提交域名证书订单: " + domains + " ...");
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode identifiers = payload.putArray("identifiers");
        for (String domain : domains) {
            ObjectNode idNode = identifiers.addObject();
            idNode.put("type", "dns");
            idNode.put("value", domain);
        }

        HttpResponse response = sendJwsRequest(accountKeyPair, newOrderUrl, payload, accountKid);
        if (response.statusCode != 201 && response.statusCode != 200) {
            throw new RuntimeException("创建订单失败 [HTTP " + response.statusCode + "]: " + response.body);
        }

        JsonNode json = mapper.readTree(response.body);
        AcmeOrder order = new AcmeOrder();
        order.orderUrl = response.getHeader("Location");
        order.status = json.get("status").asText();
        order.finalizeUrl = json.get("finalize").asText();

        ArrayNode auths = (ArrayNode) json.get("authorizations");
        if (auths != null) {
            for (JsonNode auth : auths) {
                order.authorizationUrls.add(auth.asText());
            }
        }
        log("证书订单创建完成！Status: " + order.status);
        return order;
    }

    /**
     * Challenge 信息模型
     */
    public static class AcmeChallenge {
        public String domain;
        public String type; // http-01 / dns-01
        public String challengeUrl;
        public String token;
        public String status;
        public String keyAuthorization;
        public String dnsTxtRecordName;
        public String dnsTxtRecordValue;
    }

    /**
     * 获取订单的验证 Challenge 详情
     */
    public List<AcmeChallenge> getChallenges(KeyPair accountKeyPair, AcmeOrder order, String preferType) throws Exception {
        List<AcmeChallenge> result = new ArrayList<>();
        String thumbprint = calculateJwkThumbprint((RSAPublicKey) accountKeyPair.getPublic());

        for (String authUrl : order.authorizationUrls) {
            HttpResponse resp = sendJwsRequest(accountKeyPair, authUrl, null, accountKid);
            JsonNode authJson = mapper.readTree(resp.body);

            String domain = authJson.get("identifier").get("value").asText();
            ArrayNode challenges = (ArrayNode) authJson.get("challenges");

            for (JsonNode chNode : challenges) {
                String type = chNode.get("type").asText();
                if (preferType != null && !preferType.equalsIgnoreCase(type)) {
                    continue;
                }

                AcmeChallenge ch = new AcmeChallenge();
                ch.domain = domain;
                ch.type = type;
                ch.challengeUrl = chNode.get("url").asText();
                ch.token = chNode.get("token").asText();
                ch.status = chNode.get("status").asText();

                ch.keyAuthorization = ch.token + "." + thumbprint;

                if ("dns-01".equalsIgnoreCase(type)) {
                    ch.dnsTxtRecordName = "_acme-challenge." + domain.replaceAll("^\\*\\.", "");
                    ch.dnsTxtRecordValue = base64UrlEncode(sha256(ch.keyAuthorization.getBytes(StandardCharsets.UTF_8)));
                }

                result.add(ch);
                break;
            }
        }
        return result;
    }

    /**
     * 触发指定 Challenge 进行验证
     */
    public void triggerChallenge(KeyPair accountKeyPair, AcmeChallenge challenge) throws Exception {
        log("触发 " + challenge.type + " 验证 [" + challenge.domain + "]...");
        ObjectNode payload = mapper.createObjectNode();
        payload.put("keyAuthorization", challenge.keyAuthorization);

        HttpResponse resp = sendJwsRequest(accountKeyPair, challenge.challengeUrl, payload, accountKid);
        if (resp.statusCode != 200) {
            throw new RuntimeException("触发验证失败 [HTTP " + resp.statusCode + "]: " + resp.body);
        }
    }

    /**
     * 轮询验证 Challenge 或 Order 最终完成状态
     */
    public String pollOrderStatus(KeyPair accountKeyPair, String orderUrl, int maxRetries) throws Exception {
        log("正在等待 CA 节点验证域名权限并完成签发...");
        for (int i = 0; i < maxRetries; i++) {
            Thread.sleep(3000);
            HttpResponse resp = sendJwsRequest(accountKeyPair, orderUrl, null, accountKid);
            JsonNode json = mapper.readTree(resp.body);
            String status = json.get("status").asText();
            log("订单当前状态: " + status + " (" + (i + 1) + "/" + maxRetries + ")");

            if ("valid".equalsIgnoreCase(status)) {
                return status;
            } else if ("invalid".equalsIgnoreCase(status)) {
                throw new RuntimeException("域名验证失败，订单状态变为 invalid: " + resp.body);
            }
        }
        throw new RuntimeException("等待订单验证超时");
    }

    /**
     * 提交 CSR (Finalize) 签发证书
     */
    public String finalizeOrder(KeyPair accountKeyPair, KeyPair domainKeyPair, List<String> domains, AcmeOrder order) throws Exception {
        log("本地生成 PKCS#10 CSR 证书请求文件并提交至 CA...");
        byte[] csrDer = generateCsr(domainKeyPair, domains);
        String base64UrlCsr = base64UrlEncode(csrDer);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("csr", base64UrlCsr);

        HttpResponse resp = sendJwsRequest(accountKeyPair, order.finalizeUrl, payload, accountKid);
        if (resp.statusCode != 200) {
            throw new RuntimeException("提交 CSR 失败 [HTTP " + resp.statusCode + "]: " + resp.body);
        }

        JsonNode json = mapper.readTree(resp.body);
        String status = json.get("status").asText();

        if (!"valid".equalsIgnoreCase(status)) {
            status = pollOrderStatus(accountKeyPair, order.orderUrl, 15);
        }

        HttpResponse finalOrderResp = sendJwsRequest(accountKeyPair, order.orderUrl, null, accountKid);
        JsonNode finalOrderJson = mapper.readTree(finalOrderResp.body);
        String certUrl = finalOrderJson.get("certificate").asText();
        log("证书签发成功！正在下载证书文件 (URL: " + certUrl + ")...");

        return downloadCertificate(accountKeyPair, certUrl);
    }

    /**
     * 下载证书 FullChain (PEM 格式)
     */
    public String downloadCertificate(KeyPair accountKeyPair, String certUrl) throws Exception {
        HttpResponse resp = sendJwsRequest(accountKeyPair, certUrl, null, accountKid, "application/pem-certificate-chain");
        if (resp.statusCode != 200) {
            throw new RuntimeException("下载证书文件失败 [HTTP " + resp.statusCode + "]: " + resp.body);
        }
        log("证书下载完成！");
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

    private void fetchNextNonce() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(newNonceUrl).openConnection();
        conn.setRequestMethod("HEAD");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.connect();
        lastNonce = conn.getHeaderField("Replay-Nonce");
        conn.disconnect();
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

    private HttpResponse sendJwsRequest(KeyPair accountKeyPair, String url, Object payload, String kid) throws Exception {
        return sendJwsRequest(accountKeyPair, url, payload, kid, null);
    }

    private HttpResponse sendJwsRequest(KeyPair accountKeyPair, String url, Object payload, String kid, String acceptHeader) throws Exception {
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
        if (is != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                response.body = sb.toString();
            }
        } else {
            response.body = "";
        }

        conn.disconnect();
        return response;
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }
}
