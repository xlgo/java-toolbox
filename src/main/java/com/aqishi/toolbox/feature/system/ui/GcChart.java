package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.feature.system.domain.GcEvent;
import com.aqishi.toolbox.feature.system.domain.GcReport;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 用 Java2D 画的时间序列图基类：坐标轴与刻度、拖拽缩放、悬停提示。
 *
 * <p>数据按事件预先展开成基本类型数组，绘制时按像素列分桶（每列只画极值），十万级事件也只需
 * 一次线性扫描；颜色每次绘制时从 {@link Tokens} 取，切换明暗主题后自然跟随。</p>
 */
abstract class GcChart extends JComponent {

    /** 多张图共享的横轴范围：一张图上拖拽缩放，另一张同步。NaN 表示显示全部。 */
    static final class Viewport {
        private double min = Double.NaN;
        private double max = Double.NaN;
        private final List<Runnable> listeners = new ArrayList<>();

        void set(double newMin, double newMax) {
            min = Math.min(newMin, newMax);
            max = Math.max(newMin, newMax);
            listeners.forEach(Runnable::run);
        }

        void reset() {
            min = Double.NaN;
            max = Double.NaN;
            listeners.forEach(Runnable::run);
        }

        boolean zoomed() {
            return !Double.isNaN(min);
        }

        void listen(Runnable listener) {
            listeners.add(listener);
        }
    }

    /** 图例项。 */
    record Legend(String label, Color color, boolean line) {
    }

    private static final int LEFT = 72;
    private static final int RIGHT = 14;
    private static final int TOP = 26;
    private static final int BOTTOM = 26;

    private final Viewport viewport;
    /** 事件横坐标：有时间戳用秒，否则用序号。 */
    protected double[] xs = new double[0];
    protected List<GcEvent> events = List.of();
    protected GcReport report;
    protected boolean timeAxis = true;
    private int dragFrom = -1;
    private int dragTo = -1;

