package com.aqishi.toolbox.vault;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** Reads only the legacy TOTP property and preserves all non-sensitive settings. */
public final class LegacyTotpReader {
    static final String TOTP_KEY = "totp.accounts";
    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper();

    public LegacyTotpReader(Path path) {
        this.path = path;
    }

    public boolean hasAccounts() throws VaultException {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String value = load().getProperty(TOTP_KEY);
        return value != null && !value.trim().isEmpty() && !"[]".equals(value.trim());
    }

    public List<TotpAccount> readAccounts() throws VaultException {
        String json = load().getProperty(TOTP_KEY, "[]");
        try {
            List<TotpAccount> accounts = mapper.readValue(
                    json, new TypeReference<List<TotpAccount>>() { });
            return accounts == null ? new ArrayList<TotpAccount>() : accounts;
        } catch (Exception error) {
            throw new VaultException(VaultErrorCode.MIGRATION_FAILED,
                    "Legacy TOTP data is invalid", true, error);
        }
    }

    public byte[] sanitizedBytes() throws VaultException {
        Properties properties = load();
        properties.remove(TOTP_KEY);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, "Java Toolbox Configuration");
            return output.toByteArray();
        } catch (Exception error) {
            throw new VaultException(VaultErrorCode.MIGRATION_FAILED,
                    "Unable to sanitize legacy configuration", true, error);
        }
    }

    private Properties load() throws VaultException {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            return properties;
        } catch (Exception error) {
            throw new VaultException(VaultErrorCode.MIGRATION_FAILED,
                    "Unable to read legacy configuration", true, error);
        }
    }
}
