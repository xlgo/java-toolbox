package com.aqishi.toolbox.feature.network.ssh.infra;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.SecureRandom;

/**
 * 本地端口探测与冲突避让工具类
 */
public final class PortUtils {

    private static final int EPHEMERAL_LOW = 49152;
    private static final int EPHEMERAL_SPAN = 65535 - EPHEMERAL_LOW;
    private static final int FALLBACK_ATTEMPTS = 64;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PortUtils() {
    }

    /**
     * 寻找可用本地端口。如果首选端口被占用或为 0，则自动选择系统分配的空闲端口。
     *
     * @param preferredPort 首选尝试端口
     * @return 最终分配的可用本地端口；0 表示无法分配
     */
    public static synchronized int findAvailablePort(int preferredPort) {
        if (preferredPort > 0 && isPortAvailable(preferredPort)) {
            return preferredPort;
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException ignored) {
            // Fall through to probing the ephemeral range directly.
        }
        // A forwarded local port is reachable by anything on the box, so the
        // fallback is drawn unpredictably and verified free before use.
        int offset = RANDOM.nextInt(EPHEMERAL_SPAN);
        for (int i = 0; i < FALLBACK_ATTEMPTS; i++) {
            int candidate = EPHEMERAL_LOW + ((offset + i) % EPHEMERAL_SPAN);
            if (isPortAvailable(candidate)) {
                return candidate;
            }
        }
        return 0;
    }

    /**
     * 检测本地端口是否可用
     */
    public static boolean isPortAvailable(int port) {
        if (port <= 0 || port > 65535) {
            return false;
        }
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
