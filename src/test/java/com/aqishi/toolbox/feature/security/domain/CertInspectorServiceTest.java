package com.aqishi.toolbox.feature.security.domain;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CertInspectorServiceTest {

    private final CertInspectorService service = new CertInspectorService();

    @Test
    void parsesAndVerifiesCsrSuccessfully() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name subject = new X500Name("CN=test.example.com, O=Acme Corp, C=CN");
        JcaPKCS10CertificationRequestBuilder builder = new JcaPKCS10CertificationRequestBuilder(subject, kp.getPublic());
        PKCS10CertificationRequest csr = builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate()));

        StringWriter sw = new StringWriter();
        try (org.bouncycastle.openssl.jcajce.JcaPEMWriter pw = new org.bouncycastle.openssl.jcajce.JcaPEMWriter(sw)) {
            pw.writeObject(csr);
        }
        String csrPem = sw.toString();

        CertInspectorService.CsrInfo info = service.parseCsr(csrPem);
        assertNotNull(info);
        assertTrue(info.getSubject().contains("test.example.com"));
        assertEquals("RSA", info.getPublicKeyAlgorithm());
        assertEquals(2048, info.getKeySize());
        assertTrue(info.isSignatureValid());
    }

    @Test
    void inspectsPkcs12KeystoreSuccessfully() throws Exception {
        // 创建 Root CA 和测试证书
        CertUtils.CertResult root = CertUtils.createRootCA(0, "Test Root CA", "Org", "IT", "Beijing", "Beijing", "CN", 5);
        X509Certificate rootCert = root.getCertificate();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);

        ks.setCertificateEntry("root-ca", rootCert);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ks.store(baos, "password123".toCharArray());

        List<CertInspectorService.Pkcs12EntryInfo> entries = service.inspectPkcs12(baos.toByteArray(), "password123".toCharArray());
        assertEquals(1, entries.size());
        CertInspectorService.Pkcs12EntryInfo entry = entries.get(0);
        assertEquals("root-ca", entry.getAlias());
        assertFalse(entry.isHasPrivateKey());
        assertTrue(entry.getSubject().contains("Test Root CA"));
    }

    @Test
    void validatesCertificateChainHierarchy() throws Exception {
        // 创建真实 Root CA 与由其签发的 Leaf 证书
        CertUtils.CertResult root = CertUtils.createRootCA(0, "My Root CA", "Org", "IT", "Beijing", "Beijing", "CN", 5);
        CertUtils.CertResult leaf = CertUtils.signCertificate(
                root.getCertificatePem(), root.getPrivateKeyPem(),
                "RSA 2048", "client.domain.com", "Org", "App", "Beijing", "Beijing", "CN",
                "client.domain.com", 2
        );

        X509Certificate rootCert = root.getCertificate();
        X509Certificate leafCert = leaf.getCertificate();

        // 即使乱序传入 (Root在前，Leaf在后)，验证器也应自动拓扑排序
        List<X509Certificate> chain = Arrays.asList(rootCert, leafCert);
        CertInspectorService.ChainValidationResult res = service.validateCertificateChain(chain);

        assertTrue(res.isValid());
        assertEquals(2, res.getSortedChain().size());
        assertEquals(leafCert.getSubjectX500Principal(), res.getSortedChain().get(0).getSubjectX500Principal());
        assertEquals(rootCert.getSubjectX500Principal(), res.getSortedChain().get(1).getSubjectX500Principal());
    }

    @Test
    void detectsBrokenCertificateChain() throws Exception {
        CertUtils.CertResult root1 = CertUtils.createRootCA(0, "Root CA 1", "Org", "IT", "Beijing", "Beijing", "CN", 5);
        CertUtils.CertResult root2 = CertUtils.createRootCA(0, "Root CA 2", "Org", "IT", "Beijing", "Beijing", "CN", 5);
        CertUtils.CertResult leafFromRoot1 = CertUtils.signCertificate(
                root1.getCertificatePem(), root1.getPrivateKeyPem(),
                "RSA 2048", "client.domain.com", "Org", "App", "Beijing", "Beijing", "CN",
                "client.domain.com", 2
        );

        X509Certificate leafCert = leafFromRoot1.getCertificate();
        X509Certificate mismatchRootCert = root2.getCertificate();

        // 传入由 root1 签发的叶子证书与毫不相关的 root2 根证书
        List<X509Certificate> brokenChain = Arrays.asList(leafCert, mismatchRootCert);
        CertInspectorService.ChainValidationResult res = service.validateCertificateChain(brokenChain);

        // 签名无法匹配，应为 false
        assertFalse(res.isValid());
    }

    @Test
    void safelyFormatsDatesDuringConcurrentInspectionAndChainValidation() throws Exception {
        CertUtils.CertResult root = CertUtils.createRootCA(
                0, "Concurrent Root", "Org", "IT", "Beijing", "Beijing", "CN", 5);
        X509Certificate rootCert = root.getCertificate();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setCertificateEntry("root-ca", rootCert);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        keyStore.store(output, "password123".toCharArray());
        byte[] pkcs12 = output.toByteArray();

        ExecutorService workers = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                results.add(workers.submit(() -> {
                    List<CertInspectorService.Pkcs12EntryInfo> entries = service.inspectPkcs12(
                            pkcs12, "password123".toCharArray());
                    return entries.size() == 1
                            && !entries.get(0).getNotBefore().isEmpty()
                            && !entries.get(0).getNotAfter().isEmpty();
                }));
                results.add(workers.submit(() -> service.validateCertificateChain(
                        Collections.singletonList(rootCert)).isValid()));
            }

            for (Future<Boolean> result : results) {
                assertTrue(result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            workers.shutdownNow();
        }
    }
}
