package com.aqishi.toolbox.feature.system.infra;

import com.aqishi.toolbox.feature.system.domain.KillResult;
import com.aqishi.toolbox.feature.system.domain.OsFamily;
import com.aqishi.toolbox.feature.system.domain.PortCheck;
import com.aqishi.toolbox.feature.system.domain.PortEntry;
import com.aqishi.toolbox.feature.system.domain.PortSnapshot;
import com.aqishi.toolbox.feature.system.domain.PortTableParser;
import com.aqishi.toolbox.feature.system.domain.ProcessDetails;
import com.aqishi.toolbox.feature.system.domain.ProcessKillPolicy;
import com.aqishi.toolbox.infra.concurrency.DaemonThreads;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过系统命令查询「端口 → 进程」、查询进程命令行、结束进程、检查端口是否空闲。
 *
 * <p>命令选择：Linux 优先 {@code ss}（iproute2，现代发行版默认自带），没有再用 {@code lsof}，
 * 最后退到 net-tools 的 {@code netstat}；macOS 只有 {@code lsof} 能给出 PID；
 * Windows 用 {@code netstat -ano} 取 PID、{@code tasklist} 补进程名。</p>
 *
 * <p>所有命令都经 {@link CommandRunner} 执行：合并 stderr、关闭 stdin、硬超时后强杀整棵进程树、
 * 由独立线程持续读取输出以免管道写满导致子进程阻塞、输出超过上限只保留前段。测试注入假的
 * {@link CommandRunner} 与任意 {@link OsFamily}，即可覆盖各平台的选择与回退逻辑。</p>
 *
 * <p>本类的方法会阻塞（执行外部命令、等待进程退出），只能在后台线程调用。</p>
 */
public class PortProcessProbe {

    /** 单条命令的硬超时。 */
    public static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
    /** 查询命令行的超时：Windows 上要冷启动 PowerShell。 */
    public static final Duration COMMAND_LINE_TIMEOUT = Duration.ofSeconds(25);
    /** 命令输出上限；繁忙服务器上 {@code netstat -ano} 也不过几 MB。 */
    public static final int MAX_OUTPUT_BYTES = 16 * 1024 * 1024;
    /** 结束进程后等待其退出的时长。 */
    public static final Duration DEFAULT_KILL_WAIT = Duration.ofSeconds(3);
    /** netstat 完成后最多再等 tasklist 这么久，过期就先用 ProcessHandle 与上一轮结果补名字。 */
    static final Duration TASKLIST_GRACE = Duration.ofSeconds(3);

    private final OsFamily os;
    private final CommandRunner runner;
    private final long selfPid;
    private final boolean root;
    private final Duration killWait;
    private volatile Charset consoleCharset;
    private final AtomicReference<FutureTask<Map<Long, String>>> tasklistInFlight = new AtomicReference<>();
    /** 上一次成功的 tasklist 结果，tasklist 迟到时兜底。 */
    private volatile Map<Long, String> lastTasklist = Map.of();

    public PortProcessProbe() {
        this(OsFamily.current(), new SystemCommandRunner(), ProcessHandle.current().pid(),
                "root".equals(System.getProperty("user.name")), null, DEFAULT_KILL_WAIT);
    }

    /**
     * @param os             目标系统族
     * @param runner         命令执行器
     * @param selfPid        自身 PID，结束进程时据此拒绝自杀
     * @param root           是否以 root 运行（Unix），决定数据是否可能不完整
     * @param consoleCharset 命令输出的字符集；null 表示首次使用时自动判定
     * @param killWait       结束进程后等待退出的时长
     */
    public PortProcessProbe(OsFamily os, CommandRunner runner, long selfPid, boolean root,
                            Charset consoleCharset, Duration killWait) {
        this.os = Objects.requireNonNull(os, "os");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.selfPid = selfPid;
        this.root = root;
        this.consoleCharset = consoleCharset;
        this.killWait = Objects.requireNonNull(killWait, "killWait");
    }

    public OsFamily os() {
        return os;
    }

    /** 当前系统能否「结束进程树」：Windows 的 taskkill /T 是原生支持的。 */
    public boolean supportsTreeKill() {
        return os.isWindows();
    }

    // ==========================================
    // 端口列表
    // ==========================================

