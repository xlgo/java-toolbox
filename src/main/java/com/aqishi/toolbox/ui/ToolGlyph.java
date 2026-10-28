package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.ui.kit.Skin;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 工具磁贴图标：渐变圆角底 + 白色线性字形。
 *
 * <p>效果图里每张卡片都有一个 44px 的彩色图标。这里不使用位图资源：
 * 描边矢量在任何 DPI 下都清晰，颜色又能跟着皮肤实时推导，无需维护 76 份图片。</p>
 *
 * <p>字形按工具语义挑选（摘要用 #、加密用锁、数据库用圆柱……），
 * 同一分类共享色相，因此一眼能看出「这个工具属于哪一组」。</p>
 */
public final class ToolGlyph {

    private ToolGlyph() {
    }

    /** 24×24 的设计网格：所有字形都在这个坐标系内绘制，再整体缩放到目标尺寸 */
    private static final float GRID = 24f;

    /** 工具 ID 到字形色的缓存：滚动时不重复做 HSB 运算 */
    private static final Map<String, Color> SEED_CACHE = new HashMap<>();

    private enum Glyph {
        HASH, LOCK, KEY, SHIELD, ACCOUNT, OTP, TOKEN, SIGNATURE, RADIX, CLOCK,
        IMAGE, LINK, SWAP, BRACES, TAGS, DATABASE, TEXT, REGEX, GLOBE, WEBHOOK,
        SOCKET, MQTT, SUBNET, RADAR, TERMINAL, GAUGE, CYLINDER, BOX, HEXAGON, CHMOD,
        FILE_LIST, CHAT, THREAD, CHART, ARCHIVE, DICE, QRCODE, PALETTE, ENTITY, ID,
        CALC, SORT, SEARCH, TOWER, PIN, FLOW, DROP, VIDEO
    }

    public static Icon of(String toolId, String categoryId, int size) {
        return new GlyphIcon(toolId, categoryId, Math.max(16, size));
    }

    /** 线框字形：用于工具标题与侧栏导航，不铺渐变底板。 */
    public static Icon outline(String toolId, String categoryId, int size) {
        return new OutlineGlyphIcon(toolId, Math.max(12, size), null);
    }

    /** 指定颜色的线框字形；颜色为 null 时取当前强调色。 */
    public static Icon outline(String toolId, String categoryId, int size, Color color) {
        return new OutlineGlyphIcon(toolId, Math.max(12, size), color);
    }

