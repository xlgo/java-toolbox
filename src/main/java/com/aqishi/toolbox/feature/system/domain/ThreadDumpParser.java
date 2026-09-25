package com.aqishi.toolbox.feature.system.domain;

import com.aqishi.toolbox.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HotSpot 线程转储解析器（纯函数，无 Swing 依赖）。
 *
 * <p>支持的来源：</p>
 * <ul>
 *   <li>{@code jstack [-l] <pid>}、{@code jcmd <pid> Thread.print}、{@code kill -3} 写到 stdout 的文本，
 *       前后可以夹杂日志；CRLF / LF 均可。</li>
 *   <li>多份转储首尾相接（按 {@code Full thread dump} 切分，各自带前一行的时间戳）。</li>
 *   <li>{@code ThreadMXBean} / Spring Actuator 的 {@code "name" Id=N STATE} 文本（尽力而为）。</li>
 *   <li>JDK 21 {@code jcmd <pid> Thread.dump_to_file} 的 text 与 {@code -format=json} 两种格式。</li>
 * </ul>
 *
 * <p>解析是逐行状态机：线程头开启一个线程，之后的状态行、栈帧、锁行归属于它；
 * 空行之后只接受 {@code Locked ownable synchronizers} 小节，其他内容一律视为线程结束——
 * 否则转储后面日志里的异常栈（同样以 {@code at} 开头）会被错挂到最后一个线程上。</p>
 */
public final class ThreadDumpParser {

    /** 单次解析允许的最大输入字节数，与面板读文件的上限一致。 */
    public static final long MAX_INPUT_BYTES = 64L * 1024 * 1024;

    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\r|\n");

    private static final String DUMP_HEADER = "Full thread dump";

    /** 独占一行的时间戳：jstack 为 {@code 2024-01-01 12:00:00}，jcmd JSON / text 为 ISO-8601。 */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^\\s*(\\d{4}-\\d{2}-\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?)\\s*$");

    /** 线程头里紧跟在名字后面的第一个字段，用来判断哪一个引号才是名字的结束。 */
    private static final Pattern HEADER_TOKEN = Pattern.compile(
            "^\\s+(?:#\\d+|daemon(?:\\s|$)|virtual(?:\\s|$)|prio=|os_prio=|cpu=|elapsed=|tid=|nid=|Id=\\d|\\[\\d+\\])");
    private static final Pattern OWNED_BY_SUFFIX = Pattern.compile("\\s+owned by \".*$");

    private static final Pattern NUMBER = Pattern.compile("(?:^|\\s)#(\\d+)(?=\\s|$)");
    private static final Pattern DAEMON = Pattern.compile("(?:^|\\s)daemon(?=\\s|$)");
    private static final Pattern VIRTUAL = Pattern.compile("(?:^|\\s)virtual(?=\\s|$)");
    private static final Pattern PRIO = Pattern.compile("(?:^|\\s)prio=(-?\\d+)");
    private static final Pattern OS_PRIO = Pattern.compile("(?:^|\\s)os_prio=(-?\\d+)");
    private static final Pattern CPU = Pattern.compile("(?:^|\\s)cpu=(\\S+)");
    private static final Pattern ELAPSED = Pattern.compile("(?:^|\\s)elapsed=(\\S+)");
    private static final Pattern TID = Pattern.compile("(?:^|\\s)tid=(\\S+)");
    private static final Pattern NID = Pattern.compile("(?:^|\\s)nid=(\\S+)");
    private static final Pattern MX_ID = Pattern.compile("(?:^|\\s)Id=(\\d+)");
    private static final Pattern TRAILING_SP = Pattern.compile("\\s*\\[0x[0-9a-fA-F]+]\\s*$");

    /** JDK 21 Thread.dump_to_file 文本格式的线程头：{@code #1 "main"} / {@code #23 "" virtual}。 */
    private static final Pattern PLAIN_HEADER = Pattern.compile("^#(\\d+)\\s+\"(.*)\"(\\s+virtual)?\\s*$");

