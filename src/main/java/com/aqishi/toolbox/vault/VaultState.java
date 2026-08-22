package com.aqishi.toolbox.vault;

/**
 * 保险库生命周期状态。
 *
 * <p>典型迁移为 {@code LOCKED -> UNLOCKING -> UNLOCKED -> SAVING -> UNLOCKED}；
 * 首次发现旧数据时进入 {@code MIGRATION_REQUIRED}，读写或锁文件异常时进入
 * {@code ERROR_READ_ONLY}。具体失败路径由 {@link VaultService} 负责收敛。</p>
 */
public enum VaultState {
    LOCKED,
    MIGRATION_REQUIRED,
    UNLOCKING,
    UNLOCKED,
    SAVING,
    ERROR_READ_ONLY
}
