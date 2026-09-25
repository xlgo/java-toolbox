package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 转储中的一个线程。
 *
 * <p>所有可选字段缺失时为 {@code null}（数值字段为 {@code -1}）：不同 JDK 版本、不同来源
 * （jstack / jcmd / kill -3 / JSON）给出的字段并不一致，解析器不猜测缺失值。</p>
 *
 * @param name                  线程名（已去掉外层引号，内部引号原样保留）
 * @param number                {@code #N} 序号，缺失为 -1
 * @param daemon                是否守护线程
 * @param virtual               是否虚拟线程（仅 JSON / JDK 21 文本格式会标出）
 * @param priority              {@code prio=}，缺失为 -1
 * @param osPriority            {@code os_prio=}，缺失为 -1
 * @param cpu                   {@code cpu=} 原文，如 {@code 12.34ms}
 * @param elapsed               {@code elapsed=} 原文，如 {@code 10.61s}
 * @param tid                   {@code tid=} 原文（JSON 格式里是 Java 线程 ID）
 * @param nid                   {@code nid=} 原文（JDK 19 之前为十六进制，之后为十进制）
 * @param headerState           头部 nid 之后的状态描述，如 {@code waiting for monitor entry}
 * @param state                 {@code java.lang.Thread.State:} 解析出的状态
 * @param stateDetail           状态括号内的补充，如 {@code on object monitor}、{@code parking}
 * @param frames                栈帧，自顶向下
 * @param ownableSynchronizers  {@code Locked ownable synchronizers} 小节里持有的同步器
 * @param javaThread            是否 Java 线程；GC / VM Thread 等 JVM 内部线程为 false
 */
public record ThreadInfo(
        String name,
        long number,
        boolean daemon,
        boolean virtual,
        int priority,
        int osPriority,
        String cpu,
        String elapsed,
        String tid,
        String nid,
        String headerState,
        ThreadState state,
        String stateDetail,
        List<Frame> frames,
        List<Lock> ownableSynchronizers,
        boolean javaThread) {

    public ThreadInfo {
        name = name == null ? "" : name;
        state = state == null ? ThreadState.NONE : state;
        frames = List.copyOf(frames == null ? List.of() : frames);
        ownableSynchronizers = List.copyOf(ownableSynchronizers == null ? List.of() : ownableSynchronizers);
    }

    /** 锁行的种类，对应 HotSpot 栈帧下方的 {@code - xxx <addr>} 行。 */
    public enum LockKind {
        /** {@code - locked <addr>}：该帧持有此监视器 */
        LOCKED,
        /** {@code - waiting to lock <addr>}：进入 synchronized 被阻塞 */
        WAITING_TO_LOCK,
        /** {@code - waiting to re-lock in wait() <addr>}：wait() 返回后重新抢监视器 */
        WAITING_TO_RELOCK,
        /** {@code - waiting on <addr>}：在 Object.wait() 中，监视器已释放 */
        WAITING_ON,
        /** {@code - parking to wait for <addr>}：LockSupport.park 的 blocker 对象 */
        PARKING,
        /** {@code - eliminated <addr>}：被逃逸分析消除但逻辑上仍持有的锁 */
        ELIMINATED,
        /** {@code Locked ownable synchronizers} 小节里的条目（ReentrantLock 等） */
        OWNABLE
    }

    /**
     * 一条锁记录。
     *
     * @param kind      种类
     * @param address   规范化后的地址（小写、去前导零），无地址时为 {@code null}
     * @param className 锁对象类名，缺失时为 {@code null}
     */
    public record Lock(LockKind kind, String address, String className) {
        public Lock {
            Objects.requireNonNull(kind, "kind");
        }

        /** 是否是「当前被卡住在这把锁上」的那类记录。 */
        public boolean isBlocking() {
            return kind == LockKind.WAITING_TO_LOCK
                    || kind == LockKind.WAITING_TO_RELOCK
                    || kind == LockKind.PARKING;
        }
    }

    /**
     * 一个栈帧。
     *
     * @param text  {@code at } 之后的原文，如 {@code java.lang.Thread.run(Thread.java:833)}
     * @param locks 紧跟在该帧下方的锁记录
     */
    public record Frame(String text, List<Lock> locks) {

        /** 模块 / 类加载器前缀，如 JSON 格式里的 {@code java.base/}、{@code app//}。 */
        private static final Pattern MODULE_PREFIX =
                Pattern.compile("^[A-Za-z_][\\w.\\-]*(?:@[\\w.+\\-]*)?//?(?=[A-Za-z_$])|^//?");

        public Frame {
            text = text == null ? "" : text.trim();
            locks = List.copyOf(locks == null ? List.of() : locks);
        }

        /**
         * 去掉源码位置与模块前缀后的全限定方法名，用于聚合热点方法与空闲判定。
         *
         * <p>前缀里带 {@code $} 的不剥离：{@code Foo$$Lambda$2/0x0000.run} 里的斜杠是
         * 隐藏类名的一部分，不是模块分隔符。</p>
         */
        public String methodName() {
            int paren = text.indexOf('(');
            String method = paren >= 0 ? text.substring(0, paren) : text;
            int slash = method.indexOf('/');
            if (slash >= 0 && method.substring(0, slash).indexOf('$') < 0) {
                Matcher matcher = MODULE_PREFIX.matcher(method);
                if (matcher.find()) {
                    method = method.substring(matcher.end());
                }
            }
            return method.trim();
        }
    }

    /** 栈顶帧，没有栈时为 {@code null}。 */
    public Frame topFrame() {
        return frames.isEmpty() ? null : frames.get(0);
    }

    /** 线程当前被卡住的那把锁（进入 synchronized、wait 后重入、park），没有则为 {@code null}。 */
    public Lock blockedOn() {
        for (Frame frame : frames) {
            for (Lock lock : frame.locks()) {
                if (lock.isBlocking() && lock.address() != null) {
                    return lock;
                }
            }
        }
        return null;
    }

    /**
     * 线程真正持有的锁地址。
     *
     * <p>Object.wait() 的调用方帧下仍会打印 {@code - locked <X>}，但监视器此时已释放；
     * 若不剔除，wait 中的线程会被误判为锁的持有者，进而制造出假死锁。</p>
     */
    public Set<String> heldLockAddresses() {
        Set<String> releasedByWait = new LinkedHashSet<>();
        for (Frame frame : frames) {
            for (Lock lock : frame.locks()) {
                if (lock.kind() == LockKind.WAITING_ON && lock.address() != null) {
                    releasedByWait.add(lock.address());
                }
            }
        }
        Set<String> held = new LinkedHashSet<>();
        for (Frame frame : frames) {
            for (Lock lock : frame.locks()) {
                if ((lock.kind() == LockKind.LOCKED || lock.kind() == LockKind.ELIMINATED)
                        && lock.address() != null && !releasedByWait.contains(lock.address())) {
                    held.add(lock.address());
                }
            }
        }
        for (Lock lock : ownableSynchronizers) {
            if (lock.address() != null) {
                held.add(lock.address());
            }
        }
        return held;
    }

    /** 所有锁记录（栈帧上的 + 可拥有同步器），按出现顺序。 */
    public List<Lock> allLocks() {
        List<Lock> all = new ArrayList<>();
        for (Frame frame : frames) {
            all.addAll(frame.locks());
        }
        all.addAll(ownableSynchronizers);
        return all;
    }

    /** 跨转储比对用的身份键：同名线程可能不止一个，带上 nid（没有则 tid）区分。 */
    public String identityKey() {
        String osId = nid != null ? nid : tid;
        return name + '\u0000' + (osId == null ? "" : osId);
    }
}