    private static final Pattern STATE_LINE = Pattern.compile(
            "^\\s*java\\.lang\\.Thread\\.State:\\s*([A-Za-z_]+)(?:\\s*\\((.*)\\))?\\s*$");
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(\\S.*)$");
    private static final Pattern LOCK = Pattern.compile(
            "^\\s*-\\s+(locked|waiting to lock|waiting to re-lock in wait\\(\\)|waiting on"
                    + "|parking to wait for|eliminated)\\s+<([^>]*)>(?:\\s*\\(a\\s+(.*?)\\))?\\s*$");
    /** ThreadMXBean 文本的锁行：{@code -  blocked on java.lang.Object@1b6d3586}。 */
    private static final Pattern MX_LOCK = Pattern.compile(
            "^\\s*-\\s+(blocked on|waiting on|locked|parking to wait for)\\s+([^\\s<>]+@[0-9a-fA-F]+)\\s*$");
    private static final Pattern OWNABLE_HEADER = Pattern.compile(
            "^\\s*(?:Locked ownable synchronizers:|Number of locked synchronizers\\s*=\\s*\\d+)\\s*$");
    private static final Pattern OWNABLE_ENTRY = Pattern.compile(
            "^\\s*-\\s+<([^>]*)>(?:\\s*\\(a\\s+(.*?)\\))?\\s*$");
    private static final Pattern MX_OWNABLE_ENTRY = Pattern.compile("^\\s*-\\s+([^\\s<>]+@[0-9a-fA-F]+)\\s*$");
    private static final Pattern HEX_ADDRESS = Pattern.compile("^0x([0-9a-fA-F]+)$");

    private static final Pattern DL_START = Pattern.compile("^\\s*Found one Java-level deadlock:");
    private static final Pattern DL_END = Pattern.compile(
            "^\\s*Found (?:\\d+|a total of \\d+) (?:Java-level )?deadlocks?\\.?\\s*$");
    private static final Pattern DL_STACK = Pattern.compile(
            "^\\s*Java stack information for the threads listed above:");
    private static final Pattern DL_ENTRY = Pattern.compile("^\\s*\"(.*)\":\\s*$");
    private static final Pattern DL_MONITOR = Pattern.compile(
            "waiting to lock monitor\\s+(0x[0-9a-fA-F]+)"
                    + "(?:\\s*\\(object\\s+(0x[0-9a-fA-F]+),\\s*a\\s+([^)]*)\\))?");
    private static final Pattern DL_SYNC = Pattern.compile(
            "waiting for ownable synchronizer\\s+(0x[0-9a-fA-F]+),?\\s*(?:\\(a\\s+([^)]*)\\))?");
    private static final Pattern DL_HELD_NAMED = Pattern.compile("which is held by\\s+\"(.*)\"");

    private enum Mode { NONE, THREAD, OWNABLE, PLAIN_THREAD, DEADLOCK, DEADLOCK_STACK }

    /**
     * 解析文本或 JSON 转储。
     *
     * @return 找到的转储，没有任何线程时返回空列表
     * @throws IllegalArgumentException 输入看起来是 JSON 转储但无法解析
     */
    public List<ThreadDump> parse(String input) {
        if (input == null || input.isBlank()) {
            return List.of();
        }
        String trimmed = input.strip();
        if (trimmed.startsWith("{") && trimmed.contains("\"threadDump\"")) {
            return parseJson(trimmed);
        }
        return new TextParse().run(LINE_BREAK.split(input, -1));
    }

    // ==========================================
    // 文本格式
    // ==========================================

    /** 单次文本解析的可变状态；解析器本身保持无状态，可在多个线程里复用。 */
    private static final class TextParse {
        private final List<ThreadDump> dumps = new ArrayList<>();
        private DumpBuilder dump = new DumpBuilder();
        private ThreadBuilder thread;
        private Mode mode = Mode.NONE;
        private boolean sawBlankInThread;
        private DeadlockBuilder deadlock;
        private ThreadDump.Link pendingLink;
        private String pendingTimestamp;
        private int pendingTimestampLine = -10;

        List<ThreadDump> run(String[] lines) {
            for (int i = 0; i < lines.length; i++) {
                handle(lines[i], i);
            }
            finishThread();
            finishDeadlock();
            flushDump();
            List<ThreadDump> result = new ArrayList<>();
            for (ThreadDump candidate : dumps) {
                if (!candidate.threads().isEmpty()) {
                    result.add(new ThreadDump(result.size(), candidate.timestamp(), candidate.vmInfo(),
                            candidate.threads(), candidate.jvmDeadlocks()));
                }
            }
            return result;
        }

