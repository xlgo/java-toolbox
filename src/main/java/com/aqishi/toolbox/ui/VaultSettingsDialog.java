package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultService;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.Arrays;

/** Shared idle-timeout and master-password settings. */
public final class VaultSettingsDialog extends JDialog {
    private final VaultService service;
    private final JComboBox<Integer> timeout = new JComboBox<>(new Integer[]{1, 5, 10, 30});
    private final JPasswordField current = new JPasswordField(18);
    private final JPasswordField replacement = new JPasswordField(18);
    private final JPasswordField confirmation = new JPasswordField(18);

    public VaultSettingsDialog(Window owner, VaultService service) {
        super(owner, I18n.get("vault.settings"), ModalityType.APPLICATION_MODAL);
        this.service = service;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setContentPane(build());
        pack();
        setMinimumSize(getSize());
        setLocationRelativeTo(owner);
    }

    private JPanel build() {
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(14, 16, 14, 16));
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 6, 5, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        add(form, c, 0, I18n.get("vault.timeout"), timeout);
        add(form, c, 1, I18n.get("vault.currentPassword"), current);
        add(form, c, 2, I18n.get("vault.newPassword"), replacement);
        add(form, c, 3, I18n.get("vault.confirmPassword"), confirmation);
        root.add(form, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton cancel = new JButton(I18n.get("vault.cancel"));
        JButton save = new JButton(I18n.get("vault.save"));
        cancel.addActionListener(event -> dispose());
        save.addActionListener(event -> save(save));
        buttons.add(cancel);
        buttons.add(save);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
    }

    private static void add(JPanel panel, GridBagConstraints base, int row,
                            String text, javax.swing.JComponent field) {
        GridBagConstraints left = (GridBagConstraints) base.clone();
        left.gridx = 0;
        left.gridy = row;
        left.weightx = 0;
        panel.add(new JLabel(text), left);
        GridBagConstraints right = (GridBagConstraints) base.clone();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        panel.add(field, right);
    }

    private void save(JButton button) {
        Integer minutes = (Integer) timeout.getSelectedItem();
        service.setIdleMinutes(minutes == null ? 5 : minutes);
        ConfigManager.setInt("vault.idleMinutes", minutes == null ? 5 : minutes);
        if (!ConfigManager.save()) {
            UIUtils.error(this, I18n.get("vault.settings.saveFailed"));
            return;
        }

        char[] oldPassword = current.getPassword();
        char[] newPassword = replacement.getPassword();
        char[] confirmed = confirmation.getPassword();
        if (oldPassword.length == 0 && newPassword.length == 0 && confirmed.length == 0) {
            dispose();
            return;
        }
        if (newPassword.length == 0 || !Arrays.equals(newPassword, confirmed)) {
            VaultCrypto.wipe(oldPassword);
            VaultCrypto.wipe(newPassword);
            VaultCrypto.wipe(confirmed);
            UIUtils.error(this, I18n.get("vault.password.mismatch"));
            return;
        }
        button.setEnabled(false);
        service.changePassword(oldPassword, newPassword).whenComplete((ignored, error) -> {
            VaultCrypto.wipe(confirmed);
            SwingUtilities.invokeLater(() -> {
                current.setText("");
                replacement.setText("");
                confirmation.setText("");
                if (error == null) dispose();
                else {
                    button.setEnabled(true);
                    UIUtils.error(this, I18n.get("vault.error.generic"));
                }
            });
        });
    }
}
