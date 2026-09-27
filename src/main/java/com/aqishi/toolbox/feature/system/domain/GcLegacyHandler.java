package com.aqishi.toolbox.feature.system.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDK 8 及更早的 {@code -XX:+PrintGCDetails} 日志：Parallel、Serial、CMS、G1。
 *
 * <p>这种格式没有 GC 编号，一次回收可能跨多行：G1 的明细缩进在暂停行之后；CMS 的
 * {@code concurrent mode failure} 被并发阶段的输出从中间截断到下一行；
 * {@code -XX:+PrintTenuringDistribution} 在 {@code [ParNew} 与冒号之间插入年龄表。
 * 所以先按「以时间戳开头的行」切分成条目，续行（缩进、方括号未闭合、年龄表）并入当前条目，
 * 条目收齐后再整体解析。</p>
 */
final class GcLegacyHandler {

    /** 单个条目的行数上限：损坏的日志里方括号可能永远不闭合，不能无限吞行。 */
    private static final int MAX_ENTRY_LINES = 400;

    private static final Pattern STAMP = Pattern.compile(
            "^(?:(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?):\\s*)?"
                    + "(?:(" + GcText.NUM + "):\\s*)?");
    private static final Pattern TOP_LEVEL_GC = Pattern.compile(
            "^(?:\\d{4}-\\d{2}-\\d{2}T[\\d:.,]+(?:Z|[+-]\\d{2}:?\\d{2})?:\\s*)?(?:" + GcText.NUM
                    + ":\\s*)?\\[(?:GC|Full GC)[ (\\[]");
    private static final String[] HEADERS = {"Java HotSpot", "OpenJDK", "Memory:", "CommandLine flags:", "Heap",
            "{Heap", "}"};
    private static final String TRIPLE = GcText.HEAP_TRIPLE.pattern();
    private static final Pattern DURATION = Pattern.compile(",\\s*(" + GcText.NUM + ")\\s*secs\\s*\\]");
    private static final Pattern TIMES = Pattern.compile(
            "\\[Times:\\s*user=(" + GcText.NUM + ")\\s+sys=(" + GcText.NUM + ")\\s*,\\s*real=(" + GcText.NUM
                    + ")\\s*secs\\s*\\]");
    private static final Pattern CMS_PHASE = Pattern.compile(
            "\\[CMS-concurrent-([a-z-]+):\\s*(" + GcText.NUM + ")/(" + GcText.NUM + ")\\s*secs\\s*\\]");
    private static final Pattern G1_CONCURRENT = Pattern.compile(
            "^\\[GC concurrent-([a-z-]+?)-(end|start|abort)(?:,\\s*(" + GcText.NUM + ")\\s*secs)?\\s*\\]");
    private static final Pattern META = Pattern.compile(
            "\\[(?:Metaspace|PSPermGen|CMS Perm|PS Perm|Perm)\\s*:\\s*" + TRIPLE);
    private static final Pattern GEN = Pattern.compile(
            "\\[(PSYoungGen|ParNew|DefNew|ASParNew|ParOldGen|PSOldGen|CMS|ASCMS|Tenured)(?:\\s*\\([^)]*\\))?\\s*:\\s*"
                    + TRIPLE);
    private static final Pattern G1_HEAP = Pattern.compile("Heap:\\s*" + TRIPLE);
    private static final Pattern G1_EDEN = Pattern.compile(
            "Eden:\\s*" + TRIPLE + "\\s+Survivors:\\s*(" + GcText.NUM + ")\\s?([BKMGT])\\s*->\\s*(" + GcText.NUM
                    + ")\\s?([BKMGT])");
    private static final Pattern OCCUPANCY = Pattern.compile(
            "\\]\\s*(" + GcText.NUM + ")\\s?([BKMGT])\\((" + GcText.NUM + ")\\s?([BKMGT])\\),\\s*" + GcText.NUM
                    + "\\s*secs\\s*\\]");
    private static final Pattern JRE_VERSION = Pattern.compile("JRE \\(([^)]+)\\)");

    private final GcParseState state;
    private StringBuilder entry;
    private long entryLine;
    private int entryLines;
    private int depth;
    private boolean headerEntry;

    GcLegacyHandler(GcParseState state) {
        this.state = state;
    }

    void accept(String line, long lineNo) {
        boolean starts = startsEntry(line);
        if (entry != null) {
            boolean continuation = depth > 0 ? !(starts && TOP_LEVEL_GC.matcher(line).find())
                    : !starts && isContinuation(line);
            if (continuation && entryLines < MAX_ENTRY_LINES) {
                append(line);
                return;
            }
            flush();
        }
        if (starts) {
            entry = new StringBuilder(line.strip());
            entryLine = lineNo;
            entryLines = 1;
            headerEntry = isHeader(line);
            depth = headerEntry ? 0 : balance(line);
        } else {
            state.unparsed(lineNo, line.strip());
        }
    }

    private void append(String line) {
        entryLines++;
        String stripped = line.strip();
        // 年龄表夹在 [ParNew 与冒号之间，去掉它们才能按普通分代格式匹配。
        if (stripped.startsWith("Desired survivor") || stripped.startsWith("- age")) {
            return;
        }
        entry.append('\n').append(stripped);
        if (!headerEntry) {
            depth += balance(line);
        }
    }

    private static int balance(String line) {
        int value = 0;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '[') {
                value++;
            } else if (ch == ']') {
                value--;
            }
        }
        return value;
    }

    private static boolean startsEntry(String line) {
        if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
            return false;
        }
        if (line.startsWith("[GC") || line.startsWith("[Full GC") || line.startsWith("[CMS") || isHeader(line)) {
            return true;
        }
        Matcher stamp = STAMP.matcher(line);
        return stamp.lookingAt() && stamp.end() > 0 && (stamp.group(1) != null || stamp.group(2) != null);
    }

    private static boolean isHeader(String line) {
        for (String header : HEADERS) {
            if (line.startsWith(header)) {
                return true;
            }
        }
        return false;
    }

    /** 已闭合的条目后面只接受明确的续行；其它顶格文本是噪音，单独计为无法识别。 */
    private boolean isContinuation(String line) {
        if (line.isEmpty()) {
            return false;
        }
        if (headerEntry) {
            return Character.isWhitespace(line.charAt(0));
        }
        return Character.isWhitespace(line.charAt(0)) || line.startsWith("[") || line.startsWith("Desired survivor")
                || line.startsWith("- age") || line.startsWith(":") || line.startsWith("(");
    }

    void flush() {
        if (entry == null) {
            return;
        }
        String text = entry.toString();
        long line = entryLine;
        int lines = entryLines;
        entry = null;
        Result result = parse(text, line);
        switch (result) {
            case PARSED -> state.legacyLines += lines;
            case IGNORED -> state.ignored(lines);
            default -> {
                state.unparsed(line, text.lines().findFirst().orElse(text));
                // 同一条目的其余行也计为无法识别，但样本只放首行。
                state.unparsedLines += lines - 1;
            }
        }
    }

    private enum Result { PARSED, IGNORED, UNPARSED }

    private Result parse(String text, long line) {
        if (isHeader(text)) {
            header(text);
            return Result.IGNORED;
        }
        Matcher stampMatcher = STAMP.matcher(text);
        GcParseState.Stamp stamp = new GcParseState.Stamp();
        String body = text;
        if (stampMatcher.lookingAt()) {
            if (stampMatcher.group(1) != null) {
                stamp.date = stampMatcher.group(1);
                stamp.epochMs = GcText.epochMillis(stamp.date);
            }
            if (stampMatcher.group(2) != null) {
                stamp.uptime = GcText.num(stampMatcher.group(2));
            }
            body = text.substring(stampMatcher.end());
        }
        state.resolve(stamp);
        if (state.trySafepoint(body, stamp.time)) {
            return Result.PARSED;
        }
        if (body.startsWith("Application time")) {
            return Result.IGNORED;
        }
        boolean any = false;
        Matcher cms = CMS_PHASE.matcher(text);
        while (cms.find()) {
            state.addPhase(-1, stamp.time, "CMS-concurrent-" + cms.group(1), GcText.num(cms.group(3)) * 1000);
            state.hint(GcLog.Collector.CMS);
            any = true;
        }
        if (body.startsWith("[CMS-concurrent")) {
            state.hint(GcLog.Collector.CMS);
            return Result.PARSED;
        }
        Matcher g1 = G1_CONCURRENT.matcher(body);
        if (g1.find()) {
            state.hint(GcLog.Collector.G1);
            if (g1.group(3) != null) {
                state.addPhase(-1, stamp.time, "concurrent-" + g1.group(1), GcText.num(g1.group(3)) * 1000);
            }
            return Result.PARSED;
        }
        if (body.startsWith("[GC") || body.startsWith("[Full GC")) {
            if (GcLegacyPause.parse(state, body, stamp, line)) {
                return Result.PARSED;
            }
        }
        return any ? Result.PARSED : Result.UNPARSED;
    }

    private void header(String text) {
        Matcher version = JRE_VERSION.matcher(text);
        if ((text.startsWith("Java HotSpot") || text.startsWith("OpenJDK")) && version.find()) {
            state.jvmVersion = version.group(1);
        }
        if (text.startsWith("CommandLine flags:")) {
            if (text.contains("+UseG1GC")) {
                state.declare(GcLog.Collector.G1);
            } else if (text.contains("+UseConcMarkSweepGC")) {
                state.declare(GcLog.Collector.CMS);
            } else if (text.contains("+UseParallelGC") || text.contains("+UseParallelOldGC")) {
                state.declare(GcLog.Collector.PARALLEL);
            } else if (text.contains("+UseSerialGC")) {
                state.declare(GcLog.Collector.SERIAL);
            }
        }
    }

    // ---------------------------------------------------------------- 供 GcLegacyPause 使用的正则

    static Pattern duration() {
        return DURATION;
    }

    static Pattern times() {
        return TIMES;
    }

    static Pattern meta() {
        return META;
    }

    static Pattern generation() {
        return GEN;
    }

    static Pattern g1Heap() {
        return G1_HEAP;
    }

    static Pattern g1Eden() {
        return G1_EDEN;
    }

    static Pattern occupancy() {
        return OCCUPANCY;
    }
}
