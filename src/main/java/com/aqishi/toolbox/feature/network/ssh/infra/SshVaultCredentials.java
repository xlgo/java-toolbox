package com.aqishi.toolbox.feature.network.ssh.infra;

import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.infra.secrets.SecretPrompter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Resolves credentials of a vault-stored SSH server while the vault is locked, on the
 * connecting (background) thread: unlock with the master password, or use a password
 * typed for this session only. The prompter decides how to ask; it is injected so tests
 * never open a dialog.
 */
public final class SshVaultCredentials implements SshConfigStore.LockedCredentialResolver {
    private static final long UNLOCK_TIMEOUT_SECONDS = 120;

    private final SecretPrompter prompter;

    public SshVaultCredentials(SecretPrompter prompter) {
        this.prompter = Objects.requireNonNull(prompter, "prompter");
    }

    @Override
    public boolean resolve(SshConnectionConfig config, SshConfigStore store) {
        SecretPrompter.Answer answer = prompter.askBeforeConnect(
                config.toString(), store.secretStore().status(), true);
        switch (answer.kind()) {
            case UNLOCK:
                try {
                    store.secretStore().unlock(answer.value()).get(UNLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                } catch (Exception failed) {
                    return false;
                }
                store.hydrate(config);
                return true;
            case SESSION_SECRET:
                Map<String, String> typed = new HashMap<>();
                typed.put(config.primarySecretField(), new String(answer.value()));
                answer.wipe();
                config.applyVaultSecrets(typed);
                return true;
            case SKIP_SECRET:
                return true;
            default:
                answer.wipe();
                return false;
        }
    }
}
