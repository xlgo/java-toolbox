package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.GcEvent;
import com.aqishi.toolbox.feature.system.domain.GcReport;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 堆随时间变化：回收前（浅色点）、回收后（按类型着色的点）、提交量（折线），以及堆底线的回归线。
 * 回收后的点连成的「底线」持续抬高，是内存泄漏最直观的信号。
 */
final class GcHeapChart extends GcChart {

    private double[] before = new double[0];
    private double[] after = new double[0];
    private double[] committed = new double[0];
    private GcEvent.Category[] categories = new GcEvent.Category[0];

    GcHeapChart(Viewport viewport) {
        super(viewport);
    }

    /** 只取带堆数值的事件。 */
    static List<GcEvent> heapEvents(GcReport report) {
        List<GcEvent> result = new ArrayList<>();
        if (report != null) {
            for (GcEvent event : report.log().events()) {
                if (event.hasHeap()) {
                    result.add(event);
                }
            }
        }
        return result;
    }

    @Override
    protected void prepare() {
        int n = events.size();
        before = new double[n];
        after = new double[n];
        committed = new double[n];
        categories = new GcEvent.Category[n];
        for (int i = 0; i < n; i++) {
            GcEvent event = events.get(i);
            before[i] = event.heapBeforeKb();
            after[i] = event.heapAfterKb();
            committed[i] = event.heapCommittedKb();
            categories[i] = event.category();
        }
    }

    @Override
    protected String title() {
        return I18n.get("tool.gclog.chart.heap");
    }

    @Override
    protected List<Legend> legend() {
        List<Legend> items = new ArrayList<>();
        items.add(new Legend(I18n.get("tool.gclog.chart.before"), beforeColor(), false));
        items.add(new Legend(I18n.get("tool.gclog.chart.after"), GcFormat.categoryColor(GcEvent.Category.YOUNG),
                false));
        items.add(new Legend(I18n.get("tool.gclog.chart.afterFull"), GcFormat.categoryColor(GcEvent.Category.FULL),
                false));
        items.add(new Legend(I18n.get("tool.gclog.chart.committed"), committedColor(), true));
        if (report != null && report.heapTrend() != null) {
            items.add(new Legend(I18n.get("tool.gclog.chart.trend"), trendColor(), true));
        }
        return items;
    }

    private static Color beforeColor() {
        return Tokens.blend(Tokens.mutedForeground(), Tokens.cardBackground(), 0.35f);
    }

    private static Color committedColor() {
        return Tokens.blend(Tokens.success(), Tokens.cardBackground(), 0.2f);
    }

    private Color trendColor() {
        return report != null && report.heapTrend() != null && report.heapTrend().rising() ? Tokens.danger()
                : Tokens.mutedForeground();
    }

    @Override
    protected double[] yRange(int from, int to) {
        double max = 0;
        for (int i = from; i < to; i++) {
            max = Math.max(max, Math.max(before[i], committed[i]));
        }
        return new double[]{0, max <= 0 ? 1024 : max * 1.05};
    }

    @Override
    protected double yUnit(double max) {
        return max >= 4.0 * 1024 * 1024 ? 1024 * 1024 : 1024;
    }

    @Override
    protected String yLabel(double value) {
        if (value >= 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f GB", value / (1024 * 1024));
        }
        return String.format(Locale.ROOT, "%.0f MB", value / 1024);
    }

    @Override
    protected void plot(Graphics2D g, Rectangle area, int from, int to, double x0, double x1, double y0, double y1) {
        int width = area.width + 1;
        double[] maxBefore = new double[width];
        double[] minAfter = new double[width];
        double[] maxAfter = new double[width];
        double[] lastCommitted = new double[width];
        GcEvent.Category[] lowCategory = new GcEvent.Category[width];
        GcEvent.Category[] highCategory = new GcEvent.Category[width];
        Arrays.fill(maxBefore, -1);
        Arrays.fill(minAfter, Double.POSITIVE_INFINITY);
        Arrays.fill(maxAfter, -1);
        Arrays.fill(lastCommitted, -1);
        for (int i = from; i < to; i++) {
            int column = px(xs[i], area, x0, x1) - area.x;
            if (column < 0 || column >= width) {
                continue;
            }
            maxBefore[column] = Math.max(maxBefore[column], before[i]);
            if (after[i] < minAfter[column]) {
                minAfter[column] = after[i];
                lowCategory[column] = categories[i];
            }
            if (after[i] > maxAfter[column]) {
                maxAfter[column] = after[i];
                highCategory[column] = categories[i];
            }
            if (committed[i] >= 0) {
                lastCommitted[column] = committed[i];
            }
        }
        // 提交量：阶梯折线。
        g.setColor(committedColor());
        g.setStroke(new BasicStroke(1.5f));
        int lastX = -1;
        int lastY = -1;
        for (int column = 0; column < width; column++) {
            if (lastCommitted[column] < 0) {
                continue;
            }
            int x = area.x + column;
            int y = py(lastCommitted[column], area, y0, y1);
            if (lastX >= 0) {
                g.drawLine(lastX, lastY, x, lastY);
                g.drawLine(x, lastY, x, y);
            }
            lastX = x;
            lastY = y;
        }
        g.setColor(beforeColor());
        for (int column = 0; column < width; column++) {
            if (maxBefore[column] >= 0) {
                int y = py(maxBefore[column], area, y0, y1);
                g.fillRect(area.x + column - 1, y - 1, 3, 3);
            }
        }
        for (int column = 0; column < width; column++) {
            if (maxAfter[column] < 0) {
                continue;
            }
            int x = area.x + column;
            dot(g, x, py(maxAfter[column], area, y0, y1), highCategory[column]);
            if (minAfter[column] < maxAfter[column]) {
                dot(g, x, py(minAfter[column], area, y0, y1), lowCategory[column]);
            }
        }
        GcReport.HeapTrend trend = report == null ? null : report.heapTrend();
        if (trend != null && timeAxis) {
            g.setColor(trendColor());
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{6f, 4f}, 0f));
            g.drawLine(px(trend.startTime(), area, x0, x1), py(trend.startKb(), area, y0, y1),
                    px(trend.endTime(), area, x0, x1), py(trend.endKb(), area, y0, y1));
        }
    }

    private static void dot(Graphics2D g, int x, int y, GcEvent.Category category) {
        boolean floor = category == GcEvent.Category.FULL || category == GcEvent.Category.DEGENERATED;
        g.setColor(GcFormat.categoryColor(floor ? GcEvent.Category.FULL : GcEvent.Category.YOUNG));
        int size = floor ? 6 : 4;
        g.fillOval(x - size / 2, y - size / 2, size, size);
    }

    @Override
    protected String tooltip(int index) {
        return eventTooltip(events.get(index));
    }
}
