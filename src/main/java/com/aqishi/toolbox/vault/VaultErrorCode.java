package com.aqishi.toolbox.vault;

/** Stable failure categories shared by vault layers and UI error mapping. */
public enum VaultErrorCode {
    AUTHENTICATION_FAILED,
    UNSUPPORTED_FORMAT,
    INVALID_ENVELOPE,
    FILE_TOO_LARGE,
    READ_FAILED,
    WRITE_FAILED,
    READ_ONLY,
    MIGRATION_FAILED,
    BUSY
}
