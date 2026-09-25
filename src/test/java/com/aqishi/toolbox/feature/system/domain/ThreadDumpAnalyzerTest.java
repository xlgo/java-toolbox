package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static com.aqishi.toolbox.feature.system.domain.ThreadDumpParserTest.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadDumpAnalyzerTest {

    private final ThreadDumpParser parser = new ThreadDumpParser();
    private final ThreadDumpAnalyzer analyzer = new ThreadDumpAnalyzer();

    private ThreadDumpAnalyzer.DumpAnalysis analyze(String text) {
        return analyzer.analyze(parser.parse(text)).dumps().get(0);
    }

    /** 去掉 JVM 自己的死锁报告，只留栈上的 locked / waiting 行。 */
    private static String stripJvmDeadlockSection(String text) {
        int start = text.indexOf("Found one Java-level deadlock:");
        return start < 0 ? text : text.substring(0, start);
    }

    @Test
    void countsThreadsByState() {
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(resource("deadlock-monitor.txt"));
        assertEquals(14, analysis.total());
        assertEquals(5, analysis.count(ThreadState.RUNNABLE));
        assertEquals(3, analysis.count(ThreadState.BLOCKED));
        assertEquals(2, analysis.count(ThreadState.WAITING));
        assertEquals(1, analysis.count(ThreadState.TIMED_WAITING));
        assertEquals(0, analysis.count(ThreadState.NEW));
        assertEquals(3, analysis.count(ThreadState.NONE));
    }

    @Test
    void detectsMonitorDeadlockAndMergesWithJvmReport() {
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(resource("deadlock-monitor.txt"));
        assertEquals(1, analysis.deadlocks().size());
        ThreadDumpAnalyzer.Deadlock deadlock = analysis.deadlocks().get(0);
        assertTrue(deadlock.detectedFromStacks());
        assertTrue(deadlock.reportedByJvm());
        assertEquals(List.of("Thread-0", "Thread-1"), deadlock.threadNames());
        ThreadDump.Link first = deadlock.links().get(0);
        assertEquals("0x711e0b6d8", first.lockAddress());
        assertEquals("Thread-1", first.ownerName());
        assertEquals("java.lang.Object", first.lockClass());
    }

    @Test
    void detectsMonitorDeadlockWithoutJvmSection() {
        ThreadDumpAnalyzer.DumpAnalysis analysis =
                analyze(stripJvmDeadlockSection(resource("deadlock-monitor.txt")));
        assertTrue(analysis.dump().jvmDeadlocks().isEmpty());
        assertEquals(1, analysis.deadlocks().size());
        ThreadDumpAnalyzer.Deadlock deadlock = analysis.deadlocks().get(0);
        assertTrue(deadlock.detectedFromStacks());
        assertFalse(deadlock.reportedByJvm());
        assertEquals(List.of("Thread-0", "Thread-1"), deadlock.threadNames());
    }

    @Test
    void detectsReentrantLockDeadlockViaOwnableSynchronizers() {
        String text = stripJvmDeadlockSection(resource("deadlock-reentrant.txt"));
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(text);
        assertEquals(1, analysis.deadlocks().size());
        ThreadDumpAnalyzer.Deadlock deadlock = analysis.deadlocks().get(0);
        assertEquals(List.of("lock-worker-1", "lock-worker-2"), deadlock.threadNames());
        assertEquals(ThreadInfo.LockKind.PARKING, deadlock.links().get(0).waitKind());
        assertEquals("java.util.concurrent.locks.ReentrantLock$NonfairSync", deadlock.links().get(0).lockClass());

        ThreadDumpAnalyzer.DumpAnalysis withJvm = analyze(resource("deadlock-reentrant.txt"));
        assertEquals(1, withJvm.deadlocks().size());
        assertTrue(withJvm.deadlocks().get(0).reportedByJvm());
        assertTrue(withJvm.deadlocks().get(0).detectedFromStacks());
    }

    @Test
    void keepsJvmOnlyDeadlockWhenStacksAreMissing() {
        String full = resource("deadlock-monitor.txt");
        // 把两个死锁线程的锁行删掉，只剩 JVM 报告。
        String withoutLocks = Pattern.compile("(?m)^\\t- (waiting to lock|locked) <0x0000000711e0b6[cd]8>.*\\R")
                .matcher(full.substring(0, full.indexOf("Found one Java-level deadlock:")))
                .replaceAll("") + full.substring(full.indexOf("Found one Java-level deadlock:"));
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(withoutLocks);
        assertEquals(1, analysis.deadlocks().size());
        assertFalse(analysis.deadlocks().get(0).detectedFromStacks());
        assertTrue(analysis.deadlocks().get(0).reportedByJvm());
    }

    @Test
    void doesNotReportDeadlockForWaitAndConditionParking() {
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(resource("two-dumps.txt"));
        assertTrue(analysis.deadlocks().isEmpty());
    }

    @Test
    void ranksLockContention() {
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(resource("deadlock-monitor.txt"));
        ThreadDumpAnalyzer.LockContention top = analysis.contention().get(0);
        assertEquals("0x711e0b6c8", top.address());
        assertEquals("java.lang.Object", top.className());
        assertEquals("Thread-0", top.owner().name());
        assertEquals(2, top.waiters().size());
        // Condition 上 park 的空闲线程也算排队（按对象计），但没有持有者。
        ThreadDumpAnalyzer.LockContention condition = analysis.contention().stream()
                .filter(item -> item.address().equals("0x711e05a10")).findFirst().orElseThrow();
        assertNull(condition.owner());
    }

    @Test
    void groupsIdenticalStacks() {
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(resource("deadlock-monitor.txt"));
        // exec-1 / exec-2 栈完全相同；Thread-0 / Thread-1 行号相同但 lambda 帧不同。
        assertEquals(1, analysis.stackGroups().size());
        ThreadDumpAnalyzer.StackGroup group = analysis.stackGroups().get(0);
        assertEquals(2, group.members().size());
        assertEquals("http-nio-8080-exec-1", group.members().get(0).name());
        assertEquals(2, group.states().get(ThreadState.RUNNABLE));

        // 只比较栈顶 1 帧时，Thread-0 / Thread-1 也归为一组。
        ThreadDumpAnalyzer.DumpAnalysis shallow = analyzer.analyzeDump(
                parser.parse(resource("deadlock-monitor.txt")).get(0), 1);
        assertTrue(shallow.stackGroups().stream().anyMatch(g -> g.frames().size() == 1
                && g.frames().get(0).text().startsWith("com.example.Deadlock.transfer")
                && g.members().size() == 2));
    }

    @Test
    void normalizesThreadPoolNames() {
        assertEquals("pool-#-thread-#", ThreadDumpAnalyzer.poolPattern("pool-3-thread-17"));
        assertEquals("http-nio-#-exec-#", ThreadDumpAnalyzer.poolPattern("http-nio-8080-exec-12"));
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(resource("deadlock-monitor.txt"));
        ThreadDumpAnalyzer.PoolGroup first = analysis.pools().get(0);
        assertEquals("Thread-#", first.pattern());
        assertEquals(3, first.total());
        assertEquals(3, first.count(ThreadState.BLOCKED));
        assertTrue(analysis.pools().stream().anyMatch(p -> p.pattern().equals("http-nio-#-exec-#") && p.total() == 2));
    }

    @Test
    void findsHotMethodsExcludingIdleFrames() {
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(resource("deadlock-monitor.txt"));
        assertEquals(1, analysis.hotMethods().size());
        ThreadDumpAnalyzer.HotMethod hot = analysis.hotMethods().get(0);
        assertEquals("com.example.Hash.compute", hot.method());
        assertEquals(2, hot.count());
        assertTrue(ThreadDumpAnalyzer.IDLE_TOP_METHODS.contains("sun.nio.ch.Net.accept"));
        assertTrue(ThreadDumpAnalyzer.IDLE_TOP_METHODS.contains("jdk.internal.misc.Unsafe.park"));
    }

    @Test
    void comparesThreadsAcrossDumps() {
        ThreadDumpAnalyzer.Report report = analyzer.analyze(parser.parse(resource("two-dumps.txt")));
        assertTrue(report.isMultiDump());
        List<ThreadDumpAnalyzer.ThreadTimeline> timelines = report.timelines();
        assertEquals(6, timelines.size());

        ThreadDumpAnalyzer.ThreadTimeline blocked = timeline(timelines, "report-1");
        assertTrue(blocked.presentInAll());
        assertTrue(blocked.sameStackInAll());
        assertTrue(blocked.suspicious());
        assertEquals(ThreadState.BLOCKED, blocked.stateAt(1));
        assertEquals("0x200", blocked.nid());

        ThreadDumpAnalyzer.ThreadTimeline idle = timeline(timelines, "idle-worker");
        assertTrue(idle.sameStackInAll());
        assertFalse(idle.suspicious());

        ThreadDumpAnalyzer.ThreadTimeline gone = timeline(timelines, "short-lived");
        assertFalse(gone.presentInAll());
        assertNull(gone.stateAt(1));
        ThreadDumpAnalyzer.ThreadTimeline late = timeline(timelines, "late-starter");
        assertNull(late.stateAt(0));
        assertEquals(ThreadState.RUNNABLE, late.stateAt(1));
        assertEquals("late-starter", late.latest().name());

        assertTrue(analyzer.analyze(parser.parse(resource("deadlock-monitor.txt"))).timelines().isEmpty());
    }

    private static ThreadDumpAnalyzer.ThreadTimeline timeline(List<ThreadDumpAnalyzer.ThreadTimeline> all,
                                                              String name) {
        return all.stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void handlesManyThreadsStuckInTheSamePlace() {
        StringBuilder text = new StringBuilder("Full thread dump OpenJDK 64-Bit Server VM (17 mixed mode):\n\n");
        text.append("\"holder\" #2 prio=5 os_prio=0 tid=0x1 nid=0x1 runnable [0x0]\n")
                .append("   java.lang.Thread.State: RUNNABLE\n")
                .append("\tat com.example.Db.query(Db.java:1)\n")
                .append("\t- locked <0x00000000abc00000> (a com.example.Pool)\n\n");
        for (int i = 0; i < 200; i++) {
            text.append("\"exec-").append(i).append("\" #").append(10 + i)
                    .append(" daemon prio=5 os_prio=0 tid=0x").append(Integer.toHexString(100 + i))
                    .append(" nid=0x").append(Integer.toHexString(1000 + i))
                    .append(" waiting for monitor entry [0x0]\n")
                    .append("   java.lang.Thread.State: BLOCKED (on object monitor)\n")
                    .append("\tat com.example.Pool.borrow(Pool.java:9)\n")
                    .append("\t- waiting to lock <0x00000000abc00000> (a com.example.Pool)\n")
                    .append("\tat com.example.Service.call(Service.java:3)\n\n");
        }
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(text.toString());
        assertEquals(201, analysis.total());
        assertEquals(200, analysis.stackGroups().get(0).members().size());
        assertEquals(200, analysis.contention().get(0).waiters().size());
        assertEquals("holder", analysis.contention().get(0).owner().name());
        assertEquals("exec-#", analysis.pools().get(0).pattern());
        assertTrue(analysis.deadlocks().isEmpty());
    }

    @Test
    void detectsThreeThreadCycle() {
        StringBuilder text = new StringBuilder();
        String[] names = {"c", "a", "b"};
        for (int i = 0; i < 3; i++) {
            text.append('"').append(names[i]).append("\" #").append(i + 1)
                    .append(" prio=5 os_prio=0 tid=0x1 nid=0x").append(i + 1).append(" waiting for monitor entry\n")
                    .append("   java.lang.Thread.State: BLOCKED (on object monitor)\n")
                    .append("\tat X.m(X.java:1)\n")
                    .append("\t- waiting to lock <0x").append((i + 1) % 3 + 10).append("> (a L)\n")
                    .append("\t- locked <0x").append(i + 10).append("> (a L)\n\n");
        }
        ThreadDumpAnalyzer.DumpAnalysis analysis = analyze(text.toString());
        assertEquals(1, analysis.deadlocks().size());
        // 从名字最小的线程开始：a 等 b 持有的锁 → b 等 c → c 等 a。
        List<ThreadDump.Link> links = analysis.deadlocks().get(0).links();
        assertEquals("a", links.get(0).threadName());
        assertEquals(links.get(0).ownerName(), links.get(1).threadName());
        assertEquals(links.get(2).ownerName(), links.get(0).threadName());
    }
}
