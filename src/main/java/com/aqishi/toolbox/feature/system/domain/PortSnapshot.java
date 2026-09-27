package com.aqishi.toolbox.feature.system.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 一次端口扫描的结果。
 *
 * @param entries    记录，已排序
 * @param source     实际使用的数据来源
 * @param limitation 数据是否因权限而不完整
 * @param takenAt    采集时间
 */
public record PortSnapshot(List<PortEntry> entries, Source source, Limitation limitation, Instant takenAt) {

    /** 数据来源（对应的系统命令）。 */
    public enum Source {
        WINDOWS_NETSTAT, SS, LSOF, LINUX_NETSTAT
    }

    /** 权限导致的数据缺口，界面据此提示「以管理员 / sudo 运行」。 */
    public enum Limitation {
        NONE,
        /** 部分记录没有进程信息（非 root 运行 ss / netstat）。 */
        MISSING_PROCESS_INFO,
        /** 非 root 运行 lsof 时只能看到自己用户的进程。 */
        OWN_PROCESSES_ONLY
    }

    public PortSnapshot {
        entries = List.copyOf(entries);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limitation, "limitation");
        Objects.requireNonNull(takenAt, "takenAt");
    }

    public boolean isPartial() {
        return limitation != Limitation.NONE;
    }
}
