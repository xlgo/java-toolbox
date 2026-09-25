package com.aqishi.toolbox.feature.system.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreadDumpParserTest {

    private final ThreadDumpParser parser = new ThreadDumpParser();

    static String resource(String name) {
        try (InputStream in = ThreadDumpParserTest.class.getResourceAsStream("/threaddump/" + name)) {
            return new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    static ThreadInfo thread(ThreadDump dump, String name) {
        return dump.threads().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("missing thread " + name));
    }

    @Test
    void parsesJstackHeaderFieldsStateFramesAndLocks() {
        List<ThreadDump> dumps = parser.parse(resource("deadlock-monitor.txt"));
        assertEquals(1, dumps.size());
        ThreadDump dump = dumps.get(0);
        assertEquals("2026-09-26 10:15:30", dump.timestamp());
        assertEquals("OpenJDK 64-Bit Server VM (17.0.8+7-LTS mixed mode, sharing)", dump.vmInfo());
        // 11 个 Java 线程 + 3 个 JVM 内部线程；死锁小节里的 "Thread-0": 不能被当成新线程。
        assertEquals(14, dump.threads().size());

        ThreadInfo main = thread(dump, "main");
        assertEquals(1, main.number());
        assertFalse(main.daemon());
        assertEquals(5, main.priority());
        assertEquals(0, main.osPriority());
        assertEquals("48.21ms", main.cpu());
        assertEquals("12.34s", main.elapsed());
        assertEquals("0x00007f3c9c0275b0", main.tid());
        assertEquals("0x1a03", main.nid());
        assertEquals("waiting on condition", main.headerState());
        assertEquals(ThreadState.TIMED_WAITING, main.state());
        assertEquals("sleeping", main.stateDetail());
        assertEquals(2, main.frames().size());
        assertEquals("java.lang.Thread.sleep", main.topFrame().methodName());

        ThreadInfo blocked = thread(dump, "Thread-0");
        assertEquals(ThreadState.BLOCKED, blocked.state());
        assertEquals("on object monitor", blocked.stateDetail());
        List<ThreadInfo.Lock> topLocks = blocked.topFrame().locks();
        assertEquals(2, topLocks.size());
        assertEquals(ThreadInfo.LockKind.WAITING_TO_LOCK, topLocks.get(0).kind());
        assertEquals("0x711e0b6d8", topLocks.get(0).address());
        assertEquals("java.lang.Object", topLocks.get(0).className());
        assertEquals(ThreadInfo.LockKind.LOCKED, topLocks.get(1).kind());
        assertEquals("0x711e0b6d8", blocked.blockedOn().address());
        assertTrue(blocked.heldLockAddresses().contains("0x711e0b6c8"));
        assertEquals("com.example.Deadlock$$Lambda$14/0x0000000800c03200.run",
                blocked.frames().get(2).methodName());
    }

    @Test
    void keepsQuotesInsideThreadNames() {
        ThreadDump dump = parser.parse(resource("deadlock-monitor.txt")).get(0);
        ThreadInfo quoted = thread(dump, "worker \"alpha\" #7");
        assertEquals(12, quoted.number());
        assertEquals(ThreadState.WAITING, quoted.state());
        assertEquals(ThreadInfo.LockKind.PARKING, quoted.blockedOn().kind());
    }

    @Test
    void marksVmInternalThreadsAndCompilerThreadsWithoutStacks() {
        ThreadDump dump = parser.parse(resource("deadlock-monitor.txt")).get(0);
        ThreadInfo vm = thread(dump, "VM Thread");
        assertFalse(vm.javaThread());
        assertEquals(ThreadState.NONE, vm.state());
        assertEquals("runnable", vm.headerState());
        assertTrue(vm.frames().isEmpty());
        assertFalse(thread(dump, "GC Thread#0").javaThread());

        ThreadInfo compiler = thread(dump, "C2 CompilerThread0");
        assertTrue(compiler.javaThread());
        assertTrue(compiler.daemon());
        assertEquals(ThreadState.RUNNABLE, compiler.state());
        assertTrue(compiler.frames().isEmpty());
    }

    @Test
    void waitReleasesMonitorShownAsLockedOnCallerFrame() {
        ThreadDump dump = parser.parse(resource("deadlock-monitor.txt")).get(0);
        ThreadInfo finalizer = thread(dump, "Finalizer");
        // 调用方帧打印了 "- locked"，但 wait() 已经释放了监视器。
        assertTrue(finalizer.heldLockAddresses().isEmpty());
        assertNull(finalizer.blockedOn());
    }

    @Test
    void parsesJvmReportedMonitorDeadlock() {
        ThreadDump dump = parser.parse(resource("deadlock-monitor.txt")).get(0);
        assertEquals(1, dump.jvmDeadlocks().size());
        List<ThreadDump.Link> links = dump.jvmDeadlocks().get(0).links();
        assertEquals(2, links.size());
        assertEquals("Thread-0", links.get(0).threadName());
        assertEquals("0x711e0b6d8", links.get(0).lockAddress());
        assertEquals("java.lang.Object", links.get(0).lockClass());
        assertEquals("Thread-1", links.get(0).ownerName());
        assertEquals(ThreadInfo.LockKind.WAITING_TO_LOCK, links.get(0).waitKind());
    }

    @Test
    void parsesOwnableSynchronizersAndJdk21Header() {
        ThreadDump dump = parser.parse(resource("deadlock-reentrant.txt")).get(0);
        assertEquals("2026-09-26T10:20:01.123+08:00", dump.timestamp());
        ThreadInfo worker = thread(dump, "lock-worker-1");
        assertEquals(21, worker.number());
        assertEquals("3160", worker.nid());
        assertEquals(1, worker.ownableSynchronizers().size());
        assertEquals("0x712b0a278", worker.ownableSynchronizers().get(0).address());
        assertEquals("java.util.concurrent.locks.ReentrantLock$NonfairSync",
                worker.ownableSynchronizers().get(0).className());
        assertEquals("0x712b0a2a8", worker.blockedOn().address());
        assertTrue(thread(dump, "main").ownableSynchronizers().isEmpty());

        assertEquals(1, dump.jvmDeadlocks().size());
        ThreadDump.Link link = dump.jvmDeadlocks().get(0).links().get(0);
        assertEquals(ThreadInfo.LockKind.PARKING, link.waitKind());
        assertEquals("0x712b0a2a8", link.lockAddress());
        assertEquals("lock-worker-2", link.ownerName());
    }

    @Test
    void toleratesLogNoiseAndCrlf() {
        String text = String.join("\r\n",
                "2026-09-26 12:00:00.001 INFO  [main] o.a.c.Server - Server startup in 2345 ms",
                "2026-09-26 12:00:05.442 WARN  [pool] c.e.Job - slow job",
                "Full thread dump Java HotSpot(TM) 64-Bit Server VM (25.381-b09 mixed mode):",
                "",
                "\"Attach Listener\" #30 daemon prio=9 os_prio=0 tid=0x00007f1a10001000 nid=0x7e1 waiting on condition [0x0000000000000000]",
                "   java.lang.Thread.State: RUNNABLE",
                "",
                "\"pool-3-thread-17\" #25 prio=5 os_prio=0 tid=0x00007f1a2c1e3800 nid=0x7d0 waiting on condition [0x00007f19f4dfe000]",
                "   java.lang.Thread.State: TIMED_WAITING (parking)",
                "\tat sun.misc.Unsafe.park(Native Method)",
                "\t- parking to wait for  <0x00000000f5a1b2c8> (a java.util.concurrent.SynchronousQueue$TransferStack)",
                "\tat java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:215)",
                "\tat java.lang.Thread.run(Thread.java:750)",
                "",
                "\"GC task thread#0 (ParallelGC)\" os_prio=0 tid=0x00007f1a2c01e800 nid=0x6d3 runnable ",
                "",
                "JNI global references: 312",
                "",
                "Heap",
                " PSYoungGen      total 76288K, used 26216K [0x00000000f5580000, 0x00000000faa80000, 0x0000000100000000)",
                "  eden space 65536K, 40% used [0x00000000f5580000,0x00000000f6f1a3a8,0x00000000f9580000)",
                "2026-09-26 12:00:06.100 ERROR [pool] c.e.Job - boom",
                "java.lang.IllegalStateException: boom",
                "\tat com.example.Job.run(Job.java:12)",
                "\tat java.lang.Thread.run(Thread.java:750)",
                "");
        List<ThreadDump> dumps = parser.parse(text);
        assertEquals(1, dumps.size());
        ThreadDump dump = dumps.get(0);
        assertNull(dump.timestamp());
        assertEquals(3, dump.threads().size());

        ThreadInfo pool = thread(dump, "pool-3-thread-17");
        assertEquals(ThreadState.TIMED_WAITING, pool.state());
        assertEquals(3, pool.frames().size());
        assertEquals("0xf5a1b2c8", pool.blockedOn().address());
        assertEquals("java.lang.Thread.run(Thread.java:750)", pool.frames().get(2).text());

        ThreadInfo gc = thread(dump, "GC task thread#0 (ParallelGC)");
        assertFalse(gc.javaThread());
        // 日志里的异常栈不能挂到任何线程上。
        for (ThreadInfo info : dump.threads()) {
            for (ThreadInfo.Frame frame : info.frames()) {
                assertFalse(frame.text().contains("com.example.Job"), info.name());
            }
        }
    }

    @Test
    void splitsConcatenatedDumpsWithTheirTimestamps() {
        List<ThreadDump> dumps = parser.parse(resource("two-dumps.txt"));
        assertEquals(2, dumps.size());
        assertEquals(0, dumps.get(0).index());
        assertEquals(1, dumps.get(1).index());
        assertEquals("2026-09-26 11:00:00", dumps.get(0).timestamp());
        assertEquals("2026-09-26 11:00:10", dumps.get(1).timestamp());
        assertEquals(5, dumps.get(0).threads().size());
        assertEquals(5, dumps.get(1).threads().size());
        assertEquals("late-starter", dumps.get(1).threads().get(4).name());
    }

    @Test
    void parsesThreadsWithoutDumpHeader() {
        String text = "\"only\" #3 prio=5 os_prio=0 tid=0x1 nid=0x2 runnable [0x3]\n"
                + "   java.lang.Thread.State: RUNNABLE\n"
                + "\tat com.example.A.b(A.java:1)\n";
        List<ThreadDump> dumps = parser.parse(text);
        assertEquals(1, dumps.size());
        assertNull(dumps.get(0).vmInfo());
        assertEquals("only", dumps.get(0).threads().get(0).name());
        assertEquals(1, dumps.get(0).threads().get(0).frames().size());
    }

    @Test
    void parsesThreadMxBeanStyle() {
        String text = String.join("\n",
                "\"Thread-0\" daemon prio=5 Id=12 BLOCKED on java.lang.Object@1b6d3586 owned by \"Thread-1\" Id=13",
                "\tat com.example.A.run(A.java:10)",
                "\t-  blocked on java.lang.Object@1b6d3586",
                "\t-  locked java.lang.Object@4554617c",
                "",
                "\"Thread-1\" prio=5 Id=13 WAITING on java.util.concurrent.locks.ReentrantLock$NonfairSync@74a14482 owned by \"Thread-0\" Id=12",
                "\tat sun.misc.Unsafe.park(Native Method)",
                "\t-  waiting on java.util.concurrent.locks.ReentrantLock$NonfairSync@74a14482",
                "",
                "\tNumber of locked synchronizers = 1",
                "\t- java.util.concurrent.locks.ReentrantLock$NonfairSync@1540e19d",
                "");
        ThreadDump dump = parser.parse(text).get(0);
        assertEquals(2, dump.threads().size());
        ThreadInfo first = thread(dump, "Thread-0");
        assertTrue(first.daemon());
        assertEquals("12", first.tid());
        assertEquals(ThreadState.BLOCKED, first.state());
        assertEquals("java.lang.Object@1b6d3586", first.blockedOn().address());
        assertEquals("java.lang.Object", first.blockedOn().className());
        ThreadInfo second = thread(dump, "Thread-1");
        assertEquals(ThreadState.WAITING, second.state());
        assertEquals(1, second.ownableSynchronizers().size());
    }

    @Test
    void parsesJdk21PlainTextDumpToFile() {
        String text = String.join("\n",
                "4242",
                "2026-09-26T03:04:05.678Z",
                "21.0.2+13-LTS-58",
                "",
                "#1 \"main\"",
                "      java.base/java.lang.Thread.sleep0(Native Method)",
                "      java.base/java.lang.Thread.sleep(Thread.java:509)",
                "      app//com.example.Main.main(Main.java:9)",
                "",
                "#31 \"\" virtual",
                "      java.base/java.lang.VirtualThread.park(VirtualThread.java:582)",
                "");
        ThreadDump dump = parser.parse(text).get(0);
        assertEquals("2026-09-26T03:04:05.678Z", dump.timestamp());
        assertEquals(2, dump.threads().size());
        ThreadInfo main = dump.threads().get(0);
        assertEquals("java.lang.Thread.sleep0", main.topFrame().methodName());
        assertEquals("com.example.Main.main", main.frames().get(2).methodName());
        assertTrue(dump.threads().get(1).virtual());
    }

    @Test
    void parsesJdk21JsonFormat() {
        String json = "{\n"
                + "  \"threadDump\": {\n"
                + "    \"processId\": \"4242\",\n"
                + "    \"time\": \"2026-09-26T03:04:05.678Z\",\n"
                + "    \"runtimeVersion\": \"21.0.2+13-LTS-58\",\n"
                + "    \"threadContainers\": [\n"
                + "      {\"container\": \"<root>\", \"parent\": null, \"owner\": null, \"threads\": [\n"
                + "        {\"tid\": \"1\", \"name\": \"main\", \"stack\": [\n"
                + "          \"java.base\\/java.lang.Thread.sleep0(Native Method)\",\n"
                + "          \"app\\/\\/com.example.Main.main(Main.java:9)\"]},\n"
                + "        {\"tid\": \"21\", \"name\": \"w1\", \"state\": \"WAITING\",\n"
                + "         \"parkBlocker\": {\"object\": \"java.util.concurrent.locks.ReentrantLock$NonfairSync@aa\", \"owner\": \"22\"},\n"
                + "         \"stack\": [\"java.base\\/jdk.internal.misc.Unsafe.park(Native Method)\"]},\n"
                + "        {\"tid\": \"22\", \"name\": \"w2\", \"state\": \"WAITING\",\n"
                + "         \"parkBlocker\": {\"object\": \"java.util.concurrent.locks.ReentrantLock$NonfairSync@bb\", \"owner\": \"21\"},\n"
                + "         \"stack\": [\"java.base\\/jdk.internal.misc.Unsafe.park(Native Method)\"]}\n"
                + "      ], \"threadCount\": \"3\"}\n"
                + "    ]\n"
                + "  }\n"
                + "}\n";
        List<ThreadDump> dumps = parser.parse(json);
        assertEquals(1, dumps.size());
        ThreadDump dump = dumps.get(0);
        assertEquals("2026-09-26T03:04:05.678Z", dump.timestamp());
        assertEquals("21.0.2+13-LTS-58", dump.vmInfo());
        assertEquals(3, dump.threads().size());
        ThreadInfo main = thread(dump, "main");
        assertEquals("1", main.tid());
        assertEquals(ThreadState.NONE, main.state());
        assertEquals("java.lang.Thread.sleep0", main.topFrame().methodName());
        assertEquals("com.example.Main.main", main.frames().get(1).methodName());
        ThreadInfo w1 = thread(dump, "w1");
        assertEquals(ThreadState.WAITING, w1.state());
        assertTrue(w1.heldLockAddresses().contains("java.util.concurrent.locks.ReentrantLock$NonfairSync@bb"));

        ThreadDumpAnalyzer.DumpAnalysis analysis = new ThreadDumpAnalyzer().analyzeDump(dump, 0);
        assertEquals(1, analysis.deadlocks().size());
    }

    @Test
    void rejectsBrokenJson() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("{\"threadDump\": {"));
    }

    @Test
    void returnsEmptyForTextWithoutThreads() {
        assertTrue(parser.parse("").isEmpty());
        assertTrue(parser.parse("hello\nworld\n").isEmpty());
        assertTrue(parser.parse(null).isEmpty());
    }

    @Test
    void decodesBomsUtf8AndFallsBack() {
        String sample = "\"main\" #1 prio=5 tid=0x1 nid=0x2 runnable\n";
        byte[] utf16 = ("﻿" + sample).getBytes(StandardCharsets.UTF_16LE);
        ThreadDumpParser.Decoded decoded = ThreadDumpParser.decode(utf16);
        assertEquals(StandardCharsets.UTF_16LE, decoded.charset());
        assertEquals(sample, decoded.text());

        byte[] utf8Bom = ("﻿" + sample).getBytes(StandardCharsets.UTF_8);
        assertEquals(sample, ThreadDumpParser.decode(utf8Bom).text());

        String chinese = "\"工作线程-1\" #9 prio=5 tid=0x1 nid=0x2 runnable\n";
        assertEquals(chinese, ThreadDumpParser.decode(chinese.getBytes(StandardCharsets.UTF_8)).text());

        byte[] gbk = chinese.getBytes(Charset.forName("GBK"));
        ThreadDumpParser.Decoded fallback = ThreadDumpParser.decode(gbk);
        assertNotNull(fallback.charset());
        assertTrue(fallback.charset() != StandardCharsets.UTF_8 || fallback.text().contains("�"));
    }

    @Test
    void normalizesAddresses() {
        assertEquals("0x711e0b6d8", ThreadDumpParser.normalizeAddress("0x0000000711E0B6D8"));
        assertEquals("0x0", ThreadDumpParser.normalizeAddress("0x0000"));
        assertNull(ThreadDumpParser.normalizeAddress("owner is scalar replaced"));
        assertEquals("java.lang.Object@1b6d3586", ThreadDumpParser.normalizeAddress("java.lang.Object@1b6d3586"));
    }
}
