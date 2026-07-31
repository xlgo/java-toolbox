package com.aqishi.toolbox.misc.ssh.ui;

import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.model.SshSecurityUtils;
import com.aqishi.toolbox.misc.ssh.session.SshSessionInstance;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * SSH 服务器连接配置编辑对话框
 */
public class SshConfigDialog extends JDialog {

    private final SshConnectionConfig config;
    private boolean saved = false;

    private JTextField nameField;
    private JTextField groupField;
    private JTextField hostField;
    private JSpinner portSpinner;
    private JTextField usernameField;

    private JComboBox<SshConnectionConfig.AuthType> authTypeCombo;

    // 密码认证面板组件
    private JPanel passwordPanel;
    private JPasswordField passwordField;

    // 私钥认证面板组件
    private JPanel keyPanel;
    private JComboBox<SshConnectionConfig.KeySource> keySourceCombo;
    private JTextField keyPathField;
    private JButton browseKeyBtn;
    private JTextArea keyContentArea;
    private JPasswordField passphraseField;

    // 高级连接参数
    private JSpinner timeoutSpinner;
    private JSpinner keepAliveSpinner;
    private JCheckBox autoReconnectCheck;
    private JTextArea remarksArea;

    public SshConfigDialog(Window owner, SshConnectionConfig existingConfig) {
        super(owner, existingConfig == null ? "新增 SSH 服务器" : "编辑 SSH 服务器", ModalityType.APPLICATION_MODAL);
        this.config = existingConfig != null ? existingConfig.clone() : new SshConnectionConfig();

        setSize(540, 680);
        setLocationRelativeTo(owner);
        setLayout(new BorderLayout(10, 10));

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(Tokens.fontSectionTitle());

        // 1. 基本连接信息页
        JPanel basicPanel = new JPanel(new BorderLayout());
        basicPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        FormGrid form = new FormGrid();

        nameField = Fields.text(config.getName());
        groupField = Fields.text(config.getGroup());
        hostField = Fields.text(config.getHost());

        portSpinner = new JSpinner(new SpinnerNumberModel(config.getPort(), 1, 65535, 1));
        usernameField = Fields.text(config.getUsername());

        form.row("连接名称:", nameField);
        form.row("分组名称:", groupField);
        form.row("服务器地址(IP/Host):", hostField);
        form.row("端口号:", portSpinner);
        form.row("登录用户名:", usernameField);

        authTypeCombo = new JComboBox<>(SshConnectionConfig.AuthType.values());
        authTypeCombo.setSelectedItem(config.getAuthType());
        form.row("认证方式:", authTypeCombo);

        // 密码认证面板
        passwordPanel = new JPanel(new BorderLayout(4, 4));
        passwordField = Fields.password();
        passwordField.setText(SshSecurityUtils.decrypt(config.getEncryptedPassword()));
        // CardLayout 的另一页包含私钥文本区，若直接放到 CENTER，JTextField 会被拉伸成大块空白。
        passwordPanel.add(passwordField, BorderLayout.NORTH);

        // 私钥认证面板
        keyPanel = new JPanel();
        keyPanel.setLayout(new BoxLayout(keyPanel, BoxLayout.Y_AXIS));

        JPanel keySourceBox = new JPanel(new BorderLayout(4, 4));
        keySourceCombo = new JComboBox<>(SshConnectionConfig.KeySource.values());
        keySourceCombo.setSelectedItem(config.getKeySource());
        keySourceBox.add(new JLabel("私钥来源: "), BorderLayout.WEST);
        keySourceBox.add(keySourceCombo, BorderLayout.CENTER);

        JPanel keyPathBox = new JPanel(new BorderLayout(4, 4));
        keyPathField = Fields.text(config.getKeyPath());
        browseKeyBtn = Buttons.secondary("浏览...");
        browseKeyBtn.addActionListener(e -> chooseKeyFile());
        keyPathBox.add(keyPathField, BorderLayout.CENTER);
        keyPathBox.add(browseKeyBtn, BorderLayout.EAST);

        keyContentArea = new JTextArea(4, 30);
        keyContentArea.setFont(Tokens.fontMono());
        keyContentArea.setText(config.getKeyContent());
        JScrollPane keyScroll = new JScrollPane(keyContentArea);

        JPanel passphraseBox = new JPanel(new BorderLayout(4, 4));
        passphraseField = Fields.password();
        passphraseField.setText(SshSecurityUtils.decrypt(config.getEncryptedPassphrase()));
        passphraseBox.add(new JLabel("私钥口令(Passphrase): "), BorderLayout.WEST);
        passphraseBox.add(passphraseField, BorderLayout.CENTER);

        keyPanel.add(keySourceBox);
        keyPanel.add(Box.createVerticalStrut(6));
        keyPanel.add(keyPathBox);
        keyPanel.add(Box.createVerticalStrut(6));
        keyPanel.add(keyScroll);
        keyPanel.add(Box.createVerticalStrut(6));
        keyPanel.add(passphraseBox);

        JPanel authCard = new JPanel(new CardLayout());
        authCard.add(passwordPanel, "PASSWORD");
        authCard.add(keyPanel, "PRIVATE_KEY");

        CardLayout cardLayout = (CardLayout) authCard.getLayout();
        authTypeCombo.addActionListener(e -> {
            SshConnectionConfig.AuthType sel = (SshConnectionConfig.AuthType) authTypeCombo.getSelectedItem();
            cardLayout.show(authCard, sel == SshConnectionConfig.AuthType.PASSWORD ? "PASSWORD" : "PRIVATE_KEY");
        });
        cardLayout.show(authCard, config.getAuthType() == SshConnectionConfig.AuthType.PASSWORD ? "PASSWORD" : "PRIVATE_KEY");

        form.row("身份凭据:", authCard);

        basicPanel.add(form, BorderLayout.CENTER);
        tabbedPane.addTab("基本配置", basicPanel);

        // 2. 高级参数配置页
        JPanel advPanel = new JPanel(new BorderLayout());
        advPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        FormGrid advForm = new FormGrid();

        timeoutSpinner = new JSpinner(new SpinnerNumberModel(config.getConnectTimeoutMs() / 1000, 1, 120, 1));
        keepAliveSpinner = new JSpinner(new SpinnerNumberModel(config.getKeepAliveSec(), 0, 300, 5));
        autoReconnectCheck = new JCheckBox("连接断开时自动重连", config.isAutoReconnect());

        remarksArea = new JTextArea(4, 30);
        remarksArea.setText(config.getRemarks());

        advForm.row("连接超时(秒):", timeoutSpinner);
        advForm.row("心跳保活(秒):", keepAliveSpinner);
        advForm.row("重连设置:", autoReconnectCheck);
        advForm.row("备注说明:", new JScrollPane(remarksArea));

        advPanel.add(advForm, BorderLayout.CENTER);
        tabbedPane.addTab("高级参数", advPanel);

        add(tabbedPane, BorderLayout.CENTER);

        // 3. 底部按钮栏
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton testBtn = Buttons.secondary("测试连接");
        testBtn.addActionListener(e -> testConnection());

        JButton cancelBtn = Buttons.secondary("取消");
        cancelBtn.addActionListener(e -> dispose());

        JButton saveBtn = Buttons.primary("保存配置");
        saveBtn.addActionListener(e -> saveConfig());

        bottomBar.add(testBtn);
        bottomBar.add(cancelBtn);
        bottomBar.add(saveBtn);
        add(bottomBar, BorderLayout.SOUTH);
    }

