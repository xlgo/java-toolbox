package com.aqishi.toolbox.feature.security.domain;

import java.util.Optional;

/**
 * 本工具支持验签的 JWS 算法（RFC 7518 / RFC 8037，外加 RFC 9864 的 Ed25519 / Ed448 全名算法）。
 *
 * <p>算法和密钥类型的对应关系集中在这里：「HS256 令牌却选中了 RSA 公钥」这类算法混淆，
 * 靠的就是逐一比对 {@link #kty()} 和 {@link #curve()}，而不是信任令牌头里的声明。</p>
 */
public enum JoseAlgorithm {
    RS256("RS256", Family.RSA_PKCS1, "SHA256withRSA", "SHA-256", 32, null),
    RS384("RS384", Family.RSA_PKCS1, "SHA384withRSA", "SHA-384", 48, null),
    RS512("RS512", Family.RSA_PKCS1, "SHA512withRSA", "SHA-512", 64, null),
    PS256("PS256", Family.RSA_PSS, "RSASSA-PSS", "SHA-256", 32, null),
    PS384("PS384", Family.RSA_PSS, "RSASSA-PSS", "SHA-384", 48, null),
    PS512("PS512", Family.RSA_PSS, "RSASSA-PSS", "SHA-512", 64, null),
    ES256("ES256", Family.ECDSA, "SHA256withECDSA", "SHA-256", 32, "P-256"),
    ES384("ES384", Family.ECDSA, "SHA384withECDSA", "SHA-384", 48, "P-384"),
    ES512("ES512", Family.ECDSA, "SHA512withECDSA", "SHA-512", 64, "P-521"),
    EDDSA("EdDSA", Family.EDDSA, "EdDSA", null, 0, null),
    ED25519("Ed25519", Family.EDDSA, "Ed25519", null, 0, "Ed25519"),
    ED448("Ed448", Family.EDDSA, "Ed448", null, 0, "Ed448"),
    HS256("HS256", Family.HMAC, "HmacSHA256", "SHA-256", 32, null),
    HS384("HS384", Family.HMAC, "HmacSHA384", "SHA-384", 48, null),
    HS512("HS512", Family.HMAC, "HmacSHA512", "SHA-512", 64, null);

    /** 算法族：决定验签路径与所需的密钥类型。 */
    public enum Family {
        RSA_PKCS1, RSA_PSS, ECDSA, EDDSA, HMAC
    }

    private final String joseName;
    private final Family family;
    private final String jcaName;
    private final String hash;
    private final int hashLength;
    private final String curve;

    JoseAlgorithm(String joseName, Family family, String jcaName, String hash, int hashLength, String curve) {
        this.joseName = joseName;
        this.family = family;
        this.jcaName = jcaName;
        this.hash = hash;
        this.hashLength = hashLength;
        this.curve = curve;
    }

    public String joseName() {
        return joseName;
    }

    public Family family() {
        return family;
    }

    /** JCA 的 Signature / Mac 算法名。 */
    public String jcaName() {
        return jcaName;
    }

    /** 摘要算法名（MessageDigest 命名），EdDSA 为 null。 */
    public String hash() {
        return hash;
    }

    /** 摘要字节数；也是 PSS 的盐长度和 HMAC 的最小密钥长度。 */
    public int hashLength() {
        return hashLength;
    }

    /** 要求的曲线；null 表示不限定（RSA、HMAC、泛化的 EdDSA）。 */
    public String curve() {
        return curve;
    }

    /** 该算法要求的 JWK kty。 */
    public String kty() {
        switch (family) {
            case RSA_PKCS1:
            case RSA_PSS:
                return "RSA";
            case ECDSA:
                return "EC";
            case EDDSA:
                return "OKP";
            default:
                return "oct";
        }
    }

    /** ES256/384/512 的 JOSE 原始签名（R||S）字节数，其他算法为 0。 */
    public int ecSignatureLength() {
        switch (this) {
            case ES256:
                return 64;
            case ES384:
                return 96;
            case ES512:
                return 132;
            default:
                return 0;
        }
    }

    /** 该算法能否由给定密钥验签；只看类型与曲线，不看 kid / use。 */
    public boolean acceptsKey(String keyKty, String keyCurve) {
        if (!kty().equals(keyKty)) {
            return false;
        }
        if (family == Family.EDDSA) {
            // EdDSA 只用于 Ed25519 / Ed448；X25519 这类密钥协商曲线不能验签。
            boolean signingCurve = "Ed25519".equals(keyCurve) || "Ed448".equals(keyCurve);
            return signingCurve && (curve == null || curve.equals(keyCurve));
        }
        return curve == null || curve.equals(keyCurve);
    }

    /** JOSE 名区分大小写（RFC 7515 §4.1.1）；{@code none} 与未知算法都返回空。 */
    public static Optional<JoseAlgorithm> fromJoseName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        for (JoseAlgorithm algorithm : values()) {
            if (algorithm.joseName.equals(name)) {
                return Optional.of(algorithm);
            }
        }
        return Optional.empty();
    }
}
