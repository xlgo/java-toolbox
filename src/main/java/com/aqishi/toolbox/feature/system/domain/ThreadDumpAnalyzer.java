package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * 线程转储分析：状态统计、死锁检测、锁竞争、同栈分组、线程池归并、热点方法与多次转储对比。
 *
 * <p>纯计算、无状态，可在后台线程中调用。结果只含结构化数据，文案由界面层本地化。</p>
 */
public final class ThreadDumpAnalyzer {

    /** 同栈分组时比较全部栈帧。 */
    public static final int ALL_FRAMES = 0;

    /**
     * 「空闲」栈顶方法：线程虽然处于 RUNNABLE（或在等待），但实际上是在等 IO / 事件 / 任务，
     * 不消耗 CPU。统计热点方法时排除它们，否则排名第一的永远是 epoll 和 socket accept。
     *
     * <p>按全限定方法名精确匹配（不含源码位置与模块前缀）。覆盖 Linux / macOS / Windows 的
     * NIO 选择器、阻塞 socket 读与 accept、LockSupport.park、Object.wait、Thread.sleep、
     * 引用处理线程与进程等待。</p>
     */
    public static final List<String> IDLE_TOP_METHODS = List.of(
            // NIO 选择器
            "sun.nio.ch.EPoll.wait",
            "sun.nio.ch.EPoll.epollWait",
            "sun.nio.ch.EPollArrayWrapper.epollWait",
            "sun.nio.ch.KQueue.poll",
            "sun.nio.ch.KQueue.keventPoll",
            "sun.nio.ch.KQueueArrayWrapper.kevent0",
            "sun.nio.ch.WEPoll.wait",
            "sun.nio.ch.WindowsSelectorImpl$SubSelector.poll0",
            "sun.nio.ch.PollSelectorImpl.poll",
            "sun.nio.ch.Net.poll",
            "sun.nio.ch.Iocp.getQueuedCompletionStatus",
            // 阻塞 socket 读 / accept
            "java.net.SocketInputStream.socketRead0",
            "sun.nio.ch.SocketDispatcher.read0",
            "sun.nio.ch.NioSocketImpl.read",
            "java.net.PlainSocketImpl.socketAccept",
            "java.net.PlainSocketImpl.accept0",
            "java.net.DualStackPlainSocketImpl.accept0",
            "java.net.TwoStacksPlainSocketImpl.socketAccept",
            "sun.nio.ch.Net.accept",
            "sun.nio.ch.ServerSocketChannelImpl.accept0",
            // 线程协作
            "jdk.internal.misc.Unsafe.park",
            "sun.misc.Unsafe.park",
            "java.lang.Object.wait",
            "java.lang.Object.wait0",
            "java.lang.Thread.sleep",
            "java.lang.Thread.sleep0",
            "java.lang.Thread.sleepNanos0",
            "java.lang.Thread.yield",
            "java.lang.Thread.yield0",
            // JVM 自带的后台线程
            "java.lang.ref.Reference.waitForReferencePendingList",
            "java.lang.ref.Reference.getAndClearReferencePendingList",
            "java.lang.ProcessHandleImpl.waitForProcessExit0",
            "java.lang.ProcessImpl.waitFor",
            "java.lang.ProcessImpl.waitForInterruptibly",
            "sun.nio.fs.LinuxWatchService.poll",
            "sun.nio.fs.WindowsNativeDispatcher.GetQueuedCompletionStatus0");

    private static final Set<String> IDLE_SET = Set.copyOf(IDLE_TOP_METHODS);
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    // ==========================================
    // 结果模型
    // ==========================================

    /**
     * 整体分析结果。
     *
     * @param dumps     每份转储各自的分析
     * @param timelines 多份转储时每个线程的跨转储轨迹；只有一份转储时为空
     */
    public record Report(List<DumpAnalysis> dumps, List<ThreadTimeline> timelines) {
        public Report {
            dumps = List.copyOf(dumps);
            timelines = List.copyOf(timelines);
        }

