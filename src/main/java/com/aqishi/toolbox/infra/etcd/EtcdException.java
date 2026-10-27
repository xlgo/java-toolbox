package com.aqishi.toolbox.infra.etcd;

import java.io.IOException;

public final class EtcdException extends IOException {
    private final int status;
    private final int code;
    public EtcdException(int status, int code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public int code() { return code; }
    public boolean missingKey() { return status == 404 && code == 100; }
    public boolean conflict() { return code == 101 || code == 105; }
}
