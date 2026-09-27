package com.aqishi.toolbox.feature.system.domain;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.concurrent.CancellationException;

/**
 * GC 日志解析器：JDK 9+ 统一日志（任意 {@code -Xlog} 装饰组合）与 JDK 8 {@code -XX:+PrintGCDetails}。
 *
 * <p>逐行流式解析，不要求把整份日志读进内存——生产环境的 gc.log 常有几百兆。噪音行（应用输出、
 * 截断的行）只计数并保留少量样本，不会中断解析；轮转后拼接的多个文件、甚至多次 JVM 运行的日志
 * 都能接成一条连续时间轴。</p>
 *
 * <p>无状态、线程安全：每次 {@code parse} 都新建内部状态。</p>
 */
public final class GcLogParser {

    /** 读文件时的大小上限。 */
    public static final long MAX_INPUT_BYTES = 256L * 1024 * 1024;
    /** 每解析这么多行检查一次中断：后台任务被取消时尽快让出线程。 */
    private static final int CANCEL_CHECK_INTERVAL = 4096;
    private static final char BOM = (char) 0xFEFF;

    public GcLog parse(String text) {
        try {
            return parse(new StringReader(text == null ? "" : text));
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    /**
     * 解析整个输入。调用方负责关闭 {@code source}。
     *
     * @throws CancellationException 解析线程被中断
     */
    public GcLog parse(Reader source) throws IOException {
        GcParseState state = new GcParseState();
        GcUnifiedHandler unified = new GcUnifiedHandler(state);
        GcLegacyHandler legacy = new GcLegacyHandler(state);
        BufferedReader reader = source instanceof BufferedReader buffered ? buffered : new BufferedReader(source, 1 << 16);
        String raw;
        long lineNo = 0;
        while ((raw = reader.readLine()) != null) {
            lineNo++;
            state.totalLines++;
            if (lineNo % CANCEL_CHECK_INTERVAL == 0 && Thread.currentThread().isInterrupted()) {
                throw new CancellationException("GC log parsing cancelled");
            }
            // 拼接的日志里每个文件开头都可能带 BOM。
            String line = !raw.isEmpty() && raw.charAt(0) == BOM ? raw.substring(1) : raw;
            if (line.isBlank()) {
                state.ignored(1);
                continue;
            }
            GcParseState.Stamp stamp = GcDecorations.parse(line);
            if (stamp == null && GcUnifiedHandler.looksBare(line.strip())) {
                stamp = new GcParseState.Stamp();
                stamp.body = line.strip();
            }
            if (stamp != null) {
                legacy.flush();
                state.resolve(stamp);
                unified.handle(stamp, lineNo);
            } else {
                legacy.accept(line, lineNo);
            }
        }
        legacy.flush();
        unified.finish();
        return state.finish();
    }

    /** 标记输入被截断（读文件时超过上限）。 */
    public static GcLog markTruncated(GcLog log) {
        return new GcLog(log.collector(), log.family(), log.jvmVersion(), log.events(), log.phases(),
                log.safepoints(), log.stalls(), log.firstTime(), log.lastTime(), log.restarts(), log.regionSizeKb(),
                log.totalLines(), log.ignoredLines(), log.unparsedLines(), log.unparsedSample(), true);
    }
}
