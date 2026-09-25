package com.aqishi.toolbox.feature.monitor.domain;

import java.util.function.Consumer;

/**
 * 远程桌面数据通道接口，屏蔽 TCP 直连、UDP 打洞与 ICE 三种传输的差异。
 * WebSocket 仅用于信令交换，不承载桌面数据，因此不实现本接口。
 */
public interface DesktopChannel {

    /** 发送自定义消息包 */
    void send(DesktopMessage msg);

    /** 关闭通道 */
    void close();

    /** 是否为 P2P 直连通道 */
    boolean isP2P();

    /** 获取延迟等连接信息或状态描述 */
    String getStatusDescription();

    /** 设置收到消息的回调 */
    void setMessageListener(Consumer<DesktopMessage> listener);

    /** 设置通道关闭的回调 */
    void setCloseListener(Runnable listener);
}
