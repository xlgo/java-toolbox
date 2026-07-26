package com.aqishi.toolbox.vault;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Serializes and installs authenticated vault files as verified transactions. */
public final class VaultRepository implements AutoCloseable {
    private final ApplicationPaths paths;
    private final AtomicFiles atomicFiles;
    private final VaultCrypto crypto;
    private final VaultFileLock fileLock;
    private final ObjectMapper mapper;
    private boolean closed;

    public VaultRepository(ApplicationPaths paths, AtomicFiles atomicFiles,
                           VaultCrypto crypto, VaultFileLock fileLock) {
        this(paths, atomicFiles, crypto, fileLock, new ObjectMapper());
    }

    VaultRepository(ApplicationPaths paths, AtomicFiles atomicFiles,
                    VaultCrypto crypto, VaultFileLock fileLock, ObjectMapper mapper) {
        this.paths = Objects.requireNonNull(paths, "paths");
        this.atomicFiles = Objects.requireNonNull(atomicFiles, "atomicFiles");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.fileLock = Objects.requireNonNull(fileLock, "fileLock");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public synchronized boolean exists() {
        return Files.isRegularFile(paths.getVaultFile());
    }

    public synchronized OpenedVault create(VaultData data, char[] password)
            throws VaultException {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(password, "password");
        byte[] key = null;
        try {
            requireWritable();
            if (Files.exists(paths.getVaultFile())) {
                throw writeFailure("Vault already exists", null);
            }
            VaultData validated = data.copy();
            validated.validate();
            byte[] salt = crypto.randomBytes(VaultEnvelope.SALT_BYTES);
            key = crypto.deriveKey(password, salt, VaultEnvelope.NEW_FILE_ITERATIONS);
            VaultEnvelope envelope = encrypt(
                    validated, key, salt, VaultEnvelope.NEW_FILE_ITERATIONS);
            byte[] encoded = serializeEnvelope(envelope);
            VerifiedVault installed = installVerified(encoded, key, null, false);
            return new OpenedVault(this, installed.data, installed.envelope, key);
        } finally {
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(key);
        }
    }

    public synchronized OpenedVault open(char[] password) throws VaultException {
        Objects.requireNonNull(password, "password");
        byte[] key = null;
        try {
            requireOpen();
            ParsedEnvelope parsed = readEnvelope(paths.getVaultFile());
            key = crypto.deriveKey(password, parsed.salt, parsed.envelope.getIterations());
            VaultData data = decryptData(parsed, key);
            return new OpenedVault(this, data, parsed.envelope, key);
        } finally {
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(key);
        }
    }

    public synchronized void save(OpenedVault opened, VaultData data)
            throws VaultException {
        Objects.requireNonNull(data, "data");
        requireWritable();
        requireSession(opened);
        byte[] key = opened.copyKey();
        try {
            VaultData validated = data.copy();
            validated.validate();
            VaultEnvelope current = opened.copyEnvelope();
            byte[] salt = decode(current.getSalt());
            VaultEnvelope envelope = encrypt(
                    validated, key, salt, current.getIterations());
            VerifiedVault installed = installVerified(
                    serializeEnvelope(envelope), key, key, true);
            opened.replaceAfterSave(installed.data, installed.envelope);
        } finally {
            VaultCrypto.wipe(key);
        }
    }

    public synchronized void rekey(OpenedVault opened, char[] newPassword)
            throws VaultException {
        Objects.requireNonNull(newPassword, "newPassword");
        byte[] oldKey = null;
        byte[] newKey = null;
        try {
            requireWritable();
            requireSession(opened);
            oldKey = opened.copyKey();
            VaultData data = opened.copyData();
            byte[] salt = crypto.randomBytes(VaultEnvelope.SALT_BYTES);
            newKey = crypto.deriveKey(
                    newPassword, salt, VaultEnvelope.NEW_FILE_ITERATIONS);
            VaultEnvelope envelope = encrypt(
                    data, newKey, salt, VaultEnvelope.NEW_FILE_ITERATIONS);
            VerifiedVault installed = installVerified(
                    serializeEnvelope(envelope), newKey, oldKey, true);
            opened.replaceAfterRekey(installed.data, installed.envelope, newKey);
        } finally {
            VaultCrypto.wipe(newPassword);
            VaultCrypto.wipe(oldKey);
            VaultCrypto.wipe(newKey);
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        fileLock.close();
    }

    private VaultEnvelope encrypt(VaultData data, byte[] key, byte[] salt,
                                  int iterations) throws VaultException {
        byte[] plaintext = serializeData(data);
        byte[] nonce = crypto.randomBytes(VaultEnvelope.NONCE_BYTES);
        try {
            VaultEnvelope header = VaultEnvelope.newEnvelope(
                    iterations, salt, nonce, new byte[]{1});
            byte[] ciphertext = crypto.encrypt(plaintext, key, nonce, header.aad());
            VaultEnvelope envelope = VaultEnvelope.newEnvelope(
                    iterations, salt, nonce, ciphertext);
            envelope.validate();
            return envelope;
        } finally {
            VaultCrypto.wipe(plaintext);
        }
    }

    private byte[] serializeData(VaultData data) throws VaultException {
        try {
            return mapper.writeValueAsBytes(data);
        } catch (JsonProcessingException error) {
            throw writeFailure("Unable to serialize vault data", error);
        }
    }

    private byte[] serializeEnvelope(VaultEnvelope envelope) throws VaultException {
        try {
            byte[] encoded = mapper.writeValueAsBytes(envelope);
            if (encoded.length > VaultEnvelope.MAX_FILE_BYTES) {
                throw new VaultException(VaultErrorCode.FILE_TOO_LARGE,
                        "Vault file exceeds the supported size", false);
            }
            return encoded;
        } catch (JsonProcessingException error) {
            throw writeFailure("Unable to serialize vault envelope", error);
        }
    }

    private VerifiedVault installVerified(byte[] encoded, byte[] newKey,
                                          byte[] currentKey,
                                          boolean currentRequired)
            throws VaultException {
        Path target = paths.getVaultFile();
        Path candidate = target.resolveSibling(target.getFileName() + ".candidate");
        Path backup = null;
        try {
            atomicFiles.write(candidate, encoded);
            try {
                verifyWithKey(candidate, newKey);
            } catch (VaultException verificationFailure) {
                throw writeFailure(
                        "Candidate vault failed verification", verificationFailure);
            }

            if (Files.exists(target)) {
                if (currentKey == null) {
                    throw writeFailure("Refusing to replace an existing vault", null);
                }
                verifyWithKey(target, currentKey);
                backup = backupCurrent(target, currentKey);
            } else if (currentRequired) {
                throw writeFailure("Current vault is missing", null);
            }

            atomicFiles.replace(candidate, target);
            try {
                return verifyWithKey(target, newKey);
            } catch (VaultException verificationFailure) {
                restoreAfterFailedInstallation(target, backup, currentKey,
                        verificationFailure);
                throw writeFailure("Installed vault failed verification",
                        verificationFailure);
            }
        } catch (IOException error) {
            VaultException failure = writeFailure(
                    "Unable to install verified vault", error);
            if (backup != null) {
                restoreAfterFailedInstallation(
                        target, backup, currentKey, failure);
            }
            throw failure;
        } finally {
            deleteQuietly(candidate);
        }
    }

    private Path backupCurrent(Path target, byte[] key)
            throws IOException, VaultException {
        Files.createDirectories(paths.getBackupDirectory());
        Path backup = paths.getBackupDirectory().resolve(
                "toolbox-vault-" + System.currentTimeMillis() + "-" + UUID.randomUUID()
                        + ".json.enc");
        atomicFiles.write(backup, Files.readAllBytes(target));
        try {
            verifyWithKey(backup, key);
            return backup;
        } catch (VaultException error) {
            deleteQuietly(backup);
            throw error;
        }
    }

    private void restoreAfterFailedInstallation(Path target, Path backup,
                                                byte[] currentKey,
                                                VaultException original) {
        if (backup == null || currentKey == null) {
            deleteQuietly(target);
            return;
        }
        try {
            atomicFiles.write(target, Files.readAllBytes(backup));
            verifyWithKey(target, currentKey);
        } catch (Exception restoreFailure) {
            original.addSuppressed(restoreFailure);
        }
    }

    private VerifiedVault verifyWithKey(Path file, byte[] key) throws VaultException {
        ParsedEnvelope parsed = readEnvelope(file);
        return new VerifiedVault(decryptData(parsed, key), parsed.envelope);
    }

    private ParsedEnvelope readEnvelope(Path file) throws VaultException {
        final long size;
        try {
            size = Files.size(file);
        } catch (IOException error) {
            throw readFailure("Unable to read vault file", true, error);
        }
        if (size > VaultEnvelope.MAX_FILE_BYTES) {
            throw new VaultException(VaultErrorCode.FILE_TOO_LARGE,
                    "Vault file exceeds the supported size", false);
        }

        final byte[] encoded;
        try {
            encoded = Files.readAllBytes(file);
        } catch (IOException error) {
            throw readFailure("Unable to read vault file", true, error);
        }
        if (encoded.length > VaultEnvelope.MAX_FILE_BYTES) {
            throw new VaultException(VaultErrorCode.FILE_TOO_LARGE,
                    "Vault file exceeds the supported size", false);
        }

        final VaultEnvelope envelope;
        try {
            envelope = mapper.readValue(encoded, VaultEnvelope.class);
        } catch (IOException error) {
            throw readFailure("Unable to parse vault envelope", false, error);
        }
        envelope.validate();
        return new ParsedEnvelope(
                envelope,
                decode(envelope.getSalt()),
                decode(envelope.getNonce()),
                decode(envelope.getCiphertext()));
    }

    private VaultData decryptData(ParsedEnvelope parsed, byte[] key)
            throws VaultException {
        byte[] plaintext = crypto.decrypt(
                parsed.ciphertext, key, parsed.nonce, parsed.envelope.aad());
        try {
            final VaultData data;
            try {
                JsonNode root = mapper.readTree(plaintext);
                if (root == null || !root.isObject()
                        || !root.has("schemaVersion")
                        || !root.has("passwordAccounts")
                        || !root.get("passwordAccounts").isArray()
                        || !root.has("totpAccounts")
                        || !root.get("totpAccounts").isArray()) {
                    throw new VaultException(
                            VaultErrorCode.INVALID_ENVELOPE,
                            "Vault data is missing required collections", false);
                }
                data = mapper.treeToValue(root, VaultData.class);
            } catch (IOException error) {
                throw readFailure("Unable to parse vault data", false, error);
            }
            data.validate();
            return data;
        } finally {
            VaultCrypto.wipe(plaintext);
        }
    }

    private void requireOpen() throws VaultException {
        if (closed) {
            throw new VaultException(
                    VaultErrorCode.READ_ONLY, "Vault repository is closed", false);
        }
    }

    private void requireWritable() throws VaultException {
        requireOpen();
        if (!fileLock.isWritable()) {
            throw new VaultException(
                    VaultErrorCode.READ_ONLY, "Vault is open read-only", true);
        }
    }

    private void requireSession(OpenedVault opened) throws VaultException {
        if (opened == null || opened.owner != this || opened.isClosed()) {
            throw new VaultException(
                    VaultErrorCode.READ_ONLY, "Vault session is unavailable", false);
        }
    }

    private static byte[] decode(String value) throws VaultException {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException error) {
            throw new VaultException(VaultErrorCode.INVALID_ENVELOPE,
                    "Vault Base64 field is invalid", false, error);
        }
    }

    private static VaultEnvelope copyEnvelope(VaultEnvelope source) {
        VaultEnvelope copy = new VaultEnvelope();
        copy.setFormat(source.getFormat());
        copy.setFormatVersion(source.getFormatVersion());
        copy.setKdfAlgorithm(source.getKdfAlgorithm());
        copy.setIterations(source.getIterations());
        copy.setSalt(source.getSalt());
        copy.setCipherAlgorithm(source.getCipherAlgorithm());
        copy.setNonce(source.getNonce());
        copy.setCiphertext(source.getCiphertext());
        return copy;
    }

    private static VaultException readFailure(
            String message, boolean retryable, Throwable cause) {
        return new VaultException(VaultErrorCode.READ_FAILED, message, retryable, cause);
    }

    private static VaultException writeFailure(String message, Throwable cause) {
        return cause == null
                ? new VaultException(VaultErrorCode.WRITE_FAILED, message, true)
                : new VaultException(VaultErrorCode.WRITE_FAILED, message, true, cause);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // A stale candidate is never authoritative and is replaced on the next attempt.
        }
    }

