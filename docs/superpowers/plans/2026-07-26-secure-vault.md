# Secure Unified Vault Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the legacy password-file encryption and plaintext TOTP configuration with one Java 8-compatible, authenticated, versioned vault shared by the password manager and TOTP tools.

**Architecture:** Build a focused com.aqishi.toolbox.vault package for paths, crypto, envelopes, repository transactions, legacy migration, session lifecycle, and clipboard handling. MainFrame creates one VaultService and injects it into both panels; the panels render service state and edit only their own immutable data slice.

**Tech Stack:** Java 8, Swing, JCA/JCE (PBKDF2WithHmacSHA256 and AES/GCM/NoPadding), Jackson 2.15.2, JUnit 5, Maven Surefire

---

## Scope and execution notes

This plan implements only the approved secure-vault specification. CI infrastructure, general logging, large-panel decomposition, complete i18n, and Lite/Full packaging remain separate projects. Never run migration tests against the repository-root toolbox-config.properties or toolbox-passwords.enc; every test must use a JUnit temporary directory.

## File map

### New production files

- src/main/java/com/aqishi/toolbox/vault/ApplicationPaths.java — platform-specific config, data, backup, legacy, and lock paths.
- src/main/java/com/aqishi/toolbox/vault/AtomicFiles.java — same-directory durable temporary writes and atomic replacement.
- src/main/java/com/aqishi/toolbox/util/ConfigStore.java — testable, path-aware Properties persistence that never writes totp.accounts.
- src/main/java/com/aqishi/toolbox/vault/VaultErrorCode.java — stable internal failure categories.
- src/main/java/com/aqishi/toolbox/vault/VaultException.java — checked failure with code and retryability.
- src/main/java/com/aqishi/toolbox/vault/PasswordAccount.java — password-record data bean and defensive copy.
- src/main/java/com/aqishi/toolbox/vault/TotpAccount.java — TOTP-record data bean and defensive copy.
- src/main/java/com/aqishi/toolbox/vault/VaultData.java — versioned plaintext model and scoped defensive copies.
- src/main/java/com/aqishi/toolbox/vault/VaultEnvelope.java — validated outer envelope and deterministic AAD.
- src/main/java/com/aqishi/toolbox/vault/VaultCrypto.java — PBKDF2, AES-GCM, random bytes, and array wiping.
- src/main/java/com/aqishi/toolbox/vault/VaultFileLock.java — operating-system process lock based on FileChannel.tryLock.
- src/main/java/com/aqishi/toolbox/vault/VaultRepository.java — envelope serialization, open/create/save/rekey, backups, and atomic installation.
- src/main/java/com/aqishi/toolbox/vault/LegacyPasswordReader.java — read-only AES-CBC legacy password-file decoder.
- src/main/java/com/aqishi/toolbox/vault/LegacyTotpReader.java — read-only legacy totp.accounts decoder.
- src/main/java/com/aqishi/toolbox/vault/LegacyVaultMigrator.java — probed, resumable migration transaction.
- src/main/java/com/aqishi/toolbox/vault/VaultState.java — shared lifecycle states.
- src/main/java/com/aqishi/toolbox/vault/VaultListener.java — state-only listener contract.
- src/main/java/com/aqishi/toolbox/vault/VaultClock.java — injectable time source.
- src/main/java/com/aqishi/toolbox/vault/VaultScheduler.java — injectable periodic scheduler.
- src/main/java/com/aqishi/toolbox/vault/VaultService.java — single-threaded session and mutation coordinator.
- src/main/java/com/aqishi/toolbox/vault/SecureClipboard.java — conditional delayed clipboard clearing.
- src/main/java/com/aqishi/toolbox/vault/VaultBootstrap.java — default production object graph.
- src/main/java/com/aqishi/toolbox/ui/VaultAccessPanel.java — shared setup, migration, unlock, busy, and error cards.
- src/main/java/com/aqishi/toolbox/ui/VaultSettingsDialog.java — lock-timeout and change-password UI shared by both tools.

### Modified production files

- src/main/java/com/aqishi/toolbox/util/ConfigManager.java — delegate to ConfigStore and expose remove without swallowing I/O failures.
- src/main/java/com/aqishi/toolbox/ui/MainFrame.java — own one VaultService, inject it into two panels, and close it on application shutdown.
- src/main/java/com/aqishi/toolbox/misc/AccountManagerPanel.java — remove local cryptography and file access; use PasswordAccount and VaultService.
- src/main/java/com/aqishi/toolbox/misc/TotpPanel.java — remove ConfigManager secret persistence and nested model; use TotpAccount and VaultService.
- src/main/java/com/aqishi/toolbox/util/messages.properties — shared vault labels and error text.
- src/main/java/com/aqishi/toolbox/util/messages_zh_CN.properties — Chinese vault labels and error text.
- src/main/java/com/aqishi/toolbox/util/messages_en_US.properties — English vault labels and error text.
- README.md — data location, migration, lock behavior, backup, and recovery notes.

### New and modified tests

- src/test/java/com/aqishi/toolbox/vault/ApplicationPathsTest.java
- src/test/java/com/aqishi/toolbox/util/ConfigStoreTest.java
- src/test/java/com/aqishi/toolbox/vault/VaultDataTest.java
- src/test/java/com/aqishi/toolbox/vault/VaultEnvelopeTest.java
- src/test/java/com/aqishi/toolbox/vault/VaultCryptoTest.java
- src/test/java/com/aqishi/toolbox/vault/VaultRepositoryTest.java
- src/test/java/com/aqishi/toolbox/vault/LegacyVaultMigratorTest.java
- src/test/java/com/aqishi/toolbox/vault/VaultServiceTest.java
- src/test/java/com/aqishi/toolbox/vault/SecureClipboardTest.java
- src/test/java/com/aqishi/toolbox/vault/VaultTestSupport.java
- src/test/java/com/aqishi/toolbox/misc/AccountManagerPanelVaultTest.java
- src/test/java/com/aqishi/toolbox/misc/TotpPanelVaultTest.java
- src/test/java/com/aqishi/toolbox/ui/VaultAccessPanelTest.java
- src/test/java/com/aqishi/toolbox/ui/MainFrameStructureTest.java

### Task 1: Safe application paths and non-sensitive configuration

**Files:**
- Create: src/main/java/com/aqishi/toolbox/vault/ApplicationPaths.java
- Create: src/main/java/com/aqishi/toolbox/vault/AtomicFiles.java
- Create: src/main/java/com/aqishi/toolbox/util/ConfigStore.java
- Modify: src/main/java/com/aqishi/toolbox/util/ConfigManager.java
- Test: src/test/java/com/aqishi/toolbox/vault/ApplicationPathsTest.java
- Test: src/test/java/com/aqishi/toolbox/util/ConfigStoreTest.java

- [ ] **Step 1: Write failing path-resolution tests**

Create ApplicationPathsTest with deterministic environment maps:

