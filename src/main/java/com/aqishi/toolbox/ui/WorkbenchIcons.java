package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Small, code-native icons used by the workbench chrome. */
public final class WorkbenchIcons {

    private WorkbenchIcons() { }

    public static Icon brand(int size) { return new VectorIcon(Kind.BRAND, Math.max(16, size), null); }
    public static Icon search() { return new VectorIcon(Kind.SEARCH, 16, null); }
    public static Icon sidebar(boolean collapsed) {
        return new VectorIcon(collapsed ? Kind.SIDEBAR_COLLAPSED : Kind.SIDEBAR, 16, null);
    }
    public static Icon category(String groupId) {
        return new VectorIcon(Kind.CATEGORY, 16, groupId == null ? "" : groupId);
    }
    public static Icon appearance() { return new VectorIcon(Kind.APPEARANCE, 16, null); }
    public static Icon globe() { return new VectorIcon(Kind.GLOBE, 16, null); }
    public static Icon status() { return new VectorIcon(Kind.STATUS, 16, null); }

    private enum Kind { BRAND, SEARCH, SIDEBAR, SIDEBAR_COLLAPSED, CATEGORY, APPEARANCE, GLOBE, STATUS }

    private static final class VectorIcon implements Icon {
        private final Kind kind;
        private final int size;
        private final String groupId;