        private void handle(String line, int index) {
            int headerAt = line.indexOf(DUMP_HEADER);
            if (headerAt >= 0) {
                startDump(line.substring(headerAt + DUMP_HEADER.length()), index);
                return;
            }
            Matcher timestamp = TIMESTAMP.matcher(line);
            if (timestamp.matches()) {
                finishThread();
                pendingTimestamp = timestamp.group(1);
                pendingTimestampLine = index;
                return;
            }
            ThreadBuilder header = parseHeader(line);
            if (header != null) {
                finishThread();
                finishDeadlock();
                thread = header;
                mode = Mode.THREAD;
                sawBlankInThread = false;
                return;
            }
            Matcher plain = PLAIN_HEADER.matcher(line);
            if (plain.matches() && (mode == Mode.NONE || mode == Mode.PLAIN_THREAD)) {
                finishThread();
                thread = new ThreadBuilder();
                thread.number = Long.parseLong(plain.group(1));
                thread.name = plain.group(2);
                thread.virtual = plain.group(3) != null;
                thread.forceJava = true;
                mode = Mode.PLAIN_THREAD;
                return;
            }
            if (DL_START.matcher(line).find()) {
                finishThread();
                finishDeadlock();
                deadlock = new DeadlockBuilder();
                mode = Mode.DEADLOCK;
                return;
            }
            switch (mode) {
                case THREAD:
                case OWNABLE:
                    handleThreadLine(line, index);
                    break;
                case PLAIN_THREAD:
                    if (line.isBlank() || !Character.isWhitespace(line.charAt(0))) {
                        finishThread();
                    } else {
                        thread.frames.add(new FrameBuilder(line.trim()));
                    }
                    break;
                case DEADLOCK:
                    handleDeadlockLine(line);
                    break;
                case DEADLOCK_STACK:
                    if (DL_END.matcher(line).find()) {
                        mode = Mode.NONE;
                    }
                    break;
                default:
                    break;
            }
        }

        private void startDump(String vmInfo, int index) {
            finishThread();
            finishDeadlock();
            if (!dump.threads.isEmpty() || dump.headerSeen) {
                flushDump();
            }
            dump.headerSeen = true;
            String info = vmInfo.trim();
            if (info.endsWith(":")) {
                info = info.substring(0, info.length() - 1).trim();
            }
            dump.vmInfo = info.isEmpty() ? null : info;
            // jcmd 会在时间戳前多打一行 pid，但时间戳总是紧挨在头部之前一两行。
            if (pendingTimestamp != null && index - pendingTimestampLine <= 2) {
                dump.timestamp = pendingTimestamp;
            }
            pendingTimestamp = null;
            mode = Mode.NONE;
        }

        private void handleThreadLine(String line, int index) {
            if (line.isBlank()) {
                if (mode == Mode.OWNABLE) {
                    finishThread();
                } else {
                    sawBlankInThread = true;
                }
                return;
            }
            if (OWNABLE_HEADER.matcher(line).matches()) {
                mode = Mode.OWNABLE;
                return;
            }
            if (mode == Mode.OWNABLE) {
                if (addOwnable(line)) {
                    return;
                }
                finishThread();
                handle(line, index);
                return;
            }
            if (sawBlankInThread) {
                // 空行之后只有 ownable 小节属于本线程，其余内容说明线程已结束。
                finishThread();
                handle(line, index);
                return;
            }
            Matcher state = STATE_LINE.matcher(line);
            if (state.matches()) {
                thread.state = ThreadState.parse(state.group(1));
                thread.stateDetail = state.group(2) == null ? null : state.group(2).trim();
                thread.sawStateLine = true;
                return;
            }
            Matcher frame = FRAME.matcher(line);
            if (frame.matches()) {
                thread.frames.add(new FrameBuilder(frame.group(1).trim()));
                return;
            }
            ThreadInfo.Lock lock = parseLockLine(line);
            if (lock != null) {
                if (!thread.frames.isEmpty()) {
                    thread.frames.get(thread.frames.size() - 1).locks.add(lock);
                }
                return;
            }
            if (!Character.isWhitespace(line.charAt(0))) {
                // 顶格的未知行：JNI refs、日志、Heap 小节等，线程到此结束。
                finishThread();
                handle(line, index);
            }
            // 缩进的未知行（No compile task、Carrying virtual thread、MXBean 的 "..."）忽略即可。
        }

