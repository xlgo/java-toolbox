package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.infra.InfrastructureException;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesTlsTest {

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair keyPair, String dn) throws Exception {
        long now = System.currentTimeMillis();
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                new X500Name(dn), BigInteger.valueOf(now),
                new Date(now - 60_000), new Date(now + 86_400_000),
                new X500Name(dn), keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static String pem(Object object) throws Exception {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(object);
        }
        return writer.toString();
    }

    @Test
    void buildsSocketFactoryWithClusterCaAsTrustAnchor() throws Exception {
        KeyPair ca = generateKeyPair();
        String caPem = pem(selfSigned(ca, "CN=test-cluster-ca"));
        assertNotNull(KubernetesTls.socketFactory(false, caPem, null, null));
    }

    @Test
    void buildsSocketFactoryWithClientCertificate() throws Exception {
        KeyPair ca = generateKeyPair();
        KeyPair client = generateKeyPair();
        String caPem = pem(selfSigned(ca, "CN=test-cluster-ca"));
        String clientCertPem = pem(selfSigned(client, "CN=test-client"));
        // JcaPEMWriter 输出的是未加密的 PKCS#8 PRIVATE KEY
        String clientKeyPem = pem(client.getPrivate());

        assertNotNull(KubernetesTls.socketFactory(false, caPem, clientCertPem, clientKeyPem));
    }

    @Test
    void skipTlsBuildsPermissiveFactoryWithoutAnyMaterial() {
        assertNotNull(KubernetesTls.socketFactory(true, null, null, null));
    }

    @Test
    void blankCaFallsBackToPlatformTrustStore() {
        assertNotNull(KubernetesTls.socketFactory(false, "  ", null, null));
        assertNotNull(KubernetesTls.socketFactory(false, null, null, null));
    }

    @Test
    void garbageCaPemRaisesConfigurationError() {
        InfrastructureException error = assertThrows(InfrastructureException.class,
                () -> KubernetesTls.socketFactory(false, "not-a-pem-at-all", null, null));
        assertEquals(InfrastructureException.Kind.CONFIGURATION, error.getKind());
    }

    @Test
    void garbageClientKeyRaisesConfigurationError() throws Exception {
        KeyPair ca = generateKeyPair();
        KeyPair client = generateKeyPair();
        String caPem = pem(selfSigned(ca, "CN=ca"));
        String clientCertPem = pem(selfSigned(client, "CN=client"));

        InfrastructureException error = assertThrows(InfrastructureException.class,
                () -> KubernetesTls.socketFactory(false, caPem, clientCertPem, "garbage-key"));
        assertEquals(InfrastructureException.Kind.CONFIGURATION, error.getKind());
    }

    @Test
    void hostnameVerifierIsPermissiveOnlyWhenSkipping() {
        HostnameVerifier permissive = KubernetesTls.hostnameVerifier(true);
        assertTrue(permissive.verify("anything.example.com", null));

        HostnameVerifier strict = KubernetesTls.hostnameVerifier(false);
        assertSame(HttpsURLConnection.getDefaultHostnameVerifier(), strict);
    }

    @Test
    void verificationDisabledMirrorsSkipFlag() {
        assertTrue(KubernetesTls.isVerificationDisabled(true));
        assertFalse(KubernetesTls.isVerificationDisabled(false));
    }
}
