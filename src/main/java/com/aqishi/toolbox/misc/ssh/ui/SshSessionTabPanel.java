package com.aqishi.toolbox.misc.ssh.ui;

import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.session.SshSessionInstance;
import com.aqishi.toolbox.misc.ssh.session.SshTtyConnector;
import com.aqishi.toolbox.misc.ssh.sftp.SftpPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Tokens;

import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;

/**
 * 单个 SSH 会话 Tab 面板：集成 JediTerm 终端与 SFTP 文件传输
 */
public class SshSessionTabPanel extends JPanel implements SshSessionInstance.SessionListener {

    private final SshConnectionConfig config;
    private final SshSessionInstance sessionInstance;

    private final JLabel statusDotLabel;
    private final JLabel statusTextLabel;
    private final JButton reconnectBtn;
    private final JButton disconnectBtn;

    private JediTermWidget terminalWidget;
    private SshTtyConnector activeConnector;
    private SftpPanel sftpPanel;
    private SshTunnelPanel tunnelPanel;
    private JTabbedPane contentTabs;

    public SshSessionTabPanel(SshConnectionConfig config) {
        this.config = config;
        this.sessionInstance = new SshSessionInstance(config);
        this.sessionInstance.addListener(this);

        setLayout(new BorderLayout());

        // 1. 顶部控制状态条
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        topBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Tokens.border()),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
        ));

        JPanel leftBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JLabel titleLabel = new JLabel(config.toString());
        titleLabel.setFont(Tokens.fontSectionTitle());

        statusDotLabel = new JLabel("●");
        statusDotLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        statusDotLabel.setForeground(Color.GRAY);

        statusTextLabel = new JLabel("未连接");
        statusTextLabel.setFont(Tokens.fontCaption());

        leftBox.add(titleLabel);
        leftBox.add(statusDotLabel);
        leftBox.add(statusTextLabel);

        JPanel rightBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        reconnectBtn = Buttons.secondary("重新连接");
        reconnectBtn.addActionListener(e -> doConnect());

        disconnectBtn = Buttons.secondary("断开");
        disconnectBtn.addActionListener(e -> sessionInstance.disconnect());

        JButton clearBtn = Buttons.secondary("清屏");
        clearBtn.addActionListener(e -> clearTerminal());

        JButton pasteBtn = Buttons.secondary("粘贴");
        pasteBtn.addActionListener(e -> pasteToTerminal());

        rightBox.add(clearBtn);
        rightBox.add(pasteBtn);
        rightBox.add(reconnectBtn);
        rightBox.add(disconnectBtn);

        topBar.add(leftBox, BorderLayout.WEST);
        topBar.add(rightBox, BorderLayout.EAST);

        add(topBar, BorderLayout.NORTH);

        // 2. 主体选项卡区（终端 / SFTP 文件传输）
        contentTabs = new JTabbedPane();
        contentTabs.setFont(Tokens.fontSectionTitle());

        // 终端 Shell 界面
        JPanel termContainer = new JPanel(new BorderLayout());
        termContainer.setBackground(Color.BLACK);

        try {
            DefaultSettingsProvider settings = new DefaultSettingsProvider() {
                @Override
                public Font getTerminalFont() {
                    return Tokens.fontMono().deriveFont(Font.PLAIN, 14f);
                }
            };
            terminalWidget = new JediTermWidget(settings);
            termContainer.add(terminalWidget, BorderLayout.CENTER);
        } catch (Exception e) {
            JLabel errLabel = new JLabel("初始化终端组件失败: " + e.getMessage(), SwingConstants.CENTER);
            errLabel.setForeground(Color.RED);
            termContainer.add(errLabel, BorderLayout.CENTER);
        }

        contentTabs.addTab("终端 Command Shell", termContainer);

        // SFTP 文件管理界面
        sftpPanel = new SftpPanel(sessionInstance);
        contentTabs.addTab("SFTP 文件管理", sftpPanel);

        // 端口转发 / 服务隧道界面
        tunnelPanel = new SshTunnelPanel(sessionInstance);
        contentTabs.addTab("端口转发 / 服务隧道", tunnelPanel);

        // 监听 Tab 切换，自动刷新 SFTP 目录与隧道列表
        contentTabs.addChangeListener(e -> {
            int sel = contentTabs.getSelectedIndex();
            if (sel == 1) {
                sftpPanel.refresh();
            } else if (sel == 2) {
                tunnelPanel.refreshTable();
            }
        });

        add(contentTabs, BorderLayout.CENTER);

        // 3. 初始发起连接
        doConnect();
    }

    public SshSessionInstance getSessionInstance() {
        return sessionInstance;
    }

    public void doConnect() {
        reconnectBtn.setEnabled(false);
        disconnectBtn.setEnabled(true);

        sessionInstance.connectAsync(success -> SwingUtilities.invokeLater(() -> {
            reconnectBtn.setEnabled(true);
            if (success) {
                attachTerminal();
                sftpPanel.refresh();
                tunnelPanel.refreshTable();
            }
        }));
    }

    private void attachTerminal() {
        SshTtyConnector connector = sessionInstance.getTtyConnector();
        if (terminalWidget == null || connector == null || connector == activeConnector) return;
        try {
            terminalWidget.setTtyConnector(connector);
            terminalWidget.start();
            activeConnector = connector;
        } catch (Exception error) {
            statusTextLabel.setText("终端恢复失败: " + error.getMessage());
        }
    }

    private void clearTerminal() {
        if (sessionInstance.isConnected()) {
            SshTtyConnector conn = sessionInstance.getTtyConnector();
            if (conn != null) {
                try {
                    conn.write("clear\n");
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void pasteToTerminal() {
        if (sessionInstance.isConnected()) {
            try {
                String text = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                        .getData(DataFlavor.stringFlavor);
                if (text != null && !text.isEmpty()) {
                    SshTtyConnector conn = sessionInstance.getTtyConnector();
                    if (conn != null) {
                        conn.write(text);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onStatusChanged(SshSessionInstance.Status status, String message) {
        SwingUtilities.invokeLater(() -> {
            statusTextLabel.setText(status.getLabel() + (message != null && !message.isEmpty() ? " - " + message : ""));
            switch (status) {
                case CONNECTED:
                    statusDotLabel.setForeground(new Color(40, 167, 69)); // Green
                    reconnectBtn.setEnabled(true);
                    disconnectBtn.setEnabled(true);
                    attachTerminal();
                    if (sftpPanel != null) sftpPanel.refresh();
                    if (tunnelPanel != null) tunnelPanel.refreshTable();
                    break;
                case CONNECTING:
                    statusDotLabel.setForeground(new Color(255, 193, 7)); // Yellow
                    reconnectBtn.setEnabled(false);
                    disconnectBtn.setEnabled(true);
                    break;
                case ERROR:
                    statusDotLabel.setForeground(new Color(220, 53, 69)); // Red
                    reconnectBtn.setEnabled(true);
                    disconnectBtn.setEnabled(false);
                    break;
                case DISCONNECTED:
                default:
                    statusDotLabel.setForeground(Color.GRAY);
                    reconnectBtn.setEnabled(true);
                    disconnectBtn.setEnabled(false);
                    break;
            }
        });
    }

    public void closeSession() {
        if (sessionInstance != null) {
            sessionInstance.removeListener(this);
            sessionInstance.close();
        }
    }
}
