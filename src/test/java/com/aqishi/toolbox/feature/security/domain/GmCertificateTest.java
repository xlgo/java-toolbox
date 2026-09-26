package com.aqishi.toolbox.feature.security.domain;

import com.aqishi.toolbox.util.I18n;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithID;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Date;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 国密 SM2 / SM3withSM2 证书的签发、解析与校验。 */
class GmCertificateTest {

    private static final String SM3_WITH_SM2_OID = GMObjectIdentifiers.sm2sign_with_sm3.getId();

    private static CertUtils.CertResult root(String keyAlg) throws Exception {
        return CertUtils.createRootCA(keyAlg, "GM Root", "Org", "", "", "", "CN", 1);
    }

    private static CertUtils.CertResult leaf(CertUtils.CertResult ca, String keyAlg) throws Exception {
        return CertUtils.signCertificate(ca.getCertificatePem(), ca.getPrivateKeyPem(),
                keyAlg, "gm.test", "Org", "", "", "", "CN", "gm.test", 1);
    }

    @Test
    void sm2RootIsSelfSignedWithSm3WithSm2() throws Exception {
        CertUtils.CertResult root = root("SM2");
        X509Certificate cert = root.getCertificate();

        assertEquals(SM3_WITH_SM2_OID, cert.getSigAlgOID());
        assertTrue(GmCrypto.isSm2(cert.getPublicKey()));
        GmCrypto.verify(cert, cert.getPublicKey());
        assertEquals("SM2", CertUtils.parseCertificate(root.getCertificatePem()).getPublicKeyAlgorithm());
        assertEquals(256, CertUtils.parseCertificate(root.getCertificatePem()).getPublicKeySize());
    }

    /** 私钥以 PKCS#8 导出后能被原样读回，并且仍然识别为 SM2——否则这张根证书签不了任何证书。 */
    @Test
    void sm2PrivateKeyRoundTripsThroughPkcs8() throws Exception {
        CertUtils.CertResult root = root("SM2");
        String pem = root.getPrivateKeyPem();

        assertTrue(pem.contains("BEGIN PRIVATE KEY"), pem);
        PrivateKey reread = CertUtils.parsePrivateKeyFromPem(pem);
        assertTrue(GmCrypto.isSm2(reread));
        assertArrayEquals(root.getPrivateKey().getEncoded(), reread.getEncoded());
    }

    @Test
    void sm2RootSignsSm2LeafAndTheChainValidates() throws Exception {
        CertUtils.CertResult root = root("SM2");
        CertUtils.CertResult leaf = leaf(root, "SM2");

        assertEquals(SM3_WITH_SM2_OID, leaf.getCertificate().getSigAlgOID());
        assertTrue(leaf.getCertificate().getKeyUsage()[0], "digitalSignature");
        assertFalse(leaf.getCertificate().getKeyUsage()[2], "SM2 is not an RSA key-transport key");

        CertInspectorService.ChainValidationResult result = new CertInspectorService()
                .validateCertificateChainFromPem(leaf.getCertificatePem() + root.getCertificatePem());
        assertTrue(result.isValid(), String.join("\n", result.getLogs()));
    }

    /**
     * 互通性：证书签名必须能被独立的 SM2 实现、按 GM/T 0009 默认用户 ID 验证，
     * 否则 GmSSL / 铜锁 / 国密 CA 验不过这里签出的证书。
     */
    @Test
    void signatureVerifiesWithStandardSm2SignerAndDefaultUserId() throws Exception {
        CertUtils.CertResult root = root("SM2");
        X509Certificate cert = leaf(root, "SM2").getCertificate();

        SM2Signer signer = new SM2Signer();
        signer.init(false, (ECPublicKeyParameters) ECUtil.generatePublicKeyParameter(
                root.getCertificate().getPublicKey()));
        byte[] tbs = cert.getTBSCertificate();
        signer.update(tbs, 0, tbs.length);
        assertTrue(signer.verifySignature(cert.getSignature()));
    }

