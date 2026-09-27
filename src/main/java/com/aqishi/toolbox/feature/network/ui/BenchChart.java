package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.BenchSecond;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.List;

/**
 * 逐秒 QPS 与 p99 延迟的双轴折线图（纯 Java2D）。
 *
 * <p>QPS 用左轴、强调色填充面积；p99 用右轴、警示色折线——两者量纲不同，
 * 共用一根轴会让其中一条贴在底部看不出变化。有失败的秒在横轴上方打一个红点，
 * 卡顿与报错是否同时出现一眼可见。颜色每次绘制时从 {@link Tokens} 现取，切换主题后自动跟随。</p>
 */
final class BenchChart extends JComponent {

    private static final int PAD_LEFT = 56;
    private static final int PAD_RIGHT = 64;
    private static final int PAD_TOP = 28;
    private static final int PAD_BOTTOM = 26;
    private static final int GRID_LINES = 4;

    private List<BenchSecond> seconds = List.of();

    BenchChart() {
        setPreferredSize(new Dimension(480, 220));
        setMinimumSize(new Dimension(240, 160));
    }

    void setSeconds(List<BenchSecond> data) {
        this.seconds = data == null ? List.of() : data;
        repaint();
    }

    List<BenchSecond> seconds() {
        return seconds;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Tokens.cardBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(Tokens.fontCaption());
            int plotW = getWidth() - PAD_LEFT - PAD_RIGHT;
            int plotH = getHeight() - PAD_TOP - PAD_BOTTOM;
            if (plotW < 40 || plotH < 40) {
                return;
            }
            if (seconds.isEmpty()) {
                drawCentered(g, I18n.get("tool.httpbench.chart.empty"));
                return;
            }
            double maxQps = 1;
            double maxP99 = 1;
            for (BenchSecond second : seconds) {
                maxQps = Math.max(maxQps, second.requests());
                maxP99 = Math.max(maxP99, second.p99Micros() / 1000.0);
            }
            double qpsTop = niceCeil(maxQps);
            double latencyTop = niceCeil(maxP99);
            int xSpan = Math.max(seconds.size() - 1, 1);

            drawGrid(g, plotW, plotH, qpsTop, latencyTop);
            drawXAxis(g, plotW, plotH, xSpan);
            drawQps(g, plotW, plotH, qpsTop, xSpan);
            drawP99(g, plotW, plotH, latencyTop, xSpan);
            drawErrors(g, plotW, plotH, xSpan);
            drawLegend(g);
        } finally {
            g.dispose();
        }
    }

    private void drawGrid(Graphics2D g, int plotW, int plotH, double qpsTop, double latencyTop) {
        FontMetrics metrics = g.getFontMetrics();
        for (int i = 0; i <= GRID_LINES; i++) {
            int y = PAD_TOP + plotH - (int) Math.round(plotH * i / (double) GRID_LINES);
            g.setColor(Tokens.borderSubtle());
            g.drawLine(PAD_LEFT, y, PAD_LEFT + plotW, y);
            g.setColor(Tokens.mutedForeground());
            String left = BenchChart.compact(qpsTop * i / GRID_LINES);
            g.drawString(left, PAD_LEFT - 6 - metrics.stringWidth(left), y + metrics.getAscent() / 2 - 1);
            String right = BenchFormat.latency(latencyTop * 1000.0 * i / GRID_LINES);
            g.drawString(right, PAD_LEFT + plotW + 6, y + metrics.getAscent() / 2 - 1);
        }
    }

    private void drawXAxis(Graphics2D g, int plotW, int plotH, int xSpan) {
        FontMetrics metrics = g.getFontMetrics();
        int labelWidth = metrics.stringWidth("0000s") + 12;
        int maxLabels = Math.max(2, plotW / labelWidth);
        long step = (long) niceCeil(Math.max(1.0, xSpan / (double) maxLabels));
        int baseline = PAD_TOP + plotH + metrics.getAscent() + 4;
        g.setColor(Tokens.mutedForeground());
        for (long s = 0; s <= xSpan; s += step) {
            int x = xAt(s, plotW, xSpan);
            String label = s + "s";
            g.drawString(label, x - metrics.stringWidth(label) / 2, baseline);
        }
        g.setColor(Tokens.border());
        g.drawLine(PAD_LEFT, PAD_TOP + plotH, PAD_LEFT + plotW, PAD_TOP + plotH);
    }

    private void drawQps(Graphics2D g, int plotW, int plotH, double qpsTop, int xSpan) {
        Path2D.Double line = new Path2D.Double();
        Path2D.Double area = new Path2D.Double();
        int bottom = PAD_TOP + plotH;
        area.moveTo(xAt(0, plotW, xSpan), bottom);
        for (int i = 0; i < seconds.size(); i++) {
            double x = xAt(i, plotW, xSpan);
            double y = bottom - plotH * seconds.get(i).requests() / qpsTop;
            if (i == 0) {
                line.moveTo(x, y);
            } else {
                line.lineTo(x, y);
            }
            area.lineTo(x, y);
        }
        area.lineTo(xAt(seconds.size() - 1, plotW, xSpan), bottom);
        area.closePath();
        Color accent = Tokens.accent();
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), Tokens.isDark() ? 70 : 45));
        g.fill(area);
        g.setColor(accent);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);
    }

    private void drawP99(Graphics2D g, int plotW, int plotH, double latencyTop, int xSpan) {
        Path2D.Double line = new Path2D.Double();
        int bottom = PAD_TOP + plotH;
        boolean open = false;
        for (int i = 0; i < seconds.size(); i++) {
            BenchSecond second = seconds.get(i);
            if (second.p99Micros() <= 0) {
                open = false;
                continue;
            }
            double x = xAt(i, plotW, xSpan);
            double y = bottom - plotH * (second.p99Micros() / 1000.0) / latencyTop;
            if (open) {
                line.lineTo(x, y);
            } else {
                line.moveTo(x, y);
                open = true;
            }
        }
        g.setColor(Tokens.warning());
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(line);
    }

    private void drawErrors(Graphics2D g, int plotW, int plotH, int xSpan) {
        g.setColor(Tokens.danger());
        int y = PAD_TOP + plotH - 4;
        for (int i = 0; i < seconds.size(); i++) {
            if (seconds.get(i).errors() > 0) {
                int x = xAt(i, plotW, xSpan);
                g.fillOval(x - 3, y - 3, 6, 6);
            }
        }
    }

    private void drawLegend(Graphics2D g) {
        FontMetrics metrics = g.getFontMetrics();
        int x = PAD_LEFT;
        int y = PAD_TOP - 10;
        x = legendItem(g, metrics, x, y, Tokens.accent(), I18n.get("tool.httpbench.chart.qps"));
        x = legendItem(g, metrics, x, y, Tokens.warning(), I18n.get("tool.httpbench.chart.p99"));
        legendItem(g, metrics, x, y, Tokens.danger(), I18n.get("tool.httpbench.chart.errors"));
    }

    private static int legendItem(Graphics2D g, FontMetrics metrics, int x, int y, Color color, String text) {
        g.setColor(color);
        g.fillRoundRect(x, y - 6, 12, 6, 3, 3);
        g.setColor(Tokens.foreground());
        g.drawString(text, x + 16, y);
        return x + 16 + metrics.stringWidth(text) + Tokens.SPACE_LG;
    }

    private void drawCentered(Graphics2D g, String text) {
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(Tokens.mutedForeground());
        g.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2, getHeight() / 2);
    }

    private int xAt(long index, int plotW, int xSpan) {
        return PAD_LEFT + (int) Math.round(plotW * index / (double) xSpan);
    }

    /** 向上取到 1、2、2.5、5 乘以 10 的幂，刻度读起来是整数。 */
    static double niceCeil(double value) {
        if (value <= 0) {
            return 1;
        }
        double exponent = Math.pow(10, Math.floor(Math.log10(value)));
        double fraction = value / exponent;
        double nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10;
        return nice * exponent;
    }

    /** 轴标签用的紧凑数字：1200 显示为 1.2k。 */
    static String compact(double value) {
        if (value >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000);
        }
        if (value >= 10_000) {
            return String.format(java.util.Locale.ROOT, "%.0fk", value / 1_000);
        }
        if (value >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.1fk", value / 1_000);
        }
        return value == Math.rint(value) ? String.valueOf((long) value)
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
