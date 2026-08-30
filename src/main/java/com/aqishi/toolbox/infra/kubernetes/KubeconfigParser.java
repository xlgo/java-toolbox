package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.feature.cloud.domain.KubernetesProfile;
import com.aqishi.toolbox.infra.InfrastructureException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Parses the subset of kubeconfig needed by the desktop client: current
 * context, API server URL, bearer token, and optional client certificate/key.
 * File references are resolved against the kubeconfig's source directory.
 */
public final class KubeconfigParser {

    public KubernetesProfile parse(String yamlText, File baseDirectory) throws Exception {
        if (yamlText == null || yamlText.trim().isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "Kubeconfig 内容不能为空");
        }
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(yamlText);
        String currentContext = root.path("current-context").asText();
        String clusterName = "";
        String userName = "";
        JsonNode contexts = root.path("contexts");
        if (contexts.isArray()) {
            for (JsonNode context : contexts) {
                if (context.path("name").asText().equals(currentContext) || clusterName.isEmpty()) {
                    clusterName = context.path("context").path("cluster").asText();
                    userName = context.path("context").path("user").asText();
                }
            }
        }

        String serverUrl = "https://127.0.0.1:6443";
        String clusterCa = null;
        boolean insecureSkipTlsVerify = false;
        JsonNode clusters = root.path("clusters");
        if (clusters.isArray()) {
            for (JsonNode cluster : clusters) {
                if (cluster.path("name").asText().equals(clusterName) || clusters.size() == 1) {
                    JsonNode clusterBody = cluster.path("cluster");
                    serverUrl = clusterBody.path("server").asText();
                    clusterCa = readCredential(clusterBody, "certificate-authority-data",
                            "certificate-authority", baseDirectory);
                    insecureSkipTlsVerify =
                            clusterBody.path("insecure-skip-tls-verify").asBoolean(false);
                    break;
                }
            }
        }
        if (serverUrl.trim().isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "在 Kubeconfig 中无法解析出 API Server 地址。");
        }

        String token = "";
        String clientCertificate = null;
        String clientKey = null;
        JsonNode users = root.path("users");
        if (users.isArray()) {
            for (JsonNode userEntry : users) {
                if (userEntry.path("name").asText().equals(userName) || users.size() == 1) {
                    JsonNode user = userEntry.path("user");
                    token = user.path("token").asText("");
                    clientCertificate = readCredential(user, "client-certificate-data",
                            "client-certificate", baseDirectory);
                    clientKey = readCredential(user, "client-key-data", "client-key", baseDirectory);
                    break;
                }
            }
        }
        // Verification stays on unless the kubeconfig explicitly opts out; a
        // supplied cluster CA is always the preferred trust anchor.
        boolean skipTls = insecureSkipTlsVerify && isBlank(clusterCa);
        return new KubernetesProfile(serverUrl, serverUrl, token, skipTls,
                clientCertificate, clientKey, clusterCa);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String readCredential(JsonNode user, String inlineKey, String fileKey,
                                         File baseDirectory) throws Exception {
        if (user.has(inlineKey)) {
            return new String(Base64.getDecoder().decode(user.path(inlineKey).asText().trim()),
                    StandardCharsets.UTF_8);
        }
        if (!user.has(fileKey) || baseDirectory == null) {
            return null;
        }
        File source = resolve(baseDirectory, user.path(fileKey).asText());
        if (!source.isFile()) {
            return null;
        }
        return new String(Files.readAllBytes(source.toPath()), StandardCharsets.UTF_8);
    }

    private static File resolve(File baseDirectory, String value) {
        File file = new File(value);
        return file.isAbsolute() ? file : new File(baseDirectory, value);
    }
}
