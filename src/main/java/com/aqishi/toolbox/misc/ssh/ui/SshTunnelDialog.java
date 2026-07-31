package com.aqishi.toolbox.misc.ssh.ui;

import com.aqishi.toolbox.misc.ssh.model.SshTunnelConfig;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import java.awt.*;

/**
 * 远程服务隧道新增 / 编辑对话框
 */
public class SshTunnelDialog extends JDialog {

    private final SshTunnelConfig config;
    private boolean saved = false;

    private JTextField nameField;
    private JTextField remoteHostField;
    private JSpinner remotePortSpinner;
    private JComboBox<SshTunnelConfig.BrowserScheme> browserSchemeCombo;
    private JTextField browserPathField;
    private JCheckBox autoStartCheck;

    public SshTunnelDialog(Window owner, SshTunnelConfig existingConfig) {
        super(owner, existingConfig == null ? "添加远程服务隧道" : "编辑服务隧道", ModalityType.APPLICATION_MODAL);
        this.config = existingConfig != null ? existingConfig.clone() : new SshTunnelConfig();

        setSize(460, 390);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(10, 10));

        JPanel mainPanel = new JPanel(new BorderLayout());
        mainPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        FormGrid form = new FormGrid();

        nameField = Fields.text(config.getName(), "例如：内网 MySQL / Web 服务");
        remoteHostField = Fields.text(config.getRemoteHost(), "127.0.0.1 或内网 IP");
        remotePortSpinner = new JSpinner(new SpinnerNumberModel(config.getRemotePort(), 1, 65535, 1));
        browserSchemeCombo = new JComboBox<>(SshTunnelConfig.BrowserScheme.values());
        browserSchemeCombo.setSelectedItem(config.getBrowserScheme());
        browserPathField = Fields.text(config.getBrowserPath(), "例如：/ 或 /admin");
        autoStartCheck = new JCheckBox("SSH 连接成功后自动开启此隧道", config.isAutoStart());

        form.row("服务名称:", nameField);
        form.row("远程主机地址:", remoteHostField);
        form.row("远程目标端口:", remotePortSpinner);
        form.row("浏览器协议:", browserSchemeCombo);
        form.row("浏览器路径:", browserPathField);
        form.row("自动启动:", autoStartCheck);

        mainPanel.add(form, BorderLayout.CENTER);
        add(mainPanel, BorderLayout.CENTER);

        // 底部按钮
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton cancelBtn = Buttons.secondary("取消");
        cancelBtn.addActionListener(e -> dispose());

        JButton saveBtn = Buttons.primary("保存");
        saveBtn.addActionListener(e -> save());

        bottomBar.add(cancelBtn);
        bottomBar.add(saveBtn);

        add(bottomBar, BorderLayout.SOUTH);
    }

    private void save() {
        if (nameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入服务名称", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (remoteHostField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入远程主机地址", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        config.setName(nameField.getText().trim());
        config.setRemoteHost(remoteHostField.getText().trim());
        config.setRemotePort(((Number) remotePortSpinner.getValue()).intValue());
        config.setBrowserScheme((SshTunnelConfig.BrowserScheme) browserSchemeCombo.getSelectedItem());
        config.setBrowserPath(browserPathField.getText().trim());
        config.setAutoStart(autoStartCheck.isSelected());

        this.saved = true;
        dispose();
    }

    public boolean isSaved() {
        return saved;
    }

    public SshTunnelConfig getConfig() {
        return config;
    }
}
