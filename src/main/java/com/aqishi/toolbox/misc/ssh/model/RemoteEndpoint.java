package com.aqishi.toolbox.misc.ssh.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parses the host:port values used by SSH-backed service tools.
 * Bracketed IPv6 addresses are supported; an unbracketed IPv6 value uses the default port.
 */
public final class RemoteEndpoint {

    private final String host;
    private final int port;

    public RemoteEndpoint(String host, int port) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("远程主机地址不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("远程端口超出范围: " + port);
        }
        this.host = host.trim();
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String format() {
        return host.indexOf(':') >= 0 && !host.startsWith("[")
                ? "[" + host + "]:" + port
                : host + ":" + port;
    }

    public static RemoteEndpoint parse(String raw, int defaultPort) {
        if (defaultPort < 1 || defaultPort > 65535) {
            throw new IllegalArgumentException("默认端口超出范围: " + defaultPort);
        }
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("远程地址不能为空");
        }

        String host;
        String portText = String.valueOf(defaultPort);
        if (value.startsWith("[")) {
            int closing = value.indexOf(']');
            if (closing <= 1) {
                throw new IllegalArgumentException("远程地址格式不正确: " + raw);
            }
            host = value.substring(1, closing);
            if (value.length() > closing + 1) {
                if (value.charAt(closing + 1) != ':' || value.length() == closing + 2) {
                    throw new IllegalArgumentException("远程地址格式不正确: " + raw);
                }
                portText = value.substring(closing + 2);
            }
        } else {
            int firstColon = value.indexOf(':');
            int lastColon = value.lastIndexOf(':');
            if (firstColon > 0 && firstColon == lastColon) {
                host = value.substring(0, lastColon);
                portText = value.substring(lastColon + 1);
                if (portText.trim().isEmpty()) {
                    throw new IllegalArgumentException("远程端口不能为空: " + raw);
                }
            } else {
                // An unbracketed IPv6 literal is accepted as a host with the default port.
                host = value;
            }
        }

        int port;
        try {
            port = Integer.parseInt(portText.trim());
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("远程端口格式不正确: " + raw, error);
        }
        return new RemoteEndpoint(host, port);
    }

    public static List<RemoteEndpoint> parseList(String raw, int defaultPort) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("远程地址不能为空");
        }
        List<RemoteEndpoint> result = new ArrayList<>();
        for (String item : raw.split(",")) {
            if (!item.trim().isEmpty()) {
                result.add(parse(item, defaultPort));
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("远程地址不能为空");
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public String toString() {
        return format();
    }
}
