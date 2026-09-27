package com.aqishi.toolbox.vault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** The single shared unlocked session used by password and TOTP tools. */
public final class VaultService implements AutoCloseable {
    private final Object mutex = new Object();
    private final VaultRepository repository;
    private final LegacyVaultMigrator migrator;
    private final Executor backgroundExecutor;
    private final Executor eventExecutor;
    private final VaultClock clock;
    private final VaultScheduler scheduler;
    private final List<VaultListener> listeners = new ArrayList<>();

    private volatile VaultState state;
    private volatile LegacyVaultMigrator.MigrationMode migrationMode;
    private List<String> cleanupWarnings = Collections.emptyList();
    private VaultRepository.OpenedVault opened;
    private VaultData snapshot;
    private CompletableFuture<?> activeOperation;
    private long lastActivityMillis;
    private int idleMinutes;
    private boolean closed;

    public VaultService(VaultRepository repository,
                        LegacyVaultMigrator migrator,
                        Executor backgroundExecutor,
                        Executor eventExecutor,
                        VaultClock clock,
                        VaultScheduler scheduler,
                        int initialIdleMinutes) throws VaultException {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.migrator = Objects.requireNonNull(migrator, "migrator");
        this.backgroundExecutor = Objects.requireNonNull(
                backgroundExecutor, "backgroundExecutor");
        this.eventExecutor = Objects.requireNonNull(eventExecutor, "eventExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        setIdleMinutes(initialIdleMinutes);
        migrationMode = migrator.probe();
        state = migrationMode != LegacyVaultMigrator.MigrationMode.NONE
                ? VaultState.MIGRATION_REQUIRED : VaultState.LOCKED;
        scheduler.scheduleAtFixedRate(this::checkIdleTimeout,
                30, 30, TimeUnit.SECONDS);
    }

    public CompletableFuture<Void> create(char[] password) {
        Objects.requireNonNull(password, "password");
        if (password.length == 0) {
            VaultCrypto.wipe(password);
            return failed(new VaultException(VaultErrorCode.INVALID_ENVELOPE,
                    "Master password must not be empty", true));
        }
        return runOperation(VaultState.UNLOCKING, password, () -> {
            VaultRepository.OpenedVault created = repository.create(
                    new VaultData(), password.clone());
            installOpened(created);
            migrationMode = LegacyVaultMigrator.MigrationMode.NONE;
        });
    }

    public CompletableFuture<Void> unlock(char[] password) {
        Objects.requireNonNull(password, "password");
        return runOperation(VaultState.UNLOCKING, password,
                () -> installOpened(repository.open(password.clone())));
    }

    public CompletableFuture<Void> migrate(char[] password) {
        Objects.requireNonNull(password, "password");
        return runOperation(VaultState.UNLOCKING, password, () -> {
            LegacyVaultMigrator.MigrationResult result =
                    migrator.migrate(password.clone());
            installOpened(result.getOpenedVault());
            cleanupWarnings = result.getWarnings();
            migrationMode = result.getWarnings().isEmpty()
                    ? LegacyVaultMigrator.MigrationMode.NONE
                    : LegacyVaultMigrator.MigrationMode.CLEANUP_REQUIRED;
        });
    }

    public CompletableFuture<Void> reset() {
        return runOperation(VaultState.UNLOCKING, (char[]) null, () -> {
            synchronized (mutex) {
                if (opened != null) {
                    opened.close();
                    opened = null;
                }
                snapshot = null;
            }
            repository.reset();
            List<String> warnings = migrator.discardLegacy();
            synchronized (mutex) {
                cleanupWarnings = warnings;
                migrationMode = LegacyVaultMigrator.MigrationMode.NONE;
            }
        });
    }

    public List<String> getCleanupWarnings() {
        synchronized (mutex) {
            return Collections.unmodifiableList(new ArrayList<>(cleanupWarnings));
        }
    }

    public CompletableFuture<Void> replacePasswordAccounts(
            List<PasswordAccount> accounts) {
        Objects.requireNonNull(accounts, "accounts");
        final List<PasswordAccount> copy = copyPasswords(accounts);
        return runUnlockedSave(() -> {
            VaultData candidate = currentSnapshot();
            candidate.setPasswordAccounts(copy);
            repository.save(currentOpened(), candidate);
            publishSaved(candidate);
        });
    }

    public CompletableFuture<Void> replaceTotpAccounts(List<TotpAccount> accounts) {
        Objects.requireNonNull(accounts, "accounts");
        final List<TotpAccount> copy = copyTotps(accounts);
        return runUnlockedSave(() -> {
            VaultData candidate = currentSnapshot();
            candidate.setTotpAccounts(copy);
            repository.save(currentOpened(), candidate);
            publishSaved(candidate);
        });
    }

    /**
     * Atomically applies puts and removals to the connection-secret section in
     * one verified vault write. Keys are opaque to the vault; values are never
     * logged and never appear in exception messages.
     *
     * <p>Fails with {@link VaultErrorCode#READ_ONLY} when locked and with
     * {@link VaultErrorCode#BUSY} while another vault operation runs; callers
     * that write in the background retry the latter.</p>
     */
    public CompletableFuture<Void> updateConnectionSecrets(
            Map<String, String> puts, Collection<String> removals) {
        final Map<String, String> putCopy = new LinkedHashMap<>(
                puts == null ? Collections.<String, String>emptyMap() : puts);
        final List<String> removeCopy = new ArrayList<>(
                removals == null ? Collections.<String>emptyList() : removals);
        for (Map.Entry<String, String> entry : putCopy.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || entry.getValue() == null) {
                return failed(new VaultException(VaultErrorCode.INVALID_ENVELOPE,
                        "Connection secret key or value is missing", false));
            }
        }
        return runUnlockedSave(() -> {
            VaultData candidate = currentSnapshot();
            Map<String, String> secrets = candidate.copyConnectionSecrets();
            for (String key : removeCopy) {
                if (key != null) secrets.remove(key);
            }
            secrets.putAll(putCopy);
            candidate.setConnectionSecrets(secrets);
            repository.save(currentOpened(), candidate);
            publishSaved(candidate);
        });
    }

