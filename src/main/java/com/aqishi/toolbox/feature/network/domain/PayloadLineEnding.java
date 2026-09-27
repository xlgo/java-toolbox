package com.aqishi.toolbox.feature.network.domain;

/**
 * 文本发送时追加的行尾。很多设备协议以 CRLF 结束一条指令，手敲又敲不出来，所以单独做成选项。
 */
public enum PayloadLineEnding {
    NONE(new byte[0]),
    CRLF(new byte[]{'\r', '\n'}),
    LF(new byte[]{'\n'}),
    CR(new byte[]{'\r'});

    private final byte[] bytes;

    PayloadLineEnding(byte[] bytes) {
        this.bytes = bytes;
    }

    /** 行尾字节，每次返回副本，调用方可随意修改。 */
    public byte[] bytes() {
        return bytes.clone();
    }
}
