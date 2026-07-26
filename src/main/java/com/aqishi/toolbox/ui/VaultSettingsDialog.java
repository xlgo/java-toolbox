package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.VaultCrypto;
import com.aqishi.toolbox.vault.VaultService;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Window;
import java.util.Arrays;

/** Shared idle-timeout and master-password settings. */
public final class VaultSettingsDialog extends JDialog {
    private final VaultService service;
    private final JComboBox<Integer> timeout = Fields.combo(new Integer[]{1, 5, 10, 30}, 96);
    private final JPasswordField current = Fields.password();
    private final JPasswordField replacement = Fields.password();
    private final JPasswordField confirmation = Fields.password();

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
        JPanel root = Layouts.box(0, Tokens.SPACE_LG);
        root.setOpaque(true);
        root.setBorder(KitBorders.padding(Tokens.SPACE_LG));

        // 四行共用一个标签列：自动锁定时长与三个密码框右边缘对齐，
        // 下拉框用 rowCompact 保持自身宽度，不跟着密码框拉满整行
        FormGrid form = new FormGrid();
        form.rowCompact(I18n.get("vault.timeout"), timeout);
        form.row(I18n.get("vault.currentPassword"), current);
        form.row(I18n.get("vault.newPassword"), replacement);
        form.row(I18n.get("vault.confirmPassword"), confirmation);
        root.add(form, BorderLayout.CENTER);

        // 对话框按钮统一右对齐，主操作在最右
        JButton cancel = Buttons.secondary(I18n.get("vault.cancel"));
        JButton save = Buttons.primary(I18n.get("vault.save"));
        cancel.addActionListener(event -> dispose());
        save.addActionListener(event -> save(save));
        ActionBar buttons = new ActionBar();
        buttons.right(cancel);
        buttons.right(save);
        root.add(buttons, BorderLayout.SOUTH);
        return root;
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
