package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.ssh.infra.SshConfigStore;
import com.aqishi.toolbox.feature.network.ssh.infra.SshVaultCredentials;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.ui.secrets.SecretNoticeBar;
import com.aqishi.toolbox.ui.secrets.SwingSecretPrompter;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.Objects;

/**
 * SSH client wired to the vault: SSH passwords, key passphrases and pasted private keys
 * are kept in the vault instead of {@code ssh_servers.json}, and a notice above the tool
 * reports servers whose credentials still sit in the local file.
 */
public final class VaultAwareSshClientPanel extends SshClientPanel {
    private final SecretStore secrets;
    private SecretNoticeBar notice;

    public VaultAwareSshClientPanel(SecretStore secrets) {
        super();
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        install(secrets);
    }

    /**
     * Attaches {@code secrets} to the shared SSH config store, so tunnels opened by the
     * database / Redis / Kafka tools resolve vault credentials the same way.
     */
    public static void install(SecretStore secrets) {
        SshConfigStore.getInstance().attachSecretStore(secrets,
                new SshVaultCredentials(SwingSecretPrompter.onEdt(
                        new SwingSecretPrompter(VaultAwareSshClientPanel::activeWindow))),
                SwingUtilities::invokeLater);
    }

    private static Component activeWindow() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
    }

    @Override
    protected JComponent build() {
        JComponent content = super.build();
        notice = new SecretNoticeBar(this::unlock);
        SshConfigStore.getInstance().addChangeListener(this::refreshNotice);
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(notice, BorderLayout.NORTH);
        wrapper.add(content, BorderLayout.CENTER);
        refreshNotice();
        return wrapper;
    }

    private void refreshNotice() {
        Runnable refresh = () -> {
            if (notice == null) return;
            SshConfigStore store = SshConfigStore.getInstance();
            notice.update(store.localSecretCount(), store.vaultStoredCount(),
                    secrets.status(), store.isMigrating());
        };
        if (SwingUtilities.isEventDispatchThread()) refresh.run();
        else SwingUtilities.invokeLater(refresh);
    }

    private void unlock() {
        char[] master = new SwingSecretPrompter(this::getView).askMasterPassword();
        if (master == null) return;
        secrets.unlock(master).whenComplete((ignored, error) -> {
            if (error != null) {
                SwingUtilities.invokeLater(() -> UIUtils.error(getView(), I18n.get("secrets.unlock.failed")));
            }
        });
    }
}
