package com.aqishi.toolbox.infra.database;

import com.aqishi.toolbox.infra.InfrastructureException;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Opens JDBC connections using bundled or user-supplied driver jars.
 *
 * <p>SSH forwarding is intentionally outside this class: callers provide the
 * final JDBC URL after resolving their transport. That keeps the factory
 * usable for direct, tunneled, and future proxy connections alike.</p>
 */
public final class DatabaseConnectionFactory {

    public JdbcConnectionResource open(String jdbcUrl, String username, String password,
                                       String driverClass, String driverJarPath) throws Exception {
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "JDBC URL 不能为空");
        }
        String finalUrl = jdbcUrl.trim();
        if (driverJarPath != null && !driverJarPath.trim().isEmpty()) {
            return openWithExternalDriver(finalUrl, username, password, driverClass, driverJarPath);
        }
        if (driverClass != null && !driverClass.trim().isEmpty()) {
            try {
                Class.forName(driverClass.trim());
            } catch (ClassNotFoundException ignored) {
                // DriverManager may still resolve a service-loaded driver.
            }
        }
        return new JdbcConnectionResource(
                DriverManager.getConnection(finalUrl, username, password));
    }

    private JdbcConnectionResource openWithExternalDriver(String jdbcUrl, String username,
                                                          String password, String driverClass,
                                                          String driverJarPath) throws Exception {
        if (driverClass == null || driverClass.trim().isEmpty()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "使用外部 JDBC 驱动时必须提供驱动类名");
        }
        File jarFile = new File(driverJarPath.trim());
        if (!jarFile.isFile()) {
            throw new InfrastructureException(InfrastructureException.Kind.CONFIGURATION,
                    "未找到驱动 JAR 文件：" + driverJarPath);
        }
        URL[] urls = new URL[]{jarFile.toURI().toURL()};
        URLClassLoader loader = new URLClassLoader(urls, DatabaseConnectionFactory.class.getClassLoader());
        try {
            Class<?> driverType = Class.forName(driverClass.trim(), true, loader);
            Driver driver = (Driver) driverType.getDeclaredConstructor().newInstance();
            Properties properties = new Properties();
            if (username != null && !username.isEmpty()) properties.setProperty("user", username);
            if (password != null && !password.isEmpty()) properties.setProperty("password", password);
            Connection connection = driver.connect(jdbcUrl, properties);
            if (connection == null) {
                throw new SQLException("驱动程序未接受此 JDBC URL，请检查格式是否匹配。");
            }
            return new JdbcConnectionResource(connection, loader);
        } catch (Exception error) {
            try {
                loader.close();
            } catch (Exception ignored) {
            }
            throw error;
        }
    }
}
