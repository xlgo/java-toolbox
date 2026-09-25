package com.aqishi.toolbox.feature.security.domain;

import com.aqishi.toolbox.feature.security.domain.JwtVerification.Check;
import com.aqishi.toolbox.feature.security.domain.JwtVerification.CheckId;
import com.aqishi.toolbox.feature.security.domain.JwtVerification.Status;
import com.aqishi.toolbox.feature.security.domain.JwtVerification.Verdict;
import com.fasterxml.jackson.databind.JsonNode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.SignatureException;
import java.security.interfaces.ECPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 一次 JWT 验签的执行过程。每次验签新建一个实例，持有本次的检查清单。
 *
 * <p>算法只信任「密钥能做什么」，不信任令牌头「说自己是什么」：先按 kid 选出密钥，
 * 再核对 alg 与密钥类型是否一致，不一致直接判失败，绝不拿 RSA 公钥的字节去当 HMAC 密钥
 * （经典的算法混淆攻击）。</p>
 */
final class JwtVerifier {

    /** 这些选钥失败只说明「验不了」，不说明令牌是假的。 */
    private static final Set<String> UNVERIFIABLE_CODES = Set.of(
            "key.noKeys", "key.kidNotFound", "key.ambiguous", "key.noCompatible");

    /** NumericDate 上限：公元 3 万年左右，再大基本是把毫秒当成了秒。 */
    private static final BigDecimal MAX_NUMERIC_DATE = BigDecimal.valueOf(1_000_000_000_000L);

    private final Instant now;
    private final long leeway;
    private final JwkService.VerifyOptions options;
    private final List<Check> checks = new ArrayList<>();

    JwtVerifier(Instant now, JwkService.VerifyOptions options) {
        this.now = now;
        this.options = options;
        this.leeway = Math.max(0, options.leewaySeconds());
    }

    JwtVerification verify(String rawToken, List<JwkKey> keys) {
        String token = rawToken == null ? "" : rawToken.trim();
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = token.substring(7).trim();
        }
        if (token.isEmpty()) {
            return malformed("structure.empty", null, null);
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return malformed("structure.parts", null, null, parts.length);
        }

        JsonNode header;
        try {
            header = decodeJsonPart(parts[0], "header");
        } catch (JwkException error) {
            return malformed(error.getCode(), null, null, error.getParams().toArray());
        }
        String headerJson = JwkService.pretty(header);
        JsonNode payload;
        try {
            payload = decodeJsonPart(parts[1], "payload");
        } catch (JwkException error) {
            return malformed(error.getCode(), headerJson, null, error.getParams().toArray());
        }
        String payloadJson = JwkService.pretty(payload);
        byte[] signature;
        try {
            signature = JwkService.decodeBase64Url(parts[2], "signature");
        } catch (JwkException error) {
            return malformed("structure.base64", headerJson, payloadJson, "signature");
        }
        add(CheckId.STRUCTURE, Status.PASS, "structure.ok");

        String algName = header.path("alg").isTextual() ? header.get("alg").asText() : null;
        String headerKid = header.path("kid").isTextual() ? header.get("kid").asText() : null;
        JoseAlgorithm algorithm = checkAlgorithm(header, algName);
        checkCrit(header);

        JwkKey key = null;
        if (algorithm == null) {
            add(CheckId.KEY, Status.SKIP, "key.skipped");
            add(CheckId.SIGNATURE, Status.SKIP, "sig.skipped");
        } else {
            key = selectKey(algorithm, headerKid, keys);
            if (key == null) {
                add(CheckId.SIGNATURE, Status.SKIP, "sig.skipped");
            } else {
                byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
                checkSignature(algorithm, key, signingInput, signature);
            }
        }

        checkExpiry(payload);
        checkNotBefore(payload);
        checkIssuedAt(payload);
        checkIssuer(payload);
        checkAudience(payload);

