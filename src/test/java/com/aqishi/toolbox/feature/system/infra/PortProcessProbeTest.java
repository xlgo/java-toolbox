package com.aqishi.toolbox.feature.system.infra;

import com.aqishi.toolbox.feature.system.domain.KillResult;
import com.aqishi.toolbox.feature.system.domain.OsFamily;
import com.aqishi.toolbox.feature.system.domain.PortCheck;
import com.aqishi.toolbox.feature.system.domain.PortEntry;
import com.aqishi.toolbox.feature.system.domain.PortSnapshot;
import com.aqishi.toolbox.feature.system.domain.ProcessKillPolicy;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortProcessProbeTest {

    private static final long SELF = ProcessHandle.current().pid();

    private static byte[] fixture(String name, Charset charset) throws IOException {
        try (InputStream in = PortProcessProbeTest.class.getResourceAsStream("/portprocess/" + name)) {
            String text = new String(Objects.requireNonNull(in, name).readAllBytes(), StandardCharsets.UTF_8);
            return text.getBytes(charset);
        }
    }

    /** 按命令名应答的假执行器；未登记的命令当作「不存在」。 */
    private static final class FakeRunner implements PortProcessProbe.CommandRunner {
        final Map<String, PortProcessProbe.ExecResult> answers = new HashMap<>();
        // tasklist 在另一个线程执行，记录必须线程安全。
        final List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());

        FakeRunner answer(String program, PortProcessProbe.ExecResult result) {
            answers.put(program, result);
            return this;
        }

        @Override
        public PortProcessProbe.ExecResult run(List<String> command, Duration timeout) throws IOException {
            calls.add(command);
            PortProcessProbe.ExecResult result = answers.get(command.get(0));
            if (result == null) {
                throw new IOException("Cannot run program \"" + command.get(0) + "\"");
            }
            return result;
        }
    }

    private static PortProcessProbe probe(OsFamily os, FakeRunner runner, boolean root, Charset charset) {
        return new PortProcessProbe(os, runner, SELF, root, charset, Duration.ofMillis(200));
    }

    // ==========================================
    // 命令选择
    // ==========================================

    @Test
    void choosesCommandsPerOs() {
        assertEquals(List.of(PortSnapshot.Source.WINDOWS_NETSTAT), PortProcessProbe.strategiesFor(OsFamily.WINDOWS));
        assertEquals(List.of(PortSnapshot.Source.SS, PortSnapshot.Source.LSOF, PortSnapshot.Source.LINUX_NETSTAT),
                PortProcessProbe.strategiesFor(OsFamily.LINUX));
        assertEquals(List.of(PortSnapshot.Source.LSOF), PortProcessProbe.strategiesFor(OsFamily.MAC));
        assertEquals(List.of(PortSnapshot.Source.LSOF), PortProcessProbe.strategiesFor(OsFamily.OTHER_UNIX));

        assertEquals(List.of("netstat", "-ano"), PortProcessProbe.commandFor(PortSnapshot.Source.WINDOWS_NETSTAT, true));
        assertEquals(List.of("ss", "-tulnp"), PortProcessProbe.commandFor(PortSnapshot.Source.SS, true));
        assertEquals(List.of("ss", "-tuanp"), PortProcessProbe.commandFor(PortSnapshot.Source.SS, false));
        assertTrue(PortProcessProbe.commandFor(PortSnapshot.Source.LSOF, true).contains("-sTCP:LISTEN"));
        assertFalse(PortProcessProbe.commandFor(PortSnapshot.Source.LSOF, false).contains("-sTCP:LISTEN"));
        assertEquals(List.of("netstat", "-tuanp"), PortProcessProbe.commandFor(PortSnapshot.Source.LINUX_NETSTAT, false));
    }

    @Test
    void windowsSnapshotDetectsConsoleCodePageAndAttachesNames() throws Exception {
        Charset gbk = Charset.forName("GBK");
        FakeRunner runner = new FakeRunner()
                .answer("cmd", new PortProcessProbe.ExecResult(0, "活动代码页: 936\r\n".getBytes(gbk), false, false))
                .answer("netstat", new PortProcessProbe.ExecResult(0, fixture("netstat-windows-zh.txt", gbk), false, false))
                .answer("tasklist", new PortProcessProbe.ExecResult(0, fixture("tasklist.csv", gbk), false, false));
        PortProcessProbe probe = probe(OsFamily.WINDOWS, runner, false, null);

        PortSnapshot all = probe.snapshot(false);
        assertEquals(PortSnapshot.Source.WINDOWS_NETSTAT, all.source());
        assertEquals(PortSnapshot.Limitation.NONE, all.limitation());
        assertEquals(12, all.entries().size());
        // GBK 进程名必须按 chcp 报告的代码页正确还原。
        assertTrue(all.entries().stream().anyMatch(e -> "服务主机.exe".equals(e.processName())));
        assertTrue(all.entries().stream().anyMatch(e -> e.localPort() == 8080 && "java.exe".equals(e.processName())));
        // 按端口排序。
        for (int i = 1; i < all.entries().size(); i++) {
            assertTrue(all.entries().get(i - 1).localPort() <= all.entries().get(i).localPort());
        }

        PortSnapshot listening = probe.snapshot(true);
        assertTrue(listening.entries().stream().allMatch(PortEntry::isListening));
        assertEquals(9, listening.entries().size());
        // chcp 只执行一次，结果缓存。
        assertEquals(1, runner.calls.stream().filter(c -> c.get(0).equals("cmd")).count());
    }

    @Test
    void windowsSnapshotSurvivesTasklistFailure() throws Exception {
        FakeRunner runner = new FakeRunner()
                .answer("netstat", new PortProcessProbe.ExecResult(0,
                        fixture("netstat-windows-en.txt", StandardCharsets.UTF_8), false, false));
        PortSnapshot snapshot = probe(OsFamily.WINDOWS, runner, false, StandardCharsets.UTF_8).snapshot(false);
        // tasklist 不存在：端口列表照常返回；进程名只能靠 ProcessHandle 补，取决于本机是否恰好有这些 PID。
        assertEquals(7, snapshot.entries().size());
        assertTrue(snapshot.entries().stream().allMatch(PortEntry::hasPid));
    }

    @Test
    void linuxFallsBackFromMissingSsToLsofAndFlagsOwnProcessesOnly() throws Exception {
        FakeRunner runner = new FakeRunner()
                .answer("lsof", new PortProcessProbe.ExecResult(0,
                        fixture("lsof-linux.txt", StandardCharsets.UTF_8), false, false));
        PortSnapshot snapshot = probe(OsFamily.LINUX, runner, false, StandardCharsets.UTF_8).snapshot(false);

        assertEquals(PortSnapshot.Source.LSOF, snapshot.source());
        assertEquals(PortSnapshot.Limitation.OWN_PROCESSES_ONLY, snapshot.limitation());
        assertTrue(snapshot.isPartial());
        // ss 不在 PATH 时还会去 /usr/sbin、/sbin 等固定位置找一遍。
        assertTrue(runner.calls.stream().anyMatch(c -> c.get(0).equals("/usr/sbin/ss")));
    }

    @Test
    void linuxSsWithoutRootIsPartialButWithRootIsComplete() throws Exception {
        FakeRunner runner = new FakeRunner()
                .answer("ss", new PortProcessProbe.ExecResult(0,
                        fixture("ss-nonroot.txt", StandardCharsets.UTF_8), false, false));
        PortSnapshot user = probe(OsFamily.LINUX, runner, false, StandardCharsets.UTF_8).snapshot(true);
        assertEquals(PortSnapshot.Source.SS, user.source());
        assertEquals(PortSnapshot.Limitation.MISSING_PROCESS_INFO, user.limitation());

        PortSnapshot root = probe(OsFamily.LINUX, runner, true, StandardCharsets.UTF_8).snapshot(true);
        assertEquals(PortSnapshot.Limitation.NONE, root.limitation());
    }

    @Test
    void failingCommandFallsThroughAndAllFailuresRaiseProbeException() throws Exception {
        FakeRunner runner = new FakeRunner()
                .answer("ss", PortProcessProbe.ExecResult.of(1, "ss: invalid option -- 'p'"))
                .answer("netstat", new PortProcessProbe.ExecResult(0,
                        fixture("netstat-linux.txt", StandardCharsets.UTF_8), false, false));
        PortSnapshot snapshot = probe(OsFamily.LINUX, runner, false, StandardCharsets.UTF_8).snapshot(false);
        assertEquals(PortSnapshot.Source.LINUX_NETSTAT, snapshot.source());
        assertEquals(PortSnapshot.Limitation.MISSING_PROCESS_INFO, snapshot.limitation());

        PortProcessProbe.ProbeException missing = assertThrows(PortProcessProbe.ProbeException.class,
                () -> probe(OsFamily.MAC, new FakeRunner(), false, StandardCharsets.UTF_8).snapshot(true));
        assertEquals(PortProcessProbe.ProbeException.Code.NO_TOOL, missing.code());

        FakeRunner slow = new FakeRunner().answer("lsof", new PortProcessProbe.ExecResult(-1, new byte[0], true, false));
        assertEquals(PortProcessProbe.ProbeException.Code.TIMEOUT, assertThrows(PortProcessProbe.ProbeException.class,
                () -> probe(OsFamily.MAC, slow, false, StandardCharsets.UTF_8).snapshot(true)).code());
    }

    @Test
    void lsofWithNoMatchesIsAnEmptySnapshotNotAFailure() throws Exception {
        FakeRunner runner = new FakeRunner().answer("lsof", PortProcessProbe.ExecResult.of(1, ""));
        PortSnapshot snapshot = probe(OsFamily.MAC, runner, true, StandardCharsets.UTF_8).snapshot(true);
        assertTrue(snapshot.entries().isEmpty());
    }

    // ==========================================
    // 命令行
    // ==========================================

    @Test
    void buildsWindowsCommandLineQueryAndDecodesBase64() {
        List<String> command = PortProcessProbe.windowsCommandLineCommand(1234);
        assertEquals("powershell", command.get(0));
        assertTrue(command.get(command.size() - 1).contains("ProcessId=1234"));

        String value = "\"C:\\程序\\app.exe\" --port 8080";
        String encoded = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        assertEquals(value, PortProcessProbe.decodeBase64Line("WARNING: noise\r\n" + encoded + "\r\n"));
        assertEquals("", PortProcessProbe.decodeBase64Line("Get-CimInstance : Access denied\r\n"));
    }

    @Test
    void readsCommandLineOfThisProcessThroughTheRealOs() throws Exception {
        PortProcessProbe probe = new PortProcessProbe();
        var details = probe.details(SELF);
        assertTrue(details.alive());
        // 自己的进程总能读到命令行：Windows 走 PowerShell，Linux 读 /proc，macOS 用 ps。
        assertFalse(details.commandLine().isEmpty(), details.toString());
        assertFalse(details.executable().isEmpty(), details.toString());
    }

    // ==========================================
    // 结束进程
    // ==========================================

    @Test
    void refusesGuardedPidsBeforeTouchingAnything() throws Exception {
        FakeRunner runner = new FakeRunner();
        PortProcessProbe windows = probe(OsFamily.WINDOWS, runner, false, StandardCharsets.UTF_8);
        for (long pid : new long[]{0, 4}) {
            KillResult result = windows.kill(pid, true, false);
            assertEquals(KillResult.Outcome.REFUSED, result.outcome());
            assertEquals(ProcessKillPolicy.Refusal.SYSTEM_PROCESS, result.refusal());
        }
        assertEquals(ProcessKillPolicy.Refusal.SELF, windows.kill(SELF, true, true).refusal());

        PortProcessProbe linux = probe(OsFamily.LINUX, runner, true, StandardCharsets.UTF_8);
        assertEquals(ProcessKillPolicy.Refusal.SYSTEM_PROCESS, linux.kill(1, true, false).refusal());
        assertEquals(ProcessKillPolicy.Refusal.INVALID_PID, linux.kill(0, false, false).refusal());
        assertEquals(ProcessKillPolicy.Refusal.SELF, linux.kill(SELF, false, false).refusal());
        assertTrue(runner.calls.isEmpty());
    }

    @Test
    void buildsTaskkillCommands() {
        assertEquals(List.of("taskkill", "/PID", "42"), PortProcessProbe.taskkillCommand(42, false, false));
        assertEquals(List.of("taskkill", "/PID", "42", "/T", "/F"), PortProcessProbe.taskkillCommand(42, true, true));
        assertTrue(PortProcessProbe.looksDenied("ERROR: ... Reason: Access is denied."));
        assertTrue(PortProcessProbe.looksDenied("kill: (1234) - Operation not permitted"));
        assertFalse(PortProcessProbe.looksDenied("SUCCESS"));
    }

    private static Process startSleeper(boolean wrapInShell) throws IOException {
        boolean windows = OsFamily.current().isWindows();
        List<String> command = new ArrayList<>();
        if (windows) {
            if (wrapInShell) {
                command.addAll(List.of("cmd", "/c"));
            }
            command.addAll(List.of("ping", "-n", "60", "127.0.0.1"));
        } else {
            if (wrapInShell) {
                command.addAll(List.of("sh", "-c", "sleep 60; true"));
            } else {
                command.addAll(List.of("sleep", "60"));
            }
        }
        return new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
    }

    @Test
    void killsARealChildProcessGracefullyThenForcibly() throws Exception {
        Process child = startSleeper(false);
        try {
            PortProcessProbe probe = new PortProcessProbe();
            KillResult graceful = probe.kill(child.pid(), false, false);
            // Unix 上 sleep 响应 SIGTERM；Windows 上无窗口的控制台程序只能被强制结束。
            assertTrue(graceful.outcome() == KillResult.Outcome.EXITED
                    || graceful.outcome() == KillResult.Outcome.STILL_RUNNING, graceful.toString());
            if (graceful.outcome() == KillResult.Outcome.STILL_RUNNING) {
                KillResult forced = probe.kill(child.pid(), true, false);
                assertEquals(KillResult.Outcome.EXITED, forced.outcome(), forced.toString());
                assertTrue(forced.forced());
            }
            assertFalse(child.isAlive());
            assertEquals(KillResult.Outcome.NOT_FOUND, probe.kill(child.pid(), true, false).outcome());
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void killsARealProcessTree() throws Exception {
        Process shell = startSleeper(true);
        try {
            // 等 shell 把子进程拉起来。
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (shell.descendants().findAny().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            List<ProcessHandle> children = shell.descendants().toList();
            assertFalse(children.isEmpty(), "sleeper did not start a child");

            KillResult result = new PortProcessProbe().kill(shell.pid(), true, true);
            assertEquals(KillResult.Outcome.EXITED, result.outcome(), result.toString());
            for (ProcessHandle child : children) {
                long wait = System.nanoTime() + Duration.ofSeconds(3).toNanos();
                while (child.isAlive() && System.nanoTime() < wait) {
                    Thread.sleep(50);
                }
                assertFalse(child.isAlive(), "child " + child.pid() + " survived");
            }
        } finally {
            shell.descendants().forEach(ProcessHandle::destroyForcibly);
            shell.destroyForcibly();
        }
    }

    // ==========================================
    // 端口是否空闲
    // ==========================================

    @Test
    void detectsPortsHeldByAnOpenServerSocket() throws Exception {
        PortProcessProbe probe = new PortProcessProbe();
        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            port = socket.getLocalPort();
            // 只绑了回环地址：不指定地址时也必须报告占用。
            assertEquals(PortCheck.Status.IN_USE, probe.checkPort(port, "").status());
            assertEquals(PortCheck.Status.IN_USE, probe.checkPort(port, "127.0.0.1").status());
            assertFalse(probe.isPortFree(port, null));
        }
        assertTrue(probe.isPortFree(port, "127.0.0.1"));
        assertNotEquals(PortCheck.Status.FREE, probe.checkPort(port, "192.0.2.1").status());
        assertThrows(IllegalArgumentException.class, () -> probe.checkPort(0, ""));
    }

    @Test
    void classifiesBindFailures() {
        assertEquals(PortCheck.Status.IN_USE,
                PortProcessProbe.classifyBindFailure(new BindException("Address already in use: bind")));
        assertEquals(PortCheck.Status.DENIED,
                PortProcessProbe.classifyBindFailure(new BindException("Permission denied")));
        assertEquals(PortCheck.Status.DENIED, PortProcessProbe.classifyBindFailure(new SocketException(
                "An attempt was made to access a socket in a way forbidden by its access permissions")));
        assertEquals(PortCheck.Status.INVALID_HOST,
                PortProcessProbe.classifyBindFailure(new BindException("Cannot assign requested address")));
    }

    // ==========================================
    // 真机冒烟
    // ==========================================

    @Test
    void liveSnapshotFindsAServerSocketWeOpened() throws Exception {
        PortProcessProbe probe = new PortProcessProbe();
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int port = socket.getLocalPort();
            PortSnapshot snapshot;
            try {
                snapshot = probe.snapshot(true);
            } catch (PortProcessProbe.ProbeException error) {
                org.junit.jupiter.api.Assumptions.assumeTrue(
                        error.code() != PortProcessProbe.ProbeException.Code.NO_TOOL,
                        "no port listing tool on this machine: " + error.getMessage());
                throw error;
            }
            List<PortEntry> ours = snapshot.entries().stream()
                    .filter(e -> e.protocol() == PortEntry.Protocol.TCP && e.localPort() == port).toList();
            assertFalse(ours.isEmpty(), "port " + port + " not found via " + snapshot.source());
            if (ours.get(0).hasPid()) {
                assertEquals(SELF, ours.get(0).pid());
            }
        }
    }
}
