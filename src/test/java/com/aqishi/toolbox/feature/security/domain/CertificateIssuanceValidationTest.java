package com.aqishi.toolbox.feature.security.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CertificateIssuanceValidationTest {
    private CertUtils.CertResult root(String algorithm) throws Exception {
        return CertUtils.createRootCA(algorithm, "CA", "", "", "", "", "CN", 1);
    }

    private CertUtils.CertResult issue(CertUtils.CertResult ca, String keyPem, int years) throws Exception {
        return CertUtils.signCertificate(ca.getCertificatePem(), keyPem, "EC P-256",
                "localhost", "", "", "", "", "CN", "localhost", years);
    }

    @ParameterizedTest
    @ValueSource(strings = {"RSA 2048", "EC P-256", "SM2"})
    void rejectsMismatchedPrivateKey(String algorithm) throws Exception {
        var ca = root(algorithm);
        var other = root(algorithm);
        assertThrows(IllegalArgumentException.class, () -> issue(ca, other.getPrivateKeyPem(), 1));
    }

    @Test
    void rejectsNonCaIssuer() throws Exception {
        var ca = root("EC P-256");
        var leaf = issue(ca, ca.getPrivateKeyPem(), 1);
        assertThrows(IllegalArgumentException.class, () -> issue(leaf, leaf.getPrivateKeyPem(), 1));
    }

    @Test
    void capsLeafValidityAtIssuerExpiry() throws Exception {
        var ca = root("EC P-256");
        var leaf = issue(ca, ca.getPrivateKeyPem(), 10);
        assertEquals(ca.getCertificate().getNotAfter(), leaf.getCertificate().getNotAfter());
        GmCrypto.verify(leaf.getCertificate(), ca.getCertificate().getPublicKey());
    }

    @Test
    void rejectsNonPositiveLifetime() {
        assertThrows(IllegalArgumentException.class,
                () -> CertUtils.createRootCA("EC P-256", "CA", "", "", "", "", "CN", 0));
    }

    @Test
    void dualCertificatesAlsoRequireMatchingIssuerKey() throws Exception {
        var ca = root("SM2");
        var other = root("SM2");
        assertThrows(IllegalArgumentException.class, () -> CertUtils.signSm2DualCertificates(
                ca.getCertificatePem(), other.getPrivateKeyPem(), "localhost", "", "", "", "", "CN", "localhost", 1));
    }

    @Test
    void rejectsExpiredIssuer() throws Exception {
        var ca = customRoot(-7200, -3600, org.bouncycastle.asn1.x509.KeyUsage.keyCertSign);
        assertThrows(IllegalArgumentException.class, () -> issue(ca, ca.getPrivateKeyPem(), 1));
    }

    @Test
    void rejectsNotYetValidIssuer() throws Exception {
        var ca = customRoot(3600, 7200, org.bouncycastle.asn1.x509.KeyUsage.keyCertSign);
        assertThrows(IllegalArgumentException.class, () -> issue(ca, ca.getPrivateKeyPem(), 1));
    }

    @Test
    void rejectsCaWithoutCertificateSigningUsage() throws Exception {
        var ca = customRoot(-3600, 3600, org.bouncycastle.asn1.x509.KeyUsage.digitalSignature);
        assertThrows(IllegalArgumentException.class, () -> issue(ca, ca.getPrivateKeyPem(), 1));
    }

    private CertUtils.CertResult customRoot(long fromSeconds, long toSeconds, int usage) throws Exception {
        var keys = CertUtils.generateKeyPair(CertUtils.indexOfKeyAlg("EC P-256"));
        var name = new org.bouncycastle.asn1.x500.X500Name("CN=CA");
        long now = System.currentTimeMillis();
        var builder = new org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(name, java.math.BigInteger.ONE,
                new java.util.Date(now + fromSeconds * 1000), new java.util.Date(now + toSeconds * 1000), name, keys.getPublic());
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.basicConstraints, true,
                new org.bouncycastle.asn1.x509.BasicConstraints(true));
        builder.addExtension(org.bouncycastle.asn1.x509.Extension.keyUsage, true,
                new org.bouncycastle.asn1.x509.KeyUsage(usage));
        var signer = new org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate());
        return new CertUtils.CertResult(GmCrypto.parseCertificate(builder.build(signer).getEncoded()), keys.getPrivate());
    }
}
