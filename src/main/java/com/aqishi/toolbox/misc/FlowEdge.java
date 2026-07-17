package com.aqishi.toolbox.misc;

import java.awt.Color;
import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;

public class FlowEdge {
    public String id;
    public String label;
    public FlowNode source;
    public FlowNode target;
    public int sourcePort;
    public int targetPort;
    
    // 相对坐标 (0.0 - 1.0)
    public double sourceRelX = 0.5;
    public double sourceRelY = 0.5;
    public double targetRelX = 0.5;
    public double targetRelY = 0.5;
    
    // 样式可变配置
    public Color lineColor = new Color(50, 50, 50);
    public boolean isDashed = false;
    public String routingType = "manhattan"; // manhattan, straight, bezier
    public List<Point> waypoints = new ArrayList<>(); // 自定义弯折点
    public double labelPosition = 0.5; // 连线文本位置比例 (0.0 到 1.0)

    public FlowEdge() {}

    public FlowEdge(String id, String label, FlowNode source, FlowNode target, double sourceRelX, double sourceRelY, double targetRelX, double targetRelY) {
        this.id = id;
        this.label = label;
        this.source = source;
        this.target = target;
        this.sourceRelX = sourceRelX;
        this.sourceRelY = sourceRelY;
        this.targetRelX = targetRelX;
        this.targetRelY = targetRelY;
        
        // 映射回近似端口，以兼容部分原有的只读逻辑
        this.sourcePort = getPortIndexFromRel(sourceRelX, sourceRelY, true);
        this.targetPort = getPortIndexFromRel(targetRelX, targetRelY, false);
    }

    public FlowEdge(String id, String label, FlowNode source, FlowNode target, int sourcePort, int targetPort) {
        this.id = id;
        this.label = label;
        this.source = source;
        this.target = target;
        this.sourcePort = sourcePort;
        this.targetPort = targetPort;
        
        Point2D.Double srcRel = getPortRelativeCoords(sourcePort);
        this.sourceRelX = srcRel.x;
        this.sourceRelY = srcRel.y;
        
        Point2D.Double tgtRel = getPortRelativeCoords(targetPort);
        this.targetRelX = tgtRel.x;
        this.targetRelY = tgtRel.y;
    }

    public static Point2D.Double getPortRelativeCoords(int port) {
        switch (port) {
            case 0: return new Point2D.Double(0.5, 0.0); // 上
            case 1: return new Point2D.Double(1.0, 0.5); // 右
            case 2: return new Point2D.Double(0.5, 1.0); // 下
            case 3: return new Point2D.Double(0.0, 0.5); // 左
            default: return new Point2D.Double(0.5, 0.5);
        }
    }

    public int getPortIndexFromRel(double relX, double relY, boolean isSource) {
        FlowNode node = isSource ? source : target;
        if (node.type.equals(FlowNode.TYPE_LIFELINE) || node.type.equals(FlowNode.TYPE_ACTOR)) {
            int headerH = node.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
            if (relY * node.h > headerH) {
                FlowNode other = isSource ? target : source;
                return (other.x + other.w / 2 > node.x + node.w / 2) ? 1 : 3;
            }
        }
        
        double distLeft = relX;
        double distRight = 1.0 - relX;
        double distTop = relY;
        double distBottom = 1.0 - relY;
        
        double minDist = distTop;
        int dir = 0; // Top
        
        if (distRight < minDist) {
            minDist = distRight;
            dir = 1; // Right
        }
        if (distBottom < minDist) {
            minDist = distBottom;
            dir = 2; // Bottom
        }
        if (distLeft < minDist) {
            minDist = distLeft;
            dir = 3; // Left
        }
        return dir;
    }

