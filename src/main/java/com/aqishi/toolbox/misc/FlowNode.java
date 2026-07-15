package com.aqishi.toolbox.misc;

import java.awt.Color;
import java.awt.Point;
import java.awt.geom.Point2D;

public class FlowNode {
    // 节点类型定义
    public static final String TYPE_START_END = "start_end";       // 起止框 (椭圆)
    public static final String TYPE_PROCESS = "process";           // 步骤框 (矩形)
    public static final String TYPE_DECISION = "decision";         // 判定框 (菱形)
    public static final String TYPE_DATA = "data";                 // 输入/输出 (平行四边形)
    public static final String TYPE_DATABASE = "database";         // 数据库 (圆柱)
    public static final String TYPE_CLOUD = "cloud";               // 云服务 (云朵)
    public static final String TYPE_PREDEFINED = "predefined";     // 预设子过程 (双竖边矩形)
    public static final String TYPE_DOCUMENT = "document";         // 文档 (底部波浪矩形)
    public static final String TYPE_PREPARATION = "preparation";   // 准备/初始化 (六边形)
    public static final String TYPE_MANUAL_INPUT = "manual_input"; // 手工输入 (斜顶矩形)
    public static final String TYPE_ANNOTATION = "annotation";     // 注释文本 (半包围框)
    public static final String TYPE_TERMINATOR = "terminator";     // 终结符/起止 (圆角矩形)
    public static final String TYPE_CARD = "card";                 // 卡片 (右上剪角矩形)
    public static final String TYPE_DELAY = "delay";               // 延时 (左直右圆)
    public static final String TYPE_DISPLAY = "display";           // 显示 (左尖右圆)
    public static final String TYPE_INTERNAL_STORAGE = "internal_storage"; // 内部存储
    public static final String TYPE_OFF_PAGE_CONNECTOR = "off_page_connector"; // 离页连接符
    public static final String TYPE_LIFELINE = "lifeline";         // 生命线 (对象框+下方虚线)
    public static final String TYPE_ACTOR = "actor";               // 角色生命线 (小人+下方虚线)
    public static final String TYPE_ACTIVATION = "activation";     // 激活条 (细长垂直矩形)

    public String id;
    public String type;
    public String name;
    public int x, y, w, h;
    
    // 样式可变配置
    public Color bgColor = new Color(217, 237, 247, 220);
    public Color borderColor = new Color(51, 122, 183);
    public Color textColor = new Color(50, 50, 50);
    public int fontSize = 12;
    public boolean isBold = false;
    public boolean isDashedBorder = false;
    public float borderThickness = 1.5f;

    public FlowNode() {}

    public FlowNode(String type, String id, String name, int x, int y) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        
        // 节点预置宽高比
        if (type.equals(TYPE_DECISION)) {
            this.w = 80;
            this.h = 80;
        } else if (type.equals(TYPE_OFF_PAGE_CONNECTOR)) {
            this.w = 100;
            this.h = 80;
        } else if (type.equals(TYPE_START_END) || type.equals(TYPE_CLOUD)) {
            this.w = 100;
            this.h = 50;
        } else if (type.equals(TYPE_LIFELINE)) {
            this.w = 100;
            this.h = 300;
        } else if (type.equals(TYPE_ACTOR)) {
            this.w = 60;
            this.h = 300;
        } else if (type.equals(TYPE_ACTIVATION)) {
            this.w = 20;
            this.h = 80;
        } else {
            this.w = 120;
            this.h = 50;
        }

