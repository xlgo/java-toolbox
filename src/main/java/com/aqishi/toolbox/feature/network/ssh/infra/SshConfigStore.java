package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.infra.secrets.SecretFields;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.vault.ApplicationPaths;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.domain.SshSecurityUtils;

/**
 * SSH 服务器连接配置持久化存储
 */
public class SshConfigStore {

    private static final String FILE_NAME = "ssh_servers.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static SshConfigStore instance;

    private final List<SshConnectionConfig> configs = new CopyOnWriteArrayList<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    private final File configFile;

    public SshConfigStore() {
        this(defaultConfigFile());
    }

    /** Store over an explicit file; tests use a temporary directory. */
    SshConfigStore(File configFile) {
        this.configFile = configFile;
        load();
    }

    private static File defaultConfigFile() {
        File configDir = ApplicationPaths.systemDefault().getDataDirectory().toFile();
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
        return new File(configDir, FILE_NAME);
    }

    public static synchronized SshConfigStore getInstance() {
        if (instance == null) {
            instance = new SshConfigStore();
        }
        return instance;
    }

    // ==================== Vault-backed credentials ====================

    /** Vault namespace of SSH credentials. */
    public static final String SECRET_NAMESPACE = "ssh";

    /**
     * Asks the user for credentials of a vault-stored server while the vault is locked;
     * called off the EDT. Returns false to abort the connection.
     */
    public interface LockedCredentialResolver {
        boolean resolve(SshConnectionConfig config, SshConfigStore store);
    }

    private volatile SecretStore secrets = SecretStore.disabled();
    private volatile LockedCredentialResolver lockedResolver;
    private volatile Executor callbackExecutor = Runnable::run;
    private final SecretStore.Listener secretListener = this::onSecretStatus;
    private final Set<String> pendingVaultRemovals = new LinkedHashSet<>();
    private boolean migrating;

    /**
     * Moves credentials into {@code store}: existing local secrets are migrated as soon as
     * it is unlocked, and later saves never write them to {@code ssh_servers.json}.
     */
    public void attachSecretStore(SecretStore store, LockedCredentialResolver resolver,
                                  Executor callbacks) {
        callbackExecutor = callbacks == null ? Runnable::run : callbacks;
        SecretStore next = store == null ? SecretStore.disabled() : store;
        if (next != secrets) {
            secrets.removeListener(secretListener);
            secrets = next;
            next.addListener(secretListener);
        }
        lockedResolver = resolver;
        if (next.status() == SecretStore.Status.UNLOCKED) onSecretStatus(SecretStore.Status.UNLOCKED);
    }

    public SecretStore secretStore() {
        return secrets;
    }

    /** Servers whose credentials still sit in the local file (legacy or saved while locked). */
    public int localSecretCount() {
        if (secrets.status() == SecretStore.Status.DISABLED) return 0;
        int count = 0;
        for (SshConnectionConfig config : configs) if (config.hasLocalSecrets()) count++;
        return count;
    }

    public int vaultStoredCount() {
        int count = 0;
        for (SshConnectionConfig config : configs) {
            if (config.isSecretStored() && !config.hasLocalSecrets()) count++;
        }
        return count;
    }

    public boolean isMigrating() {
        return migrating;
    }

    private void onSecretStatus(SecretStore.Status status) {
        if (status == SecretStore.Status.UNLOCKED) {
            for (SshConnectionConfig config : configs) hydrate(config);
            flushPendingRemovals();
            migrateLocalSecrets();
        } else {
            // Open sessions keep working; saved servers forget their credentials.
            for (SshConnectionConfig config : configs) config.applyVaultSecrets(null);
        }
        notifyChanged();
    }

    /** Loads the vault copy into {@code config}; true when something was found. */
    public boolean hydrate(SshConnectionConfig config) {
        if (config == null || !config.isSecretStored() || config.getId() == null) return false;
        Map<String, String> fields = SecretFields.decode(secrets.get(SECRET_NAMESPACE, config.getId()));
        if (fields.isEmpty()) return false;
        config.applyVaultSecrets(fields);
        return true;
    }

