package com.aqishi.toolbox.feature.network.domain;

import java.io.IOException;

/**
 * 建立会话（连接、监听、绑定、加入组播）失败，附带错误类别供界面本地化。
 */
public class SocketOpenException extends IOException {

    private static final long serialVersionUID = 1L;

    private final SocketError error;

    public SocketOpenException(SocketError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public SocketError getError() {
        return error;
    }
}