        return new JwtVerification(verdict(), algName, headerKid, key, checks, headerJson, payloadJson, now);
    }

    // ==========================================
    // 结构
    // ==========================================

    private JsonNode decodeJsonPart(String part, String name) {
        byte[] bytes;
        try {
            bytes = JwkService.decodeBase64Url(part, name);
        } catch (JwkException error) {
            throw new JwkException("structure.base64", name);
        }
        JsonNode node;
        try {
            node = JwkService.readJson(new String(bytes, StandardCharsets.UTF_8));
        } catch (JwkException error) {
            throw new JwkException("structure.json", name);
        }
        if (!node.isObject()) {
            throw new JwkException("structure.json", name);
        }
        return node;
    }

    private JwtVerification malformed(String code, String headerJson, String payloadJson, Object... params) {
        add(CheckId.STRUCTURE, Status.FAIL, code, params);
        return new JwtVerification(Verdict.MALFORMED, null, null, null, checks, headerJson, payloadJson, now);
    }

    // ==========================================
    // 算法与选钥
    // ==========================================

    private JoseAlgorithm checkAlgorithm(JsonNode header, String algName) {
        if (algName == null) {
            add(CheckId.ALGORITHM, Status.FAIL, "alg.missing");
            return null;
        }
        // 大小写变体（None / NONE）也按 none 拒绝：有的库曾经只比较了小写。
        if ("none".equals(algName.toLowerCase(Locale.ROOT))) {
            add(CheckId.ALGORITHM, Status.FAIL, "alg.none", algName);
            return null;
        }
        JoseAlgorithm algorithm = JoseAlgorithm.fromJoseName(algName).orElse(null);
        if (algorithm == null) {
            add(CheckId.ALGORITHM, Status.FAIL, "alg.unsupported", algName);
            return null;
        }
        add(CheckId.ALGORITHM, Status.PASS, "alg.ok", algName);
        return algorithm;
    }

    /** RFC 7515 §4.1.11：不认识 crit 里列出的扩展就必须拒绝，本工具不实现任何扩展。 */
    private void checkCrit(JsonNode header) {
        if (!header.has("crit")) {
            return;
        }
        List<String> names = new ArrayList<>();
        header.get("crit").forEach(item -> names.add(item.asText()));
        add(CheckId.CRIT, Status.FAIL, "crit.unsupported", String.join(", ", names));
    }

    private JwkKey selectKey(JoseAlgorithm algorithm, String headerKid, List<JwkKey> keys) {
        if (keys.isEmpty()) {
            add(CheckId.KEY, Status.FAIL, "key.noKeys");
            return null;
        }
        JwkKey candidate;
        if (headerKid != null) {
            List<JwkKey> matches = new ArrayList<>();
            for (JwkKey key : keys) {
                if (headerKid.equals(key.kid())) {
                    matches.add(key);
                }
            }
            if (matches.isEmpty()) {
                add(CheckId.KEY, Status.FAIL, "key.kidNotFound", headerKid);
                return null;
            }
            if (matches.size() > 1) {
                List<JwkKey> usable = usable(algorithm, matches);
                if (usable.size() > 1) {
                    add(CheckId.KEY, Status.FAIL, "key.ambiguous", usable.size());
                    return null;
                }
                // 同一 kid 下只有一把与算法相容（比如 RSA 与 EC 并存）时仍可确定；全都不相容就拿第一把报类型不符。
                candidate = usable.isEmpty() ? matches.get(0) : usable.get(0);
            } else {
                candidate = matches.get(0);
            }
            return compatibleOrReport(algorithm, candidate, "key.byKid");
        }

        List<JwkKey> usable = usable(algorithm, keys);
        if (usable.size() == 1) {
            return compatibleOrReport(algorithm, usable.get(0), "key.single");
        }
        if (usable.size() > 1) {
            add(CheckId.KEY, Status.FAIL, "key.ambiguous", usable.size());
            return null;
        }
        if (algorithm.family() == JoseAlgorithm.Family.HMAC) {
            for (JwkKey key : keys) {
                if (!key.isSymmetric()) {
                    add(CheckId.KEY, Status.FAIL, "key.confusion", algorithm.joseName(), key.kty());
                    return null;
                }
            }
        }
        if (keys.size() == 1) {
            // 只有一把钥却不相容：说清楚哪里不相容，比「没有可用密钥」更有用。
            return compatibleOrReport(algorithm, keys.get(0), "key.single");
        }
        add(CheckId.KEY, Status.FAIL, "key.noCompatible", algorithm.joseName());
        return null;
    }

    private List<JwkKey> usable(JoseAlgorithm algorithm, List<JwkKey> keys) {
        List<JwkKey> result = new ArrayList<>();
        for (JwkKey key : keys) {
            if (incompatibility(algorithm, key) == null) {
                result.add(key);
            }
        }
        return result;
    }

    /** 返回不相容的原因（检查结果码 + 参数），相容时返回 null。 */
    private Object[] incompatibility(JoseAlgorithm algorithm, JwkKey key) {
        boolean hmac = algorithm.family() == JoseAlgorithm.Family.HMAC;
        if (hmac && !key.isSymmetric()) {
            return new Object[]{"key.confusion", algorithm.joseName(), key.kty()};
        }
        if (!algorithm.kty().equals(key.kty())) {
            return new Object[]{"key.typeMismatch", algorithm.joseName(), key.kty()};
        }
        if (!algorithm.acceptsKey(key.kty(), key.curve())) {
            return new Object[]{"key.curveMismatch", algorithm.joseName(), String.valueOf(key.curve())};
        }
        if (key.alg() != null && !key.alg().equals(algorithm.joseName())) {
            return new Object[]{"key.algMismatch", key.alg(), algorithm.joseName()};
        }
        if ("enc".equals(key.use())) {
            return new Object[]{"key.useEnc"};
        }
        if (key.keyOps() != null && !key.keyOps().contains("verify")) {
            return new Object[]{"key.opsNoVerify"};
        }
        return null;
    }

    private JwkKey compatibleOrReport(JoseAlgorithm algorithm, JwkKey key, String passCode) {
        Object[] problem = incompatibility(algorithm, key);
        if (problem != null) {
            add(CheckId.KEY, Status.FAIL, (String) problem[0], Arrays.copyOfRange(problem, 1, problem.length));
            return null;
        }
        add(CheckId.KEY, Status.PASS, passCode, key.displayId(), key.kty());
        if (algorithm.family() == JoseAlgorithm.Family.HMAC && key.sizeBits() < algorithm.hashLength() * 8) {
            // RFC 7518 §3.2：HMAC 密钥不得短于摘要长度。
            add(CheckId.KEY, Status.WARN, "key.hmacShort", key.sizeBits(), algorithm.hashLength() * 8);
        }
        return key;
    }

    // ==========================================
    // 签名
    // ==========================================

    private void checkSignature(JoseAlgorithm algorithm, JwkKey key, byte[] signingInput, byte[] signature) {
        try {
            boolean valid;
            switch (algorithm.family()) {
                case HMAC: {
                    Mac mac = Mac.getInstance(algorithm.jcaName());
                    mac.init(new SecretKeySpec(key.secret(), algorithm.jcaName()));
                    // 常量时间比较：工具本身就可能被拿来对着线上服务试签名。
                    valid = MessageDigest.isEqual(mac.doFinal(signingInput), signature);
                    break;
                }
                case ECDSA: {
                    int expected = algorithm.ecSignatureLength();
                    if (signature.length != expected) {
                        add(CheckId.SIGNATURE, Status.FAIL, "sig.badLength", expected, signature.length);
                        return;
                    }
                    byte[] der = joseToDer(signature, ((ECPublicKey) key.publicKey()).getParams().getOrder());
                    if (der == null) {
                        valid = false;
                        break;
                    }
                    Signature verifier = Signature.getInstance(algorithm.jcaName());
                    verifier.initVerify(key.publicKey());
                    verifier.update(signingInput);
                    valid = verifier.verify(der);
                    break;
                }
                case RSA_PSS: {
                    Signature verifier = Signature.getInstance("RSASSA-PSS");
                    verifier.setParameter(new PSSParameterSpec(algorithm.hash(), "MGF1",
                            new MGF1ParameterSpec(algorithm.hash()), algorithm.hashLength(), 1));
                    verifier.initVerify(key.publicKey());
                    verifier.update(signingInput);
                    valid = verifier.verify(signature);
                    break;
                }
                default: {
                    Signature verifier = Signature.getInstance(algorithm.jcaName());
                    verifier.initVerify(key.publicKey());
                    verifier.update(signingInput);
                    valid = verifier.verify(signature);
                    break;
                }
            }
            if (valid) {
                add(CheckId.SIGNATURE, Status.PASS, "sig.valid");
            } else {
                add(CheckId.SIGNATURE, Status.FAIL, "sig.invalid");
            }
        } catch (SignatureException error) {
            // 长度不对、编码不对这类问题 JDK 用异常表达，对用户而言就是「签名不对」。
            add(CheckId.SIGNATURE, Status.FAIL, "sig.invalid");
        } catch (GeneralSecurityException | RuntimeException error) {
            add(CheckId.SIGNATURE, Status.FAIL, "sig.error", error.getClass().getSimpleName());
        }
    }

    /**
     * JOSE 的 ECDSA 签名是定长 R||S（RFC 7518 §3.4），JCA 要 DER。
     *
     * <p>R、S 必须落在 [1, n-1]，否则直接判无效：CVE-2022-21449 里 JDK 15–18 对全零签名验签通过，
     * 这里不依赖运行时 JDK 是否打过补丁。</p>
     */
    static byte[] joseToDer(byte[] raw, BigInteger order) {
        int half = raw.length / 2;
        BigInteger r = new BigInteger(1, Arrays.copyOfRange(raw, 0, half));
        BigInteger s = new BigInteger(1, Arrays.copyOfRange(raw, half, raw.length));
        if (r.signum() == 0 || s.signum() == 0 || r.compareTo(order) >= 0 || s.compareTo(order) >= 0) {
            return null;
        }
        byte[] rDer = derInteger(r);
        byte[] sDer = derInteger(s);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        writeDerLength(out, rDer.length + sDer.length);
        out.writeBytes(rDer);
        out.writeBytes(sDer);
        return out.toByteArray();
    }

    private static byte[] derInteger(BigInteger value) {
        byte[] content = value.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x02);
        writeDerLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else if (length <= 0xFF) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xFF);
        }
    }

    // ==========================================
    // 声明
    // ==========================================

    private void checkExpiry(JsonNode payload) {
        if (!payload.has("exp")) {
            add(CheckId.EXP, Status.WARN, "exp.missing");
            return;
        }
        Long exp = numericDate(payload.get("exp"));
        if (exp == null) {
            add(CheckId.EXP, Status.FAIL, "exp.invalid");
            return;
        }
        long nowSeconds = now.getEpochSecond();
        Instant expInstant = Instant.ofEpochSecond(exp);
        if (nowSeconds >= exp + leeway) {
            add(CheckId.EXP, Status.FAIL, "exp.expired", expInstant, nowSeconds - exp);
        } else if (nowSeconds >= exp) {
            add(CheckId.EXP, Status.WARN, "exp.withinLeeway", expInstant, nowSeconds - exp);
        } else {
            add(CheckId.EXP, Status.PASS, "exp.ok", expInstant, exp - nowSeconds);
        }
    }

    private void checkNotBefore(JsonNode payload) {
        if (!payload.has("nbf")) {
            add(CheckId.NBF, Status.SKIP, "nbf.absent");
            return;
        }
        Long nbf = numericDate(payload.get("nbf"));
        if (nbf == null) {
            add(CheckId.NBF, Status.FAIL, "nbf.invalid");
            return;
        }
        long nowSeconds = now.getEpochSecond();
        if (nowSeconds + leeway < nbf) {
            add(CheckId.NBF, Status.FAIL, "nbf.notYet", Instant.ofEpochSecond(nbf), nbf - nowSeconds);
        } else {
            add(CheckId.NBF, Status.PASS, "nbf.ok", Instant.ofEpochSecond(nbf));
        }
    }

    private void checkIssuedAt(JsonNode payload) {
        if (!payload.has("iat")) {
            add(CheckId.IAT, Status.SKIP, "iat.absent");
            return;
        }
        Long iat = numericDate(payload.get("iat"));
        if (iat == null) {
            add(CheckId.IAT, Status.FAIL, "iat.invalid");
            return;
        }
        long nowSeconds = now.getEpochSecond();
        if (iat > nowSeconds + leeway) {
            // 签发时间在未来：通常是签发方时钟漂移，或者拿毫秒当秒填了。
            add(CheckId.IAT, Status.FAIL, "iat.future", Instant.ofEpochSecond(iat), iat - nowSeconds);
        } else {
            add(CheckId.IAT, Status.PASS, "iat.ok", Instant.ofEpochSecond(iat));
        }
    }

    private void checkIssuer(JsonNode payload) {
        String expected = blankToNull(options.expectedIssuer());
        JsonNode iss = payload.get("iss");
        String actual = iss != null && iss.isTextual() ? iss.asText() : null;
        if (expected == null) {
            add(CheckId.ISS, Status.SKIP, "iss.notChecked", actual == null ? "-" : actual);
            return;
        }
        if (iss == null) {
            add(CheckId.ISS, Status.FAIL, "iss.missing", expected);
        } else if (expected.equals(actual)) {
            add(CheckId.ISS, Status.PASS, "iss.ok", actual);
        } else {
            add(CheckId.ISS, Status.FAIL, "iss.mismatch", actual == null ? iss.toString() : actual, expected);
        }
    }

    /** aud 可以是字符串也可以是字符串数组（RFC 7519 §4.1.3），数组里命中任一即可。 */
    private void checkAudience(JsonNode payload) {
        String expected = blankToNull(options.expectedAudience());
        JsonNode aud = payload.get("aud");
        List<String> audiences = new ArrayList<>();
        boolean wellFormed = true;
        if (aud != null) {
            if (aud.isTextual()) {
                audiences.add(aud.asText());
            } else if (aud.isArray()) {
                for (JsonNode item : aud) {
                    if (item.isTextual()) {
                        audiences.add(item.asText());
                    } else {
                        wellFormed = false;
                    }
                }
            } else {
                wellFormed = false;
            }
        }
        String joined = aud == null ? "-" : (wellFormed ? String.join(", ", audiences) : aud.toString());
        if (expected == null) {
            add(CheckId.AUD, Status.SKIP, "aud.notChecked", joined);
            return;
        }
        if (aud == null) {
            add(CheckId.AUD, Status.FAIL, "aud.missing", expected);
        } else if (wellFormed && audiences.contains(expected)) {
            add(CheckId.AUD, Status.PASS, "aud.ok", expected);
        } else {
            add(CheckId.AUD, Status.FAIL, "aud.mismatch", joined, expected);
        }
    }

    /** NumericDate 允许小数（RFC 7519 §2），向下取整到秒；非数字或离谱的值返回 null。 */
    private static Long numericDate(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return null;
        }
        BigDecimal value = node.decimalValue();
        if (value.abs().compareTo(MAX_NUMERIC_DATE) > 0) {
            return null;
        }
        return value.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    // ==========================================
    // 汇总
    // ==========================================

    private Verdict verdict() {
        boolean unverifiable = false;
        for (Check check : checks) {
            if (check.status() != Status.FAIL) {
                continue;
            }
            if (UNVERIFIABLE_CODES.contains(check.code())) {
                unverifiable = true;
            } else {
                return Verdict.INVALID;
            }
        }
        if (unverifiable) {
            return Verdict.UNVERIFIABLE;
        }
        // 兜底：没有明确的「签名有效」就不能给 VALID，哪怕某条路径忘了记 FAIL。
        for (Check check : checks) {
            if (check.id() == CheckId.SIGNATURE && check.status() == Status.PASS) {
                return Verdict.VALID;
            }
        }
        return Verdict.INVALID;
    }

    private void add(CheckId id, Status status, String code, Object... params) {
        checks.add(new Check(id, status, code, List.of(params)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