        private boolean addOwnable(String line) {
            String trimmed = line.trim();
            if (trimmed.equals("- None")) {
                return true;
            }
            Matcher entry = OWNABLE_ENTRY.matcher(line);
            if (entry.matches()) {
                String address = normalizeAddress(entry.group(1));
                if (address != null) {
                    thread.ownable.add(new ThreadInfo.Lock(ThreadInfo.LockKind.OWNABLE,
                            address, trimToNull(entry.group(2))));
                }
                return true;
            }
            Matcher mx = MX_OWNABLE_ENTRY.matcher(line);
            if (mx.matches()) {
                thread.ownable.add(new ThreadInfo.Lock(ThreadInfo.LockKind.OWNABLE,
                        mx.group(1), classOfIdentity(mx.group(1))));
                return true;
            }
            return false;
        }

        private void handleDeadlockLine(String line) {
            if (DL_STACK.matcher(line).find()) {
                finishDeadlock();
                mode = Mode.DEADLOCK_STACK;
                return;
            }
            if (DL_END.matcher(line).find()) {
                finishDeadlock();
                mode = Mode.NONE;
                return;
            }
            Matcher entry = DL_ENTRY.matcher(line);
            if (entry.matches()) {
                flushLink();
                pendingLink = new ThreadDump.Link(entry.group(1), null, null, null, null);
                return;
            }
            if (pendingLink == null) {
                return;
            }
            Matcher monitor = DL_MONITOR.matcher(line);
            if (monitor.find()) {
                String address = normalizeAddress(monitor.group(2) != null ? monitor.group(2) : monitor.group(1));
                pendingLink = new ThreadDump.Link(pendingLink.threadName(), address,
                        trimToNull(monitor.group(3)), ThreadInfo.LockKind.WAITING_TO_LOCK, pendingLink.ownerName());
                return;
            }
            Matcher sync = DL_SYNC.matcher(line);
            if (sync.find()) {
                pendingLink = new ThreadDump.Link(pendingLink.threadName(), normalizeAddress(sync.group(1)),
                        trimToNull(sync.group(2)), ThreadInfo.LockKind.PARKING, pendingLink.ownerName());
                return;
            }
            Matcher held = DL_HELD_NAMED.matcher(line);
            if (held.find()) {
                pendingLink = new ThreadDump.Link(pendingLink.threadName(), pendingLink.lockAddress(),
                        pendingLink.lockClass(), pendingLink.waitKind(), held.group(1));
            }
        }

        private void flushLink() {
            if (pendingLink != null && deadlock != null) {
                deadlock.links.add(pendingLink);
            }
            pendingLink = null;
        }

        private void finishDeadlock() {
            flushLink();
            if (deadlock != null && !deadlock.links.isEmpty()) {
                dump.jvmDeadlocks.add(new ThreadDump.JvmDeadlock(deadlock.links));
            }
            deadlock = null;
        }

        private void finishThread() {
            if (thread != null) {
                // 没有 Full thread dump 头的格式（JDK 21 dump_to_file 文本）把时间戳写在文件开头。
                if (dump.threads.isEmpty() && !dump.headerSeen && dump.timestamp == null) {
                    dump.timestamp = pendingTimestamp;
                }
                dump.threads.add(thread.build());
            }
            thread = null;
            sawBlankInThread = false;
            if (mode == Mode.THREAD || mode == Mode.OWNABLE || mode == Mode.PLAIN_THREAD) {
                mode = Mode.NONE;
            }
        }

        private void flushDump() {
            dumps.add(new ThreadDump(dumps.size(), dump.timestamp, dump.vmInfo, dump.threads, dump.jvmDeadlocks));
            dump = new DumpBuilder();
        }
    }