    private void chooseKeyFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 SSH 私钥文件");
        if (keyPathField.getText() != null && !keyPathField.getText().trim().isEmpty()) {
            chooser.setSelectedFile(new File(keyPathField.getText().trim()));
        }
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            keyPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private SshConnectionConfig buildFromForm() {
        SshConnectionConfig current = config.clone();
        current.setName(nameField.getText().trim());
        current.setGroup(groupField.getText().trim());
        current.setHost(hostField.getText().trim());
        current.setPort((Integer) portSpinner.getValue());
        current.setUsername(usernameField.getText().trim());

        SshConnectionConfig.AuthType authType = (SshConnectionConfig.AuthType) authTypeCombo.getSelectedItem();
        current.setAuthType(authType);

        if (authType == SshConnectionConfig.AuthType.PASSWORD) {
            String pass = new String(passwordField.getPassword());
            current.setEncryptedPassword(SshSecurityUtils.encrypt(pass));
        } else {
            current.setKeySource((SshConnectionConfig.KeySource) keySourceCombo.getSelectedItem());
            current.setKeyPath(keyPathField.getText().trim());
            current.setKeyContent(keyContentArea.getText().trim());
            String pass = new String(passphraseField.getPassword());
            current.setEncryptedPassphrase(SshSecurityUtils.encrypt(pass));
        }

        current.setConnectTimeoutMs(((Number) timeoutSpinner.getValue()).intValue() * 1000);
        current.setKeepAliveSec(((Number) keepAliveSpinner.getValue()).intValue());
        current.setAutoReconnect(autoReconnectCheck.isSelected());
        current.setRemarks(remarksArea.getText());

        return current;
    }

