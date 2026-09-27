package com.aqishi.toolbox.feature.network.domain;

/**
 * 收发日志里载荷的呈现方式。BOTH 同时给出文本与十六进制，排查"看着一样其实差一个字节"最有用。
 */
public enum PayloadDisplayMode {
    TEXT,
    HEX,
    BOTH
}
