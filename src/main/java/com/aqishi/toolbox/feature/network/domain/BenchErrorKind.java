package com.aqishi.toolbox.feature.network.domain;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;

/**
 * 压测中一次请求失败的类别。
 *
 * <p>分类只看异常类型与 cause 链，不解析异常文案——不同 JDK 版本、不同操作系统下
 * {@code HttpClient} 抛出的消息差别很大，类型才是稳定的。</p>
 */
public enum BenchErrorKind {
    /** 目标端口没有监听（RST）。 */
    CONNECT_REFUSED,
    /** 建立 TCP 连接超过了连接超时。 */
    CONNECT_TIMEOUT,
    /** 连接已建立，但整个请求超过了请求超时。 */
    REQUEST_TIMEOUT,
    /** TLS 握手或证书校验失败。 */
    TLS_ERROR,
    /** 域名无法解析。 */
    DNS_FAILURE,
    /** 连接被重置、提前关闭等其它 I/O 故障。 */
    IO_ERROR,
    /** 状态码在期望范围之外（且不是 4xx/5xx），或响应体不包含期望文本。 */
    ASSERTION_FAILED,
    /** 4xx 响应且不在期望范围内。 */
    HTTP_4XX,
    /** 5xx 响应且不在期望范围内。 */
    HTTP_5XX,
    /** 无法归类的异常。 */
    OTHER;

    /**
     * 按异常归类。先找最能说明问题的类型：DNS 与 TLS 失败经常被包在
     * {@link ConnectException} 或普通 {@link IOException} 里，必须沿 cause 链先找它们。
     */
    public static BenchErrorKind fromThrowable(Throwable error) {
        if (error == null) {
            return OTHER;
        }
        if (hasCause(error, UnresolvedAddressException.class) || hasCause(error, UnknownHostException.class)) {
            return DNS_FAILURE;
        }
        if (hasCause(error, SSLException.class)) {
            return TLS_ERROR;
        }
        if (hasCause(error, HttpConnectTimeoutException.class)) {
            return CONNECT_TIMEOUT;
        }
        if (hasCause(error, HttpTimeoutException.class)) {
            return REQUEST_TIMEOUT;
        }
        if (hasCause(error, java.net.SocketTimeoutException.class)) {
            return REQUEST_TIMEOUT;
        }
        if (hasCause(error, ConnectException.class) || hasCause(error, NoRouteToHostException.class)) {
            return CONNECT_REFUSED;
        }
        if (hasCause(error, IOException.class)) {
            return IO_ERROR;
        }
        return OTHER;
    }

    /**
     * 按状态码归类一个不符合期望的响应。
     */
    public static BenchErrorKind fromUnexpectedStatus(int status) {
        if (status >= 400 && status < 500) {
            return HTTP_4XX;
        }
        if (status >= 500 && status < 600) {
            return HTTP_5XX;
        }
        return ASSERTION_FAILED;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable cursor = error;
        for (int depth = 0; cursor != null && depth < 16; depth++) {
            if (type.isInstance(cursor)) {
                return true;
            }
            if (cursor.getCause() == cursor) {
                return false;
            }
            cursor = cursor.getCause();
        }
        return false;
    }
}