    /**
     * 解析线程头；不是线程头时返回 {@code null}。
     *
     * <p>线程名允许包含引号，因此不能简单取第一个或最后一个引号：依次尝试每个
     * 「后面紧跟线程头字段」的引号，取剩余部分不再含引号的第一个
     * （MXBean 格式末尾的 {@code owned by "x"} 除外）。</p>
     */
    static ThreadBuilder parseHeader(String line) {
        int start = 0;
        while (start < line.length() && Character.isWhitespace(line.charAt(start))) {
            start++;
        }
        if (start >= line.length() || line.charAt(start) != '"') {
            return null;
        }
        int fallback = -1;
        int chosen = -1;
        for (int quote = line.indexOf('"', start + 1); quote > 0; quote = line.indexOf('"', quote + 1)) {
            String rest = line.substring(quote + 1);
            if (!HEADER_TOKEN.matcher(rest).find()) {
                continue;
            }
            if (fallback < 0) {
                fallback = quote;
            }
            if (OWNED_BY_SUFFIX.matcher(rest).replaceFirst("").indexOf('"') < 0) {
                chosen = quote;
                break;
            }
        }
        if (chosen < 0) {
            chosen = fallback;
        }
        if (chosen < 0) {
            return null;
        }
        ThreadBuilder builder = new ThreadBuilder();
        builder.name = line.substring(start + 1, chosen);
        String rest = line.substring(chosen + 1).trim();

        Matcher matcher = NUMBER.matcher(rest);
        if (matcher.find()) {
            builder.number = Long.parseLong(matcher.group(1));
        }
        builder.daemon = DAEMON.matcher(rest).find();
        builder.virtual = VIRTUAL.matcher(rest).find();
        builder.priority = intOf(PRIO, rest);
        builder.osPriority = intOf(OS_PRIO, rest);
        builder.cpu = groupOf(CPU, rest);
        builder.elapsed = groupOf(ELAPSED, rest);
        builder.tid = groupOf(TID, rest);

        Matcher nid = NID.matcher(rest);
        Matcher mxId = MX_ID.matcher(rest);
        String tail = null;
        if (nid.find()) {
            builder.nid = nid.group(1);
            tail = rest.substring(nid.end());
        } else if (mxId.find()) {
            // MXBean 格式没有 tid / nid，只有 Java 线程 ID。
            builder.tid = mxId.group(1);
            tail = rest.substring(mxId.end());
            builder.forceJava = true;
        }
        if (tail != null) {
            String state = TRAILING_SP.matcher(tail).replaceFirst("").trim();
            builder.headerState = state.isEmpty() ? null : state;
        }
        return builder;
    }

    private static ThreadInfo.Lock parseLockLine(String line) {
        Matcher lock = LOCK.matcher(line);
        if (lock.matches()) {
            ThreadInfo.LockKind kind;
            switch (lock.group(1)) {
                case "locked":
                    kind = ThreadInfo.LockKind.LOCKED;
                    break;
                case "waiting to lock":
                    kind = ThreadInfo.LockKind.WAITING_TO_LOCK;
                    break;
                case "waiting on":
                    kind = ThreadInfo.LockKind.WAITING_ON;
                    break;
                case "parking to wait for":
                    kind = ThreadInfo.LockKind.PARKING;
                    break;
                case "eliminated":
                    kind = ThreadInfo.LockKind.ELIMINATED;
                    break;
                default:
                    kind = ThreadInfo.LockKind.WAITING_TO_RELOCK;
                    break;
            }
            return new ThreadInfo.Lock(kind, normalizeAddress(lock.group(2)), trimToNull(lock.group(3)));
        }
        Matcher mx = MX_LOCK.matcher(line);
        if (mx.matches()) {
            ThreadInfo.LockKind kind;
            switch (mx.group(1)) {
                case "blocked on":
                    kind = ThreadInfo.LockKind.WAITING_TO_LOCK;
                    break;
                case "waiting on":
                    kind = ThreadInfo.LockKind.WAITING_ON;
                    break;
                case "parking to wait for":
                    kind = ThreadInfo.LockKind.PARKING;
                    break;
                default:
                    kind = ThreadInfo.LockKind.LOCKED;
                    break;
            }
            return new ThreadInfo.Lock(kind, mx.group(2), classOfIdentity(mx.group(2)));
        }
        return null;
    }

    // ==========================================
    // JSON 格式（JDK 21+ jcmd Thread.dump_to_file -format=json）
    // ==========================================

