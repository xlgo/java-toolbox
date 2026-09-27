package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.GcAdvice;
import com.aqishi.toolbox.feature.system.domain.GcEvent;
import com.aqishi.toolbox.feature.system.domain.GcLog;
import com.aqishi.toolbox.feature.system.domain.GcReport;
import com.aqishi.toolbox.util.I18n;

import java.util.List;
import java.util.Locale;

/**
 * 建议文案与「复制报告」的纯文本：贴进工单或聊天时不依赖界面也能读懂。
 */
final class GcReportText {

    private static final int LIMIT = 20;
    private static final String RULE = "------------------------------------------";

    private GcReportText() {
    }

    // ---------------------------------------------------------------- 建议

    static String adviceTitle(GcAdvice advice) {
        Object[] params = advice.params().toArray();
        switch (advice.code()) {
            case EVACUATION_FAILURE:
                return I18n.get("tool.gclog.advice.evacuationFailure.title", params);
            case PROMOTION_FAILED:
                return I18n.get("tool.gclog.advice.promotionFailed.title", params);
            case CONCURRENT_MODE_FAILURE:
                return I18n.get("tool.gclog.advice.concurrentModeFailure.title", params);
            case ALLOCATION_STALL:
                return I18n.get("tool.gclog.advice.allocationStall.title", params);
            case FREQUENT_FULL_GC:
                return I18n.get("tool.gclog.advice.frequentFullGc.title", params);
            case DEGENERATED_GC:
                return I18n.get("tool.gclog.advice.degeneratedGc.title", params);
            case EXPLICIT_GC:
                return I18n.get("tool.gclog.advice.explicitGc.title", params);
            case HUMONGOUS_ALLOCATION:
                return I18n.get("tool.gclog.advice.humongous.title", params);
            case METASPACE_GC:
                return I18n.get("tool.gclog.advice.metaspace.title", params);
            case PAUSE_ABOVE_TARGET:
                return I18n.get("tool.gclog.advice.pauseTarget.title", params);
            case LOW_THROUGHPUT:
                return I18n.get("tool.gclog.advice.lowThroughput.title", params);
            case HEAP_FLOOR_RISING:
                return I18n.get("tool.gclog.advice.heapFloor.title", params);
            case LONG_TIME_TO_SAFEPOINT:
                return I18n.get("tool.gclog.advice.timeToSafepoint.title", params);
            default:
                return advice.code().name();
        }
    }

    static String adviceDetail(GcAdvice advice) {
        switch (advice.code()) {
            case EVACUATION_FAILURE:
                return I18n.get("tool.gclog.advice.evacuationFailure.detail");
            case PROMOTION_FAILED:
                return I18n.get("tool.gclog.advice.promotionFailed.detail");
            case CONCURRENT_MODE_FAILURE:
                return I18n.get("tool.gclog.advice.concurrentModeFailure.detail");
            case ALLOCATION_STALL:
                return I18n.get("tool.gclog.advice.allocationStall.detail");
            case FREQUENT_FULL_GC:
                return I18n.get("tool.gclog.advice.frequentFullGc.detail");
            case DEGENERATED_GC:
                return I18n.get("tool.gclog.advice.degeneratedGc.detail");
            case EXPLICIT_GC:
                return I18n.get("tool.gclog.advice.explicitGc.detail");
            case HUMONGOUS_ALLOCATION:
                return I18n.get("tool.gclog.advice.humongous.detail");
            case METASPACE_GC:
                return I18n.get("tool.gclog.advice.metaspace.detail");
            case PAUSE_ABOVE_TARGET:
                return I18n.get("tool.gclog.advice.pauseTarget.detail");
            case LOW_THROUGHPUT:
                return I18n.get("tool.gclog.advice.lowThroughput.detail");
            case HEAP_FLOOR_RISING:
                return I18n.get("tool.gclog.advice.heapFloor.detail");
            case LONG_TIME_TO_SAFEPOINT:
                return I18n.get("tool.gclog.advice.timeToSafepoint.detail");
            default:
                return "";
        }
    }

    // ---------------------------------------------------------------- 报告

