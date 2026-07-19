package com.aqishi.toolbox.monitor;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * P2P 远程桌面工具面板 (完全支持 WebRTC ICE, Offer, Answer 信令协商)。
 */
public class RemoteDesktopPanel extends ToolPanel {

    private final ObjectMapper mapper = new ObjectMapper();

    // UI 组件
    private JTabbedPane mainTabs;
    
    // 顶部公共配置
    private JTextField serverUrlField;
    private JTextField groupField;
    private JTextField nameField;
    private JButton connectServerBtn;
    private JLabel clientStatusLabel;

    // 1. 控制端组件
    private JComboBox<String> peerSelectBox;
    private JTextField targetIdField;
    private JButton startControlBtn;
    private JTextArea controlLogArea;

    // 2. 被控端组件
    private JToggleButton allowBeControlledBtn;
    private JLabel hostStatusLabel;
    private JTextArea hostLogArea;

    // 3. 信令服务组件
    private JTextField localPortField;
    private JButton startServerBtn;
    private JTextArea serverLogArea;

    // 状态管理
    private DesktopSignalClient signalClient; // 控制端信令客户端
    private DesktopSignalClient hostSignalClient; // 被控端信令客户端
    private DesktopSignalServer localSignalServer; // 本地中转信令服务器
    
    private P2PConnector p2pConnector;
    
    // 控制端连接缓存
    private DesktopChannel activeControlChannel;
    private RemoteControlWindow activeControlWindow;
    private WebSocketChannelImpl currentWsChannelBack;

    // 被控端连接缓存
    private DesktopChannel activeHostChannel;
    private ScheduledExecutorService screenPushScheduler;
    private Robot robot;
    private final Dimension hostScreenSize = Toolkit.getDefaultToolkit().getScreenSize();
    
    // 被控端命令行进程缓存
    private static class ProcessInfo {
        Process process;
        BufferedWriter writer;
        ProcessInfo(Process process, BufferedWriter writer) {
            this.process = process;
            this.writer = writer;
        }
    }
    private final Map<String, ProcessInfo> hostTerminalProcesses = new ConcurrentHashMap<>();
    
    // 被控端文件接收缓存
    private final Map<String, FileTransferDialog.FileReceiver> hostFileReceivers = new ConcurrentHashMap<>();
    
    // 被控端标注画板缓存
    private TransparentOverlayWindow overlayWindow;

    public RemoteDesktopPanel() {
        super("monitor", "remote_desktop", "p2p", "desktop", "control", "远程桌面", "远程控制");
        try {
            this.robot = new Robot();
        } catch (Exception e) {
            e.printStackTrace();
        }
        this.p2pConnector = new P2PConnector();
    }

