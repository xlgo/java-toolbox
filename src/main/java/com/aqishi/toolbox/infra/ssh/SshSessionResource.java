package com.aqishi.toolbox.infra.ssh;

import com.aqishi.toolbox.infra.ManagedResource;
import com.jcraft.jsch.Session;

/**
 * Lifecycle adapter for a JSch SSH session.
 *
 * <p>The session is normally owned by a background connection service. The
 * adapter only manages session shutdown and does not close channels created by
 * callers.</p>
 */
public final class SshSessionResource implements ManagedResource {
    private Session session;

    /**
     * Wraps an SSH session.
     *
     * @param session session to own
     */
    public SshSessionResource(Session session) {
        if (session == null) {
            throw new NullPointerException("session");
        }
        this.session = session;
    }

    /**
     * Returns the wrapped SSH session.
     *
     * @return session
     * @throws IllegalStateException when this resource is closed
     */
    public synchronized Session session() {
        if (session == null) {
            throw new IllegalStateException("SSH session is closed");
        }
        return session;
    }

    @Override
    public synchronized boolean isOpen() {
        return session != null && session.isConnected();
    }

    @Override
    public synchronized void close() {
        if (session != null) {
            session.disconnect();
            session = null;
        }
    }
}