        public boolean isMultiDump() {
            return dumps.size() > 1;
        }
    }

    /**
     * 单份转储的分析。
     *
     * @param dump        原始转储
     * @param stateCounts 各状态的线程数（所有状态都有键，没有的为 0）
     * @param deadlocks   死锁环（栈上推导出的与 JVM 报告的合并去重）
     * @param contention  有线程排队的锁，按排队数降序
     * @param stackGroups 两个及以上线程共享的栈，按人数降序
     * @param pools       线程名归一化后的分组，按人数降序
     * @param hotMethods  RUNNABLE 线程非空闲栈顶方法，按次数降序
     */
    public record DumpAnalysis(
            ThreadDump dump,
            Map<ThreadState, Integer> stateCounts,
            List<Deadlock> deadlocks,
            List<LockContention> contention,
            List<StackGroup> stackGroups,
            List<PoolGroup> pools,
            List<HotMethod> hotMethods) {

        public DumpAnalysis {
            stateCounts = Collections.unmodifiableMap(new EnumMap<>(stateCounts));
            deadlocks = List.copyOf(deadlocks);
            contention = List.copyOf(contention);
            stackGroups = List.copyOf(stackGroups);
            pools = List.copyOf(pools);
            hotMethods = List.copyOf(hotMethods);
        }

        public int total() {
            return dump.threads().size();
        }

        public int count(ThreadState state) {
            return stateCounts.getOrDefault(state, 0);
        }
    }

    /**
     * 一个死锁环。
     *
     * @param links              环上的边，从名字最小的线程开始按等待方向排列
     * @param detectedFromStacks 是否由栈上的锁行推导出
     * @param reportedByJvm      JVM 自身是否也报告了它
     */
    public record Deadlock(List<ThreadDump.Link> links, boolean detectedFromStacks, boolean reportedByJvm) {
        public Deadlock {
            links = List.copyOf(links);
        }

        public List<String> threadNames() {
            List<String> names = new ArrayList<>();
            for (ThreadDump.Link link : links) {
                names.add(link.threadName());
            }
            return names;
        }
    }

    /**
     * 一把有线程排队的锁。
     *
     * @param address   锁地址
     * @param className 锁对象类名，未知为 {@code null}
     * @param owner     持有者，转储里找不到时为 {@code null}
     * @param waiters   正在等待（进入 synchronized 或 park）这把锁的线程
     */
    public record LockContention(String address, String className, ThreadInfo owner, List<ThreadInfo> waiters) {
        public LockContention {
            waiters = List.copyOf(waiters);
        }
    }

    /**
     * 共享同一段栈的线程。
     *
     * @param frames  参与比较的栈帧（取自第一个成员）
     * @param members 成员线程
     * @param states  成员的状态分布
     */
    public record StackGroup(List<ThreadInfo.Frame> frames, List<ThreadInfo> members,
                             Map<ThreadState, Integer> states) {
        public StackGroup {
            frames = List.copyOf(frames);
            members = List.copyOf(members);
            states = Collections.unmodifiableMap(new EnumMap<>(states));
        }
    }

    /**
     * 线程名把数字替换为 {@code #} 后归并的一组线程，通常对应一个线程池。
     *
     * @param pattern 归一化后的名字，如 {@code pool-#-thread-#}
     * @param members 成员线程
     * @param states  成员的状态分布
     */
    public record PoolGroup(String pattern, List<ThreadInfo> members, Map<ThreadState, Integer> states) {
        public PoolGroup {
            members = List.copyOf(members);
            states = Collections.unmodifiableMap(new EnumMap<>(states));
        }

        public int total() {
            return members.size();
        }

        public int count(ThreadState state) {
            return states.getOrDefault(state, 0);
        }
    }

    /**
     * 热点方法。
     *
     * @param method  全限定方法名
     * @param threads 栈顶是该方法的 RUNNABLE 线程
     */
    public record HotMethod(String method, List<ThreadInfo> threads) {
        public HotMethod {
            threads = List.copyOf(threads);
        }

