package com.aqishi.toolbox.feature.security.domain;

import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.params.ParametersWithID;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import java.io.ByteArrayInputStream;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 国密（SM2 / SM3）证书相关的密码学支撑。
 *
 * <p>JDK 17 的 SunEC 已经移除了非标准曲线：不认识 sm2p256v1，也没有 SM3withSM2 签名，
 * SM2 密钥生成、证书签发、证书与私钥解析、验签都只能交给 BouncyCastle。
 * 这里只把 provider <em>实例</em>传给具体调用，不注册到全局 {@link java.security.Security}：
 * RSA / ECDSA 仍走 JDK 实现，原有证书的行为不受影响。</p>
 *
 * <p>SM3withSM2 签名使用 GM/T 0009 规定的默认用户 ID（{@code 1234567812345678}），
 * 与 GmSSL、BouncyCastle 和国密 CA 一致。注意上游 OpenSSL 3 签发、验证 X.509 时默认用的是
 * <em>空</em> ID：用它验证本工具签发的证书需加 {@code -vfyopt distid:1234567812345678}，
 * 反过来它默认签出的证书要靠 {@link #verifyReportingId} 的空 ID 回退才能验过。</p>
 */
public final class GmCrypto {

    /** SM2 推荐曲线名（GM/T 0003.5）。 */
    public static final String SM2_CURVE = "sm2p256v1";
    /** 证书与 CSR 使用的签名算法。 */
    public static final String SIGNATURE_ALGORITHM = "SM3withSM2";

    private GmCrypto() {
    }

    /** 懒加载：只有真正用到国密时才实例化 BouncyCastle 的数百个服务。 */
    private static final class Holder {
        static final Provider BC = new BouncyCastleProvider();
    }

    public static Provider provider() {
        return Holder.BC;
    }

    /** 生成 SM2 密钥对；密钥编码只带曲线 OID（命名曲线），OpenSSL 3 / 铜锁 / GmSSL 都能读。 */
    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", provider());
        generator.initialize(new ECGenParameterSpec(SM2_CURVE), new SecureRandom());
        return generator.generateKeyPair();
    }

    /** 判断公钥或私钥是否在 SM2 曲线上。依据编码里的曲线 OID，不依赖具体实现类。 */
    public static boolean isSm2(Key key) {
        if (key == null || key.getEncoded() == null) {
            return false;
        }
        try {
            if (key instanceof PublicKey) {
                return isSm2(SubjectPublicKeyInfo.getInstance(key.getEncoded()));
            }
            if (key instanceof PrivateKey) {
                return isSm2(PrivateKeyInfo.getInstance(key.getEncoded()));
            }
        } catch (RuntimeException notAsn1) {
            return false;
        }
        return false;
    }

    public static boolean isSm2(SubjectPublicKeyInfo info) {
        return info != null && isSm2Curve(info.getAlgorithm().getParameters());
    }

    public static boolean isSm2(PrivateKeyInfo info) {
        return info != null && isSm2Curve(info.getPrivateKeyAlgorithm().getParameters());
    }

    private static boolean isSm2Curve(ASN1Encodable parameters) {
        return parameters != null && GMObjectIdentifiers.sm2p256v1.equals(parameters.toASN1Primitive());
    }

    /** 证书的公钥是 SM2，或由 SM3withSM2 签名（RSA 根签发 SM2 叶子、SM2 根签发 RSA 叶子都算）。 */
    public static boolean involvesSm2(byte[] certificateDer) {
        org.bouncycastle.asn1.x509.Certificate parsed =
                org.bouncycastle.asn1.x509.Certificate.getInstance(certificateDer);
        return GMObjectIdentifiers.sm2sign_with_sm3.equals(parsed.getSignatureAlgorithm().getAlgorithm())
                || isSm2(parsed.getSubjectPublicKeyInfo());
    }

    /** 解析单张 DER 证书。 */
    public static X509Certificate parseCertificate(byte[] der) throws CertificateException {
        List<X509Certificate> certificates = parseCertificates(der);
        if (certificates.isEmpty()) {
            throw new CertificateException("No certificate found");
        }
        return certificates.get(0);
    }

    /**
     * 解析 PEM 或 DER（可含多张）证书。
     *
     * <p>普通证书仍由 JDK 解析；涉及 SM2 的证书改由 BouncyCastle 重新解析——JDK 能读出结构，
     * 但它不认识 SM2 曲线，{@code getPublicKey()} 与 {@code verify()} 都会失败。</p>
     */
    public static List<X509Certificate> parseCertificates(byte[] data) throws CertificateException {
        Collection<? extends Certificate> parsed;
        try {
            parsed = CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(data));
        } catch (CertificateException jdkRejected) {
            // 某些 JDK 版本在解码 SM2 公钥时就直接失败，整批交给 BouncyCastle
            parsed = CertificateFactory.getInstance("X.509", provider())
                    .generateCertificates(new ByteArrayInputStream(data));
        }
        List<X509Certificate> result = new ArrayList<>();
        CertificateFactory bcFactory = null;
        for (Certificate certificate : parsed) {
            if (!(certificate instanceof X509Certificate x509)) {
                continue;
            }
            byte[] der = x509.getEncoded();
            if (involvesSm2(der) && !isBouncyCastle(x509)) {
                if (bcFactory == null) {
                    bcFactory = CertificateFactory.getInstance("X.509", provider());
                }
                x509 = (X509Certificate) bcFactory.generateCertificate(new ByteArrayInputStream(der));
            }
            result.add(x509);
        }
        return result;
    }

    private static boolean isBouncyCastle(X509Certificate certificate) {
        return certificate.getClass().getName().startsWith("org.bouncycastle.");
    }

    /** 用颁发者公钥验证证书签名；SM3withSM2 或 SM2 公钥时交给 BouncyCastle。 */
    public static void verify(X509Certificate certificate, PublicKey issuerKey) throws GeneralSecurityException {
        if (isSm2(issuerKey) || involvesSm2(certificate.getEncoded())) {
            certificate.verify(issuerKey, provider());
        } else {
            certificate.verify(issuerKey);
        }
    }

    /** SM3withSM2 证书签名所用的 SM2 用户 ID。 */
    public enum Sm2SignerId {
        /** 不是 SM3withSM2 签名，无所谓用户 ID。 */
        NOT_SM2,
        /** GM/T 0009 默认 ID {@code 1234567812345678}，符合国标。 */
        STANDARD,
        /** 空 ID：上游 OpenSSL 3 的默认行为，密码学上有效但不符合国标。 */
        EMPTY
    }

    /**
     * 验证签名并报告 SM2 用户 ID。
     *
     * <p>先按国标默认 ID 验证；失败时再试空 ID。上游 OpenSSL 3 不加 {@code -sigopt distid:...}
     * 签出的证书就是空 ID，签名本身有效，但 GmSSL 与国密 CA 会拒绝——调用方应提示用户，
     * 而不是笼统地报"签名验证失败"，那会让人误以为证书被篡改或签错了 CA。</p>
     *
     * @throws GeneralSecurityException 两种 ID 都验证不过，或不是 SM2 签名且验证失败
     */
    public static Sm2SignerId verifyReportingId(X509Certificate certificate, PublicKey issuerKey)
            throws GeneralSecurityException {
        if (!GMObjectIdentifiers.sm2sign_with_sm3.getId().equals(certificate.getSigAlgOID())) {
            verify(certificate, issuerKey);
            return Sm2SignerId.NOT_SM2;
        }
        try {
            certificate.verify(issuerKey, provider());
            return Sm2SignerId.STANDARD;
        } catch (GeneralSecurityException standardFailed) {
            if (isSm2(issuerKey) && verifyWithId(certificate, issuerKey, new byte[0])) {
                return Sm2SignerId.EMPTY;
            }
            throw standardFailed;
        }
    }

    private static boolean verifyWithId(X509Certificate certificate, PublicKey issuerKey, byte[] id)
            throws GeneralSecurityException {
        SM2Signer signer = new SM2Signer();
        signer.init(false, new ParametersWithID(ECUtil.generatePublicKeyParameter(issuerKey), id));
        byte[] tbs = certificate.getTBSCertificate();
        signer.update(tbs, 0, tbs.length);
        return signer.verifySignature(certificate.getSignature());
    }

    /** 把 SubjectPublicKeyInfo 转成 JCA 公钥；SM2 公钥只有 BouncyCastle 能还原。 */
    public static PublicKey toPublicKey(SubjectPublicKeyInfo info) throws Exception {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        if (isSm2(info)) {
            converter.setProvider(provider());
        }
        return converter.getPublicKey(info);
    }

    /** 把 PrivateKeyInfo 转成 JCA 私钥；SM2 私钥只有 BouncyCastle 能还原。 */
    public static PrivateKey toPrivateKey(PrivateKeyInfo info) throws Exception {
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        if (isSm2(info)) {
            converter.setProvider(provider());
        }
        return converter.getPrivateKey(info);
    }
}
