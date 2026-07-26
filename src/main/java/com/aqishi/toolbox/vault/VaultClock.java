package com.aqishi.toolbox.vault;

public interface VaultClock {
    long currentTimeMillis();

    static VaultClock system() {
        return System::currentTimeMillis;
    }
}