    public List<Point> getPoints() {
        List<Point> pts = new ArrayList<>();
        Point p1 = source.getConnectionPoint(sourceRelX, sourceRelY);
        Point p2 = target.getConnectionPoint(targetRelX, targetRelY);
        pts.add(p1);
        if (waypoints != null && !waypoints.isEmpty()) {
            pts.addAll(waypoints);
        } else {
            int sPort = getPortIndexFromRel(sourceRelX, sourceRelY, true);
            int tPort = getPortIndexFromRel(targetRelX, targetRelY, false);
            
            if ("manhattan".equals(routingType)) {
                int dist = 20;
                Point startDir = getDirectionOffset(sPort, dist);
                Point s1 = new Point(p1.x + startDir.x, p1.y + startDir.y);
                pts.add(s1);

                Point endDir = getDirectionOffset(tPort, dist);
                Point s2 = new Point(p2.x + endDir.x, p2.y + endDir.y);

                if (sPort == 1 || sPort == 3) {
                    pts.add(new Point(s1.x, s2.y));
                } else {
                    pts.add(new Point(s2.x, s1.y));
                }
                pts.add(s2);
            } else if ("bezier".equals(routingType)) {
                int ctrlX1 = p1.x;
                int ctrlY1 = p1.y;
                int ctrlX2 = p2.x;
                int ctrlY2 = p2.y;
                int d = Math.max(30, Math.abs(p2.x - p1.x) / 2);
                if (sPort == 1 || sPort == 3) {
                    ctrlX1 = p1.x + (sPort == 1 ? d : -d);
                } else {
                    ctrlY1 = p1.y + (sPort == 2 ? d : -d);
                }
                if (tPort == 1 || tPort == 3) {
                    ctrlX2 = p2.x + (tPort == 1 ? d : -d);
                } else {
                    ctrlY2 = p2.y + (tPort == 2 ? d : -d);
                }
                for (int i = 1; i < 20; i++) {
                    double t = i / 20.0;
                    double x = (1-t)*(1-t)*(1-t)*p1.x + 3*(1-t)*(1-t)*t*ctrlX1 + 3*(1-t)*t*t*ctrlX2 + t*t*t*p2.x;
                    double y = (1-t)*(1-t)*(1-t)*p1.y + 3*(1-t)*(1-t)*t*ctrlY1 + 3*(1-t)*t*t*ctrlY2 + t*t*t*p2.y;
                    pts.add(new Point((int)x, (int)y));
                }
            }
        }
        pts.add(p2);
        return pts;
    }

    private Point getDirectionOffset(int portIndex, int d) {
        switch (portIndex) {
            case 0: return new Point(0, -d);
            case 1: return new Point(d, 0);
            case 2: return new Point(0, d);
            case 3: return new Point(-d, 0);
            default: return new Point(0, 0);
        }
    }

    public Point getPointAtFraction(double fraction) {
        List<Point> pts = getPoints();
        if (pts.isEmpty()) return new Point(0, 0);
        if (pts.size() == 1) return pts.get(0);
        
        double totalLength = 0;
        double[] lengths = new double[pts.size() - 1];
        for (int i = 0; i < pts.size() - 1; i++) {
            lengths[i] = pts.get(i).distance(pts.get(i + 1));
            totalLength += lengths[i];
        }
        
        if (totalLength == 0) return pts.get(0);
        
        double targetLength = totalLength * fraction;
        double accumulated = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            if (accumulated + lengths[i] >= targetLength) {
                double remaining = targetLength - accumulated;
                double t = remaining / lengths[i];
                Point pStart = pts.get(i);
                Point pEnd = pts.get(i + 1);
                int x = (int) (pStart.x + t * (pEnd.x - pStart.x));
                int y = (int) (pStart.y + t * (pEnd.y - pStart.y));
                return new Point(x, y);
            }
            accumulated += lengths[i];
        }
        return pts.get(pts.size() - 1);
    }

    public double getClosestFraction(Point p) {
        List<Point> pts = getPoints();
        if (pts.isEmpty()) return 0.5;
        if (pts.size() == 1) return 0.5;
        
        double totalLength = 0;
        double[] lengths = new double[pts.size() - 1];
        for (int i = 0; i < pts.size() - 1; i++) {
            lengths[i] = pts.get(i).distance(pts.get(i + 1));
            totalLength += lengths[i];
        }
        
        if (totalLength == 0) return 0.5;
        
        double minDist = Double.MAX_VALUE;
        double bestAccumulated = 0;
        double bestSegmentFraction = 0;
        
        double currentAccumulated = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            Point pStart = pts.get(i);
            Point pEnd = pts.get(i + 1);
            
            Point proj = projectPointToSegment(p, pStart, pEnd);
            double dist = p.distance(proj);
            if (dist < minDist) {
                minDist = dist;
                bestAccumulated = currentAccumulated;
                if (lengths[i] > 0) {
                    bestSegmentFraction = pStart.distance(proj);
                } else {
                    bestSegmentFraction = 0;
                }
            }
            currentAccumulated += lengths[i];
        }
        
        double fraction = (bestAccumulated + bestSegmentFraction) / totalLength;
        return Math.max(0.0, Math.min(1.0, fraction));
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
