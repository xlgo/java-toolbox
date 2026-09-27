package com.aqishi.toolbox.infra.secrets;

import com.aqishi.toolbox.util.Errors;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 单个 API 令牌一类的设置项：值存进保险库，旧版写在偏好里的明文只读、并在解锁后迁走。
 *
 * <p>连接配置有 {@link ProfileSecretManager} 管理，但像二维码工具的 Replicate token 这种
 * "一个工具一个密钥"的设置，没有配置列表可挂。这里只做三件事：</p>
 * <ul>
 *   <li>{@link #current()}：保险库解锁时取保险库里的值，否则退回旧明文（仅读，不再写）；</li>
 *   <li>{@link #remember}：保险库解锁时写入保险库；否则只在本次会话有效，绝不写回明文；</li>
 *   <li>{@link #migrate()}：解锁后把旧明文搬进保险库，确认写入成功后才从偏好里删除。</li>
 * </ul>
 */
public final class VaultTokenSetting {

    private final SecretStore store;
    private final Preferences preferences;
    private final String legacyKey;
    private final String namespace;
    private final String id;

    public VaultTokenSetting(SecretStore store, Preferences preferences, String legacyKey,
                             String namespace, String id) {
        this.store = Objects.requireNonNull(store, "store");
        this.preferences = Objects.requireNonNull(preferences, "preferences");
        this.legacyKey = Objects.requireNonNull(legacyKey, "legacyKey");
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.id = Objects.requireNonNull(id, "id");
    }

    public SecretStore store() {
        return store;
    }

    /** 当前可用的值；都没有时返回空串。 */
    public String current() {
        if (store.status() == SecretStore.Status.UNLOCKED) {
            String stored = store.get(namespace, id);
            if (stored != null && !stored.isEmpty()) {
                return stored;
            }
        }
        return legacyPlaintext();
    }

    /** 偏好里还留着旧版明文吗（界面据此提示用户解锁以完成迁移）。 */
    public boolean hasLegacyPlaintext() {
        return !legacyPlaintext().isEmpty();
    }

    /** 值能否被持久保存：只有保险库解锁时才能。 */
    public boolean persistent() {
        return store.status() == SecretStore.Status.UNLOCKED;
    }

    /**
     * 记住用户输入的值。保险库未解锁时不保存；若偏好里的旧明文与新值不同，
     * 说明它已经过期，顺手删掉，免得解锁后把过期令牌迁进保险库。
     */
    public CompletableFuture<Void> remember(String value) {
        String token = value == null ? "" : value;
        if (store.status() != SecretStore.Status.UNLOCKED) {
            if (hasLegacyPlaintext() && !legacyPlaintext().equals(token)) {
                removeLegacy();
            }
            return CompletableFuture.completedFuture(null);
        }
        if (token.equals(store.get(namespace, id))) {
            if (hasLegacyPlaintext()) removeLegacy();
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> write = token.isEmpty() ? store.remove(namespace, id) : store.put(namespace, id, token);
        return write.thenRun(() -> {
            if (hasLegacyPlaintext()) removeLegacy();
        });
    }

    /**
     * 把旧明文迁进保险库。保险库里已有值时以保险库为准（它更新），只删旧明文。
     * 写入失败时偏好保持原样，下次解锁再试。
     */
    public CompletableFuture<Boolean> migrate() {
        String legacy = legacyPlaintext();
        if (legacy.isEmpty() || store.status() != SecretStore.Status.UNLOCKED) {
            return CompletableFuture.completedFuture(false);
        }
        String stored = store.get(namespace, id);
        if (stored != null && !stored.isEmpty()) {
            removeLegacy();
            return CompletableFuture.completedFuture(true);
        }
        return store.put(namespace, id, legacy).thenApply(ignored -> {
            if (legacy.equals(store.get(namespace, id))) {
                removeLegacy();
                return true;
            }
            return false;
        });
    }

    private String legacyPlaintext() {
        String value = preferences.get(legacyKey, "");
        return value == null ? "" : value;
    }

    private void removeLegacy() {
        preferences.remove(legacyKey);
        try {
            preferences.flush();
        } catch (BackingStoreException error) {
            // 删除已在内存里生效，下次偏好落盘时会一起写出；不影响令牌本身的使用
            Errors.ignored("Unable to flush preferences after removing a plaintext token", error);
        }
    }
}