        public int count() {
            return threads.size();
        }
    }

    /**
     * 一个线程在多份转储中的轨迹。
     *
     * @param name           线程名
     * @param nid            nid（没有则为 tid），可能为 {@code null}
     * @param perDump        每份转储中的该线程，缺席为 {@code null}
     * @param presentInAll   是否在每份转储中都出现
     * @param sameStackInAll 是否每份转储里栈都完全相同（且非空）
     * @param suspicious     是否疑似卡死：栈不变且不是在空闲等待，或一直在等锁
     */
    public record ThreadTimeline(String name, String nid, List<ThreadInfo> perDump,
                                 boolean presentInAll, boolean sameStackInAll, boolean suspicious) {
        public ThreadTimeline {
            perDump = Collections.unmodifiableList(new ArrayList<>(perDump));
        }

        /** 第 {@code index} 份转储中的状态，缺席为 {@code null}。 */
        public ThreadState stateAt(int index) {
            ThreadInfo info = perDump.get(index);
            return info == null ? null : info.state();
        }

        /** 最后一次出现时的线程信息。 */
        public ThreadInfo latest() {
            for (int i = perDump.size() - 1; i >= 0; i--) {
                if (perDump.get(i) != null) {
                    return perDump.get(i);
                }
            }
            return null;
        }
    }

    // ==========================================
    // 入口
    // ==========================================

    public Report analyze(List<ThreadDump> dumps) {
        return analyze(dumps, ALL_FRAMES);
    }

    /**
     * @param stackDepth 同栈分组比较的栈帧数，{@code <= 0} 表示全部
     */
    public Report analyze(List<ThreadDump> dumps, int stackDepth) {
        Objects.requireNonNull(dumps, "dumps");
        List<DumpAnalysis> analyses = new ArrayList<>(dumps.size());
        List<Map<String, ThreadInfo>> owners = new ArrayList<>(dumps.size());
        for (ThreadDump dump : dumps) {
            analyses.add(analyzeDump(dump, stackDepth));
            owners.add(ownersByAddress(dump.threads()));
        }
        List<ThreadTimeline> timelines = dumps.size() > 1 ? timelines(dumps, owners) : List.of();
        return new Report(analyses, timelines);
    }

    public DumpAnalysis analyzeDump(ThreadDump dump, int stackDepth) {
        Objects.requireNonNull(dump, "dump");
        List<ThreadInfo> threads = dump.threads();
        Map<String, ThreadInfo> owners = ownersByAddress(threads);
        return new DumpAnalysis(dump,
                stateCounts(threads),
                deadlocks(dump, owners),
                contention(threads, owners),
                stackGroups(threads, stackDepth),
                pools(threads),
                hotMethods(threads));
    }

    /** 栈帧是否属于 {@link #IDLE_TOP_METHODS}。 */
    public static boolean isIdleFrame(ThreadInfo.Frame frame) {
        return frame != null && IDLE_SET.contains(frame.methodName());
    }

    /** 线程池归一化：数字串替换为 {@code #}，{@code http-nio-8080-exec-12} → {@code http-nio-#-exec-#}。 */
    public static String poolPattern(String threadName) {
        return DIGITS.matcher(threadName == null ? "" : threadName).replaceAll("#");
    }

    // ==========================================
    // 各项分析
    // ==========================================

    private static Map<ThreadState, Integer> stateCounts(List<ThreadInfo> threads) {
        Map<ThreadState, Integer> counts = emptyCounts();
        for (ThreadInfo thread : threads) {
            counts.merge(thread.state(), 1, Integer::sum);
        }
        return counts;
    }

    private static Map<ThreadState, Integer> emptyCounts() {
        Map<ThreadState, Integer> counts = new EnumMap<>(ThreadState.class);
        for (ThreadState state : ThreadState.values()) {
            counts.put(state, 0);
        }
        return counts;
    }