        private VectorIcon(Kind kind, int size, String groupId) {
            this.kind = kind;
            this.size = size;
            this.groupId = groupId;
        }

        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                float scale = size / 16f;
                g.translate(x, y);
                g.scale(scale, scale);
                if (kind == Kind.BRAND) paintBrand(g);
                else {
                    g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(component != null && component.getForeground() != null
                            && kind != Kind.SEARCH ? component.getForeground() : Tokens.mutedForeground());
                    switch (kind) {
                        case SEARCH -> paintSearch(g);
                        case SIDEBAR, SIDEBAR_COLLAPSED -> paintSidebar(g, kind == Kind.SIDEBAR_COLLAPSED);
                        case CATEGORY -> paintCategory(g, groupId);
                        case APPEARANCE -> paintAppearance(g);
                        case GLOBE -> paintGlobe(g);
                        case STATUS -> paintStatus(g);
                        default -> { }
                    }
                }
            } finally { g.dispose(); }
        }

        private void paintBrand(Graphics2D g) {
            g.setColor(Tokens.accent());
            g.fill(new RoundRectangle2D.Float(0, 0, 16, 16, 4, 4));
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.35f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            roundRect(g, 3.5f, 5f, 9f, 7f, 1.5f);
            line(g, 5.5f, 5f, 6.7f, 3.5f);
            line(g, 6.7f, 3.5f, 9.3f, 3.5f);
            line(g, 9.3f, 3.5f, 10.5f, 5f);
            g.fill(new Ellipse2D.Float(7.25f, 7.7f, 1.5f, 1.5f));
        }

        private void paintSearch(Graphics2D g) {
            g.draw(new Ellipse2D.Float(2.1f, 2.1f, 8.1f, 8.1f));
            line(g, 9.1f, 9.1f, 13.4f, 13.4f);
        }

        private void paintSidebar(Graphics2D g, boolean collapsed) {
            roundRect(g, 1.5f, 2f, 13f, 12f, 2f);
            line(g, 5.5f, 2.5f, 5.5f, 13.5f);
            if (collapsed) {
                line(g, 9.2f, 6f, 11.5f, 8f);
                line(g, 11.5f, 8f, 9.2f, 10f);
            } else {
                line(g, 10.8f, 6f, 8.5f, 8f);
                line(g, 8.5f, 8f, 10.8f, 10f);
            }
        }

        private void paintCategory(Graphics2D g, String id) {
            String key = id == null ? "" : id.toLowerCase();
            if (key.contains("crypto") || key.contains("security")) {
                Path2D shield = new Path2D.Float();
                shield.moveTo(8, 1.5); shield.lineTo(13, 3.5); shield.lineTo(12.5, 9);
                shield.quadTo(11.5, 12.5, 8, 14.5); shield.quadTo(4.5, 12.5, 3.5, 9);
                shield.lineTo(3, 3.5); shield.closePath(); g.draw(shield);
                line(g, 5.5f, 7.5f, 7.3f, 9.2f); line(g, 7.3f, 9.2f, 10.6f, 5.8f);
            } else if (key.contains("convert") || key.contains("codec")) {
                line(g, 2.5f, 4.5f, 13.5f, 4.5f);
                line(g, 11f, 2f, 13.5f, 4.5f); line(g, 13.5f, 4.5f, 11f, 7f);
                line(g, 13.5f, 11.5f, 2.5f, 11.5f);
                line(g, 5f, 9f, 2.5f, 11.5f); line(g, 2.5f, 11.5f, 5f, 14f);
            } else if (key.contains("format") || key.contains("text")) {
                line(g, 3f, 3f, 13f, 3f); line(g, 3f, 6.5f, 10f, 6.5f);
                line(g, 3f, 10f, 13f, 10f); line(g, 3f, 13.5f, 8f, 13.5f);
            } else if (key.contains("network")) {
                paintGlobe(g);
            } else if (key.contains("cloud")) {
                Path2D cloud = new Path2D.Float();
                cloud.moveTo(4.5, 12.5); cloud.curveTo(0.5, 12.5, 0.5, 6, 4.5, 6);
                cloud.curveTo(4, 0.5, 12, 0.5, 12, 6); cloud.curveTo(16, 6, 16, 12.5, 12, 12.5);
                cloud.closePath(); g.draw(cloud);
            } else if (key.contains("monitor")) {
                roundRect(g, 1.5f, 2f, 13f, 9.5f, 2f);
                line(g, 8f, 11.5f, 8f, 14f); line(g, 5f, 14f, 11f, 14f);
                line(g, 4f, 7.5f, 6f, 7.5f); line(g, 6f, 7.5f, 7.5f, 4.5f);
                line(g, 7.5f, 4.5f, 9f, 9f); line(g, 9f, 9f, 10f, 7.5f);
                line(g, 10f, 7.5f, 12f, 7.5f);
            } else if (key.contains("data")) {
                g.draw(new Ellipse2D.Float(2.5f, 1.5f, 11f, 4f));
                line(g, 2.5f, 3.5f, 2.5f, 12.5f); line(g, 13.5f, 3.5f, 13.5f, 12.5f);
                g.draw(new java.awt.geom.Arc2D.Float(2.5f, 6f, 11f, 4f, 180, 180, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Arc2D.Float(2.5f, 10.5f, 11f, 4f, 180, 180, java.awt.geom.Arc2D.OPEN));
            } else if (key.contains("system") || key.contains("dev")) {
                roundRect(g, 1.5f, 2f, 13f, 12f, 2f);
                line(g, 4f, 5.5f, 6.5f, 8f); line(g, 6.5f, 8f, 4f, 10.5f);
                line(g, 8.5f, 10.5f, 12f, 10.5f);
            } else if (key.contains("calc") || key.contains("compute") || key.contains("algo")) {
                roundRect(g, 3f, 1.5f, 10f, 13f, 1.5f); line(g, 5f, 5f, 11f, 5f);
                for (int row = 0; row < 2; row++) for (int col = 0; col < 3; col++) g.fill(new Ellipse2D.Float(5f + col * 2.2f, 7f + row * 2.5f, 1f, 1f));
            } else if (key.contains("generate") || key.contains("generation")) {
                line(g, 3f, 13f, 11.5f, 4.5f); line(g, 9.5f, 4.5f, 11.5f, 6.5f);
                line(g, 4f, 2f, 4f, 6f); line(g, 2f, 4f, 6f, 4f);
                line(g, 12f, 10f, 12f, 14f); line(g, 10f, 12f, 14f, 12f);
            } else if (key.contains("chart") || key.contains("diagram")) {
                roundRect(g, 5.5f, 1f, 5f, 4f, 1f); roundRect(g, 1f, 10.5f, 5f, 4f, 1f);
                roundRect(g, 10f, 10.5f, 5f, 4f, 1f);
                line(g, 8f, 5f, 8f, 8f); line(g, 3.5f, 8f, 12.5f, 8f);
                line(g, 3.5f, 8f, 3.5f, 10.5f); line(g, 12.5f, 8f, 12.5f, 10.5f);
            } else {
                roundRect(g, 1.5f, 1.5f, 5f, 5f, 1f); roundRect(g, 9.5f, 1.5f, 5f, 5f, 1f);
                roundRect(g, 1.5f, 9.5f, 5f, 5f, 1f); roundRect(g, 9.5f, 9.5f, 5f, 5f, 1f);
            }
        }

        private void paintAppearance(Graphics2D g) {
            g.draw(new Ellipse2D.Float(4.1f, 4.1f, 7.8f, 7.8f));
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                line(g, (float) (8 + Math.cos(angle) * 6), (float) (8 + Math.sin(angle) * 6),
                        (float) (8 + Math.cos(angle) * 7.5), (float) (8 + Math.sin(angle) * 7.5));
            }
        }

        private void paintGlobe(Graphics2D g) {
            g.draw(new Ellipse2D.Float(1.5f, 1.5f, 13f, 13f));
            g.draw(new Ellipse2D.Float(5f, 1.5f, 6f, 13f)); line(g, 2.2f, 8f, 13.8f, 8f);
        }

        private void paintStatus(Graphics2D g) {
            g.setColor(Tokens.success());
            g.draw(new Ellipse2D.Float(1.5f, 1.5f, 13f, 13f));
            line(g, 4.5f, 8f, 7f, 10.5f); line(g, 7f, 10.5f, 11.8f, 5.5f);
        }

        private void line(Graphics2D g, float x1, float y1, float x2, float y2) {
            g.draw(new Line2D.Float(x1, y1, x2, y2));
        }

        private void roundRect(Graphics2D g, float x, float y, float width, float height, float arc) {
            g.draw(new RoundRectangle2D.Float(x, y, width, height, arc, arc));
        }
    }
}
