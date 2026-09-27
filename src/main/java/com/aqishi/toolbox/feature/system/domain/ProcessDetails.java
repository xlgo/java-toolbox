package com.aqishi.toolbox.feature.system.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * 选中进程的补充信息，按需懒加载。拿不到的字段为空串 / null / {@link PortEntry#NO_PID}。
 *
 * @param pid         进程号
 * @param alive       查询时是否仍在运行
 * @param executable  可执行文件路径（{@code ProcessHandle.Info#command}）
 * @param commandLine 完整命令行
 * @param user        所属用户
 * @param startTime   启动时间，未知为 null
 * @param cpuTime     累计 CPU 时间，未知为 null
 * @param parentPid   父进程号
 */
public record ProcessDetails(long pid, boolean alive, String executable, String commandLine,
                             String user, Instant startTime, Duration cpuTime, long parentPid) {

    public ProcessDetails {
        executable = executable == null ? "" : executable;
        commandLine = commandLine == null ? "" : commandLine;
        user = user == null ? "" : user;
    }
}
