package com.aqishi.toolbox.misc.ssh.session;

import com.aqishi.toolbox.misc.ssh.model.SshConfigStore;
import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.model.SshTunnelConfig;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一 SSH 隧道桥接调度器：为 Redis, 数据库, Kafka 等工具提供内网代理访问
 */
public final class SshTunnelBridge {

    private static final Map<String, SshSessionInstance> activeSessions = new ConcurrentHashMap<>();
    private static final Map<String, SharedBridge> activeBridges = new ConcurrentHashMap<>();
    private static final Set<SshSessionInstance> bridgeOwnedSessions = ConcurrentHashMap.newKeySet();

    private SshTunnelBridge() {
    }

    public static class BridgeResult {
        private final SharedBridge bridge;
        private boolean closed;

        private BridgeResult(SharedBridge bridge) {
            this.bridge = bridge;
        }

        public String getLocalHost() {
            return "127.0.0.1";
        }

        public int getLocalPort() {
            SshTunnelConfig tunnel = bridge.tunnelConfig;
            return tunnel.getStatus() == SshTunnelConfig.Status.RUNNING
                    ? tunnel.getAssignedLocalPort() : 0;
        }

        public SshSessionInstance getSessionInstance() {
            return bridge.sessionInstance;
        }

        public SshTunnelConfig getTunnelConfig() {
            return bridge.tunnelConfig;
        }

        public synchronized void close() {
            if (closed) return;
            closed = true;
            release(bridge);
        }
    }

    private static final class SharedBridge {
        private final String cacheKey;
        private final SshSessionInstance sessionInstance;
        private final SshTunnelConfig tunnelConfig;
        private int leases = 1;
        private boolean active = true;

        private SharedBridge(String cacheKey, SshSessionInstance sessionInstance,
                             SshTunnelConfig tunnelConfig) {
            this.cacheKey = cacheKey;
            this.sessionInstance = sessionInstance;
            this.tunnelConfig = tunnelConfig;
        }

        private boolean acquire() {
            if (!active) return false;
            leases++;
            return true;
        }
    }

    public static void register(SshSessionInstance session) {
        if (session != null && session.getConfig() != null) {
            activeSessions.put(session.getConfig().getId(), session);
        }
    }

    public static void unregister(SshSessionInstance session) {
        if (session == null || session.getConfig() == null) return;
        activeSessions.remove(session.getConfig().getId(), session);
    }

    /** Releases bridge tunnels and sessions created by service panels during application shutdown. */
    public static synchronized void shutdown() {
        java.util.List<SharedBridge> bridges = new java.util.ArrayList<>(activeBridges.values());
        activeBridges.clear();
        for (SharedBridge bridge : bridges) {
            if (!bridge.active) continue;
            bridge.active = false;
            bridge.leases = 0;
            bridge.sessionInstance.forgetTunnel(bridge.tunnelConfig);
        }

        java.util.Set<SshSessionInstance> owned = new java.util.HashSet<>(bridgeOwnedSessions);
        bridgeOwnedSessions.clear();
        for (SshSessionInstance session : owned) {
            if (session.getConfig() != null) {
                activeSessions.remove(session.getConfig().getId(), session);
            }
            session.close();
        }
    }

