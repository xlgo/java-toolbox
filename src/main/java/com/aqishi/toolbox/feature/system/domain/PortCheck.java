package com.aqishi.toolbox.feature.system.domain;

import java.util.Objects;

/**
 * 「端口是否空闲」的检查结果。
 *
 * @param port   端口
 * @param host   实际尝试绑定的地址
 * @param status 结果
 * @param detail 绑定失败时 JDK 的原始异常描述
 */
public record PortCheck(int port, String host, Status status, String detail) {

    public enum Status {
        /** 能绑定，端口空闲。 */
        FREE,
        /** 已被占用。 */
        IN_USE,
        /** 无权绑定：Unix 下非 root 绑 1024 以下端口，或 Windows 保留/排除的端口段。 */
        DENIED,
        /** 地址无效（本机没有这个地址等）。 */
        INVALID_HOST
    }

    public PortCheck {
        Objects.requireNonNull(status, "status");
        host = host == null ? "" : host;
        detail = detail == null ? "" : detail;
    }
}
