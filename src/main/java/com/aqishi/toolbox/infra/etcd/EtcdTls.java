package com.aqishi.toolbox.infra.etcd;

import com.aqishi.toolbox.feature.data.domain.EtcdModels.Options;
import com.aqishi.toolbox.util.I18n;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.*;
import java.io.IOException;
import java.io.StringReader;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

/** Verified TLS, including the original server name when TCP is routed through a local SSH port. */
final class EtcdTls {
    private EtcdTls() { }
    static SSLSocketFactory create(Options options, String dialHost, int dialPort) throws Exception {
        TrustManager[] trust = null;
        if (!options.caFile().isEmpty()) {
            KeyStore anchors = KeyStore.getInstance("PKCS12"); anchors.load(null, null);
            Certificate[] certificates = certificates(options.caFile());
            for (int i = 0; i < certificates.length; i++) anchors.setCertificateEntry("ca-" + i, certificates[i]);
            TrustManagerFactory manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            manager.init(anchors); trust = manager.getTrustManagers();
        }
        KeyManager[] keys = null;
        if (!options.certFile().isEmpty()) {
            PrivateKey key;
            try (PEMParser parser = new PEMParser(new StringReader(Files.readString(Path.of(options.keyFile()))))) {
                Object material = parser.readObject();
                JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
                if (material instanceof PEMKeyPair pair) key = converter.getKeyPair(pair).getPrivate();
                else if (material instanceof PrivateKeyInfo info) key = converter.getPrivateKey(info);
                else throw new IOException(I18n.get("etcd.error.privateKey"));
            }
            KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, null);
            store.setKeyEntry("client", key, new char[0], certificates(options.certFile()));
            KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            manager.init(store, new char[0]); keys = manager.getKeyManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS"); context.init(keys, trust, null);
        return new ServerNameFactory(context.getSocketFactory(), unbracket(options.endpoint().getHost()),
                dialHost, dialPort, options.timeoutMillis());
    }

    private static Certificate[] certificates(String file) throws Exception {
        try (var stream = Files.newInputStream(Path.of(file))) {
            var certificates = CertificateFactory.getInstance("X.509").generateCertificates(stream);
            if (certificates.isEmpty()) throw new IOException(I18n.get("etcd.error.certificate"));
            return certificates.toArray(Certificate[]::new);
        }
    }
    static String unbracket(String host) { return host.startsWith("[") ? host.substring(1, host.length() - 1) : host; }

    private static final class ServerNameFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String serverName, dialHost;
        private final int dialPort, timeout;
        ServerNameFactory(SSLSocketFactory delegate, String serverName, String dialHost, int dialPort, int timeout) {
            this.delegate = delegate; this.serverName = serverName; this.dialHost = dialHost;
            this.dialPort = dialPort; this.timeout = timeout;
        }
        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public Socket createSocket() throws IOException {
            // HttpsURLConnection then creates a plain TCP socket and calls the layered overload.
            SocketException unsupported = new SocketException("Layered TLS required");
            unsupported.initCause(new UnsupportedOperationException()); throw unsupported;
        }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean close) throws IOException {
            SSLSocket tls = (SSLSocket) delegate.createSocket(socket, serverName, port, close);
            SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            if (serverName.indexOf(':') < 0 && !serverName.matches("[0-9.]+"))
                parameters.setServerNames(java.util.List.of(new SNIHostName(serverName)));
            tls.setSSLParameters(parameters); return tls;
        }
        private Socket dial() throws IOException {
            Socket socket = new Socket(Proxy.NO_PROXY);
            try {
                socket.connect(new InetSocketAddress(dialHost, dialPort), timeout); socket.setSoTimeout(timeout);
                return createSocket(socket, serverName, dialPort, true);
            } catch (IOException error) { socket.close(); throw error; }
        }
        @Override public Socket createSocket(String host, int port) throws IOException { return dial(); }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return dial(); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return dial(); }
        @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return dial(); }
    }
}
