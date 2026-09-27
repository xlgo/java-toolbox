package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.GcLog;
import com.aqishi.toolbox.feature.system.domain.GcReport;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.util.function.Supplier;

/**
 * 结果区顶部的摘要条：收集器、版本、时长、吞吐量、停顿与 Full GC，按健康度着色。
 */
final class GcSummary {

    private GcSummary() {
    }

    static void render(JPanel summaryRow, GcReport source) {
        summaryRow.removeAll();
        if (source == null) {
            summaryRow.add(Fields.caption(I18n.get("tool.gclog.summary.empty")));
        } else {
            GcLog log = source.log();
            GcReport.PauseStats pauses = source.pauses();
            JLabel collector = summary(I18n.get("tool.gclog.summary.collector", GcFormat.collector(log.collector())),
                    Tokens::foreground);
            summaryRow.add(collector);
            summaryRow.add(summary(GcFormat.family(log), Tokens::mutedForeground));
            summaryRow.add(summary(I18n.get("tool.gclog.summary.span", GcFormat.duration(source.spanSeconds())),
                    Tokens::mutedForeground));
            double throughput = source.throughput();
            Supplier<Color> throughputColor = !Double.isFinite(throughput) ? Tokens::mutedForeground
                    : throughput < 0.90 ? Tokens::danger : throughput < 0.95 ? Tokens::warning : Tokens::success;
            summaryRow.add(summary(I18n.get("tool.gclog.summary.throughput", GcFormat.percent(throughput)),
                    throughputColor));
            summaryRow.add(summary(I18n.get("tool.gclog.summary.pauses", String.valueOf(pauses.count()),
                    GcFormat.ms(pauses.totalMs())), Tokens::mutedForeground));
            Supplier<Color> pauseColor = pauses.maxMs() > source.pauseTargetMs()
                    ? (pauses.p99() > source.pauseTargetMs() ? Tokens::danger : Tokens::warning) : Tokens::success;
            summaryRow.add(summary(I18n.get("tool.gclog.summary.maxPause", GcFormat.ms(pauses.maxMs()),
                    GcFormat.ms(pauses.p99())), pauseColor));
            Supplier<Color> fullColor = source.fullGcCount() == 0 ? Tokens::success
                    : log.collector().concurrent() ? Tokens::danger : Tokens::warning;
            summaryRow.add(summary(I18n.get("tool.gclog.summary.fullGc", String.valueOf(source.fullGcCount())),
                    fullColor));
            summaryRow.add(summary(I18n.get("tool.gclog.summary.allocation",
                    GcFormat.rate(source.allocationRateKbPerSec())), Tokens::mutedForeground));
        }
        summaryRow.revalidate();
        summaryRow.repaint();
    }

    private static JLabel summary(String text, Supplier<Color> tone) {
        return new ToneLabel(text, tone);
    }

    /** 摘要标签：颜色表达健康度，切换主题时按语义重新取色，而不是保留旧主题的色值。 */
    private static final class ToneLabel extends JLabel {
        private final Supplier<Color> tone;

        ToneLabel(String text, Supplier<Color> tone) {
            super(text);
            this.tone = tone;
            applyTone();
        }

        @Override
        public void updateUI() {
            super.updateUI();
            applyTone();
        }

        private void applyTone() {
            // updateUI 在父类构造期间就会被调用，此时 tone 还没赋值。
            if (tone != null) {
                setFont(Tokens.fontBodyStrong());
                setForeground(tone.get());
            }
        }
    }
}
