package com.aqishi.toolbox.infra.secrets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * Owns one tool's live profile map and keeps its secrets in the vault.
 *
 * <p>All methods run on the UI thread ({@code uiExecutor} is where asynchronous vault
 * results are applied). Responsibilities:</p>
 * <ul>
 *   <li>saving: the secret goes to the vault, the profile to preferences with only a
 *       {@code secretStored} flag; a locked vault is handled through {@link SecretPrompter};</li>
 *   <li>connecting: {@link #resolve} returns the secret from the vault, or asks to unlock or
 *       for a password used for this session only;</li>
 *   <li>one-time migration: legacy plaintext secrets are written to the vault first, read
 *       back to verify, and only then stripped from preferences. A failed vault write leaves
 *       preferences untouched, and a crash in between simply repeats the (idempotent) copy;</li>
 *   <li>locking drops every secret cached for the session.</li>
 * </ul>
 */
public final class ProfileSecretManager<P extends SecretBearing> {

    /** Persistence of the name-keyed profile map. */
    public interface ProfileRepository<P> {
        LinkedHashMap<String, P> load();

        void save(Map<String, P> profiles);
    }

    /** Change notifications, delivered on the UI thread. */
    public interface Listener {
        /** Counts, migration progress or vault status changed (refresh notices). */
        default void onSecretsChanged() {
        }

        /** The vault locked: drop any secret shown or held by the panel. */
        default void onSecretsLocked() {
        }

        /** The vault unlocked: stored secrets are readable again. */
        default void onSecretsUnlocked() {
        }
    }

    public enum SaveOutcome { SAVED_WITH_SECRET, SAVED_WITHOUT_SECRET, SECRET_WRITE_FAILED, UNLOCK_FAILED, CANCELLED }

    /** Outcome of {@link #resolve}; {@link #fields()} is empty when nothing is known. */
    public static final class Resolution {
        public enum Kind { RESOLVED, NONE, MISSING, UNLOCK_FAILED, CANCELLED }

        private final Kind kind;
        private final Map<String, String> fields;

        private Resolution(Kind kind, Map<String, String> fields) {
            this.kind = kind;
            this.fields = fields == null ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(fields);
        }

        static Resolution of(Map<String, String> fields) {
            Map<String, String> cleaned = SecretFields.clean(fields);
            return new Resolution(cleaned.isEmpty() ? Kind.NONE : Kind.RESOLVED, cleaned);
        }

        static Resolution of(Kind kind) {
            return new Resolution(kind, null);
        }

        public Kind kind() {
            return kind;
        }

        public Map<String, String> fields() {
            return fields;
        }

        public String field(String name) {
            return fields.get(name);
        }

        public boolean cancelled() {
            return kind == Kind.CANCELLED;
        }

        @Override
        public String toString() {
            return "Resolution[" + kind + ", fields=" + fields.keySet() + "]";
        }
    }

    private final String namespace;
    private final ProfileRepository<P> repository;
    private final SecretStore store;
    private final Executor uiExecutor;
    private final LinkedHashMap<String, P> profiles = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> sessionCache = new HashMap<>();
    private final Set<String> pendingRemovals = new LinkedHashSet<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SecretStore.Listener storeListener = this::onStoreStatus;
    private boolean migrating;
    private boolean loaded;

    public ProfileSecretManager(String namespace, ProfileRepository<P> repository,
                                SecretStore store, Executor uiExecutor) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.store = store == null ? SecretStore.disabled() : store;
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
        this.store.addListener(storeListener);
    }

    /** The live map the panel edits; persist changes with {@link #persist()}. */
    public LinkedHashMap<String, P> profiles() {
        return profiles;
    }

    /** Reloads from preferences and starts the migration when the vault is already open. */
    public void reload() {
        loaded = true;
        profiles.clear();
        profiles.putAll(repository.load());
        sessionCache.clear();
        fireChanged();
        migratePlaintext();
    }

    /**
     * Loads once. Panels call this when their view is built; the first unlock also loads,
     * so plaintext is migrated even for a tool that was never opened this session.
     */
    public void ensureLoaded() {
        if (!loaded) reload();
    }

    public boolean loaded() {
        return loaded;
    }

    public void persist() {
        repository.save(profiles);
    }

    public SecretStore.Status status() {
        return store.status();
    }

    public SecretStore store() {
        return store;
    }

    public boolean migrating() {
        return migrating;
    }

    public int plaintextCount() {
        int count = 0;
        for (P profile : profiles.values()) if (profile.plaintextPending()) count++;
        return count;
    }

    public int vaultStoredCount() {
        int count = 0;
        for (P profile : profiles.values()) {
            if (profile.secretStored() && !profile.plaintextPending()) count++;
        }
        return count;
    }

    /** Secrets known without prompting: pending plaintext, session cache, or the open vault. */
    public Map<String, String> knownSecrets(P profile) {
        if (profile == null) return Collections.emptyMap();
        if (profile.plaintextPending()) return SecretFields.clean(profile.secretFields());
        if (!profile.secretStored()) return Collections.emptyMap();
        Map<String, String> cached = sessionCache.get(profile.secretId());
        if (cached != null) return cached;
        if (store.status() != SecretStore.Status.UNLOCKED) return Collections.emptyMap();
        Map<String, String> fields = SecretFields.decode(store.get(namespace, profile.secretId()));
        if (!fields.isEmpty()) sessionCache.put(profile.secretId(), fields);
        return fields;
    }

    /** True when the profile has a vault secret that cannot be read right now. */
    public boolean needsUnlock(P profile) {
        return profile != null && profile.secretStored() && !profile.plaintextPending()
                && !sessionCache.containsKey(profile.secretId())
                && store.status() != SecretStore.Status.UNLOCKED;
    }

    /**
     * Saves {@code profile} (carrying the secrets typed in the form) under {@code name}.
     * An existing profile of that name keeps its id; the secret never stays on the profile.
     */
    public CompletableFuture<SaveOutcome> save(String name, P profile, SecretPrompter prompter) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(profile, "profile");
        P existing = profiles.get(name);
        String id = existing != null && existing.secretId() != null
                ? existing.secretId() : UUID.randomUUID().toString();
        boolean hadSecret = existing != null && (existing.secretStored() || existing.plaintextPending());
        profile.assignSecretId(id);
        profile.markPlaintextPending(false);
        final Map<String, String> fields = SecretFields.clean(profile.secretFields());
        profile.applySecretFields(null);
        sessionCache.remove(id);

        if (fields.isEmpty() || store.status() == SecretStore.Status.DISABLED) {
            return commitWithoutSecret(name, profile, hadSecret);
        }
        pendingRemovals.remove(id);
        CompletableFuture<Boolean> ready;
        if (store.status() == SecretStore.Status.UNLOCKED) {
            ready = CompletableFuture.completedFuture(true);
        } else {
            SecretPrompter.Answer answer = prompter.askBeforeSave(profile.secretLabel(), store.status());
            switch (answer.kind()) {
                case UNLOCK:
                    ready = store.unlock(answer.value()).handle((ignored, error) -> error == null);
                    break;
                case SKIP_SECRET:
                    answer.wipe();
                    return commitWithoutSecret(name, profile, hadSecret);
                default:
                    answer.wipe();
                    return CompletableFuture.completedFuture(SaveOutcome.CANCELLED);
            }
        }
        return ready.thenCompose(unlocked -> {
            if (!unlocked) return CompletableFuture.completedFuture(SaveOutcome.UNLOCK_FAILED);
            return store.put(namespace, id, SecretFields.encode(fields))
                    .handle((ignored, error) -> error == null
                            ? SaveOutcome.SAVED_WITH_SECRET : SaveOutcome.SECRET_WRITE_FAILED);
        }).thenApplyAsync(outcome -> {
            if (outcome == SaveOutcome.UNLOCK_FAILED) return outcome;
            profile.markSecretStored(outcome == SaveOutcome.SAVED_WITH_SECRET);
            if (outcome == SaveOutcome.SAVED_WITH_SECRET) sessionCache.put(id, fields);
            commit(name, profile);
            return outcome;
        }, uiExecutor);
    }

    private CompletableFuture<SaveOutcome> commitWithoutSecret(String name, P profile, boolean hadSecret) {
        profile.markSecretStored(false);
        commit(name, profile);
        if (hadSecret) removeSecret(profile.secretId());
        return CompletableFuture.completedFuture(SaveOutcome.SAVED_WITHOUT_SECRET);
    }

    private void commit(String name, P profile) {
        profiles.put(name, profile);
        persist();
        fireChanged();
    }

    /** Removes the profile and its vault secret (deferred until unlock when locked). */
    public P delete(String name) {
        P removed = profiles.remove(name);
        if (removed == null) return null;
        persist();
        if (removed.secretStored() || removed.plaintextPending()) removeSecret(removed.secretId());
        fireChanged();
        return removed;
    }

    /** Renames a profile; its stable id, and so its vault entry, is unchanged. */
    public boolean rename(String oldName, String newName) {
        if (oldName == null || newName == null || oldName.equals(newName)
                || !profiles.containsKey(oldName) || profiles.containsKey(newName)) {
            return false;
        }
        LinkedHashMap<String, P> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, P> entry : profiles.entrySet()) {
            reordered.put(entry.getKey().equals(oldName) ? newName : entry.getKey(), entry.getValue());
        }
        profiles.clear();
        profiles.putAll(reordered);
        persist();
        fireChanged();
        return true;
    }

    private void removeSecret(String id) {
        if (id == null) return;
        sessionCache.remove(id);
        if (store.status() == SecretStore.Status.UNLOCKED) {
            store.remove(namespace, id).whenComplete((ignored, error) -> {
                if (error != null) uiExecutor.execute(() -> pendingRemovals.add(id));
            });
        } else if (store.status() != SecretStore.Status.DISABLED) {
            pendingRemovals.add(id);
        }
    }

    /**
     * Secrets for connecting with {@code profile}; asks through {@code prompter} when the
     * vault is not open. A password typed for this session is cached until the vault locks.
     */
    public CompletableFuture<Resolution> resolve(P profile, SecretPrompter prompter,
                                                 boolean sessionEntryAllowed) {
        if (profile == null || profile.plaintextPending() || !profile.secretStored()
                || sessionCache.containsKey(profile.secretId())
                || store.status() == SecretStore.Status.UNLOCKED
                || store.status() == SecretStore.Status.DISABLED) {
            return CompletableFuture.completedFuture(known(profile));
        }
        SecretPrompter.Answer answer = prompter.askBeforeConnect(
                profile.secretLabel(), store.status(), sessionEntryAllowed);
        switch (answer.kind()) {
            case UNLOCK:
                return store.unlock(answer.value()).handleAsync((ignored, error) -> error != null
                        ? Resolution.of(Resolution.Kind.UNLOCK_FAILED) : known(profile), uiExecutor);
            case SESSION_SECRET:
                if (!sessionEntryAllowed) {
                    answer.wipe();
                    return CompletableFuture.completedFuture(Resolution.of(Resolution.Kind.CANCELLED));
                }
                Map<String, String> typed = new HashMap<>();
                typed.put(profile.primarySecretField(), new String(answer.value()));
                answer.wipe();
                Map<String, String> cleaned = SecretFields.clean(typed);
                if (!cleaned.isEmpty()) sessionCache.put(profile.secretId(), cleaned);
                return CompletableFuture.completedFuture(Resolution.of(cleaned));
            case SKIP_SECRET:
                return CompletableFuture.completedFuture(Resolution.of(Resolution.Kind.NONE));
            default:
                answer.wipe();
                return CompletableFuture.completedFuture(Resolution.of(Resolution.Kind.CANCELLED));
        }
    }

    private Resolution known(P profile) {
        Map<String, String> fields = knownSecrets(profile);
        if (fields.isEmpty() && profile != null && profile.secretStored()
                && store.status() == SecretStore.Status.UNLOCKED) {
            return Resolution.of(Resolution.Kind.MISSING);
        }
        return Resolution.of(fields);
    }

    /**
     * Moves pending plaintext secrets into the vault, then strips them from preferences.
     * Completes with the number of migrated profiles; a no-op unless the vault is unlocked.
     */
    public CompletableFuture<Integer> migratePlaintext() {
        if (migrating || store.status() != SecretStore.Status.UNLOCKED) {
            return CompletableFuture.completedFuture(0);
        }
        final Map<String, Map<String, String>> snapshot = new HashMap<>();
        final Map<String, String> puts = new LinkedHashMap<>();
        for (P profile : profiles.values()) {
            if (!profile.plaintextPending()) continue;
            Map<String, String> fields = SecretFields.clean(profile.secretFields());
            if (fields.isEmpty()) {
                profile.markPlaintextPending(false);
                continue;
            }
            snapshot.put(profile.secretId(), fields);
            puts.put(profile.secretId(), SecretFields.encode(fields));
        }
        if (puts.isEmpty()) return CompletableFuture.completedFuture(0);
        migrating = true;
        fireChanged();
        CompletableFuture<Integer> result = store.apply(namespace, puts, null)
                .thenApplyAsync(ignored -> stripMigrated(snapshot, puts), uiExecutor);
        result.whenComplete((count, error) -> uiExecutor.execute(() -> {
            migrating = false;
            fireChanged();
        }));
        return result;
    }

    private int stripMigrated(Map<String, Map<String, String>> snapshot, Map<String, String> puts) {
        List<P> changed = new ArrayList<>();
        Map<P, Boolean> previousStored = new HashMap<>();
        for (P profile : profiles.values()) {
            String id = profile.secretId();
            Map<String, String> fields = snapshot.get(id);
            if (!profile.plaintextPending() || fields == null) continue;
            if (!fields.equals(SecretFields.clean(profile.secretFields()))) continue;
            // Verify the committed vault copy before the plaintext is dropped.
            if (!puts.get(id).equals(store.get(namespace, id))) continue;
            previousStored.put(profile, profile.secretStored());
            profile.applySecretFields(null);
            profile.markPlaintextPending(false);
            profile.markSecretStored(true);
            changed.add(profile);
        }
        if (changed.isEmpty()) return 0;
        try {
            persist();
        } catch (RuntimeException error) {
            for (P profile : changed) {
                profile.applySecretFields(snapshot.get(profile.secretId()));
                profile.markPlaintextPending(true);
                profile.markSecretStored(previousStored.get(profile));
            }
            throw error;
        }
        for (P profile : changed) sessionCache.put(profile.secretId(), snapshot.get(profile.secretId()));
        return changed.size();
    }

    private void onStoreStatus(SecretStore.Status status) {
        uiExecutor.execute(() -> {
            // Any vault transition ends the "session": locked drops secrets, unlocked makes
            // the vault copy authoritative again over a password typed for the session.
            sessionCache.clear();
            if (status == SecretStore.Status.UNLOCKED) {
                for (Listener listener : listeners) listener.onSecretsUnlocked();
                flushPendingRemovals();
                if (loaded) {
                    migratePlaintext();
                } else {
                    try {
                        reload();
                    } catch (RuntimeException unreadable) {
                        loaded = false;
                    }
                }
            } else {
                sessionCache.clear();
                for (Listener listener : listeners) listener.onSecretsLocked();
            }
            fireChanged();
        });
    }

    private void flushPendingRemovals() {
        if (pendingRemovals.isEmpty()) return;
        List<String> ids = new ArrayList<>(pendingRemovals);
        for (P profile : profiles.values()) ids.remove(profile.secretId());
        pendingRemovals.clear();
        if (ids.isEmpty()) return;
        store.apply(namespace, null, ids).whenComplete((ignored, error) -> {
            if (error != null) uiExecutor.execute(() -> pendingRemovals.addAll(ids));
        });
    }

    /** Test/diagnostic view of the session cache size. */
    int cachedSecretCount() {
        return sessionCache.size();
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void dispose() {
        store.removeListener(storeListener);
        sessionCache.clear();
        listeners.clear();
    }

    private void fireChanged() {
        for (Listener listener : listeners) listener.onSecretsChanged();
    }
}
