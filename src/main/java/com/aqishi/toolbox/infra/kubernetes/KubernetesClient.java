package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.infra.InfrastructureException;
import com.aqishi.toolbox.infra.ManagedResource;
import com.aqishi.toolbox.infra.network.HttpConnectionResource;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small, lifecycle-aware Kubernetes REST adapter.
 *
 * <p>The adapter owns only requests started through it. Closing it aborts all
 * in-flight HTTP connections and rejects new requests; callers own parsing and
 * UI presentation. TLS policy is supplied by the caller through
 * {@link KubernetesTls}, so this class never decides on its own to trust an
 * unverified server.</p>
 */
public final class KubernetesClient implements ManagedResource {

    private final String serverUrl;
    private final String token;
    private final SSLSocketFactory socketFactory;
    private final HostnameVerifier hostnameVerifier;
    private final Set<HttpConnectionResource> activeRequests =
            Collections.synchronizedSet(new LinkedHashSet<HttpConnectionResource>());
    private volatile boolean open = true;

    public KubernetesClient(String serverUrl, String token,
                            SSLSocketFactory socketFactory, HostnameVerifier hostnameVerifier) {
        if (serverUrl == null || serverUrl.trim().isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "Kubernetes API Server 地址不能为空");
        }
        this.serverUrl = serverUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
        this.socketFactory = socketFactory;
        this.hostnameVerifier = hostnameVerifier;
    }

    /**
     * Executes a Kubernetes API request and returns its UTF-8 body.
     */
    public String execute(String method, String apiPath, String body) throws Exception {
        if (!isOpen()) {
            throw new InfrastructureException(InfrastructureException.Kind.CLOSED,
                    "Kubernetes 客户端已关闭");
        }
        if (method == null || method.trim().isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "HTTP 方法不能为空");
        }
        if (apiPath == null || !apiPath.startsWith("/")) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "Kubernetes API 路径必须以 / 开始");
        }

        HttpConnectionResource resource = null;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(serverUrl + apiPath).openConnection();
            resource = new HttpConnectionResource(connection);
            activeRequests.add(resource);
            configure(connection, method, body);
            int status = connection.getResponseCode();
            if (status >= 200 && status < 300) {
                return readFully(connection.getInputStream());
            }
            String error = connection.getErrorStream() == null ? "" : readFully(connection.getErrorStream());
            throw new InfrastructureException(InfrastructureException.Kind.PROTOCOL,
                    "HTTP " + status + (error.isEmpty() ? "" : ": " + error));
        } catch (InfrastructureException error) {
            throw error;
        } catch (java.net.SocketTimeoutException error) {
            throw new InfrastructureException(InfrastructureException.Kind.TIMEOUT,
                    "Kubernetes 请求超时", error);
        } catch (java.io.IOException error) {
            throw new InfrastructureException(InfrastructureException.Kind.CONNECTION,
                    error.getMessage(), error);
        } finally {
            if (resource != null) {
                activeRequests.remove(resource);
                resource.close();
            }
        }
    }

    private void configure(HttpURLConnection connection, String method, String body) throws Exception {
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod(method);
        if (!token.trim().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        if (connection instanceof HttpsURLConnection) {
            HttpsURLConnection secureConnection = (HttpsURLConnection) connection;
            if (socketFactory != null) {
                secureConnection.setSSLSocketFactory(socketFactory);
            }
            if (hostnameVerifier != null) {
                secureConnection.setHostnameVerifier(hostnameVerifier);
            }
        }
    }

    private static String readFully(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int length;
            while ((length = input.read(buffer)) != -1) {
                output.write(buffer, 0, length);
            }
            return output.toString("UTF-8");
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void close() {
        open = false;
        HttpConnectionResource[] resources;
        synchronized (activeRequests) {
            resources = activeRequests.toArray(new HttpConnectionResource[0]);
            activeRequests.clear();
        }
        for (HttpConnectionResource resource : resources) {
            resource.close();
        }
    }
}
