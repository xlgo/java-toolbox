package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.network.application.BoundedLogBuffer;
import com.aqishi.toolbox.feature.network.application.HeartbeatScheduler;
import com.aqishi.toolbox.feature.network.application.WebSocketSession;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.net.URI;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * WebSocket / long-connection test client.
 *
 * <p>All connection, send and close work runs on a {@link WebSocketSession}
 * daemon thread; the panel only reacts to session events posted back to the EDT.
 * The heartbeat follows the connection lifecycle, so a reconnect always restarts
 * it exactly once. The debug log is bounded and flushed in batches.</p>
 */
public class WebSocketClientPanel extends ToolPanel implements ManagedResourceOwner {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private JTextField urlField;
    private JButton connectBtn;
    private JButton disconnectBtn;
    private JLabel statusLabel;

    private DefaultTableModel headersTableModel;
    private JTable headersTable;

    private JTextArea sendTextArea;
    private JTextArea logTextArea;
    private JLabel logStatsLabel;
    private volatile BoundedTextLog log;

    private JCheckBox heartbeatCheckBox;
    private JSpinner heartbeatIntervalSpinner;
    private JTextField heartbeatPayloadField;

    private final WebSocketSession session;
    private State currentState = State.DISCONNECTED;

    public WebSocketClientPanel() {
        this(null);
    }

    /** Test seam: {@code ticker == null} uses a real daemon heartbeat thread. */
    WebSocketClientPanel(HeartbeatScheduler.Ticker ticker) {
        super(ToolCatalog.WEBSOCKET_CLIENT);
        this.session = ticker == null
                ? new WebSocketSession(new PanelListener(), SwingUtilities::invokeLater)
                : new WebSocketSession(new PanelListener(), SwingUtilities::invokeLater, ticker);
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
        urlPanel.add(new JLabel(I18n.get("tool.websocket.url")), BorderLayout.WEST);

        urlField = new JTextField("wss://echo.websocket.events");
        urlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        urlPanel.add(urlField, BorderLayout.CENTER);

        JPanel connControlBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        statusLabel = new JLabel(I18n.get("tool.websocket.status.disconnected"), SwingConstants.CENTER);
        statusLabel.setOpaque(true);
        statusLabel.setBackground(Color.LIGHT_GRAY);
        statusLabel.setForeground(Color.BLACK);
        statusLabel.setBorder(new EmptyBorder(4, 12, 4, 12));

        connectBtn = new JButton(I18n.get("tool.websocket.btn.connect"));
        connectBtn.setFont(connectBtn.getFont().deriveFont(Font.BOLD));
        connectBtn.addActionListener(e -> connectWebSocket());

        disconnectBtn = new JButton(I18n.get("tool.websocket.btn.disconnect"));
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> disconnectWebSocket());

        connControlBar.add(new JLabel(I18n.get("tool.websocket.preset")));
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

        headersCard.add(new JLabel(I18n.get("tool.websocket.headers")), BorderLayout.NORTH);
        String[] headerCols = {"Header Name", "Header Value"};
        headersTableModel = new DefaultTableModel(headerCols, 0);
        headersTable = new JTable(headersTableModel);
        headersTable.setRowHeight(22);
        headersTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel headerBtnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addHeaderBtn = new JButton(I18n.get("tool.websocket.headers.add"));
        addHeaderBtn.addActionListener(e -> headersTableModel.addRow(new Object[]{"Authorization", "Bearer token"}));

        JButton delHeaderBtn = new JButton(I18n.get("tool.websocket.headers.remove"));
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

        sendCard.add(new JLabel(I18n.get("tool.websocket.send.title")), BorderLayout.NORTH);
        sendTextArea = new JTextArea("{\n  \"action\": \"ping\",\n  \"data\": \"Hello WebSocket\"\n}");
        sendTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JPanel sendToolBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton formatJsonBtn = new JButton(I18n.get("tool.websocket.send.format"));
        formatJsonBtn.addActionListener(e -> formatSendTextJson());

        JButton sendMsgBtn = new JButton(I18n.get("tool.websocket.send.btn"));
        sendMsgBtn.setFont(sendMsgBtn.getFont().deriveFont(Font.BOLD));
        sendMsgBtn.addActionListener(e -> sendTextMessage());

        sendToolBar.add(formatJsonBtn);
        sendToolBar.add(sendMsgBtn);

        sendCard.add(new JScrollPane(sendTextArea), BorderLayout.CENTER);
        sendCard.add(sendToolBar, BorderLayout.SOUTH);

        // 心跳保活卡片
        Card heartbeatCard = Card.plain();
        heartbeatCard.setLayout(new FlowLayout(FlowLayout.LEFT, 8, 4));