    @Override
    protected JComponent build() {
        JPanel mainPanel = new JPanel(new BorderLayout(5, 5));
        mainPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 顶层公共网络及信令配置栏
        JPanel topConfig = new JPanel(new GridBagLayout());
        topConfig.setBorder(BorderFactory.createTitledBorder(I18n.get("remote_desktop.config_border")));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0;
        topConfig.add(new JLabel(I18n.get("remote_desktop.server_url")), gbc);

        serverUrlField = new JTextField("ws://170.106.158.103:21701");
        serverUrlField.setPreferredSize(new Dimension(220, 28));
        gbc.gridx = 1; gbc.weightx = 1.0;
        topConfig.add(serverUrlField, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.0;
        topConfig.add(new JLabel(I18n.get("remote_desktop.group_room")), gbc);

        groupField = new JTextField("arges123213");
        groupField.setPreferredSize(new Dimension(100, 28));
        gbc.gridx = 3; gbc.weightx = 0.5;
        topConfig.add(groupField, gbc);

        gbc.gridx = 4; gbc.gridy = 0; gbc.weightx = 0.0;
        topConfig.add(new JLabel(I18n.get("remote_desktop.my_name")), gbc);

        nameField = new JTextField(System.getProperty("user.name"));
        nameField.setPreferredSize(new Dimension(80, 28));
        gbc.gridx = 5; gbc.weightx = 0.5;
        topConfig.add(nameField, gbc);

        connectServerBtn = new JButton(I18n.get("remote_desktop.connect_btn_connect"));
        connectServerBtn.setPreferredSize(new Dimension(90, 28));
        gbc.gridx = 6; gbc.weightx = 0.0;
        topConfig.add(connectServerBtn, gbc);

        clientStatusLabel = new JLabel(I18n.get("remote_desktop.status_unconnected"));
        clientStatusLabel.setForeground(Color.GRAY);
        clientStatusLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 7;
        topConfig.add(clientStatusLabel, gbc);

        mainPanel.add(topConfig, BorderLayout.NORTH);

        mainTabs = new JTabbedPane();
        mainTabs.addTab(I18n.get("remote_desktop.control_tab"), buildControlTab());
        mainTabs.addTab(I18n.get("remote_desktop.be_controlled_tab"), buildBeControlledTab());
        mainTabs.addTab(I18n.get("remote_desktop.server_tab"), buildServerTab());

        mainPanel.add(mainTabs, BorderLayout.CENTER);

        setupConnectionListeners();

        return mainPanel;
    }

    private void setupConnectionListeners() {
        connectServerBtn.addActionListener(e -> {
            if (signalClient != null && signalClient.isOpen()) {
                signalClient.close();
                connectServerBtn.setText(I18n.get("remote_desktop.connect_btn_connect"));
                startControlBtn.setEnabled(false);
                clientStatusLabel.setText(I18n.get("remote_desktop.status_unconnected"));
                clientStatusLabel.setForeground(Color.GRAY);
                peerSelectBox.removeAllItems();
            } else {
                try {
                    URI uri = new URI(serverUrlField.getText().trim());
                    signalClient = new DesktopSignalClient(uri);
                    signalClient.setLogListener(msg -> appendLog(controlLogArea, "[Client] " + msg));
                    
                    signalClient.setListener(new DesktopSignalClient.DesktopSignalListener() {
                        @Override
                        public void onUserListReceived(List<Map<String, String>> users) {
                            SwingUtilities.invokeLater(() -> {
                                peerSelectBox.removeAllItems();
                                peerSelectBox.addItem(I18n.get("remote_desktop.select_peer_placeholder"));
                                for (Map<String, String> u : users) {
                                    String id = u.get("id");
                                    String name = u.get("name");
                                    if (id != null && !id.equals(signalClient.getClientId())) {
                                        peerSelectBox.addItem(name + " (" + id + ")");
                                    }
                                }
                            });
                        }

                        @Override
                        public void onPeerMessage(String fromId, byte[] rawData) {
                            if (currentWsChannelBack != null) {
                                currentWsChannelBack.handleRawMessage(rawData);
                            }
                        }

                        @Override
                        public void onAnswerReceived(String fromId, String sdp) {
                            appendLog(controlLogArea, I18n.get("remote_desktop.control_log_negotiating"));
                            currentWsChannelBack = new WebSocketChannelImpl(signalClient, fromId);
                        }

                        @Override
                        public void onIceCandidateReceived(String fromId, String candidate) {
                            p2pConnector.addCandidate(candidate);
                        }

                        @Override
                        public void onPeerDisconnected(String peerId) {
                            if (activeControlWindow != null) {
                                activeControlWindow.dispose();
                            }
                            appendLog(controlLogArea, I18n.get("remote_desktop.control_log_peer_disconnected"));
                        }
                    });

                    signalClient.connect();
                    clientStatusLabel.setText(I18n.get("remote_desktop.status_connecting"));
                    clientStatusLabel.setForeground(Color.ORANGE);

                    Timer joinTimer = new Timer(500, null);
                    joinTimer.addActionListener(evt -> {
                        if (signalClient.isOpen()) {
                            String myId = "RD-" + (int)((Math.random() * 9 + 1) * 100000);
                            String group = groupField.getText().trim();
                            String name = nameField.getText().trim();
                            signalClient.join(myId, group, name);
                            
                            clientStatusLabel.setText(I18n.get("remote_desktop.status_connected", group, myId));
                            clientStatusLabel.setForeground(new Color(75, 181, 67));
                            connectServerBtn.setText(I18n.get("remote_desktop.connect_btn_disconnect"));
                            startControlBtn.setEnabled(true);
                            joinTimer.stop();
                        }
                    });
                    joinTimer.start();

                } catch (Exception ex) {
                    appendLog(controlLogArea, "Connection failed: " + ex.getMessage());
                }
            }
        });
    }

    // ==================== 选项卡1：控制端 ====================
    private JComponent buildControlTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        panel.add(new JLabel(I18n.get("remote_desktop.online_peers")), gbc);

        peerSelectBox = new JComboBox<>();
        peerSelectBox.addItem(I18n.get("remote_desktop.no_peers_placeholder"));
        peerSelectBox.setPreferredSize(new Dimension(250, 32));
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(peerSelectBox, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        panel.add(new JLabel(I18n.get("remote_desktop.target_id")), gbc);

        targetIdField = new JTextField();
        targetIdField.putClientProperty("JTextField.placeholderText", I18n.get("remote_desktop.target_id_placeholder"));
        targetIdField.setPreferredSize(new Dimension(250, 32));
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(targetIdField, gbc);

        startControlBtn = new JButton(I18n.get("remote_desktop.start_control_btn"));
        startControlBtn.setPreferredSize(new Dimension(120, 32));
        startControlBtn.setEnabled(false);
        gbc.gridx = 2; gbc.weightx = 0.0;
        panel.add(startControlBtn, gbc);

        peerSelectBox.addActionListener(e -> {
            Object sel = peerSelectBox.getSelectedItem();
            if (sel != null) {
                String str = sel.toString();
                if (str.contains("(") && str.endsWith(")")) {
                    String id = str.substring(str.lastIndexOf("(") + 1, str.length() - 1);
                    targetIdField.setText(id);
                }
            }
        });

        controlLogArea = new JTextArea();
        controlLogArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(controlLogArea);
        scroll.setBorder(BorderFactory.createTitledBorder(I18n.get("remote_desktop.control_log_border")));
        
        gbc.gridx = 0; gbc.gridy = 2;
        gbc.gridwidth = 3; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        panel.add(scroll, gbc);

        startControlBtn.addActionListener(e -> {
            String targetId = targetIdField.getText().trim();
            if (targetId.isEmpty()) {
                JOptionPane.showMessageDialog(panel, "Please select target ID", "Prompt", JOptionPane.WARNING_MESSAGE);
                return;
            }
            currentWsChannelBack = new WebSocketChannelImpl(signalClient, targetId);
            appendLog(controlLogArea, I18n.get("remote_desktop.control_log_connecting", targetId));
            
            new Thread(() -> {
                // 1. 启动增量打洞连接会话
                p2pConnector.startConnectorSession(
                    udpChannel -> {
                        appendLog(controlLogArea, I18n.get("remote_desktop.control_log_p2p_success"));
                        startControlWindow(udpChannel, targetId);
                    },
                    () -> {
                        appendLog(controlLogArea, I18n.get("remote_desktop.control_log_p2p_failed"));
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(panel, 
                                "P2P 直连协商失败，已终止连接。请检查双方网络互通性与防火墙规则！", 
                                "连接失败", JOptionPane.ERROR_MESSAGE);
                        });
                    },
                    msg -> appendLog(controlLogArea, msg)
                );

                // 2. 开启控制端本地端口监听（让被控端反向连接它，实现双向打洞）
                int localPort = p2pConnector.startHostListener(
                    udpChannel -> {
                        appendLog(controlLogArea, I18n.get("remote_desktop.control_log_p2p_success"));
                        startControlWindow(udpChannel, targetId);
                    },
                    msg -> appendLog(controlLogArea, "[LocalP2P] " + msg)
                );

                // 3. 发送 WebRTC Offer 呼叫
                signalClient.sendOffer(targetId, "dummy_sdp");

                // 4. 将本机的局域网 IP 作为 ICE Candidate 发送给对方
                if (localPort != -1) {
                    for (String ip : P2PConnector.getLocalIPv4Addresses()) {
                        String candStr = "candidate:12345 1 udp 2113937151 " + ip + " " + localPort + " typ host";
                        signalClient.sendIceCandidate(targetId, candStr);
                    }

                    // 直接读取刚才同步探测好的公网反射 IP 并发送
                    java.net.InetSocketAddress publicAddr = p2pConnector.getPublicAddress();
                    if (publicAddr != null) {
                        String publicIp = publicAddr.getHostString();
                        int publicPort = publicAddr.getPort();
                        String candStr = "candidate:12345 1 udp 2113937151 " + publicIp + " " + publicPort + " typ srflx";
                        signalClient.sendIceCandidate(targetId, candStr);
                        appendLog(controlLogArea, "收集到本机公网反射 IP 候选: " + publicIp + ":" + publicPort);
                    }
                }
            }, "Control-ConnectorInitiator").start();
        });

