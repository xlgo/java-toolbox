package com.aqishi.toolbox.feature.diagram.domain;

import org.junit.jupiter.api.Test;

import java.awt.Point;
import java.awt.geom.Point2D;

import static org.junit.jupiter.api.Assertions.*;

class FlowNodeTest {

    private static final double DELTA = 1e-6;

    private static FlowNode node(String type, int x, int y) {
        return new FlowNode(type, "n1", "节点", x, y);
    }

    @Test
    void presetDimensionsMatchNodeType() {
        FlowNode decision = node(FlowNode.TYPE_DECISION, 0, 0);
        assertEquals(80, decision.w);
        assertEquals(80, decision.h);

        FlowNode offPage = node(FlowNode.TYPE_OFF_PAGE_CONNECTOR, 0, 0);
        assertEquals(100, offPage.w);
        assertEquals(80, offPage.h);

        FlowNode startEnd = node(FlowNode.TYPE_START_END, 0, 0);
        assertEquals(100, startEnd.w);
        assertEquals(50, startEnd.h);

        FlowNode lifeline = node(FlowNode.TYPE_LIFELINE, 0, 0);
        assertEquals(100, lifeline.w);
        assertEquals(300, lifeline.h);

        FlowNode actor = node(FlowNode.TYPE_ACTOR, 0, 0);
        assertEquals(60, actor.w);
        assertEquals(300, actor.h);

        FlowNode activation = node(FlowNode.TYPE_ACTIVATION, 0, 0);
        assertEquals(20, activation.w);
        assertEquals(80, activation.h);

        FlowNode process = node(FlowNode.TYPE_PROCESS, 0, 0);
        assertEquals(120, process.w);
        assertEquals(50, process.h);
    }

    @Test
    void portPointsAreEdgeMidpoints() {
        FlowNode node = node(FlowNode.TYPE_PROCESS, 10, 20); // 120x50
        assertEquals(new Point(70, 20), node.getPortPoint(0));  // 上
        assertEquals(new Point(130, 45), node.getPortPoint(1)); // 右
        assertEquals(new Point(70, 70), node.getPortPoint(2));  // 下
        assertEquals(new Point(10, 45), node.getPortPoint(3));  // 左
        // 非法端口回退到中心
        assertEquals(new Point(70, 45), node.getPortPoint(99));
    }

    @Test
    void ellipseOutlineSnapsAlongRayFromCenter() {
        FlowNode node = node(FlowNode.TYPE_START_END, 0, 0); // 100x50, 中心 (50,25)
        assertEquals(new Point(100, 25), node.getClosestOutlinePoint(new Point(200, 25)));
        assertEquals(new Point(50, 0), node.getClosestOutlinePoint(new Point(50, -100)));
        assertEquals(new Point(0, 25), node.getClosestOutlinePoint(new Point(-200, 25)));
        assertEquals(new Point(50, 50), node.getClosestOutlinePoint(new Point(50, 200)));
    }

    @Test
    void diamondOutlineSnapsToEdges() {
        FlowNode node = node(FlowNode.TYPE_DECISION, 0, 0); // 80x80, 顶点在四边中点
        assertEquals(new Point(80, 40), node.getClosestOutlinePoint(new Point(200, 40)));
        assertEquals(new Point(40, 0), node.getClosestOutlinePoint(new Point(40, -100)));
        // 右上 45° 方向，落在 (40,0)-(80,40) 边的中点
        assertEquals(new Point(60, 20), node.getClosestOutlinePoint(new Point(90, -10)));
    }

    @Test
    void rectangleOutlineClampsToNearestEdge() {
        FlowNode node = new FlowNode(FlowNode.TYPE_PROCESS, "n1", "节点", 0, 0); // 120x50
        // 边由轴向距离最近者胜出：probe 距右边 20 < 距顶边 25，吸附右边
        assertEquals(new Point(120, 25), node.getClosestOutlinePoint(new Point(140, 25)));
        // 内部点吸附到最近的边：中心点距上/下各 25，平局时上边优先
        assertEquals(new Point(60, 0), node.getClosestOutlinePoint(new Point(60, 25)));
        assertEquals(new Point(0, 25), node.getClosestOutlinePoint(new Point(-10, 25)));
        // 现有行为备忘：probe 在右侧但距顶边更近（25 < 80）时吸附到右上角，
        // 而非右边中点——选边只看单轴距离，不看投影点距离
        assertEquals(new Point(120, 0), node.getClosestOutlinePoint(new Point(200, 25)));
    }