    /**
     * 工具语义到字形的映射。
     *
     * <p>先按精确 ID 匹配，未命中再按分类兜底——新增工具时即使忘了登记字形，
     * 也会得到分类色 + 通用网格图标，而不是空白。</p>
     */
    static Glyph glyphOf(String toolId) {
        String id = toolId == null ? "" : toolId.toLowerCase(Locale.ROOT);
        return switch (id) {
            case "hash.codec", "file.batch.digest" -> Glyph.HASH;
            case "symmetric.crypto" -> Glyph.LOCK;
            case "asymmetric.crypto", "jwk.tool" -> Glyph.KEY;
            case "cert.management", "cert.inspector" -> Glyph.SHIELD;
            case "account.manager" -> Glyph.ACCOUNT;
            case "totp.authenticator" -> Glyph.OTP;
            case "jwt.codec" -> Glyph.TOKEN;
            case "webhook.signature" -> Glyph.SIGNATURE;
            case "radix.encoding" -> Glyph.RADIX;
            case "timestamp", "cron.parser" -> Glyph.CLOCK;
            case "base64.image" -> Glyph.IMAGE;
            case "url.tool" -> Glyph.LINK;
            case "format.convert", "docker.convert" -> Glyph.SWAP;
            case "json.format", "jsonpath.tester", "xpath.tool", "structured.diff", "json.dto" ->
                    Glyph.BRACES;
            case "xml.format" -> Glyph.TAGS;
            case "sql.format", "table.sql", "mybatis.sql", "database.connector" -> Glyph.DATABASE;
            case "string.tool", "text.escape", "text.diff", "file.encoding" -> Glyph.TEXT;
            case "regex.tester" -> Glyph.REGEX;
            case "http.client", "openapi.workbench", "net.diagnostics" -> Glyph.GLOBE;
            case "callback.mock" -> Glyph.WEBHOOK;
            case "websocket.client", "socket.debug", "sse.client" -> Glyph.SOCKET;
            case "mqtt.client" -> Glyph.MQTT;
            case "subnet.calc" -> Glyph.SUBNET;
            case "port.scanner", "port.process" -> Glyph.RADAR;
            case "ssh", "maven.tree" -> Glyph.TERMINAL;
            case "http.bench" -> Glyph.GAUGE;
            case "redis.management", "kafka.connector", "zookeeper.management", "etcd.management" ->
                    Glyph.CYLINDER;
            case "docker.management" -> Glyph.BOX;
            case "k8s.deployment", "k8s.manager" -> Glyph.HEXAGON;
            case "chmod.calc" -> Glyph.CHMOD;
            case "hosts.manager", "log.viewer", "jar.inspector" -> Glyph.FILE_LIST;
            case "wechat.sender" -> Glyph.CHAT;
            case "thread.dump" -> Glyph.THREAD;
            case "gc.log", "statistics" -> Glyph.CHART;
            case "data.generator" -> Glyph.DICE;
            case "qrcode" -> Glyph.QRCODE;
            case "color.convert" -> Glyph.PALETTE;
            case "ddl.entity" -> Glyph.ENTITY;
            case "id.toolkit" -> Glyph.ID;
            case "calculator" -> Glyph.CALC;
            case "sort.visualizer" -> Glyph.SORT;
            case "search.algorithm" -> Glyph.SEARCH;
            case "hanoi" -> Glyph.TOWER;
            case "pingame" -> Glyph.PIN;
            case "bpmn.designer", "flowchart" -> Glyph.FLOW;
            case "mermaid" -> Glyph.DROP;
            case "video.monitor" -> Glyph.VIDEO;
            default -> Glyph.ARCHIVE;
        };
    }

    private static Color seedFor(String toolId, String categoryId) {
        String key = (categoryId == null ? "" : categoryId) + '/' + (toolId == null ? "" : toolId);
        return SEED_CACHE.computeIfAbsent(key, ignored -> Skin.seed(categoryId, toolId));
    }

    /** 主题切换后清空缓存：皮肤变了，融合底色也变了 */
    public static void invalidate() {
        SEED_CACHE.clear();
    }

    private static final class GlyphIcon implements Icon {

        private final Glyph glyph;
        private final String toolId;
        private final String categoryId;
        private final int size;

        private GlyphIcon(String toolId, String categoryId, int size) {
            this.toolId = toolId;
            this.categoryId = categoryId;
            this.size = size;
            this.glyph = glyphOf(toolId);
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.translate(x, y);

                Skin skin = ThemeManager.skin();
                Color seed = seedFor(toolId, categoryId);
                Color from = skin.glyphFrom(seed);
                Color to = skin.glyphTo(seed);
                float radius = size * 0.28f;

                // 主体：左上到右下的对角渐变，模拟效果图里的柔光
                g.setPaint(new LinearGradientPaint(
                        0f, 0f, size * 0.85f, size,
                        new float[]{0f, 1f},
                        new Color[]{from, to}));
                g.fill(new RoundRectangle2D.Float(0f, 0f, size, size, radius, radius));

                // 顶部高光：一条极淡的白色内弧，让图标不显得扁平
                g.setPaint(new LinearGradientPaint(
                        0f, 0f, 0f, size * 0.6f,
                        new float[]{0f, 1f},
                        new Color[]{new Color(255, 255, 255, 46), new Color(255, 255, 255, 0)}));
                g.fill(new RoundRectangle2D.Float(0f, 0f, size, size * 0.66f, radius, radius));

                // 内描边：在深色底上勾出边缘
                g.setColor(new Color(255, 255, 255, 36));
                g.setStroke(new BasicStroke(Math.max(1f, size / 44f)));
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, size - 1f, size - 1f, radius, radius));