    private List<ThreadDump> parseJson(String json) {
        JsonNode root;
        try {
            root = Json.mapper().readTree(json);
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid JSON thread dump: " + error.getMessage(), error);
        }
        JsonNode dumpNode = root.path("threadDump");
        if (!dumpNode.isObject()) {
            throw new IllegalArgumentException("JSON thread dump has no threadDump object");
        }
        List<ThreadBuilder> builders = new ArrayList<>();
        Map<String, ThreadBuilder> byTid = new HashMap<>();
        // parkBlocker.owner 给的是持有者 tid，要等全部线程读完才能把同步器挂到持有者身上。
        List<String[]> pendingOwnership = new ArrayList<>();
        for (JsonNode container : dumpNode.path("threadContainers")) {
            for (JsonNode node : container.path("threads")) {
                ThreadBuilder builder = new ThreadBuilder();
                builder.name = node.path("name").asText("");
                builder.tid = textOrNull(node.get("tid"));
                builder.virtual = node.path("virtual").asBoolean(false);
                builder.forceJava = true;
                String state = textOrNull(node.get("state"));
                if (state != null) {
                    builder.state = ThreadState.parse(state);
                    builder.sawStateLine = true;
                }
                for (JsonNode frame : node.path("stack")) {
                    builder.frames.add(new FrameBuilder(frame.asText("")));
                }
                if (!builder.frames.isEmpty()) {
                    List<ThreadInfo.Lock> top = builder.frames.get(0).locks;
                    addJsonLock(top, ThreadInfo.LockKind.WAITING_TO_LOCK, textOrNull(node.get("blockedOn")));
                    addJsonLock(top, ThreadInfo.LockKind.WAITING_ON, textOrNull(node.get("waitingOn")));
                    JsonNode blocker = node.get("parkBlocker");
                    String blockerObject = blocker == null ? null
                            : blocker.isObject() ? textOrNull(blocker.get("object")) : textOrNull(blocker);
                    addJsonLock(top, ThreadInfo.LockKind.PARKING, blockerObject);
                    if (blocker != null && blocker.isObject() && blockerObject != null) {
                        String owner = textOrNull(blocker.get("owner"));
                        if (owner != null) {
                            pendingOwnership.add(new String[]{owner, blockerObject});
                        }
                    }
                    for (JsonNode owned : node.path("monitorsOwned")) {
                        int depth = owned.path("depth").asInt(0);
                        FrameBuilder target = builder.frames.get(
                                Math.max(0, Math.min(depth, builder.frames.size() - 1)));
                        for (JsonNode lock : owned.path("locks")) {
                            addJsonLock(target.locks, ThreadInfo.LockKind.LOCKED, textOrNull(lock));
                        }
                    }
                }
                builders.add(builder);
                if (builder.tid != null) {
                    byTid.putIfAbsent(builder.tid, builder);
                }
            }
        }
        for (String[] ownership : pendingOwnership) {
            ThreadBuilder owner = byTid.get(ownership[0]);
            if (owner != null) {
                owner.ownable.add(new ThreadInfo.Lock(ThreadInfo.LockKind.OWNABLE,
                        ownership[1], classOfIdentity(ownership[1])));
            }
        }
        if (builders.isEmpty()) {
            return List.of();
        }
        List<ThreadInfo> threads = new ArrayList<>();
        for (ThreadBuilder builder : builders) {
            threads.add(builder.build());
        }
        return List.of(new ThreadDump(0, textOrNull(dumpNode.get("time")),
                textOrNull(dumpNode.get("runtimeVersion")), threads, List.of()));
    }