    /**
     * Copies local credentials into the vault, verifies them and only then rewrites the
     * JSON file without them. A failed vault write leaves the file untouched.
     */
    public synchronized CompletableFuture<Integer> migrateLocalSecrets() {
        if (migrating || secrets.status() != SecretStore.Status.UNLOCKED) {
            return CompletableFuture.completedFuture(0);
        }
        final Map<String, Map<String, String>> snapshot = new HashMap<>();
        final Map<String, String> puts = new LinkedHashMap<>();
        for (SshConnectionConfig config : configs) {
            if (!config.hasLocalSecrets()) continue;
            Map<String, String> fields = SecretFields.clean(config.localSecretFields());
            if (fields.isEmpty()) continue;
            snapshot.put(config.getId(), fields);
            puts.put(config.getId(), SecretFields.encode(fields));
        }
        if (puts.isEmpty()) return CompletableFuture.completedFuture(0);
        migrating = true;
        return secrets.apply(SECRET_NAMESPACE, puts, null)
                .thenApplyAsync(ignored -> stripMigrated(snapshot, puts), callbackExecutor)
                .whenComplete((count, error) -> callbackExecutor.execute(() -> {
                    synchronized (SshConfigStore.this) {
                        migrating = false;
                    }
                    notifyChanged();
                }));
    }

    private synchronized int stripMigrated(Map<String, Map<String, String>> snapshot,
                                           Map<String, String> puts) {
        int moved = 0;
        for (SshConnectionConfig config : configs) {
            Map<String, String> fields = snapshot.get(config.getId());
            if (fields == null || !fields.equals(SecretFields.clean(config.localSecretFields()))) continue;
            if (!puts.get(config.getId()).equals(secrets.get(SECRET_NAMESPACE, config.getId()))) continue;
            config.applyVaultSecrets(fields);
            config.clearLocalSecrets();
            config.setSecretStored(true);
            moved++;
        }
        if (moved > 0) save();
        return moved;
    }

    /** Forgets the vault copy of a server (now or on the next unlock). */
    public synchronized void forgetVaultSecret(String id) {
        if (id == null) return;
        if (secrets.status() == SecretStore.Status.UNLOCKED) {
            secrets.remove(SECRET_NAMESPACE, id).whenComplete((ignored, error) -> {
                if (error != null) {
                    synchronized (SshConfigStore.this) {
                        pendingVaultRemovals.add(id);
                    }
                }
            });
        } else if (secrets.status() != SecretStore.Status.DISABLED) {
            pendingVaultRemovals.add(id);
        }
    }

    private synchronized void flushPendingRemovals() {
        if (pendingVaultRemovals.isEmpty()) return;
        List<String> ids = new ArrayList<>(pendingVaultRemovals);
        pendingVaultRemovals.clear();
        for (SshConnectionConfig config : configs) {
            if (config.isSecretStored()) ids.remove(config.getId());
        }
        if (!ids.isEmpty()) secrets.apply(SECRET_NAMESPACE, null, ids);
    }

    /**
     * Makes sure {@code config} carries its credentials before a connection attempt:
     * from the vault when unlocked, otherwise through the registered resolver (unlock or
     * a password for this session). Returns false when the user declined.
     */
    public static boolean ensureCredentials(SshConnectionConfig config) {
        SshConfigStore store;
        synchronized (SshConfigStore.class) {
            store = instance;
        }
        return store == null || store.ensureCredentialsFor(config);
    }

    boolean ensureCredentialsFor(SshConnectionConfig config) {
        if (config == null || !config.isSecretStored() || config.hasLocalSecrets()
                || config.hasVaultSecretsLoaded()) {
            return true;
        }
        SecretStore.Status status = secrets.status();
        if (status == SecretStore.Status.DISABLED) return true;
        if (status == SecretStore.Status.UNLOCKED) {
            hydrate(config);
            return true;
        }
        LockedCredentialResolver resolver = lockedResolver;
        return resolver != null && resolver.resolve(config, this);
    }

