package com.aqishi.toolbox.misc.ssh.session;

import java.io.IOException;
import java.net.ServerSocket;

/**
 * 本地端口探测与冲突避让工具类
 */
public final class PortUtils {

    private PortUtils() {
    }

    /**
     * 寻找可用本地端口。如果首选端口被占用或为 0，则自动选择系统分配的空闲端口。
     *
     * @param preferredPort 首选尝试端口
     * @return 最终分配的可用本地端口
     */
    public static synchronized int findAvailablePort(int preferredPort) {
        if (preferredPort > 0 && isPortAvailable(preferredPort)) {
            return preferredPort;
        }
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            // 兜底返回随机端口段
            return 10000 + (int) (Math.random() * 50000);
        }
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
