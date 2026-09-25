package com.aqishi.toolbox.feature.system.domain;

import java.util.Locale;

/**
 * 线程转储中的线程状态。
 *
 * <p>前六个与 {@link Thread.State} 一一对应；{@link #NONE} 表示转储里没有
 * {@code java.lang.Thread.State:} 行——GC、VM Thread 这类 JVM 内部线程就是这样，
 * 它们不是 Java 线程，头部的 {@code runnable} 只是 OS 层面的描述，不能当成 RUNNABLE 统计。</p>
 */
public enum ThreadState {
    NEW,
    RUNNABLE,
    BLOCKED,
    WAITING,
    TIMED_WAITING,
    TERMINATED,
    NONE;

    /** 把转储里的状态文本转成枚举，无法识别时返回 {@link #NONE}。 */
    public static ThreadState parse(String text) {
        if (text == null) {
            return NONE;
        }
        String normalized = text.trim().toUpperCase(Locale.ROOT);
        for (ThreadState state : values()) {
            if (state != NONE && state.name().equals(normalized)) {
                return state;
            }
        }
        return NONE;
    }
}
