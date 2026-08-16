package com.aqishi.toolbox.catalog;

import com.aqishi.toolbox.vault.SecureClipboard;
import com.aqishi.toolbox.vault.VaultService;

import java.util.Objects;

/**
 * 创建工具面板时注入的共享依赖。配置仍由现有 ConfigManager 统一管理。
 */
public final class ToolboxContext {

    private final VaultService vaultService;
    private final SecureClipboard secureClipboard;

    public ToolboxContext(VaultService vaultService, SecureClipboard secureClipboard) {
        this.vaultService = Objects.requireNonNull(vaultService, "vaultService");
        this.secureClipboard = Objects.requireNonNull(secureClipboard, "secureClipboard");
    }

    public VaultService getVaultService() {
        return vaultService;
    }

    public SecureClipboard getSecureClipboard() {
        return secureClipboard;
    }
}
