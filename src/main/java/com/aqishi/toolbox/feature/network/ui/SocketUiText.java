package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.PayloadChecksum;
import com.aqishi.toolbox.feature.network.domain.PayloadLineEnding;
import com.aqishi.toolbox.feature.network.domain.PayloadParseException;
import com.aqishi.toolbox.feature.network.domain.SocketError;
import com.aqishi.toolbox.feature.network.domain.SocketEvent;
import com.aqishi.toolbox.util.I18n;

/**
 * 把会话层的结构化结果翻译成界面文案。全部用字面量键，i18n 资源检查能逐个核对。
 */
final class SocketUiText {

    private SocketUiText() {
    }

    static String error(SocketError error) {
        if (error == null) {
            return I18n.get("tool.socketdebug.error.unknown");
        }
        switch (error) {
            case CONNECT_FAILED: return I18n.get("tool.socketdebug.error.connectFailed");
            case TLS_FAILED: return I18n.get("tool.socketdebug.error.tlsFailed");
            case BIND_FAILED: return I18n.get("tool.socketdebug.error.bindFailed");
            case SEND_QUEUE_FULL: return I18n.get("tool.socketdebug.error.queueFull");
            case SEND_FAILED: return I18n.get("tool.socketdebug.error.sendFailed");
            case RECEIVE_FAILED: return I18n.get("tool.socketdebug.error.receiveFailed");
            case NOT_CONNECTED: return I18n.get("tool.socketdebug.error.notConnected");
            case CLIENT_REJECTED: return I18n.get("tool.socketdebug.error.clientRejected");
            case NO_SUCH_CLIENT: return I18n.get("tool.socketdebug.error.noSuchClient");
            case NO_TARGET: return I18n.get("tool.socketdebug.error.noTarget");
            case DATAGRAM_TOO_LARGE: return I18n.get("tool.socketdebug.error.datagramTooLarge");
            case MULTICAST_FAILED: return I18n.get("tool.socketdebug.error.multicastFailed");
            case OPTION_FAILED: return I18n.get("tool.socketdebug.error.optionFailed");
            default: return error.name();
        }
    }

    static String reason(SocketEvent.Reason reason) {
        if (reason == null) {
            return "";
        }
        switch (reason) {
            case LOCAL_CLOSE: return I18n.get("tool.socketdebug.reason.localClose");
            case PEER_CLOSED: return I18n.get("tool.socketdebug.reason.peerClosed");
            case IO_ERROR: return I18n.get("tool.socketdebug.reason.ioError");
            case KICKED: return I18n.get("tool.socketdebug.reason.kicked");
            case SERVER_STOPPED: return I18n.get("tool.socketdebug.reason.serverStopped");
            default: return reason.name();
        }
    }

    /** 解析错误：类别 + 从 1 开始的位置（用户习惯的列号）。 */
    static String parseError(PayloadParseException error) {
        String position = String.valueOf(error.getPosition() + 1);
        switch (error.getCode()) {
            case INVALID_HEX_CHAR: return I18n.get("tool.socketdebug.parse.invalidHex", position);
            case ODD_HEX_DIGITS: return I18n.get("tool.socketdebug.parse.oddHex", position);
            case INVALID_ESCAPE: return I18n.get("tool.socketdebug.parse.invalidEscape", position);
            default: return error.getMessage();
        }
    }

    static String lineEnding(PayloadLineEnding ending) {
        switch (ending) {
            case CRLF: return "CRLF (\\r\\n)";
            case LF: return "LF (\\n)";
            case CR: return "CR (\\r)";
            default: return I18n.get("tool.socketdebug.send.lineEnding.none");
        }
    }

    static String checksum(PayloadChecksum checksum) {
        switch (checksum) {
            case SUM8: return "SUM8";
            case XOR8: return "XOR8";
            case CRC8: return "CRC-8";
            case CRC16_MODBUS: return "CRC-16/MODBUS";
            case CRC16_CCITT_FALSE: return "CRC-16/CCITT-FALSE";
            case CRC32: return "CRC-32";
            default: return I18n.get("tool.socketdebug.send.checksum.none");
        }
    }

    /** 日志里的对端标识：服务端模式带客户端编号。 */
    static String peer(SocketEvent event) {
        String address = SocketEvent.format(event.getPeer());
        return event.getClientId() > 0 ? "#" + event.getClientId() + " " + address : address;
    }

    /** 连接状态类事件的一行说明。 */
    static String describe(SocketEvent event) {
        String detail = event.getDetail() == null ? "" : event.getDetail();
        String peer = SocketEvent.format(event.getPeer());
        switch (event.getType()) {
            case CONNECTED:
                return event.getPeer() == null
                        ? I18n.get("tool.socketdebug.event.opened", detail)
                        : I18n.get("tool.socketdebug.event.connected", peer, detail);
            case DISCONNECTED:
                return I18n.get("tool.socketdebug.event.disconnected", reason(event.getReason()), detail);
            case CLIENT_JOINED:
                return I18n.get("tool.socketdebug.event.clientJoined", String.valueOf(event.getClientId()), peer);
            case CLIENT_LEFT:
                return I18n.get("tool.socketdebug.event.clientLeft", String.valueOf(event.getClientId()), peer,
                        reason(event.getReason()));
            case RECONNECTING:
                return I18n.get("tool.socketdebug.event.reconnecting", detail, String.valueOf(event.getClientId()));
            case ERROR:
                String text = error(event.getError());
                if (event.getPeer() != null) {
                    text += " [" + peer + "]";
                }
                return detail.isEmpty() ? text : text + ": " + detail;
            default:
                return event.toString();
        }
    }

    /** 字节数的紧凑显示：1.2 KB / 3.4 MB。 */
    static String bytes(long value) {
        if (value < 1024) {
            return value + " B";
        }
        if (value < 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", value / 1024.0);
        }
        if (value < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", value / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ROOT, "%.2f GB", value / (1024.0 * 1024 * 1024));
    }
}