    /** 各系统按优先级排列的数据来源。 */
    public static List<PortSnapshot.Source> strategiesFor(OsFamily os) {
        return switch (os) {
            case WINDOWS -> List.of(PortSnapshot.Source.WINDOWS_NETSTAT);
            case LINUX -> List.of(PortSnapshot.Source.SS, PortSnapshot.Source.LSOF,
                    PortSnapshot.Source.LINUX_NETSTAT);
            case MAC, OTHER_UNIX -> List.of(PortSnapshot.Source.LSOF);
        };
    }

    /** 数据来源对应的命令行。{@code listeningOnly} 时让命令自己过滤，减少输出量。 */
    public static List<String> commandFor(PortSnapshot.Source source, boolean listeningOnly) {
        return switch (source) {
            case WINDOWS_NETSTAT -> List.of("netstat", "-ano");
            case SS -> listeningOnly ? List.of("ss", "-tulnp") : List.of("ss", "-tuanp");
            // +c 0：不截断 COMMAND（默认只显示 9 个字符）。
            case LSOF -> listeningOnly
                    ? List.of("lsof", "-nP", "+c", "0", "-iTCP", "-sTCP:LISTEN", "-iUDP")
                    : List.of("lsof", "-nP", "+c", "0", "-iTCP", "-iUDP");
            case LINUX_NETSTAT -> listeningOnly ? List.of("netstat", "-tulnp") : List.of("netstat", "-tuanp");
        };
    }

    /**
     * 采集一次端口快照。
     *
     * @param listeningOnly 只要监听中的 TCP 与未连接的 UDP
     * @throws ProbeException 所有候选命令都不可用或失败
     */
    public PortSnapshot snapshot(boolean listeningOnly) throws InterruptedException {
        if (os.isWindows()) {
            return windowsSnapshot(listeningOnly);
        }
        ProbeException lastFailure = null;
        for (PortSnapshot.Source source : strategiesFor(os)) {
            List<String> command = commandFor(source, listeningOnly);
            ExecResult result;
            try {
                result = runResolved(command);
            } catch (IOException notFound) {
                lastFailure = new ProbeException(ProbeException.Code.NO_TOOL, source,
                        String.join(" ", command) + ": " + notFound.getMessage());
                continue;
            }
            if (result.timedOut()) {
                lastFailure = new ProbeException(ProbeException.Code.TIMEOUT, source, String.join(" ", command));
                continue;
            }
            String text = decode(result.output());
            List<PortEntry> entries = parse(source, text);
            // lsof 没有匹配项时退出码为 1 且无输出，这是合法的「空结果」。
            boolean emptyOk = source == PortSnapshot.Source.LSOF && text.isBlank() && result.exitCode() == 1;
            if (result.exitCode() != 0 && entries.isEmpty() && !emptyOk) {
                lastFailure = new ProbeException(ProbeException.Code.COMMAND_FAILED, source,
                        abbreviate(text.trim(), 500));
                continue;
            }
            return finish(source, entries, listeningOnly);
        }
        throw lastFailure != null ? lastFailure
                : new ProbeException(ProbeException.Code.NO_TOOL, null, os.name());
    }

    private static final Comparator<PortEntry> ENTRY_ORDER = Comparator
            .comparingInt(PortEntry::localPort)
            .thenComparing(PortEntry::protocol)
            .thenComparing(PortEntry::localAddress)
            .thenComparingLong(PortEntry::pid)
            .thenComparing(PortEntry::remoteAddress)
            .thenComparingInt(PortEntry::remotePort);

    static List<PortEntry> parse(PortSnapshot.Source source, String text) {
        List<PortEntry> entries = switch (source) {
            case WINDOWS_NETSTAT -> PortTableParser.parseWindowsNetstat(text);
            case SS -> PortTableParser.parseSs(text, null);
            case LSOF -> PortTableParser.parseLsof(text);
            case LINUX_NETSTAT -> PortTableParser.parseLinuxNetstat(text);
        };
        return new ArrayList<>(entries);
    }