~~~java
package com.aqishi.toolbox.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationPathsTest {
    @TempDir Path temp;

    @Test
    void resolvesWindowsAppDataAndLegacyWorkingFiles() {
        Map<String, String> env = new HashMap<>();
        env.put("APPDATA", "C:\\Users\\dev\\AppData\\Roaming");

        ApplicationPaths paths = ApplicationPaths.resolve(
                "Windows 11", "C:\\Users\\dev", env, Paths.get("D:\\portable"));

        assertEquals(Paths.get("C:\\Users\\dev\\AppData\\Roaming\\JavaToolbox"),
                paths.getDataDirectory());
        assertEquals(Paths.get("D:\\portable\\toolbox-passwords.enc"),
                paths.getLegacyPasswordFile());
        assertEquals(Paths.get("D:\\portable\\toolbox-config.properties"),
                paths.getLegacyConfigFile());
    }

    @Test
    void resolvesLinuxXdgDirectories() {
        Map<String, String> env = new HashMap<>();
        env.put("XDG_DATA_HOME", "/tmp/data");
        env.put("XDG_CONFIG_HOME", "/tmp/config");

        ApplicationPaths paths = ApplicationPaths.resolve(
                "Linux", "/home/dev", env, Paths.get("/opt/toolbox"));

        assertEquals(Paths.get("/tmp/data/java-toolbox"), paths.getDataDirectory());
        assertEquals(Paths.get("/tmp/config/java-toolbox/toolbox-config.properties"),
                paths.getConfigFile());
        assertEquals(Paths.get("/tmp/data/java-toolbox/toolbox-vault.json.enc"),
                paths.getVaultFile());
    }

    @Test
    void createsPrivateDirectoriesWherePosixPermissionsAreSupported() throws Exception {
        ApplicationPaths paths = ApplicationPaths.resolve(
                "Linux", temp.toString(), Collections.emptyMap(), temp);
        paths.createPrivateDirectories();

        if (Files.getFileStore(paths.getDataDirectory())
                .supportsFileAttributeView("posix")) {
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(paths.getDataDirectory()));
        }
    }
}
~~~

- [ ] **Step 2: Write failing ConfigStore tests**

Create ConfigStoreTest. It must prove that legacy preferences are readable but totp.accounts is never copied into the new config:

~~~java
package com.aqishi.toolbox.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir Path temp;

    @Test
    void migratesOnlyNonSensitiveLegacyProperties() throws Exception {
        Path legacy = temp.resolve("legacy.properties");
        Path current = temp.resolve("config/toolbox-config.properties");
        Files.write(legacy, (
                "theme=Arc\\n" +
                "locale=zh_CN\\n" +
                "totp.accounts=[{secret:DO_NOT_COPY}]\\n").getBytes(StandardCharsets.ISO_8859_1));

        ConfigStore store = new ConfigStore(current, legacy, new AtomicFiles());
        store.load();

        assertEquals("Arc", store.get("theme", ""));
        assertNull(store.get("totp.accounts", null));
        store.save();

        Properties written = new Properties();
        written.load(Files.newInputStream(current));
        assertEquals("Arc", written.getProperty("theme"));
        assertFalse(written.containsKey("totp.accounts"));
        assertTrue(Files.exists(legacy), "ordinary config save must not delete migration source");
    }
}
~~~

- [ ] **Step 3: Run the two tests and confirm the red state**

Run:

~~~powershell
mvn -Dtest=ApplicationPathsTest,ConfigStoreTest test
~~~

Expected: test compilation fails because ApplicationPaths, AtomicFiles, and ConfigStore do not exist.

- [ ] **Step 4: Implement ApplicationPaths and AtomicFiles**

ApplicationPaths.resolve must use the exact fallback rules in the design and expose getConfigFile, getDataDirectory, getBackupDirectory, getVaultFile, getLockFile, getLegacyConfigFile, and getLegacyPasswordFile. createPrivateDirectories creates data/config/backup directories and, when the POSIX attribute view is available, applies rwx------. A permission-tightening failure is returned as a warning and never causes fallback to the working directory. AtomicFiles must be non-final so tests can inject failures. It creates the parent directory, writes a sibling temporary file, forces the FileChannel, then calls replace(candidate, target); replace attempts ATOMIC_MOVE before falling back to REPLACE_EXISTING:

