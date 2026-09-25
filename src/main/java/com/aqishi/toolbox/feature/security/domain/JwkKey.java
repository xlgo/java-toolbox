package com.aqishi.toolbox.feature.security.domain;

import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

/**
 * 解析并校验过的一把 JWK。
 *
 * <p>这里只暴露公开信息：私有成员（d、p、q……以及 oct 的 k）只记录「出现过哪些名字」，
 * 值本身不进入任何摘要或 {@link #toString()}。oct 密钥的字节只对同包的验签逻辑可见，
 * 界面层拿不到——HMAC 验签需要它，但展示层永远不该把它打印出来。</p>
 */
public final class JwkKey {

    /** 解析时发现的、不致命但值得提醒的问题。 */
    public enum WarningCode {
        /** 带有私钥成员：这份 JWK 不应出现在公开的 JWKS 里。参数：成员名列表。 */
        PRIVATE_MEMBERS,
        /** RSA 模长低于 2048 位。参数：位数。 */
        RSA_WEAK,
        /** 大整数编码带前导零（RFC 7518 §6.3.1 禁止），会改变指纹。参数：成员名。 */
        NON_CANONICAL_INTEGER,
        /** x5c 无法解析为 X.509 证书。 */
        X5C_INVALID,
        /** x5c 叶子证书里的公钥与 JWK 本身的公钥不一致。 */
        X5C_KEY_MISMATCH,
        /** x5t / x5t#S256 与叶子证书指纹不一致。参数：成员名。 */
        X5T_MISMATCH,
        /** 同时出现 use 与 key_ops（RFC 7517 §4.3 建议二者不要同时使用）。 */
        USE_AND_KEY_OPS,
        /** 声明的 alg 与密钥类型或曲线不符。参数：alg。 */
        ALG_KTY_MISMATCH
    }

    /** 一条解析警告。 */
    public record Warning(WarningCode code, List<Object> params) {
        public Warning {
            params = List.copyOf(params);
        }
    }

    private final int index;
    private final String kty;
    private final String kid;
    private final String use;
    private final String alg;
    private final List<String> keyOps;
    private final String curve;
    private final int sizeBits;
    private final String thumbprint;
    private final List<String> privateMembers;
    private final PublicKey publicKey;
    private final byte[] secret;
    private final List<X509Certificate> certificates;
    private final boolean x5cPresent;
    private final boolean x5tPresent;
    private final List<Warning> warnings;
    private final String publicJwkJson;

    JwkKey(int index, String kty, String kid, String use, String alg, List<String> keyOps,
           String curve, int sizeBits, String thumbprint, List<String> privateMembers,
           PublicKey publicKey, byte[] secret, List<X509Certificate> certificates,
           boolean x5cPresent, boolean x5tPresent, List<Warning> warnings, String publicJwkJson) {
        this.index = index;
        this.kty = kty;
        this.kid = kid;
        this.use = use;
        this.alg = alg;
        this.keyOps = keyOps == null ? null : List.copyOf(keyOps);
        this.curve = curve;
        this.sizeBits = sizeBits;
        this.thumbprint = thumbprint;
        this.privateMembers = List.copyOf(privateMembers);
        this.publicKey = publicKey;
        this.secret = secret == null ? null : secret.clone();
        this.certificates = List.copyOf(certificates);
        this.x5cPresent = x5cPresent;
        this.x5tPresent = x5tPresent;
        this.warnings = List.copyOf(warnings);
        this.publicJwkJson = publicJwkJson;
    }

    /** 在 JWKS 中的下标；单个 JWK 为 0。 */
    public int index() {
        return index;
    }

    public String kty() {
        return kty;
    }

    /** 可能为 null。 */
    public String kid() {
        return kid;
    }

    /** 可能为 null。 */
    public String use() {
        return use;
    }

    /** 可能为 null。 */
    public String alg() {
        return alg;
    }

    /** 未声明时为 null（与「声明为空数组」区分开）。 */
    public List<String> keyOps() {
        return keyOps;
    }

    /** EC / OKP 的曲线名，RSA 与 oct 为 null。 */
    public String curve() {
        return curve;
    }

    /** RSA 为模长位数，EC 为域长度，OKP 为公钥位数，oct 为密钥位数。 */
    public int sizeBits() {
        return sizeBits;
    }

    /** RFC 7638 SHA-256 指纹（base64url）。 */
    public String thumbprint() {
        return thumbprint;
    }

    /** RFC 9278 指纹 URI。 */
    public String thumbprintUri() {
        return "urn:ietf:params:oauth:jwk-thumbprint:sha-256:" + thumbprint;
    }

    /** 出现过的私有成员名（只有名字）。 */
    public List<String> privateMembers() {
        return privateMembers;
    }

    public boolean hasPrivateMembers() {
        return !privateMembers.isEmpty();
    }

    /** RSA / EC / OKP 的公钥；oct 为 null。 */
    public PublicKey publicKey() {
        return publicKey;
    }

    public boolean isSymmetric() {
        return "oct".equals(kty);
    }

    /** oct 密钥字节，仅供同包验签使用。 */
    byte[] secret() {
        return secret == null ? null : secret.clone();
    }

    /** x5c 解析出的证书链（可能为空）。 */
    public List<X509Certificate> certificates() {
        return certificates;
    }

    public boolean x5cPresent() {
        return x5cPresent;
    }

    public boolean x5tPresent() {
        return x5tPresent;
    }

    public List<Warning> warnings() {
        return warnings;
    }

    /** 去掉全部私有成员后的 JWK（紧凑 JSON）。 */
    public String publicJwkJson() {
        return publicJwkJson;
    }

    /** 展示用标识：优先 kid，没有就用指纹。 */
    public String displayId() {
        return kid != null && !kid.isEmpty() ? kid : thumbprint;
    }

    @Override
    public String toString() {
        return "JwkKey{kty=" + kty + ", kid=" + kid + ", alg=" + alg + ", thumbprint=" + thumbprint
                + ", private=" + Arrays.toString(privateMembers.toArray()) + "}";
    }
}
