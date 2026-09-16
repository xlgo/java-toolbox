package com.aqishi.toolbox.feature.security.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookSignatureServiceTest {

    private static final String SECRET = "it's a secret to everybody";
    private static final String BODY = "{\"event\":\"order.paid\",\"id\":\"1001\"}";

    private WebhookSignatureService service;

    @BeforeEach
    void setUp() {
        service = new WebhookSignatureService();
    }

    @Test
    void githubPresetSignsTheRawBody() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("github");
        String expected = hmacHex("HmacSHA256", SECRET, BODY);

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(SECRET)
                .body(BODY)
                .receivedSignature("sha256=" + expected));

        assertTrue(result.isSuccess());
        assertEquals(expected, result.getComputedSignature());
        assertTrue(result.isMatched());
        assertEquals(WebhookSignatureService.Freshness.NOT_APPLICABLE, result.getFreshness());
    }

    @Test
    void hexComparisonIgnoresCaseButStillDetectsMismatch() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("github");
        String expected = hmacHex("HmacSHA256", SECRET, BODY);

        assertTrue(service.evaluate(request(preset).secret(SECRET).body(BODY)
                .receivedSignature("sha256=" + expected.toUpperCase(Locale.ROOT))).isMatched());
        assertFalse(service.evaluate(request(preset).secret(SECRET).body(BODY)
                .receivedSignature("sha256=" + expected.replace('a', 'b'))).isMatched());
    }

    @Test
    void stripePresetSignsTimestampAndBody() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("stripe");
        String timestamp = "1712345678";
        String expected = hmacHex("HmacSHA256", SECRET, timestamp + "." + BODY);

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(SECRET)
                .body(BODY)
                .timestamp(timestamp)
                .receivedSignature(expected)
                .currentEpochSeconds(Long.parseLong(timestamp) + 30));

        assertTrue(result.isMatched());
        assertEquals(timestamp + "." + BODY, result.getSignedPayload());
        assertEquals(WebhookSignatureService.Freshness.FRESH, result.getFreshness());
        assertEquals(30L, result.getSkewSeconds());
    }

    @Test
    void flagsTimestampOutsideReplayWindow() {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("stripe");
        String timestamp = "1712345678";

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(SECRET)
                .body(BODY)
                .timestamp(timestamp)
                .replayWindowSeconds(300)
                .currentEpochSeconds(Long.parseLong(timestamp) + 3600));

        assertEquals(WebhookSignatureService.Freshness.EXPIRED, result.getFreshness());
        assertEquals(3600L, result.getSkewSeconds());
    }

    @Test
    void acceptsMillisecondTimestamps() {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("dingtalk");

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(SECRET)
                .timestamp("1712345678000")
                .currentEpochSeconds(1712345678L + 10));

        assertEquals(WebhookSignatureService.Freshness.FRESH, result.getFreshness());
        assertEquals(10L, result.getSkewSeconds());
    }

    @Test
    void dingtalkPresetEncodesBase64OverTimestampAndSecret() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("dingtalk");
        String timestamp = "1712345678";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "\n" + SECRET).getBytes(StandardCharsets.UTF_8)));

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(SECRET)
                .timestamp(timestamp)
                .receivedSignature(expected)
                .currentEpochSeconds(Long.parseLong(timestamp)));

        assertEquals(expected, result.getComputedSignature());
        assertTrue(result.isMatched());
    }

    /** Base64 的大小写是有意义的，不能像十六进制那样折叠。 */
    @Test
    void base64ComparisonIsCaseSensitive() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("dingtalk");
        String timestamp = "1712345678";
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "\n" + SECRET).getBytes(StandardCharsets.UTF_8)));

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(SECRET)
                .timestamp(timestamp)
                .receivedSignature(expected.toLowerCase(Locale.ROOT))
                .currentEpochSeconds(Long.parseLong(timestamp)));

        assertFalse(result.isMatched());
    }

    @Test
    void larkPresetHashesConcatenationWithoutKey() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("lark");
        String timestamp = "1712345678";
        String nonce = "abc123";
        String expected = hex(MessageDigest.getInstance("SHA-256")
                .digest((timestamp + nonce + SECRET + BODY).getBytes(StandardCharsets.UTF_8)));

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(SECRET)
                .body(BODY)
                .timestamp(timestamp)
                .nonce(nonce)
                .receivedSignature(expected)
                .currentEpochSeconds(Long.parseLong(timestamp)));

        assertEquals(expected, result.getComputedSignature());
        assertTrue(result.isMatched());
    }

    @Test
    void wecomPresetSortsPartsBeforeHashing() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("wecom");
        String token = "QDG6eK";
        String timestamp = "1409659589";
        String nonce = "1372623149";
        String echostr = "1616140317555161061";
        String[] parts = {token, timestamp, nonce, echostr};
        java.util.Arrays.sort(parts);
        String expected = hex(MessageDigest.getInstance("SHA-1")
                .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8)));

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(token)
                .timestamp(timestamp)
                .nonce(nonce)
                .body(echostr)
                .receivedSignature(expected)
                .currentEpochSeconds(Long.parseLong(timestamp)));

        assertEquals(expected, result.getComputedSignature());
        assertTrue(result.isMatched());
    }

    @Test
    void gitlabPresetComparesTokenDirectly() {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("gitlab");

        assertTrue(service.evaluate(request(preset).secret(SECRET)
                .receivedSignature(SECRET)).isMatched());
        assertFalse(service.evaluate(request(preset).secret(SECRET)
                .receivedSignature(SECRET + "x")).isMatched());
    }

    @Test
    void wechatPayPresetVerifiesRsaSignature() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("wechatpay");
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        String timestamp = "1712345678";
        String nonce = "n0nce";
        String payload = timestamp + "\n" + nonce + "\n" + BODY + "\n";
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .body(BODY)
                .timestamp(timestamp)
                .nonce(nonce)
                .publicKeyPem(pem)
                .receivedSignature(signature)
                .currentEpochSeconds(Long.parseLong(timestamp)));

        assertTrue(result.isSuccess());
        assertTrue(result.isMatched());
    }

    @Test
    void asymmetricVerificationRequiresAKey() {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("wechatpay");

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .body(BODY)
                .receivedSignature("whatever"));

        assertFalse(result.isSuccess());
        assertEquals("error.missingPublicKey", result.getErrorCode());
    }

    @Test
    void computesWithoutComparingWhenNoSignatureGiven() throws Exception {
        WebhookSignatureService.Preset preset = WebhookSignatureService.preset("github");

        WebhookSignatureService.Result result = service.evaluate(request(preset)
                .secret(SECRET).body(BODY));

        assertTrue(result.isSuccess());
        assertFalse(result.isCompared());
        assertFalse(result.isMatched());
        assertEquals(hmacHex("HmacSHA256", SECRET, BODY), result.getComputedSignature());
    }

    @Test
    void extractsStripeStyleHeader() {
        String[] extracted = service.extractSignature("t=1712345678,v1=5257a869e7,v0=deadbeef");

        assertEquals("5257a869e7", extracted[0]);
        assertEquals("1712345678", extracted[1]);
    }

    @Test
    void extractsPrefixedHeader() {
        String[] extracted = service.extractSignature("sha256=5257a869e7");

        assertEquals("5257a869e7", extracted[0]);
        assertEquals("", extracted[1]);
    }

    /** Base64 末尾的 '=' 是填充，不能被当成算法前缀剥掉。 */
    @Test
    void keepsBase64PaddingIntact() {
        String base64 = Base64.getEncoder().encodeToString("padd".getBytes(StandardCharsets.UTF_8));

        assertTrue(base64.endsWith("=="), base64);
        assertEquals(base64, service.extractSignature(base64)[0]);
    }

    private WebhookSignatureService.Request request(WebhookSignatureService.Preset preset) {
        return new WebhookSignatureService.Request()
                .scheme(preset.getScheme())
                .algorithm(preset.getAlgorithm())
                .encoding(preset.getEncoding())
                .template(preset.getTemplate())
                .signaturePrefix(preset.getSignaturePrefix());
    }

    private static String hmacHex(String algorithm, String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance(algorithm);
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm));
        return hex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] raw) {
        StringBuilder text = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            text.append(Character.forDigit((value >> 4) & 0xF, 16));
            text.append(Character.forDigit(value & 0xF, 16));
        }
        return text.toString();
    }
}
