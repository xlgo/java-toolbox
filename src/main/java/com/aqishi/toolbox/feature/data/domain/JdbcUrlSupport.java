package com.aqishi.toolbox.feature.data.domain;

/**
 * JDBC URL 的纯组装与改写逻辑：按数据库类型拼连接串、解析端口、
 * 把 URL 里的远端主机改写成 SSH 隧道的本地端口。
 */
public final class JdbcUrlSupport {

    private JdbcUrlSupport() {
    }

    /**
     * 按数据库类型拼装 JDBC URL；{@code Custom} 或未知类型返回 {@code null}，
     * 表示不应覆盖用户手工输入的 URL。
     */
    public static String buildJdbcUrl(String type, String host, String port, String database) {
        if (type == null || "Custom".equals(type)) {
            return null;
        }
        switch (type) {
            case "MySQL":
                return "jdbc:mysql://" + host + ":" + port + "/" + database
                        + "?useSSL=false&serverTimezone=UTC&characterEncoding=utf-8";
            case "PostgreSQL":
                return "jdbc:postgresql://" + host + ":" + port + "/" + database;
            case "Oracle":
                return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
            default:
                return null;
        }
    }

    /** 解析端口文本，非 1-65535 的输入一律拒绝。 */
    public static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value == null ? "" : value.trim());
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("端口格式不正确");
    }

    /**
     * 把 JDBC URL 中的远端主机：端口替换为隧道本地地址。能解析出 authority 段时
     * 只改写主机部分（保留 userinfo 与路径）；解析不了退化为字符串替换。
     */
    public static String replaceJdbcEndpoint(String url, String remoteHost,
                                             int remotePort, int localPort) {
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return url.replace(remoteHost + ":" + remotePort,
                    "127.0.0.1:" + localPort);
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = url.length();
        for (char delimiter : new char[]{'/', '?', '#'}) {
            int index = url.indexOf(delimiter, authorityStart);
            if (index >= 0) {
                authorityEnd = Math.min(authorityEnd, index);
            }
        }
        String authority = url.substring(authorityStart, authorityEnd);
        int userInfoEnd = authority.lastIndexOf('@');
        String userInfo = userInfoEnd >= 0 ? authority.substring(0, userInfoEnd + 1) : "";
        String hostPort = userInfoEnd >= 0 ? authority.substring(userInfoEnd + 1) : authority;
        String expectedHost = remoteHost;
        if (expectedHost.indexOf(':') >= 0 && !expectedHost.startsWith("[")) {
            expectedHost = "[" + expectedHost + "]";
        }
        if (!hostPort.startsWith(expectedHost)) {
            return url.replace(remoteHost + ":" + remotePort,
                    "127.0.0.1:" + localPort);
        }
        String replacement = userInfo + "127.0.0.1:" + localPort;
        return url.substring(0, authorityStart) + replacement
                + url.substring(authorityEnd);
    }
}