    GcChart(Viewport viewport) {
        this.viewport = viewport;
        viewport.listen(this::repaint);
        setPreferredSize(new Dimension(600, 240));
        setMinimumSize(new Dimension(200, 140));
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragFrom = event.getX();
                dragTo = event.getX();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                dragTo = event.getX();
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                finishDrag();
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() >= 2) {
                    viewport.reset();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** 事件列表：子类据此展开绘图数组。 */
    final void setReport(GcReport newReport, List<GcEvent> newEvents) {
        report = newReport;
        events = newEvents == null ? List.of() : newEvents;
        timeAxis = newReport != null && newReport.log().hasTimestamps();
        xs = new double[events.size()];
        for (int i = 0; i < xs.length; i++) {
            double time = events.get(i).time();
            xs[i] = timeAxis && Double.isFinite(time) ? time : i;
        }
        prepare();
        repaint();
    }

    protected abstract void prepare();

    protected abstract String title();

    protected abstract List<Legend> legend();

    /** 可见范围内 y 的 [最小, 最大]。 */
    protected abstract double[] yRange(int from, int to);

    protected abstract String yLabel(double value);

    /** 纵轴刻度的单位（例如 1024 表示按 MB 取整刻度），默认按原始数值。 */
    protected double yUnit(double max) {
        return 1;
    }

    protected abstract void plot(Graphics2D g, Rectangle area, int from, int to, double x0, double x1,
                                 double y0, double y1);

    /** 悬停在第 index 个事件上时的提示（HTML）。 */
    protected abstract String tooltip(int index);

    /** 同一像素列里有多个事件时挑哪一个显示提示（例如停顿最长的那个）。 */
    protected int pick(int from, int to) {
        return from;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        repaint();
    }

    // ---------------------------------------------------------------- 坐标

    protected final double[] xDomain() {
        if (xs.length == 0) {
            return new double[]{0, 1};
        }
        if (viewport.zoomed()) {
            return new double[]{viewport.min, Math.max(viewport.max, viewport.min + 1e-6)};
        }
        double lo = xs[0];
        double hi = xs[xs.length - 1];
        if (hi <= lo) {
            hi = lo + 1;
        }
        // 两端各留一点空隙，边上的柱子和点不会被坐标框切掉一半。
        double pad = (hi - lo) * 0.015;
        return new double[]{lo - pad, hi + pad};
    }

    protected final Rectangle plotArea() {
        return new Rectangle(LEFT, TOP, Math.max(10, getWidth() - LEFT - RIGHT),
                Math.max(10, getHeight() - TOP - BOTTOM));
    }

    protected static int px(double x, Rectangle area, double x0, double x1) {
        return area.x + (int) Math.round((x - x0) / (x1 - x0) * area.width);
    }

    protected static int py(double y, Rectangle area, double y0, double y1) {
        return area.y + area.height - (int) Math.round((y - y0) / (y1 - y0) * area.height);
    }

    /** 第一个 x ≥ value 的下标。 */
    protected final int lowerBound(double value) {
        int lo = 0;
        int hi = xs.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (xs[mid] < value) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private void finishDrag() {
        int from = dragFrom;
        int to = dragTo;
        dragFrom = -1;
        dragTo = -1;
        if (from >= 0 && Math.abs(to - from) > 6 && xs.length > 1) {
            Rectangle area = plotArea();
            double[] domain = xDomain();
            double a = domain[0] + (Math.min(from, to) - area.x) / (double) area.width * (domain[1] - domain[0]);
            double b = domain[0] + (Math.max(from, to) - area.x) / (double) area.width * (domain[1] - domain[0]);
            viewport.set(Math.max(a, domain[0]), Math.min(b, domain[1]));
        } else {
            repaint();
        }
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (xs.length == 0) {
            return null;
        }
        Rectangle area = plotArea();
        if (!area.contains(event.getPoint())) {
            return null;
        }
        double[] domain = xDomain();
        double perPixel = (domain[1] - domain[0]) / area.width;
        double x = domain[0] + (event.getX() - area.x) * perPixel;
        int from = lowerBound(x - 4 * perPixel);
        int to = lowerBound(x + 4 * perPixel);
        if (from >= to) {
            return null;
        }
        return tooltip(pick(from, to));
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Tokens.cardBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(Tokens.fontCaption());
            paintHeader(g);
            Rectangle area = plotArea();
            if (xs.length == 0) {
                g.setColor(Tokens.mutedForeground());
                String text = I18n.get("tool.gclog.chart.empty");
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(text, area.x + (area.width - metrics.stringWidth(text)) / 2,
                        area.y + area.height / 2);
                return;
            }
            double[] domain = xDomain();
            int from = Math.max(0, lowerBound(domain[0]) - 1);
            int to = Math.min(xs.length, lowerBound(domain[1]) + 1);
            double[] range = yRange(from, to);
            double y0 = range[0];
            double y1 = range[1] <= y0 ? y0 + 1 : range[1];
            double unit = yUnit(y1);
            double yStep = niceStep((y1 - y0) / unit, Math.max(2, area.height / 40)) * unit;
            y1 = Math.ceil(y1 / yStep) * yStep;
            paintAxes(g, area, domain[0], domain[1], y0, y1, yStep);
            Graphics2D clipped = (Graphics2D) g.create();
            clipped.clip(new Rectangle(area.x, area.y - 1, area.width + 1, area.height + 2));
            plot(clipped, area, from, to, domain[0], domain[1], y0, y1);
            clipped.dispose();
            if (dragFrom >= 0 && Math.abs(dragTo - dragFrom) > 2) {
                g.setColor(Tokens.blend(Tokens.accent(), Tokens.cardBackground(), 0.75f));
                int left = Math.max(area.x, Math.min(dragFrom, dragTo));
                int right = Math.min(area.x + area.width, Math.max(dragFrom, dragTo));
                g.fillRect(left, area.y, right - left, area.height);
            }
        } finally {
            g.dispose();
        }
    }

    private void paintHeader(Graphics2D g) {
        FontMetrics metrics = g.getFontMetrics();
        int baseline = 6 + metrics.getAscent();
        g.setColor(Tokens.foreground());
        g.setFont(Tokens.fontBodyStrong());
        g.drawString(title(), LEFT, baseline);
        int x = LEFT + g.getFontMetrics().stringWidth(title()) + 16;
        g.setFont(Tokens.fontCaption());
        String zoomHint = viewport.zoomed() ? I18n.get("tool.gclog.chart.zoomed") : null;
        int limit = getWidth() - RIGHT - (zoomHint == null ? 0 : g.getFontMetrics().stringWidth(zoomHint) + 12);
        for (Legend item : legend()) {
            if (x + 16 + g.getFontMetrics().stringWidth(item.label()) > limit) {
                // 窄窗口放不下全部图例时宁可少画几项，也不画半截文字。
                break;
            }
            g.setColor(item.color());
            if (item.line()) {
                g.fillRect(x, baseline - 5, 12, 2);
            } else {
                g.fillOval(x + 2, baseline - 8, 8, 8);
            }
            x += 16;
            g.setColor(Tokens.mutedForeground());
            g.drawString(item.label(), x, baseline);
            x += g.getFontMetrics().stringWidth(item.label()) + 14;
        }
        if (zoomHint != null) {
            g.setColor(Tokens.accent());
            g.drawString(zoomHint, getWidth() - RIGHT - g.getFontMetrics().stringWidth(zoomHint), baseline);
        }
    }

    private void paintAxes(Graphics2D g, Rectangle area, double x0, double x1, double y0, double y1, double yStep) {
        FontMetrics metrics = g.getFontMetrics();
        Color grid = Tokens.borderSubtle();
        Color label = Tokens.mutedForeground();
        g.setStroke(new BasicStroke(1f));
        for (double y = y0; y <= y1 + yStep / 2; y += yStep) {
            int yPixel = py(y, area, y0, y1);
            g.setColor(grid);
            g.drawLine(area.x, yPixel, area.x + area.width, yPixel);
            g.setColor(label);
            String text = yLabel(y);
            g.drawString(text, area.x - 6 - metrics.stringWidth(text), yPixel + metrics.getAscent() / 2 - 1);
        }
        double scale = !timeAxis ? 1 : (x1 - x0) < 120 ? 1 : (x1 - x0) < 7200 ? 60 : 3600;
        String unit = !timeAxis ? "#" : scale == 1 ? "s" : scale == 60 ? "min" : "h";
        double xStep = niceStep((x1 - x0) / scale, Math.max(2, area.width / 90)) * scale;
        double start = Math.ceil(x0 / xStep) * xStep;
        for (double x = start; x <= x1; x += xStep) {
            int xPixel = px(x, area, x0, x1);
            g.setColor(grid);
            g.drawLine(xPixel, area.y, xPixel, area.y + area.height);
            g.setColor(label);
            String text = trim(x / scale, xStep / scale) + (timeAxis ? unit : "");
            g.drawString(text, xPixel - metrics.stringWidth(text) / 2, area.y + area.height + 4 + metrics.getAscent());
        }
        g.setColor(Tokens.border());
        g.drawRect(area.x, area.y, area.width, area.height);
    }

    /** 1、2、5 × 10^n 的「好看」刻度间隔。 */
    static double niceStep(double range, int targetTicks) {
        if (!(range > 0)) {
            return 1;
        }
        double raw = range / Math.max(1, targetTicks);
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double normalized = raw / magnitude;
        double nice = normalized < 1.5 ? 1 : normalized < 3 ? 2 : normalized < 7 ? 5 : 10;
        return nice * magnitude;
    }

    /** 按刻度间隔决定小数位，避免 0.30000000000000004 这类尾巴。 */
    static String trim(double value, double step) {
        int decimals = step >= 1 ? 0 : (int) Math.min(6, Math.ceil(-Math.log10(step)));
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /** 悬停提示：一次事件的关键信息。 */
    static String eventTooltip(GcEvent event) {
        StringBuilder html = new StringBuilder("<html><b>").append(escape(event.type())).append("</b>");
        if (event.cause() != null) {
            html.append(" (").append(escape(event.cause())).append(')');
        }
        if (event.gcId() >= 0) {
            html.append(" &nbsp;GC(").append(event.gcId()).append(')');
        }
        html.append("<br>").append(I18n.get("tool.gclog.column.time")).append(": ")
                .append(escape(event.date() != null ? event.date() : GcFormat.seconds(event.time()) + " s"));
        if (Double.isFinite(event.uptime())) {
            html.append("<br>").append(I18n.get("tool.gclog.column.uptime")).append(": ")
                    .append(GcFormat.seconds(event.uptime())).append(" s");
        }
        if (Double.isFinite(event.durationMs())) {
            html.append("<br>").append(I18n.get(event.isPause() ? "tool.gclog.column.duration"
                    : "tool.gclog.column.cycleDuration")).append(": ").append(GcFormat.ms(event.durationMs()));
        }
        if (event.hasHeap()) {
            html.append("<br>").append(I18n.get("tool.gclog.column.heap")).append(": ")
                    .append(escape(GcFormat.heap(event)));
        }
        return html.append("</html>").toString();
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
