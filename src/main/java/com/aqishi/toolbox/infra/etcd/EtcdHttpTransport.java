package com.aqishi.toolbox.infra.etcd;

import com.aqishi.toolbox.feature.data.domain.EtcdModels.Options;
import com.aqishi.toolbox.feature.network.ssh.infra.SshTunnelBridge;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class EtcdHttpTransport implements EtcdTransport {
    private static final int MAX_BODY = 16 * 1024 * 1024;
    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
            DaemonThreads.factory("etcd-timeout"));
    private final String base;
    private final int timeout;
    private final SSLSocketFactory tls;
    private final AutoCloseable tunnel;
    private final Set<HttpURLConnection> active = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public static EtcdHttpTransport open(Options options) throws Exception {
        SshTunnelBridge.BridgeResult bridge = null;
        try {
            if (!options.sshConfigId().isEmpty()) bridge = SshTunnelBridge.bridgeEphemeral(
                    options.sshConfigId(), EtcdTls.unbracket(options.endpoint().getHost()), options.port());
            return new EtcdHttpTransport(options, bridge == null ? EtcdTls.unbracket(options.endpoint().getHost()) : bridge.getLocalHost(),
                    bridge == null ? options.port() : bridge.getLocalPort(), bridge);
        } catch (Exception error) { if (bridge != null) bridge.close(); throw error; }
    }

    /** The dial endpoint may differ from the TLS server identity (SSH forwarding). */
    public EtcdHttpTransport(Options options, String dialHost, int dialPort, AutoCloseable tunnel) throws Exception {
        base = new URI(options.endpoint().getScheme(), null, dialHost, dialPort, null, null, null).toString();
        timeout = options.timeoutMillis(); this.tunnel = tunnel;
        tls = options.endpoint().getScheme().equals("https") ? EtcdTls.create(options, dialHost, dialPort) : null;
    }

    @Override public JsonNode request(String method, String path, String contentType, byte[] body, String auth) throws IOException {
        if (closed.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (body.length > 2 * 1024 * 1024) throw new IOException(I18n.get("etcd.error.requestSize"));
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection(Proxy.NO_PROXY);
        connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Connection", "close");
        if (auth != null && !auth.isEmpty()) connection.setRequestProperty("Authorization", auth);
        if (connection instanceof HttpsURLConnection https) https.setSSLSocketFactory(tls);
        active.add(connection);
        AtomicBoolean timedOut = new AtomicBoolean();
        ScheduledFuture<?> deadline = TIMEOUTS.schedule(() -> {
            timedOut.set(true); connection.disconnect();
        }, timeout, TimeUnit.MILLISECONDS);
        try {
            if (closed.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            if (!method.equals("GET") && !method.equals("DELETE")) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", contentType);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            InputStream source = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] data;
            try (InputStream in = source == null ? InputStream.nullInputStream() : source) {
                data = in.readNBytes(MAX_BODY + 1);
            }
            if (data.length > MAX_BODY) throw new IOException(I18n.get("etcd.error.responseSize"));
            JsonNode json;
            try { json = Json.mapper().readTree(data); }
            catch (IOException malformed) { throw new EtcdException(status, 0, I18n.get("etcd.error.http", status)); }
            if (status < 200 || status >= 300) {
                int code = json == null ? 0 : json.path("errorCode").asInt(json.path("code").asInt());
                // Do not echo server bodies: they can contain private keys, values or credentials.
                throw new EtcdException(status, code, I18n.get("etcd.error.http", status) + " (" + code + ")");
            }
            if (json == null || !json.isObject()) throw new IOException(I18n.get("etcd.error.response"));
            if (json.has("error") || json.has("errorCode") || json.path("code").asInt() != 0)
                throw new EtcdException(status, json.path("errorCode").asInt(json.path("code").asInt()), I18n.get("etcd.error.response"));
            if (timedOut.get()) throw new SocketTimeoutException(I18n.get("etcd.error.timeout"));
            return json;
        } finally { deadline.cancel(false); active.remove(connection); connection.disconnect(); }
    }
    @Override public void cancel() { for (HttpURLConnection connection : active) connection.disconnect(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancel();
        try { if (tunnel != null) tunnel.close(); } catch (Exception ignored) { }
    }
}
