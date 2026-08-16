package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * MQTT v3.1 / v3.1.1 客户端测试工具
 */
public class MqttClientPanel extends ToolPanel {

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

    private DefaultTableModel msgTableModel;
    private JTable msgTable;
    private JTextArea msgDetailArea;

    private MqttClient mqttClient;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final ObjectMapper jsonMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public MqttClientPanel() {
        super("dev", "mqtt.client", "mqtt", "iot", "emqx", "broker", "publish", "subscribe", "消息队列", "物联网", "测试");
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

        return mainPanel;
    }

    private Card buildConnectionCard() {
        Card card = Card.plain();
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        // Line 1: Broker URL & Controls
        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.add(new JLabel("Broker 地址:"), BorderLayout.WEST);

        brokerUrlField = new JTextField("tcp://broker.emqx.io:1883");
        brokerUrlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        row1.add(brokerUrlField, BorderLayout.CENTER);

        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        presetBar.add(new JLabel("预设:"));
        JComboBox<String> presetCombo = new JComboBox<>(new String[]{
                "tcp://broker.emqx.io:1883",
                "tcp://test.mosquitto.org:1883",
                "tcp://127.0.0.1:1883"
        });
        presetCombo.addActionListener(e -> brokerUrlField.setText((String) presetCombo.getSelectedItem()));
        presetBar.add(presetCombo);

        statusLabel = new JLabel("未连接", SwingConstants.CENTER);
        statusLabel.setOpaque(true);
        statusLabel.setBackground(Color.LIGHT_GRAY);
        statusLabel.setForeground(Color.BLACK);
        statusLabel.setBorder(new EmptyBorder(3, 10, 3, 10));
        presetBar.add(statusLabel);

        connectBtn = new JButton("连接");
        connectBtn.setFont(connectBtn.getFont().deriveFont(Font.BOLD));
        connectBtn.addActionListener(e -> doConnect());

        disconnectBtn = new JButton("断开");
        disconnectBtn.setEnabled(false);
        disconnectBtn.addActionListener(e -> doDisconnect());

        presetBar.add(connectBtn);
        presetBar.add(disconnectBtn);

        row1.add(presetBar, BorderLayout.EAST);
        card.add(row1, BorderLayout.NORTH);

        // Line 2: Advanced Auth & Options
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row2.add(new JLabel("Client ID:"));
        clientIdField = new JTextField("toolbox-" + UUID.randomUUID().toString().substring(0, 8), 14);
        row2.add(clientIdField);

        row2.add(new JLabel("用户名:"));
        usernameField = new JTextField("", 8);
        row2.add(usernameField);

        row2.add(new JLabel("密码:"));
        passwordField = new JPasswordField("", 8);
        row2.add(passwordField);

        row2.add(new JLabel("KeepAlive(s):"));
        keepAliveSpinner = new JSpinner(new SpinnerNumberModel(60, 5, 3600, 5));
        row2.add(keepAliveSpinner);

        cleanSessionCheckBox = new JCheckBox("Clean Session", true);
        row2.add(cleanSessionCheckBox);

        card.add(row2, BorderLayout.SOUTH);
        return card;
    }

