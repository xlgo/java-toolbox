package com.aqishi.toolbox.misc.ssh.session;

import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.model.SshSecurityUtils;
import com.aqishi.toolbox.misc.ssh.model.SshTunnelConfig;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelDirectTCPIP;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import javax.swing.JOptionPane;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 代表一个活动的 SSH 连接会话，包含终端、SFTP、端口转发及自动恢复能力。
 */
public class SshSessionInstance implements AutoCloseable {

    public enum Status {
        DISCONNECTED("未连接"),
        CONNECTING("连接中..."),
        CONNECTED("已连接"),
        ERROR("连接异常");

        private final String label;

        Status(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public interface SessionListener {
        void onStatusChanged(Status status, String message);
    }

    private final SshConnectionConfig config;
    private final CopyOnWriteArrayList<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SshTunnelConfig> managedTunnels = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService lifecycleExecutor;

    private volatile JSch jSch;
    private volatile Session session;
    private volatile ChannelShell channelShell;
    private volatile ChannelSftp channelSftp;
    private volatile SshTtyConnector ttyConnector;
    private volatile Status status = Status.DISCONNECTED;
    private volatile String lastErrorMessage = "";
    private volatile boolean manualDisconnect;
    private volatile boolean closed;
    private boolean reconnectPending;
    private long reconnectDelayMs = 2_000L;
    private ScheduledFuture<?> reconnectFuture;
    private ScheduledFuture<?> monitorFuture;

    public SshSessionInstance(SshConnectionConfig config) {
        if (config == null) throw new IllegalArgumentException("SSH 配置不能为空");
        this.config = config;
        this.lifecycleExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "SSH-Lifecycle-" + safeHost());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    private String safeHost() {
        String host = config.getHost();
        return host == null || host.trim().isEmpty() ? "session" : host.trim();
    }

    public SshConnectionConfig getConfig() {
        return config;
    }

    public Status getStatus() {
        return status;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public void addListener(SessionListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    private void setStatus(Status newStatus, String message) {
        status = newStatus;
        lastErrorMessage = message == null ? "" : message;
        for (SessionListener listener : listeners) {
            try {
                listener.onStatusChanged(newStatus, message);
            } catch (RuntimeException ignored) {
                // A stale UI listener must not stop connection monitoring.
            }
        }
    }

    /** Asynchronously starts or resumes an SSH connection. */
    public void connectAsync(Consumer<Boolean> onFinished) {
        manualDisconnect = false;
        Thread thread = new Thread(() -> {
            boolean success = connectSync();
            if (onFinished != null) onFinished.accept(success);
        }, "SSH-Connect-" + safeHost());
        thread.setDaemon(true);
        thread.start();
    }

    /** Synchronously starts an SSH connection. */
    public synchronized boolean connectSync() {
        if (closed) return false;
        if (isConnected()) return true;

        manualDisconnect = false;
        setStatus(Status.CONNECTING, "正在连接 " + config.getHost() + ":" + config.getPort() + "...");

        try {
            jSch = new JSch();
            configureHostKeyChecking(jSch);

            if (config.getAuthType() == SshConnectionConfig.AuthType.PRIVATE_KEY) {
                String passphrase = SshSecurityUtils.decrypt(config.getEncryptedPassphrase());
                byte[] passphraseBytes = passphrase == null || passphrase.isEmpty()
                        ? null : passphrase.getBytes(StandardCharsets.UTF_8);
                if (config.getKeySource() == SshConnectionConfig.KeySource.FILE_PATH) {
                    if (config.getKeyPath() == null || config.getKeyPath().trim().isEmpty()) {
                        throw new IllegalArgumentException("私钥文件路径不能为空");
                    }
                    File keyFile = new File(config.getKeyPath().trim());
                    if (!keyFile.isFile()) {
                        throw new IllegalArgumentException("指定私钥文件不存在: " + config.getKeyPath());
                    }
                    jSch.addIdentity(keyFile.getAbsolutePath(), passphraseBytes);
                } else {
                    String keyContent = config.getKeyContent();
                    if (keyContent == null || keyContent.trim().isEmpty()) {
                        throw new IllegalArgumentException("私钥内容不能为空");
                    }
                    jSch.addIdentity("inmemory_key",
                            keyContent.getBytes(StandardCharsets.UTF_8), null, passphraseBytes);
                }
            }

            session = jSch.getSession(config.getUsername(), config.getHost(), config.getPort());
            if (config.getAuthType() == SshConnectionConfig.AuthType.PASSWORD) {
                session.setPassword(SshSecurityUtils.decrypt(config.getEncryptedPassword()));
            }

            Properties properties = new Properties();
            // 首次连接通过指纹确认，之后使用 known_hosts 拒绝未确认的变更。
            properties.put("StrictHostKeyChecking", "ask");
            session.setConfig(properties);
            session.setUserInfo(new FingerprintUserInfo());
            if (config.getKeepAliveSec() > 0) {
                session.setServerAliveInterval(config.getKeepAliveSec() * 1000);
            }
            session.connect(config.getConnectTimeoutMs());

            channelShell = (ChannelShell) session.openChannel("shell");
            channelShell.setPtyType("xterm-256color");
            InputStream input = channelShell.getInputStream();
            OutputStream output = channelShell.getOutputStream();
            channelShell.connect(config.getConnectTimeoutMs());
            ttyConnector = new SshTtyConnector(channelShell, input, output);

            try {
                channelSftp = (ChannelSftp) session.openChannel("sftp");
                channelSftp.connect(config.getConnectTimeoutMs());
            } catch (Exception sftpError) {
                channelSftp = null;
                System.err.println("SFTP 通道打开失败: " + sftpError.getMessage());
            }

            SshTunnelBridge.register(this);
            setStatus(Status.CONNECTED, "已成功连接到服务器");
            restoreTunnels();
            scheduleConnectionMonitor();
            reconnectDelayMs = 2_000L;
            return true;
        } catch (Exception error) {
            cleanupConnectionLocked();
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName() : error.getMessage();
            setStatus(Status.ERROR, "连接失败: " + message);
            scheduleReconnectLocked();
            return false;
        }
    }

    private void configureHostKeyChecking(JSch client) {
        // JSch's known_hosts repository is intentionally kept separate from server profiles.
        try {
            java.io.File dataDir = com.aqishi.toolbox.vault.ApplicationPaths.systemDefault()
                    .getDataDirectory().toFile();
            if (!dataDir.exists()) dataDir.mkdirs();
            client.setKnownHosts(new File(dataDir, "ssh_known_hosts").getAbsolutePath());
        } catch (Exception error) {
            throw new IllegalStateException("无法初始化 SSH 主机指纹存储", error);
        }
    }

    private void scheduleConnectionMonitor() {
        if (monitorFuture == null || monitorFuture.isDone()) {
            monitorFuture = lifecycleExecutor.scheduleWithFixedDelay(
                    this::pollConnection, 2, 2, TimeUnit.SECONDS);
        }
    }

    private void pollConnection() {
        if (closed || manualDisconnect || status != Status.CONNECTED) return;
        if (!isConnected()) {
            synchronized (this) {
                if (closed || manualDisconnect || status != Status.CONNECTED) return;
                cleanupConnectionLocked();
                setStatus(Status.DISCONNECTED, "SSH 连接已断开，相关服务已暂停");
                scheduleReconnectLocked();
            }
        }
    }

    private synchronized void scheduleReconnectLocked() {
        if (closed || manualDisconnect || !config.isAutoReconnect() || reconnectPending) return;
        reconnectPending = true;
        final long delay = reconnectDelayMs;
        reconnectFuture = lifecycleExecutor.schedule(() -> {
            synchronized (SshSessionInstance.this) {
                reconnectPending = false;
                if (closed || manualDisconnect) return;
            }
            if (!connectSync()) {
                synchronized (SshSessionInstance.this) {
                    reconnectDelayMs = Math.min(reconnectDelayMs * 2, 30_000L);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /** Creates a local forward with port 0 so JSch atomically selects a free port. */
    public synchronized boolean startTunnel(SshTunnelConfig tunnel) {
        if (tunnel == null) return false;
        ensureManaged(tunnel);
        if (session == null || !session.isConnected()) {
            tunnel.setAssignedLocalPort(0);
            tunnel.setStatus(SshTunnelConfig.Status.PAUSED);
            tunnel.setErrorMessage("SSH 会话未连接");
            return false;
        }
        try {
            int preferredLocalPort = tunnel.getAssignedLocalPort() > 0
                    ? tunnel.getAssignedLocalPort() : tunnel.getPreferredLocalPort();
            if (tunnel.getAssignedLocalPort() > 0) {
                try {
                    session.delPortForwardingL(tunnel.getAssignedLocalPort());
                } catch (Exception ignored) {
                    // It may already have been removed by a lost session.
                }
            }
            int localPort;
            if (preferredLocalPort > 0) {
                try {
                    localPort = session.setPortForwardingL(preferredLocalPort,
                            tunnel.getRemoteHost(), tunnel.getRemotePort());
                } catch (Exception preferredPortError) {
                    // A competing local process may have claimed the old port during recovery.
                    localPort = session.setPortForwardingL(0,
                            tunnel.getRemoteHost(), tunnel.getRemotePort());
                }
            } else {
                localPort = session.setPortForwardingL(0,
                        tunnel.getRemoteHost(), tunnel.getRemotePort());
            }
            if (localPort <= 0) throw new IllegalStateException("SSH 未返回有效本地端口");
            tunnel.setAssignedLocalPort(localPort);
            tunnel.setPreferredLocalPort(localPort);
            tunnel.setStatus(SshTunnelConfig.Status.RUNNING);
            tunnel.setErrorMessage(null);
            return true;
        } catch (Exception error) {
            tunnel.setAssignedLocalPort(0);
            tunnel.setStatus(SshTunnelConfig.Status.ERROR);
            tunnel.setErrorMessage(describeTunnelFailure(tunnel, error));
            return false;
        }
    }

    /**
     * Verifies that the SSH server can open the remote side of a forwarding
     * channel. Creating a local listener alone is not enough: OpenSSH may
     * accept it while the target service is unreachable, which later appears
     * to Redis/JDBC clients as an unexpected end of stream.
     */
    public synchronized void verifyTunnelTarget(SshTunnelConfig tunnel) throws Exception {
        if (tunnel == null || tunnel.getAssignedLocalPort() <= 0) {
            throw new IllegalArgumentException("隧道没有有效的本地端口");
        }
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH 会话未连接，无法验证远程目标");
        }
        ChannelDirectTCPIP direct = null;
        try {
            direct = (ChannelDirectTCPIP) session.openChannel("direct-tcpip");
            direct.setHost(tunnel.getRemoteHost());
            direct.setPort(tunnel.getRemotePort());
            direct.connect(Math.min(Math.max(config.getConnectTimeoutMs(), 1_000), 5_000));
        } catch (Exception error) {
            throw new IllegalStateException("SSH 服务器无法访问远程目标 "
                    + tunnel.getRemoteHost() + ":" + tunnel.getRemotePort()
                    + ": " + safeMessage(error), error);
        } finally {
            if (direct != null) {
                try {
                    direct.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public synchronized boolean stopTunnel(SshTunnelConfig tunnel) {
        if (tunnel == null) return false;
        if (tunnel.getAssignedLocalPort() > 0 && session != null && session.isConnected()) {
            try {
                session.delPortForwardingL(tunnel.getAssignedLocalPort());
            } catch (Exception ignored) {
            }
        }
        tunnel.setAssignedLocalPort(0);
        tunnel.setPreferredLocalPort(0);
        tunnel.setStatus(SshTunnelConfig.Status.STOPPED);
        tunnel.setErrorMessage(null);
        return true;
    }

    /** Releases a transient bridge tunnel while leaving persisted SSH tunnels intact. */
    public synchronized void forgetTunnel(SshTunnelConfig tunnel) {
        if (tunnel == null) return;
        stopTunnel(tunnel);
        if (!config.getTunnels().contains(tunnel)) managedTunnels.remove(tunnel);
    }

    private void ensureManaged(SshTunnelConfig tunnel) {
        if (!managedTunnels.contains(tunnel)) managedTunnels.add(tunnel);
    }

    private List<SshTunnelConfig> allTunnels() {
        List<SshTunnelConfig> result = new ArrayList<>();
        for (SshTunnelConfig tunnel : config.getTunnels()) {
            if (tunnel != null && !result.contains(tunnel)) result.add(tunnel);
        }
        for (SshTunnelConfig tunnel : managedTunnels) {
            if (tunnel != null && !result.contains(tunnel)) result.add(tunnel);
        }
        return result;
    }

    private synchronized void restoreTunnels() {
        for (SshTunnelConfig tunnel : allTunnels()) {
            if (tunnel.isAutoStart() || tunnel.getStatus() == SshTunnelConfig.Status.PAUSED
                    || tunnel.getStatus() == SshTunnelConfig.Status.RUNNING) {
                startTunnel(tunnel);
            }
        }
    }

    public synchronized ChannelSftp getSftpChannel() {
        if (channelSftp != null && channelSftp.isConnected()) return channelSftp;
        if (session != null && session.isConnected()) {
            try {
                channelSftp = (ChannelSftp) session.openChannel("sftp");
                channelSftp.connect(config.getConnectTimeoutMs());
                return channelSftp;
            } catch (Exception error) {
                System.err.println("SFTP 通道连接失败: " + safeMessage(error));
            }
        }
        return null;
    }

    public SshTtyConnector getTtyConnector() {
        return ttyConnector;
    }

    /** User-requested disconnect. It deliberately cancels automatic reconnect. */
    public synchronized void disconnect() {
        manualDisconnect = true;
        reconnectPending = false;
        if (reconnectFuture != null) reconnectFuture.cancel(false);
        cleanupConnectionLocked();
        if (status != Status.DISCONNECTED) setStatus(Status.DISCONNECTED, "连接已断开");
    }

    private void cleanupConnectionLocked() {
        for (SshTunnelConfig tunnel : allTunnels()) {
            if (tunnel.getStatus() == SshTunnelConfig.Status.RUNNING
                    || tunnel.getAssignedLocalPort() > 0) {
                if (tunnel.getAssignedLocalPort() > 0) {
                    tunnel.setPreferredLocalPort(tunnel.getAssignedLocalPort());
                }
                tunnel.setStatus(SshTunnelConfig.Status.PAUSED);
                tunnel.setErrorMessage("SSH 会话已断开");
            }
            tunnel.setAssignedLocalPort(0);
        }
        SshTunnelBridge.unregister(this);
        if (channelShell != null) {
            try { channelShell.disconnect(); } catch (Exception ignored) { }
            channelShell = null;
        }
        if (channelSftp != null) {
            try { channelSftp.disconnect(); } catch (Exception ignored) { }
            channelSftp = null;
        }
        if (session != null) {
            try { session.disconnect(); } catch (Exception ignored) { }
            session = null;
        }
        if (ttyConnector != null) {
            try { ttyConnector.close(); } catch (Exception ignored) { }
            ttyConnector = null;
        }
    }

    public boolean isConnected() {
        Session currentSession = session;
        ChannelShell currentShell = channelShell;
        return currentSession != null && currentSession.isConnected()
                && currentShell != null && currentShell.isConnected();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        manualDisconnect = true;
        disconnect();
        if (monitorFuture != null) monitorFuture.cancel(false);
        lifecycleExecutor.shutdownNow();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String describeTunnelFailure(SshTunnelConfig tunnel, Throwable error) {
        String message = safeMessage(error);
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        String target = tunnel.getRemoteHost() + ":" + tunnel.getRemotePort();
        if (lower.contains("administratively prohibited")
                || lower.contains("forwarding") && (lower.contains("denied")
                || lower.contains("prohibit") || lower.contains("open failed"))) {
            return "SSH 服务端拒绝端口转发，请在 sshd_config 开启 AllowTcpForwarding yes"
                    + "（目标 " + target + "）";
        }
        if (lower.contains("connection refused") || lower.contains("connect failed")
                || lower.contains("unknown host") || lower.contains("timeout")) {
            return "SSH 隧道无法连接远程目标 " + target + "，请确认 SSH 服务器能访问该地址和端口：" + message;
        }
        return "隧道建立失败（目标 " + target + "）：" + message;
    }

    private static final class FingerprintUserInfo implements com.jcraft.jsch.UserInfo {
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return null; }
        @Override public boolean promptPassword(String message) { return false; }
        @Override public boolean promptPassphrase(String message) { return false; }

        @Override
        public boolean promptYesNo(String message) {
            if (GraphicsEnvironment.isHeadless()) return false;
            return JOptionPane.showConfirmDialog(null, message,
                    "确认 SSH 主机指纹", JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
        }

        @Override
        public void showMessage(String message) {
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null, message, "SSH", JOptionPane.INFORMATION_MESSAGE);
            }
        }
    }
}
