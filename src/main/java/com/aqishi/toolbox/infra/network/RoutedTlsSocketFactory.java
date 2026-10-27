package com.aqishi.toolbox.infra.network;

import java.io.IOException;
import java.net.*;
import java.util.List;

import javax.net.SocketFactory;
import javax.net.ssl.*;

/** TLS identity stays independent from the TCP destination of an SSH route. */
public final class RoutedTlsSocketFactory extends SSLSocketFactory {
    private final SSLSocketFactory delegate;
    private final String identity, dialHost;
    private final int dialPort;
    private final boolean verify;

    public RoutedTlsSocketFactory(
            SSLSocketFactory delegate,
            String identity,
            String dialHost,
            int dialPort,
            boolean verify) {
        this.delegate = delegate;
        this.identity = identity;
        this.dialHost = dialHost;
        this.dialPort = dialPort;
        this.verify = verify;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        SocketException error = new SocketException("Layered TLS required");
        error.initCause(new UnsupportedOperationException());
        throw error;
    }

    @Override
    public Socket createSocket(Socket socket, String ignored, int port, boolean close)
            throws IOException {
        SSLSocket tls = (SSLSocket) delegate.createSocket(socket, identity, port, close);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm(verify ? "HTTPS" : null);
        if (identity.indexOf(':') < 0 && !identity.matches("[0-9.]+"))
            parameters.setServerNames(List.of(new SNIHostName(identity)));
        tls.setSSLParameters(parameters);
        return tls;
    }

    private Socket dial() throws IOException {
        Socket raw = new Socket(Proxy.NO_PROXY);
        try {
            raw.connect(new InetSocketAddress(dialHost, dialPort), 6000);
            raw.setSoTimeout(12000);
            return createSocket(raw, identity, dialPort, true);
        } catch (IOException error) {
            raw.close();
            throw error;
        }
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return dial();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort)
            throws IOException {
        return dial();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return dial();
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
            throws IOException {
        return dial();
    }

    /** Java-WebSocket needs an already connected TLS socket, unlike HttpsURLConnection. */
    public SocketFactory webSocketFactory() {
        return new SocketFactory() {
            @Override
            public Socket createSocket() throws IOException {
                Socket s = dial();
                s.setSoTimeout(0);
                return s;
            }

            @Override
            public Socket createSocket(String h, int p) throws IOException {
                return createSocket();
            }

            @Override
            public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException {
                return createSocket();
            }

            @Override
            public Socket createSocket(InetAddress h, int p) throws IOException {
                return createSocket();
            }

            @Override
            public Socket createSocket(InetAddress h, int p, InetAddress l, int lp)
                    throws IOException {
                return createSocket();
            }
        };
    }
}