    private void testConnection() {
        SshConnectionConfig cfg = buildFromForm();
        if (cfg.getHost() == null || cfg.getHost().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入服务器地址 (Host)", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        JDialog loadingDialog = new JDialog(this, "测试连接中...", true);
        loadingDialog.setLayout(new FlowLayout(FlowLayout.CENTER, 20, 20));
        loadingDialog.add(new JLabel("正在建立 SSH 连接，请稍候..."));
        loadingDialog.setSize(300, 100);
        loadingDialog.setLocationRelativeTo(this);

        new Thread(() -> {
            SshSessionInstance session = new SshSessionInstance(cfg);
            boolean ok;
            try {
                ok = session.connectSync();
            } finally {
                session.close();
            }

            SwingUtilities.invokeLater(() -> {
                loadingDialog.dispose();
                if (ok) {
                    JOptionPane.showMessageDialog(this, "测试连接成功！能够正常建立 SSH 会话。", "成功", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this, "测试连接失败:\n" + session.getLastErrorMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            });
        }).start();

        loadingDialog.setVisible(true);
    }

    private void saveConfig() {
        if (hostField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入服务器地址 (Host)", "校验失败", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (usernameField.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入用户名", "校验失败", JOptionPane.WARNING_MESSAGE);
            return;
        }

        this.config.setName(nameField.getText().trim());
        this.config.setGroup(groupField.getText().trim());
        this.config.setHost(hostField.getText().trim());
        this.config.setPort((Integer) portSpinner.getValue());
        this.config.setUsername(usernameField.getText().trim());

        SshConnectionConfig.AuthType authType = (SshConnectionConfig.AuthType) authTypeCombo.getSelectedItem();
        this.config.setAuthType(authType);

        if (authType == SshConnectionConfig.AuthType.PASSWORD) {
            String pass = new String(passwordField.getPassword());
            this.config.setEncryptedPassword(SshSecurityUtils.encrypt(pass));
        } else {
            this.config.setKeySource((SshConnectionConfig.KeySource) keySourceCombo.getSelectedItem());
            this.config.setKeyPath(keyPathField.getText().trim());
            this.config.setKeyContent(keyContentArea.getText().trim());
            String pass = new String(passphraseField.getPassword());
            this.config.setEncryptedPassphrase(SshSecurityUtils.encrypt(pass));
        }

        this.config.setConnectTimeoutMs(((Number) timeoutSpinner.getValue()).intValue() * 1000);
        this.config.setKeepAliveSec(((Number) keepAliveSpinner.getValue()).intValue());
        this.config.setAutoReconnect(autoReconnectCheck.isSelected());
        this.config.setRemarks(remarksArea.getText());

        this.saved = true;
        dispose();
    }

    public boolean isSaved() {
        return saved;
    }

    public SshConnectionConfig getConfig() {
        return config;
    }
}