    @Test
    void outlinePointStaysWithinNodeBoundsForAllShapes() {
        String[] types = {
                FlowNode.TYPE_PROCESS, FlowNode.TYPE_DECISION, FlowNode.TYPE_DATA,
                FlowNode.TYPE_PREPARATION, FlowNode.TYPE_CARD,
                FlowNode.TYPE_OFF_PAGE_CONNECTOR, FlowNode.TYPE_DISPLAY,
                FlowNode.TYPE_START_END, FlowNode.TYPE_TERMINATOR, FlowNode.TYPE_DELAY
        };
        Point[] probes = {
                new Point(500, 45), new Point(-300, 45), new Point(70, -300),
                new Point(70, 500), new Point(500, -300), new Point(-300, 500),
                new Point(500, 500), new Point(-300, -300)
        };
        for (String type : types) {
            FlowNode node = node(type, 10, 20);
            for (Point probe : probes) {
                Point closest = node.getClosestOutlinePoint(probe);
                assertTrue(closest.x >= node.x && closest.x <= node.x + node.w,
                        type + " 的吸附点 x 越界: " + closest);
                assertTrue(closest.y >= node.y && closest.y <= node.y + node.h,
                        type + " 的吸附点 y 越界: " + closest);
            }
        }
    }

    @Test
    void connectionPointSnapsToOutlineForRegularNodes() {
        FlowNode node = node(FlowNode.TYPE_PROCESS, 0, 0); // 120x50
        // 相对坐标在节点外（上方），连线端点吸附到顶边
        assertEquals(new Point(36, 0), node.getConnectionPoint(0.3, -0.5));
        // 顶边中点恰在轮廓上，原样返回
        assertEquals(new Point(60, 0), node.getConnectionPoint(0.5, 0.0));
    }

    @Test
    void lifelineConnectionPointIsNotSnapped() {
        FlowNode node = node(FlowNode.TYPE_LIFELINE, 0, 0); // 100x300
        // 生命线/角色按相对坐标直取，不做轮廓吸附
        assertEquals(new Point(50, 150), node.getConnectionPoint(0.5, 0.5));
    }

    @Test
    void relativePointRoundTripsThroughConnectionPoint() {
        FlowNode process = node(FlowNode.TYPE_PROCESS, 10, 20);
        Point conn = process.getConnectionPoint(0.5, 0.0);
        Point2D.Double rel = process.getClosestRelativePoint(conn);
        assertEquals(0.5, rel.x, DELTA);
        assertEquals(0.0, rel.y, DELTA);

        FlowNode ellipse = node(FlowNode.TYPE_START_END, 10, 20);
        Point connE = ellipse.getConnectionPoint(1.0, 0.5);
        Point2D.Double relE = ellipse.getClosestRelativePoint(connE);
        assertEquals(1.0, relE.x, DELTA);
        assertEquals(0.5, relE.y, DELTA);
    }

    @Test
    void relativePointIsClampedToUnitRange() {
        FlowNode node = node(FlowNode.TYPE_PROCESS, 0, 0);
        Point2D.Double rel = node.getClosestRelativePoint(new Point(999, 999));
        assertEquals(1.0, rel.x, DELTA);
        assertEquals(1.0, rel.y, DELTA);
    }

    @Test
    void lifelineRelativePointSnapsToCenterLineBelowHeader() {
        FlowNode node = node(FlowNode.TYPE_LIFELINE, 0, 0); // 100x300, headerH=35
        // 头部以下：x 吸附到中轴 0.5，y 按比例
        Point2D.Double rel = node.getClosestRelativePoint(new Point(80, 150));
        assertEquals(0.5, rel.x, DELTA);
        assertEquals(0.5, rel.y, DELTA);
        // y 不越出虚线末端（h-5）
        Point2D.Double bottom = node.getClosestRelativePoint(new Point(80, 9999));
        assertEquals(0.5, bottom.x, DELTA);
        assertEquals(295.0 / 300.0, bottom.y, DELTA);
        // 头部区域走普通轮廓吸附（顶边）
        Point2D.Double header = node.getClosestRelativePoint(new Point(20, 10));
        assertEquals(0.2, header.x, DELTA);
        assertEquals(0.0, header.y, DELTA);
    }
}
