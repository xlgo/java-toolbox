package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.SocketSession;
import com.aqishi.toolbox.feature.network.application.TcpClientSession;
import com.aqishi.toolbox.feature.network.application.TcpServerSession;
import com.aqishi.toolbox.feature.network.application.UdpSession;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.feature.network.domain.SocketOpenException;
import com.aqishi.toolbox.feature.network.domain.SocketStats;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;

/**
 * 一个会话标签页：连接表单 + 状态/统计 + 收发日志 + 发送区 + 快捷发送（服务端另有客户端列表）。
 *
 * <p>线程约定：会话事件在网络线程上到达；收发数据直接投递给日志视图的无锁队列，
 * 连接状态类事件（低频）经 invokeLater 回到 EDT 处理。每次连接递增 generation，
 * 旧会话迟到的事件只记日志、不改界面状态。</p>
 */
final class SocketSessionView extends JPanel {

    private enum UiState { IDLE, CONNECTING, OPEN, RECONNECTING }

    private final SocketSession.Kind kind;
    private final SocketConnectionForm form;
    private final SocketLogView log = new SocketLogView();
    private final SocketSendBox sendBox;
    private final SocketSendFileBar fileBar;
    private final SocketClientTable clientTable;
    private final JButton toggleBtn;
    private final JLabel statusLabel = Fields.caption("");
    private final JLabel statsLabel = Fields.caption("");
    private final Timer statsTimer;

    private volatile SocketSession session;
    private volatile int generation;
    private UiState state = UiState.IDLE;
    private SocketStats.Snapshot lastSnapshot;

    SocketSessionView(SocketSession.Kind kind, QuickSendList.Shared presets) {
        super(new BorderLayout(0, Tokens.SPACE_SM));
        setOpaque(false);
        this.kind = kind;
        this.form = new SocketConnectionForm(kind);
        this.toggleBtn = Buttons.primary(idleLabel());

        SocketSendRouter router = new SocketSendRouter(kind, () -> session, form, log::post);
        fileBar = new SocketSendFileBar(router);
        sendBox = new SocketSendBox(router, fileBar);
        clientTable = kind == SocketSession.Kind.TCP_SERVER ? new SocketClientTable(this::kick) : null;
        router.attach(sendBox, clientTable);
        QuickSendList quick = new QuickSendList(presets, router);

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(quick), BorderLayout.CENTER);

