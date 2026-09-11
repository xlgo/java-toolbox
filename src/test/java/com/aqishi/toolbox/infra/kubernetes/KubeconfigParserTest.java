package com.aqishi.toolbox.infra.kubernetes;

import com.aqishi.toolbox.domain.KubernetesProfile;
import com.aqishi.toolbox.infra.InfrastructureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class KubeconfigParserTest {

    private final KubeconfigParser parser = new KubeconfigParser();

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String kubeconfig(String clusterBody, String contextName, String userBody) {
        return "apiVersion: v1\n"
                + "kind: Config\n"
                + "current-context: " + contextName + "\n"
                + "clusters:\n"
                + "- name: c1\n"
                + "  cluster:\n"
                + clusterBody
                + "contexts:\n"
                + "- name: ctx1\n"
                + "  context:\n"
                + "    cluster: c1\n"
                + "    user: u1\n"
                + "users:\n"
                + "- name: u1\n"
                + "  user:\n"
                + userBody;
    }

    @Test
    void parsesServerTokenAndInlineCa() throws Exception {
        String yaml = kubeconfig(
                "    server: https://k8s.example.com:6443\n"
                        + "    certificate-authority-data: " + b64("CA-PEM-DATA") + "\n",
                "ctx1",
                "    token: tok123\n");

        KubernetesProfile profile = parser.parse(yaml, null);

        assertEquals("https://k8s.example.com:6443", profile.serverUrl);
        // 现有行为：profile 名直接使用 serverUrl
        assertEquals("https://k8s.example.com:6443", profile.name);
        assertEquals("tok123", profile.token);
        assertEquals("CA-PEM-DATA", profile.caCertData);
        assertFalse(profile.skipTls);
    }

    @Test
    void rejectsBlankInput() {
        InfrastructureException error = assertThrows(InfrastructureException.class,
                () -> parser.parse("  \n ", null));
        assertEquals(InfrastructureException.Kind.CONFIGURATION, error.getKind());
    }

    @Test
    void rejectsKubeconfigWithoutServerUrl() {
        String yaml = kubeconfig("", "ctx1", "    token: tok\n");
        InfrastructureException error = assertThrows(InfrastructureException.class,
                () -> parser.parse(yaml, null));
        assertEquals(InfrastructureException.Kind.CONFIGURATION, error.getKind());
    }

    @Test
    void insecureSkipTlsVerifyOnlyAppliesWithoutCa() throws Exception {
        String withSkip = kubeconfig(
                "    server: https://k8s.example.com:6443\n"
                        + "    insecure-skip-tls-verify: true\n",
                "ctx1", "    token: tok\n");
        assertTrue(parser.parse(withSkip, null).skipTls);

        // 提供了 CA 时跳过验证被忽略——CA 始终是首选信任锚
        String skipWithCa = kubeconfig(
                "    server: https://k8s.example.com:6443\n"
                        + "    insecure-skip-tls-verify: true\n"
                        + "    certificate-authority-data: " + b64("CA") + "\n",
                "ctx1", "    token: tok\n");
        assertFalse(parser.parse(skipWithCa, null).skipTls);
    }

    @Test
    void resolvesFileCredentialsAgainstBaseDirectory(@TempDir File dir) throws Exception {
        Files.writeString(new File(dir, "ca.crt").toPath(), "FILE-CA", StandardCharsets.UTF_8);
        Files.writeString(new File(dir, "client.crt").toPath(), "FILE-CERT", StandardCharsets.UTF_8);
        Files.writeString(new File(dir, "client.key").toPath(), "FILE-KEY", StandardCharsets.UTF_8);

        String yaml = kubeconfig(
                "    server: https://k8s.example.com:6443\n"
                        + "    certificate-authority: ca.crt\n",
                "ctx1",
                "    token: tok\n"
                        + "    client-certificate: client.crt\n"
                        + "    client-key: client.key\n");

        KubernetesProfile profile = parser.parse(yaml, dir);
        assertEquals("FILE-CA", profile.caCertData);
        assertEquals("FILE-CERT", profile.clientCertData);
        assertEquals("FILE-KEY", profile.clientKeyData);
    }

    @Test
    void fileCredentialWithoutBaseDirectoryYieldsNull() throws Exception {
        String yaml = kubeconfig(
                "    server: https://k8s.example.com:6443\n"
                        + "    certificate-authority: ca.crt\n",
                "ctx1", "    token: tok\n");
        assertNull(parser.parse(yaml, null).caCertData);
    }

    @Test
    void missingCredentialFileYieldsNull(@TempDir File dir) throws Exception {
        String yaml = kubeconfig(
                "    server: https://k8s.example.com:6443\n"
                        + "    certificate-authority: absent.crt\n",
                "ctx1", "    token: tok\n");
        assertNull(parser.parse(yaml, dir).caCertData);
    }

    @Test
    void fallsBackToFirstContextWhenCurrentContextDoesNotMatch() throws Exception {
        String yaml = kubeconfig(
                "    server: https://k8s.example.com:6443\n",
                "no-such-context", "    token: tok\n");
        KubernetesProfile profile = parser.parse(yaml, null);
        assertEquals("https://k8s.example.com:6443", profile.serverUrl);
        assertEquals("tok", profile.token);
    }

    @Test
    void prefersMatchingContextOverFirst() throws Exception {
        String yaml = "apiVersion: v1\n"
                + "current-context: second\n"
                + "clusters:\n"
                + "- name: c1\n"
                + "  cluster: {server: https://one.example.com}\n"
                + "- name: c2\n"
                + "  cluster: {server: https://two.example.com}\n"
                + "contexts:\n"
                + "- name: first\n"
                + "  context: {cluster: c1, user: u1}\n"
                + "- name: second\n"
                + "  context: {cluster: c2, user: u2}\n"
                + "users:\n"
                + "- name: u1\n"
                + "  user: {token: tok1}\n"
                + "- name: u2\n"
                + "  user: {token: tok2}\n";

        KubernetesProfile profile = parser.parse(yaml, null);
        assertEquals("https://two.example.com", profile.serverUrl);
        assertEquals("tok2", profile.token);
    }

    @Test
    void singleClusterIsPickedEvenWhenNameDoesNotMatch() throws Exception {
        // contexts 指向的 cluster 名不存在，但只有一条 cluster 记录时直接采用
        String yaml = "apiVersion: v1\n"
                + "current-context: ctx1\n"
                + "clusters:\n"
                + "- name: other\n"
                + "  cluster: {server: https://single.example.com}\n"
                + "contexts:\n"
                + "- name: ctx1\n"
                + "  context: {cluster: missing, user: u1}\n"
                + "users:\n"
                + "- name: u1\n"
                + "  user: {token: tok}\n";
        assertEquals("https://single.example.com", parser.parse(yaml, null).serverUrl);
    }

    @Test
    void unmatchedClusterAmongManyKeepsDefaultServerUrl() throws Exception {
        // 现有行为备忘：多条 cluster 且都不匹配时不报错，回退到内置默认值
        String yaml = "apiVersion: v1\n"
                + "current-context: ctx1\n"
                + "clusters:\n"
                + "- name: a\n"
                + "  cluster: {server: https://a.example.com}\n"
                + "- name: b\n"
                + "  cluster: {server: https://b.example.com}\n"
                + "contexts:\n"
                + "- name: ctx1\n"
                + "  context: {cluster: missing, user: u1}\n"
                + "users:\n"
                + "- name: u1\n"
                + "  user: {token: tok}\n";
        assertEquals("https://127.0.0.1:6443", parser.parse(yaml, null).serverUrl);
    }
}
