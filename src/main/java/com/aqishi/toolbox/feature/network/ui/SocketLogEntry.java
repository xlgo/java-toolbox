package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.PayloadDisplayMode;
import com.aqishi.toolbox.feature.network.domain.PayloadRenderer;

import java.nio.charset.Charset;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 收发日志中的一条记录。保存原始字节而非渲染结果：切换显示模式或字符集时可以整体重绘。
 */
final class SocketLogEntry {

    enum Kind {
        SENT,
        RECEIVED,
        INFO,
        ERROR
    }

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    final long time;
    final Kind kind;
    final String peer;
    final byte[] data;
    final String text;

    private SocketLogEntry(long time, Kind kind, String peer, byte[] data, String text) {
        this.time = time;
        this.kind = kind;
        this.peer = peer;
        this.data = data;
        this.text = text;
    }

    static SocketLogEntry payload(long time, boolean sent, String peer, byte[] data) {
        return new SocketLogEntry(time, sent ? Kind.SENT : Kind.RECEIVED, peer, data, null);
    }

    static SocketLogEntry info(long time, String text) {
        return new SocketLogEntry(time, Kind.INFO, null, null, text);
    }

    static SocketLogEntry error(long time, String text) {
        return new SocketLogEntry(time, Kind.ERROR, null, null, text);
    }

    /** 渲染成一段以换行结尾的文本。 */
    String render(boolean showTime, PayloadDisplayMode mode, Charset charset, boolean hexDump) {
        StringBuilder out = new StringBuilder(64 + (data == null ? 0 : data.length * 3));
        if (showTime) {
            out.append('[').append(TIME.format(Instant.ofEpochMilli(time))).append("] ");
        }
        switch (kind) {
            case SENT:
            case RECEIVED:
                out.append(kind == Kind.SENT ? "→ " : "← ");
                if (peer != null && !peer.isEmpty()) {
                    out.append(peer).append(' ');
                }
                out.append('(').append(data.length).append("B) ");
                String body = PayloadRenderer.render(data, mode, charset,
                        PayloadRenderer.ControlStyle.ESCAPE, hexDump);
                if (hexDump && mode != PayloadDisplayMode.TEXT && data.length > 0) {
                    out.append('\n');
                }
                out.append(body);
                break;
            case ERROR:
                out.append("! ").append(text);
                break;
            default:
                out.append("* ").append(text);
                break;
        }
        return out.append('\n').toString();
    }
}
