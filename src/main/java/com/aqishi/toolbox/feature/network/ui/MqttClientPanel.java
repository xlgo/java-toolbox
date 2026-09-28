package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.network.application.BoundedLogBuffer;
import com.aqishi.toolbox.feature.network.application.MqttLogEntry;
import com.aqishi.toolbox.feature.network.application.MqttSession;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * MQTT v3.1 / v3.1.1 client tool.
 *
 * <p>All blocking broker work happens on {@link MqttSession}'s own daemon thread;
 * the panel only prepares parameters on the EDT and applies the results the
 * session posts back to the EDT. The message log is fed through a bounded,
 * batched buffer so a busy topic cannot grow the table without limit.</p>
 */
public class MqttClientPanel extends ToolPanel implements ManagedResourceOwner {

    private JTextField brokerUrlField;
    private JTextField clientIdField;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JSpinner keepAliveSpinner;
    private JCheckBox cleanSessionCheckBox;

    private JButton connectBtn;
    private JButton disconnectBtn;
    private JLabel statusLabel;

    private JTextField subTopicField;
    private JComboBox<Integer> subQosCombo;
    private JButton subBtn;
    private DefaultTableModel subTableModel;
    private JTable subTable;

    private JTextField pubTopicField;
    private JComboBox<Integer> pubQosCombo;
    private JCheckBox retainCheckBox;
    private JTextArea pubPayloadArea;
    private JButton pubBtn;
    private JButton formatJsonBtn;

    private MqttMessageTableModel msgTableModel;
    private JTable msgTable;
    private JTextArea msgDetailArea;
    private JLabel logStatsLabel;

    private State currentState = State.DISCONNECTED;

    private final MqttSession session;
    private final BoundedLogBuffer<MqttLogEntry> logBuffer = new BoundedLogBuffer<>();
    private final Timer logFlushTimer;
    private boolean publishInFlight;
    private boolean subscribeInFlight;
    private boolean lastCleanSession = true;
    private JButton unsubBtn;
    private static final ObjectMapper jsonMapper = Json.prettyMapper();

    public MqttClientPanel() {
        this(null);
    }

    /** Test seam: {@code factory == null} uses real Paho clients. */
    MqttClientPanel(MqttSession.ClientFactory factory) {
        super(ToolCatalog.MQTT_CLIENT);
        this.session = factory == null
                ? new MqttSession(new PanelListener(), SwingUtilities::invokeLater)
                : new MqttSession(new PanelListener(), SwingUtilities::invokeLater, factory);
        this.logFlushTimer = new Timer(100, e -> flushLog());
        this.logFlushTimer.setCoalesce(true);
    }

    @Override
    protected JComponent build() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // 1. 顶栏：MQTT Broker 连接配置
        mainPanel.add(buildConnectionCard(), BorderLayout.NORTH);

        // 2. 主区：左侧（订阅 + 发布）/ 右侧（实时消息流与详情）
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setResizeWeight(0.48);

        JPanel leftPanel = new JPanel(new BorderLayout(0, 10));
        leftPanel.add(buildSubscribeCard(), BorderLayout.NORTH);
        leftPanel.add(buildPublishCard(), BorderLayout.CENTER);

        JPanel rightPanel = buildMessageLogCard();

        mainSplit.setLeftComponent(leftPanel);
        mainSplit.setRightComponent(rightPanel);

        mainPanel.add(mainSplit, BorderLayout.CENTER);

