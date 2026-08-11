package com.aqishi.toolbox.misc.ssh.ui;

import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.model.SshSecurityUtils;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * 可复用的 SSH 隧道代理配置 UI 组件。
 * 供 Redis, Database, Kafka, ZooKeeper 等中间件连接配置面板嵌入使用。
 */
public class SshTunnelConfigPanel extends JPanel {

    private final JCheckBox enableTunnelCheck;
    private final JTextField sshHostField;
    private final JSpinner sshPortSpinner;
    private final JTextField sshUserField;
    private final JComboBox<String> authTypeCombo;
    private final JPasswordField sshPasswordField;
    private final JTextField privateKeyField;
    private final JButton browseKeyBtn;
    private final JPasswordField passphraseField;

    private final JPanel innerConfigPanel;
    private final CardLayout authCardLayout;
    private final JPanel authCardPanel;

    public SshTunnelConfigPanel() {
        super(new BorderLayout());
        setOpaque(false);

        enableTunnelCheck = Fields.check("启用 SSH 隧道代理 (Jump Host / Bastion)", false);
        sshHostField = Fields.text("127.0.0.1");
        sshPortSpinner = Fields.spinner(22, 1, 65535, 1);
        sshUserField = Fields.text("root");

        authTypeCombo = Fields.combo(new String[]{"密码认证", "私钥文件"});
        sshPasswordField = Fields.password();
        privateKeyField = Fields.text("");
        browseKeyBtn = new JButton("浏览...");
        passphraseField = Fields.password();

        // 密码认证面板
        FormGrid pwdForm = new FormGrid(Tokens.SPACE_MD, Tokens.SPACE_XS);
        pwdForm.row("SSH 密码", sshPasswordField);

        // 私钥认证面板
        JPanel keyFileRow = Layouts.box();
        keyFileRow.add(privateKeyField, BorderLayout.CENTER);
        keyFileRow.add(browseKeyBtn, BorderLayout.EAST);

        FormGrid keyForm = new FormGrid(Tokens.SPACE_MD, Tokens.SPACE_XS);
        keyForm.row("私钥文件", keyFileRow);
        keyForm.row("Key Passphrase", passphraseField);

        authCardLayout = new CardLayout();
        authCardPanel = new JPanel(authCardLayout);
        authCardPanel.setOpaque(false);
        authCardPanel.add(pwdForm, "PASSWORD");
        authCardPanel.add(keyForm, "KEY");

        // 基础信息表单
        FormGrid form = new FormGrid(Tokens.SPACE_MD, Tokens.SPACE_XS);

        JPanel hostPortPanel = Layouts.box();
        hostPortPanel.add(sshHostField, BorderLayout.CENTER);
        JPanel portWrap = Layouts.box();
        portWrap.add(new JLabel(" 端口: "), BorderLayout.WEST);
        portWrap.add(sshPortSpinner, BorderLayout.CENTER);
        hostPortPanel.add(portWrap, BorderLayout.EAST);

        form.row("SSH 主机/端口", hostPortPanel);
        form.row("SSH 用户名", sshUserField);
        form.row("认证方式", authTypeCombo);
        form.fullRow(authCardPanel);

        innerConfigPanel = form;
        innerConfigPanel.setVisible(false);

        JPanel topWrap = Layouts.box();
        topWrap.add(enableTunnelCheck, BorderLayout.WEST);

        add(topWrap, BorderLayout.NORTH);
        add(innerConfigPanel, BorderLayout.CENTER);

        // 事件监听
        enableTunnelCheck.addActionListener(e -> innerConfigPanel.setVisible(enableTunnelCheck.isSelected()));

        authTypeCombo.addActionListener(e -> {
            if (authTypeCombo.getSelectedIndex() == 0) {
                authCardLayout.show(authCardPanel, "PASSWORD");
            } else {
                authCardLayout.show(authCardPanel, "KEY");
            }
        });

        browseKeyBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择 SSH 私钥文件");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                privateKeyField.setText(file.getAbsolutePath());
            }
        });
    }

    public boolean isTunnelEnabled() {
        return enableTunnelCheck.isSelected();
    }

    public void setTunnelEnabled(boolean enabled) {
        enableTunnelCheck.setSelected(enabled);
        innerConfigPanel.setVisible(enabled);
    }

    public SshConnectionConfig getSshConnectionConfig() {
        SshConnectionConfig config = new SshConnectionConfig();
        config.setHost(sshHostField.getText().trim());
        config.setPort((Integer) sshPortSpinner.getValue());
        config.setUsername(sshUserField.getText().trim());
        if (authTypeCombo.getSelectedIndex() == 0) {
            config.setAuthType(SshConnectionConfig.AuthType.PASSWORD);
            config.setEncryptedPassword(SshSecurityUtils.encrypt(new String(sshPasswordField.getPassword())));
            config.setKeyPath("");
            config.setEncryptedPassphrase("");
        } else {
            config.setAuthType(SshConnectionConfig.AuthType.PRIVATE_KEY);
            config.setEncryptedPassword("");
            config.setKeyPath(privateKeyField.getText().trim());
            config.setEncryptedPassphrase(SshSecurityUtils.encrypt(new String(passphraseField.getPassword())));
        }
        return config;
    }

    public void setSshConnectionConfig(SshConnectionConfig config, boolean enabled) {
        setTunnelEnabled(enabled);
        if (config == null) return;
        sshHostField.setText(config.getHost() == null ? "127.0.0.1" : config.getHost());
        sshPortSpinner.setValue(config.getPort() <= 0 ? 22 : config.getPort());
        sshUserField.setText(config.getUsername() == null ? "root" : config.getUsername());
        if (config.getAuthType() == SshConnectionConfig.AuthType.PRIVATE_KEY || (config.getKeyPath() != null && !config.getKeyPath().isEmpty())) {
            authTypeCombo.setSelectedIndex(1);
            authCardLayout.show(authCardPanel, "KEY");
            privateKeyField.setText(config.getKeyPath() == null ? "" : config.getKeyPath());
            passphraseField.setText(SshSecurityUtils.decrypt(config.getEncryptedPassphrase()));
        } else {
            authTypeCombo.setSelectedIndex(0);
            authCardLayout.show(authCardPanel, "PASSWORD");
            sshPasswordField.setText(SshSecurityUtils.decrypt(config.getEncryptedPassword()));
        }
    }
}
