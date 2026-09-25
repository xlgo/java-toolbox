package com.aqishi.toolbox.feature.security.domain;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CertUtilsTest {

    private static CertUtils.CertResult root(String keyAlg) throws Exception {
        return CertUtils.createRootCA(keyAlg, "Test Root", "Org", "", "", "", "CN", 1);
    }

    private static CertUtils.CertResult leaf(CertUtils.CertResult ca, String keyAlg, String san) throws Exception {
        return CertUtils.signCertificate(ca.getCertificatePem(), ca.getPrivateKeyPem(),
                keyAlg, "app.test", "Org", "", "", "", "CN", san, 1);
    }

    /** 回归：签名算法按叶子密钥选择，RSA 根证书签 EC 叶子时必然失败。 */
    @Test
    void rsaRootCanSignEcLeafAndViceVersa() throws Exception {
        CertUtils.CertResult rsaRoot = root("RSA 2048");
        X509Certificate ecLeaf = leaf(rsaRoot, "EC P-256", "app.test").getCertificate();
        ecLeaf.verify(rsaRoot.getCertificate().getPublicKey());
        assertEquals("SHA256withRSA", ecLeaf.getSigAlgName());

        CertUtils.CertResult ecRoot = root("EC P-256");
        X509Certificate rsaLeaf = leaf(ecRoot, "RSA 2048", "app.test").getCertificate();
        rsaLeaf.verify(ecRoot.getCertificate().getPublicKey());
        assertEquals("SHA256withECDSA", rsaLeaf.getSigAlgName());
    }

    /** 回归：颁发者取了 CA 的 issuer 而不是 subject，中间 CA 签发的证书链因此断开。 */
    @Test
    void issuerIsTheCaSubject() throws Exception {
        CertUtils.CertResult ca = root("RSA 2048");
        X509Certificate cert = leaf(ca, "RSA 2048", "app.test").getCertificate();

        assertEquals(ca.getCertificate().getSubjectX500Principal(), cert.getIssuerX500Principal());
    }

    @Test
    void leafDeclaresTlsUsages() throws Exception {
        X509Certificate cert = leaf(root("RSA 2048"), "EC P-256", "app.test").getCertificate();

        List<String> eku = cert.getExtendedKeyUsage();
        assertTrue(eku.contains("1.3.6.1.5.5.7.3.1"), "serverAuth: " + eku);
        boolean[] keyUsage = cert.getKeyUsage();
        assertTrue(keyUsage[0], "digitalSignature");
        assertFalse(keyUsage[2], "EC keys must not claim keyEncipherment");
    }

    /** 没填 SAN 时用 CN 兜底：浏览器只认 SAN。 */
    @Test
    void fallsBackToCommonNameForSan() throws Exception {
        X509Certificate cert = leaf(root("RSA 2048"), "RSA 2048", "").getCertificate();

        Collection<List<?>> names = cert.getSubjectAlternativeNames();
        assertEquals(1, names.size());
        assertEquals("app.test", names.iterator().next().get(1));
    }

    /** 回归：EC 私钥被导出成缺少曲线参数的 SEC1，读不回来，EC 根证书因此无法签发任何证书。 */
    @Test
    void exportedEcKeyReadsBack() throws Exception {
        CertUtils.CertResult ca = root("EC P-384");
        String pem = ca.getPrivateKeyPem();

        assertTrue(pem.startsWith("-----BEGIN PRIVATE KEY-----"), pem);
        assertEquals(ca.getPrivateKey(), CertUtils.parsePrivateKeyFromPem(pem));
    }

    /** 旧版本导出的 EC CA 私钥（只有 D 值）借助 CA 证书里的曲线参数仍能用来签发。 */
    @Test
    void legacyEcKeyWithoutCurveStillSigns() throws Exception {
        CertUtils.CertResult ca = root("EC P-256");
        java.io.StringWriter legacy = new java.io.StringWriter();
        try (org.bouncycastle.openssl.jcajce.JcaPEMWriter writer = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(legacy)) {
            writer.writeObject(ca.getPrivateKey());
        }
        assertTrue(legacy.toString().startsWith("-----BEGIN EC PRIVATE KEY-----"));

        X509Certificate cert = CertUtils.signCertificate(ca.getCertificatePem(), legacy.toString(),
                "RSA 2048", "app.test", "", "", "", "", "", "app.test", 1).getCertificate();

        cert.verify(ca.getCertificate().getPublicKey());
    }

    @Test
    void serialNumbersAreUniqueAndPositive() throws Exception {
        CertUtils.CertResult ca = root("EC P-256");
        X509Certificate first = leaf(ca, "EC P-256", "a.test").getCertificate();
        X509Certificate second = leaf(ca, "EC P-256", "b.test").getCertificate();

        assertNotEquals(first.getSerialNumber(), second.getSerialNumber());
        assertTrue(first.getSerialNumber().signum() > 0);
        assertTrue(first.getSerialNumber().bitLength() <= 159);
    }
}