    private static void addJsonLock(List<ThreadInfo.Lock> target, ThreadInfo.LockKind kind, String identity) {
        if (identity != null) {
            target.add(new ThreadInfo.Lock(kind, identity, classOfIdentity(identity)));
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText("");
        return text.isEmpty() ? null : text;
    }

    // ==========================================
    // 字节解码
    // ==========================================

    /**
     * 解码后的文本及实际使用的字符集。
     *
     * @param text    文本
     * @param charset 使用的字符集
     */
    public record Decoded(String text, Charset charset) {
    }

    /**
     * 把文件字节解码为文本：先认 BOM，再尝试严格 UTF-8，失败时退回系统本地编码。
     *
     * <p>Windows PowerShell 5 用 {@code jstack pid > a.txt} 重定向出来的是带 BOM 的 UTF-16LE，
     * 必须先认 BOM，否则会被当成满屏乱码。JDK 18 起 {@code file.encoding} 默认就是 UTF-8，
     * 所以回退时读 {@code native.encoding}（GBK 等）才是真正的平台编码。</p>
     */
    public static Decoded decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new Decoded("", StandardCharsets.UTF_8);
        }
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE),
                    StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE),
                    StandardCharsets.UTF_16BE);
        }
        // 无 BOM 的 UTF-16LE：ASCII 为主的文本每隔一个字节就是 0。
        if (bytes.length >= 4 && bytes[0] != 0 && bytes[1] == 0 && bytes[2] != 0 && bytes[3] == 0) {
            return new Decoded(new String(bytes, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE);
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return new Decoded(text, StandardCharsets.UTF_8);
        } catch (CharacterCodingException notUtf8) {
            Charset fallback = nativeCharset();
            return new Decoded(new String(bytes, fallback), fallback);
        }
    }

    private static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null) {
            try {
                return Charset.forName(name);
            } catch (RuntimeException ignored) {
                // 属性值无效时退回默认编码。
            }
        }
        return Charset.defaultCharset();
    }

    // ==========================================
    // 工具
    // ==========================================

    /** 地址规范化：小写、去前导零，保证 {@code <0x0000...ab>} 与 {@code 0xab} 能对上；非地址文本返回 null。 */
    static String normalizeAddress(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        Matcher hex = HEX_ADDRESS.matcher(text);
        if (hex.matches()) {
            String digits = hex.group(1).toLowerCase(Locale.ROOT).replaceFirst("^0+(?=.)", "");
            return "0x" + digits;
        }
        if (text.indexOf('@') > 0 && text.indexOf(' ') < 0) {
            return text;
        }
        return null;
    }

    private static String classOfIdentity(String identity) {
        int at = identity.lastIndexOf('@');
        return at > 0 ? identity.substring(0, at) : null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static int intOf(Pattern pattern, String text) {
        String value = groupOf(pattern, text);
        if (value == null) {
            return -1;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static String groupOf(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static final class DumpBuilder {
        String timestamp;
        String vmInfo;
        boolean headerSeen;
        final List<ThreadInfo> threads = new ArrayList<>();
        final List<ThreadDump.JvmDeadlock> jvmDeadlocks = new ArrayList<>();
    }

    private static final class DeadlockBuilder {
        final List<ThreadDump.Link> links = new ArrayList<>();
    }

    private static final class FrameBuilder {
        final String text;
        final List<ThreadInfo.Lock> locks = new ArrayList<>();

        FrameBuilder(String text) {
            this.text = text;
        }
    }

    static final class ThreadBuilder {
        String name = "";
        long number = -1;
        boolean daemon;
        boolean virtual;
        int priority = -1;
        int osPriority = -1;
        String cpu;
        String elapsed;
        String tid;
        String nid;
        String headerState;
        ThreadState state = ThreadState.NONE;
        String stateDetail;
        boolean sawStateLine;
        boolean forceJava;
        final List<FrameBuilder> frames = new ArrayList<>();
        final List<ThreadInfo.Lock> ownable = new ArrayList<>();

        ThreadInfo build() {
            ThreadState finalState = state;
            if (!sawStateLine && headerState != null) {
                // MXBean 格式把状态写在头部：「Id=12 BLOCKED on ...」。
                String first = headerState.split("\\s+", 2)[0];
                ThreadState fromHeader = ThreadState.parse(first);
                if (fromHeader != ThreadState.NONE && first.equals(first.toUpperCase(Locale.ROOT))) {
                    finalState = fromHeader;
                }
            }
            List<ThreadInfo.Frame> builtFrames = new ArrayList<>(frames.size());
            for (FrameBuilder frame : frames) {
                builtFrames.add(new ThreadInfo.Frame(frame.text, frame.locks));
            }
            boolean java = sawStateLine || forceJava || !frames.isEmpty() || number >= 0;
            return new ThreadInfo(name, number, daemon, virtual, priority, osPriority, cpu, elapsed,
                    tid, nid, headerState, finalState, stateDetail, builtFrames, ownable, java);
        }
    }
}