        return panel;
    }

    // ==================== 选项卡2：被控端 ====================
    private JComponent buildBeControlledTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        top.setBorder(BorderFactory.createTitledBorder(I18n.get("remote_desktop.host_switch_border")));
        
        allowBeControlledBtn = new JToggleButton(I18n.get("remote_desktop.host_btn_off"));
        allowBeControlledBtn.setPreferredSize(new Dimension(140, 32));
        top.add(allowBeControlledBtn);

        hostStatusLabel = new JLabel(I18n.get("remote_desktop.host_status_offline"));
        hostStatusLabel.setForeground(Color.GRAY);
        hostStatusLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        top.add(hostStatusLabel);

        panel.add(top, BorderLayout.NORTH);

        hostLogArea = new JTextArea();
        hostLogArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(hostLogArea);
        scroll.setBorder(BorderFactory.createTitledBorder(I18n.get("remote_desktop.host_log_border")));
        panel.add(scroll, BorderLayout.CENTER);

        allowBeControlledBtn.addActionListener(e -> {
            boolean active = allowBeControlledBtn.isSelected();
            if (active) {
                allowBeControlledBtn.setText(I18n.get("remote_desktop.host_btn_on"));
                startHostService();
            } else {
                allowBeControlledBtn.setText(I18n.get("remote_desktop.host_btn_off"));
                stopHostService();
            }
        });

        return panel;
    }

    private void startHostService() {
        appendLog(hostLogArea, I18n.get("remote_desktop.host_log_starting"));
        hostStatusLabel.setText(I18n.get("remote_desktop.host_status_starting"));
        hostStatusLabel.setForeground(Color.ORANGE);

        try {
            URI uri = new URI(serverUrlField.getText().trim());
            hostSignalClient = new DesktopSignalClient(uri);
            hostSignalClient.setLogListener(msg -> appendLog(hostLogArea, "[Signal] " + msg));
            
            hostSignalClient.setListener(new DesktopSignalClient.DesktopSignalListener() {
                private WebSocketChannelImpl wsChannel;

                @Override
                public void onUserListReceived(List<Map<String, String>> users) {}

                @Override
                public void onPeerMessage(String fromId, byte[] rawData) {
                    if (wsChannel != null) {
                        wsChannel.handleRawMessage(rawData);
                    }
                }

                @Override
                public void onOfferReceived(String fromId, String sdp) {
                    appendLog(hostLogArea, I18n.get("remote_desktop.host_log_connect_req", fromId));
                    
                    // 1. 自动同意并应答 Answer
                    hostSignalClient.sendAnswer(fromId, "dummy_sdp");

                    // 2. 启动本地直连物理端口监听（让控制端连过来）
                    int hostPort = p2pConnector.startHostListener(
                        udpChannel -> {
                            appendLog(hostLogArea, I18n.get("remote_desktop.host_log_p2p_connected"));
                            setupHostChannel(udpChannel);
                        },
                        msg -> appendLog(hostLogArea, "[P2P] " + msg)
                    );

                    // 3. 开启本地增量打洞连接会话（反向去连接对方的 IP，双向打洞）
                    p2pConnector.startConnectorSession(
                        udpChannel -> {
                            appendLog(hostLogArea, I18n.get("remote_desktop.host_log_p2p_connected"));
                            setupHostChannel(udpChannel);
                        },
                        () -> {
                            appendLog(hostLogArea, "P2P 直连打洞超时失败！");
                        },
                        msg -> appendLog(hostLogArea, msg)
                    );

                    // 4. 获取本机所有网卡地址，包装成 ICE Candidate 发生给控制端
                    if (hostPort != -1) {
                        for (String ip : P2PConnector.getLocalIPv4Addresses()) {
                            String candStr = "candidate:12345 1 udp 2113937151 " + ip + " " + hostPort + " typ host";
                            hostSignalClient.sendIceCandidate(fromId, candStr);
                        }

                        // 直接读取刚才同步探测好的公网反射 IP 并发送
                        java.net.InetSocketAddress publicAddr = p2pConnector.getPublicAddress();
                        if (publicAddr != null) {
                            String publicIp = publicAddr.getHostString();
                            int publicPort = publicAddr.getPort();
                            String candStr = "candidate:12345 1 udp 2113937151 " + publicIp + " " + publicPort + " typ srflx";
                            hostSignalClient.sendIceCandidate(fromId, candStr);
                            appendLog(hostLogArea, "收集到本机公网反射 IP 候选: " + publicIp + ":" + publicPort);
                        }
                    }
                }

                @Override
                public void onIceCandidateReceived(String fromId, String candidate) {
                    p2pConnector.addCandidate(candidate);
                }

                @Override
                public void onPeerDisconnected(String peerId) {
                    appendLog(hostLogArea, I18n.get("remote_desktop.host_log_peer_disconnected"));
                    stopHostSession();
                }
            });

            hostSignalClient.connect();

            Timer hostJoinTimer = new Timer(500, null);
            hostJoinTimer.addActionListener(evt -> {
                if (hostSignalClient.isOpen()) {
                    String myHostId = "RD-" + (int)((Math.random() * 9 + 1) * 100000);
                    String group = groupField.getText().trim();
                    String name = nameField.getText().trim() + "(Be Controlled)";
                    
                    hostSignalClient.join(myHostId, group, name);
                    hostStatusLabel.setText(I18n.get("remote_desktop.host_status_waiting", group, myHostId));
                    hostStatusLabel.setForeground(new Color(75, 181, 67));
                    appendLog(hostLogArea, I18n.get("remote_desktop.host_log_join_success", group, myHostId));
                    hostJoinTimer.stop();
                }
            });
            hostJoinTimer.start();

        } catch (Exception ex) {
            appendLog(hostLogArea, "Failed to connect host: " + ex.getMessage());
            stopHostService();
        }
    }

    private void setupHostChannel(DesktopChannel channel) {
        stopHostSession();
        
        activeHostChannel = channel;
        activeHostChannel.setMessageListener(this::handleHostReceivedMessage);
        activeHostChannel.setCloseListener(() -> {
            appendLog(hostLogArea, "Data channel closed.");
            stopHostSession();
        });

        screenPushScheduler = Executors.newSingleThreadScheduledExecutor();
        screenPushScheduler.scheduleAtFixedRate(this::pushScreenFrame, 0, 75, TimeUnit.MILLISECONDS);
        
        SwingUtilities.invokeLater(() -> {
            overlayWindow = new TransparentOverlayWindow();
        });

        appendLog(hostLogArea, I18n.get("remote_desktop.host_log_channel_ready", channel.getStatusDescription()));
    }

    private byte[] lastFrameBytes = null;

    private void pushScreenFrame() {
        if (activeHostChannel == null || robot == null) return;
        try {
            BufferedImage screenImg = robot.createScreenCapture(new Rectangle(hostScreenSize));
            BufferedImage pushImg = screenImg;
            if (hostScreenSize.width > 1920) {
                int targetW = 1920;
                int targetH = (int) (1920.0 / hostScreenSize.width * hostScreenSize.height);
                pushImg = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = pushImg.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(screenImg, 0, 0, targetW, targetH, null);
                g.dispose();
            }

            byte[] imgBytes = compressImageToJpeg(pushImg, 0.65f);
            if (lastFrameBytes != null && lastFrameBytes.length == imgBytes.length && java.util.Arrays.equals(lastFrameBytes, imgBytes)) {
                return;
            }
            
            lastFrameBytes = imgBytes;
            activeHostChannel.send(new DesktopMessage(DesktopMessage.TYPE_SCREEN_FRAME, imgBytes));
        } catch (Exception ignored) {
        }
    }

    private void handleHostReceivedMessage(DesktopMessage msg) {
        switch (msg.getType()) {
            case DesktopMessage.TYPE_CONTROL_EVENT:
                try {
                    String jsonStr = new String(msg.getPayload(), StandardCharsets.UTF_8);
                    Map<String, Object> event = mapper.readValue(jsonStr, Map.class);
                    String type = (String) event.get("type");
                    if ("mouse".equals(type)) {
                        simulateMouse(event);
                    } else if ("key".equals(type)) {
                        simulateKeyboard(event);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case DesktopMessage.TYPE_DRAWING:
                try {
                    String jsonStr = new String(msg.getPayload(), StandardCharsets.UTF_8);
                    Map<String, Object> stroke = mapper.readValue(jsonStr, Map.class);
                    String action = (String) stroke.get("action");
                    
                    if (overlayWindow != null) {
                        if ("draw".equals(action)) {
                            double x1 = ((Number) stroke.get("x1")).doubleValue();
                            double y1 = ((Number) stroke.get("y1")).doubleValue();
                            double x2 = ((Number) stroke.get("x2")).doubleValue();
                            double y2 = ((Number) stroke.get("y2")).doubleValue();
                            
                            int thickness = ((Number) stroke.get("thickness")).intValue();
                            Color col = Color.decode((String) stroke.get("color"));
                            
                            SwingUtilities.invokeLater(() -> overlayWindow.addLine(x1, y1, x2, y2, col, thickness));
                        } else if ("clear".equals(action)) {
                            SwingUtilities.invokeLater(() -> overlayWindow.clearLines());
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            case DesktopMessage.TYPE_CMD_REQUEST:
                handleHostCmdRequest(msg.getPayload());
                break;
            case DesktopMessage.TYPE_FILE_TRANSFER:
                FileTransferDialog.handleFileTransferMessage(activeHostChannel, msg.getPayload(), hostFileReceivers, 
                        (msgStr) -> appendLog(hostLogArea, "[FileRecv] " + msgStr));
                break;
        }
    }

    private void simulateMouse(Map<String, Object> event) {
        if (robot == null) return;
        String action = (String) event.get("action");
        Number buttonNum = (Number) event.get("button");
        int button = buttonNum != null ? buttonNum.intValue() : 0;
        
        double rx = ((Number) event.get("x")).doubleValue();
        double ry = ((Number) event.get("y")).doubleValue();

        int sx = (int) (rx * hostScreenSize.width);
        int sy = (int) (ry * hostScreenSize.height);

        robot.mouseMove(sx, sy);

        int mask = 0;
        if (button == MouseEvent.BUTTON1) mask = InputEvent.BUTTON1_DOWN_MASK;
        else if (button == MouseEvent.BUTTON2) mask = InputEvent.BUTTON2_DOWN_MASK;
        else if (button == MouseEvent.BUTTON3) mask = InputEvent.BUTTON3_DOWN_MASK;

        try {
            if ("press".equals(action)) {
                if (mask != 0) robot.mousePress(mask);
            } else if ("release".equals(action)) {
                if (mask != 0) robot.mouseRelease(mask);
            } else if ("wheel".equals(action)) {
                robot.mouseWheel(button);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void simulateKeyboard(Map<String, Object> event) {
        if (robot == null) return;
        String action = (String) event.get("action");
        int keyCode = ((Number) event.get("keyCode")).intValue();

        if (keyCode == KeyEvent.VK_UNDEFINED) return;

        try {
            if ("press".equals(action)) {
                robot.keyPress(keyCode);
            } else if ("release".equals(action)) {
                robot.keyRelease(keyCode);
            }
        } catch (Exception ignored) {}
    }

    private void handleHostCmdRequest(byte[] payload) {
        try {
            Map<String, Object> req = mapper.readValue(new String(payload, StandardCharsets.UTF_8), Map.class);
            String action = (String) req.get("action");
            String sessionId = (String) req.get("sessionId");

            if ("exec".equals(action)) {
                String cmd = (String) req.get("command");
                executeHostCommand(sessionId, cmd);
            } else if ("close".equals(action)) {
                closeHostTerminalSession(sessionId);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void executeHostCommand(String sessionId, String command) {
        ProcessInfo pi = hostTerminalProcesses.get(sessionId);
        if (pi == null) {
            try {
                ProcessBuilder pb;
                boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
                if (isWindows) {
                    pb = new ProcessBuilder("cmd.exe");
                } else {
                    pb = new ProcessBuilder("/bin/sh");
                }
                pb.redirectErrorStream(true);
                Process p = pb.start();
                
                String encoding = isWindows ? "GBK" : "UTF-8";
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), encoding));
                pi = new ProcessInfo(p, writer);
                hostTerminalProcesses.put(sessionId, pi);

                final ProcessInfo finalPi = pi;
                new Thread(() -> {
                    try (InputStream is = finalPi.process.getInputStream();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(is, encoding))) {
                        
                        char[] buf = new char[1024];
                        int len;
                        while ((len = reader.read(buf)) != -1) {
                            String output = new String(buf, 0, len);
                            sendCmdResponse(sessionId, output);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        closeHostTerminalSession(sessionId);
                    }
                }, "Terminal-Reader-" + sessionId).start();

            } catch (Exception e) {
                sendCmdResponse(sessionId, I18n.get("remote_desktop.term_fail_start", e.getMessage()) + "\n");
                return;
            }
        }

        if (command != null && !command.isEmpty()) {
            try {
                pi.writer.write(command + "\n");
                pi.writer.flush();
            } catch (IOException e) {
                sendCmdResponse(sessionId, "Write command failed: " + e.getMessage() + "\n");
            }
        }
    }

    private void sendCmdResponse(String sessionId, String output) {
        if (activeHostChannel == null) return;
        try {
            Map<String, Object> resp = new HashMap<>();
            resp.put("output", output);
            resp.put("sessionId", sessionId);

            byte[] jsonBytes = mapper.writeValueAsString(resp).getBytes(StandardCharsets.UTF_8);
            activeHostChannel.send(new DesktopMessage(DesktopMessage.TYPE_CMD_RESPONSE, jsonBytes));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void closeHostTerminalSession(String sessionId) {
        ProcessInfo pi = hostTerminalProcesses.remove(sessionId);
        if (pi != null) {
            try {
                pi.writer.close();
            } catch (Exception ignored) {}
            pi.process.destroyForcibly();
            appendLog(hostLogArea, "Session terminated: " + sessionId);
        }
    }

    private void stopHostSession() {
        if (screenPushScheduler != null) {
            screenPushScheduler.shutdownNow();
            screenPushScheduler = null;
        }
        
        if (overlayWindow != null) {
            final TransparentOverlayWindow toDestroy = overlayWindow;
            overlayWindow = null;
            SwingUtilities.invokeLater(() -> {
                toDestroy.destroy();
            });
        }

        for (String sessId : hostTerminalProcesses.keySet()) {
            closeHostTerminalSession(sessId);
        }
        
        for (FileTransferDialog.FileReceiver rec : hostFileReceivers.values()) {
            rec.close();
        }
        hostFileReceivers.clear();
        
        p2pConnector.stopConnectorSession();

        if (activeHostChannel != null) {
            activeHostChannel.close();
            activeHostChannel = null;
        }
        lastFrameBytes = null;
    }

    private void stopHostService() {
        stopHostSession();
        p2pConnector.stopHostListener();
        
        if (hostSignalClient != null) {
            hostSignalClient.close();
            hostSignalClient = null;
        }
        hostStatusLabel.setText(I18n.get("remote_desktop.host_status_offline"));
        hostStatusLabel.setForeground(Color.GRAY);
        appendLog(hostLogArea, I18n.get("remote_desktop.host_log_stopped"));
    }

    // ==================== 选项卡3：本地信令服务 ====================
    private JComponent buildServerTab() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        top.setBorder(BorderFactory.createTitledBorder(I18n.get("remote_desktop.server_config_border")));

        top.add(new JLabel(I18n.get("remote_desktop.server_port")));
        localPortField = new JTextField("21701");
        localPortField.setPreferredSize(new Dimension(80, 28));
        top.add(localPortField);

        startServerBtn = new JButton(I18n.get("remote_desktop.server_btn_start"));
        startServerBtn.setPreferredSize(new Dimension(120, 28));
        top.add(startServerBtn);

        panel.add(top, BorderLayout.NORTH);

        serverLogArea = new JTextArea();
        serverLogArea.setEditable(false);
        JScrollPane scroll = new JScrollPane(serverLogArea);
        scroll.setBorder(BorderFactory.createTitledBorder(I18n.get("remote_desktop.server_log_border")));
        panel.add(scroll, BorderLayout.CENTER);

        startServerBtn.addActionListener(e -> {
            if (localSignalServer != null) {
                stopLocalServer();
            } else {
                startLocalServer();
            }
        });

        return panel;
    }

    private void startLocalServer() {
        try {
            int port = Integer.parseInt(localPortField.getText().trim());
            localSignalServer = new DesktopSignalServer(port);
            localSignalServer.setLogListener(msg -> appendLog(serverLogArea, msg));
            localSignalServer.start();
            
            startServerBtn.setText(I18n.get("remote_desktop.server_btn_stop"));
            localPortField.setEnabled(false);
            appendLog(serverLogArea, I18n.get("remote_desktop.server_log_started", String.valueOf(port)));
        } catch (Exception ex) {
            appendLog(serverLogArea, "Relay service error: " + ex.getMessage());
            localSignalServer = null;
        }
    }

    private void stopLocalServer() {
        if (localSignalServer != null) {
            try {
                localSignalServer.stop();
                appendLog(serverLogArea, I18n.get("remote_desktop.server_log_stopped"));
            } catch (Exception ex) {
                appendLog(serverLogArea, "Relay stop error: " + ex.getMessage());
            }
            localSignalServer = null;
        }
        startServerBtn.setText(I18n.get("remote_desktop.server_btn_start"));
        localPortField.setEnabled(true);
    }

    private void appendLog(JTextArea area, String msg) {
        SwingUtilities.invokeLater(() -> {
            area.append(msg + "\n");
            area.setCaretPosition(area.getDocument().getLength());
        });
    }

    private void startControlWindow(DesktopChannel channel, String peerId) {
        if (channel == null) return;
        SwingUtilities.invokeLater(() -> {
            activeControlChannel = channel;
            activeControlWindow = new RemoteControlWindow(channel, peerId);
            activeControlWindow.setVisible(true);
        });
    }

    private static byte[] compressImageToJpeg(BufferedImage img, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IllegalStateException("No JPG ImageWriters found");
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }

    public void cleanup() {
        stopHostService();
        if (signalClient != null) {
            signalClient.close();
        }
        if (activeControlWindow != null) {
            activeControlWindow.dispose();
        }
        p2pConnector.stopConnectorSession();
        stopLocalServer();
    }
}