    private Card buildSubscribeCard() {
        Card card = Card.plain();
        card.setLayout(new BorderLayout(0, 6));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        JLabel title = new JLabel("📥 订阅主题 (Subscribe)");
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

        subBtn = new JButton("添加订阅");
        subBtn.addActionListener(e -> doSubscribe());
        rightControls.add(subBtn);

        subBar.add(rightControls, BorderLayout.EAST);
        card.add(subBar, BorderLayout.CENTER);

        // Table for active subscriptions
        subTableModel = new DefaultTableModel(new Object[]{"Topic", "QoS", "状态"}, 0) {
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
        JLabel title = new JLabel("📤 发布消息 (Publish)");
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

        retainCheckBox = new JCheckBox("Retain");
        pubOpts.add(retainCheckBox);

        topRow.add(pubOpts, BorderLayout.CENTER);
        card.add(topRow, BorderLayout.NORTH);

        pubPayloadArea = new JTextArea("{\n  \"msg\": \"Hello MQTT\",\n  \"time\": " + System.currentTimeMillis() + "\n}");
        pubPayloadArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane payloadScroll = new JScrollPane(pubPayloadArea);
        card.add(payloadScroll, BorderLayout.CENTER);

        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        formatJsonBtn = new JButton("格式化 JSON");
        formatJsonBtn.addActionListener(e -> formatPubPayload());
        bottomRow.add(formatJsonBtn);

        pubBtn = new JButton("发送消息");
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
        JLabel title = new JLabel("📋 消息通信日志 (Message Log)");
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        headerPanel.add(title, BorderLayout.WEST);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton copyBtn = new JButton("复制详情");
        copyBtn.addActionListener(e -> {
            String text = msgDetailArea.getText();
            if (text != null && !text.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                JOptionPane.showMessageDialog(getView(), "详情内容已复制到剪贴板", "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        btnPanel.add(copyBtn);

        JButton clearBtn = new JButton("清空日志");
        clearBtn.addActionListener(e -> {
            msgTableModel.setRowCount(0);
            msgDetailArea.setText("");
        });
        btnPanel.add(clearBtn);
        headerPanel.add(btnPanel, BorderLayout.EAST);

        card.add(headerPanel, BorderLayout.NORTH);

        // Messages Split Table / Detail
        JSplitPane msgSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        msgSplit.setResizeWeight(0.65);

        msgTableModel = new DefaultTableModel(new Object[]{"方向", "时间", "Topic", "QoS", "Retain", "Payload"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
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
                if (row >= 0) {
                    String payload = (String) msgTableModel.getValueAt(row, 5);
                    try {
                        Object jsonObj = jsonMapper.readValue(payload, Object.class);
                        msgDetailArea.setText(jsonMapper.writeValueAsString(jsonObj));
                    } catch (Exception ex) {
                        msgDetailArea.setText(payload);
                    }
                    msgDetailArea.setCaretPosition(0);
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
        return card;
    }

    private synchronized void doConnect() {
        String brokerUrl = brokerUrlField.getText().trim();
        String clientId = clientIdField.getText().trim();

        if (brokerUrl.isEmpty()) {
            JOptionPane.showMessageDialog(getView(), "请输入 Broker 地址！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            MemoryPersistence persistence = new MemoryPersistence();
            mqttClient = new MqttClient(brokerUrl, clientId, persistence);

            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(cleanSessionCheckBox.isSelected());
            options.setKeepAliveInterval((Integer) keepAliveSpinner.getValue());
            options.setConnectionTimeout(10);

            String user = usernameField.getText().trim();
            if (!user.isEmpty()) {
                options.setUserName(user);
                options.setPassword(passwordField.getPassword());
            }

            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    SwingUtilities.invokeLater(() -> {
                        updateStatus(false, "连接断开");
                        appendLog("System", "-", 0, false, "连接已断开: " + (cause != null ? cause.getMessage() : "未知"));
                    });
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    SwingUtilities.invokeLater(() -> {
                        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                        appendLog("📥 接收", topic, message.getQos(), message.isRetained(), payload);
                    });
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {
                }
            });

            connectBtn.setEnabled(false);
            statusLabel.setText("连接中...");
            statusLabel.setBackground(Color.ORANGE);

            new Thread(() -> {
                try {
                    mqttClient.connect(options);
                    SwingUtilities.invokeLater(() -> {
                        updateStatus(true, "已连接");
                        appendLog("System", "-", 0, false, "成功连接至 " + brokerUrl);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        updateStatus(false, "未连接");
                        connectBtn.setEnabled(true);
                        JOptionPane.showMessageDialog(getView(), "连接失败: " + ex.getMessage(), "连接错误", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();

        } catch (Exception ex) {
            updateStatus(false, "未连接");
            connectBtn.setEnabled(true);
            JOptionPane.showMessageDialog(getView(), "客户端创建失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private synchronized void doDisconnect() {
        if (mqttClient != null && mqttClient.isConnected()) {
            try {
                mqttClient.disconnect();
                mqttClient.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        updateStatus(false, "未连接");
        appendLog("System", "-", 0, false, "手动已断开连接");
    }

    private void doSubscribe() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            JOptionPane.showMessageDialog(getView(), "请先连接 MQTT Broker！", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String topic = subTopicField.getText().trim();
        int qos = (Integer) subQosCombo.getSelectedItem();

        if (topic.isEmpty()) {
            JOptionPane.showMessageDialog(getView(), "请输入要订阅的主题！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            mqttClient.subscribe(topic, qos);
            subTableModel.addRow(new Object[]{topic, qos, "已订阅"});
            appendLog("System", topic, qos, false, "已订阅主题: " + topic + " (QoS " + qos + ")");
        } catch (MqttException ex) {
            JOptionPane.showMessageDialog(getView(), "订阅失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void doPublish() {
        if (mqttClient == null || !mqttClient.isConnected()) {
            JOptionPane.showMessageDialog(getView(), "请先连接 MQTT Broker！", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String topic = pubTopicField.getText().trim();
        int qos = (Integer) pubQosCombo.getSelectedItem();
        boolean retain = retainCheckBox.isSelected();
        String payloadStr = pubPayloadArea.getText();

        if (topic.isEmpty()) {
            JOptionPane.showMessageDialog(getView(), "请输入发布目标主题！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            MqttMessage message = new MqttMessage(payloadStr.getBytes(StandardCharsets.UTF_8));
            message.setQos(qos);
            message.setRetained(retain);

            mqttClient.publish(topic, message);
            appendLog("📤 发送", topic, qos, retain, payloadStr);
        } catch (MqttException ex) {
            JOptionPane.showMessageDialog(getView(), "发送失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void formatPubPayload() {
        String text = pubPayloadArea.getText();
        if (text != null && !text.trim().isEmpty()) {
            try {
                Object jsonObj = jsonMapper.readValue(text, Object.class);
                pubPayloadArea.setText(jsonMapper.writeValueAsString(jsonObj));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(getView(), "格式化失败: " + ex.getMessage(), "JSON 语法错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void updateStatus(boolean connected, String text) {
        statusLabel.setText(text);
        statusLabel.setBackground(connected ? new Color(46, 125, 50) : Color.LIGHT_GRAY);
        statusLabel.setForeground(connected ? Color.WHITE : Color.BLACK);

        connectBtn.setEnabled(!connected);
        disconnectBtn.setEnabled(connected);

        brokerUrlField.setEnabled(!connected);
        clientIdField.setEnabled(!connected);
        usernameField.setEnabled(!connected);
        passwordField.setEnabled(!connected);
        keepAliveSpinner.setEnabled(!connected);
        cleanSessionCheckBox.setEnabled(!connected);
    }

    private void appendLog(String direction, String topic, int qos, boolean retain, String payload) {
        String timeStr = dateFormat.format(new Date());
        msgTableModel.addRow(new Object[]{direction, timeStr, topic, qos, retain ? "是" : "否", payload});

        // 自动滚动到最新一行
        msgTable.scrollRectToVisible(msgTable.getCellRect(msgTableModel.getRowCount() - 1, 0, true));
    }
}
