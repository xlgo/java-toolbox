package com.aqishi.toolbox.feature.system.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * 一条套接字记录：协议、本地/远端端点、连接状态，以及占用它的进程。
 *
 * <p>各平台命令的输出格式差异很大，解析器统一归一到这里：状态用 {@link #STATE_LISTEN} 这类
 * 与平台无关的大写名（Windows 的 {@code LISTENING}、ss 的 {@code ESTAB} 都会被换算），
 * UDP 未连接套接字的状态为空串；端口未知或为通配（{@code *}）时记为 {@link #NO_PORT}；
 * 拿不到进程信息（非管理员运行 ss/netstat）时 {@code pid} 为 {@link #NO_PID}。</p>
 *
 * @param protocol      TCP 或 UDP
 * @param localAddress  本地地址，不带方括号；可能带 {@code %网卡} 后缀，通配为 {@code *}
 * @param localPort     本地端口
 * @param remoteAddress 远端地址；未连接时为 {@code *} 或 {@code 0.0.0.0}
 * @param remotePort    远端端口，未连接时为 {@link #NO_PORT}
 * @param state         归一化后的连接状态；UDP 未连接时为空串
 * @param pid           进程号，未知为 {@link #NO_PID}
 * @param processName   进程名，未知为空串
 * @param commandLine   完整命令行，未查询时为空串
 */
public record PortEntry(Protocol protocol, String localAddress, int localPort,
                        String remoteAddress, int remotePort, String state,
                        long pid, String processName, String commandLine) {

    public static final int NO_PORT = -1;
    public static final long NO_PID = -1L;

    public static final String STATE_LISTEN = "LISTEN";
    public static final String STATE_ESTABLISHED = "ESTABLISHED";

    public enum Protocol {
        TCP, UDP
    }

    public PortEntry {
        Objects.requireNonNull(protocol, "protocol");
        localAddress = localAddress == null ? "" : localAddress;
        remoteAddress = remoteAddress == null ? "" : remoteAddress;
        state = state == null ? "" : state;
        processName = processName == null ? "" : processName;
        commandLine = commandLine == null ? "" : commandLine;
    }

    public boolean hasPid() {
        return pid >= 0;
    }

    /**
     * 是否处于「对外提供服务」的状态。
     *
     * <p>TCP 看 LISTEN；UDP 没有监听的概念，未连接（远端为通配）的套接字就是在等数据报，
     * 与 {@code ss -l}、{@code netstat -l} 的口径一致。</p>
     */
    public boolean isListening() {
        if (protocol == Protocol.TCP) {
            return STATE_LISTEN.equals(state);
        }
        return remotePort <= 0 && !STATE_ESTABLISHED.equals(state);
    }

    public boolean isIpv6() {
        return localAddress.indexOf(':') >= 0;
    }

    /** 本地端点的展示形式：IPv6 加方括号，与各平台命令的写法一致。 */
    public String localEndpoint() {
        return endpoint(localAddress, localPort);
    }

    /** 远端端点的展示形式；未连接显示为 {@code *:*}。 */
    public String remoteEndpoint() {
        if (remoteAddress.isEmpty() && remotePort <= 0) {
            return "";
        }
        return endpoint(remoteAddress.isEmpty() ? "*" : remoteAddress, remotePort);
    }

    public PortEntry withProcessName(String name) {
        return new PortEntry(protocol, localAddress, localPort, remoteAddress, remotePort, state,
                pid, name, commandLine);
    }

    public PortEntry withCommandLine(String line) {
        return new PortEntry(protocol, localAddress, localPort, remoteAddress, remotePort, state,
                pid, processName, line);
    }

    /**
     * 用来在刷新前后找回「同一条」记录的标识：同一个套接字的端点、协议和进程都不会变，
     * 状态与进程名会变（例如 ESTABLISHED 变 TIME_WAIT），因此不参与比较。
     */
    public String identity() {
        return protocol + "|" + localEndpoint() + "|" + remoteEndpoint() + "|" + pid;
    }

    /** 文本过滤用的小写索引：进程名、PID、地址、端口、状态、命令行。 */
    public String searchText() {
        return (processName + ' ' + (hasPid() ? pid : "") + ' ' + localEndpoint() + ' '
                + remoteEndpoint() + ' ' + state + ' ' + protocol + ' ' + commandLine)
                .toLowerCase(Locale.ROOT);
    }

    static String endpoint(String address, int port) {
        String host = address.indexOf(':') >= 0 ? "[" + address + "]" : address;
        return host + ":" + (port < 0 ? "*" : String.valueOf(port));
    }
}
