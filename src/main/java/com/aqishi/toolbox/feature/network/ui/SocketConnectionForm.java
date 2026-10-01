package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.SocketSession;
import com.aqishi.toolbox.feature.network.application.TcpClientSession;
import com.aqishi.toolbox.feature.network.application.TcpServerSession;
import com.aqishi.toolbox.feature.network.application.UdpSession;
import com.aqishi.toolbox.feature.network.domain.PayloadCodec;
import com.aqishi.toolbox.feature.network.domain.SocketFrameSplitter;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接参数表单，字段随模式变化。只负责收集与校验输入，生成会话参数；不做任何网络操作。
 */
final class SocketConnectionForm extends JPanel {

    private final SocketSession.Kind kind;

    // 通用
    final JTextField hostField = Fields.mono("127.0.0.1");
    final JSpinner portSpinner = portSpinner(8080);
    final JComboBox<String> bindCombo = Fields.combo(new String[]{"0.0.0.0", "127.0.0.1", "::"});
    final JSpinner localPortSpinner = portSpinner(0);
    final JCheckBox echoCheck = Fields.check(I18n.get("tool.socketdebug.form.echo"), false);

    // TCP 客户端
    final JTextField localHostField = Fields.mono("");
    final JSpinner timeoutSpinner = Fields.spinner(5000, 100, 120_000, 500);
    final JCheckBox noDelayCheck = Fields.check("TCP_NODELAY", true);
    final JCheckBox keepAliveCheck = Fields.check("SO_KEEPALIVE", false);
    final JCheckBox tlsCheck = Fields.check("TLS", false);
    final JCheckBox trustAllCheck = Fields.check(I18n.get("tool.socketdebug.form.trustAll"), false);
    final JCheckBox reconnectCheck = Fields.check(I18n.get("tool.socketdebug.form.reconnect"), false);

    // TCP 服务端
    final JSpinner maxClientsSpinner = Fields.spinner(TcpServerSession.DEFAULT_MAX_CLIENTS, 1, 10_000, 1);

    // TCP 分帧
    final JComboBox<String> frameCombo = Fields.combo(new String[]{
            I18n.get("tool.socketdebug.frame.raw"), I18n.get("tool.socketdebug.frame.delimiter"),
            I18n.get("tool.socketdebug.frame.fixed"), I18n.get("tool.socketdebug.frame.idle"), I18n.get("tool.socketdebug.frame.length")});
    final JTextField frameParamField = Fields.mono("\\n");
    final JCheckBox keepDelimiterCheck = Fields.check(I18n.get("tool.socketdebug.frame.keep"), false);
    final JSpinner lengthOffset = Fields.spinner(0, 0, 65535, 1);
    final JComboBox<Integer> lengthWidth = new JComboBox<>(new Integer[]{1, 2, 4});
    final JComboBox<String> lengthOrder = new JComboBox<>(new String[]{"Big endian", "Little endian"});
    final JSpinner headerSize = Fields.spinner(2, 1, 65536, 1);
    final JSpinner frameLimit = Fields.spinner(65536, 1, 16 * 1024 * 1024, 1024);
    final JCheckBox lengthIncludesHeader = Fields.check(I18n.get("tool.socketdebug.frame.includesHeader"), false);
    final JCheckBox stripHeader = Fields.check(I18n.get("tool.socketdebug.frame.stripHeader"), false);
    private JPanel lengthFields;

    // UDP
    final JTextField targetHostField = Fields.mono("127.0.0.1");
    final JSpinner targetPortSpinner = portSpinner(9000);
    final JCheckBox replyLastCheck = Fields.check(I18n.get("tool.socketdebug.udp.replyLast"), false);
    final JCheckBox broadcastCheck = Fields.check(I18n.get("tool.socketdebug.udp.broadcast"), false);
    final JTextField groupField = Fields.mono("239.255.0.1");
    final JTextField interfaceField = Fields.mono("");
    final JButton joinBtn = Buttons.snug(I18n.get("tool.socketdebug.udp.join"));
    final JButton leaveBtn = Buttons.snug(I18n.get("tool.socketdebug.udp.leave"));

    /** 连接期间禁止修改的字段。 */
    private final List<JComponent> connectionFields = new ArrayList<>();
    private final List<Component> rows = new ArrayList<>();

