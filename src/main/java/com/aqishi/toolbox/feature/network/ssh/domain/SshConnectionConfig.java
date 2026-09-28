package com.aqishi.toolbox.feature.network.ssh.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

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

    /**
     * True when password / passphrase / key text are kept in the vault. The JSON file then
     * holds none of them; they are loaded into the {@code vault*} runtime fields while the
     * vault is unlocked and dropped again when it locks.
     */
    private boolean secretStored;
    @JsonIgnore
    private transient String vaultPassword;
    @JsonIgnore
    private transient String vaultPassphrase;
    @JsonIgnore
    private transient String vaultKeyContent;

    /** Secret field names used for the vault entry. */
    public static final String SECRET_PASSWORD = "password";
    public static final String SECRET_PASSPHRASE = "passphrase";
    public static final String SECRET_KEY_CONTENT = "keyContent";

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
        if (encryptedKeyContent != null && !encryptedKeyContent.isEmpty()) {
            return SshSecurityUtils.decrypt(encryptedKeyContent);
        }
        return vaultKeyContent == null ? "" : vaultKeyContent;
    }

    /** Password to authenticate with: a locally entered value wins over the vault copy. */
    public String resolvedPassword() {
        if (encryptedPassword != null && !encryptedPassword.isEmpty()) {
            return SshSecurityUtils.decrypt(encryptedPassword);
        }
        return vaultPassword == null ? "" : vaultPassword;
    }

    /** Key passphrase to use: a locally entered value wins over the vault copy. */
    public String resolvedPassphrase() {
        if (encryptedPassphrase != null && !encryptedPassphrase.isEmpty()) {
            return SshSecurityUtils.decrypt(encryptedPassphrase);
        }
        return vaultPassphrase == null ? "" : vaultPassphrase;
    }

    public boolean isSecretStored() {
        return secretStored;
    }

    public void setSecretStored(boolean secretStored) {
        this.secretStored = secretStored;
    }

    /** Secrets still held in the (machine-key obfuscated) local form, awaiting the vault. */
    public boolean hasLocalSecrets() {
        return notEmpty(encryptedPassword) || notEmpty(encryptedPassphrase)
                || notEmpty(encryptedKeyContent);
    }

    /** Decrypted local secrets by vault field name; empty values are omitted. */
    public Map<String, String> localSecretFields() {
        Map<String, String> fields = new HashMap<>();
        put(fields, SECRET_PASSWORD, notEmpty(encryptedPassword) ? SshSecurityUtils.decrypt(encryptedPassword) : null);
        put(fields, SECRET_PASSPHRASE, notEmpty(encryptedPassphrase) ? SshSecurityUtils.decrypt(encryptedPassphrase) : null);
        put(fields, SECRET_KEY_CONTENT, notEmpty(encryptedKeyContent) ? SshSecurityUtils.decrypt(encryptedKeyContent) : null);
        return fields;
    }

    public void clearLocalSecrets() {
        encryptedPassword = "";
        encryptedPassphrase = "";
        encryptedKeyContent = "";
    }

    /** Installs vault values for this session; {@code null} drops them (vault locked). */
    public void applyVaultSecrets(Map<String, String> fields) {
        vaultPassword = fields == null ? null : fields.get(SECRET_PASSWORD);
        vaultPassphrase = fields == null ? null : fields.get(SECRET_PASSPHRASE);
        vaultKeyContent = fields == null ? null : fields.get(SECRET_KEY_CONTENT);
    }

    public boolean hasVaultSecretsLoaded() {
        return vaultPassword != null || vaultPassphrase != null || vaultKeyContent != null;
    }

    /** The field a password typed for this session only belongs to. */
    public String primarySecretField() {
        return getAuthType() == AuthType.PRIVATE_KEY ? SECRET_PASSPHRASE : SECRET_PASSWORD;
    }

    private static boolean notEmpty(String value) {
        return value != null && !value.isEmpty();
    }

    private static void put(Map<String, String> fields, String key, String value) {
        if (value != null && !value.isEmpty()) fields.put(key, value);
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

    /**
     * Tunnel definitions. A copy-on-write list: the Swing tunnel panel edits it while the
     * session lifecycle thread iterates it to restore or pause tunnels.
     */
    private java.util.List<SshTunnelConfig> tunnels = new CopyOnWriteArrayList<>();

    public java.util.List<SshTunnelConfig> getTunnels() {
        if (tunnels == null) tunnels = new CopyOnWriteArrayList<>();
        return tunnels;
    }

    public void setTunnels(java.util.List<SshTunnelConfig> tunnels) {
        CopyOnWriteArrayList<SshTunnelConfig> copy = new CopyOnWriteArrayList<>();
        if (tunnels != null) {
            for (SshTunnelConfig tunnel : tunnels) {
                if (tunnel != null) copy.add(tunnel);
            }
        }
        this.tunnels = copy;
    }

    /**
     * Takes over every connection field of {@code source} (including credentials, the vault
     * flag and credentials loaded from the vault for this session) but neither the ID nor
     * the tunnels: those belong to the stored instance being updated.
     */
    public void copyConnectionFrom(SshConnectionConfig source) {
        if (source == null || source == this) return;
        name = source.name;
        group = source.group;
        host = source.host;
        port = source.port;
        username = source.username;
        authType = source.authType;
        encryptedPassword = source.encryptedPassword;
        keySource = source.keySource;
        keyPath = source.keyPath;
        encryptedKeyContent = source.encryptedKeyContent;
        encryptedPassphrase = source.encryptedPassphrase;
        connectTimeoutMs = source.connectTimeoutMs;
        keepAliveSec = source.keepAliveSec;
        autoReconnect = source.autoReconnect;
        remarks = source.remarks;
        secretStored = source.secretStored;
        vaultPassword = source.vaultPassword;
        vaultPassphrase = source.vaultPassphrase;
        vaultKeyContent = source.vaultKeyContent;
    }

    /**
     * A copy for "duplicate server": new server ID, and tunnels with new IDs and no runtime
     * state, so the copy never shares tunnel identities or a running local port with the
     * original.
     */
    public SshConnectionConfig duplicate() {
        SshConnectionConfig copy = clone();
        copy.id = UUID.randomUUID().toString();
        CopyOnWriteArrayList<SshTunnelConfig> fresh = new CopyOnWriteArrayList<>();
        for (SshTunnelConfig tunnel : getTunnels()) {
            SshTunnelConfig definition = tunnel.copyDefinition();
            definition.setId(UUID.randomUUID().toString());
            fresh.add(definition);
        }
        copy.tunnels = fresh;
        return copy;
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
            copy.secretStored = this.secretStored;
            copy.vaultPassword = this.vaultPassword;
            copy.vaultPassphrase = this.vaultPassphrase;
            copy.vaultKeyContent = this.vaultKeyContent;
        }
        copy.tunnels = new CopyOnWriteArrayList<>();
        if (this.tunnels != null) {
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
