package com.aqishi.toolbox.infra.secrets;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Encrypted storage for connection secrets (database/Redis passwords, Kubernetes
 * tokens, SSH passphrases ...), addressed by a tool namespace plus a stable
 * profile id.
 *
 * <p>Reads are served from the unlocked in-memory snapshot and return
 * {@code null} while locked; writes are asynchronous. Implementations never log
 * secret values and never put them into exception messages.</p>
 */
public interface SecretStore {

    /** What the store can currently do. */
    enum Status {
        /** No vault is wired in (tests, standalone panels): secrets are simply not saved. */
        DISABLED,
        /** The vault has not been created yet (or still needs its legacy migration). */
        NO_VAULT,
        LOCKED,
        UNLOCKED,
        /** The vault is read-only or failed to open; nothing can be stored right now. */
        UNAVAILABLE
    }

    /** Invoked when {@link #status()} changes; on the store's event thread. */
    interface Listener {
        void onSecretStoreStatus(Status status);
    }

    Status status();

    /** The stored secret, or {@code null} when absent or not unlocked. */
    String get(String namespace, String id);

    /** Ids stored under {@code namespace}; empty unless unlocked. */
    List<String> ids(String namespace);

    CompletableFuture<Void> put(String namespace, String id, String secret);

    CompletableFuture<Void> remove(String namespace, String id);

    /** Moves a secret to a new id in one write (profile renamed or re-keyed). */
    CompletableFuture<Void> move(String namespace, String fromId, String toId);

    /** Applies several puts and removals in a single vault write. */
    CompletableFuture<Void> apply(String namespace, Map<String, String> puts,
                                  Collection<String> removals);

    /** Unlocks the backing vault; the array is wiped by the store. */
    CompletableFuture<Void> unlock(char[] masterPassword);

    void addListener(Listener listener);

    void removeListener(Listener listener);

    /** A store that keeps nothing; used where no vault is available. */
    static SecretStore disabled() {
        return DisabledSecretStore.INSTANCE;
    }

    /** No-op implementation behind {@link #disabled()}. */
    final class DisabledSecretStore implements SecretStore {
        static final DisabledSecretStore INSTANCE = new DisabledSecretStore();

        private DisabledSecretStore() {
        }

        @Override
        public Status status() {
            return Status.DISABLED;
        }

        @Override
        public String get(String namespace, String id) {
            return null;
        }

        @Override
        public List<String> ids(String namespace) {
            return Collections.emptyList();
        }

        @Override
        public CompletableFuture<Void> put(String namespace, String id, String secret) {
            return unsupported();
        }

        @Override
        public CompletableFuture<Void> remove(String namespace, String id) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> move(String namespace, String fromId, String toId) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> apply(String namespace, Map<String, String> puts,
                                             Collection<String> removals) {
            return puts == null || puts.isEmpty()
                    ? CompletableFuture.<Void>completedFuture(null) : unsupported();
        }

        @Override
        public CompletableFuture<Void> unlock(char[] masterPassword) {
            if (masterPassword != null) java.util.Arrays.fill(masterPassword, '\0');
            return unsupported();
        }

        @Override
        public void addListener(Listener listener) {
        }

        @Override
        public void removeListener(Listener listener) {
        }

        private static CompletableFuture<Void> unsupported() {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Secret store is disabled"));
            return future;
        }
    }
}
