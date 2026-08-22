package com.aqishi.toolbox.infra.database;

import com.aqishi.toolbox.infra.ManagedResource;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Lifecycle adapter for a JDBC connection.
 *
 * <p>The creating application service owns this resource. Closing it is safe
 * from a finally block and never leaks a vendor-specific checked exception to
 * a Swing adapter.</p>
 */
public final class JdbcConnectionResource implements ManagedResource {
    private Connection connection;
    private AutoCloseable closeHook;

    /**
     * Wraps an already-open JDBC connection.
     *
     * @param connection connection to own
     */
    public JdbcConnectionResource(Connection connection) {
        this(connection, null);
    }

    /**
     * Wraps a connection and an optional companion resource such as an
     * external driver class loader. The companion is released after JDBC
     * closes, preserving driver availability for the connection lifetime.
     */
    public JdbcConnectionResource(Connection connection, AutoCloseable closeHook) {
        if (connection == null) {
            throw new NullPointerException("connection");
        }
        this.connection = connection;
        this.closeHook = closeHook;
    }

    /**
     * Returns the owned JDBC connection.
     *
     * @return connection
     * @throws IllegalStateException when this resource is closed
     */
    public synchronized Connection connection() {
        if (!isOpen()) {
            throw new IllegalStateException("JDBC connection is closed");
        }
        return connection;
    }

    @Override
    public synchronized boolean isOpen() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException ignored) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // close() is deliberately best-effort at the lifecycle boundary
        } finally {
            connection = null;
            if (closeHook != null) {
                try {
                    closeHook.close();
                } catch (Exception ignored) {
                    // Companion cleanup is also best-effort at this boundary.
                } finally {
                    closeHook = null;
                }
            }
        }
    }
}
