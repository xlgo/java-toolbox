package com.aqishi.toolbox.feature.network.domain;

/**
 * 发送被拒绝（队列已满、未连接、数据报过大等）。非受检：发送在界面线程上同步调用，
 * 只做入队，失败原因需要立刻反馈给用户而不是层层声明。
 */
public class SocketSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final SocketError error;

    public SocketSendException(SocketError error, String message) {
        super(message);
        this.error = error;
    }

    public SocketError getError() {
        return error;
    }
}