    SocketConnectionForm(SocketSession.Kind kind) {
        super(new BorderLayout());
        setOpaque(false);
        this.kind = kind;
        bindCombo.setEditable(true);
        frameParamField.setColumns(8);
        hostField.setColumns(14);
        targetHostField.setColumns(12);
        groupField.setColumns(12);
        interfaceField.setColumns(8);
        localHostField.setColumns(10);
        interfaceField.putClientProperty("JTextField.placeholderText", I18n.get("tool.socketdebug.udp.interface.hint"));
        localHostField.putClientProperty("JTextField.placeholderText", I18n.get("tool.socketdebug.form.auto"));
        frameParamField.setToolTipText(I18n.get("tool.socketdebug.frame.param.tip"));
        switch (kind) {
            case TCP_CLIENT:
                buildTcpClient();
                break;
            case TCP_SERVER:
                buildTcpServer();
                break;
            default:
                buildUdp();
                break;
        }
        add(Layouts.stack(Tokens.SPACE_XS, rows.toArray(new Component[0])), BorderLayout.CENTER);
        frameCombo.addActionListener(e -> syncFrameFields());
        tlsCheck.addActionListener(e -> trustAllCheck.setEnabled(tlsCheck.isSelected() && tlsCheck.isEnabled()));
        trustAllCheck.setEnabled(false);
        syncFrameFields();
    }

    private void buildTcpClient() {
        rows.add(row(label("tool.socketdebug.form.host"), hostField, label("tool.socketdebug.form.port"), portSpinner,
                label("tool.socketdebug.form.timeout"), timeoutSpinner, tlsCheck, trustAllCheck));
        rows.add(row(label("tool.socketdebug.form.localBind"), localHostField, localPortSpinner,
                noDelayCheck, keepAliveCheck, reconnectCheck));
        rows.add(frameRow());
        connectionFields.addAll(List.of(hostField, portSpinner, timeoutSpinner, tlsCheck, trustAllCheck,
                localHostField, localPortSpinner, noDelayCheck, keepAliveCheck, reconnectCheck));
    }

    private void buildTcpServer() {
        rows.add(row(label("tool.socketdebug.form.bind"), bindCombo, label("tool.socketdebug.form.port"), localPortSpinner,
                label("tool.socketdebug.form.maxClients"), maxClientsSpinner, echoCheck));
        rows.add(frameRow());
        localPortSpinner.setValue(8080);
        connectionFields.addAll(List.of(bindCombo, localPortSpinner, maxClientsSpinner));
    }

    private void buildUdp() {
        rows.add(row(label("tool.socketdebug.form.bind"), bindCombo, label("tool.socketdebug.form.localPort"),
                localPortSpinner, echoCheck, broadcastCheck));
        rows.add(row(label("tool.socketdebug.udp.target"), targetHostField, targetPortSpinner, replyLastCheck));
        rows.add(row(label("tool.socketdebug.udp.group"), groupField, interfaceField, joinBtn, leaveBtn));
        connectionFields.addAll(List.of(bindCombo, localPortSpinner));
        setMulticastEnabled(false);
    }

    private JPanel frameRow() {
        lengthWidth.setSelectedItem(2);
        JPanel panel = new JPanel(new java.awt.BorderLayout(0, Tokens.SPACE_XS)); panel.setOpaque(false);
        panel.add(row(label("tool.socketdebug.frame.mode"), frameCombo, frameParamField, keepDelimiterCheck), java.awt.BorderLayout.NORTH);
        lengthFields = row(label("tool.socketdebug.frame.offset"), lengthOffset, label("tool.socketdebug.frame.width"), lengthWidth,
                lengthOrder, label("tool.socketdebug.frame.headerSize"), headerSize, label("tool.socketdebug.frame.limit"), frameLimit,
                lengthIncludesHeader, stripHeader);
        panel.add(lengthFields); lengthFields.setVisible(false);
        return panel;
    }

    private static JPanel row(Component... children) {
        return Layouts.wrapRow(Tokens.SPACE_SM, Tokens.SPACE_XS, children);
    }

    private static JLabel label(String key) {
        return Fields.label(I18n.get(key));
    }

