package com.aqishi.toolbox.infra.redis;

import com.aqishi.toolbox.infra.InfrastructureException;
import redis.clients.jedis.Jedis;

/**
 * Creates configured Redis resources without exposing Jedis construction to a
 * Swing panel. SSH forwarding, when requested, is resolved by the caller and
 * supplied here as the final host and port.
 */
public final class RedisClient {

    public RedisResource open(String host, int port, int timeoutMillis,
                              String password, int database) {
        if (host == null || host.trim().isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "Redis 主机不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "Redis 端口无效");
        }
        Jedis client = new Jedis(host, port, timeoutMillis);
        try {
            if (password != null && !password.isEmpty()) client.auth(password);
            client.select(database);
            return new RedisResource(client);
        } catch (RuntimeException error) {
            client.close();
            throw new InfrastructureException(InfrastructureException.Kind.CONNECTION,
                    error.getMessage(), error);
        }
    }
}
