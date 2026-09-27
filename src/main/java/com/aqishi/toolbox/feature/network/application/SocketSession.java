package com.aqishi.toolbox.feature.network.application;

import com.aqishi.toolbox.feature.network.domain.SocketStats;

import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * 一个 TCP 客户端、TCP 服务端或 UDP 会话。实例一次性使用：{@link #open()} 一次，{@link #close()} 后作废。
 *
 * <p>所有网络 I/O 都在会话自己的守护线程上进行（线程名以 {@link #THREAD_PREFIX} 开头）；
 * {@link #send(byte[])} 只入队，不会阻塞调用线程。</p>
 */
public interface SocketSession extends AutoCloseable {

    /** 会话线程名前缀，便于线程转储定位与测试检查线程是否释放。 */
    String THREAD_PREFIX = "socket-debug";

    /** 每个连接默认的发送队列长度（条）。 */
    int DEFAULT_QUEUE_CAPACITY = 1024;

    /** 会话类型。 */
    enum Kind {
        TCP_CLIENT,
        TCP_SERVER,
        UDP
    }

    Kind kind();

    /**
     * 建立会话：连接 / 开始监听 / 绑定。会阻塞直到成功或失败，必须在后台线程调用。
     *
     * @throws com.aqishi.toolbox.feature.network.domain.SocketOpenException 失败时，附带错误类别
     */
    void open() throws IOException;

    /** 当前是否可发送（已连接 / 正在监听 / 已绑定）。 */
    boolean isOpen();

    /** 会话是否已终止（手动关闭，或断开且不会自动重连）。 */
    boolean isClosed();

    /**
     * 入队发送。TCP 客户端发给服务端；服务端发给全部客户端；UDP 发给默认目标或最近发送方。
     *
     * @throws com.aqishi.toolbox.feature.network.domain.SocketSendException 队列满、未连接等
     */
    void send(byte[] data);

    /**
     * 与 {@link #send(byte[])} 相同，但队列满时最多等待 timeoutMillis 毫秒——用于发送文件这类
     * 需要背压、又不能丢数据的场景。只能在后台线程调用。
     */
    void sendAwait(byte[] data, long timeoutMillis) throws InterruptedException;

    /** 实际绑定的本地地址；未打开时为 null。 */
    InetSocketAddress localAddress();

    SocketStats stats();

    /** 关闭会话并释放全部线程与套接字；可重复调用。 */
    @Override
    void close();
}