        setupDefaultColors();
    }

    private void setupDefaultColors() {
        if (type.equals(TYPE_START_END)) {
            bgColor = new Color(223, 240, 216, 220); // 浅绿
            borderColor = new Color(70, 136, 71);
        } else if (type.equals(TYPE_DECISION)) {
            bgColor = new Color(252, 248, 227, 220); // 浅黄
            borderColor = new Color(138, 109, 59);
        } else if (type.equals(TYPE_DATA)) {
            bgColor = new Color(235, 230, 245, 220); // 浅紫
            borderColor = new Color(117, 85, 175);
        } else if (type.equals(TYPE_DATABASE)) {
            bgColor = new Color(217, 237, 247, 220); // 浅蓝
            borderColor = new Color(58, 135, 173);
        } else if (type.equals(TYPE_CLOUD)) {
            bgColor = new Color(240, 248, 255, 220); // 淡青
            borderColor = new Color(70, 130, 180);
        } else if (type.equals(TYPE_ANNOTATION)) {
            bgColor = new Color(0, 0, 0, 0); // 纯透
            borderColor = new Color(120, 120, 120);
        } else if (type.equals(TYPE_TERMINATOR)) {
            bgColor = new Color(223, 240, 216, 220); // 浅绿
            borderColor = new Color(70, 136, 71);
        } else if (type.equals(TYPE_CARD)) {
            bgColor = new Color(252, 248, 227, 220); // 浅黄
            borderColor = new Color(138, 109, 59);
        } else if (type.equals(TYPE_DELAY)) {
            bgColor = new Color(235, 230, 245, 220); // 浅紫
            borderColor = new Color(117, 85, 175);
        } else if (type.equals(TYPE_DISPLAY)) {
            bgColor = new Color(217, 237, 247, 220); // 浅蓝
            borderColor = new Color(58, 135, 173);
        } else if (type.equals(TYPE_INTERNAL_STORAGE)) {
            bgColor = new Color(245, 245, 245, 220); // 极简灰
            borderColor = new Color(120, 120, 120);
        } else if (type.equals(TYPE_OFF_PAGE_CONNECTOR)) {
            bgColor = new Color(242, 222, 222, 220); // 警示红
            borderColor = new Color(170, 70, 70);
        } else if (type.equals(TYPE_LIFELINE)) {
            bgColor = new Color(240, 240, 240, 220); // 浅灰
            borderColor = new Color(100, 100, 100);
        } else if (type.equals(TYPE_ACTOR)) {
            bgColor = new Color(0, 0, 0, 0); // 纯透明，小人不填充
            borderColor = new Color(50, 50, 50);
        } else if (type.equals(TYPE_ACTIVATION)) {
            bgColor = new Color(255, 255, 240, 220); // 象牙白/浅黄
            borderColor = new Color(120, 120, 120);
        }
    }

    public Point getPortPoint(int index) {
        switch (index) {
            case 0: return new Point(x + w / 2, y); // 上
            case 1: return new Point(x + w, y + h / 2); // 右
            case 2: return new Point(x + w / 2, y + h); // 下
            case 3: return new Point(x, y + h / 2); // 左
            default: return new Point(x + w / 2, y + h / 2);
        }
    }

    public Point getConnectionPoint(double relX, double relY) {
        return new Point((int) (x + relX * w), (int) (y + relY * h));
    }

    public Point2D.Double getClosestRelativePoint(Point p) {
        int headerH = type.equals(TYPE_LIFELINE) ? 35 : (type.equals(TYPE_ACTOR) ? 50 : 0);
        
        if ((type.equals(TYPE_LIFELINE) || type.equals(TYPE_ACTOR)) && p.y > y + headerH) {
            // Snap to the vertical line in the middle
            double rx = 0.5;
            double ry = (double) (Math.min(y + h - 5, Math.max(y + headerH, p.y)) - y) / h;
            return new Point2D.Double(rx, ry);
        }

        Point closest = getClosestOutlinePoint(p);
        double rx = (double) (closest.x - x) / w;
        double ry = (double) (closest.y - y) / h;
        
        rx = Math.max(0.0, Math.min(1.0, rx));
        ry = Math.max(0.0, Math.min(1.0, ry));
        return new Point2D.Double(rx, ry);
    }

    public Point getClosestOutlinePoint(Point p) {
        double cx = x + w / 2.0;
        double cy = y + h / 2.0;
        
        if (type.equals(TYPE_START_END) || type.equals(TYPE_TERMINATOR) || type.equals(TYPE_DELAY)) {
            double rx = w / 2.0;
            double ry = h / 2.0;
            double theta = Math.atan2(p.y - cy, p.x - cx);
            return new Point((int) (cx + rx * Math.cos(theta)), (int) (cy + ry * Math.sin(theta)));
        }
        
        java.util.List<Point> vertices = new java.util.ArrayList<>();
        if (type.equals(TYPE_DECISION)) {
            vertices.add(new Point((int) cx, y));
            vertices.add(new Point(x + w, (int) cy));
            vertices.add(new Point((int) cx, y + h));
            vertices.add(new Point(x, (int) cy));
        } else if (type.equals(TYPE_DATA)) {
            int skew = 15;
            vertices.add(new Point(x + skew, y));
            vertices.add(new Point(x + w, y));
            vertices.add(new Point(x + w - skew, y + h));
            vertices.add(new Point(x, y + h));
        } else if (type.equals(TYPE_PREPARATION)) {
            vertices.add(new Point(x + 15, y));
            vertices.add(new Point(x + w - 15, y));
            vertices.add(new Point(x + w, (int) cy));
            vertices.add(new Point(x + w - 15, y + h));
            vertices.add(new Point(x + 15, y + h));
            vertices.add(new Point(x, (int) cy));
        } else if (type.equals(TYPE_CARD)) {
            int cut = 12;
            vertices.add(new Point(x, y));
            vertices.add(new Point(x + w - cut, y));
            vertices.add(new Point(x + w, y + cut));
            vertices.add(new Point(x + w, y + h));
            vertices.add(new Point(x, y + h));
        } else if (type.equals(TYPE_OFF_PAGE_CONNECTOR)) {
            vertices.add(new Point(x, y));
            vertices.add(new Point(x + w, y));
            vertices.add(new Point(x + w, y + 2 * h / 3));
            vertices.add(new Point((int) cx, y + h));
            vertices.add(new Point(x, y + 2 * h / 3));
        } else if (type.equals(TYPE_DISPLAY)) {
            int tip = 15;
            vertices.add(new Point(x + tip, y));
            vertices.add(new Point(x + w - tip, y));
            vertices.add(new Point(x + w, (int) cy));
            vertices.add(new Point(x + w - tip, y + h));
            vertices.add(new Point(x + tip, y + h));
            vertices.add(new Point(x, (int) cy));
        }
        
        if (!vertices.isEmpty()) {
            Point closest = null;
            double minDist = Double.MAX_VALUE;
            for (int i = 0; i < vertices.size(); i++) {
                Point s1 = vertices.get(i);
                Point s2 = vertices.get((i + 1) % vertices.size());
                Point proj = projectPointToSegment(p, s1, s2);
                double d = p.distance(proj);
                if (d < minDist) {
                    minDist = d;
                    closest = proj;
                }
            }
            return closest;
        }
        
        double clampX = Math.min(x + w, Math.max(x, p.x));
        double clampY = Math.min(y + h, Math.max(y, p.y));
        
        double distLeft = Math.abs(p.x - x);
        double distRight = Math.abs(p.x - (x + w));
        double distTop = Math.abs(p.y - y);
        double distBottom = Math.abs(p.y - (y + h));
        
        double minDist = distLeft;
        Point closest = new Point(x, (int) clampY);
        
        if (distRight < minDist) {
            minDist = distRight;
            closest = new Point(x + w, (int) clampY);
        }
        if (distTop < minDist) {
            minDist = distTop;
            closest = new Point((int) clampX, y);
        }
        if (distBottom < minDist) {
            minDist = distBottom;
            closest = new Point((int) clampX, y + h);
        }
        return closest;
    }

    private static Point projectPointToSegment(Point p, Point s1, Point s2) {
        double x1 = s1.x, y1 = s1.y;
        double x2 = s2.x, y2 = s2.y;
        double px = p.x, py = p.y;
        double dx = x2 - x1;
        double dy = y2 - y1;
        if (dx == 0 && dy == 0) return new Point(s1);
        double t = ((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy);
        t = Math.max(0.0, Math.min(1.0, t));
        return new Point((int) (x1 + t * dx), (int) (y1 + t * dy));
    }
}
