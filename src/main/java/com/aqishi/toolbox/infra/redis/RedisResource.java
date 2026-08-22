package com.aqishi.toolbox.infra.redis;

import com.aqishi.toolbox.infra.ManagedResource;
import redis.clients.jedis.Jedis;

/**
 * Lifecycle adapter for a Jedis connection.
 *
 * <p>The owning application service is responsible for thread confinement;
 * Jedis instances should not be shared between concurrent operations.</p>
 */
public final class RedisResource implements ManagedResource {
    private Jedis jedis;

    /**
     * Wraps a Jedis instance.
     *
     * @param jedis client to own
     */
    public RedisResource(Jedis jedis) {
        if (jedis == null) {
            throw new NullPointerException("jedis");
        }
        this.jedis = jedis;
    }

    /**
     * Returns the owned Jedis client.
     *
     * @return Jedis client
     * @throws IllegalStateException when this resource is closed
     */
    public synchronized Jedis client() {
        if (!isOpen()) {
            throw new IllegalStateException("Redis connection is closed");
        }
        return jedis;
    }

    @Override
    public synchronized boolean isOpen() {
        return jedis != null && jedis.isConnected();
    }

    @Override
    public synchronized void close() {
        if (jedis == null) {
            return;
        }
        try {
            jedis.close();
        } finally {
            jedis = null;
        }
    }
}
