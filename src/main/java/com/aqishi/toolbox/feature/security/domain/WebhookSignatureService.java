package com.aqishi.toolbox.feature.security.domain;

import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Webhook 回调签名的计算与校验。
 *
 * <p>回调调试最常卡在两处：不知道对方到底签的是哪串字节，以及本地算出来的签名
 * 和请求头里的对不上却看不出差在哪。这里把「待签串模板 + 算法 + 编码」三件事显式化，
 * 既能按厂商预设一键还原，也能自定义模板逐字比对。</p>
 *
 * <p>比对一律走 {@link MessageDigest#isEqual}，不用 {@code String.equals}：
 * 这个工具本身会被用来验签，逐字符短路比较会把签名长度和前缀泄漏给计时观测。</p>
 *
 * <p>本类不产出面向用户的文案；失败原因以稳定的错误码返回，由界面层本地化。</p>
 */
public final class WebhookSignatureService {

    /**
     * 可以从签名头里剥掉的算法前缀白名单。
     *
     * <p>不用「等号前是短标识符就剥」这类通用规则：Base64 签名结尾的 {@code =} 是填充，
     * 通用规则会把 {@code cGFkZA==} 切成 {@code =}，而且切完照样像个签名，很难发现。</p>
     */
    private static final java.util.regex.Pattern ALGORITHM_PREFIX = java.util.regex.Pattern
            .compile("(?i)sha1|sha256|sha384|sha512|md5|hmac|v0|v1|sig|signature");

    /** 待签串模板中的占位符。 */
    public static final String PLACEHOLDER_BODY = "{body}";
    public static final String PLACEHOLDER_TIMESTAMP = "{timestamp}";
    public static final String PLACEHOLDER_NONCE = "{nonce}";
    public static final String PLACEHOLDER_SECRET = "{secret}";

    /** 签名方式。 */
    public enum Scheme {
        /** HMAC(secret, payload)。GitHub / Stripe / Slack / 钉钉等。 */
        HMAC,
        /** 直接对拼接串做散列，不带密钥。飞书事件订阅属于此类。 */
        DIGEST,
        /** 把若干参数字典序排序后拼接再散列。企业微信回调 URL 校验属于此类。 */
        SORTED_DIGEST,
        /** 非对称验签，需要对方的公钥或证书。微信支付 v3 属于此类。 */
        RSA_VERIFY,
        /** 不签名，直接比对共享令牌。GitLab 属于此类。 */
        PLAIN_TOKEN
    }

    /** 签名的文本编码。 */
    public enum Encoding {
        HEX_LOWER, HEX_UPPER, BASE64
    }

    /** 厂商预设：把某家回调的签名规则固化成一组默认值。 */
    public static final class Preset {
        private final String id;
        private final Scheme scheme;
        private final String algorithm;
        private final Encoding encoding;
        private final String template;
        private final String headerName;
        private final String signaturePrefix;
        private final boolean usesTimestamp;
        private final boolean usesNonce;

        Preset(String id, Scheme scheme, String algorithm, Encoding encoding, String template,
               String headerName, String signaturePrefix, boolean usesTimestamp, boolean usesNonce) {
            this.id = id;
            this.scheme = scheme;
            this.algorithm = algorithm;
            this.encoding = encoding;
            this.template = template;
            this.headerName = headerName;
            this.signaturePrefix = signaturePrefix;
            this.usesTimestamp = usesTimestamp;
            this.usesNonce = usesNonce;
        }

        public String getId() {
            return id;
        }

        public Scheme getScheme() {
            return scheme;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public Encoding getEncoding() {
            return encoding;
        }

        public String getTemplate() {
            return template;
        }

        public String getHeaderName() {
            return headerName;
        }

        public String getSignaturePrefix() {
            return signaturePrefix;
        }

        public boolean usesTimestamp() {
            return usesTimestamp;
        }

        public boolean usesNonce() {
            return usesNonce;
        }

        /** 该预设是否需要公钥而不是共享密钥。 */
        public boolean needsPublicKey() {
            return scheme == Scheme.RSA_VERIFY;
        }
    }

    private static final Map<String, Preset> PRESETS;

    static {
        Map<String, Preset> presets = new LinkedHashMap<>();
        // GitHub：对原始请求体做 HMAC-SHA256，签名带 sha256= 前缀。
        presets.put("github", new Preset("github", Scheme.HMAC, "HmacSHA256", Encoding.HEX_LOWER,
                PLACEHOLDER_BODY, "X-Hub-Signature-256", "sha256=", false, false));
        // GitLab：不签名，直接比对 Secret Token。
        presets.put("gitlab", new Preset("gitlab", Scheme.PLAIN_TOKEN, "", Encoding.HEX_LOWER,
                "", "X-Gitlab-Token", "", false, false));
        // Stripe：待签串是 时间戳.请求体，签名头里取 v1= 那一段。
        presets.put("stripe", new Preset("stripe", Scheme.HMAC, "HmacSHA256", Encoding.HEX_LOWER,
                PLACEHOLDER_TIMESTAMP + "." + PLACEHOLDER_BODY, "Stripe-Signature", "", true, false));
        // Slack：待签串固定以版本号 v0 开头，签名同样带 v0= 前缀。
        presets.put("slack", new Preset("slack", Scheme.HMAC, "HmacSHA256", Encoding.HEX_LOWER,
                "v0:" + PLACEHOLDER_TIMESTAMP + ":" + PLACEHOLDER_BODY,
                "X-Slack-Signature", "v0=", true, false));
        // 钉钉机器人：待签串是 时间戳\n密钥，结果取 Base64。
        presets.put("dingtalk", new Preset("dingtalk", Scheme.HMAC, "HmacSHA256", Encoding.BASE64,
                PLACEHOLDER_TIMESTAMP + "\n" + PLACEHOLDER_SECRET, "sign", "", true, false));
        // 飞书事件订阅：无密钥散列，拼接顺序为 时间戳 nonce 密钥 请求体。
        presets.put("lark", new Preset("lark", Scheme.DIGEST, "SHA-256", Encoding.HEX_LOWER,
                PLACEHOLDER_TIMESTAMP + PLACEHOLDER_NONCE + PLACEHOLDER_SECRET + PLACEHOLDER_BODY,
                "X-Lark-Signature", "", true, true));
        // 企业微信回调校验：token/timestamp/nonce/echostr 字典序排序后拼接做 SHA-1。
        presets.put("wecom", new Preset("wecom", Scheme.SORTED_DIGEST, "SHA-1", Encoding.HEX_LOWER,
                PLACEHOLDER_SECRET + "," + PLACEHOLDER_TIMESTAMP + "," + PLACEHOLDER_NONCE + ","
                        + PLACEHOLDER_BODY, "msg_signature", "", true, true));
        // 微信支付 v3：RSA-SHA256 验签，待签串每段后面都有换行。
        presets.put("wechatpay", new Preset("wechatpay", Scheme.RSA_VERIFY, "SHA256withRSA", Encoding.BASE64,
                PLACEHOLDER_TIMESTAMP + "\n" + PLACEHOLDER_NONCE + "\n" + PLACEHOLDER_BODY + "\n",
                "Wechatpay-Signature", "", true, true));
        // 通用 HMAC：模板与算法完全交给使用者。
        presets.put("custom", new Preset("custom", Scheme.HMAC, "HmacSHA256", Encoding.HEX_LOWER,
                PLACEHOLDER_TIMESTAMP + "." + PLACEHOLDER_BODY, "X-Signature", "", true, true));
        PRESETS = Collections.unmodifiableMap(presets);
    }

    /** 全部预设，按展示顺序。 */
    public static Map<String, Preset> presets() {
        return PRESETS;
    }

    public static Preset preset(String id) {
        Preset preset = PRESETS.get(id);
        if (preset == null) {
            throw new IllegalArgumentException("Unknown webhook preset: " + id);
        }
        return preset;
    }

    /** 支持的 HMAC 算法，按常用度排序。 */
    public static List<String> hmacAlgorithms() {
        return Arrays.asList("HmacSHA256", "HmacSHA1", "HmacSHA512", "HmacSHA384", "HmacMD5");
    }

    /** 支持的散列算法。 */
    public static List<String> digestAlgorithms() {
        return Arrays.asList("SHA-256", "SHA-1", "SHA-512", "MD5");
    }

    /** 一次验签请求。 */
    public static final class Request {
        private Scheme scheme = Scheme.HMAC;
        private String algorithm = "HmacSHA256";
        private Encoding encoding = Encoding.HEX_LOWER;
        private String template = PLACEHOLDER_BODY;
        private String signaturePrefix = "";
        private String secret = "";
        private String body = "";
        private String timestamp = "";
        private String nonce = "";
        private String receivedSignature = "";
        private String publicKeyPem = "";
        private long replayWindowSeconds = 300L;
        private long currentEpochSeconds = System.currentTimeMillis() / 1000L;

        public Request scheme(Scheme value) {
            this.scheme = value;
            return this;
        }

        public Request algorithm(String value) {
            this.algorithm = value;
            return this;
        }

        public Request encoding(Encoding value) {
            this.encoding = value;
            return this;
        }

        public Request template(String value) {
            this.template = value;
            return this;
        }

        public Request signaturePrefix(String value) {
            this.signaturePrefix = value == null ? "" : value;
            return this;
        }

        public Request secret(String value) {
            this.secret = value == null ? "" : value;
            return this;
        }

        public Request body(String value) {
            this.body = value == null ? "" : value;
            return this;
        }

        public Request timestamp(String value) {
            this.timestamp = value == null ? "" : value.trim();
            return this;
        }

        public Request nonce(String value) {
            this.nonce = value == null ? "" : value.trim();
            return this;
        }

        public Request receivedSignature(String value) {
            this.receivedSignature = value == null ? "" : value.trim();
            return this;
        }

        public Request publicKeyPem(String value) {
            this.publicKeyPem = value == null ? "" : value;
            return this;
        }

        public Request replayWindowSeconds(long value) {
            this.replayWindowSeconds = value;
            return this;
        }

        /** 覆盖「当前时间」，用于测试或复盘历史请求。 */
        public Request currentEpochSeconds(long value) {
            this.currentEpochSeconds = value;
            return this;
        }
    }

    /** 时间戳新鲜度判定。 */
    public enum Freshness {
        NOT_APPLICABLE, FRESH, EXPIRED, UNPARSEABLE
    }

    /** 验签结果。 */
    public static final class Result {
        private final boolean success;
        private final String errorCode;
        private final String errorDetail;
        private final String signedPayload;
        private final String computedSignature;
        private final boolean matched;
        private final boolean compared;
        private final Freshness freshness;
        private final long skewSeconds;

        private Result(boolean success, String errorCode, String errorDetail, String signedPayload,
                       String computedSignature, boolean matched, boolean compared,
                       Freshness freshness, long skewSeconds) {
            this.success = success;
            this.errorCode = errorCode;
            this.errorDetail = errorDetail;
            this.signedPayload = signedPayload;
            this.computedSignature = computedSignature;
            this.matched = matched;
            this.compared = compared;
            this.freshness = freshness;
            this.skewSeconds = skewSeconds;
        }

        public boolean isSuccess() {
            return success;
        }

        /** 稳定错误码，界面据此查本地化文案。 */
        public String getErrorCode() {
            return errorCode;
        }

        /** 解析器给出的原始细节，可为 null。 */
        public String getErrorDetail() {
            return errorDetail;
        }

        /** 实际参与签名的字节串，便于逐字比对。 */
        public String getSignedPayload() {
            return signedPayload;
        }

        /** 本地算出的签名；非对称验签时为空。 */
        public String getComputedSignature() {
            return computedSignature;
        }

        public boolean isMatched() {
            return matched;
        }

        /** 是否真的做了比对（未填写收到的签名时为 false）。 */
        public boolean isCompared() {
            return compared;
        }

        public Freshness getFreshness() {
            return freshness;
        }

        /** 请求时间戳与当前时间的偏差秒数，正数表示时间戳更早。 */
        public long getSkewSeconds() {
            return skewSeconds;
        }
    }

    /** 计算签名并与收到的签名比对。 */
    public Result evaluate(Request request) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        Freshness freshness = Freshness.NOT_APPLICABLE;
        long skew = 0L;
        if (usesPlaceholder(request, PLACEHOLDER_TIMESTAMP) && !request.timestamp.isEmpty()) {
            Long epochSeconds = parseEpochSeconds(request.timestamp);
            if (epochSeconds == null) {
                freshness = Freshness.UNPARSEABLE;
            } else {
                skew = request.currentEpochSeconds - epochSeconds;
                freshness = request.replayWindowSeconds <= 0
                        || Math.abs(skew) <= request.replayWindowSeconds
                        ? Freshness.FRESH : Freshness.EXPIRED;
            }
        }

        String received = stripPrefix(request.receivedSignature, request.signaturePrefix);
        boolean hasReceived = !received.isEmpty();

        if (request.scheme == Scheme.PLAIN_TOKEN) {
            boolean matched = hasReceived && constantTimeEquals(request.secret, received, false);
            return new Result(true, null, null, "", request.secret, matched, hasReceived,
                    freshness, skew);
        }

        String payload;
        if (request.scheme == Scheme.SORTED_DIGEST) {
            payload = buildSortedPayload(request);
        } else {
            payload = expand(request.template, request);
        }

        if (request.scheme == Scheme.RSA_VERIFY) {
            if (request.publicKeyPem.trim().isEmpty()) {
                return failure("error.missingPublicKey", null, payload, freshness, skew);
            }
            if (!hasReceived) {
                return new Result(true, null, null, payload, "", false, false, freshness, skew);
            }
            try {
                PublicKey publicKey = readPublicKey(request.publicKeyPem);
                Signature verifier = Signature.getInstance(request.algorithm);
                verifier.initVerify(publicKey);
                verifier.update(payload.getBytes(StandardCharsets.UTF_8));
                boolean matched = verifier.verify(Base64.getDecoder()
                        .decode(received.replaceAll("\\s", "")));
                return new Result(true, null, null, payload, "", matched, true, freshness, skew);
            } catch (IllegalArgumentException badBase64) {
                return failure("error.badSignatureEncoding", badBase64.getMessage(), payload, freshness, skew);
            } catch (Exception error) {
                return failure("error.verifyFailed", Errors.describe(error), payload, freshness, skew);
            }
        }

        String computed;
        try {
            byte[] digest = request.scheme == Scheme.HMAC
                    ? hmac(request.algorithm, request.secret, payload)
                    : digest(request.algorithm, payload);
            computed = encode(digest, request.encoding);
        } catch (Exception error) {
            return failure("error.computeFailed", Errors.describe(error), payload, freshness, skew);
        }

        boolean base64 = request.encoding == Encoding.BASE64;
        boolean matched = hasReceived && constantTimeEquals(computed, received, !base64);
        return new Result(true, null, null, payload, computed, matched, hasReceived, freshness, skew);
    }

    /**
     * 从整条签名头里取出签名本身。
     *
     * <p>Stripe 的 {@code t=...,v1=...}、以及 {@code sha256=...} 这类带前缀的写法都能直接粘进来。
     * 返回长度为 2 的数组：第 0 位是签名，第 1 位是顺带解析出的时间戳（没有则为空串）。</p>
     */
    public String[] extractSignature(String rawHeader) {
        String[] extracted = {"", ""};
        if (rawHeader == null) {
            return extracted;
        }
        String value = rawHeader.trim();
        if (value.isEmpty()) {
            return extracted;
        }
        if (value.contains("=") && (value.contains(",") || value.startsWith("t="))) {
            for (String part : value.split(",")) {
                String segment = part.trim();
                int separator = segment.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = segment.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                String item = segment.substring(separator + 1).trim();
                if ("t".equals(key)) {
                    extracted[1] = item;
                } else if (extracted[0].isEmpty()) {
                    extracted[0] = item;
                }
            }
            if (!extracted[0].isEmpty()) {
                return extracted;
            }
        }
        int separator = value.indexOf('=');
        if (separator > 0 && separator < value.length() - 1 && !value.contains(" ")
                && ALGORITHM_PREFIX.matcher(value.substring(0, separator)).matches()) {
            extracted[0] = value.substring(separator + 1);
            return extracted;
        }
        extracted[0] = value;
        return extracted;
    }

    private boolean usesPlaceholder(Request request, String placeholder) {
        if (request.scheme == Scheme.SORTED_DIGEST) {
            return true;
        }
        return request.template != null && request.template.contains(placeholder);
    }

    private String expand(String template, Request request) {
        if (template == null) {
            return "";
        }
        return template
                .replace(PLACEHOLDER_TIMESTAMP, request.timestamp)
                .replace(PLACEHOLDER_NONCE, request.nonce)
                .replace(PLACEHOLDER_SECRET, request.secret)
                .replace(PLACEHOLDER_BODY, request.body);
    }

    /** 字典序排序拼接：模板里用逗号分隔要参与排序的片段。 */
    private String buildSortedPayload(Request request) {
        List<String> parts = new ArrayList<>();
        for (String raw : expand(request.template, request).split(",", -1)) {
            String part = raw.trim();
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        Collections.sort(parts);
        return String.join("", parts);
    }

    private static byte[] hmac(String algorithm, String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] digest(String algorithm, String payload) throws Exception {
        return MessageDigest.getInstance(algorithm)
                .digest(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(byte[] raw, Encoding encoding) {
        switch (encoding) {
            case BASE64:
                return Base64.getEncoder().encodeToString(raw);
            case HEX_UPPER:
                return Hex.toHexUpper(raw);
            default:
                return Hex.toHex(raw);
        }
    }

    private static String stripPrefix(String signature, String prefix) {
        String value = signature == null ? "" : signature.trim();
        if (prefix != null && !prefix.isEmpty()
                && value.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return value.substring(prefix.length()).trim();
        }
        return value;
    }

    /**
     * 恒定时间比较。{@code ignoreCase} 只用于十六进制签名——那里大小写不影响语义，
     * 而 Base64 的大小写是有意义的，不能一并折叠。
     */
    private static boolean constantTimeEquals(String expected, String actual, boolean ignoreCase) {
        String left = ignoreCase ? expected.toLowerCase(Locale.ROOT) : expected;
        String right = ignoreCase ? actual.toLowerCase(Locale.ROOT) : actual;
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static Long parseEpochSeconds(String timestamp) {
        try {
            long value = Long.parseLong(timestamp.trim());
            // 13 位是毫秒戳，统一折算成秒再比对窗口。
            return timestamp.trim().length() >= 13 ? value / 1000L : value;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** 同时接受裸公钥 PEM 与证书 PEM：平台方给的经常是整张证书。 */
    private static PublicKey readPublicKey(String pem) throws Exception {
        String trimmed = pem.trim();
        if (trimmed.contains("BEGIN CERTIFICATE")) {
            byte[] der = Base64.getMimeDecoder().decode(stripPemArmor(trimmed));
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der));
            return certificate.getPublicKey();
        }
        byte[] der = Base64.getMimeDecoder().decode(stripPemArmor(trimmed));
        return java.security.KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
    }

    private static String stripPemArmor(String pem) {
        return pem.replaceAll("-----(BEGIN|END)[^-]+-----", "").replaceAll("\\s", "");
    }

    private static Result failure(String errorCode, String detail, String payload,
                                  Freshness freshness, long skew) {
        return new Result(false, errorCode, detail, payload, "", false, false, freshness, skew);
    }
}
