package com.aqishi.toolbox.vault;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Resumable migration from the two pre-vault sensitive-data sources. */
public final class LegacyVaultMigrator {
    private final ApplicationPaths paths;
    private final VaultRepository repository;
    private final AtomicFiles atomicFiles;
    private final VaultCrypto crypto;
    private final ObjectMapper mapper = new ObjectMapper();

    public LegacyVaultMigrator(ApplicationPaths paths, VaultRepository repository,
                               AtomicFiles atomicFiles, VaultCrypto crypto) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.atomicFiles = Objects.requireNonNull(atomicFiles, "atomicFiles");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
    }

    public MigrationMode probe() throws VaultException {
        boolean password = Files.isRegularFile(paths.getLegacyPasswordFile());
        boolean totp = new LegacyTotpReader(paths.getLegacyConfigFile()).hasAccounts();
        if (repository.exists()) {
            return password || totp ? MigrationMode.CLEANUP_REQUIRED : MigrationMode.NONE;
        }
        if (password && totp) return MigrationMode.BOTH;
        if (password) return MigrationMode.PASSWORD_ONLY;
        if (totp) return MigrationMode.TOTP_ONLY;
        return MigrationMode.NONE;
    }

    public MigrationResult migrate(char[] password) throws VaultException {
        Objects.requireNonNull(password, "password");
        byte[] passwordSource = null;
        byte[] configSource = null;
        try {
            MigrationMode mode = probe();
            if (mode == MigrationMode.CLEANUP_REQUIRED) {
                VaultRepository.OpenedVault opened = repository.open(password.clone());
                List<String> warnings = finishConfigurationCleanup();
                return new MigrationResult(mode, opened, warnings);
            }

            List<PasswordAccount> passwords = Collections.emptyList();
            List<TotpAccount> totps = Collections.emptyList();
            if (mode == MigrationMode.PASSWORD_ONLY || mode == MigrationMode.BOTH) {
                passwordSource = readSource(paths.getLegacyPasswordFile());
                passwords = new LegacyPasswordReader().read(
                        paths.getLegacyPasswordFile(), password.clone());
            }
            if (mode == MigrationMode.TOTP_ONLY || mode == MigrationMode.BOTH) {
                configSource = readSource(paths.getLegacyConfigFile());
                totps = new LegacyTotpReader(paths.getLegacyConfigFile()).readAccounts();
            }

            if (passwordSource != null) {
                backup("legacy-password", passwordSource, password.clone());
            }
            if (configSource != null) {
                backup("legacy-config", configSource, password.clone());
            }

            VaultData data = new VaultData();
            data.setPasswordAccounts(passwords);
            data.setTotpAccounts(totps);
            VaultRepository.OpenedVault created = repository.create(data, password.clone());
            created.close();
            VaultRepository.OpenedVault verified = repository.open(password.clone());

            List<String> warnings;
            try {
                warnings = finishConfigurationCleanup();
            } catch (VaultException error) {
                verified.close();
                throw error;
            }
            return new MigrationResult(mode, verified, warnings);
        } finally {
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(passwordSource);
            VaultCrypto.wipe(configSource);
        }
    }

    private void backup(String prefix, byte[] source, char[] password)
            throws VaultException {
        byte[] salt = crypto.randomBytes(VaultEnvelope.SALT_BYTES);
        byte[] nonce = crypto.randomBytes(VaultEnvelope.NONCE_BYTES);
        byte[] key = null;
        byte[] encoded = null;
        Path backup = paths.getBackupDirectory().resolve(
                prefix + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID()
                        + ".json.enc");
        try {
            key = crypto.deriveKey(password, salt, VaultEnvelope.NEW_FILE_ITERATIONS);
            VaultEnvelope header = VaultEnvelope.newEnvelope(
                    VaultEnvelope.NEW_FILE_ITERATIONS, salt, nonce, new byte[]{1});
            byte[] ciphertext = crypto.encrypt(source, key, nonce, header.aad());
            VaultEnvelope envelope = VaultEnvelope.newEnvelope(
                    VaultEnvelope.NEW_FILE_ITERATIONS, salt, nonce, ciphertext);
            encoded = mapper.writeValueAsBytes(envelope);
            atomicFiles.write(backup, encoded);
            verifyBackup(backup, key);
            VaultCrypto.wipe(ciphertext);
        } catch (VaultException error) {
            deleteQuietly(backup);
            throw error;
        } catch (Exception error) {
            deleteQuietly(backup);
            throw new VaultException(VaultErrorCode.MIGRATION_FAILED,
                    "Unable to create encrypted legacy backup", true, error);
        } finally {
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(key);
            VaultCrypto.wipe(encoded);
        }
    }

    private void verifyBackup(Path backup, byte[] key) throws Exception {
        byte[] encoded = Files.readAllBytes(backup);
        VaultEnvelope envelope = mapper.readValue(encoded, VaultEnvelope.class);
        envelope.validate();
        byte[] plaintext = crypto.decrypt(
                Base64.getDecoder().decode(envelope.getCiphertext()), key,
                Base64.getDecoder().decode(envelope.getNonce()), envelope.aad());
        VaultCrypto.wipe(encoded);
        VaultCrypto.wipe(plaintext);
    }

    private List<String> finishConfigurationCleanup() throws VaultException {
        List<String> warnings = new ArrayList<>();
        Path legacyConfig = paths.getLegacyConfigFile();
        if (Files.isRegularFile(legacyConfig)) {
            byte[] sanitized = new LegacyTotpReader(legacyConfig).sanitizedBytes();
            try {
                atomicFiles.write(paths.getConfigFile(), sanitized);
                String reread = new String(Files.readAllBytes(paths.getConfigFile()),
                        java.nio.charset.StandardCharsets.ISO_8859_1);
                if (reread.contains(LegacyTotpReader.TOTP_KEY)) {
                    throw new IOException("Sensitive TOTP property remains");
                }
            } catch (Exception error) {
                throw new VaultException(VaultErrorCode.MIGRATION_FAILED,
                        "Unable to install sanitized configuration", true, error);
            } finally {
                VaultCrypto.wipe(sanitized);
            }
        }
        deleteLegacy(paths.getLegacyPasswordFile(), warnings);
        if (!samePath(paths.getConfigFile(), legacyConfig)) {
            deleteLegacy(legacyConfig, warnings);
        }
        return warnings;
    }

    private static byte[] readSource(Path source) throws VaultException {
        try {
            return Files.readAllBytes(source);
        } catch (IOException error) {
            throw new VaultException(VaultErrorCode.MIGRATION_FAILED,
                    "Unable to read legacy source", true, error);
        }
    }

    private static void deleteLegacy(Path path, List<String> warnings) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException error) {
            warnings.add(path.toAbsolutePath().toString());
        }
    }

    private static boolean samePath(Path first, Path second) {
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    public enum MigrationMode {
        NONE, PASSWORD_ONLY, TOTP_ONLY, BOTH, CLEANUP_REQUIRED
    }

    public static final class MigrationResult {
        private final MigrationMode mode;
        private final VaultRepository.OpenedVault openedVault;
        private final List<String> warnings;

        private MigrationResult(MigrationMode mode,
                                VaultRepository.OpenedVault openedVault,
                                List<String> warnings) {
            this.mode = mode;
            this.openedVault = openedVault;
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public MigrationMode getMode() {
            return mode;
        }

        public VaultRepository.OpenedVault getOpenedVault() {
            return openedVault;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