    /**
     * Returns one connection secret, or {@code null} when it is absent or the
     * vault is not open. A save in progress keeps the last committed snapshot
     * readable so a connect that races a background write still finds it.
     */
    public String getConnectionSecret(String key) {
        if (key == null) return null;
        synchronized (mutex) {
            if (snapshot == null
                    || (state != VaultState.UNLOCKED && state != VaultState.SAVING)) {
                return null;
            }
            lastActivityMillis = clock.currentTimeMillis();
            return snapshot.connectionSecret(key);
        }
    }

    /** Keys of every stored connection secret; empty while locked. */
    public List<String> getConnectionSecretKeys() {
        synchronized (mutex) {
            if (snapshot == null
                    || (state != VaultState.UNLOCKED && state != VaultState.SAVING)) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(
                    new ArrayList<>(snapshot.copyConnectionSecrets().keySet()));
        }
    }

    public CompletableFuture<Void> changePassword(
            char[] currentPassword, char[] newPassword) {
        Objects.requireNonNull(currentPassword, "currentPassword");
        Objects.requireNonNull(newPassword, "newPassword");
        if (newPassword.length == 0) {
            VaultCrypto.wipe(currentPassword);
            VaultCrypto.wipe(newPassword);
            return failed(new VaultException(VaultErrorCode.INVALID_ENVELOPE,
                    "Master password must not be empty", true));
        }
        char[][] owned = new char[][]{currentPassword, newPassword};
        return runOperation(VaultState.SAVING, owned, () -> {
            VaultRepository.OpenedVault check = repository.open(currentPassword.clone());
            check.close();
            repository.rekey(currentOpened(), newPassword.clone());
            synchronized (mutex) {
                lastActivityMillis = clock.currentTimeMillis();
            }
        });
    }

    public List<PasswordAccount> getPasswordAccounts() {
        synchronized (mutex) {
            if (state != VaultState.UNLOCKED || snapshot == null) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(snapshot.copyPasswordAccounts());
        }
    }

