package com.aqishi.toolbox.vault;

/** Checked vault failure with a stable category and retry guidance. */
public class VaultException extends Exception {
    private final VaultErrorCode code;
    private final boolean retryable;

    public VaultException(VaultErrorCode code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public VaultException(VaultErrorCode code, String message, boolean retryable,
                          Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    public VaultErrorCode getCode() {
        return code;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
