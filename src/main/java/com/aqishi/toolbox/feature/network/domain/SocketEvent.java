package com.aqishi.toolbox.feature.network.domain;

import java.net.InetSocketAddress;

/**
 * 会话向界面报告的不可变事件。只携带结构化数据与英文细节，文案由界面按类型本地化。
 */
public final class SocketEvent {

    /** 事件类型。 */
    public enum Type {
        /** TCP 客户端连上 / 服务端开始监听 / UDP 绑定成功。 */
        CONNECTED,
        /** 会话结束（手动关闭、对端断开或出错），reason 给出原因。 */
        DISCONNECTED,
        /** 服务端接入一个客户端。 */
        CLIENT_JOINED,
        /** 服务端的某个客户端断开，reason 给出原因。 */
        CLIENT_LEFT,
        /** 收到数据（TCP 为分帧后的一帧，UDP 为一个数据报）。 */
        RECEIVED,
        /** 数据已写出。 */
        SENT,
        /** 连接断开后准备自动重连，detail 为等待毫秒数。 */
        RECONNECTING,
        /** 出错，error 给出类别。 */
        ERROR
    }

    /** 断开原因。 */
    public enum Reason {
        LOCAL_CLOSE,
        PEER_CLOSED,
        IO_ERROR,
        KICKED,
        SERVER_STOPPED
    }

    private final Type type;
    private final long time;
    private final byte[] data;
    private final InetSocketAddress peer;
    private final int clientId;
    private final SocketError error;
    private final Reason reason;
    private final String detail;

    private SocketEvent(Type type, long time, byte[] data, InetSocketAddress peer, int clientId,
                        SocketError error, Reason reason, String detail) {
        this.type = type;
        this.time = time;
        this.data = data;
        this.peer = peer;
        this.clientId = clientId;
        this.error = error;
        this.reason = reason;
        this.detail = detail;
    }

    public static SocketEvent connected(InetSocketAddress local, InetSocketAddress remote) {
        return new SocketEvent(Type.CONNECTED, now(), null, remote, 0, null, null,
                local == null ? null : format(local));
    }

    public static SocketEvent disconnected(Reason reason, String detail) {
        return new SocketEvent(Type.DISCONNECTED, now(), null, null, 0, null, reason, detail);
    }

    public static SocketEvent clientJoined(int clientId, InetSocketAddress peer) {
        return new SocketEvent(Type.CLIENT_JOINED, now(), null, peer, clientId, null, null, null);
    }

    public static SocketEvent clientLeft(int clientId, InetSocketAddress peer, Reason reason, String detail) {
        return new SocketEvent(Type.CLIENT_LEFT, now(), null, peer, clientId, null, reason, detail);
    }

    public static SocketEvent received(byte[] data, InetSocketAddress peer, int clientId) {
        return new SocketEvent(Type.RECEIVED, now(), data, peer, clientId, null, null, null);
    }

    public static SocketEvent sent(byte[] data, InetSocketAddress peer, int clientId) {
        return new SocketEvent(Type.SENT, now(), data, peer, clientId, null, null, null);
    }

    public static SocketEvent reconnecting(int attempt, long delayMillis) {
        return new SocketEvent(Type.RECONNECTING, now(), null, null, attempt, null, null,
                String.valueOf(delayMillis));
    }

    public static SocketEvent error(SocketError error, InetSocketAddress peer, String detail) {
        return new SocketEvent(Type.ERROR, now(), null, peer, 0, error, null, detail);
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    /** host:port，IPv6 加方括号；不做反向解析，避免在事件线程上阻塞 DNS。 */
    public static String format(InetSocketAddress address) {
        if (address == null) {
            return "";
        }
        String host = address.getAddress() != null
                ? address.getAddress().getHostAddress()
                : address.getHostString();
        if (host.indexOf(':') >= 0) {
            int zone = host.indexOf('%');
            return "[" + (zone > 0 ? host.substring(0, zone) : host) + "]:" + address.getPort();
        }
        return host + ":" + address.getPort();
    }

    public Type getType() {
        return type;
    }

    /** 事件发生的时刻（epoch 毫秒）。 */
    public long getTime() {
        return time;
    }

    /** RECEIVED / SENT 的载荷；其他类型为 null。不复制，调用方勿修改。 */
    public byte[] getData() {
        return data;
    }

    public InetSocketAddress getPeer() {
        return peer;
    }

    /** 服务端客户端编号；RECONNECTING 时为重连次数；其他情况为 0。 */
    public int getClientId() {
        return clientId;
    }

    public SocketError getError() {
        return error;
    }

    public Reason getReason() {
        return reason;
    }

    public String getDetail() {
        return detail;
    }

    @Override
    public String toString() {
        return "SocketEvent{" + type + (peer == null ? "" : " " + format(peer))
                + (clientId == 0 ? "" : " #" + clientId)
                + (data == null ? "" : " " + data.length + "B")
                + (error == null ? "" : " " + error)
                + (reason == null ? "" : " " + reason)
                + (detail == null ? "" : " " + detail) + "}";
    }
}
