package com.aqishi.toolbox.misc.ssh.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * SSH 服务器连接配置模型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SshConnectionConfig implements Cloneable {

    public enum AuthType {
        PASSWORD("密码认证"),
        PRIVATE_KEY("私钥认证");

        private final String label;

        AuthType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public enum KeySource {
        FILE_PATH("文件路径"),
        TEXT_CONTENT("私钥文本");

        private final String label;

        KeySource(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private String id;
    private String name;
    private String group;
    private String host;
    private int port = 22;
    private String username = "root";
    private AuthType authType = AuthType.PASSWORD;

    // 加密保存的密码
    private String encryptedPassword;

    // 私钥类型及配置。私钥文本只以密文属性写入 JSON。
    private KeySource keySource = KeySource.FILE_PATH;
    private String keyPath;
    private String encryptedKeyContent;
    // 加密保存的私钥 Passphrase
    private String encryptedPassphrase;

    // 连接参数
    private int connectTimeoutMs = 10000;
    private int keepAliveSec = 30;
    private boolean autoReconnect = true;
    private String remarks;

    public SshConnectionConfig() {
        this.id = UUID.randomUUID().toString();
        this.group = "默认分组";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGroup() {
        return (group == null || group.trim().isEmpty()) ? "默认分组" : group.trim();
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port <= 0 ? 22 : port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public AuthType getAuthType() {
        return authType == null ? AuthType.PASSWORD : authType;
    }

    public void setAuthType(AuthType authType) {
        this.authType = authType;
    }

    public String getEncryptedPassword() {
        return encryptedPassword;
    }

    public void setEncryptedPassword(String encryptedPassword) {
        this.encryptedPassword = encryptedPassword;
    }

    public KeySource getKeySource() {
        return keySource == null ? KeySource.FILE_PATH : keySource;
    }

    public void setKeySource(KeySource keySource) {
        this.keySource = keySource;
    }

    public String getKeyPath() {
        return keyPath;
    }

    public void setKeyPath(String keyPath) {
        this.keyPath = keyPath;
    }

    @JsonIgnore
    public String getKeyContent() {
        return SshSecurityUtils.decrypt(encryptedKeyContent);
    }

    @JsonIgnore
    public void setKeyContent(String keyContent) {
        this.encryptedKeyContent = SshSecurityUtils.encrypt(keyContent);
    }

    public String getEncryptedKeyContent() {
        return encryptedKeyContent;
    }

    public void setEncryptedKeyContent(String encryptedKeyContent) {
        this.encryptedKeyContent = encryptedKeyContent;
    }

    /** Reads the pre-1.6 JSON property without ever serializing it back. */
    @JsonProperty(value = "keyContent", access = JsonProperty.Access.WRITE_ONLY)
    public void importLegacyKeyContent(String keyContent) {
        this.encryptedKeyContent = keyContent;
    }

    public String getEncryptedPassphrase() {
        return encryptedPassphrase;
    }

    public void setEncryptedPassphrase(String encryptedPassphrase) {
        this.encryptedPassphrase = encryptedPassphrase;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs <= 0 ? 10000 : connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getKeepAliveSec() {
        return keepAliveSec;
    }

    public void setKeepAliveSec(int keepAliveSec) {
        this.keepAliveSec = keepAliveSec;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    private java.util.List<SshTunnelConfig> tunnels = new java.util.ArrayList<>();

    public java.util.List<SshTunnelConfig> getTunnels() {
        if (tunnels == null) tunnels = new java.util.ArrayList<>();
        return tunnels;
    }

    public void setTunnels(java.util.List<SshTunnelConfig> tunnels) {
        this.tunnels = tunnels;
    }

    public String getRemarks() {
        return remarks;
    }

    public void setRemarks(String remarks) {
        this.remarks = remarks;
    }

    /** Converts legacy/plain secrets before the configuration is persisted. */
    public void normalizeSensitiveValues() {
        encryptedPassword = SshSecurityUtils.migrate(encryptedPassword);
        encryptedPassphrase = SshSecurityUtils.migrate(encryptedPassphrase);
        encryptedKeyContent = SshSecurityUtils.migrate(encryptedKeyContent);
    }

    @Override
    public SshConnectionConfig clone() {
        SshConnectionConfig copy;
        try {
            copy = (SshConnectionConfig) super.clone();
        } catch (CloneNotSupportedException e) {
            copy = new SshConnectionConfig();
            copy.id = this.id;
            copy.name = this.name;
            copy.group = this.group;
            copy.host = this.host;
            copy.port = this.port;
            copy.username = this.username;
            copy.authType = this.authType;
            copy.encryptedPassword = this.encryptedPassword;
            copy.keySource = this.keySource;
            copy.keyPath = this.keyPath;
            copy.encryptedKeyContent = this.encryptedKeyContent;
            copy.encryptedPassphrase = this.encryptedPassphrase;
            copy.connectTimeoutMs = this.connectTimeoutMs;
            copy.keepAliveSec = this.keepAliveSec;
            copy.autoReconnect = this.autoReconnect;
            copy.remarks = this.remarks;
        }
        if (this.tunnels != null) {
            copy.tunnels = new java.util.ArrayList<>();
            for (SshTunnelConfig t : this.tunnels) {
                copy.tunnels.add(t.clone());
            }
        }
        return copy;
    }

    @Override
    public String toString() {
        return (name != null && !name.trim().isEmpty()) ? name : (username + "@" + host + ":" + port);
    }
}
