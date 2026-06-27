package com.aqishi.toolbox.crypto;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.security.auth.x500.X500Principal;
import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

/**
 * 证书工具类：根证书创建、证书签发、证书解析、PEM 编解码。
 * <p>基于 BouncyCastle bcpkix 实现 X.509 v3 证书操作。</p>
 */
public class CertUtils {

    private static final String BC_PROVIDER = BouncyCastleProvider.PROVIDER_NAME;

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    // ===========================================================
    //  支持的密钥算法
    // ===========================================================
    public static final String[] KEY_ALGORITHMS = {"RSA 2048", "RSA 4096", "EC P-256", "EC P-384", "EC P-521"};
    public static final String[] KEY_ALG_INTERNAL = {"RSA", "RSA", "EC", "EC", "EC"};
    public static final int[] KEY_SIZES = {2048, 4096, 256, 384, 521};

    // ===========================================================
    //  1. 创建自签名根证书
    // ===========================================================
    public static CertResult createRootCA(String keyAlgLabel, String cn, String o, String ou,
                                          String l, String st, String c, int years) throws Exception {
        int idx = indexOfKeyAlg(keyAlgLabel);
        if (idx < 0) throw new IllegalArgumentException("不支持的密钥算法: " + keyAlgLabel);
        return createRootCA(idx, cn, o, ou, l, st, c, years);
    }