    public static final class OpenedVault implements AutoCloseable {
        private final VaultRepository owner;
        private VaultData data;
        private VaultEnvelope envelope;
        private byte[] key;
        private boolean closed;

        private OpenedVault(VaultRepository owner, VaultData data,
                            VaultEnvelope envelope, byte[] key) {
            this.owner = owner;
            this.data = data.copy();
            this.envelope = VaultRepository.copyEnvelope(envelope);
            this.key = key.clone();
        }

        public synchronized VaultData getData() {
            if (closed) {
                throw new IllegalStateException("Vault session is closed");
            }
            return data.copy();
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            VaultCrypto.wipe(key);
            key = null;
            data = null;
            envelope = null;
        }

        private synchronized boolean isClosed() {
            return closed;
        }

        private synchronized byte[] copyKey() {
            if (closed) {
                throw new IllegalStateException("Vault session is closed");
            }
            return key.clone();
        }

        private synchronized VaultData copyData() {
            if (closed) {
                throw new IllegalStateException("Vault session is closed");
            }
            return data.copy();
        }

        private synchronized VaultEnvelope copyEnvelope() {
            if (closed) {
                throw new IllegalStateException("Vault session is closed");
            }
            return VaultRepository.copyEnvelope(envelope);
        }

        private synchronized void replaceAfterSave(
                VaultData installedData, VaultEnvelope installedEnvelope) {
            data = installedData.copy();
            envelope = VaultRepository.copyEnvelope(installedEnvelope);
        }

        private synchronized void replaceAfterRekey(
                VaultData installedData, VaultEnvelope installedEnvelope,
                byte[] installedKey) {
            byte[] replacement = installedKey.clone();
            VaultCrypto.wipe(key);
            key = replacement;
            data = installedData.copy();
            envelope = VaultRepository.copyEnvelope(installedEnvelope);
        }
    }

    private static final class ParsedEnvelope {
        private final VaultEnvelope envelope;
        private final byte[] salt;
        private final byte[] nonce;
        private final byte[] ciphertext;

        private ParsedEnvelope(VaultEnvelope envelope, byte[] salt,
                               byte[] nonce, byte[] ciphertext) {
            this.envelope = envelope;
            this.salt = salt;
            this.nonce = nonce;
            this.ciphertext = ciphertext;
        }
    }

    private static final class VerifiedVault {
        private final VaultData data;
        private final VaultEnvelope envelope;

        private VerifiedVault(VaultData data, VaultEnvelope envelope) {
            this.data = data;
            this.envelope = envelope;
        }
    }
}
