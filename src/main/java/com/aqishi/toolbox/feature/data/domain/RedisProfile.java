package com.aqishi.toolbox.feature.data.domain;

/**
 * Persisted Redis connection profile. Field names preserve the existing
 * {@code redis_profiles} payload consumed by earlier releases.
 */
public class RedisProfile {
    public String name;
    public String host;
    public int port;
    public String password;
    public int db;

    public RedisProfile() {
    }

    public RedisProfile(String name, String host, int port, String password, int db) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.password = password;
        this.db = db;
    }
}