    public List<TotpAccount> getTotpAccounts() {
        synchronized (mutex) {
            if (state != VaultState.UNLOCKED || snapshot == null) {
                return Collections.emptyList();
            }
            return Collections.unmodifiableList(snapshot.copyTotpAccounts());
        }
    }

    public VaultState getState() {
        return state;
    }

    public LegacyVaultMigrator.MigrationMode getMigrationMode() {
        return migrationMode;
    }

    public boolean isInitialized() {
        return repository.exists();
    }

    public void addListener(VaultListener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (mutex) {
            listeners.add(listener);
        }
    }

    public void removeListener(VaultListener listener) {
        synchronized (mutex) {
            listeners.remove(listener);
        }
    }

    public void setIdleMinutes(int minutes) {
        if (minutes != 1 && minutes != 5 && minutes != 10 && minutes != 30) {
            throw new IllegalArgumentException("Idle timeout must be 1, 5, 10, or 30 minutes");
        }
        synchronized (mutex) {
            idleMinutes = minutes;
        }
    }

    public void touch() {
        synchronized (mutex) {
            if (state == VaultState.UNLOCKED) {
                lastActivityMillis = clock.currentTimeMillis();
            }
        }
    }

    /**
     * Closes the open vault and drops the decrypted snapshot.
     *
     * <p>Listeners receive the state actually entered, which is not always
     * {@link VaultState#LOCKED}: a vault that still needs migration or that is
     * read-only falls back to {@code MIGRATION_REQUIRED} / {@code ERROR_READ_ONLY}.
     * Reporting LOCKED there made panels offer an unlock that could not succeed.</p>
     */
    public void lock() {
        VaultState notifyState = null;
        synchronized (mutex) {
            if (opened != null) {
                opened.close();
                opened = null;
            }
            snapshot = null;
            VaultState lockedState = fallbackLockedState();
            if (state != lockedState) {
                state = lockedState;
                notifyState = lockedState;
            }
        }
        if (notifyState != null) notifyListeners(notifyState);
    }