        toggleBtn.addActionListener(e -> toggle());
        wireRuntimeOptions();
        statsTimer = new Timer(1000, e -> refreshStats());
        statsTimer.start();
        applyState(UiState.IDLE);
    }

    private Card buildTop() {
        JPanel status = Layouts.wrapRow(Tokens.SPACE_MD, Tokens.SPACE_XS, toggleBtn, statusLabel, statsLabel);
        Card card = Card.plain();
        card.setContent(Layouts.stack(Tokens.SPACE_SM, form, status));
        return card;
    }

    private JComponent buildCenter(QuickSendList quick) {
        Card logCard = Card.flush(I18n.get("tool.socketdebug.card.log"));
        logCard.setContent(log);

        Card quickCard = Card.flush(I18n.get("tool.socketdebug.card.quick"));
        quickCard.setContent(quick);
        JComponent side = quickCard;
        if (clientTable != null) {
            Card clientsCard = Card.flush(I18n.get("tool.socketdebug.card.clients"));
            clientsCard.setContent(clientTable);
            side = Layouts.splitVertical(clientsCard, quickCard, 0.5, 0.5);
        }
        JSplitPane upper = Layouts.splitHorizontal(logCard, side, 1.0, 0.72);

        Card sendCard = Card.flush(I18n.get("tool.socketdebug.card.send"));
        sendCard.setContent(sendBox);
        return Layouts.splitVertical(upper, sendCard, 1.0, 0.62);
    }

    private String idleLabel() {
        switch (kind) {
            case TCP_SERVER: return I18n.get("tool.socketdebug.btn.listen");
            case UDP: return I18n.get("tool.socketdebug.btn.bind");
            default: return I18n.get("tool.socketdebug.btn.connect");
        }
    }

    private String openLabel() {
        switch (kind) {
            case TCP_SERVER: return I18n.get("tool.socketdebug.btn.stop");
            case UDP: return I18n.get("tool.socketdebug.btn.unbind");
            default: return I18n.get("tool.socketdebug.btn.disconnect");
        }
    }

    /** 运行期可改的选项直接作用到当前会话。 */
    private void wireRuntimeOptions() {
        form.echoCheck.addActionListener(e -> {
            SocketSession current = session;
            if (current instanceof TcpServerSession) {
                ((TcpServerSession) current).setEcho(form.echoCheck.isSelected());
            } else if (current instanceof UdpSession) {
                ((UdpSession) current).setEcho(form.echoCheck.isSelected());
            }
        });
        form.broadcastCheck.addActionListener(e -> {
            SocketSession current = session;
            if (current instanceof UdpSession) {
                try {
                    ((UdpSession) current).setBroadcast(form.broadcastCheck.isSelected());
                } catch (SocketOpenException error) {
                    reportOpenError(error);
                }
            }
        });
        form.joinBtn.addActionListener(e -> membership(true));
        form.leaveBtn.addActionListener(e -> membership(false));
        Runnable frame = this::applyFrameConfig;
        form.frameCombo.addActionListener(e -> frame.run());
        form.keepDelimiterCheck.addActionListener(e -> frame.run());
        form.lengthOffset.addChangeListener(e -> frame.run());
        form.lengthWidth.addActionListener(e -> frame.run());
        form.lengthOrder.addActionListener(e -> frame.run());
        form.headerSize.addChangeListener(e -> frame.run());
        form.frameLimit.addChangeListener(e -> frame.run());
        form.lengthIncludesHeader.addActionListener(e -> frame.run());
        form.stripHeader.addActionListener(e -> frame.run());
        form.frameParamField.addActionListener(e -> frame.run());
    }

    private void applyFrameConfig() {
        SocketSession current = session;
        if (!(current instanceof TcpClientSession) && !(current instanceof TcpServerSession)) {
            return;
        }
        try {
            if (current instanceof TcpClientSession) {
                ((TcpClientSession) current).setFrameConfig(form.frameConfig());
            } else {
                ((TcpServerSession) current).setFrameConfig(form.frameConfig());
            }
        } catch (IllegalArgumentException invalid) {
            log.post(SocketLogEntry.error(System.currentTimeMillis(), invalid.getMessage()));
        }
    }

    private void membership(boolean join) {
        SocketSession current = session;
        if (!(current instanceof UdpSession)) {
            return;
        }
        String group = form.groupField.getText().trim();
        String nif = form.interfaceField.getText().trim();
        try {
            if (join) {
                ((UdpSession) current).joinGroup(group, nif);
                info(I18n.get("tool.socketdebug.udp.joined", group));
            } else {
                ((UdpSession) current).leaveGroup(group, nif);
                info(I18n.get("tool.socketdebug.udp.left", group));
            }
        } catch (SocketOpenException error) {
            reportOpenError(error);
        }
    }

    // ------------------------------------------------------------------
    // 连接生命周期
    // ------------------------------------------------------------------

    void toggle() {
        if (state == UiState.IDLE) {
            connect();
        } else {
            disconnect();
        }
    }

    private void connect() {
        SocketSession created;
        int gen = ++generation;
        SocketEventRelay relay = new SocketEventRelay(gen);
        try {
            switch (kind) {
                case TCP_SERVER:
                    created = new TcpServerSession(form.tcpServerOptions(), relay);
                    break;
                case UDP:
                    created = new UdpSession(form.udpOptions(), relay);
                    break;
                default:
                    created = new TcpClientSession(form.tcpClientOptions(), relay);
                    break;
            }
        } catch (IllegalArgumentException invalid) {
            log.post(SocketLogEntry.error(System.currentTimeMillis(), invalid.getMessage()));
            return;
        }
        session = created;
        lastSnapshot = null;
        applyState(UiState.CONNECTING);
        DaemonThreads.factory("socket-debug-open").newThread(() -> {
            try {
                created.open();
                SwingUtilities.invokeLater(() -> {
                    if (session == created && state == UiState.CONNECTING && created.isOpen()) {
                        applyState(UiState.OPEN);
                    }
                });
            } catch (IOException | RuntimeException error) {
                SwingUtilities.invokeLater(() -> {
                    if (error instanceof SocketOpenException) {
                        reportOpenError((SocketOpenException) error);
                    } else {
                        log.post(SocketLogEntry.error(System.currentTimeMillis(), Errors.describe(error)));
                    }
                    if (session == created) {
                        session = null;
                        applyState(UiState.IDLE);
                    }
                });
            }
        }).start();
    }

    private void disconnect() {
        SocketSession current = session;
        session = null;
        generation++;
        applyState(UiState.IDLE);
        if (current != null) {
            current.close();
            info(I18n.get("tool.socketdebug.status.closedByUser"));
        }
    }

    private void applyState(UiState next) {
        state = next;
        boolean active = next != UiState.IDLE;
        form.setConnected(active);
        if (!active) {
            sendBox.stopAutoSend();
            fileBar.cancel();
        }
        switch (next) {
            case CONNECTING:
                toggleBtn.setText(I18n.get("tool.socketdebug.btn.cancel"));
                setStatus(I18n.get("tool.socketdebug.status.connecting"), Tokens.warning());
                break;
            case RECONNECTING:
                toggleBtn.setText(openLabel());
                setStatus(I18n.get("tool.socketdebug.status.reconnecting"), Tokens.warning());
                break;
            case OPEN:
                toggleBtn.setText(openLabel());
                setStatus(describeOpen(), Tokens.success());
                break;
            default:
                toggleBtn.setText(idleLabel());
                setStatus(I18n.get("tool.socketdebug.status.idle"), Tokens.mutedForeground());
                break;
        }
        // 连接中/已连接时按钮用危险色提示"再点就断开"
        toggleBtn.putClientProperty("FlatLaf.styleClass", active ? "danger" : "primary");
        toggleBtn.repaint();
        refreshStats();
    }

    private String describeOpen() {
        SocketSession current = session;
        if (current == null) {
            return "";
        }
        String local = SocketEvent.format(current.localAddress());
        if (current instanceof TcpClientSession) {
            return I18n.get("tool.socketdebug.status.connected", local,
                    SocketEvent.format(((TcpClientSession) current).remoteAddress()));
        }
        if (current instanceof TcpServerSession) {
            return I18n.get("tool.socketdebug.status.listening", local);
        }
        return I18n.get("tool.socketdebug.status.bound", local);
    }

    private void setStatus(String text, Color color) {
        statusLabel.setText(text);
        statusLabel.setForeground(color);
    }

    private void refreshStats() {
        SocketSession current = session;
        if (current == null) {
            return;
        }
        SocketStats.Snapshot now = current.stats().snapshot();
        statsLabel.setText(I18n.get("tool.socketdebug.stats",
                SocketUiText.bytes(now.getBytesSent()), String.valueOf(now.getPacketsSent()),
                SocketUiText.bytes(now.getBytesReceived()), String.valueOf(now.getPacketsReceived()),
                SocketUiText.bytes(now.sendRate(lastSnapshot)), SocketUiText.bytes(now.receiveRate(lastSnapshot))));
        lastSnapshot = now;
        if (clientTable != null && current instanceof TcpServerSession) {
            clientTable.update(((TcpServerSession) current).clients());
        }
    }

    private void kick(int clientId) {
        SocketSession current = session;
        if (current instanceof TcpServerSession) {
            ((TcpServerSession) current).disconnect(clientId);
        }
    }

    /** EDT：处理连接状态类事件。 */
    private void handleControl(SocketEvent event, int gen) {
        if (gen != generation) {
            return;
        }
        SocketSession current = session;
        switch (event.getType()) {
            case CONNECTED:
                if (current != null && current.isOpen()) {
                    applyState(UiState.OPEN);
                }
                break;
            case RECONNECTING:
                if (current != null && !current.isClosed()) applyState(UiState.RECONNECTING);
                break;
            case DISCONNECTED:
                if (current == null || current.isClosed()) {
                    session = null;
                    applyState(UiState.IDLE);
                } else {
                    applyState(UiState.RECONNECTING);
                }
                break;
            case CLIENT_JOINED:
            case CLIENT_LEFT:
                if (clientTable != null && current instanceof TcpServerSession) {
                    clientTable.update(((TcpServerSession) current).clients());
                }
                break;
            default:
                break;
        }
    }

    private void reportOpenError(SocketOpenException error) {
        log.post(SocketLogEntry.error(System.currentTimeMillis(),
                SocketUiText.error(error.getError()) + ": " + error.getMessage()));
    }

    private void info(String text) {
        log.post(SocketLogEntry.info(System.currentTimeMillis(), text));
    }

    /** 关闭会话并停止所有定时器；标签关闭或面板释放时调用，可重复调用。 */
    void dispose() {
        statsTimer.stop();
        sendBox.dispose();
        fileBar.cancel();
        SocketSession current = session;
        session = null;
        generation++;
        if (current != null) {
            current.close();
        }
        log.dispose();
    }

    SocketSession.Kind kind() {
        return kind;
    }

    // ---- 测试辅助 ----

    SocketConnectionForm formForTest() { return form; }
    SocketSendBox sendBoxForTest() { return sendBox; }
    SocketLogView logForTest() { return log; }
    SocketClientTable clientTableForTest() { return clientTable; }
    boolean isOpenForTest() { return state == UiState.OPEN; }
    boolean isIdleForTest() { return state == UiState.IDLE; }
    SocketSession sessionForTest() { return session; }

    // ------------------------------------------------------------------

    /** 会话监听器：数据直接进日志队列，控制事件回到 EDT。 */
    private final class SocketEventRelay implements com.aqishi.toolbox.feature.network.application.SocketSessionListener {
        private final int gen;

        SocketEventRelay(int gen) {
            this.gen = gen;
        }

        @Override
        public void onEvent(SocketEvent event) {
            if (gen != generation) return;
            switch (event.getType()) {
                case RECEIVED:
                case SENT:
                    log.post(SocketLogEntry.payload(event.getTime(), event.getType() == SocketEvent.Type.SENT,
                            SocketUiText.peer(event), event.getData()));
                    return;
                case ERROR:
                    log.post(SocketLogEntry.error(event.getTime(), SocketUiText.describe(event)));
                    break;
                default:
                    log.post(SocketLogEntry.info(event.getTime(), SocketUiText.describe(event)));
                    break;
            }
            SwingUtilities.invokeLater(() -> handleControl(event, gen));
        }
    }
}
