package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketEvent;

/**
 * 会话事件回调。在会话自己的网络线程上调用，实现必须快速返回且不得阻塞——
 * 界面实现应只把事件放进队列，由 EDT 定时批量取走。
 */
@FunctionalInterface
public interface SocketSessionListener {

    void onEvent(SocketEvent event);
}
