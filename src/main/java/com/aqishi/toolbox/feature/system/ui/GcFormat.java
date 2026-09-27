package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.GcAdvice;
import com.aqishi.toolbox.feature.system.domain.GcEvent;
import com.aqishi.toolbox.feature.system.domain.GcLog;
import com.aqishi.toolbox.feature.system.domain.GcReport;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import java.awt.Color;
import java.util.Locale;

/**
 * GC 分析界面共用的格式化与本地化：耗时、容量、时长、枚举文案与配色。
 *
 * <p>数字统一按 {@link Locale#ROOT} 格式化后再作为字符串传给 {@link I18n}，
 * 避免 MessageFormat 按区域插入千分位。</p>
 */
final class GcFormat {

    private GcFormat() {
    }

    static String ms(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        if (value >= 1000) {
            return String.format(Locale.ROOT, "%.0f ms", value);
        }
        if (value >= 10) {
            return String.format(Locale.ROOT, "%.1f ms", value);
        }
        return String.format(Locale.ROOT, "%.3f ms", value);
    }

    /** KB 为单位的容量。 */
    static String size(double kb) {
        if (!Double.isFinite(kb) || kb < 0) {
            return "-";
        }
        if (kb >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.2f GB", kb / (1024 * 1024));
        }
        if (kb >= 1024) {
            return String.format(Locale.ROOT, "%.1f MB", kb / 1024);
        }
        return String.format(Locale.ROOT, "%.0f KB", kb);
    }

    static String rate(double kbPerSec) {
        return Double.isFinite(kbPerSec) ? size(kbPerSec) + "/s" : "-";
    }

    static String percent(double ratio) {
        return Double.isFinite(ratio) ? String.format(Locale.ROOT, "%.2f%%", ratio * 100) : "-";
    }

    /** 时长：秒 / 分 / 时。 */
    static String duration(double seconds) {
        if (!Double.isFinite(seconds)) {
            return "-";
        }
        if (seconds < 120) {
            return String.format(Locale.ROOT, "%.1f s", seconds);
        }
        if (seconds < 7200) {
            return String.format(Locale.ROOT, "%.1f min", seconds / 60);
        }
        return String.format(Locale.ROOT, "%.2f h", seconds / 3600);
    }

    static String seconds(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, "%.3f", value) : "-";
    }

    static String heap(GcEvent event) {
        if (!event.hasHeap()) {
            return "-";
        }
        String text = size(event.heapBeforeKb()) + " -> " + size(event.heapAfterKb());
        return event.heapCommittedKb() >= 0 ? text + " (" + size(event.heapCommittedKb()) + ")" : text;
    }

    static String cause(String cause) {
        return cause == null ? I18n.get("tool.gclog.value.noCause") : cause;
    }

    static String collector(GcLog.Collector collector) {
        switch (collector) {
            case G1:
                return "G1";
            case PARALLEL:
                return "Parallel";
            case SERIAL:
                return "Serial";
            case CMS:
                return "CMS";
            case ZGC:
                return "ZGC";
            case ZGC_GENERATIONAL:
                return I18n.get("tool.gclog.collector.zgcGenerational");
            case SHENANDOAH:
                return "Shenandoah";
            case EPSILON:
                return "Epsilon";
            default:
                return I18n.get("tool.gclog.collector.unknown");
        }
    }

    static String family(GcLog log) {
        String family;
        switch (log.family()) {
            case JDK8_OR_EARLIER:
                family = I18n.get("tool.gclog.family.jdk8");
                break;
            case JDK9_PLUS:
                family = I18n.get("tool.gclog.family.jdk9");
                break;
            default:
                family = I18n.get("tool.gclog.family.unknown");
        }
        return log.jvmVersion() == null ? family : family + " (" + log.jvmVersion() + ")";
    }

    static String category(GcEvent.Category category) {
        switch (category) {
            case YOUNG:
                return I18n.get("tool.gclog.category.young");
            case MIXED:
                return I18n.get("tool.gclog.category.mixed");
            case FULL:
                return I18n.get("tool.gclog.category.full");
            case CYCLE:
                return I18n.get("tool.gclog.category.cycle");
            case DEGENERATED:
                return I18n.get("tool.gclog.category.degenerated");
            default:
                return I18n.get("tool.gclog.category.other");
        }
    }

    static String phaseKind(GcReport.PhaseKind kind) {
        return kind == GcReport.PhaseKind.CONCURRENT ? I18n.get("tool.gclog.phase.concurrent")
                : I18n.get("tool.gclog.phase.step");
    }

    static String severity(GcAdvice.Severity severity) {
        switch (severity) {
            case CRITICAL:
                return I18n.get("tool.gclog.severity.critical");
            case WARNING:
                return I18n.get("tool.gclog.severity.warning");
            default:
                return I18n.get("tool.gclog.severity.info");
        }
    }

    static Color severityColor(GcAdvice.Severity severity) {
        switch (severity) {
            case CRITICAL:
                return Tokens.danger();
            case WARNING:
                return Tokens.warning();
            default:
                return Tokens.accent();
        }
    }

    /** 图表配色：每次绘制时取当前主题的色值。 */
    static Color categoryColor(GcEvent.Category category) {
        switch (category) {
            case YOUNG:
                return Tokens.accent();
            case MIXED:
                return Tokens.success();
            case FULL:
                return Tokens.danger();
            case CYCLE:
                return Tokens.warning();
            case DEGENERATED:
                return Tokens.shift(Tokens.danger(), Tokens.isDark() ? 0.35f : -0.35f);
            default:
                return Tokens.mutedForeground();
        }
    }
}
