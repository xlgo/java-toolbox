package com.aqishi.toolbox.infra.docker;

import com.aqishi.toolbox.feature.network.ssh.domain.SshHostKeyPrompt;
import com.aqishi.toolbox.util.I18n;

import java.util.Objects;

/** One Docker connection: local CLI, remote CLI, or local CLI through a shared TCP tunnel. */
public record DockerConnectionOptions(
        String executable,
        String context,
        String sshId,
        SshMode sshMode,
        String remoteHost,
        int remotePort) {
    public enum SshMode {
        COMMAND("command"),
        TCP_TUNNEL("tunnel");
        private final String key;

        SshMode(String key) {
            this.key = key;
        }

        @Override
        public String toString() {
            return I18n.get("docker.ssh.mode." + key);
        }
    }

    public DockerConnectionOptions {
        executable = DockerCliRunner.checked(executable).trim();
        context = DockerCliRunner.checked(context).trim();
        sshId = Objects.requireNonNullElse(sshId, "").trim();
        sshMode = Objects.requireNonNullElse(sshMode, SshMode.COMMAND);
        remoteHost = Objects.requireNonNullElse(remoteHost, "").trim();
        if (remoteHost.startsWith("[") && remoteHost.endsWith("]"))
            remoteHost = remoteHost.substring(1, remoteHost.length() - 1);
        if (executable.isEmpty())
            throw new IllegalArgumentException(I18n.get("docker.error.executable"));
        if (!sshId.isEmpty() && sshMode == SshMode.TCP_TUNNEL) {
            if (remoteHost.isEmpty()
                    || !remoteHost.matches("[a-zA-Z0-9_.:%-]+")
                    || remotePort < 1
                    || remotePort > 65535)
                throw new IllegalArgumentException(I18n.get("docker.ssh.invalidTarget"));
            if (!context.isEmpty())
                throw new IllegalArgumentException(I18n.get("docker.ssh.contextConflict"));
        }
    }

    public DockerCommandRunner createRunner() {
        return createRunner(SshHostKeyPrompt.denyAll());
    }

    public DockerCommandRunner createRunner(SshHostKeyPrompt prompt) {
        return !sshId.isEmpty() && sshMode == SshMode.TCP_TUNNEL
                ? new DockerSshTunnelRunner(this)
                : new DockerCliRunner(executable, context, sshId, prompt);
    }
}