    /** 锁地址 → 持有者；同一地址出现多个持有者时（转储不一致）取第一个。 */
    private static Map<String, ThreadInfo> ownersByAddress(List<ThreadInfo> threads) {
        Map<String, ThreadInfo> owners = new HashMap<>();
        for (ThreadInfo thread : threads) {
            for (String address : thread.heldLockAddresses()) {
                owners.putIfAbsent(address, thread);
            }
        }
        return owners;
    }

    /**
     * 死锁检测：等待图里每个线程至多一条出边（它卡住的那把锁 → 锁的持有者），
     * 因此沿出边走到重复节点即得一个环，O(n)。
     *
     * <p>JVM 自己的死锁报告只覆盖监视器和可拥有同步器，且转储被裁剪时可能丢失；
     * 栈上推导能补上这些情况。两者按线程集合去重合并。</p>
     */
    private static List<Deadlock> deadlocks(ThreadDump dump, Map<String, ThreadInfo> owners) {
        List<ThreadInfo> threads = dump.threads();
        int size = threads.size();
        // 按对象身份建索引：record 的 equals 是值相等，两条内容完全相同的线程记录不能合并。
        Map<ThreadInfo, Integer> indexOf = new IdentityHashMap<>();
        for (int i = 0; i < size; i++) {
            indexOf.put(threads.get(i), i);
        }
        int[] next = new int[size];
        ThreadInfo.Lock[] waitingFor = new ThreadInfo.Lock[size];
        for (int i = 0; i < size; i++) {
            next[i] = -1;
            ThreadInfo.Lock lock = threads.get(i).blockedOn();
            if (lock == null) {
                continue;
            }
            ThreadInfo owner = owners.get(lock.address());
            if (owner != null && owner != threads.get(i)) {
                next[i] = indexOf.get(owner);
                waitingFor[i] = lock;
            }
        }

        List<Deadlock> result = new ArrayList<>();
        Map<Set<String>, Integer> byThreads = new HashMap<>();
        int[] color = new int[size];
        for (int start = 0; start < size; start++) {
            if (color[start] != 0) {
                continue;
            }
            List<Integer> path = new ArrayList<>();
            int node = start;
            while (node >= 0 && color[node] == 0) {
                color[node] = 1;
                path.add(node);
                node = next[node];
            }
            if (node >= 0 && color[node] == 1) {
                List<Integer> cycle = path.subList(path.indexOf(node), path.size());
                List<ThreadDump.Link> links = cycleLinks(threads, cycle, next, waitingFor);
                byThreads.put(nameSet(links), result.size());
                result.add(new Deadlock(links, true, false));
            }
            for (int visited : path) {
                color[visited] = 2;
            }
        }

        for (ThreadDump.JvmDeadlock reported : dump.jvmDeadlocks()) {
            Set<String> key = nameSet(reported.links());
            Integer existing = byThreads.get(key);
            if (existing != null) {
                Deadlock found = result.get(existing);
                result.set(existing, new Deadlock(found.links(), true, true));
            } else {
                byThreads.put(key, result.size());
                result.add(new Deadlock(reported.links(), false, true));
            }
        }
        return result;
    }

    private static List<ThreadDump.Link> cycleLinks(List<ThreadInfo> threads, List<Integer> cycle,
                                                    int[] next, ThreadInfo.Lock[] waitingFor) {
        // 从名字最小的线程开始，保证同一个环每次输出顺序一致。
        int startAt = 0;
        for (int i = 1; i < cycle.size(); i++) {
            if (threads.get(cycle.get(i)).name().compareTo(threads.get(cycle.get(startAt)).name()) < 0) {
                startAt = i;
            }
        }
        List<ThreadDump.Link> links = new ArrayList<>(cycle.size());
        for (int offset = 0; offset < cycle.size(); offset++) {
            int node = cycle.get((startAt + offset) % cycle.size());
            ThreadInfo.Lock lock = waitingFor[node];
            ThreadInfo.LockKind kind = lock.kind() == ThreadInfo.LockKind.PARKING
                    ? ThreadInfo.LockKind.PARKING : ThreadInfo.LockKind.WAITING_TO_LOCK;
            links.add(new ThreadDump.Link(threads.get(node).name(), lock.address(), lock.className(),
                    kind, threads.get(next[node]).name()));
        }
        return links;
    }

