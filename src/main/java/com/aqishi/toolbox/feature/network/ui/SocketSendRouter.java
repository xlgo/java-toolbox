package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.application.SocketSession;
import com.aqishi.toolbox.feature.network.application.TcpServerSession;
import com.aqishi.toolbox.feature.network.application.UdpSession;
import com.aqishi.toolbox.feature.network.domain.PayloadCodec;
import com.aqishi.toolbox.feature.network.domain.PayloadParseException;
import com.aqishi.toolbox.feature.network.domain.PayloadPreset;
import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketSendException;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 发送区、文件发送与快捷发送共用的去向：按会话类型路由——服务端发给选中或全部客户端，
 * UDP 发送前把表单里的目标同步给会话，TCP 客户端直接入队。
 */
final class SocketSendRouter implements SocketSendBox.Sink, SocketSendFileBar.Target, QuickSendList.Host {

    private static final int FILE_CHUNK_TCP = 16 * 1024;
    private static final int FILE_CHUNK_UDP = 1024;
    private static final long FILE_WAIT_MS = 10_000;

    private final SocketSession.Kind kind;
    private final Supplier<SocketSession> session;
    private final SocketConnectionForm form;
    private final Consumer<SocketLogEntry> log;
    private SocketClientTable clientTable;
    private SocketSendBox sendBox;

    SocketSendRouter(SocketSession.Kind kind, Supplier<SocketSession> session, SocketConnectionForm form,
                     Consumer<SocketLogEntry> log) {
        this.kind = kind;
        this.session = session;
        this.form = form;
        this.log = log;
    }

    /** 发送区与客户端表依赖本对象构造，因此事后注入。 */
    void attach(SocketSendBox box, SocketClientTable table) {
        this.sendBox = box;
        this.clientTable = table;
    }

    private boolean toSelectedClients(SocketSession current) {
        return current instanceof TcpServerSession && clientTable != null && clientTable.isSendToSelected();
    }

    @Override
    public void send(byte[] payload) {
        SocketSession current = requireOpen();
        if (toSelectedClients(current)) {
            ((TcpServerSession) current).sendTo(clientTable.selectedIds(), payload);
        } else {
            prepareUdp(current);
            current.send(payload);
        }
    }

    @Override
    public void sendAwait(byte[] chunk) throws Exception {
        SocketSession current = requireOpen();
        if (toSelectedClients(current)) {
            ((TcpServerSession) current).sendToAwait(clientTable.selectedIds(), chunk, FILE_WAIT_MS);
        } else {
            current.sendAwait(chunk, FILE_WAIT_MS);
        }
    }

    private SocketSession requireOpen() {
        SocketSession current = session.get();
        if (current == null || !current.isOpen()) {
            throw new SocketSendException(SocketError.NOT_CONNECTED, "Not connected");
        }
        return current;
    }

    /** UDP 目标取表单当前值（EDT 上读），发送前同步给会话。 */
    private void prepareUdp(SocketSession current) {
        if (current instanceof UdpSession) {
            UdpSession udp = (UdpSession) current;
            udp.setTarget(form.targetHostField.getText().trim(), form.targetPort());
            udp.setReplyToLastSender(form.replyLastCheck.isSelected());
        }
    }

    @Override
    public boolean isReady() {
        SocketSession current = session.get();
        return current != null && current.isOpen();
    }

    @Override
    public int chunkSize() {
        return kind == SocketSession.Kind.UDP ? FILE_CHUNK_UDP : FILE_CHUNK_TCP;
    }

    @Override
    public void report(String message, boolean error) {
        long now = System.currentTimeMillis();
        log.accept(error ? SocketLogEntry.error(now, message) : SocketLogEntry.info(now, message));
    }

    @Override
    public PayloadPreset capture(String name) {
        return sendBox.toPreset(name);
    }

    @Override
    public void sendPreset(PayloadPreset preset) {
        if (!isReady()) {
            report(SocketUiText.error(SocketError.NOT_CONNECTED), true);
            return;
        }
        try {
            send(PayloadCodec.encode(preset.getContent(), preset.toSpec()));
        } catch (PayloadParseException error) {
            report(preset.getName() + ": " + SocketUiText.parseError(error), true);
        } catch (SocketSendException error) {
            report(SocketUiText.error(error.getError()) + " (" + error.getMessage() + ")", true);
        }
    }
}
