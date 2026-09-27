package com.aqishi.toolbox.feature.monitor.domain;

import java.util.Arrays;

/**
 * Everything one end needs to run the secure channel handshake.
 *
 * <p>The IDs are the signaling IDs of the two ends (the controller's own ID and
 * the host's ID); they are bound into the handshake transcript. The password
 * array is owned by this object: {@link #destroy()} wipes it when the
 * connection attempt is over.</p>
 */
public final class SecureSessionConfig {

    public static final long DEFAULT_HANDSHAKE_TIMEOUT_MS = 10_000L;

    private final SecureChannelHandshake.Role role;
    private final String controllerId;
    private final String hostId;
    private final char[] password;
    private final long handshakeTimeoutMillis;

    public SecureSessionConfig(SecureChannelHandshake.Role role, String controllerId, String hostId,
                               char[] password) {
        this(role, controllerId, hostId, password, DEFAULT_HANDSHAKE_TIMEOUT_MS);
    }

    public SecureSessionConfig(SecureChannelHandshake.Role role, String controllerId, String hostId,
                               char[] password, long handshakeTimeoutMillis) {
        if (role == null || controllerId == null || hostId == null) {
            throw new IllegalArgumentException("role and both session ids are required");
        }
        this.role = role;
        this.controllerId = controllerId;
        this.hostId = hostId;
        this.password = password == null ? new char[0] : password.clone();
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    }

    public SecureChannelHandshake.Role role() {
        return role;
    }

    public String controllerId() {
        return controllerId;
    }

    public String hostId() {
        return hostId;
    }

    public boolean hasPassword() {
        synchronized (password) {
            for (char c : password) {
                if (c != '\0') return true;
            }
            return false;
        }
    }

    public long handshakeTimeoutMillis() {
        return handshakeTimeoutMillis;
    }

    /** Creates a fresh handshake for one transport attempt. */
    public SecureChannelHandshake newHandshake(java.security.SecureRandom random) {
        synchronized (password) {
            char[] copy = password.length == 0 || !hasPassword() ? null : password.clone();
            try {
                return new SecureChannelHandshake(role, controllerId, hostId, copy, random);
            } finally {
                if (copy != null) Arrays.fill(copy, '\0');
            }
        }
    }

    /** Wipes the password; later handshakes run as if none was configured. */
    public void destroy() {
        synchronized (password) {
            Arrays.fill(password, '\0');
        }
    }
}