~~~java
public void write(Path target, byte[] bytes) throws IOException {
    Files.createDirectories(target.toAbsolutePath().getParent());
    Path temporary = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
    boolean installed = false;
    try {
        try (FileChannel channel = FileChannel.open(
                temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        replace(temporary, target);
        installed = true;
    } finally {
        if (!installed) Files.deleteIfExists(temporary);
    }
}

public void replace(Path source, Path target) throws IOException {
    try {
        Files.move(source, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
~~~

- [ ] **Step 5: Implement ConfigStore and convert ConfigManager into a facade**

ConfigStore.load chooses the new config when present, otherwise reads the legacy file; it immediately removes totp.accounts from the in-memory Properties. save delegates to AtomicFiles and throws IOException. ConfigManager retains existing get, getInt, set, and setInt signatures, adds remove(String), and records the last save failure so callers can show it instead of silently discarding it:

~~~java
public static synchronized boolean save() {
    try {
        store.save();
        lastSaveError = null;
        return true;
    } catch (IOException error) {
        lastSaveError = error;
        return false;
    }
}

public static synchronized void remove(String key) {
    store.remove(key);
}

public static synchronized IOException getLastSaveError() {
    return lastSaveError;
}
~~~

Update all existing callers only as required by the boolean return type; source-compatible calls that ignore the return value may remain until their later refactor.

- [ ] **Step 6: Run focused and existing configuration tests**

Run:

~~~powershell
mvn -Dtest=ApplicationPathsTest,ConfigStoreTest,ToolNavigationStateTest test
~~~

Expected: all selected tests pass and no repository-root config file is modified.

- [ ] **Step 7: Commit the filesystem foundation**

~~~powershell
git add src/main/java/com/aqishi/toolbox/vault/ApplicationPaths.java src/main/java/com/aqishi/toolbox/vault/AtomicFiles.java src/main/java/com/aqishi/toolbox/util/ConfigStore.java src/main/java/com/aqishi/toolbox/util/ConfigManager.java src/test/java/com/aqishi/toolbox/vault/ApplicationPathsTest.java src/test/java/com/aqishi/toolbox/util/ConfigStoreTest.java
git commit -m "refactor(config): move settings to safe user paths"
~~~

### Task 2: Versioned vault data contract and validation

**Files:**
- Create: src/main/java/com/aqishi/toolbox/vault/VaultErrorCode.java
- Create: src/main/java/com/aqishi/toolbox/vault/VaultException.java
- Create: src/main/java/com/aqishi/toolbox/vault/PasswordAccount.java
- Create: src/main/java/com/aqishi/toolbox/vault/TotpAccount.java
- Create: src/main/java/com/aqishi/toolbox/vault/VaultData.java
- Create: src/main/java/com/aqishi/toolbox/vault/VaultEnvelope.java
- Test: src/test/java/com/aqishi/toolbox/vault/VaultDataTest.java
- Test: src/test/java/com/aqishi/toolbox/vault/VaultEnvelopeTest.java

- [ ] **Step 1: Write failing copy and envelope-validation tests**

The tests must assert that panel-facing lists are defensive and that unsafe KDF/file metadata is rejected before cryptography:

~~~java
@Test
void returnsScopedDefensiveCopies() {
    PasswordAccount account = new PasswordAccount("GitHub", "dev", "secret", "https://github.com");
    VaultData data = new VaultData();
    data.setPasswordAccounts(Collections.singletonList(account));

    List<PasswordAccount> copy = data.copyPasswordAccounts();
    copy.get(0).setPassword("changed");

    assertEquals("secret", data.copyPasswordAccounts().get(0).getPassword());
    assertTrue(data.copyTotpAccounts().isEmpty());
}

@Test
void rejectsUnsafeEnvelopeParameters() {
    VaultEnvelope envelope = VaultEnvelope.newEnvelope(
            99_999, new byte[16], new byte[12], new byte[32]);
    VaultException error = assertThrows(VaultException.class, envelope::validate);
    assertEquals(VaultErrorCode.INVALID_ENVELOPE, error.getCode());
}
~~~

- [ ] **Step 2: Run tests and verify failure**

Run:

~~~powershell
mvn -Dtest=VaultDataTest,VaultEnvelopeTest test
~~~

Expected: compilation fails because the vault model classes do not exist.

- [ ] **Step 3: Implement error types and data beans**

VaultErrorCode must contain AUTHENTICATION_FAILED, UNSUPPORTED_FORMAT, INVALID_ENVELOPE, FILE_TOO_LARGE, READ_FAILED, WRITE_FAILED, READ_ONLY, MIGRATION_FAILED, and BUSY. VaultException stores the code and a boolean retryable.

PasswordAccount fields are name, username, password, and url. TotpAccount fields are id, label, secret, issuer, algorithm, digits, period, and showDirectly. Each bean provides a public no-arg constructor for Jackson, full getters/setters, a value constructor, and copy().

VaultData has schemaVersion = 1, private lists initialized empty, Jackson getters/setters that copy their input, copyPasswordAccounts(), copyTotpAccounts(), copy(), and validate(). It never returns its internal list. validate rejects more than 10,000 records in either collection, names/IDs/labels/usernames/URLs/issuers longer than 4,096 characters, TOTP secrets longer than 4,096 characters, and stored account passwords longer than 1 MiB. Null strings normalize to empty strings only where the legacy UI already allowed empty values.

- [ ] **Step 4: Implement VaultEnvelope with deterministic AAD**

Use the constants from the approved spec:

~~~java
public static final String FORMAT = "java-toolbox-vault";
public static final int FORMAT_VERSION = 1;
public static final String KDF = "PBKDF2WithHmacSHA256";
public static final String CIPHER = "AES/GCM/NoPadding";
public static final int MIN_ITERATIONS = 100_000;
public static final int MAX_ITERATIONS = 5_000_000;
public static final int NEW_FILE_ITERATIONS = 600_000;
public static final int SALT_BYTES = 16;
public static final int NONCE_BYTES = 12;
public static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;
~~~

aad() must return UTF-8 bytes of one fixed, delimiter-safe sequence:

~~~java
String value = format + "|" + formatVersion + "|" + kdfAlgorithm + "|" +
        iterations + "|" + salt + "|" + cipherAlgorithm + "|" + nonce;
return value.getBytes(StandardCharsets.UTF_8);
~~~

validate checks exact format/version/algorithm names, iteration range, decoded salt/nonce lengths, and non-empty ciphertext. It throws VaultException with UNSUPPORTED_FORMAT only for a recognized envelope with a newer version; malformed fields use INVALID_ENVELOPE.

- [ ] **Step 5: Run model tests**

Run:

~~~powershell
mvn -Dtest=VaultDataTest,VaultEnvelopeTest test
~~~

Expected: all tests pass.

- [ ] **Step 6: Commit the data contract**

~~~powershell
git add src/main/java/com/aqishi/toolbox/vault src/test/java/com/aqishi/toolbox/vault/VaultDataTest.java src/test/java/com/aqishi/toolbox/vault/VaultEnvelopeTest.java
git commit -m "feat(vault): define versioned vault data contract"
~~~

### Task 3: PBKDF2 and authenticated encryption

**Files:**
- Create: src/main/java/com/aqishi/toolbox/vault/VaultCrypto.java
- Test: src/test/java/com/aqishi/toolbox/vault/VaultCryptoTest.java

- [ ] **Step 1: Write failing cryptographic tests**

Include the RFC-compatible PBKDF2-HMAC-SHA256 vector and tamper checks:

~~~java
@Test
void derivesKnownPbkdf2HmacSha256Vector() throws Exception {
    byte[] key = crypto.deriveKey("password".toCharArray(),
            "salt".getBytes(StandardCharsets.US_ASCII), 1);
    assertEquals("120fb6cffcf8b32c43e7225256c4f837", hex(key));
}

@Test
void detectsCiphertextAndAadTampering() throws Exception {
    byte[] key = new byte[16];
    byte[] nonce = new byte[12];
    byte[] aad = "header".getBytes(StandardCharsets.UTF_8);
    byte[] ciphertext = crypto.encrypt("payload".getBytes(StandardCharsets.UTF_8), key, nonce, aad);

    ciphertext[0] ^= 1;
    assertThrows(VaultException.class, () -> crypto.decrypt(ciphertext, key, nonce, aad));

    byte[] clean = crypto.encrypt("payload".getBytes(StandardCharsets.UTF_8), key, nonce, aad);
    assertThrows(VaultException.class, () -> crypto.decrypt(
            clean, key, nonce, "changed".getBytes(StandardCharsets.UTF_8)));
}
~~~

Also test randomBytes returns requested lengths, two calls differ, empty passwords are rejected by the service rather than crypto, and wipe fills char[] and byte[] with zeroes.

- [ ] **Step 2: Run test and verify failure**

Run:

~~~powershell
mvn -Dtest=VaultCryptoTest test
~~~

Expected: compilation fails because VaultCrypto does not exist.

- [ ] **Step 3: Implement VaultCrypto**

Use only provider-neutral Java 8 APIs:

~~~java
public byte[] deriveKey(char[] password, byte[] salt, int iterations)
        throws VaultException {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 128);
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException error) {
        throw new VaultException(VaultErrorCode.UNSUPPORTED_FORMAT,
                "PBKDF2WithHmacSHA256 is unavailable", false, error);
    } finally {
        spec.clearPassword();
    }
}

public byte[] encrypt(byte[] plaintext, byte[] key, byte[] nonce, byte[] aad)
        throws VaultException {
    try {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    } catch (GeneralSecurityException error) {
        throw new VaultException(VaultErrorCode.WRITE_FAILED,
                "Unable to encrypt vault", true, error);
    }
}
~~~

decrypt mirrors encrypt and maps AEADBadTagException to AUTHENTICATION_FAILED without exposing whether the password or file was wrong. randomBytes uses one SecureRandom instance. Add static wipe overloads for byte[] and char[].

- [ ] **Step 4: Run cryptographic tests twice**

Run:

~~~powershell
mvn -Dtest=VaultCryptoTest test
mvn -Dtest=VaultCryptoTest test
~~~

Expected: both runs pass; the nondeterministic nonce test is stable.

- [ ] **Step 5: Commit authenticated cryptography**

~~~powershell
git add src/main/java/com/aqishi/toolbox/vault/VaultCrypto.java src/test/java/com/aqishi/toolbox/vault/VaultCryptoTest.java
git commit -m "feat(vault): add PBKDF2 AES-GCM encryption"
~~~

### Task 4: Transactional repository and process lock

**Files:**
- Create: src/main/java/com/aqishi/toolbox/vault/VaultFileLock.java
- Create: src/main/java/com/aqishi/toolbox/vault/VaultRepository.java
- Create: src/test/java/com/aqishi/toolbox/vault/VaultTestSupport.java
- Test: src/test/java/com/aqishi/toolbox/vault/VaultRepositoryTest.java

- [ ] **Step 1: Write failing repository tests**

VaultTestSupport creates ApplicationPaths rooted inside @TempDir, sample VaultData, and a helper that reads an envelope without printing ciphertext.

Repository tests must cover:

~~~java
@Test
void createsOpensAndSavesWithFreshNonce() throws Exception {
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

@Test
void wrongPasswordAndModifiedEnvelopeNeverOverwriteFile() throws Exception {
    support.repository().create(support.sampleData(), "right".toCharArray()).close();
    byte[] original = Files.readAllBytes(support.paths().getVaultFile());

    assertThrows(VaultException.class,
            () -> support.repository().open("wrong".toCharArray()));
    assertArrayEquals(original, Files.readAllBytes(support.paths().getVaultFile()));
}
~~~

Add tests for file-size rejection before JSON parsing, backup creation, a failing AtomicFiles test double preserving the original file, unsupported atomic move fallback, and two VaultFileLock instances making the second read-only.

- [ ] **Step 2: Run repository tests and verify failure**

Run:

~~~powershell
mvn -Dtest=VaultRepositoryTest test
~~~

Expected: compilation fails because VaultRepository and VaultFileLock do not exist.

- [ ] **Step 3: Implement VaultFileLock**

open the lock file with CREATE and WRITE, call tryLock(), handle OverlappingFileLockException as unavailable, and expose isWritable(). close releases FileLock and FileChannel. A stale unlocked file must be reusable.

- [ ] **Step 4: Implement VaultRepository.OpenedVault**

OpenedVault stores a defensive VaultData copy, the current validated VaultEnvelope, and the 16-byte derived key. getData returns a copy. replaceAfterSave updates the internal envelope/data only after a successful installation. close wipes the key and clears data references.

- [ ] **Step 5: Implement repository create/open/save/rekey**

Use ObjectMapper for VaultData and VaultEnvelope. open performs the checks in this order: Files.size limit, JSON parse, envelope.validate, Base64 decode, derive key, AES-GCM decrypt, VaultData schema validation. create refuses to overwrite an existing vault. save requires a writable VaultFileLock, generates a new nonce, writes through AtomicFiles, reopens and decrypts the installed file, then updates OpenedVault. rekey creates a new salt and key but does not close the old session until the new file passes validation.

The installation helper must preserve the previous valid envelope:

~~~java
private void installVerified(byte[] encoded, char[] verificationPassword)
        throws VaultException {
    Path target = paths.getVaultFile();
    Path candidate = target.resolveSibling(target.getFileName() + ".candidate");
    try {
        atomicFiles.write(candidate, encoded);
        openFile(candidate, verificationPassword.clone()).close();
        if (Files.exists(target)) backupCurrent(target);
        atomicFiles.replace(candidate, target);
        openFile(target, verificationPassword.clone()).close();
    } catch (IOException error) {
        throw new VaultException(VaultErrorCode.WRITE_FAILED,
                "Unable to install verified vault", true, error);
    } finally {
        VaultCrypto.wipe(verificationPassword);
        deleteQuietly(candidate);
    }
}
~~~

For save with an already-derived key, add a verifyWithKey helper instead of retaining or reconstructing the original password.

- [ ] **Step 6: Run repository and crypto tests**

Run:

~~~powershell
mvn -Dtest=VaultCryptoTest,VaultRepositoryTest test
~~~

Expected: all selected tests pass, including the failed-write preservation test.

- [ ] **Step 7: Commit the repository**

~~~powershell
git add src/main/java/com/aqishi/toolbox/vault/VaultFileLock.java src/main/java/com/aqishi/toolbox/vault/VaultRepository.java src/test/java/com/aqishi/toolbox/vault/VaultRepositoryTest.java src/test/java/com/aqishi/toolbox/vault/VaultTestSupport.java
git commit -m "feat(vault): persist vault with verified atomic writes"
~~~

### Task 5: Read-only legacy decoding and resumable migration

**Files:**
- Create: src/main/java/com/aqishi/toolbox/vault/LegacyPasswordReader.java
- Create: src/main/java/com/aqishi/toolbox/vault/LegacyTotpReader.java
- Create: src/main/java/com/aqishi/toolbox/vault/LegacyVaultMigrator.java
- Test: src/test/java/com/aqishi/toolbox/vault/LegacyVaultMigratorTest.java

- [ ] **Step 1: Write failing migration scenario tests**

Generate legacy fixtures inside @TempDir. The old password helper must reproduce the current SHA-256-first-16-bytes plus AES/CBC/PKCS5Padding format. Cover NONE, PASSWORD_ONLY, TOTP_ONLY, and BOTH:

~~~java
@Test
void migratesBothSourcesAndRemovesPlaintextOnlyAfterVerification() throws Exception {
    support.writeLegacyPasswordFile("old-master",
            Collections.singletonList(new PasswordAccount("GitHub", "dev", "pw", "url")));
    support.writeLegacyConfig(
            "theme=Arc\\n" +
            "totp.accounts=[{\\"id\\":\\"1\\",\\"label\\":\\"Mail\\"," +
            "\\"secret\\":\\"JBSWY3DPEHPK3PXP\\",\\"algorithm\\":\\"SHA1\\"," +
            "\\"digits\\":6,\\"period\\":30,\\"showDirectly\\":true}]\\n");

    migrator.migrate("old-master".toCharArray());

    VaultData data = repository.open("old-master".toCharArray()).getData();
    assertEquals(1, data.copyPasswordAccounts().size());
    assertEquals(1, data.copyTotpAccounts().size());
    assertFalse(Files.exists(support.paths().getLegacyConfigFile()));
    assertFalse(Files.exists(support.paths().getLegacyPasswordFile()));
    assertFalse(new String(Files.readAllBytes(support.paths().getConfigFile()),
            StandardCharsets.ISO_8859_1).contains("totp.accounts"));
}
~~~

Add injected failures at backup write, vault install, sanitized-config write, and legacy delete. Assert sources remain through config verification; when vault installation succeeded but config migration failed, retry finalizes configuration without importing duplicate records.

- [ ] **Step 2: Run migration tests and verify failure**

Run:

~~~powershell
mvn -Dtest=LegacyVaultMigratorTest test
~~~

Expected: compilation fails because legacy readers and migrator do not exist.

- [ ] **Step 3: Implement LegacyPasswordReader**

Keep the old algorithm private to this read-only class:

~~~java
ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
byte[] passwordBytes = new byte[encoded.remaining()];
encoded.get(passwordBytes);
byte[] hash = MessageDigest.getInstance("SHA-256").digest(passwordBytes);
byte[] key = Arrays.copyOf(hash, 16);
byte[] bytes = Files.readAllBytes(path);
byte[] iv = Arrays.copyOfRange(bytes, 0, 16);
Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
        new IvParameterSpec(iv));
byte[] plaintext = cipher.doFinal(Arrays.copyOfRange(bytes, 16, bytes.length));
~~~

Parse magic = TOOLBOX_PWD_MGR and map name, username, password, and url in source order. Wipe password, passwordBytes, hash, key, and plaintext in finally. This class must not expose an encrypt method in production.

- [ ] **Step 4: Implement LegacyTotpReader**

Load the legacy Properties file directly from ApplicationPaths.getLegacyConfigFile, parse only totp.accounts with ObjectMapper, map all eight existing fields, and provide sanitizedProperties() that removes totp.accounts while preserving all other entries.

- [ ] **Step 5: Implement LegacyVaultMigrator**

Expose MigrationMode NONE, PASSWORD_ONLY, TOTP_ONLY, BOTH, and CLEANUP_REQUIRED. migrate is synchronous, takes ownership of its char[], wipes it in finally, and is always called by VaultService on the service executor. The exact transaction is:

1. Probe without writing.
2. Decode supported old sources.
3. Encrypt opaque source bytes into separately salted backup envelopes under backupDirectory.
4. Reopen both backups with the supplied master password.
5. Create and reopen the authoritative vault.
6. Write and reread sanitized ConfigStore data.
7. Delete legacy sources.
8. Return the opened authoritative vault plus warnings for any cleanup deletion failure.

If step 5 succeeds and step 6 fails, a retry sees the vault, enters CLEANUP_REQUIRED, verifies the vault contains the migrated collections, then resumes at step 6 without merging source records again.

- [ ] **Step 6: Run migration and repository tests**

Run:

~~~powershell
mvn -Dtest=LegacyVaultMigratorTest,VaultRepositoryTest test
~~~

Expected: all selected tests pass and failure injection never destroys the last readable source.

- [ ] **Step 7: Commit migration**

~~~powershell
git add src/main/java/com/aqishi/toolbox/vault/LegacyPasswordReader.java src/main/java/com/aqishi/toolbox/vault/LegacyTotpReader.java src/main/java/com/aqishi/toolbox/vault/LegacyVaultMigrator.java src/test/java/com/aqishi/toolbox/vault/LegacyVaultMigratorTest.java
git commit -m "feat(vault): migrate legacy passwords and TOTP safely"
~~~

### Task 6: Shared vault session, scoped data, and automatic lock

**Files:**
- Create: src/main/java/com/aqishi/toolbox/vault/VaultState.java
- Create: src/main/java/com/aqishi/toolbox/vault/VaultListener.java
- Create: src/main/java/com/aqishi/toolbox/vault/VaultClock.java
- Create: src/main/java/com/aqishi/toolbox/vault/VaultScheduler.java
- Create: src/main/java/com/aqishi/toolbox/vault/VaultService.java
- Test: src/test/java/com/aqishi/toolbox/vault/VaultServiceTest.java

- [ ] **Step 1: Write failing service tests**

Use a direct Executor, fake clock, and scheduler that captures its periodic Runnable:

~~~java
@Test
void unlockNotifiesBothListenersButScopesDataAccess() throws Exception {
    repository.create(support.sampleData(), "master".toCharArray()).close();
    List<VaultState> first = new ArrayList<>();
    List<VaultState> second = new ArrayList<>();
    service.addListener(first::add);
    service.addListener(second::add);

    service.unlock("master".toCharArray()).get();

    assertEquals(VaultState.UNLOCKED, service.getState());
    assertEquals(first, second);
    assertEquals(1, service.getPasswordAccounts().size());
    assertEquals(1, service.getTotpAccounts().size());
    assertThrows(UnsupportedOperationException.class,
            () -> service.getPasswordAccounts().add(new PasswordAccount()));
}

@Test
void locksAfterConfiguredIdleTime() throws Exception {
    service.unlock("master".toCharArray()).get();
    clock.advanceMinutes(4);
    scheduler.tick();
    assertEquals(VaultState.UNLOCKED, service.getState());

    clock.advanceMinutes(1);
    scheduler.tick();
    assertEquals(VaultState.LOCKED, service.getState());
    assertTrue(service.getPasswordAccounts().isEmpty());
}
~~~

Also test create rejects empty password, manual lock, 1/5/10/30-minute validation, touch resets time, TOTP timer activity does not call touch, save rollback on repository failure, serialized duplicate operations return BUSY, rekey success/failure, close waits for an in-flight save, and close wipes the session.

- [ ] **Step 2: Run service tests and verify failure**

Run:

~~~powershell
mvn -Dtest=VaultServiceTest test
~~~

Expected: compilation fails because the service lifecycle classes do not exist.

- [ ] **Step 3: Implement lifecycle contracts**

VaultState values are LOCKED, MIGRATION_REQUIRED, UNLOCKING, UNLOCKED, SAVING, and ERROR_READ_ONLY. VaultListener has onStateChanged(VaultState). VaultClock.system uses System.currentTimeMillis. VaultScheduler exposes schedule and scheduleAtFixedRate; production wraps one daemon ScheduledExecutorService, while tests capture tasks deterministically.

- [ ] **Step 4: Implement VaultService**

Constructor dependencies are VaultRepository, LegacyVaultMigrator, one background Executor, one event-dispatch Executor, VaultClock, VaultScheduler, and initial timeout minutes. Public methods are:

~~~java
CompletableFuture<Void> create(char[] password);
CompletableFuture<Void> unlock(char[] password);
CompletableFuture<Void> migrate(char[] password);
CompletableFuture<Void> replacePasswordAccounts(List<PasswordAccount> accounts);
CompletableFuture<Void> replaceTotpAccounts(List<TotpAccount> accounts);
CompletableFuture<Void> changePassword(char[] currentPassword, char[] newPassword);
List<PasswordAccount> getPasswordAccounts();
List<TotpAccount> getTotpAccounts();
VaultState getState();
LegacyVaultMigrator.MigrationMode getMigrationMode();
boolean isInitialized();
void addListener(VaultListener listener);
void removeListener(VaultListener listener);
void setIdleMinutes(int minutes);
void touch();
void lock();
void close();
~~~

Every char[] method takes ownership and wipes it in finally. Mutations copy the last persisted VaultData, save the copy, then publish it only after repository success. On failure the old snapshot remains. Listeners receive only state; production event dispatch uses SwingUtilities.invokeLater and tests use Runnable::run.

Lock closes OpenedVault, clears the snapshot, updates LOCKED, and notifies once. State transitions are guarded by one lock; create/unlock/migrate/save/rekey reject a second active operation with VaultErrorCode.BUSY. close is idempotent, rejects new work, waits up to five seconds for an in-flight repository transaction, then closes the opened vault, scheduler, executor, repository, and file lock; an interrupted or timed-out close preserves the last atomically installed file.

- [ ] **Step 5: Run service, migration, and repository tests**

Run:

~~~powershell
mvn -Dtest=VaultServiceTest,LegacyVaultMigratorTest,VaultRepositoryTest test
~~~

Expected: all selected tests pass.

- [ ] **Step 6: Commit the shared session**

~~~powershell
git add src/main/java/com/aqishi/toolbox/vault/VaultState.java src/main/java/com/aqishi/toolbox/vault/VaultListener.java src/main/java/com/aqishi/toolbox/vault/VaultClock.java src/main/java/com/aqishi/toolbox/vault/VaultScheduler.java src/main/java/com/aqishi/toolbox/vault/VaultService.java src/test/java/com/aqishi/toolbox/vault/VaultServiceTest.java
git commit -m "feat(vault): share unlock and automatic lock session"
~~~

### Task 7: Conditional secure clipboard clearing

**Files:**
- Create: src/main/java/com/aqishi/toolbox/vault/SecureClipboard.java
- Test: src/test/java/com/aqishi/toolbox/vault/SecureClipboardTest.java

- [ ] **Step 1: Write failing clipboard tests**

Use an in-memory ClipboardGateway and fake scheduler:

~~~java
@Test
void clearsOnlyWhenClipboardStillContainsSensitiveValue() {
    clipboard.copySensitive("secret");
    scheduler.advanceSeconds(30);
    assertEquals("", gateway.readText());
}

@Test
void preservesContentCopiedByTheUserLater() {
    clipboard.copySensitive("secret");
    gateway.writeText("user replacement");
    scheduler.advanceSeconds(30);
    assertEquals("user replacement", gateway.readText());
}
~~~

- [ ] **Step 2: Run test and verify failure**

Run:

~~~powershell
mvn -Dtest=SecureClipboardTest test
~~~

Expected: compilation fails because SecureClipboard does not exist.

- [ ] **Step 3: Implement SecureClipboard**

Define a package-private ClipboardGateway with readText, writeText, and clear. Production uses Toolkit.getDefaultToolkit().getSystemClipboard(). Store only a SHA-256 digest of the text for the delayed comparison:

~~~java
public void copySensitive(String value) throws VaultException {
    gateway.writeText(value);
    byte[] expected = digest(value);
    scheduler.schedule(() -> {
        try {
            String current = gateway.readText();
            if (MessageDigest.isEqual(expected, digest(current))) gateway.clear();
        } finally {
            VaultCrypto.wipe(expected);
        }
    }, 30, TimeUnit.SECONDS);
}
~~~

Map clipboard-busy failures to a retryable VaultException without logging value or digest.

- [ ] **Step 4: Run clipboard tests and commit**

Run:

~~~powershell
mvn -Dtest=SecureClipboardTest test
~~~

Expected: both tests pass.

Commit:

~~~powershell
git add src/main/java/com/aqishi/toolbox/vault/SecureClipboard.java src/test/java/com/aqishi/toolbox/vault/SecureClipboardTest.java
git commit -m "feat(vault): clear sensitive clipboard content safely"
~~~

### Task 8: Shared access and settings UI

**Files:**
- Create: src/main/java/com/aqishi/toolbox/ui/VaultAccessPanel.java
- Create: src/main/java/com/aqishi/toolbox/ui/VaultSettingsDialog.java
- Modify: src/main/java/com/aqishi/toolbox/util/messages.properties
- Modify: src/main/java/com/aqishi/toolbox/util/messages_zh_CN.properties
- Modify: src/main/java/com/aqishi/toolbox/util/messages_en_US.properties
- Test: src/test/java/com/aqishi/toolbox/ui/VaultAccessPanelTest.java

- [ ] **Step 1: Write failing Swing state-card tests**

Create a real VaultService backed by @TempDir and run component operations on the EDT:

~~~java
@Test
void followsSharedServiceStateWithoutReceivingSecrets() throws Exception {
    VaultAccessPanel[] panel = new VaultAccessPanel[1];
    SwingUtilities.invokeAndWait(() ->
            panel[0] = new VaultAccessPanel(service, new JPanel()));

    assertFalse(service.isInitialized());
    assertEquals("SETUP", panel[0].getVisibleCardName());
    service.create("master".toCharArray()).get();
    SwingUtilities.invokeAndWait(() -> { });
    assertEquals("CONTENT", panel[0].getVisibleCardName());

    service.lock();
    SwingUtilities.invokeAndWait(() -> { });
    assertEquals("UNLOCK", panel[0].getVisibleCardName());
}
~~~

Add tests for MIGRATION_REQUIRED, busy button disabling, ERROR_READ_ONLY, mismatched setup confirmation, allowed timeout values, and listener removal on dispose.

- [ ] **Step 2: Run test and verify failure**

Run:

~~~powershell
mvn -Dtest=VaultAccessPanelTest test
~~~

Expected: compilation fails because VaultAccessPanel and VaultSettingsDialog do not exist.

- [ ] **Step 3: Implement VaultAccessPanel**

Use CardLayout names SETUP, MIGRATE, UNLOCK, BUSY, CONTENT, and ERROR. LOCKED plus !service.isInitialized() selects SETUP; LOCKED plus an initialized vault selects UNLOCK. Password actions always obtain char[], clone only when confirmation is required, compare with Arrays.equals, wipe both arrays, and invoke VaultService asynchronously:

~~~java
private void unlock() {
    char[] password = unlockField.getPassword();
    showCard(BUSY);
    service.unlock(password).whenComplete((ignored, error) ->
            SwingUtilities.invokeLater(() -> {
                unlockField.setText("");
                if (error != null) showSafeError(error);
            }));
}
~~~

Expose getVisibleCardName with package-private visibility for the Swing test. No method exposes password fields or VaultData.

- [ ] **Step 4: Implement VaultSettingsDialog**

The timeout combo contains exactly 1, 5, 10, and 30 minutes. Saving calls service.setIdleMinutes and ConfigManager.setInt("vault.idleMinutes", value), then checks ConfigManager.save(). Change-password fields use char[], call service.changePassword, wipe arrays, disable submit while pending, and never store current password in a field.

- [ ] **Step 5: Add all new labels to the three message bundles**

Add stable keys for vault setup, migration, unlock, busy, lock, timeout, change password, generic authentication failure, read-only error, cleanup warning, and clipboard warning. English and Chinese bundles must have identical key sets.

- [ ] **Step 6: Run Swing and bundle tests**

Run:

~~~powershell
mvn -Dtest=VaultAccessPanelTest,MainFrameStructureTest test
~~~

Expected: selected tests pass with no EDT exceptions.

- [ ] **Step 7: Commit shared access UI**

~~~powershell
git add src/main/java/com/aqishi/toolbox/ui/VaultAccessPanel.java src/main/java/com/aqishi/toolbox/ui/VaultSettingsDialog.java src/main/java/com/aqishi/toolbox/util/messages.properties src/main/java/com/aqishi/toolbox/util/messages_zh_CN.properties src/main/java/com/aqishi/toolbox/util/messages_en_US.properties src/test/java/com/aqishi/toolbox/ui/VaultAccessPanelTest.java
git commit -m "feat(ui): add shared vault access states"
~~~

### Task 9: Move the password manager onto VaultService

**Files:**
- Modify: src/main/java/com/aqishi/toolbox/misc/AccountManagerPanel.java
- Test: src/test/java/com/aqishi/toolbox/misc/AccountManagerPanelVaultTest.java

- [ ] **Step 1: Write failing password-panel tests**

Test construction is lazy, locked content is hidden, unlocked records appear, and saves go through the service:

~~~java
@Test
void usesSharedVaultAndNeverCreatesLegacyPasswordFile() throws Exception {
    service.create("master".toCharArray()).get();
    AccountManagerPanel panel = new AccountManagerPanel(service, clipboard);
    SwingUtilities.invokeAndWait(panel::getView);

    assertNotNull(findTable(panel.getView()));
    assertFalse(Files.exists(paths.getLegacyPasswordFile()));

    service.lock();
    SwingUtilities.invokeAndWait(() -> { });
    assertNull(findVisibleTable(panel.getView()));
}
~~~

Add a test that copying a password calls the injected SecureClipboard rather than UIUtils.copyToClipboard, and a simulated save failure leaves the previously persisted row visible.

- [ ] **Step 2: Run the test and verify failure**

Run:

~~~powershell
mvn -Dtest=AccountManagerPanelVaultTest test
~~~

Expected: compilation fails because AccountManagerPanel lacks injected dependencies.

- [ ] **Step 3: Replace panel-owned crypto and persistence**

Delete DATA_FILE_NAME, MAGIC_HEADER, ObjectMapper, aesKey, masterPassword, accountsNode, deriveKey, encryptAES, decryptAES, saveDataFile, handleSetup, handleUnlock, and checkStateAndSwitch. Add:

~~~java
private final VaultService vaultService;
private final SecureClipboard clipboard;
private final List<PasswordAccount> accounts = new ArrayList<>();

public AccountManagerPanel(VaultService vaultService, SecureClipboard clipboard) {
    super("crypto", "account.manager",
            "密码管理", "账号密码", "密码簿", "Password Manager", "Account", "Keeper");
    this.vaultService = Objects.requireNonNull(vaultService, "vaultService");
    this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
}
~~~

build constructs the existing table/content UI, wraps it in VaultAccessPanel, and registers a state listener that refreshes accounts only on UNLOCKED. handleLock delegates to vaultService.lock. Change-password opens VaultSettingsDialog.

- [ ] **Step 4: Convert CRUD to typed defensive lists**

Read rows from vaultService.getPasswordAccounts(). For add/edit/delete, create a new ArrayList from the service list, modify the copy, and call replacePasswordAccounts. Refresh only after the returned future succeeds; on failure show the safe error and reload the last persisted service list. Call vaultService.touch() on table selection, search/filter input, opening an edit dialog, copying, visiting a URL, and successful record mutation.

Replace copyField with:

~~~java
private void copyField(int column) {
    int row = getSelectedModelIndex();
    if (row < 0) return;
    PasswordAccount account = accounts.get(row);
    String value = column == 1 ? account.getUsername()
            : column == 2 ? account.getPassword() : account.getUrl();
    try {
        clipboard.copySensitive(value);
        vaultService.touch();
    } catch (VaultException error) {
        UIUtils.error(container, I18n.get("vault.clipboard.error"));
    }
}
~~~

- [ ] **Step 5: Run password-panel and service tests**

Run:

~~~powershell
mvn -Dtest=AccountManagerPanelVaultTest,VaultServiceTest test
~~~

Expected: all selected tests pass; no code in AccountManagerPanel imports javax.crypto, MessageDigest, SecretKeySpec, IvParameterSpec, or java.io.File.

- [ ] **Step 6: Commit password-manager integration**

~~~powershell
git add src/main/java/com/aqishi/toolbox/misc/AccountManagerPanel.java src/test/java/com/aqishi/toolbox/misc/AccountManagerPanelVaultTest.java
git commit -m "refactor(vault): secure password manager storage"
~~~

### Task 10: Move TOTP onto VaultService

**Files:**
- Modify: src/main/java/com/aqishi/toolbox/misc/TotpPanel.java
- Test: src/test/java/com/aqishi/toolbox/misc/TotpPanelVaultTest.java

- [ ] **Step 1: Write failing TOTP-panel tests**

Tests must prove TOTP is hidden while locked, available after shared unlock, persists via VaultService, and never writes totp.accounts:

~~~java
@Test
void persistsTotpOnlyInsideVault() throws Exception {
    service.create("master".toCharArray()).get();
    service.replaceTotpAccounts(Collections.singletonList(
            new TotpAccount("1", "Mail", "JBSWY3DPEHPK3PXP",
                    "Example", "SHA1", 6, 30, true))).get();

    TotpPanel panel = new TotpPanel(service, clipboard);
    SwingUtilities.invokeAndWait(panel::getView);

    assertTrue(findLabels(panel.getView()).contains("Mail"));
    assertFalse(Files.exists(paths.getLegacyConfigFile()));
    assertFalse(new String(Files.readAllBytes(paths.getVaultFile()),
            StandardCharsets.UTF_8).contains("JBSWY3DPEHPK3PXP"));
}
~~~

Add tests for shared lock, import/edit/delete rollback on save failure, direct-display preference remaining inside TotpAccount, and clipboard copy using SecureClipboard.

- [ ] **Step 2: Run test and verify failure**

Run:

~~~powershell
mvn -Dtest=TotpPanelVaultTest test
~~~

Expected: compilation fails because TotpPanel lacks injected dependencies and still owns a nested TotpAccount.

- [ ] **Step 3: Replace config secret persistence**

Delete ObjectMapper, loadConfig, saveAccounts, and the nested TotpAccount class. Import com.aqishi.toolbox.vault.TotpAccount and add:

~~~java
private final VaultService vaultService;
private final SecureClipboard clipboard;
private final List<TotpAccount> accounts = new ArrayList<>();

public TotpPanel(VaultService vaultService, SecureClipboard clipboard) {
    super("crypto", "totp.authenticator",
            "谷歌验证器", "Google Authenticator", "2FA", "OTP", "MFA",
            "双因素认证", "身份验证", "totp", "authenticator");
    this.vaultService = Objects.requireNonNull(vaultService, "vaultService");
    this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
}
~~~

build wraps the existing content in VaultAccessPanel. A state listener copies vaultService.getTotpAccounts only on UNLOCKED, stops or masks the refresh timer when locked, and does not treat timer ticks as service.touch.

- [ ] **Step 4: Route all mutations and copy through services**

For add/import/edit/delete, mutate a defensive list and call replaceTotpAccounts; refresh after success and roll back after failure. The global show toggle remains a non-sensitive ConfigManager preference, while each account showDirectly stays in the encrypted model. Call vaultService.touch() for explicit reveal, add/import/edit/delete, account selection, and copy; the 100 ms TOTP refresh timer never calls touch. copyCodeToClipboard calls SecureClipboard and touch.

- [ ] **Step 5: Run TOTP, password, and OTP utility tests**

Run:

~~~powershell
mvn -Dtest=TotpPanelVaultTest,AccountManagerPanelVaultTest,OtpUtilsTest test
~~~

Expected: all selected tests pass; rg -n "totp\\.accounts" src/main/java returns matches only in migration/config safety code, never TotpPanel.

- [ ] **Step 6: Commit TOTP integration**

~~~powershell
git add src/main/java/com/aqishi/toolbox/misc/TotpPanel.java src/test/java/com/aqishi/toolbox/misc/TotpPanelVaultTest.java
git commit -m "refactor(vault): encrypt TOTP account storage"
~~~

### Task 11: Build the production object graph and shutdown lifecycle

**Files:**
- Create: src/main/java/com/aqishi/toolbox/vault/VaultBootstrap.java
- Modify: src/main/java/com/aqishi/toolbox/ui/MainFrame.java
- Modify: src/test/java/com/aqishi/toolbox/ui/MainFrameStructureTest.java

- [ ] **Step 1: Write the failing composition test**

MainFrameStructureTest must create a temporary VaultService and pass it to a package-private constructor:

~~~java
@Test
void usesTheInjectedVaultServiceInsteadOfRealUserData() throws Exception {
    VaultTestSupport support = VaultTestSupport.in(temp);
    VaultService service = support.service();
    AtomicReference<MainFrame> frame = new AtomicReference<>();

    SwingUtilities.invokeAndWait(() -> frame.set(new MainFrame(service, support.clipboard())));
    try {
        assertSame(service, frame.get().getVaultServiceForTest());
    } finally {
        SwingUtilities.invokeAndWait(frame.get()::dispose);
        service.close();
    }
}
~~~

Retain the existing sidebar, lazy content, and no-JTabbedPane assertions. The test helper may count the two expected consumers rather than using reflection into secrets.

- [ ] **Step 2: Run MainFrameStructureTest and verify failure**

Run:

~~~powershell
mvn -Dtest=MainFrameStructureTest test
~~~

Expected: compilation fails because the injected MainFrame constructor and VaultBootstrap do not exist.

- [ ] **Step 3: Implement VaultBootstrap**

createDefault builds ApplicationPaths.systemDefault, AtomicFiles, a ConfigStore for migration using the same paths as the ConfigManager facade, VaultCrypto, VaultFileLock, VaultRepository, LegacyVaultMigrator, a single daemon executor, production clock/scheduler, idle minutes from ConfigManager with a validated default of 5, VaultService, and SecureClipboard. Return a small Components value containing service and clipboard so MainFrame does not construct internals.

- [ ] **Step 4: Inject shared components into MainFrame**

Use constructor delegation:

~~~java
public MainFrame() {
    this(VaultBootstrap.createDefault());
}

private MainFrame(VaultBootstrap.Components components) {
    this(components.getService(), components.getClipboard());
}

MainFrame(VaultService vaultService, SecureClipboard clipboard) {
    super(I18n.get("app.title"));
    this.vaultService = Objects.requireNonNull(vaultService, "vaultService");
    this.secureClipboard = Objects.requireNonNull(clipboard, "clipboard");
    createTools();
    // existing window initialization follows
}
~~~

Replace AccountManagerPanel::new and TotpPanel::new with lambdas that pass the same fields. windowClosing persists window state first, then calls vaultService.close. Override dispose to stop statusTimer and close the idempotent VaultService, so test frames and normal frames release file locks even when no WINDOW_CLOSING event is sent. Add package-private getVaultServiceForTest() for the composition assertion; it exposes only object identity, not keys or data.

- [ ] **Step 5: Run structure and lazy-loading tests**

Run:

~~~powershell
mvn -Dtest=MainFrameStructureTest,ToolContentHostTest,BpmnPanelLazyInitializationTest test
~~~

Expected: all selected tests pass and tests use only temporary vault paths.

- [ ] **Step 6: Commit application composition**

~~~powershell
git add src/main/java/com/aqishi/toolbox/vault/VaultBootstrap.java src/main/java/com/aqishi/toolbox/ui/MainFrame.java src/test/java/com/aqishi/toolbox/ui/MainFrameStructureTest.java
git commit -m "feat(vault): inject one vault session into the app"
~~~

### Task 12: End-to-end migration, documentation, and compatibility verification

**Files:**
- Modify: README.md
- Modify: docs/superpowers/specs/2026-07-26-secure-vault-design.md only if implementation reveals a factual mismatch
- Test: all vault and existing tests

- [ ] **Step 1: Add a process-level temporary-directory migration test**

Create or extend LegacyVaultMigratorTest to build the complete production graph with temporary paths, migrate BOTH legacy sources, close the first service, build a second service, unlock the new vault, and assert both data slices survive restart. Also assert the sanitized config and every unencrypted file under the temporary root contain neither the fixture password nor fixture TOTP Secret.

- [ ] **Step 2: Run the end-to-end test alone**

Run:

~~~powershell
mvn -Dtest=LegacyVaultMigratorTest#survivesRestartWithoutPlaintextSecrets test
~~~

Expected: PASS; the test output contains no fixture secret.

- [ ] **Step 3: Update README**

Document:

- one master password unlocks password manager and TOTP;
- default five-minute lock and 1/5/10/30-minute choices;
- 30-second conditional clipboard clearing;
- platform-specific config/data directories;
- automatic legacy migration and encrypted backup directory;
- recovery steps for wrong password, damaged vault, cleanup warning, and another running instance;
- master password cannot be recovered;
- no TOTP Secret remains in toolbox-config.properties after migration.

- [ ] **Step 4: Run secret and legacy-code scans**

Run:

~~~powershell
rg -n "encryptAES|decryptAES|AES/CBC|TOOLBOX_PWD_MGR|totp\\.accounts" src/main/java
rg -n "masterPassword\\s*=|oldP\\s*=\\s*new String|newP\\s*=\\s*new String|confP\\s*=\\s*new String" src/main/java
~~~

Expected:

- AES/CBC and TOOLBOX_PWD_MGR appear only in LegacyPasswordReader.
- totp.accounts appears only in ConfigStore, LegacyTotpReader, LegacyVaultMigrator, or documentation.
- no panel stores a master password String.

- [ ] **Step 5: Run the complete test suite**

Run:

~~~powershell
mvn test
~~~

Expected: BUILD SUCCESS; all existing 70 tests and all new vault tests pass with zero failures or errors.

- [ ] **Step 6: Run a clean package**

Run:

~~~powershell
mvn clean package
~~~

Expected: BUILD SUCCESS and target/java-toolbox.jar exists.

- [ ] **Step 7: Verify Java 8 and current-JDK compatibility**

On a machine or CI worker with JDK 8:

~~~powershell
$env:JAVA_HOME='C:\path\to\jdk8'
mvn -version
mvn clean test
~~~

Expected: Maven reports Java 1.8 and BUILD SUCCESS.

Restore the current development JDK and run:

~~~powershell
mvn -version
mvn clean test
~~~

Expected: Maven reports the development JDK and BUILD SUCCESS. If no JDK 8 runtime is locally available, do not claim Java 8 runtime verification; record it as an external verification requirement while still confirming target 1.8 compilation.

- [ ] **Step 8: Perform manual desktop smoke checks with disposable data**

Start the application from a temporary working directory and use only fixture credentials. Verify setup, both tools unlocking together, add/edit/delete, manual lock, five-minute lock with a temporarily shortened test configuration, clipboard replacement preservation, change password, restart unlock, and a failed migration retaining both old files. Never open the developer's real TOTP or password files during this check.

- [ ] **Step 9: Commit documentation and final integration tests**

~~~powershell
git add README.md src/test/java/com/aqishi/toolbox/vault/LegacyVaultMigratorTest.java docs/superpowers/specs/2026-07-26-secure-vault-design.md
git commit -m "docs(vault): document secure storage and recovery"
~~~

- [ ] **Step 10: Inspect the final change set**

Run:

~~~powershell
git status --short
git log --oneline --decorate -12
git diff origin/main...HEAD --stat
~~~

Expected: the worktree is clean; commits are scoped by task; no toolbox-config.properties, toolbox-passwords.enc, backup, candidate, lock, or vault data file is tracked.

## Completion gate

Do not declare the implementation complete unless:

1. All approved acceptance criteria map to a passing test or an explicitly recorded manual check.
2. Existing tests and new vault tests pass.
3. clean package succeeds.
4. The repository contains no real secret fixture.
5. The working tree contains no generated vault/config/backup file.
6. Java 8 runtime verification is either evidenced or accurately reported as still external.