                // 字形：占图标内侧约 56%，用白色描边绘制
                float inset = size * 0.22f;
                float scale = (size - inset * 2) / GRID;
                g.translate(inset, inset);
                g.scale(scale, scale);
                g.setColor(skin.glyphInk());
                g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                paint(glyph, g);
            } finally {
                g.dispose();
            }
        }
    }

    /** 线框字形：与磁贴共用同一套语义路径，只改用统一描边。 */
    private static final class OutlineGlyphIcon implements Icon {

        private final Glyph glyph;
        private final int size;
        private final Color color;

        private OutlineGlyphIcon(String toolId, int size, Color color) {
            this.glyph = glyphOf(toolId);
            this.size = size;
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.translate(x, y);
                float inset = size * 0.10f;
                float scale = (size - inset * 2) / GRID;
                g.translate(inset, inset);
                g.scale(scale, scale);
                g.setColor(color == null ? com.aqishi.toolbox.ui.kit.Tokens.accent() : color);
                g.setStroke(new BasicStroke(1.85f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                paint(glyph, g);
            } finally {
                g.dispose();
            }
        }
    }

    // ---------------------------------------------------------------- 字形绘制

    private static void paint(Glyph glyph, Graphics2D g) {
        switch (glyph) {
            case HASH -> {
                line(g, 9f, 4f, 7f, 20f);
                line(g, 17f, 4f, 15f, 20f);
                line(g, 4.5f, 9.5f, 19.5f, 9.5f);
                line(g, 4f, 14.5f, 19f, 14.5f);
            }
            case LOCK -> {
                roundRect(g, 5f, 10.5f, 14f, 10f, 2.4f);
                arc(g, 8f, 3.5f, 8f, 9.5f);
                dot(g, 12f, 15.5f, 1.1f);
                line(g, 12f, 16.5f, 12f, 18f);
            }
            case KEY -> {
                circle(g, 8f, 8f, 3.6f);
                line(g, 10.6f, 10.6f, 19f, 19f);
                line(g, 16f, 16f, 18f, 14f);
                line(g, 18.4f, 18.4f, 20f, 16.8f);
            }
            case SHIELD -> {
                Path2D shield = new Path2D.Float();
                shield.moveTo(12f, 3f);
                shield.lineTo(19.5f, 6f);
                shield.lineTo(19f, 13f);
                shield.quadTo(18f, 18.5f, 12f, 21f);
                shield.quadTo(6f, 18.5f, 5f, 13f);
                shield.lineTo(4.5f, 6f);
                shield.closePath();
                g.draw(shield);
                line(g, 9f, 11.5f, 11.2f, 13.8f);
                line(g, 11.2f, 13.8f, 15.2f, 9.4f);
            }
            case ACCOUNT -> {
                circle(g, 12f, 8.5f, 3.4f);
                Path2D body = new Path2D.Float();
                body.moveTo(5f, 20f);
                body.quadTo(5.5f, 14.5f, 12f, 14.5f);
                body.quadTo(18.5f, 14.5f, 19f, 20f);
                g.draw(body);
            }
            case OTP -> {
                roundRect(g, 7f, 3f, 10f, 18f, 2.2f);
                line(g, 10.5f, 6.2f, 13.5f, 6.2f);
                dot(g, 9.6f, 12f, 1f);
                dot(g, 12f, 12f, 1f);
                dot(g, 14.4f, 12f, 1f);
                dot(g, 9.6f, 15.4f, 1f);
                dot(g, 12f, 15.4f, 1f);
                dot(g, 14.4f, 15.4f, 1f);
            }
            case TOKEN -> {
                Path2D ticket = new Path2D.Float();
                ticket.moveTo(4f, 8f);
                ticket.lineTo(20f, 8f);
                ticket.lineTo(20f, 16f);
                ticket.lineTo(4f, 16f);
                ticket.closePath();
                g.draw(ticket);
                line(g, 8.5f, 8f, 8.5f, 16f);
                line(g, 12.5f, 11f, 17f, 11f);
                line(g, 12.5f, 13.5f, 16f, 13.5f);
            }
            case SIGNATURE -> {
                Path2D stroke = new Path2D.Float();
                stroke.moveTo(4f, 16f);
                stroke.curveTo(7f, 8f, 9f, 19f, 11.5f, 12f);
                stroke.curveTo(13f, 7.5f, 14.5f, 15f, 17f, 11f);
                stroke.curveTo(18f, 9.5f, 19f, 10f, 20f, 11f);
                g.draw(stroke);
                line(g, 4f, 20f, 20f, 20f);
            }
            case RADIX -> {
                line(g, 8f, 6f, 5.5f, 8f);
                line(g, 5.5f, 8f, 8f, 10f);
                circle(g, 13.5f, 17.5f, 2.6f);
                line(g, 8f, 14f, 8f, 10.5f);
            }
            case CLOCK -> {
                circle(g, 12f, 12f, 8f);
                line(g, 12f, 7.2f, 12f, 12.2f);
                line(g, 12f, 12.2f, 15.6f, 14.2f);
            }
            case IMAGE -> {
                roundRect(g, 3.5f, 5f, 17f, 14f, 2.2f);
                circle(g, 8.5f, 9.5f, 1.6f);
                Path2D hill = new Path2D.Float();
                hill.moveTo(5f, 17f);
                hill.lineTo(10f, 12f);
                hill.lineTo(13.5f, 15.5f);
                hill.lineTo(16.5f, 12.5f);
                hill.lineTo(19f, 15f);
                g.draw(hill);
            }
            case LINK -> {
                line(g, 9.5f, 14.5f, 14.5f, 9.5f);
                Path2D left = new Path2D.Float();
                left.moveTo(8.2f, 12.2f);
                left.lineTo(6.4f, 14f);
                left.quadTo(3.6f, 16.8f, 6.4f, 19.6f);
                left.quadTo(9.2f, 22.4f, 12f, 19.6f);
                left.lineTo(13.6f, 18f);
                g.draw(left);
                Path2D right = new Path2D.Float();
                right.moveTo(15.8f, 11.8f);
                right.lineTo(17.6f, 10f);
                right.quadTo(20.4f, 7.2f, 17.6f, 4.4f);
                right.quadTo(14.8f, 1.6f, 12f, 4.4f);
                right.lineTo(10.4f, 6f);
                g.draw(right);
            }
            case SWAP -> {
                line(g, 4f, 8.5f, 19f, 8.5f);
                line(g, 15.5f, 5f, 19f, 8.5f);
                line(g, 19f, 8.5f, 15.5f, 12f);
                line(g, 20f, 15.5f, 5f, 15.5f);
                line(g, 8.5f, 12f, 5f, 15.5f);
                line(g, 5f, 15.5f, 8.5f, 19f);
            }
            case BRACES -> {
                Path2D left = new Path2D.Float();
                left.moveTo(9f, 4f);
                left.quadTo(6f, 4f, 6f, 7f);
                left.lineTo(6f, 10f);
                left.quadTo(6f, 12f, 4f, 12f);
                left.quadTo(6f, 12f, 6f, 14f);
                left.lineTo(6f, 17f);
                left.quadTo(6f, 20f, 9f, 20f);
                g.draw(left);
                Path2D right = new Path2D.Float();
                right.moveTo(15f, 4f);
                right.quadTo(18f, 4f, 18f, 7f);
                right.lineTo(18f, 10f);
                right.quadTo(18f, 12f, 20f, 12f);
                right.quadTo(18f, 12f, 18f, 14f);
                right.lineTo(18f, 17f);
                right.quadTo(18f, 20f, 15f, 20f);
                g.draw(right);
            }
            case TAGS -> {
                line(g, 9.5f, 7f, 4.5f, 12f);
                line(g, 4.5f, 12f, 9.5f, 17f);
                line(g, 14.5f, 7f, 19.5f, 12f);
                line(g, 19.5f, 12f, 14.5f, 17f);
            }
            case DATABASE -> {
                g.draw(new Ellipse2D.Float(4.5f, 3.5f, 15f, 4.6f));
                line(g, 4.5f, 5.8f, 4.5f, 18.2f);
                line(g, 19.5f, 5.8f, 19.5f, 18.2f);
                g.draw(new Arc2D.Float(4.5f, 8.2f, 15f, 4.6f, 180, 180, Arc2D.OPEN));
                g.draw(new Arc2D.Float(4.5f, 15.2f, 15f, 4.6f, 180, 180, Arc2D.OPEN));
            }
            case TEXT -> {
                line(g, 4f, 6.5f, 20f, 6.5f);
                line(g, 4f, 12f, 15f, 12f);
                line(g, 4f, 17.5f, 18f, 17.5f);
            }
            case REGEX -> {
                circle(g, 8.5f, 16.5f, 1.4f);
                line(g, 15f, 6f, 15f, 14f);
                line(g, 11.5f, 8f, 18.5f, 12f);
                line(g, 18.5f, 8f, 11.5f, 12f);
            }
            case GLOBE -> {
                circle(g, 12f, 12f, 8.2f);
                g.draw(new Ellipse2D.Float(8.6f, 3.8f, 6.8f, 16.4f));
                line(g, 4f, 12f, 20f, 12f);
            }
            case WEBHOOK -> {
                circle(g, 7f, 8f, 2.6f);
                circle(g, 17f, 8f, 2.6f);
                circle(g, 12f, 17.5f, 2.6f);
                line(g, 9.3f, 9.4f, 11f, 15.2f);
                line(g, 14.7f, 9.4f, 13f, 15.2f);
                line(g, 9.4f, 7.2f, 14.6f, 7.2f);
            }
            case SOCKET -> {
                roundRect(g, 4f, 9f, 16f, 10f, 2.4f);
                line(g, 8.5f, 9f, 8.5f, 4.5f);
                line(g, 15.5f, 9f, 15.5f, 4.5f);
                line(g, 9f, 14f, 15f, 14f);
            }
            case MQTT -> {
                g.draw(new Arc2D.Float(9f, 8f, 12f, 12f, 135, 90, Arc2D.OPEN));
                g.draw(new Arc2D.Float(5.5f, 4.5f, 19f, 19f, 135, 90, Arc2D.OPEN));
                circle(g, 7f, 17f, 1.8f);
            }
            case SUBNET -> {
                circle(g, 6f, 6.5f, 2.2f);
                circle(g, 18f, 6.5f, 2.2f);
                circle(g, 12f, 18f, 2.2f);
                line(g, 7.6f, 8.2f, 10.8f, 16.2f);
                line(g, 16.4f, 8.2f, 13.2f, 16.2f);
                line(g, 8.2f, 6.5f, 15.8f, 6.5f);
            }
            case RADAR -> {
                g.draw(new Arc2D.Float(3f, 3f, 18f, 18f, 0, 360, Arc2D.OPEN));
                g.draw(new Arc2D.Float(7f, 7f, 10f, 10f, 0, 360, Arc2D.OPEN));
                circle(g, 12f, 12f, 1.3f);
                line(g, 12f, 12f, 19f, 5.5f);
            }
            case TERMINAL -> {
                roundRect(g, 3f, 4.5f, 18f, 15f, 2.4f);
                line(g, 7f, 10f, 10f, 12.5f);
                line(g, 10f, 12.5f, 7f, 15f);
                line(g, 13f, 15f, 17f, 15f);
            }
            case GAUGE -> {
                g.draw(new Arc2D.Float(3.5f, 4.5f, 17f, 17f, 0, 180, Arc2D.OPEN));
                line(g, 12f, 13f, 16.5f, 8.5f);
                circle(g, 12f, 13f, 1.6f);
                line(g, 4.5f, 19.5f, 19.5f, 19.5f);
            }
            case CYLINDER -> {
                g.draw(new Ellipse2D.Float(5.5f, 3f, 13f, 4.2f));
                line(g, 5.5f, 5f, 5.5f, 17f);
                line(g, 18.5f, 5f, 18.5f, 17f);
                g.draw(new Arc2D.Float(5.5f, 14.8f, 13f, 4.2f, 180, 180, Arc2D.OPEN));
                g.draw(new Arc2D.Float(5.5f, 8.8f, 13f, 4.2f, 180, 180, Arc2D.OPEN));
            }
            case BOX -> {
                Path2D cube = new Path2D.Float();
                cube.moveTo(12f, 3f);
                cube.lineTo(20f, 7.2f);
                cube.lineTo(20f, 16.4f);
                cube.lineTo(12f, 21f);
                cube.lineTo(4f, 16.4f);
                cube.lineTo(4f, 7.2f);
                cube.closePath();
                g.draw(cube);
                line(g, 4f, 7.2f, 12f, 11.6f);
                line(g, 20f, 7.2f, 12f, 11.6f);
                line(g, 12f, 11.6f, 12f, 21f);
            }
            case HEXAGON -> {
                Path2D hex = hexagon(12f, 12f, 8.6f);
                g.draw(hex);
                Path2D inner = hexagon(12f, 12f, 3.4f);
                g.draw(inner);
            }
            case CHMOD -> {
                circle(g, 12f, 12f, 8.2f);
                line(g, 12f, 7f, 12f, 8.4f);
                line(g, 12f, 15.6f, 12f, 17f);
                line(g, 7f, 12f, 8.4f, 12f);
                line(g, 15.6f, 12f, 17f, 12f);
                circle(g, 12f, 12f, 2.6f);
            }
            case FILE_LIST -> {
                Path2D page = new Path2D.Float();
                page.moveTo(6f, 3f);
                page.lineTo(14.5f, 3f);
                page.lineTo(18.5f, 7f);
                page.lineTo(18.5f, 21f);
                page.lineTo(6f, 21f);
                page.closePath();
                g.draw(page);
                line(g, 14.5f, 3f, 14.5f, 7f);
                line(g, 14.5f, 7f, 18.5f, 7f);
                line(g, 9f, 12f, 15.5f, 12f);
                line(g, 9f, 15.5f, 15.5f, 15.5f);
                line(g, 9f, 19f, 13f, 19f);
            }
            case CHAT -> {
                Path2D bubble = new Path2D.Float();
                bubble.moveTo(4f, 6f);
                bubble.lineTo(20f, 6f);
                bubble.lineTo(20f, 15f);
                bubble.lineTo(11f, 15f);
                bubble.lineTo(7f, 19f);
                bubble.lineTo(7f, 15f);
                bubble.lineTo(4f, 15f);
                bubble.closePath();
                g.draw(bubble);
                dot(g, 9f, 10.5f, 1f);
                dot(g, 12f, 10.5f, 1f);
                dot(g, 15f, 10.5f, 1f);
            }
            case THREAD -> {
                circle(g, 7f, 5.5f, 2.4f);
                circle(g, 7f, 16f, 2.4f);
                circle(g, 17f, 10.5f, 2.4f);
                line(g, 7f, 8f, 7f, 13.4f);
                Path2D branch = new Path2D.Float();
                branch.moveTo(7f, 11f);
                branch.curveTo(12f, 11f, 12f, 10.5f, 14.6f, 10.5f);
                g.draw(branch);
            }
            case CHART -> {
                line(g, 4f, 20f, 20f, 20f);
                roundRectFill(g, 6f, 12f, 3.4f, 7f, 1.2f);
                roundRectFill(g, 10.6f, 7.5f, 3.4f, 11.5f, 1.2f);
                roundRectFill(g, 15.2f, 10f, 3.4f, 9f, 1.2f);
            }
            case ARCHIVE -> {
                roundRect(g, 4f, 4.5f, 16f, 15.5f, 2.2f);
                line(g, 4f, 9f, 20f, 9f);
                line(g, 12f, 9f, 12f, 13f);
                roundRect(g, 10.5f, 12.5f, 3f, 4f, 0.8f);
            }
            case DICE -> {
                roundRect(g, 4f, 4f, 16f, 16f, 3.6f);
                dot(g, 9f, 9f, 1.4f);
                dot(g, 15f, 9f, 1.4f);
                dot(g, 12f, 12f, 1.4f);
                dot(g, 9f, 15f, 1.4f);
                dot(g, 15f, 15f, 1.4f);
            }
            case QRCODE -> {
                roundRect(g, 4f, 4f, 6f, 6f, 1.2f);
                roundRect(g, 14f, 4f, 6f, 6f, 1.2f);
                roundRect(g, 4f, 14f, 6f, 6f, 1.2f);
                line(g, 14f, 14f, 20f, 14f);
                line(g, 20f, 14f, 20f, 20f);
                line(g, 14f, 17f, 17f, 17f);
            }
            case PALETTE -> {
                Path2D palette = new Path2D.Float();
                palette.moveTo(12f, 3.5f);
                palette.curveTo(19f, 3.5f, 21f, 9f, 19f, 13f);
                palette.curveTo(17.5f, 16f, 14f, 16f, 13.5f, 18f);
                palette.curveTo(13f, 20.5f, 8f, 20.5f, 6f, 16f);
                palette.curveTo(4f, 11.5f, 5.5f, 3.5f, 12f, 3.5f);
                palette.closePath();
                g.draw(palette);
                dot(g, 9f, 8.5f, 1.3f);
                dot(g, 13.5f, 7.5f, 1.3f);
                dot(g, 16f, 11f, 1.3f);
            }
            case ENTITY -> {
                roundRect(g, 3.5f, 4f, 17f, 16f, 2.2f);
                line(g, 3.5f, 9f, 20.5f, 9f);
                line(g, 8.5f, 9f, 8.5f, 20f);
                line(g, 11.5f, 13f, 17.5f, 13f);
                line(g, 11.5f, 16.5f, 15.5f, 16.5f);
            }
            case ID -> {
                line(g, 9f, 5f, 7f, 19f);
                line(g, 16f, 5f, 14f, 19f);
                line(g, 5f, 9.5f, 19f, 9.5f);
                line(g, 4.5f, 14f, 18.5f, 14f);
            }
            case CALC -> {
                roundRect(g, 5f, 3f, 14f, 18f, 2.2f);
                roundRect(g, 7.5f, 5.5f, 9f, 4f, 1f);
                dot(g, 8.5f, 13f, 1.1f);
                dot(g, 12f, 13f, 1.1f);
                dot(g, 15.5f, 13f, 1.1f);
                dot(g, 8.5f, 17f, 1.1f);
                dot(g, 12f, 17f, 1.1f);
                dot(g, 15.5f, 17f, 1.1f);
            }
            case SORT -> {
                line(g, 4f, 20f, 20f, 20f);
                roundRectFill(g, 5.5f, 15f, 3f, 4f, 1f);
                roundRectFill(g, 10.5f, 11f, 3f, 8f, 1f);
                roundRectFill(g, 15.5f, 6.5f, 3f, 12.5f, 1f);
            }
            case SEARCH -> {
                circle(g, 10.5f, 10.5f, 6f);
                line(g, 15f, 15f, 20f, 20f);
            }
            case TOWER -> {
                line(g, 3f, 20f, 21f, 20f);
                line(g, 6f, 20f, 8f, 12f);
                line(g, 18f, 20f, 16f, 12f);
                line(g, 8f, 12f, 16f, 12f);
                line(g, 9f, 12f, 10f, 6f);
                line(g, 15f, 12f, 14f, 6f);
                line(g, 10f, 6f, 14f, 6f);
                dot(g, 12f, 3.5f, 1.4f);
            }
            case PIN -> {
                circle(g, 12f, 9f, 5.2f);
                line(g, 12f, 14.2f, 12f, 20f);
                dot(g, 12f, 9f, 1.4f);
            }
            case FLOW -> {
                roundRect(g, 8f, 3f, 8f, 5f, 1.2f);
                roundRect(g, 3f, 15f, 8f, 5f, 1.2f);
                roundRect(g, 13f, 15f, 8f, 5f, 1.2f);
                line(g, 12f, 8f, 12f, 11.5f);
                line(g, 7f, 11.5f, 17f, 11.5f);
                line(g, 7f, 11.5f, 7f, 15f);
                line(g, 17f, 11.5f, 17f, 15f);
            }
            case DROP -> {
                Path2D drop = new Path2D.Float();
                drop.moveTo(12f, 3.5f);
                drop.curveTo(17.5f, 10f, 19f, 13f, 19f, 15.5f);
                drop.curveTo(19f, 19f, 16f, 21f, 12f, 21f);
                drop.curveTo(8f, 21f, 5f, 19f, 5f, 15.5f);
                drop.curveTo(5f, 13f, 6.5f, 10f, 12f, 3.5f);
                drop.closePath();
                g.draw(drop);
            }
            case VIDEO -> {
                roundRect(g, 3f, 6.5f, 13f, 11f, 2.2f);
                Path2D lens = new Path2D.Float();
                lens.moveTo(16f, 10.5f);
                lens.lineTo(21f, 7.5f);
                lens.lineTo(21f, 16.5f);
                lens.lineTo(16f, 13.5f);
                lens.closePath();
                g.draw(lens);
            }
        }
    }

    // ---------------------------------------------------------------- 绘制工具

    private static void line(Graphics2D g, float x1, float y1, float x2, float y2) {
        g.draw(new Line2D.Float(x1, y1, x2, y2));
    }

    private static void circle(Graphics2D g, float cx, float cy, float r) {
        g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    private static void dot(Graphics2D g, float cx, float cy, float r) {
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
    }

    private static void roundRect(Graphics2D g, float x, float y, float w, float h, float r) {
        g.draw(new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2));
    }

    private static void roundRectFill(Graphics2D g, float x, float y, float w, float h, float r) {
        g.fill(new RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2));
    }

    /** 锁梁：上方开口的半圆弧 */
    private static void arc(Graphics2D g, float x, float y, float w, float h) {
        g.draw(new Arc2D.Float(x, y, w, h, 0, 180, Arc2D.OPEN));
    }

    private static Path2D hexagon(float cx, float cy, float radius) {
        Path2D path = new Path2D.Float();
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI / 6 + i * Math.PI / 3;
            float x = (float) (cx + Math.cos(angle) * radius);
            float y = (float) (cy + Math.sin(angle) * radius);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        path.closePath();
        return path;
    }

    /** 供测试断言：字形覆盖不应退化为同一个通用图标 */
    static String glyphNameOf(String toolId) {
        return glyphOf(toolId).name();
    }

    /** 当前皮肤下的磁贴图标底色，用于在测试里校验配色跟随皮肤 */
    static Color tileColorFor(String toolId, String categoryId) {
        return ThemeManager.skin().glyphFrom(seedFor(toolId, categoryId));
    }
}
