package com.aqishi.toolbox.feature.network.domain;

/**
 * 会话错误类别。界面按类别本地化，detail 里的英文原始信息仅作补充。
 */
public enum SocketError {
    /** 连接失败（拒绝、超时、DNS 等）。 */
    CONNECT_FAILED,
    /** TLS 握手失败（证书不受信任、主机名不匹配、协议不支持等）。 */
    TLS_FAILED,
    /** 绑定本地地址或端口失败。 */
    BIND_FAILED,
    /** 发送队列已满，对端读取太慢或网络阻塞。 */
    SEND_QUEUE_FULL,
    /** 写出失败。 */
    SEND_FAILED,
    /** 读取失败。 */
    RECEIVE_FAILED,
    /** 会话未连接或已关闭。 */
    NOT_CONNECTED,
    /** 服务端已达最大客户端数，新连接被拒绝。 */
    CLIENT_REJECTED,
    /** 指定的客户端不存在（可能已断开）。 */
    NO_SUCH_CLIENT,
    /** UDP 没有可用的目标地址。 */
    NO_TARGET,
    /** UDP 数据报超过 65507 字节。 */
    DATAGRAM_TOO_LARGE,
    /** 加入或退出组播组失败。 */
    MULTICAST_FAILED,
    /** 设置套接字选项失败。 */
    OPTION_FAILED
}
