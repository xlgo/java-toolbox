package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.domain.SshHostKeyPrompt;
import com.aqishi.toolbox.feature.network.ssh.domain.SshTunnelConfig;

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

    /**
     * 隧道自建 SSH 会话时使用的主机指纹确认方式。
     *
     * <p>早先隧道会话一律用 {@link SshHostKeyPrompt#denyAll()}：只通过数据库 / Redis / Kafka
     * 等工具走隧道、从没在 SSH 终端里连过这台主机的用户，首次连接必然失败，也没有机会确认指纹。
     * 现在由界面层在启动时注入与 SSH 终端相同的确认对话框；默认仍是拒绝，
     * 没有界面可问时宁可失败，也不静默信任未知主机。已记录的主机密钥变更照样被 known_hosts 拒绝。</p>
     */
    private static volatile SshHostKeyPrompt hostKeyPrompt = SshHostKeyPrompt.denyAll();

    private SshTunnelBridge() {
    }

    public static class BridgeResult implements AutoCloseable {
        private final SharedBridge bridge;
        private boolean closed;

        private BridgeResult(SharedBridge bridge) {
            this.bridge = bridge;
        }

        public String getLocalHost() {
            SshTunnelConfig tunnel = bridge.tunnelConfig;
            return tunnel == null ? SshTunnelConfig.DEFAULT_BIND_ADDRESS : tunnel.getBindAddress();
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

        @Override
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

    /** 注入隧道会话的主机指纹确认方式；传 null 恢复为一律拒绝。 */
    public static void setHostKeyPrompt(SshHostKeyPrompt prompt) {
        hostKeyPrompt = prompt != null ? prompt : SshHostKeyPrompt.denyAll();
    }

    static SshHostKeyPrompt hostKeyPrompt() {
        return hostKeyPrompt;
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
     * 通过指定的 SSH 服务器节点，为目标远程主机和端口建立本地隧道桥接。
     * 本地端口优先与远程端口相同，被占用时自动改用其他端口。
     */
    public static synchronized BridgeResult bridge(String sshConfigId, String remoteHost, int remotePort) throws Exception {
        return bridge(sshConfigId, remoteHost, remotePort, remotePort,
                SshTunnelConfig.DEFAULT_BIND_ADDRESS, false);
    }

    /** Like {@link #bridge(String, String, int)} but on a system-assigned local port of 127.0.0.1. */
    public static synchronized BridgeResult bridgeEphemeral(String sshConfigId, String remoteHost,
                                                            int remotePort) throws Exception {
        return bridge(sshConfigId, remoteHost, remotePort, 0,
                SshTunnelConfig.DEFAULT_BIND_ADDRESS, false);
    }

    /**
     * Forwards exactly {@code bindAddress:localPort}; fails instead of choosing another port.
     * Used for Kafka broker routes, where the client dials the advertised port and a moved
     * forward would reach nothing — or another broker.
     */
    public static synchronized BridgeResult bridgeExact(String sshConfigId, String remoteHost,
                                                        int remotePort, String bindAddress,
                                                        int localPort) throws Exception {
        if (localPort < 1 || localPort > 65535) {
            throw new IllegalArgumentException("local port out of range: " + localPort);
        }
        return bridge(sshConfigId, remoteHost, remotePort, localPort, bindAddress, true);
    }

    private static BridgeResult bridge(String sshConfigId, String remoteHost, int remotePort,
                                       int preferredLocalPort, String bindAddress,
                                       boolean exactPort) throws Exception {
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
        String bind = bindAddress == null || bindAddress.trim().isEmpty()
                ? SshTunnelConfig.DEFAULT_BIND_ADDRESS : bindAddress.trim();

        SshConnectionConfig sshConfig = SshConfigStore.getInstance().findById(sshConfigId);
        if (sshConfig == null) {
            throw new IllegalArgumentException("找不到指定的 SSH 服务器配置 (ID: " + sshConfigId + ")");
        }

        // Bridges with different local requirements must not be shared: a Kafka broker route
        // on 127.0.0.2:9092 is a different forward than a bootstrap one on an ephemeral port.
        String cacheKey = sshConfig.getId() + "\u0000" + remoteHost + "\u0000" + remotePort
                + "\u0000" + bind + "\u0000" + preferredLocalPort + (exactPort ? "!" : "");
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
            SshSessionInstance candidate = new SshSessionInstance(sshConfig, hostKeyPrompt);
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
            tunnel.setBindAddress(bind);
            // A Kafka broker route is only correct when the local port equals the advertised
            // one, so those forwards are never moved to another port.
            if (exactPort) tunnel.setRequiredLocalPort(preferredLocalPort);
            else tunnel.setPreferredLocalPort(preferredLocalPort);
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
