package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.GcEvent;
import com.aqishi.toolbox.feature.system.domain.GcReport;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 每次停顿的时长（竖条，按类型着色）与停顿目标线。像素列内只画最长的一次，缩放后细节自然展开。
 */
final class GcPauseChart extends GcChart {

    private double[] durations = new double[0];
    private GcEvent.Category[] categories = new GcEvent.Category[0];
    private Set<GcEvent.Category> present = EnumSet.noneOf(GcEvent.Category.class);

    GcPauseChart(Viewport viewport) {
        super(viewport);
    }

    static List<GcEvent> pauseEvents(GcReport report) {
        List<GcEvent> result = new ArrayList<>();
        if (report != null) {
            for (GcEvent event : report.log().events()) {
                if (event.isPause() && Double.isFinite(event.durationMs())) {
                    result.add(event);
                }
            }
        }
        return result;
    }

    @Override
    protected void prepare() {
        int n = events.size();
        durations = new double[n];
        categories = new GcEvent.Category[n];
        present = EnumSet.noneOf(GcEvent.Category.class);
        for (int i = 0; i < n; i++) {
            durations[i] = events.get(i).durationMs();
            categories[i] = events.get(i).category();
            present.add(categories[i]);
        }
    }

    @Override
    protected String title() {
        return I18n.get("tool.gclog.chart.pause");
    }

    @Override
    protected List<Legend> legend() {
        List<Legend> items = new ArrayList<>();
        for (GcEvent.Category category : GcEvent.Category.values()) {
            if (present.contains(category)) {
                items.add(new Legend(GcFormat.category(category), GcFormat.categoryColor(category), false));
            }
        }
        if (report != null) {
            items.add(new Legend(I18n.get("tool.gclog.chart.target",
                    String.format(Locale.ROOT, "%.0f", report.pauseTargetMs())), Tokens.warning(), true));
        }
        return items;
    }

    @Override
    protected double[] yRange(int from, int to) {
        double max = 0;
        for (int i = from; i < to; i++) {
            max = Math.max(max, durations[i]);
        }
        // 目标线离数据不太远时纳入纵轴，便于对照；远高于所有停顿时就不为它压扁数据。
        if (report != null && report.pauseTargetMs() < max * 3) {
            max = Math.max(max, report.pauseTargetMs());
        }
        return new double[]{0, max <= 0 ? 1 : max * 1.08};
    }

    @Override
    protected String yLabel(double value) {
        return value >= 1000 ? String.format(Locale.ROOT, "%.1f s", value / 1000)
                : value >= 1 || value == 0 ? String.format(Locale.ROOT, "%.0f ms", value)
                : String.format(Locale.ROOT, "%.2f ms", value);
    }

    @Override
    protected void plot(Graphics2D g, Rectangle area, int from, int to, double x0, double x1, double y0, double y1) {
        int width = area.width + 1;
        double[] tallest = new double[width];
        GcEvent.Category[] tallestCategory = new GcEvent.Category[width];
        int visible = 0;
        for (int i = from; i < to; i++) {
            int column = px(xs[i], area, x0, x1) - area.x;
            if (column < 0 || column >= width) {
                continue;
            }
            visible++;
            if (tallestCategory[column] == null || durations[i] > tallest[column]) {
                tallest[column] = durations[i];
                tallestCategory[column] = categories[i];
            }
        }
        int barWidth = Math.max(1, Math.min(7, visible == 0 ? 1 : area.width / visible - 1));
        int baseline = py(y0, area, y0, y1);
        for (int column = 0; column < width; column++) {
            if (tallestCategory[column] == null) {
                continue;
            }
            int top = Math.min(baseline - 1, py(tallest[column], area, y0, y1));
            g.setColor(GcFormat.categoryColor(tallestCategory[column]));
            g.fillRect(area.x + column - barWidth / 2, top, barWidth, baseline - top);
        }
        if (report != null && report.pauseTargetMs() <= y1) {
            int y = py(report.pauseTargetMs(), area, y0, y1);
            g.setColor(Tokens.warning());
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{5f, 4f}, 0f));
            g.drawLine(area.x, y, area.x + area.width, y);
        }
    }

    /** 同一像素范围里挑最长的停顿显示提示。 */
    @Override
    protected int pick(int from, int to) {
        int best = from;
        for (int i = from + 1; i < to; i++) {
            if (durations[i] > durations[best]) {
                best = i;
            }
        }
        return best;
    }

    @Override
    protected String tooltip(int index) {
        return eventTooltip(events.get(index));
    }
}
