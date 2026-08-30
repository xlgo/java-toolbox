package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.infra.network.WebSocketResource;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket / 长连接测试客户端面板
 */
public class WebSocketClientPanel extends ToolPanel implements ManagedResourceOwner {

    private JTextField urlField;
    private JButton connectBtn;
    private JButton disconnectBtn;
    private JLabel statusLabel;

    private DefaultTableModel headersTableModel;
    private JTable headersTable;

    private JTextArea sendTextArea;
    private JTextArea logTextArea;

    private JCheckBox heartbeatCheckBox;
    private JSpinner heartbeatIntervalSpinner;
    private JTextField heartbeatPayloadField;
    private Timer heartbeatTimer;

    private WebSocketClient webSocketClient;
    private WebSocketResource webSocketResource;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");

    public WebSocketClientPanel() {
        super("misc", "websocket.client", "websocket", "ws", "wss", "socket", "connect", "client", "测试", "长连接");
    }

    @Override
    protected JComponent build() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 12));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        // --- 顶栏：WebSocket 地址与连接控制 ---
        Card topCard = Card.plain();
        topCard.setLayout(new BorderLayout(12, 12));
        topCard.setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel urlPanel = new JPanel(new BorderLayout(8, 0));
        urlPanel.add(new JLabel("WebSocket 地址 (ws:// 或 wss://): "), BorderLayout.WEST);

        urlField = new JTextField("wss://echo.websocket.events");
        urlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        urlPanel.add(urlField, BorderLayout.CENTER);

        JPanel connControlBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        statusLabel = new JLabel("未连接", SwingConstants.CENTER);
        statusLabel.setOpaque(true);
        statusLabel.setBackground(Color.LIGHT_GRAY);
        statusLabel.setForeground(Color.BLACK);
        statusLabel.setBorder(new EmptyBorder(4, 12, 4, 12));

        connectBtn = new JButton("建立连接");
        connectBtn.setFont(connectBtn.getFont().deriveFont(Font.BOLD));
        connectBtn.addActionListener(e -> connectWebSocket());

        disconnectBtn = new JButton("断开连接");
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> disconnectWebSocket());

        connControlBar.add(new JLabel("公共预设:"));
        JComboBox<String> presetCombo = new JComboBox<>(new String[]{
                "wss://echo.websocket.events",
                "wss://socketsbay.com/wss/v2/1/demo/",
                "ws://localhost:8080/ws"
        });
        presetCombo.addActionListener(e -> urlField.setText((String) presetCombo.getSelectedItem()));
        connControlBar.add(presetCombo);

        connControlBar.add(statusLabel);
        connControlBar.add(connectBtn);
        connControlBar.add(disconnectBtn);

        topCard.add(urlPanel, BorderLayout.CENTER);
        topCard.add(connControlBar, BorderLayout.SOUTH);

        // --- 中间 Split 面板：左发送控制 / 右收发日志流 ---
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setResizeWeight(0.45);

        // 左侧面板 (请求头 + 发送区 + 心跳配置)
        JPanel leftPanel = new JPanel(new BorderLayout(0, 12));
        leftPanel.setBorder(new EmptyBorder(0, 0, 0, 8));

        // 请求头配置
        Card headersCard = Card.plain();
        headersCard.setLayout(new BorderLayout(0, 6));
        headersCard.setBorder(new EmptyBorder(8, 8, 8, 8));

        headersCard.add(new JLabel("握手 Request Headers:"), BorderLayout.NORTH);
        String[] headerCols = {"Header Name", "Header Value"};
        headersTableModel = new DefaultTableModel(headerCols, 0);
        headersTable = new JTable(headersTableModel);
        headersTable.setRowHeight(22);
        headersTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel headerBtnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addHeaderBtn = new JButton("+ 请求头");
        addHeaderBtn.addActionListener(e -> headersTableModel.addRow(new Object[]{"Authorization", "Bearer token"}));

        JButton delHeaderBtn = new JButton("- 删除");
        delHeaderBtn.addActionListener(e -> {
            int row = headersTable.getSelectedRow();
            if (row >= 0) headersTableModel.removeRow(row);
        });

        headerBtnBar.add(addHeaderBtn);
        headerBtnBar.add(delHeaderBtn);

        headersCard.add(new JScrollPane(headersTable), BorderLayout.CENTER);
        headersCard.add(headerBtnBar, BorderLayout.SOUTH);

        // 消息发送卡片
        Card sendCard = Card.plain();
        sendCard.setLayout(new BorderLayout(0, 8));
        sendCard.setBorder(new EmptyBorder(8, 8, 8, 8));

        sendCard.add(new JLabel("发送文本消息 (Text / JSON):"), BorderLayout.NORTH);
        sendTextArea = new JTextArea("{\n  \"action\": \"ping\",\n  \"data\": \"Hello WebSocket\"\n}");
        sendTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JPanel sendToolBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton formatJsonBtn = new JButton("格式化 JSON");
        formatJsonBtn.addActionListener(e -> formatSendTextJson());

        JButton sendMsgBtn = new JButton("发送消息");
        sendMsgBtn.setFont(sendMsgBtn.getFont().deriveFont(Font.BOLD));
        sendMsgBtn.addActionListener(e -> sendTextMessage());

        sendToolBar.add(formatJsonBtn);
        sendToolBar.add(sendMsgBtn);

        sendCard.add(new JScrollPane(sendTextArea), BorderLayout.CENTER);
        sendCard.add(sendToolBar, BorderLayout.SOUTH);

        // 心跳保活卡片
        Card heartbeatCard = Card.plain();
        heartbeatCard.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));

        heartbeatCheckBox = new JCheckBox("开启定时心跳包");
        heartbeatIntervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
        heartbeatPayloadField = new JTextField("ping", 8);

        heartbeatCheckBox.addActionListener(e -> toggleHeartbeatTimer());

        heartbeatCard.add(heartbeatCheckBox);
        heartbeatCard.add(new JLabel("间隔 (秒):"));
        heartbeatCard.add(heartbeatIntervalSpinner);
        heartbeatCard.add(new JLabel("内容:"));
        heartbeatCard.add(heartbeatPayloadField);

        leftPanel.add(headersCard, BorderLayout.NORTH);
        leftPanel.add(sendCard, BorderLayout.CENTER);
        leftPanel.add(heartbeatCard, BorderLayout.SOUTH);

        // 右侧面板 (收发调试日志)
        Card rightCard = Card.plain();
        rightCard.setLayout(new BorderLayout(0, 8));
        rightCard.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel logHeader = new JPanel(new BorderLayout());
        logHeader.add(new JLabel("调试日志与消息流 (Message Stream Log):"), BorderLayout.WEST);

        JPanel logActionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton clearLogBtn = new JButton("清空日志");
        clearLogBtn.addActionListener(e -> logTextArea.setText(""));

        JButton copyLogBtn = new JButton("复制日志");
        copyLogBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(logTextArea.getText());
            JOptionPane.showMessageDialog(getView(), "已复制日志到剪贴板", "提示", JOptionPane.INFORMATION_MESSAGE);
        });

        logActionBtns.add(clearLogBtn);
        logActionBtns.add(copyLogBtn);
        logHeader.add(logActionBtns, BorderLayout.EAST);

        logTextArea = new JTextArea();
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        logTextArea.setEditable(false);

        rightCard.add(logHeader, BorderLayout.NORTH);
        rightCard.add(new JScrollPane(logTextArea), BorderLayout.CENTER);

        mainSplit.setLeftComponent(leftPanel);
        mainSplit.setRightComponent(rightCard);

        mainPanel.add(topCard, BorderLayout.NORTH);
        mainPanel.add(mainSplit, BorderLayout.CENTER);

        return mainPanel;
    }

    private void connectWebSocket() {
        String urlStr = urlField.getText().trim();
        if (urlStr.isEmpty()) {
            JOptionPane.showMessageDialog(getView(), "请输入有效的 WebSocket URL", "警告", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            closeResources();
            URI uri = new URI(urlStr);
            Map<String, String> headers = new HashMap<>();
            for (int i = 0; i < headersTableModel.getRowCount(); i++) {
                String k = String.valueOf(headersTableModel.getValueAt(i, 0)).trim();
                String v = String.valueOf(headersTableModel.getValueAt(i, 1)).trim();
                if (!k.isEmpty()) headers.put(k, v);
            }

            statusLabel.setText("正在连接...");
            statusLabel.setBackground(Color.ORANGE);
            connectBtn.setEnabled(false);

            appendLog("[SYSTEM]", "正在尝试连接到 " + uri);

            webSocketClient = new WebSocketClient(uri, headers) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("已建立连接");
                        statusLabel.setBackground(new Color(40, 167, 69));
                        statusLabel.setForeground(Color.WHITE);
                        connectBtn.setEnabled(false);
                        disconnectBtn.setEnabled(true);
                        appendLog("[CONNECTED]", "WebSocket 连接握手成功！HTTP Status: " + handshakedata.getHttpStatus());
                    });
                }

                @Override
                public void onMessage(String message) {
                    SwingUtilities.invokeLater(() -> appendLog("[RECV]", message));
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("已断开");
                        statusLabel.setBackground(Color.LIGHT_GRAY);
                        statusLabel.setForeground(Color.BLACK);
                        connectBtn.setEnabled(true);
                        disconnectBtn.setEnabled(false);
                        appendLog("[CLOSED]", "连接已关闭 (code: " + code + ", reason: " + reason + ")");
                        stopHeartbeatTimer();
                    });
                }

                @Override
                public void onError(Exception ex) {
                    SwingUtilities.invokeLater(() -> appendLog("[ERROR]", "发生异常: " + ex.getMessage()));
                }
            };
            webSocketResource = new WebSocketResource(webSocketClient);

            webSocketClient.connect();
        } catch (Exception e) {
            closeResources();
            statusLabel.setText("连接失败");
            statusLabel.setBackground(Color.RED);
            connectBtn.setEnabled(true);
            appendLog("[ERROR]", "创建连接失败: " + e.getMessage());
        }
    }

    private void disconnectWebSocket() {
        WebSocketResource resource = webSocketResource;
        webSocketResource = null;
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
                appendLog("[ERROR]", "关闭连接异常: " + e.getMessage());
            }
        } else if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    /** Releases the socket and heartbeat timer without relying on visible UI. */
    @Override
    public void closeResources() {
        if (heartbeatTimer != null) {
            heartbeatTimer.stop();
            heartbeatTimer = null;
        }
        WebSocketResource resource = webSocketResource;
        webSocketResource = null;
        if (resource != null) resource.close();
        else if (webSocketClient != null) webSocketClient.close();
    }

    private void sendTextMessage() {
        if (webSocketClient == null || !webSocketClient.isOpen()) {
            JOptionPane.showMessageDialog(getView(), "请先建立 WebSocket 连接后再发送消息！", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String msg = sendTextArea.getText();
        if (msg.isEmpty()) return;

        try {
            webSocketClient.send(msg);
            appendLog("[SENT]", msg);
        } catch (Exception e) {
            appendLog("[ERROR]", "发送消息失败: " + e.getMessage());
        }
    }

    private void toggleHeartbeatTimer() {
        if (heartbeatCheckBox.isSelected()) {
            int intervalSec = (Integer) heartbeatIntervalSpinner.getValue();
            if (heartbeatTimer != null) heartbeatTimer.stop();

            heartbeatTimer = new Timer(intervalSec * 1000, e -> {
                if (webSocketClient != null && webSocketClient.isOpen()) {
                    String payload = heartbeatPayloadField.getText();
                    webSocketClient.send(payload);
                    appendLog("[HEARTBEAT]", payload);
                }
            });
            heartbeatTimer.start();
            appendLog("[SYSTEM]", "已开启定时心跳，间隔 " + intervalSec + " 秒");
        } else {
            stopHeartbeatTimer();
        }
    }

    private void stopHeartbeatTimer() {
        if (heartbeatTimer != null) {
            heartbeatTimer.stop();
            heartbeatTimer = null;
            appendLog("[SYSTEM]", "已停止定时心跳");
        }
    }

    private void appendLog(String tag, String text) {
        String timestamp = dateFormat.format(new Date());
        logTextArea.append(String.format("%s %s %s\n", timestamp, tag, text));
        logTextArea.setCaretPosition(logTextArea.getDocument().getLength());
    }

    private void formatSendTextJson() {
        String input = sendTextArea.getText().trim();
        if (input.isEmpty()) return;
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Object obj = mapper.readValue(input, Object.class);
            sendTextArea.setText(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(getView(), "JSON 格式化失败: " + e.getMessage(), "提示", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
