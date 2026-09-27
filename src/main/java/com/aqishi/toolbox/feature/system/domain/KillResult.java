package com.aqishi.toolbox.feature.system.domain;

import java.util.Objects;

/**
 * 一次结束进程的结果。
 *
 * @param pid     目标进程
 * @param outcome 结果类别
 * @param refusal 被护栏拒绝时的原因，其他情况为 null
 * @param forced  是否使用了强制方式（SIGKILL / {@code taskkill /F}）
 * @param detail  命令原始输出，便于排查；可能是系统语言的文字，界面只作补充展示
 */
public record KillResult(long pid, Outcome outcome, ProcessKillPolicy.Refusal refusal,
                         boolean forced, String detail) {

    public enum Outcome {
        /** 进程已退出。 */
        EXITED,
        /** 请求已发出，但等待期限内进程仍在运行（忽略 SIGTERM，或控制台程序不响应 WM_CLOSE）。 */
        STILL_RUNNING,
        /** 进程本来就不存在。 */
        NOT_FOUND,
        /** 权限不足：需要管理员 / sudo。 */
        ACCESS_DENIED,
        /** 被 {@link ProcessKillPolicy} 拒绝。 */
        REFUSED,
        /** 其他失败。 */
        FAILED
    }

    public KillResult {
        Objects.requireNonNull(outcome, "outcome");
        detail = detail == null ? "" : detail;
    }

    public static KillResult refused(long pid, ProcessKillPolicy.Refusal refusal) {
        return new KillResult(pid, Outcome.REFUSED, Objects.requireNonNull(refusal, "refusal"), false, "");
    }

    /** 目标已不在运行（包括本来就不存在）。 */
    public boolean gone() {
        return outcome == Outcome.EXITED || outcome == Outcome.NOT_FOUND;
    }
}
