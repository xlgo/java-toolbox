package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.feature.network.ssh.infra.SshTunnelBridge;
import com.aqishi.toolbox.infra.network.RoutedTlsSocketFactory;
import com.aqishi.toolbox.util.I18n;

import java.net.URI;
import java.util.Set;

import javax.net.ssl.*;

/** Owns a cluster's route; never changes the identity used to verify its server certificate. */
public final class KubernetesEndpoint implements AutoCloseable {
    private final String server, identity;
    private final SSLSocketFactory tls;
    private final AutoCloseable tunnel;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();

    private KubernetesEndpoint(
            String server, String identity, SSLSocketFactory tls, AutoCloseable tunnel) {
        this.server = server;
        this.identity = identity;
        this.tls = tls;
        this.tunnel = tunnel;
    }

    public static KubernetesEndpoint open(
            String url,
            String sshId,
            String serverName,
            boolean skip,
            String ca,
            String cert,
            String key)
            throws Exception {
        return open(
                url,
                sshId,
                serverName,
                skip,
                ca,
                cert,
                key,
                (id, host, port) -> {
                    var bridge = SshTunnelBridge.bridgeEphemeral(id, host, port);
                    return new Tunnel(bridge.getLocalHost(), bridge.getLocalPort(), bridge);
                });
    }

    record Tunnel(String host, int port, AutoCloseable resource) {}

    @FunctionalInterface
    interface TunnelOpener {
        Tunnel open(String id, String host, int port) throws Exception;
    }

    static KubernetesEndpoint open(
            String url,
            String sshId,
            String serverName,
            boolean skip,
            String ca,
            String cert,
            String key,
            TunnelOpener opener)
            throws Exception {
        URI original = URI.create(url);
        if (!Set.of("http", "https").contains(original.getScheme())
                || original.getHost() == null
                || original.getUserInfo() != null
                || original.getQuery() != null
                || original.getFragment() != null
                || original.getPort() == 0
                || original.getPort() > 65535)
            throw new IllegalArgumentException(I18n.get("k8s.ssh.invalidServer"));
        String host = original.getHost().replace("[", "").replace("]", "");
        String identity = serverName == null || serverName.isBlank() ? host : serverName.trim();
        int port =
                original.getPort() < 0
                        ? (original.getScheme().equals("https") ? 443 : 80)
                        : original.getPort();
        Tunnel bridge = null;
        try {
            if (sshId != null && !sshId.isBlank()) bridge = opener.open(sshId, host, port);
            String dialHost = bridge == null ? host : bridge.host();
            int dialPort = bridge == null ? port : bridge.port();
            String routed =
                    new URI(
                                    original.getScheme(),
                                    null,
                                    dialHost,
                                    dialPort,
                                    original.getPath(),
                                    null,
                                    null)
                            .toString()
                            .replaceAll("/+$", "");
            SSLSocketFactory tls =
                    original.getScheme().equals("https")
                            ? new RoutedTlsSocketFactory(
                                    KubernetesTls.socketFactory(skip, ca, cert, key),
                                    identity,
                                    dialHost,
                                    dialPort,
                                    !skip)
                            : null;
            return new KubernetesEndpoint(
                    routed, identity, tls, bridge == null ? null : bridge.resource());
        } catch (Exception error) {
            if (bridge != null) bridge.resource().close();
            throw error;
        }
    }

    public String server() {
        return server;
    }

    public String identity() {
        return identity;
    }

    public SSLSocketFactory socketFactory() {
        return tls;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            if (tunnel != null) tunnel.close();
        } catch (Exception ignored) {
        }
    }
}