        heartbeatCheckBox = new JCheckBox(I18n.get("tool.websocket.heartbeat.enable"));
        heartbeatIntervalSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 60, 1));
        heartbeatPayloadField = new JTextField("ping", 8);

        heartbeatCheckBox.addActionListener(e -> applyHeartbeatSettings(true));
        heartbeatIntervalSpinner.addChangeListener(e -> applyHeartbeatSettings(false));
        heartbeatPayloadField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyHeartbeatSettings(false);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyHeartbeatSettings(false);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyHeartbeatSettings(false);
            }
        });

        heartbeatCard.add(heartbeatCheckBox);
        heartbeatCard.add(new JLabel(I18n.get("tool.websocket.heartbeat.interval")));
        heartbeatCard.add(heartbeatIntervalSpinner);
        heartbeatCard.add(new JLabel(I18n.get("tool.websocket.heartbeat.payload")));
        heartbeatCard.add(heartbeatPayloadField);

        leftPanel.add(headersCard, BorderLayout.NORTH);
        leftPanel.add(sendCard, BorderLayout.CENTER);
        leftPanel.add(heartbeatCard, BorderLayout.SOUTH);

        // 右侧面板 (收发调试日志)
        Card rightCard = Card.plain();
        rightCard.setLayout(new BorderLayout(0, 8));
        rightCard.setBorder(new EmptyBorder(8, 8, 8, 8));

        JPanel logHeader = new JPanel(new BorderLayout());
        logHeader.add(new JLabel(I18n.get("tool.websocket.log.title")), BorderLayout.WEST);

        JPanel logActionBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton clearLogBtn = new JButton(I18n.get("tool.websocket.log.clear"));
        clearLogBtn.addActionListener(e -> log.clear());

        JButton copyLogBtn = new JButton(I18n.get("tool.websocket.log.copy"));
        copyLogBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(logTextArea.getText());
            UIUtils.info(getView(), I18n.get("tool.websocket.log.copied"));
        });

        logStatsLabel = new JLabel();
        logActionBtns.add(logStatsLabel);
        logActionBtns.add(clearLogBtn);
        logActionBtns.add(copyLogBtn);
        logHeader.add(logActionBtns, BorderLayout.EAST);

        logTextArea = new JTextArea();
        logTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        logTextArea.setEditable(false);
        log = new BoundedTextLog(logTextArea, BoundedLogBuffer.DEFAULT_MAX_RETAINED, l -> updateLogStats());
        updateLogStats();

        rightCard.add(logHeader, BorderLayout.NORTH);
        rightCard.add(new JScrollPane(logTextArea), BorderLayout.CENTER);

        mainSplit.setLeftComponent(leftPanel);
        mainSplit.setRightComponent(rightCard);

        mainPanel.add(topCard, BorderLayout.NORTH);
        mainPanel.add(mainSplit, BorderLayout.CENTER);

        applyState(State.DISCONNECTED);
        log.start();
        return mainPanel;
    }

    private enum State { DISCONNECTED, CONNECTING, CONNECTED }

    private void connectWebSocket() {
        String urlStr = urlField.getText().trim();
        if (urlStr.isEmpty()) {
            UIUtils.warn(getView(), I18n.get("tool.websocket.warn.noUrl"), I18n.get("tool.websocket.warn.title"));
            return;
        }
        URI uri;
        try {
            uri = new URI(urlStr);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException(urlStr);
            }
        } catch (Exception e) {
            applyState(State.DISCONNECTED);
            appendLog("[ERROR]", I18n.get("tool.websocket.log.createFailed", e.getMessage()));
            return;
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i < headersTableModel.getRowCount(); i++) {
            String k = String.valueOf(headersTableModel.getValueAt(i, 0)).trim();
            String v = String.valueOf(headersTableModel.getValueAt(i, 1)).trim();
            if (!k.isEmpty()) headers.put(k, v);
        }

        applyHeartbeatSettings(false);
        applyState(State.CONNECTING);
        appendLog("[SYSTEM]", I18n.get("tool.websocket.log.connecting", uri));
        session.connect(uri, headers);
    }

    private void disconnectWebSocket() {
        // The session drops every late event of this socket, so the panel settles its own state.
        session.disconnect();
        applyState(State.DISCONNECTED);
        appendLog("[CLOSED]", I18n.get("tool.websocket.log.closedByUser"));
    }

    /** Releases the socket, heartbeat and log timer without relying on visible UI. */
    @Override
    public void closeResources() {
        BoundedTextLog l = log;
        if (l != null) {
            l.stop();
        }
        session.close();
    }

    private void sendTextMessage() {
        if (currentState != State.CONNECTED) {
            UIUtils.warn(getView(), I18n.get("tool.websocket.warn.notConnected"), I18n.get("tool.websocket.tip"));
            return;
        }
        String msg = sendTextArea.getText();
        if (msg.isEmpty()) return;
        session.send(msg);
    }

    /**
     * Pushes the heartbeat controls into the session. The session keeps the
     * setting across reconnects and runs the timer only while connected.
     */
    private void applyHeartbeatSettings(boolean announce) {
        boolean enabled = heartbeatCheckBox.isSelected();
        int intervalSec = (Integer) heartbeatIntervalSpinner.getValue();
        session.configureHeartbeat(enabled, intervalSec * 1000L, heartbeatPayloadField.getText());
        if (announce) {
            appendLog("[SYSTEM]", enabled
                    ? I18n.get("tool.websocket.log.heartbeatOn", intervalSec)
                    : I18n.get("tool.websocket.log.heartbeatOff"));
        }
    }

    private void applyState(State state) {
        currentState = state;
        switch (state) {
            case CONNECTED -> {
                statusLabel.setText(I18n.get("tool.websocket.status.connected"));
                statusLabel.setBackground(new Color(40, 167, 69));
                statusLabel.setForeground(Color.WHITE);
            }
            case CONNECTING -> {
                statusLabel.setText(I18n.get("tool.websocket.status.connecting"));
                statusLabel.setBackground(Color.ORANGE);
                statusLabel.setForeground(Color.BLACK);
            }
            default -> {
                statusLabel.setText(I18n.get("tool.websocket.status.disconnected"));
                statusLabel.setBackground(Color.LIGHT_GRAY);
                statusLabel.setForeground(Color.BLACK);
            }
        }
        connectBtn.setEnabled(state == State.DISCONNECTED);
        disconnectBtn.setEnabled(state != State.DISCONNECTED);
    }

    /** Thread-safe: only queues the line; the log timer renders it on the EDT. */
    private void appendLog(String tag, String text) {
        BoundedTextLog l = log;
        if (l != null) {
            l.append(LOG_TIME.format(LocalTime.now()) + " " + tag + " " + text);
        }
    }

    private void updateLogStats() {
        BoundedTextLog l = log;
        if (l == null || logStatsLabel == null) {
            return;
        }
        String text = "";
        if (l.trimmedTotal() > 0 || l.droppedTotal() > 0) {
            text = I18n.get("tool.websocket.log.trimmed", l.trimmedTotal(), l.droppedTotal(), l.maxLines());
        }
        logStatsLabel.setText(text);
    }

    private void formatSendTextJson() {
        String input = sendTextArea.getText().trim();
        if (input.isEmpty()) return;
        try {
            Object obj = JSON.readValue(input, Object.class);
            sendTextArea.setText(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj));
        } catch (Exception e) {
            UIUtils.info(getView(), I18n.get("tool.websocket.err.formatFailed", e.getMessage()));
        }
    }

    /** Session callbacks: state events arrive on the EDT, log events on socket threads. */
    private final class PanelListener implements WebSocketSession.Listener {
        @Override
        public void onOpen(int httpStatus) {
            applyState(State.CONNECTED);
            appendLog("[CONNECTED]", I18n.get("tool.websocket.log.opened", httpStatus));
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            applyState(State.DISCONNECTED);
            appendLog("[CLOSED]", I18n.get("tool.websocket.log.closed", code, reason));
        }

        @Override
        public void onError(String message) {
            appendLog("[ERROR]", I18n.get("tool.websocket.log.error", message));
        }

        @Override
        public void onMessage(String text) {
            appendLog("[RECV]", text);
        }

        @Override
        public void onSent(String text) {
            appendLog("[SENT]", text);
        }

        @Override
        public void onHeartbeat(String payload) {
            appendLog("[HEARTBEAT]", payload);
        }
    }

    // ---- test seams (package-private, EDT only) ----

    String stateForTest() {
        return currentState.name();
    }

    void setUrlForTest(String url) {
        urlField.setText(url);
    }

    void setHeartbeatForTest(boolean enabled, int intervalSec, String payload) {
        heartbeatIntervalSpinner.setValue(intervalSec);
        heartbeatPayloadField.setText(payload);
        if (heartbeatCheckBox.isSelected() != enabled) {
            heartbeatCheckBox.doClick();
        }
    }

    void clickConnectForTest() {
        connectBtn.doClick();
    }

    void clickDisconnectForTest() {
        disconnectBtn.doClick();
    }

    WebSocketSession sessionForTest() {
        return session;
    }

    BoundedTextLog logForTest() {
        return log;
    }
}