    public static CertResult createRootCA(int keyAlgIndex, String cn, String o, String ou,
                                           String l, String st, String c, int years) throws Exception {
        KeyPair keyPair = generateKeyPair(keyAlgIndex);
        String sigAlg = (KEY_ALG_INTERNAL[keyAlgIndex].equals("EC")) ? "SHA256withECDSA" : "SHA256withRSA";

        X500Name issuerName = buildX500Name(cn, o, ou, l, st, c);

        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + (long) years * 365 * 24 * 3600 * 1000);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        // 使用 X500Principal 构造，兼容 JDK X.509
        X500Principal issuerPrincipal = new X500Principal(issuerName.toString());
        X500Principal subjectPrincipal = issuerPrincipal; // 自签名

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerPrincipal, serial, notBefore, notAfter, subjectPrincipal, keyPair.getPublic());

        // CA 基本约束: pathLen = 1
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(1));
        // 密钥用法：证书签名 + CRL 签名
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        // 主题密钥标识
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                createSubjectKeyId(keyPair.getPublic()));

        ContentSigner signer = new JcaContentSignerBuilder(sigAlg)
                .setProvider(BC_PROVIDER).build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BC_PROVIDER).getCertificate(holder);

        return new CertResult(cert, keyPair.getPrivate());
    }

    // ===========================================================
    //  2. 使用根证书签发子证书
    // ===========================================================
    public static CertResult signCertificate(
            String caCertPem, String caKeyPem,
            String keyAlgLabel, String cn, String o, String ou,
            String l, String st, String c,
            String sanDns, int years) throws Exception {

        X509Certificate caCert = parseCertFromPem(caCertPem);
        PrivateKey caKey = parsePrivateKeyFromPem(caKeyPem);

        int idx = indexOfKeyAlg(keyAlgLabel);
        if (idx < 0) throw new IllegalArgumentException("不支持的密钥算法: " + keyAlgLabel);
        KeyPair keyPair = generateKeyPair(idx);
        String sigAlg = (KEY_ALG_INTERNAL[idx].equals("EC")) ? "SHA256withECDSA" : "SHA256withRSA";

        X500Name subjectName = buildX500Name(cn, o, ou, l, st, c);
        X500Principal subjectPrincipal = new X500Principal(subjectName.toString());

        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + (long) years * 365 * 24 * 3600 * 1000);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                caCert.getIssuerX500Principal(), // 使用 JDK X500Principal
                serial, notBefore, notAfter, subjectPrincipal, keyPair.getPublic());

        // 终端证书：非 CA
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        // 密钥用法：数字签名 + 密钥加密
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        // 主题密钥标识
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                createSubjectKeyId(keyPair.getPublic()));
        // 颁发机构密钥标识
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                createAuthorityKeyId(caCert));

        // SAN
        if (sanDns != null && !sanDns.trim().isEmpty()) {
            String[] dnsList = sanDns.split("[,\\s]+");
            List<GeneralName> nameList = new ArrayList<>();
            for (String dns : dnsList) {
                String d = dns.trim();
                if (!d.isEmpty()) {
                    nameList.add(new GeneralName(GeneralName.dNSName, d));
                }
            }
            if (!nameList.isEmpty()) {
                builder.addExtension(Extension.subjectAlternativeName, false,
                        new GeneralNames(nameList.toArray(new GeneralName[0])));
            }
        }

        ContentSigner signer = new JcaContentSignerBuilder(sigAlg)
                .setProvider(BC_PROVIDER).build(caKey);
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider(BC_PROVIDER).getCertificate(holder);

        return new CertResult(cert, keyPair.getPrivate());
    }

    // ===========================================================
    //  3. 证书解析
    // ===========================================================
    public static CertInfo parseCertificate(String pem) throws Exception {
        X509Certificate cert = parseCertFromPem(pem);
        return new CertInfo(cert);
    }

    // ===========================================================
    //  4. PEM 编解码
    // ===========================================================
    public static String toPem(X509Certificate cert) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pw = new JcaPEMWriter(sw)) {
            pw.writeObject(cert);
        }
        return sw.toString();
    }

    public static String toPemPrivateKey(PrivateKey key) throws IOException {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pw = new JcaPEMWriter(sw)) {
            pw.writeObject(key);
        }
        return sw.toString();
    }

    public static X509Certificate parseCertFromPem(String pem) throws Exception {
        String b64 = pem.replaceAll("-----BEGIN[^-]+-----", "")
                .replaceAll("-----END[^-]+-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(b64);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
    }

    public static PrivateKey parsePrivateKeyFromPem(String pem) throws Exception {
        PEMParser parser = new PEMParser(new StringReader(pem));
        Object obj = parser.readObject();
        parser.close();

        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(BC_PROVIDER);

        if (obj instanceof PrivateKeyInfo) {
            return converter.getPrivateKey((PrivateKeyInfo) obj);
        } else if (obj instanceof PEMKeyPair) {
            return converter.getPrivateKey(((PEMKeyPair) obj).getPrivateKeyInfo());
        } else {
            throw new IllegalArgumentException("无法识别的私钥格式: "
                    + (obj == null ? "null" : obj.getClass().getName())
                    + "。请使用未加密的 PKCS#8 或 PKCS#1 PEM 格式。");
        }
    }

    // ===========================================================
    //  辅助方法
    // ===========================================================
    private static KeyPair generateKeyPair(int idx) throws Exception {
        String algorithm = KEY_ALG_INTERNAL[idx];
        int size = KEY_SIZES[idx];
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(algorithm, BC_PROVIDER);
        kpg.initialize(size, new SecureRandom());
        return kpg.generateKeyPair();
    }

    private static X500Name buildX500Name(String cn, String o, String ou,
                                           String l, String st, String c) {
        X500NameBuilder builder = new X500NameBuilder(BCStyle.INSTANCE);
        if (notBlank(cn)) builder.addRDN(BCStyle.CN, cn);
        if (notBlank(o))  builder.addRDN(BCStyle.O, o);
        if (notBlank(ou)) builder.addRDN(BCStyle.OU, ou);
        if (notBlank(l))  builder.addRDN(BCStyle.L, l);
        if (notBlank(st)) builder.addRDN(BCStyle.ST, st);
        if (notBlank(c))  builder.addRDN(BCStyle.C, c);
        return builder.build();
    }

    private static SubjectKeyIdentifier createSubjectKeyId(PublicKey pub) throws Exception {
        byte[] encoded = pub.getEncoded();
        try (ASN1InputStream asn1In = new ASN1InputStream(encoded)) {
            ASN1Primitive asn1 = asn1In.readObject();
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(asn1);
            // bcpkix 的 SubjectKeyIdentifier 需要通过 digest 方式创建
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] keyBytes = spki.getPublicKeyData().getBytes();
            byte[] digest = md.digest(keyBytes);
            return new SubjectKeyIdentifier(digest);
        }
    }

    private static AuthorityKeyIdentifier createAuthorityKeyId(X509Certificate cert) throws Exception {
        byte[] encoded = cert.getPublicKey().getEncoded();
        try (ASN1InputStream asn1In = new ASN1InputStream(encoded)) {
            ASN1Primitive asn1 = asn1In.readObject();
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(asn1);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] keyBytes = spki.getPublicKeyData().getBytes();
            byte[] digest = md.digest(keyBytes);
            return new AuthorityKeyIdentifier(digest);
        }
    }

    private static int indexOfKeyAlg(String label) {
        for (int i = 0; i < KEY_ALGORITHMS.length; i++) {
            if (KEY_ALGORITHMS[i].equals(label)) return i;
        }
        return -1;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ===========================================================
    //  内部类：返回结果
    // ===========================================================

    /** 证书生成结果 */
    public static class CertResult {
        private final X509Certificate certificate;
        private final PrivateKey privateKey;

        public CertResult(X509Certificate certificate, PrivateKey privateKey) {
            this.certificate = certificate;
            this.privateKey = privateKey;
        }

        public X509Certificate getCertificate() { return certificate; }
        public PrivateKey getPrivateKey() { return privateKey; }

        public String getCertificatePem() throws IOException { return toPem(certificate); }
        public String getPrivateKeyPem() throws IOException { return toPemPrivateKey(privateKey); }
    }

    /** 证书解析信息 */
    public static class CertInfo {
        private final String subject;
        private final String issuer;
        private final String serialNumber;
        private final int version;
        private final String sigAlgName;
        private final Date notBefore;
        private final Date notAfter;
        private final boolean expired;
        private final String publicKeyAlgorithm;
        private final int publicKeySize;
        private final String md5Fingerprint;
        private final String sha1Fingerprint;
        private final String sha256Fingerprint;
        private final List<String> subjectAltNames;
        private final boolean isCA;
        private final boolean hasDigitalSignature;
        private final boolean hasKeyEncipherment;
        private final boolean hasKeyCertSign;
        private final String rawPem;

        public CertInfo(X509Certificate cert) throws Exception {
            this.rawPem = toPem(cert);
            this.subject = cert.getSubjectX500Principal().getName();
            this.issuer = cert.getIssuerX500Principal().getName();
            this.serialNumber = cert.getSerialNumber().toString(16).toUpperCase();
            this.version = cert.getVersion();
            this.sigAlgName = cert.getSigAlgName();
            this.notBefore = cert.getNotBefore();
            this.notAfter = cert.getNotAfter();
            this.expired = new Date().after(cert.getNotAfter());
            this.publicKeyAlgorithm = cert.getPublicKey().getAlgorithm();
            this.publicKeySize = getKeySize(cert.getPublicKey());

            this.md5Fingerprint = fingerprint(cert.getEncoded(), "MD5");
            this.sha1Fingerprint = fingerprint(cert.getEncoded(), "SHA-1");
            this.sha256Fingerprint = fingerprint(cert.getEncoded(), "SHA-256");

            this.subjectAltNames = parseSan(cert);

            // keyUsage 数组: digitalSignature(0), nonRepudiation(1), keyEncipherment(2),
            // dataEncipherment(3), keyAgreement(4), keyCertSign(5), cRLSign(6)
            boolean[] keyUsage = cert.getKeyUsage();
            this.hasDigitalSignature = keyUsage != null && keyUsage.length > 0 && keyUsage[0];
            this.hasKeyEncipherment = keyUsage != null && keyUsage.length > 2 && keyUsage[2];
            this.hasKeyCertSign = keyUsage != null && keyUsage.length > 5 && keyUsage[5];

            int pathLen = cert.getBasicConstraints();
            this.isCA = pathLen >= 0;
        }

        public String getSubject() { return subject; }
        public String getIssuer() { return issuer; }
        public String getSerialNumber() { return serialNumber; }
        public int getVersion() { return version; }
        public String getSigAlgName() { return sigAlgName; }
        public Date getNotBefore() { return notBefore; }
        public Date getNotAfter() { return notAfter; }
        public boolean isExpired() { return expired; }
        public String getPublicKeyAlgorithm() { return publicKeyAlgorithm; }
        public int getPublicKeySize() { return publicKeySize; }
        public String getMd5Fingerprint() { return md5Fingerprint; }
        public String getSha1Fingerprint() { return sha1Fingerprint; }
        public String getSha256Fingerprint() { return sha256Fingerprint; }
        public List<String> getSubjectAltNames() { return subjectAltNames; }
        public boolean isCA() { return isCA; }
        public boolean hasDigitalSignature() { return hasDigitalSignature; }
        public boolean hasKeyEncipherment() { return hasKeyEncipherment; }
        public boolean hasKeyCertSign() { return hasKeyCertSign; }
        public String getRawPem() { return rawPem; }

        /** 格式化的解析报告文本 */
        public String toReport() {
            StringBuilder sb = new StringBuilder();
            sb.append("========== 证书信息 ==========\n");
            sb.append("主题 (Subject):   ").append(subject).append("\n");
            sb.append("签发者 (Issuer):  ").append(issuer).append("\n");
            sb.append("序列号 (Serial):  ").append(serialNumber).append("\n");
            sb.append("版本 (Version):   v").append(version).append("\n");
            sb.append("签名算法:         ").append(sigAlgName).append("\n");
            sb.append("有效期从:         ").append(notBefore).append("\n");
            sb.append("有效期至:         ").append(notAfter).append("\n");
            sb.append("是否过期:         ").append(expired ? "是 ⚠️" : "否 ✓").append("\n");
            sb.append("公钥算法:         ").append(publicKeyAlgorithm).append(" (").append(publicKeySize).append(" bit)\n");
            sb.append("是否是 CA:        ").append(isCA ? "是" : "否").append("\n");

            sb.append("密钥用法:         ");
            List<String> usage = new ArrayList<>();
            if (hasDigitalSignature) usage.add("数字签名");
            if (hasKeyEncipherment) usage.add("密钥加密");
            if (hasKeyCertSign) usage.add("证书签名");
            sb.append(usage.isEmpty() ? "未指定" : String.join(", ", usage)).append("\n");

            if (!subjectAltNames.isEmpty()) {
                sb.append("主题备用名 (SAN): ").append(String.join(", ", subjectAltNames)).append("\n");
            }

            sb.append("──────────────────────────────────\n");
            sb.append("指纹 / Fingerprint:\n");
            sb.append("  MD5:    ").append(md5Fingerprint).append("\n");
            sb.append("  SHA-1:  ").append(sha1Fingerprint).append("\n");
            sb.append("  SHA-256:").append(sha256Fingerprint).append("\n");
            sb.append("==================================\n");
            return sb.toString();
        }
    }

    // ===========================================================
    //  内部辅助
    // ===========================================================
    private static String fingerprint(byte[] data, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        byte[] digest = md.digest(data);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digest.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", digest[i]));
        }
        return sb.toString();
    }

    private static List<String> parseSan(X509Certificate cert) {
        List<String> result = new ArrayList<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2) {
                        int type = (Integer) san.get(0);
                        Object value = san.get(1);
                        String prefix;
                        switch (type) {
                            case GeneralName.dNSName:                     prefix = "DNS:";   break;
                            case GeneralName.iPAddress:                   prefix = "IP:";    break;
                            case GeneralName.rfc822Name:                  prefix = "EMAIL:"; break;
                            case GeneralName.uniformResourceIdentifier:  prefix = "URI:";   break;
                            default: prefix = "TYPE" + type + ":"; break;
                        }
                        result.add(prefix + value);
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return result;
    }

    private static int getKeySize(PublicKey key) {
        if (key instanceof RSAPublicKey) {
            return ((RSAPublicKey) key).getModulus().bitLength();
        } else if (key instanceof ECPublicKey) {
            return ((ECPublicKey) key).getParams().getCurve().getField().getFieldSize();
        }
        return 0;
    }
}
