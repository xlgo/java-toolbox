package com.aqishi.toolbox.feature.data.domain;

/**
 * Persisted JDBC connection profile. The mutable public fields preserve the
 * previous JSON schema used by {@code db_profiles}.
 */
public class DatabaseProfile {
    public String name;
    public String dbType;
    public String host;
    public String port;
    public String database;
    public String username;
    public String password;
    public String driverClass;
    public String url;
    public String jarPath;

    public DatabaseProfile() {
    }

    public DatabaseProfile(String name, String dbType, String host, String port,
                           String database, String username, String password,
                           String driverClass, String url, String jarPath) {
        this.name = name;
        this.dbType = dbType;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.driverClass = driverClass;
        this.url = url;
        this.jarPath = jarPath;
    }
}
