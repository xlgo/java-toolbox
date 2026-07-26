package com.aqishi.toolbox.vault;

public enum VaultState {
    LOCKED,
    MIGRATION_REQUIRED,
    UNLOCKING,
    UNLOCKED,
    SAVING,
    ERROR_READ_ONLY
}
