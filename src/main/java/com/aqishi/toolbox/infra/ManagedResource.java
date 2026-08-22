package com.aqishi.toolbox.infra;

/**
 * Common lifecycle contract for an infrastructure resource owned by the
 * application.
 *
 * <p>Implementations are expected to be owned by the thread or service that
 * created them. {@link #close()} is idempotent, does not throw checked
 * exceptions, and releases listeners, sockets, executors, and client handles
 * owned by the resource.</p>
 */
public interface ManagedResource extends AutoCloseable {

    /**
     * Returns whether the underlying resource can still be used.
     *
     * @return {@code true} while the resource is open
     */
    boolean isOpen();

    /**
     * Closes the resource. Repeated calls have no effect.
     */
    @Override
    void close();
}
