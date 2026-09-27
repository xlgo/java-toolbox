package com.aqishi.toolbox.vault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Versioned plaintext payload that is encrypted as one vault unit.
 *
 * <p>Schema history: version 1 held password and TOTP accounts; version 2 adds
 * {@code connectionSecrets}, an opaque key/value section used by connection
 * tools (database, Redis, Kafka, Kubernetes, SSH) so their saved passwords no
 * longer live in plaintext preferences. Version 1 payloads are upgraded in
 * memory by {@link #upgradeLegacySchema()} and written back as version 2 on the
 * next save.</p>
 */
public class VaultData {
    public static final int SCHEMA_VERSION = 2;
    /** Oldest payload schema that {@link #upgradeLegacySchema()} still accepts. */
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;
    public static final int MAX_RECORDS = 10_000;
    public static final int MAX_TEXT_LENGTH = 4_096;
    public static final int MAX_PASSWORD_LENGTH = 1024 * 1024;

    private int schemaVersion = SCHEMA_VERSION;
    private List<PasswordAccount> passwordAccounts = new ArrayList<>();
    private List<TotpAccount> totpAccounts = new ArrayList<>();
    private TreeMap<String, String> connectionSecrets = new TreeMap<>();

    public VaultData() {
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public List<PasswordAccount> getPasswordAccounts() {
        return copyPasswordAccounts();
    }

    public void setPasswordAccounts(List<PasswordAccount> passwordAccounts) {
        this.passwordAccounts = copyPasswords(passwordAccounts);
    }

    public List<TotpAccount> getTotpAccounts() {
        return copyTotpAccounts();
    }

    public void setTotpAccounts(List<TotpAccount> totpAccounts) {
        this.totpAccounts = copyTotps(totpAccounts);
    }

    public Map<String, String> getConnectionSecrets() {
        return copyConnectionSecrets();
    }

    public void setConnectionSecrets(Map<String, String> connectionSecrets) {
        this.connectionSecrets = copySecrets(connectionSecrets);
    }

    /** Single-entry lookup without copying the whole section. */
    public String connectionSecret(String key) {
        return key == null || connectionSecrets == null ? null : connectionSecrets.get(key);
    }

    public Map<String, String> copyConnectionSecrets() {
        return copySecrets(connectionSecrets);
    }

    /**
     * Brings an older payload up to {@link #SCHEMA_VERSION}. Version 1 had no
     * connection secrets, so it simply gains an empty section. Unknown or
     * future versions are left untouched so {@link #validate()} rejects them.
     */
    public void upgradeLegacySchema() {
        if (schemaVersion >= MIN_SUPPORTED_SCHEMA_VERSION && schemaVersion < SCHEMA_VERSION) {
            if (connectionSecrets == null) connectionSecrets = new TreeMap<>();
            schemaVersion = SCHEMA_VERSION;
        }
    }

    public List<PasswordAccount> copyPasswordAccounts() {
        return copyPasswords(passwordAccounts);
    }

    public List<TotpAccount> copyTotpAccounts() {
        return copyTotps(totpAccounts);
    }

    public VaultData copy() {
        VaultData copy = new VaultData();
        copy.schemaVersion = schemaVersion;
        copy.passwordAccounts = copyPasswords(passwordAccounts);
        copy.totpAccounts = copyTotps(totpAccounts);
        copy.connectionSecrets = copySecrets(connectionSecrets);
        return copy;
    }

    public void validate() throws VaultException {
        if (schemaVersion > SCHEMA_VERSION) {
            throw failure(VaultErrorCode.UNSUPPORTED_FORMAT,
                    "Unsupported vault data schema version");
        }
        if (schemaVersion != SCHEMA_VERSION) {
            throw failure(VaultErrorCode.INVALID_ENVELOPE,
                    "Invalid vault data schema version");
        }
        if (passwordAccounts == null || passwordAccounts.size() > MAX_RECORDS
                || totpAccounts == null || totpAccounts.size() > MAX_RECORDS
                || connectionSecrets == null || connectionSecrets.size() > MAX_RECORDS) {
            throw failure(VaultErrorCode.INVALID_ENVELOPE,
                    "Vault record count is invalid");
        }

        for (PasswordAccount account : passwordAccounts) {
            if (account == null || account.getName() == null) {
                throw failure(VaultErrorCode.INVALID_ENVELOPE,
                        "Password account is malformed");
            }
            requireTextLimit(account.getName(), MAX_TEXT_LENGTH);
            requireTextLimit(account.getUsername(), MAX_TEXT_LENGTH);
            requireTextLimit(account.getUrl(), MAX_TEXT_LENGTH);
            requirePasswordLimit(account.getPassword());
        }

        for (TotpAccount account : totpAccounts) {
            if (account == null || account.getId() == null || account.getLabel() == null
                    || account.getSecret() == null || account.getAlgorithm() == null) {
                throw failure(VaultErrorCode.INVALID_ENVELOPE,
                        "TOTP account is malformed");
            }
            requireTextLimit(account.getId(), MAX_TEXT_LENGTH);
            requireTextLimit(account.getLabel(), MAX_TEXT_LENGTH);
            requireTextLimit(account.getSecret(), MAX_TEXT_LENGTH);
            requireTextLimit(account.getIssuer(), MAX_TEXT_LENGTH);
        }

        for (Map.Entry<String, String> entry : connectionSecrets.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                throw failure(VaultErrorCode.INVALID_ENVELOPE,
                        "Connection secret is malformed");
            }
            requireTextLimit(entry.getKey(), MAX_TEXT_LENGTH);
            requirePasswordLimit(entry.getValue());
        }
    }

    private static TreeMap<String, String> copySecrets(Map<String, String> source) {
        TreeMap<String, String> copy = new TreeMap<>();
        if (source == null || source.isEmpty()) {
            return copy;
        }
        for (Map.Entry<String, String> entry : source.entrySet()) {
            // TreeMap rejects null keys; keep the entry so validate() reports it instead.
            copy.put(entry.getKey() == null ? "" : entry.getKey(), entry.getValue());
        }
        return copy;
    }

    private static List<PasswordAccount> copyPasswords(List<PasswordAccount> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<PasswordAccount> copy = new ArrayList<>(source.size());
        for (PasswordAccount account : source) {
            copy.add(account == null ? null : account.copy());
        }
        return copy;
    }

    private static List<TotpAccount> copyTotps(List<TotpAccount> source) {
        if (source == null || source.isEmpty()) {
            return new ArrayList<>();
        }
        List<TotpAccount> copy = new ArrayList<>(source.size());
        for (TotpAccount account : source) {
            copy.add(account == null ? null : account.copy());
        }
        return copy;
    }

    private static void requireTextLimit(String value, int maximum)
            throws VaultException {
        if (value == null || value.length() > maximum) {
            throw failure(VaultErrorCode.INVALID_ENVELOPE,
                    "Vault account field exceeds its allowed length");
        }
    }

    private static void requirePasswordLimit(String value) throws VaultException {
        if (value == null || value.length() > MAX_PASSWORD_LENGTH
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_LENGTH) {
            throw failure(VaultErrorCode.INVALID_ENVELOPE,
                    "Vault account password exceeds its allowed length");
        }
    }

    private static VaultException failure(VaultErrorCode code, String message) {
        return new VaultException(code, message, false);
    }
}