    @Test
    void dualCertificatesSeparateSigningAndEncryptionUsage() throws Exception {
        CertUtils.CertResult root = root("SM2");
        CertUtils.Sm2DualCertResult dual = CertUtils.signSm2DualCertificates(
                root.getCertificatePem(), root.getPrivateKeyPem(),
                "gm.test", "Org", "", "", "", "CN", "gm.test, IP:10.0.0.1", 1);
        X509Certificate sign = dual.getSigning().getCertificate();
        X509Certificate enc = dual.getEncryption().getCertificate();

        boolean[] signUsage = sign.getKeyUsage();
        assertTrue(signUsage[0], "digitalSignature");
        assertFalse(signUsage[2] || signUsage[3] || signUsage[4], "signing cert must not carry encryption usage");

        boolean[] encUsage = enc.getKeyUsage();
        assertFalse(encUsage[0], "encryption cert must not sign");
        assertTrue(encUsage[2] && encUsage[3] && encUsage[4], "keyEncipherment + dataEncipherment + keyAgreement");

        assertNotEquals(sign.getPublicKey(), enc.getPublicKey(), "each certificate has its own key pair");
        assertEquals(sign.getSubjectX500Principal(), enc.getSubjectX500Principal());
        // getSubjectAlternativeNames 返回的 UnmodifiableCollection 没有实现 equals，按内容比较
        assertEquals(String.valueOf(sign.getSubjectAlternativeNames()),
                String.valueOf(enc.getSubjectAlternativeNames()));
        GmCrypto.verify(sign, root.getCertificate().getPublicKey());
        GmCrypto.verify(enc, root.getCertificate().getPublicKey());

        CertUtils.CertInfo encInfo = CertUtils.parseCertificate(dual.getEncryption().getCertificatePem());
        assertTrue(encInfo.hasDataEncipherment());
        assertTrue(encInfo.hasKeyAgreement());
    }

    /** 签名算法跟随 CA 私钥：RSA 根签 SM2 叶子用 SHA256withRSA，SM2 根签 RSA 叶子用 SM3withSM2。 */
    @Test
    void crossAlgorithmIssuanceFollowsTheCaKey() throws Exception {
        CertUtils.CertResult rsaRoot = root("RSA 2048");
        X509Certificate sm2Leaf = leaf(rsaRoot, "SM2").getCertificate();
        assertEquals("SHA256withRSA", sm2Leaf.getSigAlgName());
        assertTrue(GmCrypto.isSm2(sm2Leaf.getPublicKey()));
        GmCrypto.verify(sm2Leaf, rsaRoot.getCertificate().getPublicKey());

        CertUtils.CertResult sm2Root = root("SM2");
        CertUtils.CertResult rsaLeaf = leaf(sm2Root, "RSA 2048");
        assertEquals(SM3_WITH_SM2_OID, rsaLeaf.getCertificate().getSigAlgOID());
        CertInspectorService.ChainValidationResult result = new CertInspectorService()
                .validateCertificateChainFromPem(rsaLeaf.getCertificatePem() + sm2Root.getCertificatePem());
        assertTrue(result.isValid(), String.join("\n", result.getLogs()));
    }

