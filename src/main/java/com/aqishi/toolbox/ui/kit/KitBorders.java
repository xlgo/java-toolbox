package com.aqishi.toolbox.ui.kit;

import javax.swing.border.Border;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * 边框工厂：卡片描边、带标题的卡片描边、单侧细线。
 *
 * <p>{@link #card(String)} 是 {@code TitledBorder} 的替代品，画法与 {@link Card} 一致
 * （圆角描边 + 标题带 + 细分隔线），可以直接套在已有的 {@code JScrollPane} 上，
 * 因此不需要改动调用方的组件结构。</p>
 */
public final class KitBorders {

    private KitBorders() {
    }

    /** 圆角描边，无标题 */
    public static Border card() {
        return new CardBorder(null);
    }

    /** 圆角描边 + 顶部标题带 */
    public static Border card(String title) {
        return new CardBorder(title);
    }

    /** 单侧细线，用于顶栏 / 状态栏分隔 */
    public static Border line(int top, int left, int bottom, int right) {
        return new HairlineBorder(top, left, bottom, right, false);
    }

    /** 单侧细线，使用更弱的分隔色 */
    public static Border lineSubtle(int top, int left, int bottom, int right) {
        return new HairlineBorder(top, left, bottom, right, true);
    }

    /** 空白内边距 */
    public static Border padding(int top, int left, int bottom, int right) {
        return javax.swing.BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    /** 四周等距空白内边距 */
    public static Border padding(int all) {
        return javax.swing.BorderFactory.createEmptyBorder(all, all, all, all);
    }

    /** 卡片描边实现 */
    private static final class CardBorder implements Border {

        private final String title;

        CardBorder(String title) {
            this.title = title == null || title.isEmpty() ? null : title;
        }

        private int titleBandHeight(Component component) {
            if (title == null) {
                return 0;
            }
            Font font = Tokens.fontSectionTitle();
            FontMetrics metrics = component.getFontMetrics(font);
            return metrics.getHeight() + Tokens.SPACE_MD + Tokens.SPACE_SM - 2;
        }

        @Override
        public Insets getBorderInsets(Component component) {
            int top = title == null ? 1 : titleBandHeight(component);
            return new Insets(top + (title == null ? 0 : 1), 1, 1, 1);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }

        @Override
        public void paintBorder(Component component, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                float arc = Tokens.RADIUS_CARD;

                if (title != null) {
                    // 标题带填充：与卡片本体同色，视觉上仍属于同一张卡片
                    int band = titleBandHeight(component);
                    java.awt.geom.RoundRectangle2D.Float top =
                            new java.awt.geom.RoundRectangle2D.Float(
                                    x + 0.5f, y + 0.5f, width - 1f, band * 2f, arc, arc);
                    java.awt.Shape oldClip = g2.getClip();
                    g2.clipRect(x, y, width, band);
                    g2.setColor(Tokens.cardBackground());
                    g2.fill(top);
                    g2.setClip(oldClip);

                    Font font = Tokens.fontSectionTitle();
                    FontMetrics metrics = component.getFontMetrics(font);
                    g2.setFont(font);
                    g2.setColor(Tokens.foreground());
                    int baseline = y + Tokens.SPACE_MD - 2 + metrics.getAscent();
                    g2.drawString(title, x + Tokens.CARD_PADDING, baseline);

                    g2.setColor(Tokens.borderSubtle());
                    g2.drawLine(x + 1, y + band, x + width - 2, y + band);
                }

                g2.setColor(Tokens.border());
                g2.draw(new java.awt.geom.RoundRectangle2D.Float(
                        x + 0.5f, y + 0.5f, width - 1f, height - 1f, arc, arc));
            } finally {
                g2.dispose();
            }
        }
    }

    /** 单侧细线实现 */
    private static final class HairlineBorder implements Border {

        private final int top;
        private final int left;
        private final int bottom;
        private final int right;
        private final boolean subtle;

        HairlineBorder(int top, int left, int bottom, int right, boolean subtle) {
            this.top = top;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            this.subtle = subtle;
        }

        @Override
        public Insets getBorderInsets(Component component) {
            return new Insets(top, left, bottom, right);
        }

        @Override
        public boolean isBorderOpaque() {
            return false;
        }

        @Override
        public void paintBorder(Component component, Graphics g, int x, int y, int width, int height) {
            g.setColor(subtle ? Tokens.borderSubtle() : Tokens.border());
            if (top > 0) {
                g.fillRect(x, y, width, top);
            }
            if (bottom > 0) {
                g.fillRect(x, y + height - bottom, width, bottom);
            }
            if (left > 0) {
                g.fillRect(x, y, left, height);
            }
            if (right > 0) {
                g.fillRect(x + width - right, y, right, height);
            }
        }
    }
}
