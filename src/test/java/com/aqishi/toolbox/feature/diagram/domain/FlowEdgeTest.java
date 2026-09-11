package com.aqishi.toolbox.feature.diagram.domain;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.geom.Point2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FlowEdgeTest {

    private static final double DELTA = 1e-6;

    private static FlowNode process(int x, int y) {
        return new FlowNode(FlowNode.TYPE_PROCESS, "p", "步骤", x, y); // 120x50
    }

    @Test
    void portRelativeCoordsMapToEdgeMidpoints() {
        assertEquals(new Point2D.Double(0.5, 0.0), FlowEdge.getPortRelativeCoords(0));
        assertEquals(new Point2D.Double(1.0, 0.5), FlowEdge.getPortRelativeCoords(1));
        assertEquals(new Point2D.Double(0.5, 1.0), FlowEdge.getPortRelativeCoords(2));
        assertEquals(new Point2D.Double(0.0, 0.5), FlowEdge.getPortRelativeCoords(3));
        assertEquals(new Point2D.Double(0.5, 0.5), FlowEdge.getPortRelativeCoords(99));
    }

    @Test
    void portConstructorDerivesRelativeCoordinates() {
        FlowEdge edge = new FlowEdge("e", "l", process(0, 0), process(200, 0), 0, 2);
        assertEquals(0.5, edge.sourceRelX, DELTA);
        assertEquals(0.0, edge.sourceRelY, DELTA);
        assertEquals(0.5, edge.targetRelX, DELTA);
        assertEquals(1.0, edge.targetRelY, DELTA);
    }

    @Test
    void relativeConstructorDerivesApproximatePorts() {
        FlowEdge edge = new FlowEdge("e", "l", process(0, 0), process(200, 0),
                1.0, 0.5, 0.0, 0.5);
        assertEquals(1, edge.sourcePort);  // 右边
        assertEquals(3, edge.targetPort);  // 左边
    }

    @Test
    void portIndexFromRelativePicksNearestEdge() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 0);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 0, 0);
        assertEquals(1, edge.getPortIndexFromRel(0.9, 0.5, true));
        assertEquals(2, edge.getPortIndexFromRel(0.5, 0.9, true));
        assertEquals(3, edge.getPortIndexFromRel(0.1, 0.5, true));
        assertEquals(0, edge.getPortIndexFromRel(0.5, 0.1, true));
        // 正中心四边等距，平局时上边优先
        assertEquals(0, edge.getPortIndexFromRel(0.5, 0.5, true));
    }

    @Test
    void lifelineBelowHeaderResolvesToLeftOrRightByPeerPosition() {
        FlowNode lifeline = new FlowNode(FlowNode.TYPE_LIFELINE, "lf", "对象", 0, 0); // 100x300
        FlowNode right = process(300, 0);
        FlowNode left = process(-200, 0);

        FlowEdge toRight = new FlowEdge("e1", "l", lifeline, right, 0, 0);
        assertEquals(1, toRight.getPortIndexFromRel(0.5, 0.5, true)); // 对端在右 → 右

        FlowEdge toLeft = new FlowEdge("e2", "l", lifeline, left, 0, 0);
        assertEquals(3, toLeft.getPortIndexFromRel(0.5, 0.5, true)); // 对端在左 → 左

        // 头部区域（relY*h <= 35）不走生命线特例，按最近边判定
        assertEquals(0, toRight.getPortIndexFromRel(0.5, 0.05, true));
    }

    @Test
    void straightRoutingHasOnlyEndpoints() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 0);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 1.0, 0.5, 0.0, 0.5);
        edge.routingType = "straight";

        List<Point> pts = edge.getPoints();
        assertEquals(2, pts.size());
        assertEquals(new Point(120, 25), pts.get(0));
        assertEquals(new Point(200, 25), pts.get(1));
    }

    @Test
    void customWaypointsOverrideAutoRouting() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 0);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 1.0, 0.5, 0.0, 0.5);
        edge.routingType = "manhattan";
        edge.waypoints = List.of(new Point(150, 60), new Point(170, 60));

        List<Point> pts = edge.getPoints();
        assertEquals(List.of(
                new Point(120, 25), new Point(150, 60),
                new Point(170, 60), new Point(200, 25)), pts);
    }

    @Test
    void manhattanRoutingProducesExpectedOrthogonalPath() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 100);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 1.0, 0.5, 0.0, 0.5);
        edge.routingType = "manhattan";

        // 起点 (120,25) 向右 20px，终点 (200,125) 向左 20px，中间以直角拐角衔接
        assertEquals(List.of(
                new Point(120, 25),
                new Point(140, 25),
                new Point(140, 125),
                new Point(180, 125),
                new Point(200, 125)), edge.getPoints());
    }

    @Test
    void manhattanSegmentsAreAlwaysAxisAligned() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 100);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 0.5, 1.0, 0.5, 0.0);
        edge.routingType = "manhattan";

        List<Point> pts = edge.getPoints();
        for (int i = 0; i < pts.size() - 1; i++) {
            Point a = pts.get(i);
            Point b = pts.get(i + 1);
            assertTrue(a.x == b.x || a.y == b.y,
                    "第 " + i + " 段不是水平/垂直: " + a + " -> " + b);
        }
    }

    @Test
    void bezierRoutingSamplesCurveBetweenEndpoints() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 0);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 1.0, 0.5, 0.0, 0.5);
        edge.routingType = "bezier";

        List<Point> pts = edge.getPoints();
        assertEquals(21, pts.size()); // 起点 + 19 个采样点 + 终点
        assertEquals(new Point(120, 25), pts.get(0));
        assertEquals(new Point(200, 25), pts.get(20));
    }

    @Test
    void pointAtFractionInterpolatesAlongPath() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 0);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 1.0, 0.5, 0.0, 0.5);
        edge.routingType = "straight"; // (120,25) -> (200,25)，全长 80

        assertEquals(new Point(120, 25), edge.getPointAtFraction(0.0));
        assertEquals(new Point(200, 25), edge.getPointAtFraction(1.0));
        assertEquals(new Point(160, 25), edge.getPointAtFraction(0.5));
    }

    @Test
    void closestFractionInvertsPointAtFraction() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 0);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 1.0, 0.5, 0.0, 0.5);
        edge.routingType = "straight";

        assertEquals(0.0, edge.getClosestFraction(new Point(120, 25)), DELTA);
        assertEquals(1.0, edge.getClosestFraction(new Point(200, 25)), DELTA);
        // 在线段中点正下方，投影仍落在中点
        assertEquals(0.5, edge.getClosestFraction(new Point(160, 60)), DELTA);
    }

    @Test
    void closestFractionIsClampedForPointsBeyondEnds() {
        FlowNode source = process(0, 0);
        FlowNode target = process(200, 0);
        FlowEdge edge = new FlowEdge("e", "l", source, target, 1.0, 0.5, 0.0, 0.5);
        edge.routingType = "straight";

        assertEquals(0.0, edge.getClosestFraction(new Point(-500, 25)), DELTA);
        assertEquals(1.0, edge.getClosestFraction(new Point(999, 25)), DELTA);
    }
}