    /** GmSSL / 铜锁默认输出 SEC1（EC PRIVATE KEY）格式的 SM2 私钥，拿来当 CA 私钥也要能签发。 */
    @Test
    void acceptsSec1Sm2CaKeyAsProducedByGmssl() throws Exception {
        CertUtils.CertResult root = root("SM2");
        StringWriter sec1 = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sec1)) {
            writer.writeObject(root.getPrivateKey());
        }
        assertTrue(sec1.toString().contains("BEGIN EC PRIVATE KEY"), sec1.toString());

        CertUtils.CertResult leaf = CertUtils.signCertificate(root.getCertificatePem(), sec1.toString(),
                "SM2", "gm.test", "Org", "", "", "", "CN", "", 1);
        GmCrypto.verify(leaf.getCertificate(), root.getCertificate().getPublicKey());
    }

    @Test
    void parsesAndVerifiesSm2Csr() throws Exception {
        KeyPair keys = GmCrypto.generateKeyPair();
        PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(
                new X500Name("CN=gm.test, O=Org, C=CN"), keys.getPublic())
                .build(new JcaContentSignerBuilder(GmCrypto.SIGNATURE_ALGORITHM)
                        .setProvider(GmCrypto.provider()).build(keys.getPrivate()));
        StringWriter pem = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(pem)) {
            writer.writeObject(csr);
        }

        CertInspectorService.CsrInfo info = new CertInspectorService().parseCsr(pem.toString());

        assertEquals("SM2", info.getPublicKeyAlgorithm());
        assertEquals(SM3_WITH_SM2_OID, info.getSignatureAlgorithm());
        assertTrue(info.isSignatureValid());
    }

    @Test
    void inspectsSm2Pkcs12KeyStore() throws Exception {
        CertUtils.CertResult root = root("SM2");
        CertUtils.CertResult leaf = leaf(root, "SM2");
        KeyStore store = KeyStore.getInstance("PKCS12", GmCrypto.provider());
        store.load(null, null);
        char[] password = "changeit".toCharArray();
        store.setKeyEntry("gm", leaf.getPrivateKey(), password,
                new Certificate[]{leaf.getCertificate(), root.getCertificate()});
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        store.store(bytes, password);

        List<CertInspectorService.Pkcs12EntryInfo> entries =
                new CertInspectorService().inspectPkcs12(bytes.toByteArray(), password);

        assertEquals(1, entries.size());
        assertEquals("SM2", entries.get(0).getKeyAlg());
        assertTrue(entries.get(0).isHasPrivateKey());
        assertEquals(2, entries.get(0).getChainLength());
    }

    /**
     * 上游 OpenSSL 3 默认用空 SM2 用户 ID 签发。签名有效，不能报"签名失败"误导用户，
     * 但要提示它不符合国标。
     */
    @Test
    void chainSignedWithEmptySm2IdIsValidButWarned() throws Exception {
        CertUtils.CertResult root = root("SM2");
        KeyPair leafKeys = GmCrypto.generateKeyPair();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                root.getCertificate().getSubjectX500Principal(), BigInteger.TEN,
                new Date(System.currentTimeMillis() - 60_000), new Date(System.currentTimeMillis() + 86_400_000),
                new javax.security.auth.x500.X500Principal("CN=openssl-style"), leafKeys.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        X509Certificate leaf = GmCrypto.parseCertificate(
                builder.build(emptyIdSigner(root.getPrivateKey())).getEncoded());

        assertEquals(GmCrypto.Sm2SignerId.EMPTY,
                GmCrypto.verifyReportingId(leaf, root.getCertificate().getPublicKey()));
        assertEquals(GmCrypto.Sm2SignerId.STANDARD,
                GmCrypto.verifyReportingId(root.getCertificate(), root.getCertificate().getPublicKey()));

        CertInspectorService.ChainValidationResult result = new CertInspectorService()
                .validateCertificateChainFromPem(CertUtils.toPem(leaf) + root.getCertificatePem());
        assertTrue(result.isValid(), String.join("\n", result.getLogs()));
        assertTrue(result.getLogs().contains(I18n.get("tool.certinspector.sm2EmptyId", 0)),
                String.join("\n", result.getLogs()));
    }

    /** 与 OpenSSL 3 默认行为相同：SM3withSM2，但用户 ID 为空。 */
    private static ContentSigner emptyIdSigner(PrivateKey caKey) throws Exception {
        org.bouncycastle.crypto.params.AsymmetricKeyParameter key = ECUtil.generatePrivateKeyParameter(caKey);
        ByteArrayOutputStream tbs = new ByteArrayOutputStream();
        return new ContentSigner() {
            @Override
            public AlgorithmIdentifier getAlgorithmIdentifier() {
                return new AlgorithmIdentifier(GMObjectIdentifiers.sm2sign_with_sm3);
            }

            @Override
            public OutputStream getOutputStream() {
                return tbs;
            }

            @Override
            public byte[] getSignature() {
                try {
                    SM2Signer signer = new SM2Signer();
                    signer.init(true, new ParametersWithID(
                            new ParametersWithRandom(key, new SecureRandom()), new byte[0]));
                    byte[] data = tbs.toByteArray();
                    signer.update(data, 0, data.length);
                    return signer.generateSignature();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    /** 非国密证书仍由 JDK 解析，原有行为不变。 */
    @Test
    void nonSm2CertificatesStayOnTheJdkImplementation() throws Exception {
        X509Certificate rsa = root("RSA 2048").getCertificate();
        X509Certificate ec = root("EC P-256").getCertificate();

        assertFalse(rsa.getClass().getName().startsWith("org.bouncycastle."), rsa.getClass().getName());
        assertFalse(ec.getClass().getName().startsWith("org.bouncycastle."), ec.getClass().getName());
        assertFalse(GmCrypto.isSm2(ec.getPublicKey()), "P-256 must not be mistaken for SM2");
        assertEquals("EC", CertUtils.parseCertificate(CertUtils.toPem(ec)).getPublicKeyAlgorithm());
    }
}