    /**
     * 通过指定的 SSH 服务器节点，为目标远程主机和端口建立本地隧道桥接
     */
    public static synchronized BridgeResult bridge(String sshConfigId, String remoteHost, int remotePort) throws Exception {
        if (sshConfigId == null || sshConfigId.trim().isEmpty()) {
            throw new IllegalArgumentException("请选择用于隧道的 SSH 服务器配置");
        }
        if (remoteHost == null || remoteHost.trim().isEmpty()) {
            throw new IllegalArgumentException("远程服务地址不能为空");
        }
        if (remotePort < 1 || remotePort > 65535) {
            throw new IllegalArgumentException("远程服务端口超出范围: " + remotePort);
        }
        remoteHost = remoteHost.trim();

        SshConnectionConfig sshConfig = SshConfigStore.getInstance().findById(sshConfigId);
        if (sshConfig == null) {
            throw new IllegalArgumentException("找不到指定的 SSH 服务器配置 (ID: " + sshConfigId + ")");
        }

        String cacheKey = sshConfig.getId() + "\u0000" + remoteHost + "\u0000" + remotePort;
        SharedBridge cached = activeBridges.get(cacheKey);
        if (cached != null && cached.acquire()) {
            try {
                SshSessionInstance cachedSession = cached.sessionInstance;
                if (!cachedSession.isConnected() && !cachedSession.connectSync()) {
                    throw new IllegalStateException("无法恢复 SSH 连接: " + cachedSession.getLastErrorMessage());
                }
                if (cachedSession.isConnected()
                        && cached.tunnelConfig.getStatus() == SshTunnelConfig.Status.RUNNING
                        && cached.tunnelConfig.getAssignedLocalPort() > 0) {
                    cachedSession.verifyTunnelTarget(cached.tunnelConfig);
                    return new BridgeResult(cached);
                }
                if (cachedSession.isConnected() && cachedSession.startTunnel(cached.tunnelConfig)) {
                    cachedSession.verifyTunnelTarget(cached.tunnelConfig);
                    return new BridgeResult(cached);
                }
                throw new IllegalStateException("端口转发失败: " + cached.tunnelConfig.getErrorMessage());
            } catch (Exception error) {
                release(cached);
                throw error;
            }
        } else if (cached != null) {
            activeBridges.remove(cacheKey, cached);
        }

        SshSessionInstance session = activeSessions.get(sshConfig.getId());
        boolean createdSession = false;
        if (session == null) {
            SshSessionInstance candidate = new SshSessionInstance(sshConfig);
            activeSessions.put(sshConfig.getId(), candidate);
            bridgeOwnedSessions.add(candidate);
            session = candidate;
            createdSession = true;
        }

        SshTunnelConfig tunnel = null;
        try {
            if (!session.isConnected()) {
                boolean ok = session.connectSync();
                if (!ok) {
                    throw new IllegalStateException("无法建立 SSH 连接: " + session.getLastErrorMessage());
                }
            }

            tunnel = new SshTunnelConfig();
            tunnel.setName("BridgeTo-" + remoteHost + ":" + remotePort);
            tunnel.setRemoteHost(remoteHost);
            tunnel.setRemotePort(remotePort);
            // Kafka commonly advertises the service port in its broker
            // metadata. Prefer the same local port when available; the
            // normal automatic fallback still handles local port conflicts.
            tunnel.setPreferredLocalPort(remotePort);
            // A bridge is an active service connection and should be restored after SSH recovery.
            tunnel.setAutoStart(true);

            boolean tunnelOk = session.startTunnel(tunnel);
            if (!tunnelOk) {
                throw new IllegalStateException("端口转发失败: " + tunnel.getErrorMessage());
            }
            session.verifyTunnelTarget(tunnel);

            SharedBridge result = new SharedBridge(cacheKey, session, tunnel);
            activeBridges.put(cacheKey, result);
            return new BridgeResult(result);
        } catch (Exception error) {
            if (tunnel != null) session.forgetTunnel(tunnel);
            if (createdSession) {
                bridgeOwnedSessions.remove(session);
                activeSessions.remove(sshConfig.getId(), session);
                session.close();
            }
            throw error;
        }
    }

    private static synchronized void release(SharedBridge bridge) {
        if (bridge == null || !bridge.active) return;
        bridge.leases--;
        if (bridge.leases > 0) return;

        bridge.active = false;
        activeBridges.remove(bridge.cacheKey, bridge);
        bridge.sessionInstance.forgetTunnel(bridge.tunnelConfig);

        if (bridgeOwnedSessions.contains(bridge.sessionInstance)
                && !hasActiveBridgeForSession(bridge.sessionInstance)) {
            bridgeOwnedSessions.remove(bridge.sessionInstance);
            String id = bridge.sessionInstance.getConfig().getId();
            activeSessions.remove(id, bridge.sessionInstance);
            bridge.sessionInstance.close();
        }
    }

    private static boolean hasActiveBridgeForSession(SshSessionInstance session) {
        for (SharedBridge activeBridge : activeBridges.values()) {
            if (activeBridge.sessionInstance == session && activeBridge.active) return true;
        }
        return false;
    }
}