    @Override
    public void close() {
        CompletableFuture<?> pending;
        synchronized (mutex) {
            if (closed) return;
            closed = true;
            pending = activeOperation;
        }
        if (pending != null) {
            try {
                pending.get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        lock();
        scheduler.close();
        repository.close();
        if (backgroundExecutor instanceof ExecutorService) {
            ((ExecutorService) backgroundExecutor).shutdown();
        }
    }

    private CompletableFuture<Void> runUnlockedSave(Operation operation) {
        synchronized (mutex) {
            // Another save in flight is a transient collision, not a lock: report BUSY so
            // background writers (connection secrets) can retry instead of giving up.
            if (state == VaultState.SAVING && opened != null) {
                return failed(new VaultException(VaultErrorCode.BUSY,
                        "Another vault operation is active", true));
            }
            if (state != VaultState.UNLOCKED || opened == null) {
                return failed(new VaultException(VaultErrorCode.READ_ONLY,
                        "Vault is locked", true));
            }
        }
        return runOperation(VaultState.SAVING, (char[]) null, operation);
    }

    private CompletableFuture<Void> runOperation(
            VaultState workingState, char[] password, Operation operation) {
        return runOperation(workingState,
                password == null ? null : new char[][]{password}, operation);
    }

    private CompletableFuture<Void> runOperation(
            VaultState workingState, char[][] sensitive, Operation operation) {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        synchronized (mutex) {
            if (closed) {
                wipe(sensitive);
                return failed(new VaultException(VaultErrorCode.READ_ONLY,
                        "Vault service is closed", false));
            }
            if (activeOperation != null && !activeOperation.isDone()) {
                wipe(sensitive);
                return failed(new VaultException(VaultErrorCode.BUSY,
                        "Another vault operation is active", true));
            }
            activeOperation = future;
            state = workingState;
        }
        notifyListeners(workingState);
        try {
            backgroundExecutor.execute(() -> {
                try {
                    operation.run();
                    VaultException closedFailure = null;
                    synchronized (mutex) {
                        if (closed) {
                            if (opened != null) {
                                opened.close();
                                opened = null;
                                snapshot = null;
                            }
                            state = VaultState.LOCKED;
                            closedFailure = new VaultException(VaultErrorCode.READ_ONLY,
                                    "Vault service is closed", false);
                        } else {
                            state = opened != null ? VaultState.UNLOCKED : fallbackLockedState();
                        }
                    }
                    if (closedFailure == null) future.complete(null);
                    else future.completeExceptionally(closedFailure);
                } catch (Throwable error) {
                    synchronized (mutex) {
                        state = closed ? VaultState.LOCKED
                                : (opened == null ? fallbackLockedState() : VaultState.UNLOCKED);
                    }
                    future.completeExceptionally(error);
                } finally {
                    wipe(sensitive);
                    VaultState completedState;
                    synchronized (mutex) {
                        activeOperation = null;
                        completedState = state;
                    }
                    notifyListeners(completedState);
                }
            });
        } catch (Throwable error) {
            wipe(sensitive);
            synchronized (mutex) {
                activeOperation = null;
                state = closed ? VaultState.LOCKED : fallbackLockedState();
            }
            future.completeExceptionally(error);
            notifyListeners(state);
        }
        return future;
    }

    private void installOpened(VaultRepository.OpenedVault replacement) throws VaultException {
        synchronized (mutex) {
            if (closed) {
                replacement.close();
                throw new VaultException(VaultErrorCode.READ_ONLY,
                        "Vault service is closed", false);
            }
            if (opened != null) opened.close();
            opened = replacement;
            snapshot = replacement.getData();
            lastActivityMillis = clock.currentTimeMillis();
        }
    }

    private VaultRepository.OpenedVault currentOpened() throws VaultException {
        synchronized (mutex) {
            if (opened == null) {
                throw new VaultException(VaultErrorCode.READ_ONLY,
                        "Vault is locked", true);
            }
            return opened;
        }
    }

    private VaultData currentSnapshot() throws VaultException {
        synchronized (mutex) {
            if (snapshot == null) {
                throw new VaultException(VaultErrorCode.READ_ONLY,
                        "Vault is locked", true);
            }
            return snapshot.copy();
        }
    }

    private void publishSaved(VaultData candidate) {
        synchronized (mutex) {
            snapshot = candidate.copy();
            lastActivityMillis = clock.currentTimeMillis();
        }
    }

    private void checkIdleTimeout() {
        boolean expired;
        synchronized (mutex) {
            expired = state == VaultState.UNLOCKED
                    && clock.currentTimeMillis() - lastActivityMillis
                    >= TimeUnit.MINUTES.toMillis(idleMinutes);
        }
        if (expired) lock();
    }

    private VaultState fallbackLockedState() {
        try {
            migrationMode = migrator.probe();
            return migrationMode != LegacyVaultMigrator.MigrationMode.NONE
                    ? VaultState.MIGRATION_REQUIRED : VaultState.LOCKED;
        } catch (VaultException error) {
            return VaultState.ERROR_READ_ONLY;
        }
    }

    private void notifyListeners(VaultState value) {
        List<VaultListener> copy;
        synchronized (mutex) {
            copy = new ArrayList<>(listeners);
        }
        for (VaultListener listener : copy) {
            eventExecutor.execute(() -> listener.onStateChanged(value));
        }
    }

    private static List<PasswordAccount> copyPasswords(List<PasswordAccount> source) {
        List<PasswordAccount> copy = new ArrayList<>(source.size());
        for (PasswordAccount account : source) {
            copy.add(account == null ? null : account.copy());
        }
        return copy;
    }

    private static List<TotpAccount> copyTotps(List<TotpAccount> source) {
        List<TotpAccount> copy = new ArrayList<>(source.size());
        for (TotpAccount account : source) {
            copy.add(account == null ? null : account.copy());
        }
        return copy;
    }

    private static void wipe(char[][] values) {
        if (values == null) return;
        for (char[] value : values) VaultCrypto.wipe(value);
    }

    private static <T> CompletableFuture<T> failed(Throwable error) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(error);
        return future;
    }

    private interface Operation {
        void run() throws Exception;
    }
}
