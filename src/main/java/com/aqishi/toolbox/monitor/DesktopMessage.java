package com.aqishi.toolbox.monitor;

/**
 * 远程桌面自定义二进制通信消息包。
 */
public class DesktopMessage {

    // 消息类型定义
    public static final byte TYPE_SCREEN_FRAME = 0x01;    // 屏幕截图 JPEG 帧
    public static final byte TYPE_CONTROL_EVENT = 0x02;   // 键鼠控制事件 JSON
    public static final byte TYPE_CMD_REQUEST = 0x03;     // 执行命令行请求 JSON
    public static final byte TYPE_CMD_RESPONSE = 0x04;    // 命令行输出回显
    public static final byte TYPE_FILE_TRANSFER = 0x05;   // 文件传输控制与数据块
    public static final byte TYPE_DRAWING = 0x06;         // 远程画板标注数据 JSON
    public static final byte TYPE_HEARTBEAT = 0x07;       // 心跳包
    public static final byte TYPE_P2P_SIGNAL = 0x08;      // P2P 协商握手

    private final byte type;
    private final byte[] payload;

    public DesktopMessage(byte type, byte[] payload) {
        this.type = type;
        this.payload = payload == null ? new byte[0] : payload;
    }

    public byte getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }
}
