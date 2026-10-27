package com.aqishi.toolbox.infra.ssh;

import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.domain.SshHostKeyPrompt;
import com.aqishi.toolbox.feature.network.ssh.infra.SshConfigStore;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.vault.ApplicationPaths;
import com.jcraft.jsch.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent SSH exec transport. Owns no terminal, SFTP channel, or tunnel registry entry. */
public final class SshExecConnection implements AutoCloseable {
    private final SshConnectionConfig config;
    private final SshHostKeyPrompt prompt;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Session session;

    public SshExecConnection(SshConnectionConfig config, SshHostKeyPrompt prompt) {
        this.config = Objects.requireNonNull(config, "config");
        this.prompt = Objects.requireNonNullElseGet(prompt, SshHostKeyPrompt::denyAll);
    }

    public synchronized void connect() throws Exception {
        checkOpen();
        if (session != null && session.isConnected()) return;
        if (!SshConfigStore.ensureCredentials(config))
            throw new IOException(I18n.get("secrets.ssh.locked"));
        checkOpen();
        JSch client = new JSch();
        Path data = ApplicationPaths.systemDefault().getDataDirectory();
        Files.createDirectories(data);
        client.setKnownHosts(data.resolve("ssh_known_hosts").toString());
        AtomicBoolean changed = new AtomicBoolean();
        client.setHostKeyRepository(checkingRepository(client.getHostKeyRepository(), changed));
        if (config.getAuthType() == SshConnectionConfig.AuthType.PRIVATE_KEY) {
            String passphrase = config.resolvedPassphrase();
            byte[] password =
                    passphrase == null || passphrase.isEmpty()
                            ? null
                            : passphrase.getBytes(StandardCharsets.UTF_8);
            if (config.getKeySource() == SshConnectionConfig.KeySource.FILE_PATH) {
                String path = config.getKeyPath();
                if (path == null || path.isBlank() || !Files.isRegularFile(Path.of(path)))
                    throw new IOException(I18n.get("ssh.exec.keyFile"));
                client.addIdentity(Path.of(path).toAbsolutePath().toString(), password);
            } else {
                String content = config.getKeyContent();
                if (content == null || content.isBlank())
                    throw new IOException(I18n.get("ssh.exec.keyContent"));
                client.addIdentity(
                        "exec-session", content.getBytes(StandardCharsets.UTF_8), null, password);
            }
        }
        checkOpen();
        Session candidate =
                client.getSession(config.getUsername(), config.getHost(), config.getPort());
        session = candidate;
        try {
            checkOpen();
            candidate.setConfig("StrictHostKeyChecking", "ask");
            candidate.setUserInfo(userInfo(prompt, closed, changed));
            if (config.getAuthType() == SshConnectionConfig.AuthType.PASSWORD)
                candidate.setPassword(config.resolvedPassword());
            if (config.getKeepAliveSec() > 0)
                candidate.setServerAliveInterval(config.getKeepAliveSec() * 1000);
            candidate.connect(config.getConnectTimeoutMs());
            checkOpen();
        } catch (Exception error) {
            candidate.disconnect();
            throw error;
        }
    }

    public ChannelExec openExecChannel() throws Exception {
        checkOpen();
        Session current = session;
        if (current == null || !current.isConnected())
            throw new IOException(I18n.get("ssh.exec.notConnected"));
        return (ChannelExec) current.openChannel("exec");
    }

    private void checkOpen() throws InterruptedIOException {
        if (closed.get() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException(I18n.get("ssh.exec.closed"));
    }

    @Override
    public void close() {
        closed.set(true);
        Session current = session;
        if (current != null) current.disconnect();
    }

    static UserInfo userInfo(SshHostKeyPrompt prompt, AtomicBoolean closed, AtomicBoolean changed) {
        return new UserInfo() {
            public String getPassphrase() {
                return null;
            }

            public String getPassword() {
                return null;
            }

            public boolean promptPassword(String message) {
                return false;
            }

            public boolean promptPassphrase(String message) {
                return false;
            }

            public boolean promptYesNo(String message) {
                return !closed.get()
                        && !changed.get()
                        && prompt.confirmHostKey(message)
                        && !closed.get();
            }

            public void showMessage(String message) {
                if (!closed.get()) prompt.showMessage(message);
            }
        };
    }

    static HostKeyRepository checkingRepository(HostKeyRepository delegate, AtomicBoolean changed) {
        return new HostKeyRepository() {
            public int check(String host, byte[] key) {
                int result = delegate.check(host, key);
                if (result == CHANGED) changed.set(true);
                return result;
            }

            public void add(HostKey key, UserInfo userInfo) {
                delegate.add(key, userInfo);
            }

            public void remove(String host, String type) {
                delegate.remove(host, type);
            }

            public void remove(String host, String type, byte[] key) {
                delegate.remove(host, type, key);
            }

            public String getKnownHostsRepositoryID() {
                return delegate.getKnownHostsRepositoryID();
            }

            public HostKey[] getHostKey() {
                return delegate.getHostKey();
            }

            public HostKey[] getHostKey(String host, String type) {
                return delegate.getHostKey(host, type);
            }
        };
    }
}