        applyState(State.DISCONNECTED);
        logFlushTimer.start();
        return mainPanel;
    }

    private Card buildConnectionCard() {
        Card card = Card.plain();
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        // Line 1: Broker URL & Controls
        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.add(new JLabel(I18n.get("tool.mqtt.broker")), BorderLayout.WEST);

        brokerUrlField = new JTextField("tcp://broker.emqx.io:1883");
        brokerUrlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        row1.add(brokerUrlField, BorderLayout.CENTER);

        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        presetBar.add(new JLabel(I18n.get("tool.mqtt.preset")));
        JComboBox<String> presetCombo = new JComboBox<>(new String[]{
                "tcp://broker.emqx.io:1883",
                "tcp://test.mosquitto.org:1883",
                "tcp://127.0.0.1:1883"
        });
        presetCombo.addActionListener(e -> brokerUrlField.setText((String) presetCombo.getSelectedItem()));
        presetBar.add(presetCombo);

        statusLabel = new JLabel(I18n.get("tool.mqtt.status.disconnected"), SwingConstants.CENTER);
        statusLabel.setOpaque(true);
        statusLabel.setBackground(Color.LIGHT_GRAY);
        statusLabel.setForeground(Color.BLACK);
        statusLabel.setBorder(new EmptyBorder(3, 10, 3, 10));
        presetBar.add(statusLabel);

        connectBtn = new JButton(I18n.get("tool.mqtt.btn.connect"));
        connectBtn.setFont(connectBtn.getFont().deriveFont(Font.BOLD));
        connectBtn.addActionListener(e -> doConnect());

        disconnectBtn = new JButton(I18n.get("tool.mqtt.btn.disconnect"));
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> doDisconnect());

        presetBar.add(connectBtn);
        presetBar.add(disconnectBtn);

        row1.add(presetBar, BorderLayout.EAST);
        card.add(row1, BorderLayout.NORTH);

        // Line 2: Advanced Auth & Options
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row2.add(new JLabel(I18n.get("tool.mqtt.clientId")));
        clientIdField = new JTextField("toolbox-" + UUID.randomUUID().toString().substring(0, 8), 14);
        row2.add(clientIdField);

        row2.add(new JLabel(I18n.get("tool.mqtt.username")));
        usernameField = new JTextField("", 8);
        row2.add(usernameField);

        row2.add(new JLabel(I18n.get("tool.mqtt.password")));
        passwordField = new JPasswordField("", 8);
        row2.add(passwordField);

        row2.add(new JLabel(I18n.get("tool.mqtt.keepAlive")));
        keepAliveSpinner = new JSpinner(new SpinnerNumberModel(60, 5, 3600, 5));
        row2.add(keepAliveSpinner);

        cleanSessionCheckBox = new JCheckBox(I18n.get("tool.mqtt.cleanSession"), true);
        row2.add(cleanSessionCheckBox);

        card.add(row2, BorderLayout.SOUTH);
        return card;
    }

    private Card buildSubscribeCard() {
        Card card = Card.plain();
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel title = new JLabel(I18n.get("tool.mqtt.sub.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        card.add(title, BorderLayout.NORTH);

        JPanel subBar = new JPanel(new BorderLayout(6, 0));
        subTopicField = new JTextField("testtopic/#");
        subTopicField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        subBar.add(subTopicField, BorderLayout.CENTER);

        JPanel rightControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightControls.add(new JLabel("QoS:"));
        subQosCombo = new JComboBox<>(new Integer[]{0, 1, 2});
        rightControls.add(subQosCombo);

        subBtn = new JButton(I18n.get("tool.mqtt.sub.btn"));
        subBtn.addActionListener(e -> doSubscribe());
        rightControls.add(subBtn);

        unsubBtn = new JButton(I18n.get("tool.mqtt.sub.unsubscribe"));
        unsubBtn.addActionListener(e -> doUnsubscribe());
        rightControls.add(unsubBtn);

        subBar.add(rightControls, BorderLayout.EAST);
        card.add(subBar, BorderLayout.CENTER);

        // Table for active subscriptions
        subTableModel = new DefaultTableModel(new Object[]{
                "Topic", "QoS", I18n.get("tool.mqtt.sub.col.status")}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        subTable = new JTable(subTableModel);
        subTable.setRowHeight(24);
        subTable.getColumnModel().getColumn(1).setPreferredWidth(40);
        subTable.getColumnModel().getColumn(2).setPreferredWidth(70);

        JScrollPane tableScroll = new JScrollPane(subTable);
        tableScroll.setPreferredSize(new Dimension(0, 90));
        card.add(tableScroll, BorderLayout.SOUTH);

        return card;
    }

    private Card buildPublishCard() {
        Card card = Card.plain();
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel topRow = new JPanel(new BorderLayout(6, 0));
        JLabel title = new JLabel(I18n.get("tool.mqtt.pub.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        topRow.add(title, BorderLayout.WEST);

        JPanel pubOpts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        pubOpts.add(new JLabel("Topic:"));
        pubTopicField = new JTextField("testtopic/demo", 14);
        pubTopicField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pubOpts.add(pubTopicField);

        pubOpts.add(new JLabel("QoS:"));
        pubQosCombo = new JComboBox<>(new Integer[]{0, 1, 2});
        pubOpts.add(pubQosCombo);

        retainCheckBox = new JCheckBox(I18n.get("tool.mqtt.pub.retain"));
        pubOpts.add(retainCheckBox);

        topRow.add(pubOpts, BorderLayout.CENTER);
        card.add(topRow, BorderLayout.NORTH);

        pubPayloadArea = new JTextArea("{\n  \"msg\": \"Hello MQTT\",\n  \"time\": " + System.currentTimeMillis() + "\n}");
        pubPayloadArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane payloadScroll = new JScrollPane(pubPayloadArea);
        card.add(payloadScroll, BorderLayout.CENTER);

        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        formatJsonBtn = new JButton(I18n.get("tool.mqtt.pub.format"));
        formatJsonBtn.addActionListener(e -> formatPubPayload());
        bottomRow.add(formatJsonBtn);

        pubBtn = new JButton(I18n.get("tool.mqtt.pub.send"));
        pubBtn.setFont(pubBtn.getFont().deriveFont(Font.BOLD));
        pubBtn.addActionListener(e -> doPublish());
        bottomRow.add(pubBtn);

        card.add(bottomRow, BorderLayout.SOUTH);
        return card;
    }

    private JPanel buildMessageLogCard() {
        Card card = Card.plain();
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        JPanel headerPanel = new JPanel(new BorderLayout());
        JLabel title = new JLabel(I18n.get("tool.mqtt.log.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        headerPanel.add(title, BorderLayout.WEST);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        logStatsLabel = new JLabel();
        btnPanel.add(logStatsLabel);

        JButton copyBtn = new JButton(I18n.get("tool.mqtt.log.copy"));
        copyBtn.addActionListener(e -> {
            String text = msgDetailArea.getText();
            if (text != null && !text.isEmpty()) {
                UIUtils.copyToClipboard(text);
                UIUtils.info(getView(), I18n.get("tool.mqtt.log.copied"));
            }
        });
        btnPanel.add(copyBtn);

        JButton clearBtn = new JButton(I18n.get("tool.mqtt.log.clear"));
        clearBtn.addActionListener(e -> clearLog());
        btnPanel.add(clearBtn);
        headerPanel.add(btnPanel, BorderLayout.EAST);

        card.add(headerPanel, BorderLayout.NORTH);

        // Messages Split Table / Detail
        JSplitPane msgSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        msgSplit.setResizeWeight(0.65);

        msgTableModel = new MqttMessageTableModel();
        msgTable = new JTable(msgTableModel);
        msgTable.setRowHeight(22);
        msgTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        msgTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        msgTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        msgTable.getColumnModel().getColumn(3).setPreferredWidth(40);
        msgTable.getColumnModel().getColumn(4).setPreferredWidth(50);
        msgTable.getColumnModel().getColumn(5).setPreferredWidth(200);

        msgTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int row = msgTable.getSelectedRow();
                if (row >= 0 && row < msgTableModel.getRowCount()) {
                    showDetail(msgTableModel.entryAt(row).payload());
                }
            }
        });

        JScrollPane tableScroll = new JScrollPane(msgTable);
        msgSplit.setTopComponent(tableScroll);

        msgDetailArea = new JTextArea();
        msgDetailArea.setEditable(false);
        msgDetailArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane detailScroll = new JScrollPane(msgDetailArea);
        msgSplit.setBottomComponent(detailScroll);

        card.add(msgSplit, BorderLayout.CENTER);
        updateLogStats();
        return card;
    }

    private void showDetail(String payload) {
        try {
            Object jsonObj = jsonMapper.readValue(payload, Object.class);
            msgDetailArea.setText(jsonMapper.writeValueAsString(jsonObj));
        } catch (Exception ex) {
            msgDetailArea.setText(payload);
        }
        msgDetailArea.setCaretPosition(0);
    }

    private void doConnect() {
        String brokerUrl = brokerUrlField.getText().trim();
        if (brokerUrl.isEmpty()) {
            UIUtils.error(getView(), I18n.get("tool.mqtt.err.noBroker"));
            return;
        }

        // Everything the io thread needs is captured here, on the EDT.
        MqttSession.Settings settings = new MqttSession.Settings(
                brokerUrl,
                clientIdField.getText().trim(),
                usernameField.getText().trim(),
                passwordField.getPassword(),
                (Integer) keepAliveSpinner.getValue(),
                cleanSessionCheckBox.isSelected());
        lastCleanSession = settings.cleanSession();

        applyState(State.CONNECTING);
        appendLog(MqttLogEntry.system("-", I18n.get("tool.mqtt.log.connecting", brokerUrl)));
        session.connect(settings);
    }

    private void doDisconnect() {
        session.disconnect();
        applyState(State.DISCONNECTED);
        markSubscriptions(I18n.get("tool.mqtt.sub.col.inactive"));
        appendLog(MqttLogEntry.system("-", I18n.get("tool.mqtt.log.manualDisconnect")));
    }

    /** Releases the MQTT client without updating Swing controls during exit. */
    @Override
    public void closeResources() {
        stopLogFlushTimer();
        session.close();
    }

    private void doSubscribe() {
        if (!isConnected()) {
            UIUtils.warn(getView(), I18n.get("tool.mqtt.warn.noConnection"), I18n.get("tool.mqtt.tip"));
            return;
        }

        String topic = subTopicField.getText().trim();
        if (topic.isEmpty()) {
            UIUtils.error(getView(), I18n.get("tool.mqtt.err.noSubTopic"));
            return;
        }
        int qos = (Integer) subQosCombo.getSelectedItem();

        subscribeInFlight = true;
        updateActionButtons();
        session.subscribe(topic, qos);
    }

    private void doUnsubscribe() {
        int row = subTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        String topic = String.valueOf(subTableModel.getValueAt(row, 0));
        subTableModel.removeRow(row);
        if (isConnected()) {
            session.unsubscribe(topic);
        }
    }

    /** Rewrites the status column of every subscription row. */
    private void markSubscriptions(String status) {
        for (int i = 0; i < subTableModel.getRowCount(); i++) {
            subTableModel.setValueAt(status, i, 2);
        }
    }

    private void doPublish() {
        if (!isConnected()) {
            UIUtils.warn(getView(), I18n.get("tool.mqtt.warn.noConnection"), I18n.get("tool.mqtt.tip"));
            return;
        }

        String topic = pubTopicField.getText().trim();
        if (topic.isEmpty()) {
            UIUtils.error(getView(), I18n.get("tool.mqtt.err.noPubTopic"));
            return;
        }
        int qos = (Integer) pubQosCombo.getSelectedItem();
        boolean retain = retainCheckBox.isSelected();
        String payloadStr = pubPayloadArea.getText();

        publishInFlight = true;
        updateActionButtons();
        session.publish(topic, payloadStr.getBytes(StandardCharsets.UTF_8), qos, retain, payloadStr);
    }

    private void formatPubPayload() {
        String text = pubPayloadArea.getText();
        if (text != null && !text.trim().isEmpty()) {
            try {
                Object jsonObj = jsonMapper.readValue(text, Object.class);
                pubPayloadArea.setText(jsonMapper.writeValueAsString(jsonObj));
            } catch (Exception ex) {
                UIUtils.error(getView(), I18n.get("tool.mqtt.err.formatFailed", ex.getMessage()),
                        I18n.get("tool.mqtt.title.jsonError"));
            }
        }
    }

    private boolean isConnected() {
        return currentState == State.CONNECTED;
    }

    private enum State { DISCONNECTED, CONNECTING, CONNECTED, LOST }

    private void applyState(State state) {
        currentState = state;
        boolean connected = state == State.CONNECTED;
        boolean editable = state == State.DISCONNECTED || state == State.LOST;
        switch (state) {
            case CONNECTED -> {
                statusLabel.setText(I18n.get("tool.mqtt.status.connected"));
                statusLabel.setBackground(new Color(46, 125, 50));
                statusLabel.setForeground(Color.WHITE);
            }
            case CONNECTING -> {
                statusLabel.setText(I18n.get("tool.mqtt.status.connecting"));
                statusLabel.setBackground(Color.ORANGE);
                statusLabel.setForeground(Color.BLACK);
            }
            case LOST -> {
                statusLabel.setText(I18n.get("tool.mqtt.status.lost"));
                statusLabel.setBackground(Color.LIGHT_GRAY);
                statusLabel.setForeground(Color.BLACK);
            }
            default -> {
                statusLabel.setText(I18n.get("tool.mqtt.status.disconnected"));
                statusLabel.setBackground(Color.LIGHT_GRAY);
                statusLabel.setForeground(Color.BLACK);
            }
        }

        publishInFlight = false;
        subscribeInFlight = false;
        updateActionButtons();

        brokerUrlField.setEnabled(editable);
        clientIdField.setEnabled(editable);
        usernameField.setEnabled(editable);
        passwordField.setEnabled(editable);
        keepAliveSpinner.setEnabled(editable);
        cleanSessionCheckBox.setEnabled(editable);
    }

    private void updateActionButtons() {
        boolean connecting = State.CONNECTING == currentState;
        boolean connected = isConnected();
        connectBtn.setEnabled(!connected && !connecting);
        // While connecting the user may still cancel: the attempt is abandoned,
        // not interrupted, and its late result is discarded.
        disconnectBtn.setEnabled(connected || connecting);
        pubBtn.setEnabled(connected && !publishInFlight);
        subBtn.setEnabled(connected && !subscribeInFlight);
    }

    private void flushLog() {
        BoundedLogBuffer.Flush<MqttLogEntry> flush = logBuffer.drain();
        if (flush.isEmpty()) {
            return;
        }
        int lastBefore = msgTableModel.getRowCount() - 1;
        boolean followTail = msgTable.getSelectionModel().isSelectionEmpty()
                || msgTable.getSelectedRow() == lastBefore;

        msgTableModel.apply(flush);
        if (followTail && msgTableModel.getRowCount() > 0) {
            int last = msgTableModel.getRowCount() - 1;
            msgTable.scrollRectToVisible(msgTable.getCellRect(last, 0, true));
        }
        if (flush.evictedCount() > 0 || flush.droppedCount() > 0) {
            updateLogStats();
        }
    }

    private void clearLog() {
        logBuffer.clear();
        msgTableModel.clear();
        msgDetailArea.setText("");
        updateLogStats();
    }

    private void updateLogStats() {
        if (logStatsLabel == null) {
            return;
        }
        String text = I18n.get("tool.mqtt.log.stats",
                logBuffer.retainedSize() + logBuffer.pendingSize(), logBuffer.maxRetained());
        long trimmed = logBuffer.trimmedTotal();
        long dropped = logBuffer.droppedTotal();
        if (trimmed > 0 || dropped > 0) {
            text = text + "  " + I18n.get("tool.mqtt.log.trimmed", trimmed, dropped);
        }
        logStatsLabel.setText(text);
    }

    private void appendLog(MqttLogEntry entry) {
        logBuffer.offer(entry);
    }

    private void stopLogFlushTimer() {
        logFlushTimer.stop();
    }

    /** Session callbacks. State events arrive on the EDT; log events on network threads. */
    private final class PanelListener implements MqttSession.Listener {
        @Override
        public void onConnected(String brokerUrl) {
            applyState(State.CONNECTED);
            if (lastCleanSession) {
                subTableModel.setRowCount(0);
            } else {
                // The broker resumes a persistent session together with its subscriptions.
                markSubscriptions(I18n.get("tool.mqtt.sub.col.subscribed"));
            }
            appendLog(MqttLogEntry.system("-", I18n.get("tool.mqtt.log.connected", brokerUrl)));
        }

        @Override
        public void onConnectionLost(String reason) {
            applyState(State.LOST);
            markSubscriptions(I18n.get("tool.mqtt.sub.col.inactive"));
            String detail = reason == null || reason.isEmpty() ? I18n.get("tool.mqtt.log.unknown") : reason;
            appendLog(MqttLogEntry.system("-", I18n.get("tool.mqtt.log.connectionLost", detail)));
        }

        @Override
        public void onSubscribed(String topic, int qos) {
            subscribeInFlight = false;
            updateActionButtons();
            subTableModel.addRow(new Object[]{topic, qos, I18n.get("tool.mqtt.sub.col.subscribed")});
            appendLog(MqttLogEntry.system(topic, I18n.get("tool.mqtt.log.subscribed", topic, qos)));
        }

        @Override
        public void onUnsubscribed(String topic) {
            appendLog(MqttLogEntry.system(topic, I18n.get("tool.mqtt.log.unsubscribed", topic)));
        }

        @Override
        public void onPublished(String topic, int qos, boolean retain, String payload) {
            publishInFlight = false;
            updateActionButtons();
            appendLog(MqttLogEntry.of(MqttLogEntry.Direction.SENT, topic, qos, retain, payload));
        }

        @Override
        public void onFailed(MqttSession.Operation operation, String message) {
            if (operation == MqttSession.Operation.CONNECT) {
                applyState(State.DISCONNECTED);
                UIUtils.error(getView(), I18n.get("tool.mqtt.err.connectFailed", message),
                        I18n.get("tool.mqtt.title.connectError"));
                return;
            }
            if (operation == MqttSession.Operation.SUBSCRIBE) {
                subscribeInFlight = false;
                updateActionButtons();
                UIUtils.error(getView(), I18n.get("tool.mqtt.err.subscribeFailed", message));
                return;
            }
            if (operation == MqttSession.Operation.PUBLISH) {
                publishInFlight = false;
                updateActionButtons();
                UIUtils.error(getView(), I18n.get("tool.mqtt.err.publishFailed", message));
                return;
            }
            UIUtils.error(getView(), I18n.get("tool.mqtt.err.operationFailed", message));
        }

        @Override
        public void onMessage(String topic, MqttMessage message) {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            appendLog(MqttLogEntry.of(MqttLogEntry.Direction.RECEIVED, topic,
                    message.getQos(), message.isRetained(), payload));
        }
    }

    // ---- test seams (package-private, EDT only) ----

    String stateForTest() {
        return currentState.name();
    }

    void clickConnectForTest() {
        connectBtn.doClick();
    }

    void clickDisconnectForTest() {
        disconnectBtn.doClick();
    }

    void clickPublishForTest() {
        pubBtn.doClick();
    }

    boolean publishEnabledForTest() {
        return pubBtn.isEnabled();
    }

    void flushLogForTest() {
        flushLog();
    }

    int logRowsForTest() {
        return msgTableModel.getRowCount();
    }

    MqttMessageTableModel logModelForTest() {
        return msgTableModel;
    }

    long logTrimmedForTest() {
        return logBuffer.trimmedTotal();
    }

    String logStatsForTest() {
        return logStatsLabel.getText();
    }

    boolean logTimerRunningForTest() {
        return logFlushTimer.isRunning();
    }

    MqttSession sessionForTest() {
        return session;
    }

    /** Feeds the log the way the Paho callback thread does. Thread-safe. */
    void offerLogForTest(MqttLogEntry entry) {
        appendLog(entry);
    }
}
