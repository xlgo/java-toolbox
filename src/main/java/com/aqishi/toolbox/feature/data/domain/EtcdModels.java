package com.aqishi.toolbox.feature.data.domain;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/** Values stay as bytes: switching the editor representation must not corrupt binary data. */
public final class EtcdModels {
    private EtcdModels() { }
    public enum Api { V3, V2 }
    public enum Encoding { UTF8, BASE64 }

    public record Options(URI endpoint, Api api, String username, String password, int timeoutMillis,
                          String caFile, String certFile, String keyFile, String sshConfigId) {
        public Options {
            if (endpoint == null || endpoint.getHost() == null
                    || !("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                    || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                    || (endpoint.getPort() != -1 && (endpoint.getPort() < 1 || endpoint.getPort() > 65535))
                    || (!endpoint.getPath().isEmpty() && !endpoint.getPath().equals("/")))
                throw new IllegalArgumentException("etcd.error.endpoint");
            if (api == null || timeoutMillis < 1000 || timeoutMillis > 60000)
                throw new IllegalArgumentException("etcd.error.options");
            username = blank(username); password = password == null ? "" : password;
            caFile = blank(caFile); certFile = blank(certFile); keyFile = blank(keyFile); sshConfigId = blank(sshConfigId);
            if (certFile.isEmpty() != keyFile.isEmpty()) throw new IllegalArgumentException("etcd.error.certPair");
            if (!"https".equals(endpoint.getScheme()) && !(caFile + certFile + keyFile).isEmpty())
                throw new IllegalArgumentException("etcd.error.httpsRequired");
        }
        public int port() { return endpoint.getPort() < 0 ? (endpoint.getScheme().equals("https") ? 443 : 80) : endpoint.getPort(); }
        @Override public String toString() { return "EtcdOptions[" + api + ", " + endpoint + "]"; }
    }

    public record Entry(byte[] key, byte[] value, long created, long modified, long version,
                        long lease, long ttl, boolean directory) {
        public Entry { key = key.clone(); value = value.clone(); }
        @Override public byte[] key() { return key.clone(); }
        @Override public byte[] value() { return value.clone(); }
        public long byteSize() { return (long)key.length + value.length; }
        public String label() {
            try { return decode(key, Encoding.UTF8); }
            catch (IllegalArgumentException binary) { return "Base64:" + encode(key, Encoding.BASE64); }
        }
    }

    public record Page(List<Entry> entries, byte[] nextKey, long revision, boolean more) {
        public Page { entries = List.copyOf(entries); nextKey = nextKey == null ? null : nextKey.clone(); }
        @Override public byte[] nextKey() { return nextKey == null ? null : nextKey.clone(); }
    }

    public static String encode(byte[] bytes, Encoding encoding) {
        return encoding == Encoding.BASE64 ? Base64.getEncoder().encodeToString(bytes) : decode(bytes, encoding);
    }
    public static byte[] parse(String text, Encoding encoding) {
        if (encoding == Encoding.BASE64) {
            try { return Base64.getDecoder().decode(text); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("etcd.error.base64"); }
        }
        return text.getBytes(StandardCharsets.UTF_8);
    }
    private static String decode(byte[] bytes, Encoding encoding) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new IllegalArgumentException("etcd.error.binary"); }
    }
    public static byte[] prefixEnd(byte[] prefix) {
        for (int i = prefix.length - 1; i >= 0; i--) {
            if ((prefix[i] & 255) < 255) {
                byte[] end = Arrays.copyOf(prefix, i + 1); end[i]++; return end;
            }
        }
        return new byte[]{0};
    }
    public static byte[] after(byte[] key) { return Arrays.copyOf(key, key.length + 1); }
    public static String v2Path(byte[] key) {
        String path = decode(key, Encoding.UTF8);
        if (!path.startsWith("/") || path.contains("//") || path.indexOf('\0') >= 0)
            throw new IllegalArgumentException("etcd.error.v2Path");
        for (String part : path.split("/")) if (part.equals(".") || part.equals(".."))
            throw new IllegalArgumentException("etcd.error.v2Path");
        return path;
    }
    private static String blank(String text) { return text == null ? "" : text.trim(); }
}
