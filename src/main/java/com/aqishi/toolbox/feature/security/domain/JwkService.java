package com.aqishi.toolbox.feature.security.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JWK / JWKS 的解析、指纹、PEM 互转与 JWT 验签。
 *
 * <p>排查 OIDC / OAuth2 验签失败时，真正要回答的通常是三个问题：JWKS 里到底有哪些钥、
 * 令牌该用哪一把、用了之后为什么不过。这里把三步拆开，每步都给出可定位的错误码，
 * 而不是笼统的「验签失败」。</p>
 *
 * <p>解析一律从严：base64url 拒绝填充和非法字符、EC 点必须在曲线上、JSON 拒绝重复成员。
 * 宽松解析在调试工具里看似友好，实际会让「本地能过、线上库拒收」的问题被掩盖掉。</p>
 *
 * <p>本类是纯逻辑，不访问网络也不依赖 Swing；拉取远端 JWKS 见 {@code JwksFetcher}。</p>
 */
public final class JwkService {

    /** 各 kty 的私有成员（RFC 7518 §6.3.2 / §6.2.2 / §6.4，RFC 8037 §2）。 */
    private static final Map<String, List<String>> PRIVATE_MEMBERS = Map.of(
            "RSA", List.of("d", "p", "q", "dp", "dq", "qi", "oth"),
            "EC", List.of("d"),
            "OKP", List.of("d"),
            "oct", List.of("k"));

    /** 拒绝重复成员与尾随内容：{"alg":"RS256","alg":"none"} 这种令牌头必须当场报错。 */
    private static final ObjectReader STRICT_READER = Json.mapper().readerFor(JsonNode.class)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    private static final Map<String, Curve> EC_CURVES = new LinkedHashMap<>();
    private static final Map<String, OkpCurve> OKP_CURVES = new LinkedHashMap<>();

    static {
        EC_CURVES.put("P-256", new Curve("P-256", "secp256r1", 32, 256));
        EC_CURVES.put("P-384", new Curve("P-384", "secp384r1", 48, 384));
        EC_CURVES.put("P-521", new Curve("P-521", "secp521r1", 66, 521));
        // SubjectPublicKeyInfo 前缀：SEQUENCE { SEQUENCE { OID }, BIT STRING }，后面直接接原始公钥。
        OKP_CURVES.put("Ed25519", new OkpCurve("Ed25519", "EdDSA", 32, 256, "302a300506032b6570032100"));
        OKP_CURVES.put("Ed448", new OkpCurve("Ed448", "EdDSA", 57, 448, "3043300506032b6571033a00"));
        OKP_CURVES.put("X25519", new OkpCurve("X25519", "XDH", 32, 256, "302a300506032b656e032100"));
        OKP_CURVES.put("X448", new OkpCurve("X448", "XDH", 56, 448, "3042300506032b656f033900"));
    }

    /** 验签时的可选项。 */
    public record VerifyOptions(long leewaySeconds, String expectedIssuer, String expectedAudience) {
        public static VerifyOptions defaults() {
            return new VerifyOptions(60, null, null);
        }
    }

    private final Clock clock;

    public JwkService() {
        this(Clock.systemUTC());
    }

    /** @param clock 声明时间检查使用的时钟；测试时注入固定时钟 */
    public JwkService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ==========================================
    // 解析
    // ==========================================

    /**
     * 解析单个 JWK、{@code {"keys":[...]}} 或 JWK 数组。
     *
     * @throws JwkException 整体不是 JWK / JWKS，或单个 JWK 本身无效
     */
    public JwkSet parse(String json) {
        if (json == null || json.isBlank()) {
            throw new JwkException("emptyInput");
        }
        JsonNode root = readJson(json.trim());
        if (root.isObject() && root.has("keys")) {
            JsonNode keys = root.get("keys");
            if (!keys.isArray()) {
                throw new JwkException("keysNotArray");
            }
            return parseArray(keys);
        }
        if (root.isArray()) {
            return parseArray(root);
        }
        if (root.isObject() && root.has("kty")) {
            // 单个 JWK 出错时直接抛出：只有一把钥，没什么可「部分展示」的。
            JwkKey key = parseKey(root, 0);
            return new JwkSet(List.of(key), List.of(), true, List.of());
        }
        if (root.isObject() && root.has("jwks_uri")) {
            throw new JwkException("discoveryDocument", root.get("jwks_uri").asText(""));
        }
        throw new JwkException("notJwk");
    }