    public synchronized void load() {
        configs.clear();
        boolean migrationNeeded = false;
        if (configFile.exists() && configFile.isFile()) {
            try {
                List<SshConnectionConfig> list = mapper.readValue(configFile, new TypeReference<List<SshConnectionConfig>>() {});
                if (list != null) {
                    for (SshConnectionConfig config : list) {
                        if (config == null) continue;
                        if (config.getId() == null || config.getId().trim().isEmpty()) {
                            config.setId(UUID.randomUUID().toString());
                        }
                        migrationNeeded |= hasLegacySensitiveValues(config);
                        config.normalizeSensitiveValues();
                        configs.add(config);
                    }
                }
            } catch (Exception e) {
                System.err.println("无法读取 SSH 配置: " + e.getMessage());
            }
        }
        if (migrationNeeded) {
            save();
        }
        if (secrets.status() == SecretStore.Status.UNLOCKED) {
            for (SshConnectionConfig config : configs) hydrate(config);
        }
    }

    private static boolean hasLegacySensitiveValues(SshConnectionConfig config) {
        return isLegacy(config.getEncryptedPassword())
                || isLegacy(config.getEncryptedPassphrase())
                || isLegacy(config.getEncryptedKeyContent());
    }

    private static boolean isLegacy(String value) {
        return value != null && !value.isEmpty() && !SshSecurityUtils.isEncrypted(value);
    }

    public synchronized void save() {
        try {
            File parent = configFile.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException("无法创建 SSH 配置目录: " + parent);
            }
            for (SshConnectionConfig config : configs) {
                config.normalizeSensitiveValues();
            }
            byte[] json = mapper.writeValueAsBytes(configs);
            Path target = configFile.toPath();
            Path temporary = target.resolveSibling(configFile.getName() + ".tmp");
            Files.write(temporary, json, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(target);
            notifyChanged();
        } catch (IOException e) {
            System.err.println("无法保存 SSH 配置: " + e.getMessage());
        }
    }

    public void addChangeListener(Runnable listener) {
        if (listener != null && !changeListeners.contains(listener)) changeListeners.add(listener);
    }

    public void removeChangeListener(Runnable listener) {
        changeListeners.remove(listener);
    }

    private void notifyChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows 使用当前用户数据目录的 ACL；POSIX 平台额外收紧到 600。
        }
    }

    public List<SshConnectionConfig> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(configs));
    }

    public Map<String, List<SshConnectionConfig>> getGroupedConfigs() {
        Map<String, List<SshConnectionConfig>> map = new LinkedHashMap<>();
        for (SshConnectionConfig cfg : configs) {
            String grp = cfg.getGroup();
            map.computeIfAbsent(grp, k -> new ArrayList<>()).add(cfg);
        }
        return map;
    }

    public SshConnectionConfig findById(String id) {
        if (id == null) return null;
        for (SshConnectionConfig cfg : configs) {
            if (id.equals(cfg.getId())) {
                return cfg;
            }
        }
        return null;
    }

    public synchronized void addOrUpdate(SshConnectionConfig config) {
        if (config == null) return;
        int idx = -1;
        for (int i = 0; i < configs.size(); i++) {
            if (configs.get(i).getId().equals(config.getId())) {
                idx = i;
                break;
            }
        }
        SshConnectionConfig previous = idx >= 0 ? configs.get(idx) : null;
        if (idx >= 0) {
            configs.set(idx, config);
        } else {
            configs.add(config);
        }
        save();
        if (previous != null && previous.isSecretStored() && !config.isSecretStored()) {
            forgetVaultSecret(config.getId());
        }
        // New credentials typed while the vault is open go straight into it.
        if (config.hasLocalSecrets()) migrateLocalSecrets();
    }

    public synchronized boolean delete(String id) {
        if (id == null) return false;
        SshConnectionConfig existing = findById(id);
        boolean removed = configs.removeIf(cfg -> id.equals(cfg.getId()));
        if (removed) {
            save();
            if (existing != null && existing.isSecretStored()) forgetVaultSecret(id);
        }
        return removed;
    }
}
