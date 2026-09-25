package com.aqishi.toolbox.feature.system.domain;

import java.util.List;

/**
 * 一次完整的线程转储。
 *
 * @param index        在输入中的序号，从 0 开始
 * @param timestamp    转储前的时间戳行，没有则为 {@code null}
 * @param vmInfo       {@code Full thread dump} 之后的 JVM 描述，没有则为 {@code null}
 * @param threads      线程，按转储中的顺序
 * @param jvmDeadlocks JVM 自己在 {@code Found one Java-level deadlock:} 小节报告的死锁
 */
public record ThreadDump(
        int index,
        String timestamp,
        String vmInfo,
        List<ThreadInfo> threads,
        List<JvmDeadlock> jvmDeadlocks) {

    public ThreadDump {
        threads = List.copyOf(threads == null ? List.of() : threads);
        jvmDeadlocks = List.copyOf(jvmDeadlocks == null ? List.of() : jvmDeadlocks);
    }

    /**
     * JVM 报告的一个死锁环。
     *
     * @param links 环上的每一条「线程 → 等待的锁 → 持有者」
     */
    public record JvmDeadlock(List<Link> links) {
        public JvmDeadlock {
            links = List.copyOf(links == null ? List.of() : links);
        }
    }

    /**
     * 死锁环上的一条边。
     *
     * @param threadName  等待中的线程
     * @param lockAddress 等待的锁（对象地址，已规范化），缺失为 {@code null}
     * @param lockClass   锁对象类名，缺失为 {@code null}
     * @param waitKind    {@link ThreadInfo.LockKind#WAITING_TO_LOCK}（监视器）或
     *                    {@link ThreadInfo.LockKind#PARKING}（可拥有同步器）
     * @param ownerName   持有该锁的线程名，JVM 无法给出时为 {@code null}
     */
    public record Link(String threadName, String lockAddress, String lockClass,
                       ThreadInfo.LockKind waitKind, String ownerName) {
    }
}
