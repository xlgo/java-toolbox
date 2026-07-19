package com.aqishi.toolbox.monitor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 被控端本地屏幕的全屏透明置顶标注画板。
 * 用于展示控制端在画面上绘制的红色圈点或批注，带自动渐隐消逝动画。
 */
public class TransparentOverlayWindow extends JWindow {

    private final List<LineSegment> segments = new ArrayList<>();
    private final Dimension screenSize;
    private final Timer fadeTimer;

    public TransparentOverlayWindow() {
        this.screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize(screenSize);
        setLocation(0, 0);
        setAlwaysOnTop(true);
        setFocusable(false);

        // 尝试设置完全透明背景 (JDK 7+)
        try {
            setBackground(new Color(0, 0, 0, 0));
        } catch (Exception e) {
            System.err.println("当前系统或JDK不支持窗口透明背景: " + e.getMessage());
        }

        // 自定义绘制面板
        OverlayPanel panel = new OverlayPanel();
        setContentPane(panel);

        // 定时器：每100ms执行一次，用于实现线条的渐隐淡出效果
        fadeTimer = new Timer(100, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                long now = System.currentTimeMillis();
                boolean changed = false;
                synchronized (segments) {
                    Iterator<LineSegment> it = segments.iterator();
                    while (it.hasNext()) {
                        LineSegment seg = it.next();
                        long age = now - seg.timestamp;
                        if (age > 4000) { // 超过4秒，开始计算淡出
                            if (age > 5000) { // 超过5秒，彻底移除
                                it.remove();
                                changed = true;
                            } else {
                                // 4s - 5s 之间，计算 alpha
                                float ratio = 1.0f - (age - 4000) / 1000.0f;
                                seg.alpha = Math.max(0.0f, Math.min(1.0f, ratio));
                                changed = true;
                            }
                        }
                    }
                }

                if (changed) {
                    repaint();
                }

                // 如果已经没有线条，则在置顶层隐藏自己，避免潜在的鼠标遮挡
                synchronized (segments) {
                    if (segments.isEmpty() && isShowing()) {
                        setVisible(false);
                    }
                }
            }
        });
        fadeTimer.start();
    }

    /**
     * 接收控制端发来的笔迹点（比例值），并在本地绘制。
     */
    public void addLine(double x1, double y1, double x2, double y2, Color color, int thickness) {
        int sx1 = (int) (x1 * screenSize.width);
        int sy1 = (int) (y1 * screenSize.height);
        int sx2 = (int) (x2 * screenSize.width);
        int sy2 = (int) (y2 * screenSize.height);

        synchronized (segments) {
            segments.add(new LineSegment(new Point(sx1, sy1), new Point(sx2, sy2), color, thickness));
            if (!isShowing()) {
                setVisible(true);
                toFront();
            }
        }
        repaint();
    }

    /**
     * 清空所有画板标注。
     */
    public void clearLines() {
        synchronized (segments) {
            segments.clear();
        }
        repaint();
        setVisible(false);
    }

    public void destroy() {
        if (fadeTimer != null) {
            fadeTimer.stop();
        }
        dispose();
    }

    private static class LineSegment {
        Point p1, p2;
        Color color;
        int thickness;
        long timestamp;
        float alpha = 1.0f;

        LineSegment(Point p1, Point p2, Color color, int thickness) {
            this.p1 = p1;
            this.p2 = p2;
            this.color = color;
            this.thickness = thickness;
            this.timestamp = System.currentTimeMillis();
        }
    }

    private class OverlayPanel extends JPanel {
        OverlayPanel() {
            setOpaque(false); // 设为透明背景
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            synchronized (segments) {
                for (LineSegment seg : segments) {
                    // 应用渐隐的透明度
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, seg.alpha));
                    g2.setColor(seg.color);
                    g2.setStroke(new BasicStroke(seg.thickness, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(seg.p1.x, seg.p1.y, seg.p2.x, seg.p2.y);
                }
            }

            g2.dispose();
        }
    }
}