    static String build(GcReport report) {
        GcLog log = report.log();
        GcReport.PauseStats pauses = report.pauses();
        StringBuilder text = new StringBuilder();
        text.append(I18n.get("tool.gclog.report.title")).append('\n');
        text.append("==========================================\n");
        line(text, "tool.gclog.report.collector", GcFormat.collector(log.collector()));
        line(text, "tool.gclog.report.jvm", GcFormat.family(log));
        line(text, "tool.gclog.report.span", GcFormat.duration(report.spanSeconds()));
        line(text, "tool.gclog.report.lines", String.valueOf(log.totalLines()), String.valueOf(log.unparsedLines()));
        line(text, "tool.gclog.report.throughput", GcFormat.percent(report.throughput()));
        line(text, "tool.gclog.report.pauses", String.valueOf(pauses.count()), GcFormat.ms(pauses.totalMs()),
                GcFormat.ms(pauses.avgMs()), GcFormat.ms(pauses.maxMs()));
        line(text, "tool.gclog.report.percentiles", GcFormat.ms(pauses.p50()), GcFormat.ms(pauses.p90()),
                GcFormat.ms(pauses.p95()), GcFormat.ms(pauses.p99()), GcFormat.ms(pauses.p999()));
        line(text, "tool.gclog.report.frequency", Double.isFinite(report.pausesPerMinute())
                ? String.format(Locale.ROOT, "%.2f", report.pausesPerMinute()) : "-");
        line(text, "tool.gclog.report.fullGc", String.valueOf(report.fullGcCount()));
        line(text, "tool.gclog.report.rates", GcFormat.rate(report.allocationRateKbPerSec()),
                GcFormat.rate(report.promotionRateKbPerSec()));
        GcReport.HeapTrend trend = report.heapTrend();
        if (trend != null) {
            line(text, "tool.gclog.report.trend", String.format(Locale.ROOT, "%.1f", trend.slopeKbPerHour() / 1024),
                    String.format(Locale.ROOT, "%.2f", trend.r2()), String.valueOf(trend.points()));
        }

        section(text, I18n.get("tool.gclog.report.byType"));
        for (GcReport.TypeStats stats : head(report.byType())) {
            text.append("  ").append(stats.type()).append("  x").append(stats.stats().count())
                    .append("  total ").append(GcFormat.ms(stats.stats().totalMs()))
                    .append("  avg ").append(GcFormat.ms(stats.stats().avgMs()))
                    .append("  p99 ").append(GcFormat.ms(stats.stats().p99()))
                    .append("  max ").append(GcFormat.ms(stats.stats().maxMs())).append('\n');
        }
        section(text, I18n.get("tool.gclog.report.causes"));
        for (GcReport.CauseStats stats : head(report.causes())) {
            text.append("  ").append(GcFormat.cause(stats.cause())).append("  x").append(stats.collections())
                    .append(stats.fullCount() > 0 ? "  (Full x" + stats.fullCount() + ")" : "")
                    .append("  ").append(GcFormat.ms(stats.totalPauseMs())).append('\n');
        }
        section(text, I18n.get("tool.gclog.report.longest", String.valueOf(LIMIT)));
        for (GcEvent event : head(report.longestPauses())) {
            text.append("  ").append(GcFormat.ms(event.durationMs())).append("  ").append(event.type())
                    .append(event.cause() == null ? "" : " (" + event.cause() + ")")
                    .append("  @ ").append(event.date() != null ? event.date() : GcFormat.seconds(event.time()) + "s")
                    .append("  ").append(GcFormat.heap(event)).append('\n');
        }
        section(text, I18n.get("tool.gclog.report.phases"));
        for (GcReport.PhaseStats stats : head(report.phases())) {
            text.append("  ").append(stats.name()).append("  [").append(GcFormat.phaseKind(stats.kind()))
                    .append("]  x").append(stats.count()).append("  avg ").append(GcFormat.ms(stats.avgMs()))
                    .append("  max ").append(GcFormat.ms(stats.maxMs())).append('\n');
        }
        GcReport.SafepointStats safepoints = report.safepoints();
        if (safepoints != null) {
            section(text, I18n.get("tool.gclog.tab.safepoints"));
            text.append("  ").append(I18n.get("tool.gclog.safepoint.summary", String.valueOf(safepoints.count()),
                    GcFormat.ms(safepoints.totalMs()), GcFormat.ms(safepoints.reach().p99()),
                    GcFormat.ms(safepoints.reach().maxMs()))).append('\n');
        }
        section(text, I18n.get("tool.gclog.tab.advice"));
        if (report.advice().isEmpty()) {
            text.append("  ").append(I18n.get("tool.gclog.advice.none")).append('\n');
        }
        for (GcAdvice advice : report.advice()) {
            text.append("  [").append(GcFormat.severity(advice.severity())).append("] ")
                    .append(adviceTitle(advice)).append('\n');
            text.append("      ").append(adviceDetail(advice)).append('\n');
        }
        return text.toString();
    }

    private static void line(StringBuilder text, String key, Object... args) {
        text.append(I18n.get(key, args)).append('\n');
    }

    private static void section(StringBuilder text, String title) {
        text.append('\n').append(title).append('\n').append(RULE).append('\n');
    }

    private static <T> List<T> head(List<T> items) {
        return items.size() <= LIMIT ? items : items.subList(0, LIMIT);
    }
}