    private static Set<String> nameSet(List<ThreadDump.Link> links) {
        Set<String> names = new TreeSet<>();
        for (ThreadDump.Link link : links) {
            names.add(link.threadName());
        }
        return names;
    }

    private static List<LockContention> contention(List<ThreadInfo> threads, Map<String, ThreadInfo> owners) {
        Map<String, List<ThreadInfo>> waiters = new LinkedHashMap<>();
        Map<String, String> classes = new HashMap<>();
        for (ThreadInfo thread : threads) {
            ThreadInfo.Lock lock = thread.blockedOn();
            if (lock == null) {
                continue;
            }
            waiters.computeIfAbsent(lock.address(), key -> new ArrayList<>()).add(thread);
            if (lock.className() != null) {
                classes.putIfAbsent(lock.address(), lock.className());
            }
        }
        List<LockContention> result = new ArrayList<>();
        for (Map.Entry<String, List<ThreadInfo>> entry : waiters.entrySet()) {
            String address = entry.getKey();
            String className = classes.get(address);
            ThreadInfo owner = owners.get(address);
            if (className == null && owner != null) {
                className = classOfHeld(owner, address);
            }
            result.add(new LockContention(address, className, owner, entry.getValue()));
        }
        result.sort(Comparator.comparingInt((LockContention item) -> item.waiters().size()).reversed()
                .thenComparing(LockContention::address));
        return result;
    }

    private static String classOfHeld(ThreadInfo owner, String address) {
        for (ThreadInfo.Lock lock : owner.allLocks()) {
            if (address.equals(lock.address()) && lock.className() != null) {
                return lock.className();
            }
        }
        return null;
    }

    private static List<StackGroup> stackGroups(List<ThreadInfo> threads, int stackDepth) {
        Map<String, List<ThreadInfo>> groups = new LinkedHashMap<>();
        for (ThreadInfo thread : threads) {
            if (thread.frames().isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(stackKey(thread, stackDepth), key -> new ArrayList<>()).add(thread);
        }
        List<StackGroup> result = new ArrayList<>();
        for (List<ThreadInfo> members : groups.values()) {
            if (members.size() < 2) {
                continue;
            }
            List<ThreadInfo.Frame> frames = members.get(0).frames();
            if (stackDepth > 0 && frames.size() > stackDepth) {
                frames = frames.subList(0, stackDepth);
            }
            result.add(new StackGroup(frames, members, countStates(members)));
        }
        // 稳定排序：人数相同时保持在转储中首次出现的顺序。
        result.sort(Comparator.comparingInt((StackGroup group) -> group.members().size()).reversed());
        return result;
    }

    /** 只比较栈帧文本，不含锁行：同一处代码等不同的锁对象，仍然是「卡在同一个地方」。 */
    private static String stackKey(ThreadInfo thread, int stackDepth) {
        List<ThreadInfo.Frame> frames = thread.frames();
        int limit = stackDepth > 0 ? Math.min(stackDepth, frames.size()) : frames.size();
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            key.append(frames.get(i).text()).append('\n');
        }
        return key.toString();
    }

