package com.aqishi.toolbox.infra.secrets;

import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultErrorCode;
import com.aqishi.toolbox.vault.VaultException;
import com.aqishi.toolbox.vault.VaultListener;
import com.aqishi.toolbox.vault.VaultService;
import com.aqishi.toolbox.vault.VaultState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link SecretStore} kept in the {@code connectionSecrets} section of the shared vault.
 *
 * <p>Vault keys are {@code namespace + "/" + id}. Writes issued through this store are
 * queued one after another, and a write that collides with another vault operation
 * (for example the account manager saving) is retried for a few seconds instead of
 * failing with {@code BUSY}.</p>
 */
public final class VaultSecretStore implements SecretStore {
    static final int DEFAULT_RETRIES = 50;

    private final VaultService service;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final long retryDelayMillis;
    private final int retries;
    private final Object queueLock = new Object();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private volatile Status lastStatus;

    public VaultSecretStore(VaultService service) {
        this(service, 100, DEFAULT_RETRIES);
    }

    VaultSecretStore(VaultService service, long retryDelayMillis, int retries) {
        this.service = Objects.requireNonNull(service, "service");
        this.retryDelayMillis = retryDelayMillis;
        this.retries = retries;
        this.lastStatus = status();
        VaultListener bridge = this::onVaultState;
        service.addListener(bridge);
    }

    /**
     * A store over {@code service}, or {@link SecretStore#disabled()} for a null service.
     * Each tool gets its own instance (all share the one vault session); concurrent
     * writes from different instances are serialised by the BUSY retry.
     */
    public static SecretStore of(VaultService service) {
        return service == null ? SecretStore.disabled() : new VaultSecretStore(service);
    }

    static String key(String namespace, String id) {
        if (namespace == null || namespace.isEmpty() || namespace.contains("/")) {
            throw new IllegalArgumentException("namespace");
        }
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("id");
        }
        return namespace + "/" + id;
    }

    @Override
    public Status status() {
        return map(service.getState());
    }

    private Status map(VaultState state) {
        switch (state) {
            case UNLOCKED:
            case SAVING:
                return Status.UNLOCKED;
            case MIGRATION_REQUIRED:
                return Status.NO_VAULT;
            case ERROR_READ_ONLY:
                return Status.UNAVAILABLE;
            case UNLOCKING:
            case LOCKED:
            default:
                return service.isInitialized() ? Status.LOCKED : Status.NO_VAULT;
        }
    }

    @Override
    public String get(String namespace, String id) {
        return service.getConnectionSecret(key(namespace, id));
    }

    @Override
    public List<String> ids(String namespace) {
        String prefix = key(namespace, "x");
        prefix = prefix.substring(0, prefix.length() - 1);
        List<String> ids = new ArrayList<>();
        for (String key : service.getConnectionSecretKeys()) {
            if (key.startsWith(prefix)) ids.add(key.substring(prefix.length()));
        }
        return Collections.unmodifiableList(ids);
    }

    @Override
    public CompletableFuture<Void> put(String namespace, String id, String secret) {
        Objects.requireNonNull(secret, "secret");
        return apply(namespace, Collections.singletonMap(id, secret), null);
    }

    @Override
    public CompletableFuture<Void> remove(String namespace, String id) {
        return apply(namespace, null, Collections.singletonList(id));
    }

    @Override
    public CompletableFuture<Void> move(String namespace, String fromId, String toId) {
        final String from = key(namespace, fromId);
        final String to = key(namespace, toId);
        if (from.equals(to)) return CompletableFuture.completedFuture(null);
        return enqueue(() -> {
            String value = service.getConnectionSecret(from);
            Map<String, String> puts = value == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.singletonMap(to, value);
            return service.updateConnectionSecrets(puts, Collections.singletonList(from));
        });
    }

    @Override
    public CompletableFuture<Void> apply(String namespace, Map<String, String> puts,
                                         Collection<String> removals) {
        final Map<String, String> keyedPuts = new LinkedHashMap<>();
        if (puts != null) {
            for (Map.Entry<String, String> entry : puts.entrySet()) {
                keyedPuts.put(key(namespace, entry.getKey()),
                        Objects.requireNonNull(entry.getValue(), "secret"));
            }
        }
        final List<String> keyedRemovals = new ArrayList<>();
        if (removals != null) {
            for (String id : removals) keyedRemovals.add(key(namespace, id));
        }
        if (keyedPuts.isEmpty() && keyedRemovals.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return enqueue(() -> service.updateConnectionSecrets(keyedPuts, keyedRemovals));
    }

    @Override
    public CompletableFuture<Void> unlock(char[] masterPassword) {
        Objects.requireNonNull(masterPassword, "masterPassword");
        if (service.getState() == VaultState.UNLOCKED) {
            VaultCrypto.wipe(masterPassword);
            return CompletableFuture.completedFuture(null);
        }
        return service.unlock(masterPassword);
    }

    @Override
    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void onVaultState(VaultState state) {
        Status next = map(state);
        if (next == lastStatus) return;
        lastStatus = next;
        for (Listener listener : listeners) {
            try {
                listener.onSecretStoreStatus(next);
            } catch (RuntimeException ignored) {
                // One misbehaving panel must not keep the others holding secrets after a lock.
            }
        }
    }

    private CompletableFuture<Void> enqueue(Supplier<CompletableFuture<Void>> operation) {
        synchronized (queueLock) {
            CompletableFuture<Void> result = tail
                    .handle((ignored, error) -> null)
                    .thenCompose(ignored -> withRetry(operation, retries));
            tail = result;
            return result;
        }
    }

    private CompletableFuture<Void> withRetry(Supplier<CompletableFuture<Void>> operation,
                                              int remaining) {
        CompletableFuture<Void> attempt;
        try {
            attempt = operation.get();
        } catch (RuntimeException error) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(error);
            return failed;
        }
        return attempt.handle((ignored, error) -> {
            if (error == null) return CompletableFuture.<Void>completedFuture(null);
            if (remaining > 0 && isBusy(error)) {
                return CompletableFuture.supplyAsync(() -> null,
                                CompletableFuture.delayedExecutor(retryDelayMillis, TimeUnit.MILLISECONDS))
                        .thenCompose(x -> withRetry(operation, remaining - 1));
            }
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(unwrap(error));
            return failed;
        }).thenCompose(future -> future);
    }

    private static boolean isBusy(Throwable error) {
        Throwable cause = unwrap(error);
        return cause instanceof VaultException
                && ((VaultException) cause).getCode() == VaultErrorCode.BUSY;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
