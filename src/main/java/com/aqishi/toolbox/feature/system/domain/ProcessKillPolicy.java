package com.aqishi.toolbox.feature.system.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 结束进程前的领域级护栏。
 *
 * <p>界面上的确认框挡不住手滑，这里再拒绝几类「结束了就要重启机器」的目标：
 * Windows 的 PID 0（System Idle）与 4（System）及其间的保留号、Unix 的 init（PID 1），
 * 以及工具箱自己的 JVM。Unix 上 {@code kill 0} / {@code kill -1} 会把信号发给整个进程组乃至
 * 所有进程，非正数 PID 一律视为非法。</p>
 */
public final class ProcessKillPolicy {

    public enum Refusal {
        /** PID 非法（非正数）。 */
        INVALID_PID,
        /** 操作系统核心进程。 */
        SYSTEM_PROCESS,
        /** 工具箱自身。 */
        SELF
    }

    private ProcessKillPolicy() {
    }

    public static Optional<Refusal> check(long pid, OsFamily os, long selfPid) {
        Objects.requireNonNull(os, "os");
        if (pid < 0 || (pid == 0 && !os.isWindows())) {
            return Optional.of(Refusal.INVALID_PID);
        }
        if (os.isWindows() ? pid <= 4 : pid == 1) {
            return Optional.of(Refusal.SYSTEM_PROCESS);
        }
        if (pid == selfPid) {
            return Optional.of(Refusal.SELF);
        }
        return Optional.empty();
    }
}
