package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.infra.InfrastructureException;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds the TLS material used to talk to a Kubernetes API server.
 *
 * <p>Verification is on by default: when no cluster CA is supplied the platform
 * trust store validates the server chain, and when {@code certificate-authority-data}
 * is present it becomes the sole trust anchor. Skipping verification is an
 * explicit, per-connection opt-in that never installs a process-wide default,
 * so enabling it for one cluster cannot silently weaken another connection.</p>
 */
public final class KubernetesTls {

    private KubernetesTls() {
    }

    /**
     * Creates a socket factory for one cluster connection.
     *
     * @param skipTls        when true the server chain and hostname are not verified
     * @param caCertPem      PEM-encoded cluster CA certificate, may be null
     * @param clientCertPem  PEM-encoded client certificate, may be null
     * @param clientKeyPem   PEM-encoded client private key, may be null
     */
    public static SSLSocketFactory socketFactory(boolean skipTls, String caCertPem,
                                                 String clientCertPem, String clientKeyPem) {
        try {
            KeyManager[] keyManagers = clientKeyManagers(clientCertPem, clientKeyPem);
            TrustManager[] trustManagers = trustManagers(skipTls, caCertPem);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, new SecureRandom());
            return context.getSocketFactory();
        } catch (InfrastructureException error) {
            throw error;
        } catch (Exception error) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "无法构建 Kubernetes TLS 上下文: " + error.getMessage(), error);
        }
    }

    /**
     * Returns the hostname verifier matching the requested verification mode.
     * The permissive verifier is only ever handed to a connection that also
     * opted into an unverified trust manager.
     */
    public static HostnameVerifier hostnameVerifier(boolean skipTls) {
        if (!skipTls) {
            return HttpsURLConnection.getDefaultHostnameVerifier();
        }
        return (host, session) -> true;
    }

    /**
     * True when the caller should warn before connecting: verification is off.
     */
    public static boolean isVerificationDisabled(boolean skipTls) {
        return skipTls;
    }

    private static TrustManager[] trustManagers(boolean skipTls, String caCertPem) throws Exception {
        if (skipTls) {
            // Deliberately not cached: a shared permissive factory would leak
            // into every other TLS connection in the process.
            return new TrustManager[]{new PermissiveTrustManager()};
        }
        if (caCertPem == null || caCertPem.trim().isEmpty()) {
            return null;
        }
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        anchors.load(null, null);
        int index = 0;
        for (Certificate certificate : parseCertificates(caCertPem)) {
            anchors.setCertificateEntry("cluster-ca-" + index++, certificate);
        }
        if (index == 0) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "集群 CA 证书中没有解析到任何证书");
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        factory.init(anchors);
        return factory.getTrustManagers();
    }

    private static KeyManager[] clientKeyManagers(String clientCertPem, String clientKeyPem) throws Exception {
        if (isBlank(clientCertPem) || isBlank(clientKeyPem)) {
            return null;
        }
        List<Certificate> chain = parseCertificates(clientCertPem);
        if (chain.isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "客户端证书中没有解析到任何证书");
        }
        PrivateKey privateKey = parsePrivateKey(clientKeyPem);

        char[] password = new char[0];
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("client", privateKey, password,
                chain.toArray(new Certificate[0]));

        KeyManagerFactory factory = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        return factory.getKeyManagers();
    }

    private static List<Certificate> parseCertificates(String pem) throws Exception {
        List<Certificate> certificates = new ArrayList<>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (ByteArrayInputStream input = new ByteArrayInputStream(pem.getBytes("UTF-8"))) {
            Collection<? extends Certificate> parsed = factory.generateCertificates(input);
            for (Certificate certificate : parsed) {
                certificates.add(certificate);
            }
        }
        return certificates;
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (object instanceof org.bouncycastle.openssl.PEMKeyPair) {
                return converter.getKeyPair((org.bouncycastle.openssl.PEMKeyPair) object).getPrivate();
            }
            if (object instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo) {
                return converter.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo) object);
            }
            if (object instanceof org.bouncycastle.openssl.PEMEncryptedKeyPair) {
                throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                        "不支持加密的私钥文件，请使用未加密的私钥。");
            }
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "无法解析的私钥格式: " + (object == null ? "null" : object.getClass().getName()));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Accepts any server chain. Instances are created per connection so the
     * relaxed policy cannot outlive the cluster that requested it.
     */
    private static final class PermissiveTrustManager implements X509TrustManager {
        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certificates, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] certificates, String authType) {
        }
    }
}
