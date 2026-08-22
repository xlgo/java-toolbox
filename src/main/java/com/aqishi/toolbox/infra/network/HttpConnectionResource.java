package com.aqishi.toolbox.infra.network;

import com.aqishi.toolbox.infra.ManagedResource;

import java.net.HttpURLConnection;

/**
 * Lifecycle adapter for a JDK HTTP connection.
 *
 * <p>The owning request service owns the connection and should close the
 * response stream separately when one was opened.</p>
 */
public final class HttpConnectionResource implements ManagedResource {
    private HttpURLConnection connection;

    /**
     * Wraps an HTTP connection.
     *
     * @param connection connection to own
     */
    public HttpConnectionResource(HttpURLConnection connection) {
        if (connection == null) {
            throw new NullPointerException("connection");
        }
        this.connection = connection;
    }

    /**
     * Returns the wrapped connection.
     *
     * @return HTTP connection
     * @throws IllegalStateException when this resource is closed
     */
    public synchronized HttpURLConnection connection() {
        if (connection == null) {
            throw new IllegalStateException("HTTP connection is closed");
        }
        return connection;
    }

    @Override
    public synchronized boolean isOpen() {
        return connection != null;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }
}
