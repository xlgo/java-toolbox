package com.aqishi.toolbox.ui.secrets;

import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/**
 * One-line, non-modal notice above a connection tool: unencrypted saved passwords
 * waiting for the vault, a migration in progress, or vault passwords that cannot be
 * filled in while the vault is locked. Hidden when there is nothing to say.
 */
public final class SecretNoticeBar extends JPanel {
    private final JLabel message = Fields.caption("");
    private final JButton unlock = Buttons.ghost(I18n.get("secrets.unlock"));

    public SecretNoticeBar(Runnable onUnlock) {
        super(new BorderLayout(Tokens.SPACE_SM, 0));
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Tokens.borderSubtle()),
                KitBorders.padding(Tokens.SPACE_XS, Tokens.SPACE_LG, Tokens.SPACE_XS, Tokens.SPACE_LG)));
        unlock.addActionListener(event -> {
            if (onUnlock != null) onUnlock.run();
        });
        add(message, BorderLayout.CENTER);
        add(unlock, BorderLayout.EAST);
        setVisible(false);
    }

    /** Text currently shown, or empty when hidden (for tests). */
    public String messageText() {
        return isVisible() ? message.getText() : "";
    }

    public boolean unlockOffered() {
        return isVisible() && unlock.isVisible();
    }

    public void update(int plaintextCount, int vaultStoredCount,
                       SecretStore.Status status, boolean migrating) {
        String text = null;
        boolean offerUnlock = false;
        if (status == SecretStore.Status.DISABLED) {
            text = null;
        } else if (plaintextCount > 0) {
            if (migrating) {
                text = I18n.get("secrets.notice.migrating", plaintextCount);
            } else if (status == SecretStore.Status.LOCKED) {
                text = I18n.get("secrets.notice.plaintext.locked", plaintextCount);
                offerUnlock = true;
            } else if (status == SecretStore.Status.NO_VAULT) {
                text = I18n.get("secrets.notice.plaintext.noVault", plaintextCount);
            } else {
                text = I18n.get("secrets.notice.plaintext.unavailable", plaintextCount);
            }
        } else if (vaultStoredCount > 0 && status == SecretStore.Status.LOCKED) {
            text = I18n.get("secrets.notice.locked", vaultStoredCount);
            offerUnlock = true;
        }
        // Unlocked but not migrating means the last attempt failed: same wording as read-only.
        message.setForeground(plaintextCount > 0 && !migrating ? Tokens.warning() : Tokens.mutedForeground());
        message.setText(text == null ? "" : text);
        unlock.setVisible(offerUnlock);
        boolean show = text != null;
        if (show != isVisible()) {
            setVisible(show);
            revalidate();
            repaint();
        }
    }
}