    /**
     * 权限缺口：root 运行时 ss/netstat 没有进程信息的是内核套接字（NFS 等），不算缺口；
     * 非 root 时 lsof 本身就只能看到自己的进程。Windows 的 {@code netstat -ano} 不需要管理员就能拿到全部 PID。
     */
    PortSnapshot.Limitation limitationOf(PortSnapshot.Source source, List<PortEntry> entries) {
        if (os.isWindows() || root) {
            return PortSnapshot.Limitation.NONE;
        }
        if (source == PortSnapshot.Source.LSOF) {
            return PortSnapshot.Limitation.OWN_PROCESSES_ONLY;
        }
        for (PortEntry entry : entries) {
            if (!entry.hasPid()) {
                return PortSnapshot.Limitation.MISSING_PROCESS_INFO;
            }
        }
        return PortSnapshot.Limitation.NONE;
    }

    private PortSnapshot finish(PortSnapshot.Source source, List<PortEntry> entries, boolean listeningOnly) {
        List<PortEntry> result = new ArrayList<>(entries);
        if (listeningOnly) {
            result.removeIf(entry -> !entry.isListening());
        }
        result = PortTableParser.distinct(result);
        result.sort(ENTRY_ORDER);
        return new PortSnapshot(result, source, limitationOf(source, result), Instant.now());
    }