    static JSpinner portSpinner(int value) {
        JSpinner spinner = Fields.spinner(value, 0, 65535, 1);
        // 默认 NumberEditor 会按千分位显示成 8,080
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "#"));
        return spinner;
    }

    private void syncFrameFields() {
        int mode = frameCombo.getSelectedIndex();
        frameParamField.setEnabled(mode != 0 && mode != 4);
        if (lengthFields != null) { lengthFields.setVisible(mode == 4); lengthFields.getParent().revalidate(); }
        keepDelimiterCheck.setEnabled(mode == 1);
        if (mode == 1 && !looksLikeDelimiter(frameParamField.getText())) {
            frameParamField.setText("\\n");
        } else if ((mode == 2 || mode == 3) && !frameParamField.getText().trim().matches("\\d+")) {
            frameParamField.setText(mode == 2 ? "16" : "50");
        }
    }

    private static boolean looksLikeDelimiter(String text) {
        return !text.isEmpty() && !text.trim().matches("\\d+");
    }

    /** 当前分帧配置；参数非法时抛出 IllegalArgumentException（消息已本地化）。 */
    SocketFrameSplitter.Config frameConfig() {
        String param = frameParamField.getText();
        try {
            switch (frameCombo.getSelectedIndex()) {
                case 1:
                    byte[] delimiter = PayloadCodec.encodeEscaped(param, StandardCharsets.UTF_8);
                    return SocketFrameSplitter.Config.delimiter(delimiter, keepDelimiterCheck.isSelected());
                case 2:
                    return SocketFrameSplitter.Config.fixedLength(Integer.parseInt(param.trim()));
                case 3:
                    return SocketFrameSplitter.Config.idleTimeout(Long.parseLong(param.trim()));
                case 4:
                    lengthOffset.commitEdit(); headerSize.commitEdit(); frameLimit.commitEdit();
                    return SocketFrameSplitter.Config.lengthField(new SocketFrameSplitter.LengthField(
                            (Integer) lengthOffset.getValue(), (Integer) lengthWidth.getSelectedItem(), lengthOrder.getSelectedIndex() == 1,
                            (Integer) headerSize.getValue(), lengthIncludesHeader.isSelected(), stripHeader.isSelected()), (Integer) frameLimit.getValue());
                default:
                    return SocketFrameSplitter.Config.raw();
            }
        } catch (IllegalArgumentException | java.text.ParseException invalid) {
            throw new IllegalArgumentException(I18n.get("tool.socketdebug.frame.invalid", param), invalid);
        }
    }

    TcpClientSession.Options tcpClientOptions() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException(I18n.get("tool.socketdebug.form.hostRequired"));
        }
        int port = port(portSpinner);
        if (port == 0) {
            throw new IllegalArgumentException(I18n.get("tool.socketdebug.form.portRequired"));
        }
        return new TcpClientSession.Options().host(host).port(port)
                .localHost(localHostField.getText().trim()).localPort(port(localPortSpinner))
                .connectTimeoutMillis(((Number) timeoutSpinner.getValue()).intValue())
                .noDelay(noDelayCheck.isSelected()).keepAlive(keepAliveCheck.isSelected())
                .tls(tlsCheck.isSelected()).trustAll(tlsCheck.isSelected() && trustAllCheck.isSelected())
                .autoReconnect(reconnectCheck.isSelected()).frame(frameConfig());
    }

    TcpServerSession.Options tcpServerOptions() {
        return new TcpServerSession.Options().bindHost(bindHost()).port(port(localPortSpinner))
                .maxClients(((Number) maxClientsSpinner.getValue()).intValue())
                .echo(echoCheck.isSelected()).frame(frameConfig());
    }

    UdpSession.Options udpOptions() {
        return new UdpSession.Options().bindHost(bindHost()).bindPort(port(localPortSpinner))
                .target(targetHostField.getText().trim(), port(targetPortSpinner))
                .broadcast(broadcastCheck.isSelected()).echo(echoCheck.isSelected())
                .replyToLastSender(replyLastCheck.isSelected());
    }

    private String bindHost() {
        Object value = bindCombo.getEditor().getItem();
        String text = value == null ? "" : value.toString().trim();
        return text.isEmpty() ? "0.0.0.0" : text;
    }

    static int port(JSpinner spinner) {
        try {
            spinner.commitEdit();
        } catch (java.text.ParseException invalid) {
            // 保留上一个合法值
        }
        return ((Number) spinner.getValue()).intValue();
    }

    int targetPort() {
        return port(targetPortSpinner);
    }

    /** 连接期间锁定连接参数；运行期可改的（回显、分帧、UDP 目标等）保持可编辑。 */
    void setConnected(boolean connected) {
        for (JComponent field : connectionFields) {
            field.setEnabled(!connected);
        }
        if (!connected) {
            trustAllCheck.setEnabled(tlsCheck.isSelected());
        }
        if (kind == SocketSession.Kind.UDP) {
            setMulticastEnabled(connected);
        }
    }

    private void setMulticastEnabled(boolean enabled) {
        joinBtn.setEnabled(enabled);
        leaveBtn.setEnabled(enabled);
    }
}
