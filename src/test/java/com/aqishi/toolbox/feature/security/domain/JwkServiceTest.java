package com.aqishi.toolbox.feature.security.domain;

import com.aqishi.toolbox.feature.security.domain.JwtVerification.CheckId;
import com.aqishi.toolbox.feature.security.domain.JwtVerification.Status;
import com.aqishi.toolbox.feature.security.domain.JwtVerification.Verdict;
import com.aqishi.toolbox.util.Json;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwkServiceTest {

    /** RFC 7638 §3.1 的示例密钥。 */
    private static final String RFC7638_KEY = "{\"kty\":\"RSA\","
            + "\"n\":\"0vx7agoebGcQSuuPiLJXZptN9nndrQmbXEps2aiAFbWhM78LhWx4cbbfAAtVT86zwu1RK7aPFFxuhDR1L6tSoc_BJECP"
            + "ebWKRXjBZCiFV4n3oknjhMstn64tZ_2W-5JsGY4Hc5n9yBXArwl93lqt7_RN5w6Cf0h4QyQ5v-65YGjQR0_FDW2QvzqY368QQMicA"
            + "taSqzs8KJZgnYb9c7d0zgdAZHzu6qMQvRL5hajrn1n91CbOpbISD08qNLyrdkt-bFTWhAI4vMQFh6WeZu0fM4lFd2NcRwr3XPks"
            + "INHaQ-G_xBniIqbw0Ls1jF44-csFCur-kEgU8awapJzKnqDKgw\","
            + "\"e\":\"AQAB\",\"alg\":\"RS256\",\"kid\":\"2011-04-29\"}";

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private static KeyPair rsa;
    private static KeyPair ec256;
    private static KeyPair ec384;
    private static KeyPair ed25519;

    private final JwkService service = new JwkService(Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator rsaGenerator = KeyPairGenerator.getInstance("RSA");
        rsaGenerator.initialize(2048);
        rsa = rsaGenerator.generateKeyPair();
        ec256 = ecKeyPair("secp256r1");
        ec384 = ecKeyPair("secp384r1");
        ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static KeyPair ecKeyPair(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    // ==========================================
    // 解析与指纹
    // ==========================================

    @Test
    void rfc7638ExampleThumbprint() {
        JwkKey key = single(RFC7638_KEY);

        assertEquals("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs", key.thumbprint());
        assertEquals("RSA", key.kty());
        assertEquals("2011-04-29", key.kid());
        assertEquals(2048, key.sizeBits());
        assertFalse(key.hasPrivateMembers());
        assertTrue(key.warnings().isEmpty(), key.warnings().toString());
    }

    @Test
    void parsesJwksAndCollectsPerKeyFailures() {
        String jwks = "{\"keys\":[" + RFC7638_KEY + ",{\"kty\":\"EC\",\"kid\":\"bad\",\"crv\":\"P-999\","
                + "\"x\":\"AA\",\"y\":\"AA\"}," + jwkJson(ec256, "ec", "ES256") + "]}";

        JwkSet set = service.parse(jwks);

        assertEquals(2, set.keys().size());
        assertEquals(1, set.failures().size());
        assertEquals("bad", set.failures().get(0).kid());
        assertEquals("unsupportedCurve", set.failures().get(0).error().getCode());
        assertFalse(set.singleKey());
    }

    @Test
    void reportsDuplicateKids() {
        String jwks = "{\"keys\":[" + jwkJson(rsa, "same", null) + "," + jwkJson(ec256, "same", null) + "]}";

        assertEquals(List.of("same"), service.parse(jwks).duplicateKids());
    }

    @Test
    void strictBase64UrlRejectsPaddingIllegalCharsAndNonCanonical() {
        JwkException padding = assertThrows(JwkException.class,
                () -> service.parse(RFC7638_KEY.replace("\"AQAB\"", "\"AQAB=\"")));
        assertEquals("base64urlPadding", padding.getCode());
        assertEquals(List.of("e"), padding.getParams());

        JwkException illegal = assertThrows(JwkException.class,
                () -> service.parse(RFC7638_KEY.replace("0vx7agoe", "0vx7ag+e")));
        assertEquals("base64urlIllegal", illegal.getCode());
        assertEquals(List.of("n"), illegal.getParams());

        JwkException nonCanonical = assertThrows(JwkException.class,
                () -> service.parse("{\"kty\":\"oct\",\"k\":\"AB\"}"));
        assertEquals("base64urlNonCanonical", nonCanonical.getCode());
    }

    @Test
    void flagsPrivateMembersWithoutEchoingThem() throws Exception {
        String withPrivate = RFC7638_KEY.replace("\"e\":\"AQAB\"", "\"e\":\"AQAB\",\"d\":\"c2VjcmV0dmFsdWU\"");

        JwkKey key = single(withPrivate);

        assertTrue(key.hasPrivateMembers());
        assertEquals(List.of("d"), key.privateMembers());
        assertEquals(JwkKey.WarningCode.PRIVATE_MEMBERS, key.warnings().get(0).code());
        assertFalse(key.publicJwkJson().contains("c2VjcmV0dmFsdWU"));
        assertFalse(key.toString().contains("c2VjcmV0dmFsdWU"));
        assertFalse(Json.mapper().readTree(key.publicJwkJson()).has("d"));
        assertEquals("NzbLsXh8uDCcd-6MNwXF4W_7noWXFZAfHkxZsRGC9Xs", key.thumbprint());
    }

    @Test
    void octKeyIsFlaggedAsSecret() {
        JwkKey key = single("{\"kty\":\"oct\",\"kid\":\"h\",\"k\":\"" + b64("0123456789abcdef0123456789abcdef") + "\"}");

        assertTrue(key.isSymmetric());
        assertEquals(List.of("k"), key.privateMembers());
        assertEquals(256, key.sizeBits());
        assertNull(key.publicKey());
        assertThrows(JwkException.class, () -> service.publicKeyPem(key));
    }

    @Test
    void rejectsEcPointOffCurveAndWrongLength() throws Exception {
        Map<String, Object> jwk = members(ec256);
        byte[] y = Base64.getUrlDecoder().decode((String) jwk.get("y"));
        y[y.length - 1] ^= 1;
        jwk.put("y", b64(y));
        JwkException offCurve = assertThrows(JwkException.class,
                () -> service.parse(Json.mapper().writeValueAsString(jwk)));
        assertEquals("ecPointNotOnCurve", offCurve.getCode());

        jwk.put("y", b64(Arrays.copyOf(y, 31)));
        JwkException length = assertThrows(JwkException.class,
                () -> service.parse(Json.mapper().writeValueAsString(jwk)));
        assertEquals("ecPointLength", length.getCode());
    }

    @Test
    void rejectsSillyRsaExponent() {
        JwkException error = assertThrows(JwkException.class,
                () -> service.parse(RFC7638_KEY.replace("\"AQAB\"", "\"AQ\"")));
        assertEquals("rsaExponentInvalid", error.getCode());
    }

    @Test
    void warnsWhenDeclaredAlgDoesNotFitKey() {
        JwkKey key = single(jwkJson(ec256, "ec", "RS256"));

        assertTrue(key.warnings().stream().anyMatch(w -> w.code() == JwkKey.WarningCode.ALG_KTY_MISMATCH));
    }

    // ==========================================
    // PEM
    // ==========================================

    @Test
    void pemRoundTripKeepsThumbprint() {
        for (KeyPair pair : List.of(rsa, ec256, ec384, ed25519)) {
            JwkKey original = single(jwkJson(pair, null, null));
            String pem = service.publicKeyPem(original);
            assertTrue(pem.startsWith("-----BEGIN PUBLIC KEY-----\n"));
            for (String line : pem.split("\n")) {
                assertTrue(line.length() <= 64, line);
            }

            JwkKey converted = single(service.pemToJwk(pem, "", "sig", ""));

            assertEquals(original.thumbprint(), converted.thumbprint());
            // kid 默认取指纹
            assertEquals(original.thumbprint(), converted.kid());
            assertEquals("sig", converted.use());
        }
    }

    @Test
    void pemToJwkFromPkcs1RsaPublicKey() throws Exception {
        byte[] pkcs1 = SubjectPublicKeyInfo.getInstance(rsa.getPublic().getEncoded())
                .parsePublicKey().getEncoded();
        String pem = JwkService.pem("RSA PUBLIC KEY", pkcs1);

        JwkKey converted = single(service.pemToJwk(pem, "k1", null, "PS256"));

        assertEquals("k1", converted.kid());
        assertEquals("PS256", converted.alg());
        assertEquals(single(jwkJson(rsa, null, null)).thumbprint(), converted.thumbprint());
    }

    @Test
    void pemToJwkFromCertificateAddsX5cAndExportsLeaf() throws Exception {
        X509Certificate certificate = selfSigned(ec256);
        String pem = JwkService.pem("CERTIFICATE", certificate.getEncoded());

        String json = service.pemToJwk(pem, null, null, "ES256");
        JwkKey key = single(json);

        assertTrue(key.x5cPresent());
        assertEquals(1, key.certificates().size());
        assertTrue(key.warnings().isEmpty(), key.warnings().toString());
        assertEquals(pem, service.certificatePem(key));
        assertTrue(json.contains("x5t#S256"));
    }

    @Test
    void pemToJwkRejectsAlgForWrongKeyTypeAndPrivateKeys() {
        String pem = JwkService.pem("PUBLIC KEY", ec256.getPublic().getEncoded());
        JwkException mismatch = assertThrows(JwkException.class, () -> service.pemToJwk(pem, null, null, "RS256"));
        assertEquals("algKeyMismatch", mismatch.getCode());

        String privatePem = JwkService.pem("PRIVATE KEY", ec256.getPrivate().getEncoded());
        assertEquals("pemPrivateKey",
                assertThrows(JwkException.class, () -> service.pemToJwk(privatePem, null, null, null)).getCode());
    }

    @Test
    void x5cKeyMismatchIsWarned() throws Exception {
        X509Certificate other = selfSigned(ec384);
        Map<String, Object> jwk = members(ec256);
        jwk.put("x5c", List.of(Base64.getEncoder().encodeToString(other.getEncoded())));

        JwkKey key = single(Json.mapper().writeValueAsString(jwk));

        assertTrue(key.warnings().stream().anyMatch(w -> w.code() == JwkKey.WarningCode.X5C_KEY_MISMATCH));
    }

    // ==========================================
    // 验签
    // ==========================================

    @Test
    void verifiesRsaPkcs1AndPss() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));
        for (String alg : List.of("RS256", "RS384", "RS512", "PS256", "PS384", "PS512")) {
            String token = sign(alg, "r1", claims(), rsa.getPrivate());

            JwtVerification result = service.verify(token, keys, JwkService.VerifyOptions.defaults());

            assertEquals(Verdict.VALID, result.verdict(), alg + " " + result.checks());
            assertEquals("r1", result.key().kid());
            assertEquals(alg, result.algorithm());
            assertTrue(result.payloadJson().contains("\"sub\""));
        }
    }

    @Test
    void verifiesEcdsaAndEdDsa() throws Exception {
        List<JwkKey> keys = keys(jwkJson(ec256, "p256", null), jwkJson(ec384, "p384", null),
                jwkJson(ed25519, "ed", null));

        assertValid(service.verify(sign("ES256", "p256", claims(), ec256.getPrivate()), keys, null));
        assertValid(service.verify(sign("ES384", "p384", claims(), ec384.getPrivate()), keys, null));
        assertValid(service.verify(sign("EdDSA", "ed", claims(), ed25519.getPrivate()), keys, null));
    }

    @Test
    void verifiesHmacWithOctKey() throws Exception {
        byte[] secret = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        List<JwkKey> keys = keys("{\"kty\":\"oct\",\"kid\":\"h\",\"k\":\"" + b64(secret) + "\"}");

        assertValid(service.verify(hmac("HS256", "h", claims(), secret), keys, null));
        JwtVerification wrong = service.verify(hmac("HS256", "h", claims(), "nope-nope-nope-nope-nope-nope-00"
                .getBytes(StandardCharsets.UTF_8)), keys, null);
        assertEquals(Verdict.INVALID, wrong.verdict());
        assertEquals("sig.invalid", wrong.check(CheckId.SIGNATURE).code());
    }

    @Test
    void selectsSingleCompatibleKeyWithoutKid() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null), jwkJson(ec256, "e1", null));

        JwtVerification result = service.verify(sign("ES256", null, claims(), ec256.getPrivate()), keys, null);

        assertValid(result);
        assertEquals("key.single", result.check(CheckId.KEY).code());
        assertEquals("e1", result.key().kid());
    }

    @Test
    void reportsAmbiguityWithoutKid() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "a", null), RFC7638_KEY);

        JwtVerification result = service.verify(sign("RS256", null, claims(), rsa.getPrivate()), keys, null);

        assertEquals(Verdict.UNVERIFIABLE, result.verdict());
        assertEquals("key.ambiguous", result.check(CheckId.KEY).code());
    }

    @Test
    void tamperedPayloadFails() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));
        String token = sign("RS256", "r1", claims(), rsa.getPrivate());
        String[] parts = token.split("\\.");
        Map<String, Object> evil = claims();
        evil.put("sub", "admin");
        String tampered = parts[0] + "." + b64(Json.mapper().writeValueAsBytes(evil)) + "." + parts[2];

        JwtVerification result = service.verify(tampered, keys, null);

        assertEquals(Verdict.INVALID, result.verdict());
        assertEquals("sig.invalid", result.check(CheckId.SIGNATURE).code());
    }

    @Test
    void unknownKidIsUnverifiable() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));

        JwtVerification result = service.verify(sign("RS256", "other", claims(), rsa.getPrivate()), keys, null);

        assertEquals(Verdict.UNVERIFIABLE, result.verdict());
        assertEquals("key.kidNotFound", result.check(CheckId.KEY).code());
        assertEquals(Status.SKIP, result.check(CheckId.SIGNATURE).status());
    }

    @Test
    void algorithmConfusionWithRsaPublicKeyAsHmacSecretFails() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));
        byte[] publicPem = service.publicKeyPem(keys.get(0)).getBytes(StandardCharsets.US_ASCII);

        for (String kid : Arrays.asList("r1", null)) {
            JwtVerification forged = service.verify(hmac("HS256", kid, claims(), publicPem), keys, null);
            assertEquals(Verdict.INVALID, forged.verdict(), String.valueOf(kid));
            assertEquals("key.confusion", forged.check(CheckId.KEY).code());
            assertEquals(Status.SKIP, forged.check(CheckId.SIGNATURE).status());
        }
        // 用 DER 字节当密钥同样不行
        JwtVerification der = service.verify(hmac("HS256", "r1", claims(), rsa.getPublic().getEncoded()), keys, null);
        assertEquals(Verdict.INVALID, der.verdict());
    }

    @Test
    void keyTypeAndCurveMismatchFail() throws Exception {
        List<JwkKey> keys = keys(jwkJson(ec256, "e1", null), jwkJson(ec384, "e2", null));

        JwtVerification rsOnEc = service.verify(sign("RS256", "e1", claims(), rsa.getPrivate()), keys, null);
        assertEquals(Verdict.INVALID, rsOnEc.verdict());
        assertEquals("key.typeMismatch", rsOnEc.check(CheckId.KEY).code());

        JwtVerification wrongCurve = service.verify(sign("ES256", "e2", claims(), ec256.getPrivate()), keys, null);
        assertEquals(Verdict.INVALID, wrongCurve.verdict());
        assertEquals("key.curveMismatch", wrongCurve.check(CheckId.KEY).code());
    }

    @Test
    void ecdsaRejectsWrongLengthAndZeroSignatures() throws Exception {
        List<JwkKey> keys = keys(jwkJson(ec256, "e1", null));
        String token = sign("ES256", "e1", claims(), ec256.getPrivate());
        String signingInput = token.substring(0, token.lastIndexOf('.'));

        JwtVerification der = service.verify(signingInput + "." + b64(new byte[70]), keys, null);
        assertEquals("sig.badLength", der.check(CheckId.SIGNATURE).code());

        JwtVerification zero = service.verify(signingInput + "." + b64(new byte[64]), keys, null);
        assertEquals(Verdict.INVALID, zero.verdict());
        assertEquals("sig.invalid", zero.check(CheckId.SIGNATURE).code());
    }

    @Test
    void noneAlgorithmIsRejected() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));
        for (String alg : List.of("none", "None", "NONE")) {
            String token = b64(("{\"alg\":\"" + alg + "\"}").getBytes(StandardCharsets.UTF_8)) + "."
                    + b64(Json.mapper().writeValueAsBytes(claims())) + ".";

            JwtVerification result = service.verify(token, keys, null);

            assertEquals(Verdict.INVALID, result.verdict());
            assertEquals("alg.none", result.check(CheckId.ALGORITHM).code());
        }
    }

    @Test
    void malformedTokens() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));

        assertEquals("structure.parts", service.verify("a.b", keys, null).check(CheckId.STRUCTURE).code());
        assertEquals("structure.base64", service.verify("eyJ=.e30.", keys, null).check(CheckId.STRUCTURE).code());
        String duplicate = b64("{\"alg\":\"RS256\",\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        JwtVerification dup = service.verify(duplicate + ".e30.", keys, null);
        assertEquals(Verdict.MALFORMED, dup.verdict());
        assertEquals("structure.json", dup.check(CheckId.STRUCTURE).code());
    }

    @Test
    void critHeaderIsRejected() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("kid", "r1");
        header.put("crit", List.of("exp"));

        JwtVerification result = service.verify(signWithHeader(header, claims(), rsa.getPrivate()), keys, null);

        assertEquals(Verdict.INVALID, result.verdict());
        assertEquals(Status.PASS, result.check(CheckId.SIGNATURE).status());
        assertEquals("crit.unsupported", result.check(CheckId.CRIT).code());
    }

    @Test
    void expNbfIatHonourLeewayAndFixedClock() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));
        long now = NOW.getEpochSecond();
        JwkService.VerifyOptions leeway60 = new JwkService.VerifyOptions(60, null, null);

        Map<String, Object> recentlyExpired = claims();
        recentlyExpired.put("exp", now - 30);
        JwtVerification inLeeway = service.verify(sign("RS256", "r1", recentlyExpired, rsa.getPrivate()), keys, leeway60);
        assertEquals(Verdict.VALID, inLeeway.verdict());
        assertEquals("exp.withinLeeway", inLeeway.check(CheckId.EXP).code());
        JwtVerification strict = service.verify(sign("RS256", "r1", recentlyExpired, rsa.getPrivate()), keys,
                new JwkService.VerifyOptions(0, null, null));
        assertEquals(Verdict.INVALID, strict.verdict());
        assertEquals("exp.expired", strict.check(CheckId.EXP).code());
        assertEquals(30L, strict.check(CheckId.EXP).params().get(1));

        Map<String, Object> notYet = claims();
        notYet.put("nbf", now + 120);
        JwtVerification early = service.verify(sign("RS256", "r1", notYet, rsa.getPrivate()), keys, leeway60);
        assertEquals(Verdict.INVALID, early.verdict());
        assertEquals("nbf.notYet", early.check(CheckId.NBF).code());
        notYet.put("nbf", now + 30);
        assertEquals(Verdict.VALID,
                service.verify(sign("RS256", "r1", notYet, rsa.getPrivate()), keys, leeway60).verdict());

        Map<String, Object> future = claims();
        future.put("iat", now + 3600);
        JwtVerification fromFuture = service.verify(sign("RS256", "r1", future, rsa.getPrivate()), keys, leeway60);
        assertEquals("iat.future", fromFuture.check(CheckId.IAT).code());
        assertEquals(Verdict.INVALID, fromFuture.verdict());

        Map<String, Object> noExp = claims();
        noExp.remove("exp");
        JwtVerification missing = service.verify(sign("RS256", "r1", noExp, rsa.getPrivate()), keys, leeway60);
        assertEquals(Status.WARN, missing.check(CheckId.EXP).status());
        assertEquals(Verdict.VALID, missing.verdict());
        assertEquals(NOW, missing.now());
    }

    @Test
    void issuerAndAudienceArray() throws Exception {
        List<JwkKey> keys = keys(jwkJson(rsa, "r1", null));
        Map<String, Object> claims = claims();
        claims.put("aud", List.of("api://one", "api://two"));
        String token = sign("RS256", "r1", claims, rsa.getPrivate());

        JwtVerification ok = service.verify(token, keys,
                new JwkService.VerifyOptions(60, "https://issuer.example", "api://two"));
        assertEquals(Verdict.VALID, ok.verdict(), ok.checks().toString());
        assertEquals("aud.ok", ok.check(CheckId.AUD).code());
        assertEquals("iss.ok", ok.check(CheckId.ISS).code());

        JwtVerification badAud = service.verify(token, keys, new JwkService.VerifyOptions(60, null, "api://three"));
        assertEquals(Verdict.INVALID, badAud.verdict());
        assertEquals("aud.mismatch", badAud.check(CheckId.AUD).code());
        assertEquals(Status.SKIP, badAud.check(CheckId.ISS).status());

        JwtVerification badIss = service.verify(token, keys, new JwkService.VerifyOptions(60, "https://evil", null));
        assertEquals("iss.mismatch", badIss.check(CheckId.ISS).code());
        assertEquals(Verdict.INVALID, badIss.verdict());
    }

    @Test
    void keyUseEncIsNotUsedForVerification() throws Exception {
        String jwk = jwkJson(rsa, "r1", null).replace("{", "{\"use\":\"enc\",");
        JwtVerification result = service.verify(sign("RS256", "r1", claims(), rsa.getPrivate()), keys(jwk), null);

        assertEquals(Verdict.INVALID, result.verdict());
        assertEquals("key.useEnc", result.check(CheckId.KEY).code());
    }

    // ==========================================
    // 辅助
    // ==========================================

    private void assertValid(JwtVerification result) {
        assertEquals(Verdict.VALID, result.verdict(), result.checks().toString());
        assertNotNull(result.key());
    }

    private JwkKey single(String json) {
        JwkSet set = service.parse(json);
        assertEquals(1, set.keys().size(), set.failures().toString());
        return set.keys().get(0);
    }

    private List<JwkKey> keys(String... jwks) {
        JwkSet set = service.parse("{\"keys\":[" + String.join(",", jwks) + "]}");
        assertTrue(set.failures().isEmpty(), set.failures().toString());
        return set.keys();
    }

    /** 走 PEM → JWK 生成 JWK，顺便覆盖转换路径。 */
    private String jwkJson(KeyPair pair, String kid, String alg) {
        try {
            Map<String, Object> jwk = members(pair);
            if (kid != null) {
                jwk.put("kid", kid);
            }
            if (alg != null) {
                jwk.put("alg", alg);
            }
            return Json.mapper().writeValueAsString(jwk);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> members(KeyPair pair) throws Exception {
        String pem = JwkService.pem("PUBLIC KEY", pair.getPublic().getEncoded());
        Map<String, Object> jwk = Json.mapper().readValue(service.pemToJwk(pem, "tmp", null, null), Map.class);
        jwk.remove("kid");
        return new LinkedHashMap<>(jwk);
    }

    private static Map<String, Object> claims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "https://issuer.example");
        claims.put("sub", "user-1");
        claims.put("iat", NOW.getEpochSecond() - 10);
        claims.put("exp", NOW.getEpochSecond() + 600);
        return claims;
    }

    private static String sign(String alg, String kid, Map<String, Object> claims, PrivateKey key) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", alg);
        header.put("typ", "JWT");
        if (kid != null) {
            header.put("kid", kid);
        }
        return signWithHeader(header, claims, key);
    }

    private static String signWithHeader(Map<String, Object> header, Map<String, Object> claims, PrivateKey key)
            throws Exception {
        String alg = (String) header.get("alg");
        String input = b64(Json.mapper().writeValueAsBytes(header)) + "." + b64(Json.mapper().writeValueAsBytes(claims));
        byte[] data = input.getBytes(StandardCharsets.US_ASCII);
        byte[] signature;
        String hash = "SHA" + alg.substring(2);
        String hashName = "SHA-" + alg.substring(2);
        if (alg.startsWith("RS")) {
            signature = jcaSign(hash + "withRSA", key, data, null);
        } else if (alg.startsWith("PS")) {
            int saltLength = Integer.parseInt(alg.substring(2)) / 8;
            signature = jcaSign("RSASSA-PSS", key, data, new PSSParameterSpec(hashName, "MGF1",
                    new MGF1ParameterSpec(hashName), saltLength, 1));
        } else if (alg.startsWith("ES")) {
            int size = "ES256".equals(alg) ? 32 : "ES384".equals(alg) ? 48 : 66;
            signature = derToJose(jcaSign(hash + "withECDSA", key, data, null), size);
        } else {
            signature = jcaSign("Ed25519", key, data, null);
        }
        return input + "." + b64(signature);
    }

    private static byte[] jcaSign(String algorithm, PrivateKey key, byte[] data, PSSParameterSpec pss)
            throws Exception {
        Signature signer = Signature.getInstance(algorithm);
        if (pss != null) {
            signer.setParameter(pss);
        }
        signer.initSign(key);
        signer.update(data);
        return signer.sign();
    }

    private static String hmac(String alg, String kid, Map<String, Object> claims, byte[] secret) throws Exception {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", alg);
        if (kid != null) {
            header.put("kid", kid);
        }
        String input = b64(Json.mapper().writeValueAsBytes(header)) + "." + b64(Json.mapper().writeValueAsBytes(claims));
        Mac mac = Mac.getInstance("Hmac" + "SHA" + alg.substring(2));
        mac.init(new SecretKeySpec(secret, mac.getAlgorithm()));
        return input + "." + b64(mac.doFinal(input.getBytes(StandardCharsets.US_ASCII)));
    }

    /** DER SEQUENCE { INTEGER r, INTEGER s } → 定长 R||S。 */
    private static byte[] derToJose(byte[] der, int size) {
        int offset = 2;
        if ((der[1] & 0xFF) == 0x81) {
            offset = 3;
        }
        int rLength = der[offset + 1];
        byte[] r = Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        int sOffset = offset + 2 + rLength;
        int sLength = der[sOffset + 1];
        byte[] s = Arrays.copyOfRange(der, sOffset + 2, sOffset + 2 + sLength);
        byte[] out = new byte[size * 2];
        byte[] rUnsigned = JwkService.unsigned(new BigInteger(1, r));
        byte[] sUnsigned = JwkService.unsigned(new BigInteger(1, s));
        System.arraycopy(rUnsigned, 0, out, size - rUnsigned.length, rUnsigned.length);
        System.arraycopy(sUnsigned, 0, out, 2 * size - sUnsigned.length, sUnsigned.length);
        return out;
    }

    private static X509Certificate selfSigned(KeyPair pair) throws Exception {
        X500Name name = new X500Name("CN=jwk-test");
        Date notBefore = Date.from(NOW.minusSeconds(3600));
        Date notAfter = Date.from(NOW.plusSeconds(86400));
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, BigInteger.ONE,
                notBefore, notAfter, name, pair.getPublic());
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String b64(String text) {
        return b64(text.getBytes(StandardCharsets.UTF_8));
    }
}
