package com.aqishi.toolbox.vault;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultRepositoryTest {
    @TempDir Path temp;

    @Test
    void createsOpensAndSavesWithFreshNonce() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultRepository repository = support.repository();
            char[] password = "correct horse battery staple".toCharArray();
            VaultRepository.OpenedVault opened =
                    repository.create(support.sampleData(), password.clone());
            String firstNonce = support.readEnvelope().getNonce();

            VaultData changed = opened.getData();
            List<PasswordAccount> accounts = changed.copyPasswordAccounts();
            accounts.get(0).setUsername("updated");
            changed.setPasswordAccounts(accounts);
            repository.save(opened, changed);
            String secondNonce = support.readEnvelope().getNonce();

            assertNotEquals(firstNonce, secondNonce);
            assertEquals("updated",
                    repository.open(password.clone()).getData()
                            .copyPasswordAccounts().get(0).getUsername());
        }
    }

    @Test
    void jacksonRoundTripPreservesStableEnvelopeAndNestedDataContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VaultData data;
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            data = support.sampleData();
        }
        byte[] dataJson = mapper.writeValueAsBytes(data);
        JsonNode dataTree = mapper.readTree(dataJson);

        assertEquals(setOf("schemaVersion", "passwordAccounts", "totpAccounts"),
                fieldNames(dataTree));
        assertEquals(VaultData.SCHEMA_VERSION, dataTree.get("schemaVersion").asInt());
        JsonNode password = dataTree.get("passwordAccounts").get(0);
        assertEquals(setOf("name", "username", "password", "url"), fieldNames(password));
        assertEquals("GitHub", password.get("name").asText());
        assertEquals("dev", password.get("username").asText());
        assertEquals("password", password.get("password").asText());
        assertEquals("https://github.com", password.get("url").asText());
        JsonNode totp = dataTree.get("totpAccounts").get(0);
        assertEquals(setOf("id", "label", "secret", "issuer", "algorithm",
                "digits", "period", "showDirectly"), fieldNames(totp));
        assertEquals("totp-1", totp.get("id").asText());
        assertEquals("Mail", totp.get("label").asText());
        assertEquals("JBSWY3DPEHPK3PXP", totp.get("secret").asText());
        assertEquals("Example", totp.get("issuer").asText());
        assertEquals("SHA256", totp.get("algorithm").asText());
        assertEquals(8, totp.get("digits").asInt());
        assertEquals(45, totp.get("period").asInt());
        assertFalse(totp.get("showDirectly").asBoolean());

        VaultData decodedData = mapper.readValue(dataJson, VaultData.class);
        decodedData.validate();
        assertEquals("password", decodedData.copyPasswordAccounts().get(0).getPassword());
        assertEquals("JBSWY3DPEHPK3PXP",
                decodedData.copyTotpAccounts().get(0).getSecret());

        VaultEnvelope envelope = VaultEnvelope.newEnvelope(
                VaultEnvelope.NEW_FILE_ITERATIONS,
                new byte[VaultEnvelope.SALT_BYTES],
                new byte[VaultEnvelope.NONCE_BYTES],
                new byte[]{1, 2, 3});
        byte[] aad = envelope.aad();
        byte[] envelopeJson = mapper.writeValueAsBytes(envelope);
        JsonNode envelopeTree = mapper.readTree(envelopeJson);
        assertEquals(setOf("format", "formatVersion", "kdfAlgorithm", "iterations",
                "salt", "cipherAlgorithm", "nonce", "ciphertext"),
                fieldNames(envelopeTree));
        assertEquals(VaultEnvelope.FORMAT, envelopeTree.get("format").asText());
        assertEquals(VaultEnvelope.FORMAT_VERSION,
                envelopeTree.get("formatVersion").asInt());
        assertEquals(VaultEnvelope.KDF, envelopeTree.get("kdfAlgorithm").asText());
        assertEquals(VaultEnvelope.NEW_FILE_ITERATIONS,
                envelopeTree.get("iterations").asInt());
        assertEquals(VaultEnvelope.CIPHER, envelopeTree.get("cipherAlgorithm").asText());
        assertEquals(envelope.getSalt(), envelopeTree.get("salt").asText());
        assertEquals(envelope.getNonce(), envelopeTree.get("nonce").asText());
        assertEquals(envelope.getCiphertext(), envelopeTree.get("ciphertext").asText());

        VaultEnvelope decodedEnvelope = mapper.readValue(envelopeJson, VaultEnvelope.class);
        decodedEnvelope.validate();
        assertArrayEquals(aad, decodedEnvelope.aad());
    }

    @Test
    void wrongPasswordAndModifiedEnvelopeNeverOverwriteFile() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            support.repository().create(
                    support.sampleData(), "right".toCharArray()).close();
            byte[] original = Files.readAllBytes(support.paths().getVaultFile());

            VaultException wrongPassword = assertThrows(VaultException.class,
                    () -> support.repository().open("wrong".toCharArray()));
            assertEquals(VaultErrorCode.AUTHENTICATION_FAILED, wrongPassword.getCode());
            assertArrayEquals(original, Files.readAllBytes(support.paths().getVaultFile()));

            VaultEnvelope modified = support.readEnvelope();
            modified.setIterations(modified.getIterations() + 1);
            new ObjectMapper().writeValue(support.paths().getVaultFile().toFile(), modified);
            byte[] tampered = Files.readAllBytes(support.paths().getVaultFile());
            VaultException authentication = assertThrows(VaultException.class,
                    () -> support.repository().open("right".toCharArray()));
            assertEquals(VaultErrorCode.AUTHENTICATION_FAILED, authentication.getCode());
            assertArrayEquals(tampered, Files.readAllBytes(support.paths().getVaultFile()));
        }
    }

    @Test
    void rejectsOversizedFileBeforeJsonParsing() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            byte[] oversized = new byte[(int) VaultEnvelope.MAX_FILE_BYTES + 1];
            Arrays.fill(oversized, (byte) '{');
            Files.write(support.paths().getVaultFile(), oversized);

            VaultException error = assertThrows(VaultException.class,
                    () -> support.repository().open("irrelevant".toCharArray()));

            assertEquals(VaultErrorCode.FILE_TOO_LARGE, error.getCode());
        }
    }

    @Test
    void rejectsAuthenticatedPayloadMissingRequiredCollections() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultCrypto crypto = new VaultCrypto();
            char[] password = "master".toCharArray();
            byte[] salt = crypto.randomBytes(VaultEnvelope.SALT_BYTES);
            byte[] nonce = crypto.randomBytes(VaultEnvelope.NONCE_BYTES);
            byte[] key = crypto.deriveKey(
                    password, salt, VaultEnvelope.NEW_FILE_ITERATIONS);
            VaultEnvelope header = VaultEnvelope.newEnvelope(
                    VaultEnvelope.NEW_FILE_ITERATIONS, salt, nonce, new byte[]{1});
            byte[] ciphertext = crypto.encrypt(
                    "{\"schemaVersion\":1}".getBytes(StandardCharsets.UTF_8),
                    key, nonce, header.aad());
            VaultEnvelope envelope = VaultEnvelope.newEnvelope(
                    VaultEnvelope.NEW_FILE_ITERATIONS, salt, nonce, ciphertext);
            new ObjectMapper().writeValue(
                    support.paths().getVaultFile().toFile(), envelope);

            VaultException error = assertThrows(VaultException.class,
                    () -> support.repository().open("master".toCharArray()));

            assertEquals(VaultErrorCode.INVALID_ENVELOPE, error.getCode());
            VaultCrypto.wipe(password);
            VaultCrypto.wipe(key);
        }
    }

    @Test
    void savesEncryptedBackupBeforeReplacingCurrentVault() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultRepository.OpenedVault opened = support.repository().create(
                    support.sampleData(), "master".toCharArray());
            byte[] original = Files.readAllBytes(support.paths().getVaultFile());

            support.repository().save(opened, opened.getData());

            List<Path> backups;
            try (java.util.stream.Stream<Path> files =
                         Files.list(support.paths().getBackupDirectory())) {
                backups = new java.util.ArrayList<>();
                files.forEach(backups::add);
            }
            assertEquals(1, backups.size());
            assertArrayEquals(original, Files.readAllBytes(backups.get(0)));
        }
    }

    @Test
    void failedAtomicInstallationPreservesOriginalAndOpenedState() throws Exception {
        AtomicFiles failAfterCreate = new FailOnNthVaultInstallAtomicFiles(2);
        try (VaultTestSupport support = new VaultTestSupport(temp, failAfterCreate)) {
            VaultRepository.OpenedVault opened = support.repository().create(
                    support.sampleData(), "master".toCharArray());
            byte[] original = Files.readAllBytes(support.paths().getVaultFile());
            VaultData changed = opened.getData();
            List<PasswordAccount> accounts = changed.copyPasswordAccounts();
            accounts.get(0).setUsername("not-installed");
            changed.setPasswordAccounts(accounts);

            VaultException error = assertThrows(VaultException.class,
                    () -> support.repository().save(opened, changed));

            assertEquals(VaultErrorCode.WRITE_FAILED, error.getCode());
            assertArrayEquals(original, Files.readAllBytes(support.paths().getVaultFile()));
            assertEquals("dev", opened.getData().copyPasswordAccounts().get(0).getUsername());
        }
    }

    @Test
    void moveFailureAfterReplacementRollsBackToOriginalVault() throws Exception {
        ReplaceThenFailAtomicFiles atomicFiles = new ReplaceThenFailAtomicFiles(2);
        try (VaultTestSupport support = new VaultTestSupport(temp, atomicFiles)) {
            VaultRepository.OpenedVault opened = support.repository().create(
                    support.sampleData(), "master".toCharArray());
            byte[] original = Files.readAllBytes(support.paths().getVaultFile());
            VaultData changed = opened.getData();
            List<PasswordAccount> accounts = changed.copyPasswordAccounts();
            accounts.get(0).setUsername("must-be-rolled-back");
            changed.setPasswordAccounts(accounts);

            VaultException error = assertThrows(VaultException.class,
                    () -> support.repository().save(opened, changed));

            assertEquals(VaultErrorCode.WRITE_FAILED, error.getCode());
            assertArrayEquals(original, Files.readAllBytes(support.paths().getVaultFile()));
            assertEquals("dev", support.repository().open("master".toCharArray())
                    .getData().copyPasswordAccounts().get(0).getUsername());
        }
    }

    @Test
    void candidateMustDecryptAndValidateBeforeBackupOrReplacement() throws Exception {
        CorruptNthCandidateAtomicFiles atomicFiles =
                new CorruptNthCandidateAtomicFiles(2);
        try (VaultTestSupport support = new VaultTestSupport(temp, atomicFiles)) {
            VaultRepository.OpenedVault opened = support.repository().create(
                    support.sampleData(), "master".toCharArray());
            byte[] original = Files.readAllBytes(support.paths().getVaultFile());
            VaultData changed = opened.getData();
            List<PasswordAccount> accounts = changed.copyPasswordAccounts();
            accounts.get(0).setUsername("unverified");
            changed.setPasswordAccounts(accounts);

            VaultException error = assertThrows(VaultException.class,
                    () -> support.repository().save(opened, changed));

            assertEquals(VaultErrorCode.WRITE_FAILED, error.getCode());
            assertArrayEquals(original, Files.readAllBytes(support.paths().getVaultFile()));
            try (java.util.stream.Stream<Path> backups =
                         Files.list(support.paths().getBackupDirectory())) {
                assertEquals(0, backups.count());
            }
        }
    }

    @Test
    void fallsBackWhenAtomicMoveIsUnsupported() throws Exception {
        AtomicMoveFallbackFiles atomicFiles = new AtomicMoveFallbackFiles();
        try (VaultTestSupport support = new VaultTestSupport(temp, atomicFiles)) {
            VaultRepository.OpenedVault opened = support.repository().create(
                    support.sampleData(), "master".toCharArray());
            support.repository().save(opened, opened.getData());

            assertTrue(atomicFiles.fallbackMoves > 0);
            assertEquals("dev", support.repository().open("master".toCharArray())
                    .getData().copyPasswordAccounts().get(0).getUsername());
        }
    }

    @Test
    void secondProcessLockIsReadOnlyAndStaleFileIsReusable() throws Exception {
        Path lockFile = temp.resolve("toolbox-vault.lock");
        try (VaultFileLock first = new VaultFileLock(lockFile);
             VaultFileLock second = new VaultFileLock(lockFile)) {
            assertTrue(first.isWritable());
            assertFalse(second.isWritable());
        }

        try (VaultFileLock afterRelease = new VaultFileLock(lockFile)) {
            assertTrue(afterRelease.isWritable());
        }
    }

    @Test
    void rekeysOnlyAfterNewVaultIsVerified() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultRepository.OpenedVault opened = support.repository().create(
                    support.sampleData(), "old-master".toCharArray());

            support.repository().rekey(opened, "new-master".toCharArray());

            VaultException oldPassword = assertThrows(VaultException.class,
                    () -> support.repository().open("old-master".toCharArray()));
            assertEquals(VaultErrorCode.AUTHENTICATION_FAILED, oldPassword.getCode());
            assertEquals("password", support.repository().open("new-master".toCharArray())
                    .getData().copyPasswordAccounts().get(0).getPassword());
            support.repository().save(opened, opened.getData());
        }
    }

    @Test
    void refusesOverwriteAndClosedSessionUse() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            VaultRepository.OpenedVault opened = support.repository().create(
                    support.sampleData(), "master".toCharArray());
            byte[] original = Files.readAllBytes(support.paths().getVaultFile());

            VaultException overwrite = assertThrows(VaultException.class,
                    () -> support.repository().create(
                            new VaultData(), "different".toCharArray()));
            assertEquals(VaultErrorCode.WRITE_FAILED, overwrite.getCode());
            assertArrayEquals(original, Files.readAllBytes(support.paths().getVaultFile()));

            opened.close();
            assertThrows(IllegalStateException.class, opened::getData);
            VaultException closed = assertThrows(VaultException.class,
                    () -> support.repository().save(opened, support.sampleData()));
            assertEquals(VaultErrorCode.READ_ONLY, closed.getCode());
        }
    }

    @Test
    void readOnlyRepositoryCanOpenButCannotSave() throws Exception {
        ApplicationPaths paths;
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            paths = support.paths();
            support.repository().create(support.sampleData(), "master".toCharArray()).close();
        }

        try (VaultFileLock writer = new VaultFileLock(paths.getLockFile());
             VaultFileLock readerLock = new VaultFileLock(paths.getLockFile())) {
            assertTrue(writer.isWritable());
            assertFalse(readerLock.isWritable());
            VaultRepository reader = new VaultRepository(
                    paths, new AtomicFiles(), new VaultCrypto(), readerLock);
            VaultRepository.OpenedVault opened = reader.open("master".toCharArray());

            VaultException error = assertThrows(VaultException.class,
                    () -> reader.save(opened, opened.getData()));

            assertEquals(VaultErrorCode.READ_ONLY, error.getCode());
        }
    }

    @Test
    void takesOwnershipAndWipesPasswordArrays() throws Exception {
        try (VaultTestSupport support = new VaultTestSupport(temp)) {
            char[] createPassword = "master".toCharArray();
            support.repository().create(support.sampleData(), createPassword);
            assertArrayEquals(new char[createPassword.length], createPassword);

            char[] openPassword = "master".toCharArray();
            VaultRepository.OpenedVault opened = support.repository().open(openPassword);
            assertArrayEquals(new char[openPassword.length], openPassword);

            char[] newPassword = "changed".toCharArray();
            support.repository().rekey(opened, newPassword);
            assertArrayEquals(new char[newPassword.length], newPassword);

            char[] wrongPassword = "wrong".toCharArray();
            assertThrows(VaultException.class,
                    () -> support.repository().open(wrongPassword));
            assertArrayEquals(new char[wrongPassword.length], wrongPassword);

            opened.close();
            char[] rejectedRekey = "not-used".toCharArray();
            assertThrows(VaultException.class,
                    () -> support.repository().rekey(opened, rejectedRekey));
            assertArrayEquals(new char[rejectedRekey.length], rejectedRekey);
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> setOf(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static final class FailOnNthVaultInstallAtomicFiles extends AtomicFiles {
        private final int failureNumber;
        private int installations;

        private FailOnNthVaultInstallAtomicFiles(int failureNumber) {
            this.failureNumber = failureNumber;
        }

        @Override
        public void replace(Path source, Path target) throws IOException {
            if (target.getFileName().toString().equals("toolbox-vault.json.enc")
                    && ++installations == failureNumber) {
                throw new IOException("injected install failure");
            }
            super.replace(source, target);
        }
    }

    private static final class AtomicMoveFallbackFiles extends AtomicFiles {
        private int fallbackMoves;

        @Override
        protected void moveAtomically(Path source, Path target) throws IOException {
            throw new AtomicMoveNotSupportedException(
                    source.toString(), target.toString(), "injected fallback");
        }

        @Override
        protected void moveReplacing(Path source, Path target) throws IOException {
            fallbackMoves++;
            super.moveReplacing(source, target);
        }
    }

    private static final class ReplaceThenFailAtomicFiles extends AtomicFiles {
        private final int failureNumber;
        private int installations;

        private ReplaceThenFailAtomicFiles(int failureNumber) {
            this.failureNumber = failureNumber;
        }

        @Override
        public void replace(Path source, Path target) throws IOException {
            if (target.getFileName().toString().equals("toolbox-vault.json.enc")
                    && ++installations == failureNumber) {
                super.replace(source, target);
                throw new IOException("injected failure after replacement");
            }
            super.replace(source, target);
        }
    }

    private static final class CorruptNthCandidateAtomicFiles extends AtomicFiles {
        private final int corruptionNumber;
        private int candidates;

        private CorruptNthCandidateAtomicFiles(int corruptionNumber) {
            this.corruptionNumber = corruptionNumber;
        }

        @Override
        public void write(Path target, byte[] bytes) throws IOException {
            super.write(target, bytes);
            if (target.getFileName().toString().endsWith(".candidate")
                    && ++candidates == corruptionNumber) {
                Files.write(target, new byte[]{'{', '}'});
            }
        }
    }
}