    private JwkSet parseArray(JsonNode array) {
        List<JwkKey> keys = new ArrayList<>();
        List<JwkSet.Failure> failures = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonNode node = array.get(i);
            try {
                keys.add(parseKey(node, i));
            } catch (JwkException error) {
                String kid = node != null && node.isObject() && node.path("kid").isTextual()
                        ? node.get("kid").asText() : null;
                failures.add(new JwkSet.Failure(i, kid, error));
            }
        }
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (JwkKey key : keys) {
            if (key.kid() != null && !seen.add(key.kid())) {
                duplicates.add(key.kid());
            }
        }
        return new JwkSet(keys, failures, false, new ArrayList<>(duplicates));
    }

    /** 解析一个 JWK 对象。 */
    JwkKey parseKey(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new JwkException("keyNotObject", index);
        }
        String kty = requiredString(node, "kty");
        String kid = optionalString(node, "kid");
        String use = optionalString(node, "use");
        String alg = optionalString(node, "alg");
        List<String> keyOps = readKeyOps(node);

        List<JwkKey.Warning> warnings = new ArrayList<>();
        List<String> privateMembers = new ArrayList<>();
        for (String member : PRIVATE_MEMBERS.getOrDefault(kty, List.of())) {
            if (node.has(member)) {
                privateMembers.add(member);
            }
        }

        Map<String, String> thumbprintMembers = new TreeMap<>();
        thumbprintMembers.put("kty", kty);
        PublicKey publicKey = null;
        byte[] secret = null;
        String curve = null;
        int sizeBits;

        switch (kty) {
            case "RSA": {
                String n = requiredString(node, "n");
                String e = requiredString(node, "e");
                byte[] modulusBytes = decodeBase64Url(n, "n");
                byte[] exponentBytes = decodeBase64Url(e, "e");
                BigInteger modulus = new BigInteger(1, modulusBytes);
                BigInteger exponent = new BigInteger(1, exponentBytes);
                if (modulus.bitLength() < 512 || !modulus.testBit(0)) {
                    throw new JwkException("rsaModulusInvalid", modulus.bitLength());
                }
                // e 必须是奇数且 ≥3；长度超过模长一半的指数没有合法实现会生成，基本是字段填反了。
                if (exponent.compareTo(BigInteger.valueOf(3)) < 0 || !exponent.testBit(0)
                        || exponent.bitLength() > 256 || exponent.compareTo(modulus) >= 0) {
                    throw new JwkException("rsaExponentInvalid");
                }
                if (hasLeadingZero(modulusBytes)) {
                    warnings.add(new JwkKey.Warning(JwkKey.WarningCode.NON_CANONICAL_INTEGER, List.of("n")));
                }
                if (hasLeadingZero(exponentBytes)) {
                    warnings.add(new JwkKey.Warning(JwkKey.WarningCode.NON_CANONICAL_INTEGER, List.of("e")));
                }
                sizeBits = modulus.bitLength();
                if (sizeBits < 2048) {
                    warnings.add(new JwkKey.Warning(JwkKey.WarningCode.RSA_WEAK, List.of(sizeBits)));
                }
                publicKey = generate("RSA", new RSAPublicKeySpec(modulus, exponent));
                thumbprintMembers.put("n", n);
                thumbprintMembers.put("e", e);
                break;
            }
            case "EC": {
                curve = requiredString(node, "crv");
                Curve spec = EC_CURVES.get(curve);
                if (spec == null) {
                    throw new JwkException("unsupportedCurve", curve);
                }
                String x = requiredString(node, "x");
                String y = requiredString(node, "y");
                byte[] xBytes = decodeBase64Url(x, "x");
                byte[] yBytes = decodeBase64Url(y, "y");
                // RFC 7518 §6.2.1.2：坐标必须是定长编码，不能省略前导零。
                if (xBytes.length != spec.fieldBytes) {
                    throw new JwkException("ecPointLength", "x", spec.fieldBytes, xBytes.length);
                }
                if (yBytes.length != spec.fieldBytes) {
                    throw new JwkException("ecPointLength", "y", spec.fieldBytes, yBytes.length);
                }
                ECPoint point = new ECPoint(new BigInteger(1, xBytes), new BigInteger(1, yBytes));
                ECParameterSpec parameters = spec.parameters();
                if (!isOnCurve(point, parameters.getCurve())) {
                    throw new JwkException("ecPointNotOnCurve", curve);
                }
                publicKey = generate("EC", new ECPublicKeySpec(point, parameters));
                sizeBits = spec.bits;
                thumbprintMembers.put("crv", curve);
                thumbprintMembers.put("x", x);
                thumbprintMembers.put("y", y);
                break;
            }
            case "OKP": {
                curve = requiredString(node, "crv");
                OkpCurve spec = OKP_CURVES.get(curve);
                if (spec == null) {
                    throw new JwkException("unsupportedCurve", curve);
                }
                String x = requiredString(node, "x");
                byte[] xBytes = decodeBase64Url(x, "x");
                if (xBytes.length != spec.keyBytes) {
                    throw new JwkException("okpKeyLength", curve, spec.keyBytes, xBytes.length);
                }
                publicKey = generate(spec.keyFactory, new X509EncodedKeySpec(concat(spec.prefix(), xBytes)));
                sizeBits = spec.bits;
                thumbprintMembers.put("crv", curve);
                thumbprintMembers.put("x", x);
                break;
            }
            case "oct": {
                String k = requiredString(node, "k");
                secret = decodeBase64Url(k, "k");
                if (secret.length == 0) {
                    throw new JwkException("octEmpty");
                }
                sizeBits = secret.length * 8;
                thumbprintMembers.put("k", k);
                break;
            }
            default:
                throw new JwkException("unsupportedKty", kty);
        }

        if (!privateMembers.isEmpty()) {
            warnings.add(0, new JwkKey.Warning(JwkKey.WarningCode.PRIVATE_MEMBERS,
                    List.of(String.join(", ", privateMembers))));
        }
        if (use != null && keyOps != null) {
            warnings.add(new JwkKey.Warning(JwkKey.WarningCode.USE_AND_KEY_OPS, List.of()));
        }
        if (alg != null) {
            JoseAlgorithm declared = JoseAlgorithm.fromJoseName(alg).orElse(null);
            if (declared != null && !declared.acceptsKey(kty, curve)) {
                warnings.add(new JwkKey.Warning(JwkKey.WarningCode.ALG_KTY_MISMATCH, List.of(alg)));
            }
        }

        boolean x5cPresent = node.has("x5c");
        boolean x5tPresent = node.has("x5t") || node.has("x5t#S256");
        List<X509Certificate> certificates = readCertificates(node, publicKey, warnings);

        return new JwkKey(index, kty, kid, use, alg, keyOps, curve, sizeBits,
                thumbprint(thumbprintMembers), privateMembers, publicKey, secret, certificates,
                x5cPresent, x5tPresent, warnings, publicJson(node, kty));
    }

    private List<String> readKeyOps(JsonNode node) {
        if (!node.has("key_ops")) {
            return null;
        }
        JsonNode ops = node.get("key_ops");
        if (!ops.isArray()) {
            throw new JwkException("keyOpsNotArray");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode op : ops) {
            if (!op.isTextual()) {
                throw new JwkException("keyOpsNotArray");
            }
            result.add(op.asText());
        }
        return result;
    }

    /** x5c 是标准 Base64（带填充，不是 base64url），RFC 7517 §4.7。 */
    private List<X509Certificate> readCertificates(JsonNode node, PublicKey publicKey,
                                                   List<JwkKey.Warning> warnings) {
        if (!node.has("x5c")) {
            return List.of();
        }
        List<X509Certificate> chain = new ArrayList<>();
        try {
            JsonNode x5c = node.get("x5c");
            if (!x5c.isArray() || x5c.isEmpty()) {
                throw new CertificateException("x5c must be a non-empty array");
            }
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (JsonNode entry : x5c) {
                if (!entry.isTextual()) {
                    throw new CertificateException("x5c entry must be a string");
                }
                byte[] der = Base64.getDecoder().decode(entry.asText());
                chain.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der)));
            }
        } catch (CertificateException | IllegalArgumentException | ClassCastException error) {
            warnings.add(new JwkKey.Warning(JwkKey.WarningCode.X5C_INVALID, List.of()));
            return List.of();
        }
        X509Certificate leaf = chain.get(0);
        if (publicKey != null && !samePublicKey(publicKey, leaf.getPublicKey())) {
            warnings.add(new JwkKey.Warning(JwkKey.WarningCode.X5C_KEY_MISMATCH, List.of()));
        }
        checkCertificateThumbprint(node, "x5t", "SHA-1", leaf, warnings);
        checkCertificateThumbprint(node, "x5t#S256", "SHA-256", leaf, warnings);
        return chain;
    }

    private void checkCertificateThumbprint(JsonNode node, String member, String digest,
                                            X509Certificate leaf, List<JwkKey.Warning> warnings) {
        if (!node.path(member).isTextual()) {
            return;
        }
        try {
            String expected = base64Url(MessageDigest.getInstance(digest).digest(leaf.getEncoded()));
            if (!expected.equals(node.get(member).asText())) {
                warnings.add(new JwkKey.Warning(JwkKey.WarningCode.X5T_MISMATCH, List.of(member)));
            }
        } catch (GeneralSecurityException error) {
            warnings.add(new JwkKey.Warning(JwkKey.WarningCode.X5T_MISMATCH, List.of(member)));
        }
    }

    private static String publicJson(JsonNode node, String kty) {
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        for (String member : PRIVATE_MEMBERS.getOrDefault(kty, List.of())) {
            copy.remove(member);
        }
        try {
            return Json.mapper().writeValueAsString(copy);
        } catch (IOException error) {
            return "{}";
        }
    }

    // ==========================================
    // 指纹
    // ==========================================

    /**
     * RFC 7638 指纹：只取必需成员，按成员名字典序、无空白序列化后做 SHA-256。
     *
     * <p>成员值按 JWK 里的原文参与计算，不做重新编码——带前导零的 n 与规范的 n
     * 会得到不同指纹，这正是 {@link JwkKey.WarningCode#NON_CANONICAL_INTEGER} 要提醒的。</p>
     */
    static String thumbprint(Map<String, String> requiredMembers) {
        StringBuilder json = new StringBuilder("{");
        for (Map.Entry<String, String> entry : new TreeMap<>(requiredMembers).entrySet()) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        json.append('}');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json.toString().getBytes(StandardCharsets.UTF_8));
            return base64Url(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String quote(String value) {
        try {
            return Json.mapper().writeValueAsString(value);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    // ==========================================
    // 导出
    // ==========================================

    /** 公钥的 SubjectPublicKeyInfo PEM。 */
    public String publicKeyPem(JwkKey key) {
        if (key.publicKey() == null) {
            throw new JwkException("noPublicKey");
        }
        return pem("PUBLIC KEY", key.publicKey().getEncoded());
    }

    /** x5c 叶子证书 PEM。 */
    public String certificatePem(JwkKey key) {
        if (key.certificates().isEmpty()) {
            throw new JwkException("noCertificate");
        }
        try {
            return pem("CERTIFICATE", key.certificates().get(0).getEncoded());
        } catch (CertificateException error) {
            throw new JwkException("noCertificate");
        }
    }

    /** 去掉私有成员后的 JWK，美化输出。 */
    public String publicJwkPretty(JwkKey key) {
        return pretty(readJson(key.publicJwkJson()));
    }

    static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    // ==========================================
    // PEM → JWK
    // ==========================================

    /**
     * 把 PEM 公钥 / X.509 证书 / PKCS#1 RSA 公钥转成 JWK。
     *
     * @param kid 为空时用 RFC 7638 指纹
     * @param use 可空
     * @param alg 可空；已知算法会校验与密钥类型是否匹配
     * @return 美化的 JWK JSON
     */
    public String pemToJwk(String pem, String kid, String use, String alg) {
        if (pem == null || pem.isBlank()) {
            throw new JwkException("emptyPem");
        }
        PublicKey publicKey;
        List<X509Certificate> chain = new ArrayList<>();
        Matcher matcher = PEM_BLOCK.matcher(pem);
        List<String[]> blocks = new ArrayList<>();
        while (matcher.find()) {
            blocks.add(new String[]{matcher.group(1).trim(), matcher.group(2)});
        }
        if (blocks.isEmpty()) {
            if (pem.contains("-----BEGIN")) {
                throw new JwkException("pemInvalid");
            }
            // 没有 PEM 头尾时按裸 Base64 DER 处理：先当 SPKI，再当证书。
            byte[] der = decodePemBody(pem);
            PublicKey parsed = tryPublicKey(der);
            if (parsed != null) {
                publicKey = parsed;
            } else {
                chain.add(certificate(der));
                publicKey = chain.get(0).getPublicKey();
            }
        } else {
            String type = blocks.get(0)[0];
            if (type.contains("PRIVATE KEY")) {
                throw new JwkException("pemPrivateKey");
            }
            switch (type) {
                case "PUBLIC KEY": {
                    publicKey = tryPublicKey(decodePemBody(blocks.get(0)[1]));
                    if (publicKey == null) {
                        throw new JwkException("pemUnsupportedKey");
                    }
                    break;
                }
                case "RSA PUBLIC KEY": {
                    publicKey = pkcs1RsaPublicKey(decodePemBody(blocks.get(0)[1]));
                    break;
                }
                case "CERTIFICATE": {
                    for (String[] block : blocks) {
                        if ("CERTIFICATE".equals(block[0])) {
                            chain.add(certificate(decodePemBody(block[1])));
                        }
                    }
                    publicKey = chain.get(0).getPublicKey();
                    break;
                }
                default:
                    throw new JwkException("pemUnsupported", type);
            }
        }

        Map<String, String> members = publicMembers(publicKey);
        String kty = members.get("kty");
        String trimmedAlg = blankToNull(alg);
        if (trimmedAlg != null) {
            JoseAlgorithm declared = JoseAlgorithm.fromJoseName(trimmedAlg).orElse(null);
            if (declared != null && !declared.acceptsKey(kty, members.get("crv"))) {
                throw new JwkException("algKeyMismatch", trimmedAlg, kty);
            }
        }
        String thumbprint = thumbprint(members);

        ObjectNode jwk = Json.mapper().createObjectNode();
        jwk.put("kty", kty);
        jwk.put("kid", blankToNull(kid) == null ? thumbprint : kid.trim());
        if (blankToNull(use) != null) {
            jwk.put("use", use.trim());
        }
        if (trimmedAlg != null) {
            jwk.put("alg", trimmedAlg);
        }
        for (String member : List.of("crv", "x", "y", "n", "e")) {
            if (members.containsKey(member)) {
                jwk.put(member, members.get(member));
            }
        }
        if (!chain.isEmpty()) {
            ArrayNode x5c = jwk.putArray("x5c");
            try {
                for (X509Certificate certificate : chain) {
                    x5c.add(Base64.getEncoder().encodeToString(certificate.getEncoded()));
                }
                jwk.put("x5t#S256", base64Url(MessageDigest.getInstance("SHA-256")
                        .digest(chain.get(0).getEncoded())));
            } catch (GeneralSecurityException error) {
                throw new JwkException("pemInvalid");
            }
        }
        return pretty(jwk);
    }

    /** 由 JDK 公钥对象得出 JWK 公开成员（含 kty），值均为规范编码。 */
    static Map<String, String> publicMembers(PublicKey publicKey) {
        Map<String, String> members = new TreeMap<>();
        if (publicKey instanceof RSAPublicKey) {
            RSAPublicKey rsa = (RSAPublicKey) publicKey;
            members.put("kty", "RSA");
            members.put("n", base64Url(unsigned(rsa.getModulus())));
            members.put("e", base64Url(unsigned(rsa.getPublicExponent())));
            return members;
        }
        if (publicKey instanceof ECPublicKey) {
            ECPublicKey ec = (ECPublicKey) publicKey;
            Curve curve = identifyCurve(ec.getParams());
            members.put("kty", "EC");
            members.put("crv", curve.name);
            members.put("x", base64Url(fixedLength(ec.getW().getAffineX(), curve.fieldBytes)));
            members.put("y", base64Url(fixedLength(ec.getW().getAffineY(), curve.fieldBytes)));
            return members;
        }
        byte[] encoded = publicKey.getEncoded();
        for (OkpCurve curve : OKP_CURVES.values()) {
            byte[] prefix = curve.prefix();
            if (encoded != null && encoded.length == prefix.length + curve.keyBytes
                    && Arrays.equals(Arrays.copyOf(encoded, prefix.length), prefix)) {
                members.put("kty", "OKP");
                members.put("crv", curve.name);
                members.put("x", base64Url(Arrays.copyOfRange(encoded, prefix.length, encoded.length)));
                return members;
            }
        }
        throw new JwkException("pemUnsupportedKey");
    }

    private static PublicKey tryPublicKey(byte[] der) {
        for (String algorithm : List.of("RSA", "EC", "EdDSA", "XDH")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(der));
            } catch (GeneralSecurityException | RuntimeException ignored) {
                // 换下一种算法再试；SPKI 里的 OID 决定只有一种能成功。
            }
        }
        return null;
    }

    private static PublicKey pkcs1RsaPublicKey(byte[] der) {
        try {
            org.bouncycastle.asn1.pkcs.RSAPublicKey rsa =
                    org.bouncycastle.asn1.pkcs.RSAPublicKey.getInstance(der);
            return generate("RSA", new RSAPublicKeySpec(rsa.getModulus(), rsa.getPublicExponent()));
        } catch (JwkException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new JwkException("pemInvalid");
        }
    }

    private static X509Certificate certificate(byte[] der) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException | ClassCastException error) {
            throw new JwkException("pemInvalid");
        }
    }

    private static byte[] decodePemBody(String body) {
        StringBuilder base64 = new StringBuilder();
        for (String line : body.split("\\R")) {
            String trimmed = line.trim();
            // 跳过 RFC 1421 风格的头部行（Proc-Type: / DEK-Info:）；带这类头的一般是加密私钥。
            if (trimmed.isEmpty() || trimmed.contains(":")) {
                continue;
            }
            base64.append(trimmed);
        }
        try {
            byte[] der = Base64.getDecoder().decode(base64.toString().replaceAll("\\s", ""));
            if (der.length == 0) {
                throw new JwkException("pemInvalid");
            }
            return der;
        } catch (IllegalArgumentException error) {
            throw new JwkException("pemInvalid");
        }
    }

    // ==========================================
    // 验签
    // ==========================================

    /** 用给定密钥集验证紧凑 JWT。 */
    public JwtVerification verify(String token, List<JwkKey> keys, VerifyOptions options) {
        return new JwtVerifier(clock.instant(), options == null ? VerifyOptions.defaults() : options)
                .verify(token, keys == null ? List.of() : keys);
    }

    // ==========================================
    // 共用工具
    // ==========================================

    /**
     * 严格 base64url 解码（RFC 7515 §2：无填充、只允许 URL 安全字母表）。
     *
     * <p>最后还要重编码比对一次：尾部多余比特不为零的串（例如 {@code AB} 与 {@code AA} 解出同一字节）
     * 会让同一把钥对应多种写法，进而得出不同指纹。</p>
     */
    static byte[] decodeBase64Url(String value, String member) {
        if (value.indexOf('=') >= 0) {
            throw new JwkException("base64urlPadding", member);
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!allowed) {
                throw new JwkException("base64urlIllegal", member);
            }
        }
        if (value.length() % 4 == 1) {
            throw new JwkException("base64urlIllegal", member);
        }
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        if (!base64Url(decoded).equals(value)) {
            throw new JwkException("base64urlNonCanonical", member);
        }
        return decoded;
    }

    static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static JsonNode readJson(String json) {
        try {
            JsonNode node = STRICT_READER.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw new JwkException("invalidJson", "empty");
            }
            return node;
        } catch (IOException error) {
            String message = error.getMessage() == null ? "" : error.getMessage();
            int newline = message.indexOf('\n');
            throw new JwkException("invalidJson", newline > 0 ? message.substring(0, newline) : message);
        }
    }

    static String pretty(JsonNode node) {
        try {
            return Json.prettyMapper().writeValueAsString(node);
        } catch (IOException error) {
            return node.toString();
        }
    }

    private static String requiredString(JsonNode node, String member) {
        JsonNode value = node.get(member);
        if (value == null || value.isNull()) {
            throw new JwkException("missingMember", member);
        }
        if (!value.isTextual()) {
            throw new JwkException("memberNotString", member);
        }
        return value.asText();
    }

    private static String optionalString(JsonNode node, String member) {
        JsonNode value = node.get(member);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new JwkException("memberNotString", member);
        }
        return value.asText();
    }

    private static PublicKey generate(String algorithm, java.security.spec.KeySpec spec) {
        try {
            return KeyFactory.getInstance(algorithm).generatePublic(spec);
        } catch (GeneralSecurityException | RuntimeException error) {
            throw new JwkException("keyBuildFailed", algorithm);
        }
    }

    /** y² ≡ x³ + ax + b (mod p)，且坐标都在 [0, p) 内。无效曲线攻击靠的就是跳过这一步。 */
    private static boolean isOnCurve(ECPoint point, EllipticCurve curve) {
        BigInteger p = ((ECFieldFp) curve.getField()).getP();
        BigInteger x = point.getAffineX();
        BigInteger y = point.getAffineY();
        if (x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0) {
            return false;
        }
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right = x.pow(3).add(curve.getA().multiply(x)).add(curve.getB()).mod(p);
        return left.equals(right);
    }

    static Curve identifyCurve(ECParameterSpec parameters) {
        for (Curve curve : EC_CURVES.values()) {
            ECParameterSpec known = curve.parameters();
            if (known.getCurve().equals(parameters.getCurve())
                    && known.getOrder().equals(parameters.getOrder())
                    && known.getGenerator().equals(parameters.getGenerator())) {
                return curve;
            }
        }
        throw new JwkException("unsupportedCurve", String.valueOf(parameters.getCurve().getField().getFieldSize()));
    }

    static ECParameterSpec curveParameters(String name) {
        Curve curve = EC_CURVES.get(name);
        return curve == null ? null : curve.parameters();
    }

    private static boolean samePublicKey(PublicKey a, PublicKey b) {
        if (a instanceof RSAPublicKey && b instanceof RSAPublicKey) {
            return ((RSAPublicKey) a).getModulus().equals(((RSAPublicKey) b).getModulus())
                    && ((RSAPublicKey) a).getPublicExponent().equals(((RSAPublicKey) b).getPublicExponent());
        }
        if (a instanceof ECPublicKey && b instanceof ECPublicKey) {
            return ((ECPublicKey) a).getW().equals(((ECPublicKey) b).getW());
        }
        return Arrays.equals(a.getEncoded(), b.getEncoded());
    }

    private static boolean hasLeadingZero(byte[] bytes) {
        return bytes.length > 1 && bytes[0] == 0;
    }

    static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static byte[] fixedLength(BigInteger value, int length) {
        byte[] bytes = unsigned(value);
        if (bytes.length == length) {
            return bytes;
        }
        byte[] padded = new byte[length];
        System.arraycopy(bytes, 0, padded, length - bytes.length, bytes.length);
        return padded;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 已支持的 EC 曲线名（JWK crv）。 */
    public static List<String> supportedEcCurves() {
        return List.copyOf(EC_CURVES.keySet());
    }

    /** 已支持的 OKP 曲线名（JWK crv）。 */
    public static List<String> supportedOkpCurves() {
        return List.copyOf(OKP_CURVES.keySet());
    }

    static final class Curve {
        final String name;
        final String jcaName;
        final int fieldBytes;
        final int bits;
        private volatile ECParameterSpec parameters;

        Curve(String name, String jcaName, int fieldBytes, int bits) {
            this.name = name;
            this.jcaName = jcaName;
            this.fieldBytes = fieldBytes;
            this.bits = bits;
        }

        ECParameterSpec parameters() {
            ECParameterSpec cached = parameters;
            if (cached == null) {
                try {
                    AlgorithmParameters algorithmParameters = AlgorithmParameters.getInstance("EC");
                    algorithmParameters.init(new ECGenParameterSpec(jcaName));
                    cached = algorithmParameters.getParameterSpec(ECParameterSpec.class);
                    parameters = cached;
                } catch (GeneralSecurityException error) {
                    throw new JwkException("unsupportedCurve", name);
                }
            }
            return cached;
        }
    }

    private static final class OkpCurve {
        final String name;
        final String keyFactory;
        final int keyBytes;
        final int bits;
        private final byte[] prefix;

        OkpCurve(String name, String keyFactory, int keyBytes, int bits, String prefixHex) {
            this.name = name;
            this.keyFactory = keyFactory;
            this.keyBytes = keyBytes;
            this.bits = bits;
            this.prefix = new BigInteger(prefixHex, 16).toByteArray();
        }

        byte[] prefix() {
            return prefix.clone();
        }
    }
}