    private static List<PoolGroup> pools(List<ThreadInfo> threads) {
        Map<String, List<ThreadInfo>> groups = new LinkedHashMap<>();
        for (ThreadInfo thread : threads) {
            groups.computeIfAbsent(poolPattern(thread.name()), key -> new ArrayList<>()).add(thread);
        }
        List<PoolGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<ThreadInfo>> entry : groups.entrySet()) {
            result.add(new PoolGroup(entry.getKey(), entry.getValue(), countStates(entry.getValue())));
        }
        result.sort(Comparator.comparingInt(PoolGroup::total).reversed().thenComparing(PoolGroup::pattern));
        return result;
    }

    private static List<HotMethod> hotMethods(List<ThreadInfo> threads) {
        Map<String, List<ThreadInfo>> groups = new LinkedHashMap<>();
        for (ThreadInfo thread : threads) {
            ThreadInfo.Frame top = thread.topFrame();
            if (thread.state() != ThreadState.RUNNABLE || top == null || isIdleFrame(top)) {
                continue;
            }
            groups.computeIfAbsent(top.methodName(), key -> new ArrayList<>()).add(thread);
        }
        List<HotMethod> result = new ArrayList<>();
        for (Map.Entry<String, List<ThreadInfo>> entry : groups.entrySet()) {
            result.add(new HotMethod(entry.getKey(), entry.getValue()));
        }
        result.sort(Comparator.comparingInt(HotMethod::count).reversed().thenComparing(HotMethod::method));
        return result;
    }

    private static List<ThreadTimeline> timelines(List<ThreadDump> dumps, List<Map<String, ThreadInfo>> owners) {
        int count = dumps.size();
        Map<String, ThreadInfo[]> byKey = new LinkedHashMap<>();
        for (int d = 0; d < count; d++) {
            for (ThreadInfo thread : dumps.get(d).threads()) {
                ThreadInfo[] slots = byKey.computeIfAbsent(thread.identityKey(), key -> new ThreadInfo[count]);
                if (slots[d] == null) {
                    slots[d] = thread;
                }
            }
        }
        List<ThreadTimeline> result = new ArrayList<>(byKey.size());
        for (ThreadInfo[] slots : byKey.values()) {
            ThreadInfo first = null;
            boolean presentInAll = true;
            for (ThreadInfo slot : slots) {
                if (slot == null) {
                    presentInAll = false;
                } else if (first == null) {
                    first = slot;
                }
            }
            boolean sameStack = presentInAll && !first.frames().isEmpty();
            for (int d = 1; sameStack && d < count; d++) {
                sameStack = sameFrames(first, slots[d]);
            }
            boolean suspicious = sameStack && looksStuck(slots, owners);
            String osId = first.nid() != null ? first.nid() : first.tid();
            result.add(new ThreadTimeline(first.name(), osId, Arrays.asList(slots),
                    presentInAll, sameStack, suspicious));
        }
        return result;
    }

    /** 栈始终不变时，排除「空闲等待」的正常情况：栈顶是 park / wait / epoll 且不是在等一把有主的锁。 */
    private static boolean looksStuck(ThreadInfo[] slots, List<Map<String, ThreadInfo>> owners) {
        for (int d = 0; d < slots.length; d++) {
            ThreadInfo thread = slots[d];
            if (thread.state() == ThreadState.BLOCKED) {
                return true;
            }
            ThreadInfo.Lock lock = thread.blockedOn();
            if (lock != null) {
                if (lock.kind() != ThreadInfo.LockKind.PARKING) {
                    return true;
                }
                if (owners.get(d).containsKey(lock.address())) {
                    return true;
                }
            }
        }
        return !isIdleFrame(slots[0].topFrame());
    }

    private static boolean sameFrames(ThreadInfo left, ThreadInfo right) {
        List<ThreadInfo.Frame> a = left.frames();
        List<ThreadInfo.Frame> b = right.frames();
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).text().equals(b.get(i).text())) {
                return false;
            }
        }
        return true;
    }

    private static Map<ThreadState, Integer> countStates(List<ThreadInfo> members) {
        Map<ThreadState, Integer> counts = new EnumMap<>(ThreadState.class);
        for (ThreadInfo member : members) {
            counts.merge(member.state(), 1, Integer::sum);
        }
        return counts;
    }
}