    /**
     * Windows：netstat 与 tasklist 并行执行。
     *
     * <p>tasklist 在部分机器上（安全软件逐个扫描进程时）要好几秒，与 netstat 串行会让每次刷新都卡住；
     * 并行后耗时取两者较长者。tasklist 失败或超时不影响端口列表，缺的进程名再用
     * {@link ProcessHandle.Info#command()} 的文件名补——它对非管理员查询不到的系统服务无效，
     * 所以只作补充而不是首选。</p>
     */
    private PortSnapshot windowsSnapshot(boolean listeningOnly) throws InterruptedException {
        // 先在当前线程判定字符集，免得两条命令各自去执行一次 chcp。
        consoleCharset();
        FutureTask<Map<Long, String>> names = startTasklist();
        PortSnapshot.Source source = PortSnapshot.Source.WINDOWS_NETSTAT;
        List<String> command = commandFor(source, listeningOnly);
        ExecResult result;
        try {
            result = runner.run(command, COMMAND_TIMEOUT);
        } catch (IOException notFound) {
            throw new ProbeException(ProbeException.Code.NO_TOOL, source,
                    String.join(" ", command) + ": " + notFound.getMessage());
        }
        if (result.timedOut()) {
            throw new ProbeException(ProbeException.Code.TIMEOUT, source, String.join(" ", command));
        }
        String text = decode(result.output());
        List<PortEntry> entries = parse(source, text);
        if (result.exitCode() != 0 && entries.isEmpty()) {
            throw new ProbeException(ProbeException.Code.COMMAND_FAILED, source, abbreviate(text.trim(), 500));
        }
        Map<Long, String> fresh;
        try {
            fresh = names.get(TASKLIST_GRACE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException | CancellationException late) {
            fresh = Map.of();
        }
        entries = PortTableParser.attachProcessNames(entries, fresh);
        entries = PortTableParser.attachProcessNames(entries, handleNames(entries));
        // 最后才用上一轮 tasklist 的结果：PID 可能已被复用，只给实在查不到名字的记录兜底。
        entries = PortTableParser.attachProcessNames(entries, lastTasklist);
        return finish(source, entries, listeningOnly);
    }

    /**
     * 同一时刻只跑一个 tasklist：自动刷新每 3 秒一次，而 tasklist 慢起来要 10 秒，不加限制会越堆越多。
     * 超出等待期限的那次不取消，跑完后刷新 {@link #lastTasklist}，下一轮就能用上。
     */
    private FutureTask<Map<Long, String>> startTasklist() {
        FutureTask<Map<Long, String>> running = tasklistInFlight.get();
        if (running != null && !running.isDone()) {
            return running;
        }
        FutureTask<Map<Long, String>> task = new FutureTask<>(() -> {
            Map<Long, String> names = windowsProcessNames();
            if (!names.isEmpty()) {
                lastTasklist = names;
            }
            return names;
        });
        if (!tasklistInFlight.compareAndSet(running, task)) {
            return tasklistInFlight.get();
        }
        DaemonThreads.factory("port-probe-tasklist").newThread(task).start();
        return task;
    }

    /** tasklist 失败不影响端口列表本身，只是缺进程名。 */
    private Map<Long, String> windowsProcessNames() throws InterruptedException {
        try {
            ExecResult result = runner.run(List.of("tasklist", "/FO", "CSV", "/NH"), COMMAND_TIMEOUT);
            if (result.timedOut()) {
                return Map.of();
            }
            return PortTableParser.parseTasklistCsv(decode(result.output()));
        } catch (IOException error) {
            return Map.of();
        }
    }

    /** 用 {@link ProcessHandle} 给仍缺名字的 PID 补上可执行文件名。 */
    private static Map<Long, String> handleNames(List<PortEntry> entries) {
        Map<Long, String> names = new HashMap<>();
        for (PortEntry entry : entries) {
            if (!entry.hasPid() || !entry.processName().isEmpty() || names.containsKey(entry.pid())) {
                continue;
            }
            String command = safeHandle(entry.pid())
                    .flatMap(handle -> {
                        try {
                            return handle.info().command();
                        } catch (RuntimeException error) {
                            return Optional.empty();
                        }
                    })
                    .orElse("");
            int slash = Math.max(command.lastIndexOf('\\'), command.lastIndexOf('/'));
            names.put(entry.pid(), command.substring(slash + 1));
        }
        names.values().removeIf(String::isEmpty);
        return names;
    }

    // ==========================================
    // 进程详情
    // ==========================================

    /** 查询进程的可执行文件、命令行等信息；进程不存在时 {@code alive} 为 false。 */
    public ProcessDetails details(long pid) throws InterruptedException {
        return withCommandLine(basicDetails(pid));
    }

    /** 给 {@link #basicDetails} 的结果补上命令行（慢：Windows 上要启动 PowerShell）。 */
    public ProcessDetails withCommandLine(ProcessDetails basic) throws InterruptedException {
        if (!basic.alive()) {
            return basic;
        }
        String commandLine = commandLine(basic.pid());
        if (commandLine.isEmpty()) {
            commandLine = basic.commandLine();
        }
        return new ProcessDetails(basic.pid(), basic.alive(), basic.executable(), commandLine, basic.user(),
                basic.startTime(), basic.cpuTime(), basic.parentPid());
    }

    /**
     * 只用 {@link ProcessHandle} 查询（进程内完成，毫秒级），不含可靠的命令行：
     * Unix 上 {@code commandLine} 是 JDK 给出的近似值，Windows 上为空。
     */
    public ProcessDetails basicDetails(long pid) {
        Optional<ProcessHandle> handle = safeHandle(pid);
        boolean alive = handle.map(ProcessHandle::isAlive).orElse(false);
        String executable = "";
        String user = "";
        Instant start = null;
        Duration cpu = null;
        long parent = PortEntry.NO_PID;
        String fallbackCommandLine = "";
        if (handle.isPresent()) {
            try {
                ProcessHandle.Info info = handle.get().info();
                executable = info.command().orElse("");
                user = info.user().orElse("");
                start = info.startInstant().orElse(null);
                cpu = info.totalCpuDuration().orElse(null);
                // Windows 上 Info 拿不到参数，commandLine 只是可执行文件本身，只作最后退路。
                fallbackCommandLine = info.commandLine().orElse("");
                parent = handle.get().parent().map(ProcessHandle::pid).orElse(PortEntry.NO_PID);
            } catch (RuntimeException ignored) {
                // 个别平台对受保护进程的查询会抛异常：保留已拿到的字段。
            }
        }
        String commandLine = os.isWindows() ? "" : fallbackCommandLine;
        return new ProcessDetails(pid, alive, executable, commandLine, user, start, cpu, parent);
    }

    /** 查询完整命令行；拿不到（进程已退出、无权读取）时返回空串。 */
    public String commandLine(long pid) throws InterruptedException {
        if (pid <= 0) {
            return "";
        }
        if (os.isWindows()) {
            String value = windowsCommandLine(pid);
            if (!value.isEmpty()) {
                return value;
            }
            return runQuietly(List.of("wmic", "process", "where", "ProcessId=" + pid, "get", "CommandLine"));
        }
        if (os == OsFamily.LINUX) {
            try {
                byte[] bytes = Files.readAllBytes(Path.of("/proc", String.valueOf(pid), "cmdline"));
                String value = PortTableParser.parseNulSeparated(new String(bytes, StandardCharsets.UTF_8));
                if (!value.isEmpty()) {
                    return value;
                }
            } catch (IOException | RuntimeException ignored) {
                // 没有 /proc 或无权读取：改用 ps。
            }
        }
        return runQuietly(List.of("ps", "-o", "args=", "-p", String.valueOf(pid)));
    }

    /**
     * 用 PowerShell 查询 Win32_Process 的命令行，结果按 UTF-8 做 Base64 输出——这样完全绕开控制台代码页，
     * 路径里的中文、日文都能原样还原。Windows PowerShell 5.1 里 Get-WmiObject 比 Get-CimInstance 少加载
     * 一个模块、明显更快，不可用时再用 CIM。PowerShell 冷启动在装了安全软件的机器上要好几秒，
     * 因此单独用更长的 {@link #COMMAND_LINE_TIMEOUT}。wmic 在新版 Windows 上已被移除，只作退路。
     */
    static List<String> windowsCommandLineCommand(long pid) {
        String filter = "'ProcessId=" + pid + "'";
        String script = "try{$c=(Get-WmiObject Win32_Process -Filter " + filter + " -ErrorAction Stop).CommandLine}"
                + "catch{$c=(Get-CimInstance Win32_Process -Filter " + filter + ").CommandLine};"
                + "if($c){[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($c))}";
        return List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
    }

    private String windowsCommandLine(long pid) throws InterruptedException {
        try {
            ExecResult result = runner.run(windowsCommandLineCommand(pid), COMMAND_LINE_TIMEOUT);
            if (result.timedOut()) {
                return "";
            }
            return decodeBase64Line(new String(result.output(), StandardCharsets.US_ASCII));
        } catch (IOException error) {
            return "";
        }
    }

    /** 取输出中最后一个合法的 Base64 行并按 UTF-8 解码；PowerShell 的错误信息不会是合法 Base64。 */
    static String decodeBase64Line(String output) {
        String[] lines = output.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            if (line.isEmpty() || !line.matches("[A-Za-z0-9+/]+={0,2}") || line.length() % 4 != 0) {
                continue;
            }
            try {
                return new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8).trim();
            } catch (IllegalArgumentException ignored) {
                // 不是 Base64：继续往前找。
            }
        }
        return "";
    }

    private String runQuietly(List<String> command) throws InterruptedException {
        try {
            ExecResult result = runResolved(command);
            if (result.timedOut() || result.exitCode() != 0) {
                return "";
            }
            return PortTableParser.parseCommandLine(decode(result.output()));
        } catch (IOException error) {
            return "";
        }
    }

    // ==========================================
    // 结束进程
    // ==========================================

    /** 护栏检查，界面可在弹确认框之前调用。 */
    public Optional<ProcessKillPolicy.Refusal> checkKillable(long pid) {
        return ProcessKillPolicy.check(pid, os, selfPid);
    }

    /**
     * 结束进程并等待其退出。
     *
     * <p>优先用 {@link ProcessHandle}：它直接调用 kill(2) / TerminateProcess，返回值能区分
     * 「信号发出去了」和「无权操作」，不必解析本地化的错误文字。Windows 上 {@link ProcessHandle#destroy()}
     * 其实也是强杀（{@code supportsNormalTermination()} 为 false），所以温和结束改走
     * {@code taskkill /PID}（发送关闭请求）；进程树同样交给 {@code taskkill /T}。</p>
     *
     * @param force 强制结束（SIGKILL / TerminateProcess）
     * @param tree  连同子进程一起结束
     */
    public KillResult kill(long pid, boolean force, boolean tree) throws InterruptedException {
        Optional<ProcessKillPolicy.Refusal> refusal = checkKillable(pid);
        if (refusal.isPresent()) {
            return KillResult.refused(pid, refusal.get());
        }
        Optional<ProcessHandle> found = safeHandle(pid);
        if (found.isEmpty() || !found.get().isAlive()) {
            return new KillResult(pid, KillResult.Outcome.NOT_FOUND, null, force, "");
        }
        ProcessHandle handle = found.get();
        // Windows 上 destroy() 与 destroyForcibly() 等价，温和结束只能靠 taskkill。
        boolean normalTermination = handle.supportsNormalTermination();
        if (os.isWindows() && (tree || (!force && !normalTermination))) {
            return killWithTaskkill(handle, force, tree);
        }
        return killWithHandle(handle, force, tree);
    }

    private KillResult killWithHandle(ProcessHandle handle, boolean force, boolean tree) throws InterruptedException {
        long pid = handle.pid();
        boolean sent;
        String detail = "";
        try {
            if (tree) {
                // 先子后父：父进程先退出的话子进程会被 init 收养，再也找不到归属。
                handle.descendants().forEach(child -> {
                    if (ProcessKillPolicy.check(child.pid(), os, selfPid).isEmpty()) {
                        destroy(child, force);
                    }
                });
            }
            sent = destroy(handle, force);
        } catch (UnsupportedOperationException | SecurityException | IllegalStateException unsupported) {
            // 取不到句柄能力时退回系统命令。
            if (os.isWindows()) {
                return killWithTaskkill(handle, force, tree);
            }
            ExecResult result = runSignal(pid, force);
            sent = result != null && result.exitCode() == 0;
            detail = result == null ? unsupported.getMessage() : decode(result.output()).trim();
            if (!sent && looksDenied(detail)) {
                return new KillResult(pid, KillResult.Outcome.ACCESS_DENIED, null, force, detail);
            }
        }
        if (awaitExit(handle)) {
            return new KillResult(pid, KillResult.Outcome.EXITED, null, force, detail);
        }
        if (!sent) {
            // kill(2) 返回 EPERM / OpenProcess 失败，而进程仍在：几乎总是权限不足。
            return new KillResult(pid, KillResult.Outcome.ACCESS_DENIED, null, force, detail);
        }
        return new KillResult(pid, KillResult.Outcome.STILL_RUNNING, null, force, detail);
    }

    private KillResult killWithTaskkill(ProcessHandle handle, boolean force, boolean tree) throws InterruptedException {
        long pid = handle.pid();
        String detail;
        int exit;
        try {
            ExecResult result = runner.run(taskkillCommand(pid, force, tree), COMMAND_TIMEOUT);
            detail = decode(result.output()).trim();
            exit = result.timedOut() ? -1 : result.exitCode();
        } catch (IOException error) {
            detail = error.getMessage();
            exit = -1;
        }
        if (awaitExit(handle)) {
            return new KillResult(pid, KillResult.Outcome.EXITED, null, force, detail);
        }
        if (force) {
            // taskkill /F 没杀掉：用 TerminateProcess 的返回值确认是否是权限问题，不去解析本地化的错误文字。
            boolean terminated;
            try {
                terminated = handle.destroyForcibly();
            } catch (RuntimeException error) {
                terminated = false;
            }
            if (terminated && awaitExit(handle)) {
                return new KillResult(pid, KillResult.Outcome.EXITED, null, true, detail);
            }
            if (!terminated) {
                return new KillResult(pid, KillResult.Outcome.ACCESS_DENIED, null, true, detail);
            }
        } else if (exit != 0 && looksDenied(detail)) {
            return new KillResult(pid, KillResult.Outcome.ACCESS_DENIED, null, false, detail);
        }
        return new KillResult(pid, KillResult.Outcome.STILL_RUNNING, null, force, detail);
    }

    static List<String> taskkillCommand(long pid, boolean force, boolean tree) {
        List<String> command = new ArrayList<>(List.of("taskkill", "/PID", String.valueOf(pid)));
        if (tree) {
            command.add("/T");
        }
        if (force) {
            command.add("/F");
        }
        return command;
    }

    private ExecResult runSignal(long pid, boolean force) throws InterruptedException {
        try {
            return runner.run(List.of("kill", force ? "-9" : "-15", String.valueOf(pid)), COMMAND_TIMEOUT);
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean destroy(ProcessHandle handle, boolean force) {
        return force ? handle.destroyForcibly() : handle.destroy();
    }

    /** 英文系统下的权限错误文字；本地化系统靠 ProcessHandle 的返回值判断，这里只是补充。 */
    static boolean looksDenied(String output) {
        if (output == null) {
            return false;
        }
        String text = output.toLowerCase(Locale.ROOT);
        return text.contains("access is denied") || text.contains("operation not permitted")
                || text.contains("permission denied") || text.contains("access denied");
    }

    private boolean awaitExit(ProcessHandle handle) throws InterruptedException {
        long deadline = System.nanoTime() + killWait.toNanos();
        while (handle.isAlive()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(100);
        }
        return true;
    }

    private static Optional<ProcessHandle> safeHandle(long pid) {
        if (pid < 0) {
            return Optional.empty();
        }
        try {
            return ProcessHandle.of(pid);
        } catch (RuntimeException error) {
            return Optional.empty();
        }
    }

    // ==========================================
    // 端口是否空闲
    // ==========================================

    /** {@link #checkPort} 的简化形式：只有 {@link PortCheck.Status#FREE} 才算空闲。 */
    public boolean isPortFree(int port, String host) {
        return checkPort(port, host).status() == PortCheck.Status.FREE;
    }

    /**
     * 尝试以独占方式绑定一个 TCP 监听套接字（{@code SO_REUSEADDR=false}）。
     *
     * <p>未指定地址时依次尝试通配地址与回环地址：Linux 上别人只绑了 127.0.0.1 时通配绑定会失败，
     * Windows 上却可能成功，只查一个会误报「空闲」。</p>
     */
    public PortCheck checkPort(int port, String host) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port out of range: " + port);
        }
        List<InetSocketAddress> targets = new ArrayList<>();
        String label;
        if (host == null || host.isBlank()) {
            targets.add(new InetSocketAddress(port));
            targets.add(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            label = "*";
        } else {
            label = host.trim();
            try {
                targets.add(new InetSocketAddress(InetAddress.getByName(label), port));
            } catch (UnknownHostException error) {
                return new PortCheck(port, label, PortCheck.Status.INVALID_HOST, error.getMessage());
            }
        }
        for (InetSocketAddress target : targets) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(target, 1);
            } catch (IOException error) {
                return new PortCheck(port, label, classifyBindFailure(error), String.valueOf(error.getMessage()));
            }
        }
        return new PortCheck(port, label, PortCheck.Status.FREE, "");
    }

    static PortCheck.Status classifyBindFailure(IOException error) {
        String message = String.valueOf(error.getMessage()).toLowerCase(Locale.ROOT);
        if (message.contains("permission denied") || message.contains("access permissions")
                || message.contains("forbidden")) {
            return PortCheck.Status.DENIED;
        }
        if (message.contains("assign requested address") || message.contains("not valid in its context")) {
            return PortCheck.Status.INVALID_HOST;
        }
        return PortCheck.Status.IN_USE;
    }

    // ==========================================
    // 命令执行
    // ==========================================

    /**
     * Unix 上非 root 用户的 PATH 常常不含 /usr/sbin、/sbin（Debian 系），ss、lsof 就装在那里；
     * 按名字找不到时再试这几个固定位置。
     */
    private ExecResult runResolved(List<String> command) throws IOException, InterruptedException {
        try {
            return runner.run(command, COMMAND_TIMEOUT);
        } catch (IOException notFound) {
            if (os.isWindows()) {
                throw notFound;
            }
            for (String dir : List.of("/usr/sbin/", "/sbin/", "/usr/bin/", "/bin/")) {
                List<String> absolute = new ArrayList<>(command);
                absolute.set(0, dir + command.get(0));
                try {
                    return runner.run(absolute, COMMAND_TIMEOUT);
                } catch (IOException ignored) {
                    // 继续下一个位置。
                }
            }
            throw notFound;
        }
    }

    private String decode(byte[] bytes) throws InterruptedException {
        return ConsoleCharsets.decode(bytes, consoleCharset());
    }

    /** 命令输出的字符集，首次使用时判定并缓存；判定方法见 {@link ConsoleCharsets}。 */
    Charset consoleCharset() throws InterruptedException {
        Charset charset = consoleCharset;
        if (charset != null) {
            return charset;
        }
        if (os.isWindows()) {
            try {
                ExecResult result = runner.run(List.of("cmd", "/c", "chcp"), COMMAND_TIMEOUT);
                OptionalInt codePage = ConsoleCharsets.parseCodePage(
                        new String(result.output(), StandardCharsets.ISO_8859_1));
                if (codePage.isPresent()) {
                    charset = ConsoleCharsets.forCodePage(codePage.getAsInt());
                }
            } catch (IOException ignored) {
                // 没有 cmd：退回系统属性。
            }
        }
        if (charset == null) {
            charset = ConsoleCharsets.fromProperties(System::getProperty);
        }
        consoleCharset = charset;
        return charset;
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** 执行外部命令的抽象，测试时替换为返回固定输出的假实现。 */
    @FunctionalInterface
    public interface CommandRunner {
        /**
         * @throws IOException 命令无法启动（通常是不存在）
         */
        ExecResult run(List<String> command, Duration timeout) throws IOException, InterruptedException;
    }

    /**
     * 命令执行结果。
     *
     * @param exitCode  退出码，超时时为 -1
     * @param output    合并后的 stdout + stderr 原始字节
     * @param timedOut  是否超时被强杀
     * @param truncated 输出是否超过上限被截断
     */
    public record ExecResult(int exitCode, byte[] output, boolean timedOut, boolean truncated) {
        public ExecResult {
            output = output == null ? new byte[0] : output;
        }

        public static ExecResult of(int exitCode, String output) {
            return new ExecResult(exitCode, output.getBytes(StandardCharsets.UTF_8), false, false);
        }
    }

    /** 所有候选命令都失败时抛出；{@link #getMessage()} 是英文诊断，界面按 {@link #code()} 翻译。 */
    public static final class ProbeException extends RuntimeException {
        public enum Code {
            /** 系统上没有可用的命令。 */
            NO_TOOL,
            /** 命令超时。 */
            TIMEOUT,
            /** 命令执行失败。 */
            COMMAND_FAILED
        }

        private final Code code;
        private final PortSnapshot.Source source;
        private final String detail;

        public ProbeException(Code code, PortSnapshot.Source source, String detail) {
            super(code + (source == null ? "" : " (" + source + ")") + ": " + detail);
            this.code = Objects.requireNonNull(code, "code");
            this.source = source;
            this.detail = detail == null ? "" : detail;
        }

        public Code code() {
            return code;
        }

        public PortSnapshot.Source source() {
            return source;
        }

        public String detail() {
            return detail;
        }
    }

    /**
     * 基于 {@link ProcessBuilder} 的默认实现。
     *
     * <p>输出由单独的守护线程读取：子进程写满管道缓冲（Windows 上只有 4 KB）后会阻塞，
     * 若主线程只在 {@code waitFor} 之后再读就会互相等死。超过上限的部分继续读但丢弃，
     * 保证子进程能写完退出。超时或调用线程被中断时强杀整棵进程树。</p>
     */
    public static final class SystemCommandRunner implements CommandRunner {

        private final int maxBytes;

        public SystemCommandRunner() {
            this(MAX_OUTPUT_BYTES);
        }

        public SystemCommandRunner(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public ExecResult run(List<String> command, Duration timeout) throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // stdin 关不掉不影响读取输出。
            }
            Collector collector = new Collector(process.getInputStream(), maxBytes);
            Thread reader = DaemonThreads.factory("port-probe-output").newThread(collector);
            reader.start();
            boolean finished;
            try {
                finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                destroyTree(process);
                throw interrupted;
            }
            if (!finished) {
                destroyTree(process);
                process.waitFor(2, TimeUnit.SECONDS);
            }
            // 孙进程若继承了管道，进程退出后读线程仍可能阻塞：最多再等两秒，拿到多少算多少。
            reader.join(2000);
            return new ExecResult(finished ? process.exitValue() : -1, collector.bytes(), !finished,
                    collector.truncated());
        }

        private static void destroyTree(Process process) {
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (RuntimeException ignored) {
                // 枚举子进程失败时至少杀掉直接子进程。
            }
            process.destroyForcibly();
        }
    }

    private static final class Collector implements Runnable {
        private final InputStream in;
        private final int maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private boolean truncated;

        Collector(InputStream in, int maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (InputStream stream = in) {
                int read;
                while ((read = stream.read(chunk)) >= 0) {
                    synchronized (this) {
                        int room = maxBytes - buffer.size();
                        if (room > 0) {
                            buffer.write(chunk, 0, Math.min(room, read));
                        }
                        if (read > room) {
                            truncated = true;
                        }
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时管道断开，已读到的部分照常返回。
            }
        }

        synchronized byte[] bytes() {
            return buffer.toByteArray();
        }

        synchronized boolean truncated() {
            return truncated;
        }
    }
}
