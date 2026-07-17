package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class CanvasPanel extends JPanel {
    private final FlowchartPanel parent;
    private FlowNode hoveredNode = null;
    private boolean isExtending = false;
    private int snapOffsetX = 0;
    private int snapOffsetY = 0;

    CanvasPanel(FlowchartPanel parent) {
        this.parent = parent;
        setPreferredSize(new Dimension(1000, 800));
        setBackground(UIManager.getColor("Panel.background"));
        setFocusable(true);
        // 允许接收并拦截底层鼠标事件以统一转换缩放坐标
        enableEvents(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        // 允许接收并拦截底层鼠标事件
        enableEvents(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);

        // 监听 DND 拖拽放下 (从左侧备选库拖入画布)
        setDropTarget(new DropTarget(this, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent dtde) {
                try {
                    if (dtde.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                        dtde.acceptDrop(DnDConstants.ACTION_COPY);
                        String shapeType = (String) dtde.getTransferable().getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
                        Point dropPoint = dtde.getLocation();
                        
                        // 直接在拖放下的中心位置创建节点
                        CanvasPanel.this.parent.addNewNodeAt(shapeType, dropPoint);
                        dtde.dropComplete(true);
                    } else {
                        dtde.rejectDrop();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                    dtde.rejectDrop();
                }
            }
        }));

        // 监听鼠标交互
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2) {
                    FlowNode clickedNode = getNodeAtPoint(e.getPoint());
                    if (clickedNode != null) {
                        editNodeName(clickedNode);
                        return;
                    }
                    // 如果双击在连线文字区域，也可以编辑连线文本
                    for (FlowEdge edge : CanvasPanel.this.parent.edges) {
                        Rectangle bounds = getEdgeLabelBounds(edge);
                        if (bounds != null && bounds.contains(e.getPoint())) {
                            editEdgeLabel(edge);
                            return;
                        }
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                CanvasPanel.this.parent.currentMousePoint = e.getPoint();

                // 只要是左键点击，先记下可能的拖拽起始状态以供撤销使用
                if (SwingUtilities.isLeftMouseButton(e) && CanvasPanel.this.parent.currentMode == FlowchartPanel.Mode.SELECT) {
                    try {
                        CanvasPanel.this.parent.dragStartState = CanvasPanel.this.parent.serializeToJson();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }

                if (SwingUtilities.isRightMouseButton(e)) {
                    if (CanvasPanel.this.parent.selectedEdge != null) {
                        int wpIdx = getWaypointHandleAt(CanvasPanel.this.parent.selectedEdge, e.getPoint());
                        if (wpIdx != -1) {
                            CanvasPanel.this.parent.selectedEdge.waypoints.remove(wpIdx);
                            repaint();
                            return;
                        }
                    }
                    showContextMenu(e);
                    return;
                }

                // 1. 快捷添加模式
                if (CanvasPanel.this.parent.currentMode != FlowchartPanel.Mode.SELECT && CanvasPanel.this.parent.currentMode != FlowchartPanel.Mode.CONNECT) {
                    CanvasPanel.this.parent.addNewNodeAt(getShapeTypeFromMode(CanvasPanel.this.parent.currentMode), e.getPoint());
                    CanvasPanel.this.parent.setMode(FlowchartPanel.Mode.SELECT);
                    return;
                }

                // 2. 连线模式
                if (CanvasPanel.this.parent.currentMode == FlowchartPanel.Mode.CONNECT) {
                    initiateConnection(e.getPoint());
                    return;
                }

                // 3. 选择/移动模式
                if (CanvasPanel.this.parent.currentMode == FlowchartPanel.Mode.SELECT) {
                    // 3.1 改变大小手柄判定
                    if (CanvasPanel.this.parent.selectedNode != null && CanvasPanel.this.parent.selectedNodes.size() == 1) {
                        FlowchartPanel.DragState resizeState = getResizeHandleState(CanvasPanel.this.parent.selectedNode, e.getPoint());
                        if (resizeState != FlowchartPanel.DragState.NONE) {
                            CanvasPanel.this.parent.currentDragState = resizeState;
                            return;
                        }
                    }

                    // 3.2 连线手柄/弯折点判定
                    if (CanvasPanel.this.parent.selectedEdge != null) {
                        Point p1 = CanvasPanel.this.parent.selectedEdge.source.getConnectionPoint(
                            CanvasPanel.this.parent.selectedEdge.sourceRelX,
                            CanvasPanel.this.parent.selectedEdge.sourceRelY
                        );
                        if (e.getPoint().distance(p1) < 8.0) {
                            CanvasPanel.this.parent.currentDragState = FlowchartPanel.DragState.DRAG_EDGE_START;
                            CanvasPanel.this.parent.draggedEdge = CanvasPanel.this.parent.selectedEdge;
                            CanvasPanel.this.parent.tempDragPoint = e.getPoint();
                            return;
                        }
                        Point p2 = CanvasPanel.this.parent.selectedEdge.target.getConnectionPoint(
                            CanvasPanel.this.parent.selectedEdge.targetRelX,
                            CanvasPanel.this.parent.selectedEdge.targetRelY
                        );
                        if (e.getPoint().distance(p2) < 8.0) {
                            CanvasPanel.this.parent.currentDragState = FlowchartPanel.DragState.DRAG_EDGE_END;
                            CanvasPanel.this.parent.draggedEdge = CanvasPanel.this.parent.selectedEdge;
                            CanvasPanel.this.parent.tempDragPoint = e.getPoint();
                            return;
                        }
                        int wpIdx = getWaypointHandleAt(CanvasPanel.this.parent.selectedEdge, e.getPoint());
                        if (wpIdx != -1) {
                            CanvasPanel.this.parent.currentDragState = FlowchartPanel.DragState.DRAG_WAYPOINT;
                            CanvasPanel.this.parent.draggedEdge = CanvasPanel.this.parent.selectedEdge;
                            CanvasPanel.this.parent.draggedWaypointIndex = wpIdx;
                            return;
                        }
                        int midIdx = getMidpointHandleAt(CanvasPanel.this.parent.selectedEdge, e.getPoint());
                        if (midIdx != -1) {
                            List<Point> pts = CanvasPanel.this.parent.selectedEdge.getPoints();
                            Point mid = new Point((pts.get(midIdx).x + pts.get(midIdx+1).x)/2, (pts.get(midIdx).y + pts.get(midIdx+1).y)/2);
                            int insertIdx = Math.max(0, midIdx);
                            if (insertIdx > CanvasPanel.this.parent.selectedEdge.waypoints.size()) insertIdx = CanvasPanel.this.parent.selectedEdge.waypoints.size();
                            CanvasPanel.this.parent.selectedEdge.waypoints.add(insertIdx, mid);
                            CanvasPanel.this.parent.currentDragState = FlowchartPanel.DragState.DRAG_WAYPOINT;
                            CanvasPanel.this.parent.draggedEdge = CanvasPanel.this.parent.selectedEdge;
                            CanvasPanel.this.parent.draggedWaypointIndex = insertIdx;
                            repaint();
                            return;
                        }
                    }

                    // 3.3 悬停节点连线标记判定
                    if (hoveredNode != null) {
                        int markerIdx = getConnectionMarkerAt(hoveredNode, e.getPoint());
                        if (markerIdx != -1) {
                            CanvasPanel.this.parent.isConnecting = true;
                            CanvasPanel.this.parent.connectSourceNode = hoveredNode;
                            CanvasPanel.this.parent.connectSourcePortIndex = markerIdx;
                            
                            // 根据生命线和普通节点精准计算起点的相对比例
                            if (hoveredNode.type.equals(FlowNode.TYPE_LIFELINE) || hoveredNode.type.equals(FlowNode.TYPE_ACTOR)) {
                                int headerH = hoveredNode.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
                                int gap = 25;
                                int targetY = hoveredNode.y + headerH + markerIdx * gap;
                                double relY = (double) (targetY - hoveredNode.y) / hoveredNode.h;
                                CanvasPanel.this.parent.connectSourceRelX = 0.5;
                                CanvasPanel.this.parent.connectSourceRelY = relY;
                            } else if (hoveredNode.type.equals(FlowNode.TYPE_ACTIVATION)) {
                                double[] relYs = {0.0, 0.5, 1.0};
                                CanvasPanel.this.parent.connectSourceRelX = 0.5;
                                CanvasPanel.this.parent.connectSourceRelY = relYs[markerIdx];
                            } else {
                                double[][] relCoords = {
                                    {0.5, 0.0}, {1.0, 0.5}, {0.5, 1.0}, {0.0, 0.5},
                                    {0.0, 0.0}, {1.0, 0.0}, {0.0, 1.0}, {1.0, 1.0}
                                };
                                CanvasPanel.this.parent.connectSourceRelX = relCoords[markerIdx][0];
                                CanvasPanel.this.parent.connectSourceRelY = relCoords[markerIdx][1];
                            }
                            return;
                        }
                    }

                    // 3.4 节点选择与拖动判定
                    FlowNode clickedNode = getNodeAtPoint(e.getPoint());
                    if (clickedNode != null) {
                        if (e.isControlDown()) {
                            if (CanvasPanel.this.parent.selectedNodes.contains(clickedNode)) {
                                CanvasPanel.this.parent.selectedNodes.remove(clickedNode);
                            } else {
                                CanvasPanel.this.parent.selectedNodes.add(clickedNode);
                            }
                        } else {
                            if (!CanvasPanel.this.parent.selectedNodes.contains(clickedNode)) {
                                CanvasPanel.this.parent.selectedNodes.clear();
                                CanvasPanel.this.parent.selectedNodes.add(clickedNode);
                            }
                        }
                        CanvasPanel.this.parent.selectedNode = clickedNode;
                        CanvasPanel.this.parent.selectedEdge = null;
                        CanvasPanel.this.parent.draggedNode = clickedNode;
                        CanvasPanel.this.parent.currentDragState = FlowchartPanel.DragState.MOVE_NODE;
                        CanvasPanel.this.parent.updatePropertyPanel();
                        repaint();
                        return;
                    }

                    // 3.5 连线选择判定
                    FlowEdge clickedEdge = getEdgeAtPoint(e.getPoint());
                    if (clickedEdge != null) {
                        CanvasPanel.this.parent.clearSelection();
                        CanvasPanel.this.parent.selectedEdge = clickedEdge;
                        CanvasPanel.this.parent.updatePropertyPanel();
                        repaint();
                        return;
                    }

                    // 3.6 框选判定
                    CanvasPanel.this.parent.clearSelection();
                    CanvasPanel.this.parent.selectionStart = e.getPoint();
                    CanvasPanel.this.parent.selectionRect = new Rectangle(CanvasPanel.this.parent.selectionStart);
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.MOVE_NODE && CanvasPanel.this.parent.draggedNode != null) {
                    for (FlowNode n : CanvasPanel.this.parent.selectedNodes) {
                        n.x += snapOffsetX;
                        n.y += snapOffsetY;
                    }
                    snapOffsetX = 0;
                    snapOffsetY = 0;
                }

                Point zoomPt = getZoomedPoint(e.getPoint());
                if (CanvasPanel.this.parent.isConnecting && CanvasPanel.this.parent.connectSourceNode != null) {
                    completeConnection(zoomPt);
                    // 连线工具使用一次就自动恢复为选择模式
                    CanvasPanel.this.parent.setMode(FlowchartPanel.Mode.SELECT);
                }

                if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_EDGE_START && CanvasPanel.this.parent.draggedEdge != null) {
                    FlowNode target = getNodeAtPoint(e.getPoint());
                    if (target != null) {
                        Point[] outPt = new Point[1];
                        java.awt.geom.Point2D.Double rel = snapToBestPosition(target, e.getPoint(), outPt);
                        
                        FlowNode other = CanvasPanel.this.parent.draggedEdge.target;
                        if ((target.type.equals(FlowNode.TYPE_LIFELINE) || target.type.equals(FlowNode.TYPE_ACTOR)) &&
                            (other.type.equals(FlowNode.TYPE_LIFELINE) || other.type.equals(FlowNode.TYPE_ACTOR))) {
                            Point otherPt = other.getConnectionPoint(CanvasPanel.this.parent.draggedEdge.targetRelX, CanvasPanel.this.parent.draggedEdge.targetRelY);
                            rel.y = (double)(otherPt.y - target.y) / target.h;
                        }
                        
                        CanvasPanel.this.parent.draggedEdge.source = target;
                        CanvasPanel.this.parent.draggedEdge.sourceRelX = rel.x;
                        CanvasPanel.this.parent.draggedEdge.sourceRelY = rel.y;
                        CanvasPanel.this.parent.draggedEdge.sourcePort = CanvasPanel.this.parent.draggedEdge.getPortIndexFromRel(rel.x, rel.y, true);
                    }
                } else if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_EDGE_END && CanvasPanel.this.parent.draggedEdge != null) {
                    FlowNode target = getNodeAtPoint(e.getPoint());
                    if (target != null) {
                        Point[] outPt = new Point[1];
                        java.awt.geom.Point2D.Double rel = snapToBestPosition(target, e.getPoint(), outPt);
                        
                        FlowNode other = CanvasPanel.this.parent.draggedEdge.source;
                        if ((target.type.equals(FlowNode.TYPE_LIFELINE) || target.type.equals(FlowNode.TYPE_ACTOR)) &&
                            (other.type.equals(FlowNode.TYPE_LIFELINE) || other.type.equals(FlowNode.TYPE_ACTOR))) {
                            Point otherPt = other.getConnectionPoint(CanvasPanel.this.parent.draggedEdge.sourceRelX, CanvasPanel.this.parent.draggedEdge.sourceRelY);
                            rel.y = (double)(otherPt.y - target.y) / target.h;
                        }
                        
                        CanvasPanel.this.parent.draggedEdge.target = target;
                        CanvasPanel.this.parent.draggedEdge.targetRelX = rel.x;
                        CanvasPanel.this.parent.draggedEdge.targetRelY = rel.y;
                        CanvasPanel.this.parent.draggedEdge.targetPort = CanvasPanel.this.parent.draggedEdge.getPortIndexFromRel(rel.x, rel.y, false);
                    }
                }

                // 拖拽类修改判定
                if (CanvasPanel.this.parent.dragStartState != null) {
                    try {
                        String currentState = CanvasPanel.this.parent.serializeToJson();
                        if (!currentState.equals(CanvasPanel.this.parent.dragStartState)) {
                            CanvasPanel.this.parent.undoStack.push(CanvasPanel.this.parent.dragStartState);
                            CanvasPanel.this.parent.redoStack.clear();
                            CanvasPanel.this.parent.updateUndoRedoButtons();
                        }
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                    CanvasPanel.this.parent.dragStartState = null;
                }

                CanvasPanel.this.parent.isConnecting = false;
                CanvasPanel.this.parent.connectSourceNode = null;
                CanvasPanel.this.parent.currentDragState = FlowchartPanel.DragState.NONE;
                CanvasPanel.this.parent.draggedNode = null;
                CanvasPanel.this.parent.draggedEdge = null;
                CanvasPanel.this.parent.draggedWaypointIndex = -1;
                CanvasPanel.this.parent.tempDragPoint = null;
                CanvasPanel.this.parent.tempTargetNode = null;
                CanvasPanel.this.parent.tempTargetPortIndex = -1;
                CanvasPanel.this.parent.selectionStart = null;
                CanvasPanel.this.parent.selectionRect = null;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - CanvasPanel.this.parent.currentMousePoint.x;
                int dy = e.getY() - CanvasPanel.this.parent.currentMousePoint.y;
                CanvasPanel.this.parent.currentMousePoint = e.getPoint();

                if (CanvasPanel.this.parent.isConnecting && CanvasPanel.this.parent.connectSourceNode != null) {
                    CanvasPanel.this.parent.tempTargetNode = null;
                    FlowNode target = getNodeAtPoint(e.getPoint());
                    if (target != null && target != CanvasPanel.this.parent.connectSourceNode) {
                        CanvasPanel.this.parent.tempTargetNode = target;
                        Point[] outPt = new Point[1];
                        java.awt.geom.Point2D.Double rel = snapToBestPosition(target, e.getPoint(), outPt);
                        
                        // 正在拉线预览时，如果两端都是生命线，强制Y轴对齐，使虚线水平呈现
                        FlowNode source = CanvasPanel.this.parent.connectSourceNode;
                        if ((source.type.equals(FlowNode.TYPE_LIFELINE) || source.type.equals(FlowNode.TYPE_ACTOR)) &&
                            (target.type.equals(FlowNode.TYPE_LIFELINE) || target.type.equals(FlowNode.TYPE_ACTOR))) {
                            Point srcPt = source.getConnectionPoint(CanvasPanel.this.parent.connectSourceRelX, CanvasPanel.this.parent.connectSourceRelY);
                            rel.y = (double)(srcPt.y - target.y) / target.h;
                        }
                        
                        CanvasPanel.this.parent.tempTargetRelX = rel.x;
                        CanvasPanel.this.parent.tempTargetRelY = rel.y;
                        CanvasPanel.this.parent.tempTargetPortIndex = getClosestPortIndex(target, e.getPoint());
                    }
                    repaint();
                    return;
                }

                if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.MOVE_NODE && CanvasPanel.this.parent.draggedNode != null) {
                    FlowNode dn = CanvasPanel.this.parent.draggedNode;
                    
                    // 1. 数据层平滑累加纯鼠标位移，永远不锁死拖拽
                    for (FlowNode n : CanvasPanel.this.parent.selectedNodes) {
                        n.x += dx;
                        n.y += dy;
                    }
                    
                    // 2. 预先重置吸附偏移量
                    snapOffsetX = 0;
                    snapOffsetY = 0;
                    
                    // 3. 计算最新的吸附偏移量
                    int[] srcXs = {dn.x, dn.x + dn.w / 2, dn.x + dn.w};
                    int[] srcYs = {dn.y, dn.y + dn.h / 2, dn.y + dn.h};
                    
                    int threshold = 8; // 8像素磁吸阈值
                    boolean snapX = false;
                    boolean snapY = false;
                    
                    for (FlowNode target : CanvasPanel.this.parent.nodes) {
                        if (CanvasPanel.this.parent.selectedNodes.contains(target)) continue;
                        
                        int[] tgtXs = {target.x, target.x + target.w / 2, target.x + target.w};
                        int[] tgtYs = {target.y, target.y + target.h / 2, target.y + target.h};
                        
                        for (int sx : srcXs) {
                            for (int tx : tgtXs) {
                                if (Math.abs(sx - tx) < threshold) {
                                    snapOffsetX = tx - sx;
                                    snapX = true;
                                    break;
                                }
                            }
                            if (snapX) break;
                        }
                        
                        for (int sy : srcYs) {
                            for (int ty : tgtYs) {
                                if (Math.abs(sy - ty) < threshold) {
                                    snapOffsetY = ty - sy;
                                    snapY = true;
                                    break;
                                }
                            }
                            if (snapY) break;
                        }
                    }
                    
                    // 处理时序图激活条磁吸生命线逻辑
                    for (FlowNode n : CanvasPanel.this.parent.selectedNodes) {
                        if (n.type.equals(FlowNode.TYPE_ACTIVATION)) {
                            FlowNode closestLifeline = null;
                            double minDist = 40.0;
                            int activationCenterX = n.x + snapOffsetX + n.w / 2;
                            for (FlowNode targetNode : CanvasPanel.this.parent.nodes) {
                                if (targetNode.type.equals(FlowNode.TYPE_LIFELINE) || targetNode.type.equals(FlowNode.TYPE_ACTOR)) {
                                    int lifelineCenterX = targetNode.x + targetNode.w / 2;
                                    double dist = Math.abs(activationCenterX - lifelineCenterX);
                                    if (dist < minDist) {
                                        minDist = dist;
                                        closestLifeline = targetNode;
                                    }
                                }
                            }
                            if (closestLifeline != null) {
                                n.x = closestLifeline.x + closestLifeline.w / 2 - n.w / 2 - snapOffsetX;
                            }
                        }
                    }
                    
                    CanvasPanel.this.parent.adjustCanvasSize();
                    repaint();
                } else if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_WAYPOINT) {
                    if (CanvasPanel.this.parent.draggedEdge != null && CanvasPanel.this.parent.draggedWaypointIndex >= 0 && CanvasPanel.this.parent.draggedWaypointIndex < CanvasPanel.this.parent.draggedEdge.waypoints.size()) {
                        Point wp = CanvasPanel.this.parent.draggedEdge.waypoints.get(CanvasPanel.this.parent.draggedWaypointIndex);
                        wp.x += dx;
                        wp.y += dy;
                        repaint();
                    }
                } else if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_EDGE_START || CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_EDGE_END) {
                    CanvasPanel.this.parent.tempDragPoint = e.getPoint();
                    CanvasPanel.this.parent.tempTargetNode = getNodeAtPoint(e.getPoint());
                    if (CanvasPanel.this.parent.tempTargetNode != null) {
                        Point[] outPt = new Point[1];
                        java.awt.geom.Point2D.Double rel = snapToBestPosition(CanvasPanel.this.parent.tempTargetNode, e.getPoint(), outPt);
                        CanvasPanel.this.parent.tempTargetRelX = rel.x;
                        CanvasPanel.this.parent.tempTargetRelY = rel.y;
                        CanvasPanel.this.parent.tempTargetPortIndex = getClosestPortIndex(CanvasPanel.this.parent.tempTargetNode, e.getPoint());
                        CanvasPanel.this.parent.tempDragPoint = outPt[0]; // snap preview end point
                    } else {
                        CanvasPanel.this.parent.tempTargetPortIndex = -1;
                    }
                    repaint();
                } else if (isResizeState(CanvasPanel.this.parent.currentDragState)) {
                    if (CanvasPanel.this.parent.selectedNode != null) {
                        performResize(CanvasPanel.this.parent.selectedNode, CanvasPanel.this.parent.currentDragState, dx, dy);
                        CanvasPanel.this.parent.adjustCanvasSize();
                        repaint();
                    }
                } else if (CanvasPanel.this.parent.selectionStart != null) {
                    int x = Math.min(CanvasPanel.this.parent.selectionStart.x, e.getX());
                    int y = Math.min(CanvasPanel.this.parent.selectionStart.y, e.getY());
                    int w = Math.abs(CanvasPanel.this.parent.selectionStart.x - e.getX());
                    int h = Math.abs(CanvasPanel.this.parent.selectionStart.y - e.getY());
                    CanvasPanel.this.parent.selectionRect.setBounds(x, y, w, h);

                    CanvasPanel.this.parent.selectedNodes.clear();
                    for (FlowNode n : CanvasPanel.this.parent.nodes) {
                        if (CanvasPanel.this.parent.selectionRect.intersects(new Rectangle(n.x, n.y, n.w, n.h))) {
                            CanvasPanel.this.parent.selectedNodes.add(n);
                        }
                    }
                    repaint();
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                if (CanvasPanel.this.parent.currentMode == FlowchartPanel.Mode.SELECT) {
                    FlowNode hitNode = getNodeAtPoint(e.getPoint());
                    if (hitNode != hoveredNode) {
                        hoveredNode = hitNode;
                        repaint();
                    }
                    if (CanvasPanel.this.parent.selectedNode != null && CanvasPanel.this.parent.selectedNodes.size() == 1) {
                        FlowchartPanel.DragState resizeState = getResizeHandleState(CanvasPanel.this.parent.selectedNode, e.getPoint());
                        if (resizeState != FlowchartPanel.DragState.NONE) {
                            switch (resizeState) {
                                case RESIZE_TL: setCursor(Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)); break;
                                case RESIZE_TC: setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)); break;
                                case RESIZE_TR: setCursor(Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)); break;
                                case RESIZE_ML: setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)); break;
                                case RESIZE_MR: setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)); break;
                                case RESIZE_BL: setCursor(Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)); break;
                                case RESIZE_BC: setCursor(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)); break;
                                case RESIZE_BR: setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)); break;
                            }
                            return;
                        }
                    }
                    if (hoveredNode != null) {
                        int markerIdx = getConnectionMarkerAt(hoveredNode, e.getPoint());
                        if (markerIdx != -1) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            return;
                        }
                    }
                    if (CanvasPanel.this.parent.selectedNode != null && isPointInConnectionHandle(CanvasPanel.this.parent.selectedNode, e.getPoint())) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                        return;
                    }
                    if (CanvasPanel.this.parent.selectedEdge != null) {
                        if (getWaypointHandleAt(CanvasPanel.this.parent.selectedEdge, e.getPoint()) != -1) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                            return;
                        }
                        if (getMidpointHandleAt(CanvasPanel.this.parent.selectedEdge, e.getPoint()) != -1) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            return;
                        }
                        Point p1 = CanvasPanel.this.parent.selectedEdge.source.getConnectionPoint(
                            CanvasPanel.this.parent.selectedEdge.sourceRelX,
                            CanvasPanel.this.parent.selectedEdge.sourceRelY
                        );
                        Point p2 = CanvasPanel.this.parent.selectedEdge.target.getConnectionPoint(
                            CanvasPanel.this.parent.selectedEdge.targetRelX,
                            CanvasPanel.this.parent.selectedEdge.targetRelY
                        );
                        if (e.getPoint().distance(p1) < 8.0 || e.getPoint().distance(p2) < 8.0) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            return;
                        }
                    }
                } else {
                    FlowNode hitNode = getNodeAtPoint(e.getPoint());
                    if (hitNode != hoveredNode) {
                        hoveredNode = hitNode;
                        repaint();
                    }
                }
                setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
            }
        };

        addMouseListener(listener);
        addMouseMotionListener(listener);

        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                    deleteSelected();
                } else if (e.getKeyCode() == KeyEvent.VK_F2 || e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (CanvasPanel.this.parent.selectedNode != null) {
                        editNodeName(CanvasPanel.this.parent.selectedNode);
                    } else if (CanvasPanel.this.parent.selectedEdge != null) {
                        editEdgeLabel(CanvasPanel.this.parent.selectedEdge);
                    }
                } else if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Z) {
                    CanvasPanel.this.parent.undo();
                } else if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_Y) {
                    CanvasPanel.this.parent.redo();
                }
            }
        });

        // 支持 Ctrl+滚轮 进行缩放，无 Ctrl 时向上传递普通滚轮事件以实现滚动条滚动
        addMouseWheelListener(e -> {
            if (e.isControlDown()) {
                double oldZoom = CanvasPanel.this.parent.zoomFactor;
                if (e.getWheelRotation() < 0) {
                    CanvasPanel.this.parent.zoomFactor = Math.min(3.0, CanvasPanel.this.parent.zoomFactor + 0.1);
                } else {
                    CanvasPanel.this.parent.zoomFactor = Math.max(0.3, CanvasPanel.this.parent.zoomFactor - 0.1);
                }
                if (CanvasPanel.this.parent.zoomFactor != oldZoom) {
                    CanvasPanel.this.parent.adjustCanvasSize();
                    repaint();
                }
            } else {
                // 将普通的滚轮事件分发给外层的 ScrollPane，从而启用丝滑的画面滚动
                if (getParent() != null) {
                    getParent().dispatchEvent(e);
                }
            }
        });
    }

    private String getShapeTypeFromMode(FlowchartPanel.Mode mode) {
        switch (mode) {
            case ADD_START_END: return FlowNode.TYPE_START_END;
            case ADD_DECISION: return FlowNode.TYPE_DECISION;
            case ADD_DATA: return FlowNode.TYPE_DATA;
            case ADD_DATABASE: return FlowNode.TYPE_DATABASE;
            case ADD_CLOUD: return FlowNode.TYPE_CLOUD;
            case ADD_PREDEFINED: return FlowNode.TYPE_PREDEFINED;
            case ADD_DOCUMENT: return FlowNode.TYPE_DOCUMENT;
            case ADD_PREPARATION: return FlowNode.TYPE_PREPARATION;
            case ADD_MANUAL_INPUT: return FlowNode.TYPE_MANUAL_INPUT;
            case ADD_ANNOTATION: return FlowNode.TYPE_ANNOTATION;
            case ADD_TERMINATOR: return FlowNode.TYPE_TERMINATOR;
            case ADD_CARD: return FlowNode.TYPE_CARD;
            case ADD_DELAY: return FlowNode.TYPE_DELAY;
            case ADD_DISPLAY: return FlowNode.TYPE_DISPLAY;
            case ADD_INTERNAL_STORAGE: return FlowNode.TYPE_INTERNAL_STORAGE;
            case ADD_OFF_PAGE_CONNECTOR: return FlowNode.TYPE_OFF_PAGE_CONNECTOR;
            default: return FlowNode.TYPE_PROCESS;
        }
    }

    private void showContextMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        FlowNode nodeUnder = getNodeAtPoint(e.getPoint());
        FlowEdge edgeUnder = getEdgeAtPoint(e.getPoint());

        if (nodeUnder != null) {
            CanvasPanel.this.parent.selectedNode = nodeUnder;
            CanvasPanel.this.parent.selectedEdge = null;
            CanvasPanel.this.parent.updatePropertyPanel();
            repaint();
            
            JMenuItem delNodeItem = new JMenuItem("删除该节点");
            delNodeItem.addActionListener(evt -> deleteNode(nodeUnder));
            menu.add(delNodeItem);
            
            JMenuItem renameItem = new JMenuItem("编辑文本内容");
            renameItem.addActionListener(evt -> editNodeName(nodeUnder));
            menu.add(renameItem);
        } else if (edgeUnder != null) {
            CanvasPanel.this.parent.selectedEdge = edgeUnder;
            CanvasPanel.this.parent.selectedNode = null;
            CanvasPanel.this.parent.updatePropertyPanel();
            repaint();
            
            JMenuItem delEdgeItem = new JMenuItem("删除该连线");
            delEdgeItem.addActionListener(evt -> deleteEdge(edgeUnder));
            menu.add(delEdgeItem);
        }

        if (menu.getComponentCount() > 0) {
            double zoom = CanvasPanel.this.parent.zoomFactor;
            menu.show(this, (int)(e.getX() * zoom), (int)(e.getY() * zoom));
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 应用画布缩放
        double zoom = CanvasPanel.this.parent.zoomFactor;
        g2.scale(zoom, zoom);

        drawGrid(g2);
        drawAll(g2);

        // 绘制框选矩形
        if (CanvasPanel.this.parent.selectionRect != null) {
            g2.setColor(new Color(64, 158, 255, 30));
            g2.fillRect(CanvasPanel.this.parent.selectionRect.x, CanvasPanel.this.parent.selectionRect.y, CanvasPanel.this.parent.selectionRect.width, CanvasPanel.this.parent.selectionRect.height);
            g2.setColor(new Color(64, 158, 255, 180));
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4f}, 0.0f));
            g2.drawRect(CanvasPanel.this.parent.selectionRect.x, CanvasPanel.this.parent.selectionRect.y, CanvasPanel.this.parent.selectionRect.width, CanvasPanel.this.parent.selectionRect.height);
        }

        // 连线建立虚线
        if (CanvasPanel.this.parent.isConnecting && CanvasPanel.this.parent.connectSourceNode != null) {
            g2.setColor(UIManager.getColor("Component.warningBorderColor"));
            if (g2.getColor() == null) g2.setColor(Color.ORANGE);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5f}, 0.0f));
            
            Point p1 = CanvasPanel.this.parent.connectSourceNode.getConnectionPoint(
                CanvasPanel.this.parent.connectSourceRelX, 
                CanvasPanel.this.parent.connectSourceRelY
            );
            g2.drawLine(p1.x, p1.y, CanvasPanel.this.parent.currentMousePoint.x, CanvasPanel.this.parent.currentMousePoint.y);

            if (CanvasPanel.this.parent.tempTargetNode != null) {
                Point mPt = CanvasPanel.this.parent.tempTargetNode.getConnectionPoint(
                    CanvasPanel.this.parent.tempTargetRelX,
                    CanvasPanel.this.parent.tempTargetRelY
                );
                // 绘制发光的吸附推荐位置
                g2.setColor(new Color(64, 158, 255, 60));
                g2.fillOval(mPt.x - 10, mPt.y - 10, 20, 20);
                g2.setColor(new Color(64, 158, 255, 180));
                g2.setStroke(new BasicStroke(2.0f));
                g2.drawOval(mPt.x - 6, mPt.y - 6, 12, 12);
                g2.setColor(new Color(64, 158, 255));
                g2.fillOval(mPt.x - 3, mPt.y - 3, 6, 6);
            }
        }

        // 拖拽移动节点时绘制对齐参考线
        if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.MOVE_NODE && CanvasPanel.this.parent.draggedNode != null) {
            FlowNode dn = CanvasPanel.this.parent.draggedNode;
            int threshold = 6;
            g2.setColor(new Color(255, 99, 71, 180)); // 浅红番茄色对齐线
            g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4f, 4f}, 0.0f));
            
            int[] srcXs = {dn.x, dn.x + dn.w / 2, dn.x + dn.w};
            int[] srcYs = {dn.y, dn.y + dn.h / 2, dn.y + dn.h};
            
            for (FlowNode target : CanvasPanel.this.parent.nodes) {
                if (CanvasPanel.this.parent.selectedNodes.contains(target)) continue;
                
                int[] tgtXs = {target.x, target.x + target.w / 2, target.x + target.w};
                int[] tgtYs = {target.y, target.y + target.h / 2, target.y + target.h};
                
                // 检查垂直对齐
                for (int sx : srcXs) {
                    for (int tx : tgtXs) {
                        if (Math.abs(sx - tx) < threshold) {
                            g2.drawLine(tx, 0, tx, getHeight());
                        }
                    }
                }
                
                // 检查水平对齐
                for (int sy : srcYs) {
                    for (int ty : tgtYs) {
                        if (Math.abs(sy - ty) < threshold) {
                            g2.drawLine(0, ty, getWidth(), ty);
                        }
                    }
                }
            }
        }

        // 绘制悬停节点四周的连线标记
        if (hoveredNode != null) {
            drawHoveredNodeMarkers(g2, hoveredNode);
        }

        // 绘制选中节点大小缩放手柄
        if (CanvasPanel.this.parent.currentMode == FlowchartPanel.Mode.SELECT && CanvasPanel.this.parent.selectedNode != null && CanvasPanel.this.parent.selectedNodes.size() == 1) {
            drawResizeHandles(g2, CanvasPanel.this.parent.selectedNode);
        }

        // 绘制选中连线手柄 (端点与弯折点)
        if (CanvasPanel.this.parent.currentMode == FlowchartPanel.Mode.SELECT && CanvasPanel.this.parent.selectedEdge != null) {
            drawEdgeHandles(g2, CanvasPanel.this.parent.selectedEdge);
        }

        // 建立连线时高亮锚点
        if (CanvasPanel.this.parent.currentMode == FlowchartPanel.Mode.CONNECT || CanvasPanel.this.parent.isConnecting) {
            drawAllPorts(g2);
        }
    }

    void drawAll(Graphics2D g2) {
        drawAllEdges(g2);
        drawAllNodes(g2);
    }

    private void drawGrid(Graphics2D g2) {
        g2.setColor(UIManager.getColor("Separator.foreground"));
        if (g2.getColor() == null) {
            g2.setColor(new Color(230, 230, 230, 100));
        } else {
            g2.setColor(new Color(g2.getColor().getRed(), g2.getColor().getGreen(), g2.getColor().getBlue(), 30));
        }
        int step = 20;
        int w = getWidth();
        int h = getHeight();
        for (int i = 0; i < w; i += step) {
            g2.drawLine(i, 0, i, h);
        }
        for (int i = 0; i < h; i += step) {
            g2.drawLine(0, i, w, i);
        }
    }

    private void drawAllNodes(Graphics2D g2) {
        for (FlowNode node : CanvasPanel.this.parent.nodes) {
            boolean isSelected = CanvasPanel.this.parent.selectedNodes.contains(node);
            
            // 备份原数据坐标，防止拖拽磁吸死锁
            int originalX = node.x;
            int originalY = node.y;
            if (isSelected && CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.MOVE_NODE) {
                node.x += snapOffsetX;
                node.y += snapOffsetY;
            }
            
            try {
                Color borderTheme = isSelected ? UIManager.getColor("Component.focusColor") : node.borderColor;
                if (borderTheme == null) borderTheme = isSelected ? Color.BLUE : Color.GRAY;

                // 粗细样式应用
                float thick = isSelected ? node.borderThickness + 1.0f : node.borderThickness;
                Stroke stroke = node.isDashedBorder ? 
                        new BasicStroke(thick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5f}, 0.0f) :
                        new BasicStroke(thick);

                g2.setStroke(stroke);

            if (node.type.equals(FlowNode.TYPE_START_END)) {
                // 起止框 (椭圆)
                g2.setColor(node.bgColor);
                g2.fillOval(node.x, node.y, node.w, node.h);
                g2.setColor(borderTheme);
                g2.drawOval(node.x, node.y, node.w, node.h);

            } else if (node.type.equals(FlowNode.TYPE_DECISION)) {
                // 判定框 (菱形)
                int[] xPoints = {node.x + node.w / 2, node.x + node.w, node.x + node.w / 2, node.x};
                int[] yPoints = {node.y, node.y + node.h / 2, node.y + node.h, node.y + node.h / 2};
                g2.setColor(node.bgColor);
                g2.fillPolygon(xPoints, yPoints, 4);
                g2.setColor(borderTheme);
                g2.drawPolygon(xPoints, yPoints, 4);

            } else if (node.type.equals(FlowNode.TYPE_DATA)) {
                // 数据框 (平行四边形)
                int skew = 15;
                int[] xPoints = {node.x + skew, node.x + node.w, node.x + node.w - skew, node.x};
                int[] yPoints = {node.y, node.y, node.y + node.h, node.y + node.h};
                g2.setColor(node.bgColor);
                g2.fillPolygon(xPoints, yPoints, 4);
                g2.setColor(borderTheme);
                g2.drawPolygon(xPoints, yPoints, 4);

            } else if (node.type.equals(FlowNode.TYPE_DATABASE)) {
                // 数据库 (圆柱)
                int dy = 10;
                g2.setColor(node.bgColor);
                g2.fillRect(node.x, node.y + dy, node.w, node.h - dy * 2);
                g2.fillOval(node.x, node.y, node.w, dy * 2);
                g2.fillOval(node.x, node.y + node.h - dy * 2, node.w, dy * 2);

                g2.setColor(borderTheme);
                g2.drawOval(node.x, node.y, node.w, dy * 2);
                g2.drawArc(node.x, node.y + node.h - dy * 2, node.w, dy * 2, 180, 180);
                g2.drawLine(node.x, node.y + dy, node.x, node.y + node.h - dy);
                g2.drawLine(node.x + node.w, node.y + dy, node.x + node.w, node.y + node.h - dy);

            } else if (node.type.equals(FlowNode.TYPE_CLOUD)) {
                // 云朵 (云服务)
                g2.setColor(node.bgColor);
                int cx = node.x, cy = node.y, cw = node.w, ch = node.h;
                g2.fillOval(cx + 10, cy + 12, 35, 35);
                g2.fillOval(cx + 35, cy + 5, 45, 45);
                g2.fillOval(cx + 65, cy + 15, 30, 30);
                g2.fillRect(cx + 25, cy + 20, 50, 25);
                
                g2.setColor(borderTheme);
                g2.drawArc(cx + 10, cy + 12, 35, 35, 75, 180);
                g2.drawArc(cx + 35, cy + 5, 45, 45, 10, 160);
                g2.drawArc(cx + 65, cy + 15, 30, 30, -80, 170);
                g2.drawLine(cx + 25, cy + 45, cx + 75, cy + 45);

            } else if (node.type.equals(FlowNode.TYPE_PREDEFINED)) {
                // 预设子过程 (左右双竖线矩形)
                g2.setColor(node.bgColor);
                g2.fillRect(node.x, node.y, node.w, node.h);
                g2.setColor(borderTheme);
                g2.drawRect(node.x, node.y, node.w, node.h);
                g2.drawLine(node.x + 8, node.y, node.x + 8, node.y + node.h);
                g2.drawLine(node.x + node.w - 8, node.y, node.x + node.w - 8, node.y + node.h);

            } else if (node.type.equals(FlowNode.TYPE_DOCUMENT)) {
                // 文档框 (底部波浪)
                Path2D.Double path = new Path2D.Double();
                path.moveTo(node.x, node.y);
                path.lineTo(node.x + node.w, node.y);
                path.lineTo(node.x + node.w, node.y + node.h - 10);
                path.quadTo(node.x + node.w * 0.75, node.y + node.h - 18, node.x + node.w * 0.5, node.y + node.h - 10);
                path.quadTo(node.x + node.w * 0.25, node.y + node.h, node.x, node.y + node.h - 10);
                path.closePath();
                g2.setColor(node.bgColor);
                g2.fill(path);
                g2.setColor(borderTheme);
                g2.draw(path);

            } else if (node.type.equals(FlowNode.TYPE_PREPARATION)) {
                // 准备 (六边形)
                int[] xPoints = {node.x + 15, node.x + node.w - 15, node.x + node.w, node.x + node.w - 15, node.x + 15, node.x};
                int[] yPoints = {node.y, node.y, node.y + node.h / 2, node.y + node.h, node.y + node.h, node.y + node.h / 2};
                g2.setColor(node.bgColor);
                g2.fillPolygon(xPoints, yPoints, 6);
                g2.setColor(borderTheme);
                g2.drawPolygon(xPoints, yPoints, 6);

            } else if (node.type.equals(FlowNode.TYPE_MANUAL_INPUT)) {
                // 手工输入 (斜顶四边形)
                int[] xPoints = {node.x, node.x + node.w, node.x + node.w, node.x};
                int[] yPoints = {node.y + 10, node.y, node.y + node.h, node.y + node.h};
                g2.setColor(node.bgColor);
                g2.fillPolygon(xPoints, yPoints, 4);
                g2.setColor(borderTheme);
                g2.drawPolygon(xPoints, yPoints, 4);

            } else if (node.type.equals(FlowNode.TYPE_ANNOTATION)) {
                // 注释 (半包围线，背景透明)
                g2.setColor(borderTheme);
                g2.drawLine(node.x + 12, node.y, node.x, node.y);
                g2.drawLine(node.x, node.y, node.x, node.y + node.h);
                g2.drawLine(node.x, node.y + node.h, node.x + 12, node.y + node.h);

            } else if (node.type.equals(FlowNode.TYPE_TERMINATOR)) {
                // 终结符/起止 (圆角矩形)
                g2.setColor(node.bgColor);
                g2.fillRoundRect(node.x, node.y, node.w, node.h, 20, 20);
                g2.setColor(borderTheme);
                g2.drawRoundRect(node.x, node.y, node.w, node.h, 20, 20);

            } else if (node.type.equals(FlowNode.TYPE_CARD)) {
                // 卡片登记 (右上剪角)
                int cut = 12;
                int[] xPoints = {node.x, node.x + node.w - cut, node.x + node.w, node.x + node.w, node.x};
                int[] yPoints = {node.y, node.y, node.y + cut, node.y + node.h, node.y + node.h};
                g2.setColor(node.bgColor);
                g2.fillPolygon(xPoints, yPoints, 5);
                g2.setColor(borderTheme);
                g2.drawPolygon(xPoints, yPoints, 5);

            } else if (node.type.equals(FlowNode.TYPE_LIFELINE)) {
                // 生命线 (顶端对象矩形 + 垂直虚线)
                int boxH = 35;
                g2.setColor(node.bgColor);
                g2.fillRect(node.x, node.y, node.w, boxH);
                g2.setColor(borderTheme);
                g2.drawRect(node.x, node.y, node.w, boxH);
                
                g2.setStroke(new BasicStroke(thick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{6f, 6f}, 0.0f));
                g2.drawLine(node.x + node.w / 2, node.y + boxH, node.x + node.w / 2, node.y + node.h);

            } else if (node.type.equals(FlowNode.TYPE_ACTOR)) {
                // 角色生命线 (小人 + 垂直虚线)
                int actorH = 50;
                int cx = node.x + node.w / 2;
                
                g2.setColor(borderTheme);
                g2.drawOval(cx - 8, node.y + 4, 16, 16);
                g2.drawLine(cx, node.y + 20, cx, node.y + 38);
                g2.drawLine(cx - 15, node.y + 28, cx + 15, node.y + 28);
                g2.drawLine(cx, node.y + 38, cx - 10, node.y + 50);
                g2.drawLine(cx, node.y + 38, cx + 10, node.y + 50);

                g2.setStroke(new BasicStroke(thick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{6f, 6f}, 0.0f));
                g2.drawLine(cx, node.y + actorH, cx, node.y + node.h);

            } else if (node.type.equals(FlowNode.TYPE_ACTIVATION)) {
                // 激活条 (细长垂直矩形)
                g2.setColor(node.bgColor);
                g2.fillRect(node.x, node.y, node.w, node.h);
                g2.setColor(borderTheme);
                g2.drawRect(node.x, node.y, node.w, node.h);

            } else if (node.type.equals(FlowNode.TYPE_DELAY)) {
                // 延时符 (左侧直角，右侧半圆)
                Path2D.Double path = new Path2D.Double();
                path.moveTo(node.x, node.y);
                path.lineTo(node.x + node.w - node.h / 2, node.y);
                path.curveTo(node.x + node.w, node.y, node.x + node.w, node.y + node.h, node.x + node.w - node.h / 2, node.y + node.h);
                path.lineTo(node.x, node.y + node.h);
                path.closePath();
                g2.setColor(node.bgColor);
                g2.fill(path);
                g2.setColor(borderTheme);
                g2.draw(path);

            } else if (node.type.equals(FlowNode.TYPE_DISPLAY)) {
                // 显示器 (左侧尖，右侧半圆，主体是矩形)
                Path2D.Double path = new Path2D.Double();
                int tip = 15;
                path.moveTo(node.x + tip, node.y);
                path.lineTo(node.x + node.w - tip, node.y);
                path.curveTo(node.x + node.w, node.y + node.h / 4, node.x + node.w, node.y + 3 * node.h / 4, node.x + node.w - tip, node.y + node.h);
                path.lineTo(node.x + tip, node.y + node.h);
                path.lineTo(node.x, node.y + node.h / 2);
                path.closePath();
                g2.setColor(node.bgColor);
                g2.fill(path);
                g2.setColor(borderTheme);
                g2.draw(path);

            } else if (node.type.equals(FlowNode.TYPE_INTERNAL_STORAGE)) {
                // 内部存储 (有两条内边缘的矩形)
                g2.setColor(node.bgColor);
                g2.fillRect(node.x, node.y, node.w, node.h);
                g2.setColor(borderTheme);
                g2.drawRect(node.x, node.y, node.w, node.h);
                g2.drawLine(node.x + 8, node.y, node.x + 8, node.y + node.h);
                g2.drawLine(node.x, node.y + 8, node.x + node.w, node.y + 8);

            } else if (node.type.equals(FlowNode.TYPE_OFF_PAGE_CONNECTOR)) {
                // 离页连接符 (五边形，尖角向下)
                int[] xPoints = {node.x, node.x + node.w, node.x + node.w, node.x + node.w / 2, node.x};
                int[] yPoints = {node.y, node.y, node.y + 2 * node.h / 3, node.y + node.h, node.y + 2 * node.h / 3};
                g2.setColor(node.bgColor);
                g2.fillPolygon(xPoints, yPoints, 5);
                g2.setColor(borderTheme);
                g2.drawPolygon(xPoints, yPoints, 5);

            } else {
                // 默认步骤框 (矩形)
                g2.setColor(node.bgColor);
                g2.fillRect(node.x, node.y, node.w, node.h);
                g2.setColor(borderTheme);
                g2.drawRect(node.x, node.y, node.w, node.h);
            }
            drawNodeName(g2, node);
            } finally {
                node.x = originalX;
                node.y = originalY;
            }
        }
    }

    private void drawNodeName(Graphics2D g2, FlowNode node) {
        int style = node.isBold ? Font.BOLD : Font.PLAIN;
        g2.setFont(new Font("Microsoft YaHei", style, node.fontSize));
        g2.setColor(node.textColor != null ? node.textColor : UIManager.getColor("Label.foreground"));
        if (g2.getColor() == null) g2.setColor(Color.BLACK);
        
        FontMetrics fm = g2.getFontMetrics();
        String name = node.name == null ? "" : node.name;
        int textWidth = fm.stringWidth(name);

        int targetH = node.h;
        if (node.type.equals(FlowNode.TYPE_LIFELINE)) {
            targetH = 35; // Draw text centered in the top box
        } else if (node.type.equals(FlowNode.TYPE_ACTOR)) {
            // Draw text below the stick figure
            g2.drawString(name, node.x + (node.w - textWidth) / 2, node.y + 65);
            return;
        }

        // 对长文本自动换行
        if (textWidth > node.w - 12 && name.length() > 4) {
            int mid = name.length() / 2;
            String part1 = name.substring(0, mid);
            String part2 = name.substring(mid);
            g2.drawString(part1, node.x + (node.w - fm.stringWidth(part1)) / 2, node.y + targetH / 2 - 2);
            g2.drawString(part2, node.x + (node.w - fm.stringWidth(part2)) / 2, node.y + targetH / 2 + node.fontSize);
        } else {
            g2.drawString(name, node.x + (node.w - textWidth) / 2, node.y + targetH / 2 + fm.getAscent() / 2 - 2);
        }
    }

    private void drawAllEdges(Graphics2D g2) {
        for (FlowEdge edge : CanvasPanel.this.parent.edges) {
            boolean isSelected = (CanvasPanel.this.parent.selectedEdge == edge);
            g2.setColor(isSelected ? UIManager.getColor("Component.focusColor") : edge.lineColor);
            if (g2.getColor() == null) g2.setColor(isSelected ? Color.BLUE : Color.DARK_GRAY);

            float thick = isSelected ? 2.5f : 1.5f;
            g2.setStroke(edge.isDashed ? 
                    new BasicStroke(thick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5f}, 0.0f) :
                    new BasicStroke(thick));

            Point p1 = edge.source.getConnectionPoint(edge.sourceRelX, edge.sourceRelY);
            Point p2 = edge.target.getConnectionPoint(edge.targetRelX, edge.targetRelY);

            if (edge == CanvasPanel.this.parent.draggedEdge) {
                if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_EDGE_START && CanvasPanel.this.parent.tempDragPoint != null) {
                    p1 = CanvasPanel.this.parent.tempDragPoint;
                } else if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_EDGE_END && CanvasPanel.this.parent.tempDragPoint != null) {
                    p2 = CanvasPanel.this.parent.tempDragPoint;
                }
            }

            // 若包含自定义拐点，以直接折线相连
            if (edge.waypoints != null && !edge.waypoints.isEmpty()) {
                List<Point> pts = new ArrayList<>();
                pts.add(p1);
                pts.addAll(edge.waypoints);
                pts.add(p2);
                for (int i = 0; i < pts.size() - 1; i++) {
                    g2.drawLine(pts.get(i).x, pts.get(i).y, pts.get(i + 1).x, pts.get(i + 1).y);
                }
                drawArrow(g2, pts.get(pts.size() - 2), p2);
            } else {
                // 根据连线模式绘制：Manhattan折线 / Straight直线 / Bezier贝塞尔曲线
                if ("bezier".equals(edge.routingType)) {
                    drawBezierLine(g2, p1, p2, edge.sourcePort, edge.targetPort);
                } else if ("straight".equals(edge.routingType)) {
                    drawStraightLine(g2, p1, p2);
                } else {
                    drawManhattanLine(g2, p1, p2, edge.sourcePort, edge.targetPort);
                }
            }

            if (edge.label != null && !edge.label.trim().isEmpty()) {
                drawEdgeLabel(g2, edge, edge.label);
            }
        }
    }

    private void drawStraightLine(Graphics2D g2, Point p1, Point p2) {
        g2.drawLine(p1.x, p1.y, p2.x, p2.y);
        drawArrow(g2, p1, p2);
    }

    private void drawManhattanLine(Graphics2D g2, Point p1, Point p2, int port1, int port2) {
        List<Point> pts = new ArrayList<>();
        pts.add(p1);

        int dist = 20;
        Point startDir = getDirectionOffset(port1, dist);
        Point s1 = new Point(p1.x + startDir.x, p1.y + startDir.y);
        pts.add(s1);

        Point endDir = getDirectionOffset(port2, dist);
        Point s2 = new Point(p2.x + endDir.x, p2.y + endDir.y);

        if (port1 == 1 || port1 == 3) {
            pts.add(new Point(s1.x, s2.y));
        } else {
            pts.add(new Point(s2.x, s1.y));
        }

        pts.add(s2);
        pts.add(p2);

        for (int i = 0; i < pts.size() - 1; i++) {
            g2.drawLine(pts.get(i).x, pts.get(i).y, pts.get(i + 1).x, pts.get(i + 1).y);
        }

        drawArrow(g2, pts.get(pts.size() - 2), p2);
    }

    private void drawBezierLine(Graphics2D g2, Point p1, Point p2, int port1, int port2) {
        int ctrlX1 = p1.x;
        int ctrlY1 = p1.y;
        int ctrlX2 = p2.x;
        int ctrlY2 = p2.y;
        
        int d = Math.max(30, Math.abs(p2.x - p1.x) / 2);
        if (port1 == 1 || port1 == 3) {
            ctrlX1 = p1.x + (port1 == 1 ? d : -d);
        } else {
            ctrlY1 = p1.y + (port1 == 2 ? d : -d);
        }
        if (port2 == 1 || port2 == 3) {
            ctrlX2 = p2.x + (port2 == 1 ? d : -d);
        } else {
            ctrlY2 = p2.y + (port2 == 2 ? d : -d);
        }

        Path2D.Double path = new Path2D.Double();
        path.moveTo(p1.x, p1.y);
        path.curveTo(ctrlX1, ctrlY1, ctrlX2, ctrlY2, p2.x, p2.y);
        g2.draw(path);

        drawArrow(g2, new Point(ctrlX2, ctrlY2), p2);
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

    private void drawArrow(Graphics2D g2, Point from, Point to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double angle = Math.atan2(dy, dx);
        int len = 8;

        Polygon arrow = new Polygon();
        arrow.addPoint(to.x, to.y);
        arrow.addPoint((int) (to.x - len * Math.cos(angle - Math.PI / 6)), (int) (to.y - len * Math.sin(angle - Math.PI / 6)));
        arrow.addPoint((int) (to.x - len * Math.cos(angle + Math.PI / 6)), (int) (to.y - len * Math.sin(angle + Math.PI / 6)));
        
        g2.fillPolygon(arrow);
    }

    private Rectangle getEdgeLabelBounds(FlowEdge edge) {
        if (edge.label == null || edge.label.trim().isEmpty()) {
            return null;
        }
        Font font = new Font("Microsoft YaHei", Font.PLAIN, 11);
        FontMetrics fm = getFontMetrics(font);
        Point center = edge.getPointAtFraction(edge.labelPosition);
        int cx = center.x;
        int cy = center.y - 4;
        int w = fm.stringWidth(edge.label) + 4;
        int h = fm.getHeight();
        int x = cx - w / 2;
        int y = cy - fm.getAscent() + 2;
        return new Rectangle(x, y, w, h);
    }

    private void drawEdgeLabel(Graphics2D g2, FlowEdge edge, String label) {
        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        FontMetrics fm = g2.getFontMetrics();
        Point center = edge.getPointAtFraction(edge.labelPosition);
        int cx = center.x;
        int cy = center.y - 4;

        g2.setColor(UIManager.getColor("Panel.background"));
        if (g2.getColor() == null) g2.setColor(Color.WHITE);
        g2.fillRect(cx - fm.stringWidth(label) / 2 - 2, cy - fm.getAscent() + 2, fm.stringWidth(label) + 4, fm.getHeight());

        g2.setColor(UIManager.getColor("Label.foreground"));
        if (g2.getColor() == null) g2.setColor(Color.BLACK);
        g2.drawString(label, cx - fm.stringWidth(label) / 2, cy + fm.getAscent() / 2);
    }

    private void drawConnectionHandle(Graphics2D g2, FlowNode node) {
        int hx = node.x + node.w + 14;
        int hy = node.y + node.h / 2 - 9;
        g2.setColor(UIManager.getColor("Component.accentColor"));
        if (g2.getColor() == null) g2.setColor(Color.BLUE);
        g2.drawOval(hx, hy, 18, 18);
        g2.setColor(new Color(64, 158, 255, 30));
        g2.fillOval(hx, hy, 18, 18);

        g2.setColor(UIManager.getColor("Label.foreground"));
        g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 11));
        g2.drawString("🔗", hx + 3, hy + 13);
    }

    private void drawAllPorts(Graphics2D g2) {
        g2.setColor(Color.GRAY);
        g2.setStroke(new BasicStroke(1.0f));
        for (FlowNode node : CanvasPanel.this.parent.nodes) {
            if (node == CanvasPanel.this.parent.connectSourceNode) continue;
            
            if (node.type.equals(FlowNode.TYPE_LIFELINE) || node.type.equals(FlowNode.TYPE_ACTOR)) {
                int headerH = node.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
                int cx = node.x + node.w / 2;
                g2.setColor(new Color(64, 158, 255, 180));
                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4f, 4f}, 0.0f));
                g2.drawLine(cx, node.y + headerH, cx, node.y + node.h);
                g2.setColor(Color.GRAY);
                g2.setStroke(new BasicStroke(1.0f));
            } else {
                for (int i = 0; i < 4; i++) {
                    Point p = node.getPortPoint(i);
                    g2.drawOval(p.x - 3, p.y - 3, 6, 6);
                    g2.setColor(new Color(250, 250, 250));
                    g2.fillOval(p.x - 2, p.y - 2, 4, 4);
                    g2.setColor(Color.GRAY);
                }
            }
        }
    }

    private FlowNode getNodeAtPoint(Point p) {
        for (int i = CanvasPanel.this.parent.nodes.size() - 1; i >= 0; i--) {
            FlowNode node = CanvasPanel.this.parent.nodes.get(i);
            if (new Rectangle(node.x, node.y, node.w, node.h).contains(p)) {
                return node;
            }
        }
        return null;
    }

    private FlowEdge getEdgeAtPoint(Point p) {
        for (FlowEdge edge : CanvasPanel.this.parent.edges) {
            List<Point> pts = edge.getPoints();
            for (int i = 0; i < pts.size() - 1; i++) {
                Point p1 = pts.get(i);
                Point p2 = pts.get(i + 1);
                if (new Line2D.Double(p1, p2).ptSegDist(p) < 8.0) {
                    return edge;
                }
            }
        }
        return null;
    }

    private boolean isPointInConnectionHandle(FlowNode node, Point p) {
        int hx = node.x + node.w + 14;
        int hy = node.y + node.h / 2 - 9;
        return new Rectangle(hx, hy, 18, 18).contains(p);
    }

    private int getClosestPortIndex(FlowNode node, Point p) {
        double minDist = Double.MAX_VALUE;
        int minIdx = 0;
        for (int i = 0; i < 4; i++) {
            Point port = node.getPortPoint(i);
            double dist = p.distance(port);
            if (dist < minDist) {
                minDist = dist;
                minIdx = i;
            }
        }
        return minIdx;
    }

    private void initiateConnection(Point p) {
        FlowNode node = getNodeAtPoint(p);
        if (node != null) {
            CanvasPanel.this.parent.isConnecting = true;
            CanvasPanel.this.parent.connectSourceNode = node;
            Point[] outPt = new Point[1];
            java.awt.geom.Point2D.Double rel = snapToBestPosition(node, p, outPt);
            CanvasPanel.this.parent.connectSourceRelX = rel.x;
            CanvasPanel.this.parent.connectSourceRelY = rel.y;
            CanvasPanel.this.parent.connectSourcePortIndex = getClosestPortIndex(node, outPt[0]);
        }
    }

    private void completeConnection(Point p) {
        FlowNode target = getNodeAtPoint(p);
        FlowNode source = CanvasPanel.this.parent.connectSourceNode;
        if (target != null && target != source) {
            Point[] outPt = new Point[1];
            java.awt.geom.Point2D.Double tgtRel = snapToBestPosition(target, p, outPt);
            
            // 如果连线的源端和目标端都是生命线/角色线，强制它们在Y轴绝对水平对齐
            if ((source.type.equals(FlowNode.TYPE_LIFELINE) || source.type.equals(FlowNode.TYPE_ACTOR)) &&
                (target.type.equals(FlowNode.TYPE_LIFELINE) || target.type.equals(FlowNode.TYPE_ACTOR))) {
                Point srcPt = source.getConnectionPoint(CanvasPanel.this.parent.connectSourceRelX, CanvasPanel.this.parent.connectSourceRelY);
                double alignedRelY = (double)(srcPt.y - target.y) / target.h;
                tgtRel.y = alignedRelY;
            }
            
            boolean exist = false;
            for (FlowEdge e : CanvasPanel.this.parent.edges) {
                if (e.source == source && e.target == target 
                    && Math.abs(e.sourceRelX - CanvasPanel.this.parent.connectSourceRelX) < 0.01
                    && Math.abs(e.sourceRelY - CanvasPanel.this.parent.connectSourceRelY) < 0.01
                    && Math.abs(e.targetRelX - tgtRel.x) < 0.01
                    && Math.abs(e.targetRelY - tgtRel.y) < 0.01) {
                    exist = true;
                    break;
                }
            }
            if (!exist) {
                String id = "Flow_" + UUID.randomUUID().toString().substring(0, 8);
                FlowEdge edge = new FlowEdge(id, "", source, target,
                    CanvasPanel.this.parent.connectSourceRelX, CanvasPanel.this.parent.connectSourceRelY,
                    tgtRel.x, tgtRel.y);
                
                CanvasPanel.this.parent.saveState();
                // 将 FlowEdge 添加进 edges
                CanvasPanel.this.parent.edges.add(edge);

                // 【自动对齐最大长度】：如果新增的生命线被连接，立即将参与该连线的生命线高度统一为当前最大的生命线高度
                int maxH = 0;
                for (FlowNode n : CanvasPanel.this.parent.nodes) {
                    if (n.type.equals(FlowNode.TYPE_LIFELINE) || n.type.equals(FlowNode.TYPE_ACTOR)) {
                        if (n.h > maxH) maxH = n.h;
                    }
                }
                if (maxH > 0) {
                    FlowNode[] connectedNodes = {source, target};
                    for (FlowNode n : connectedNodes) {
                        if ((n.type.equals(FlowNode.TYPE_LIFELINE) || n.type.equals(FlowNode.TYPE_ACTOR)) && n.h < maxH) {
                            double oldH = n.h;
                            n.h = maxH;
                            double newH = n.h;
                            // 重算与该节点关联的连线的相对 Y 坐标比率
                            for (FlowEdge e : CanvasPanel.this.parent.edges) {
                                if (e.source == n) {
                                    double oldAbsY = n.y + e.sourceRelY * oldH;
                                    e.sourceRelY = (oldAbsY - n.y) / newH;
                                }
                                if (e.target == n) {
                                    double oldAbsY = n.y + e.targetRelY * oldH;
                                    e.targetRelY = (oldAbsY - n.y) / newH;
                                }
                            }
                        }
                    }
                }
                
                // 【连线结束后检测最后一个点进行自动延长】：
                // 1. 如果源是生命线，且连在了源生命线的最后一个点
                if (source.type.equals(FlowNode.TYPE_LIFELINE) || source.type.equals(FlowNode.TYPE_ACTOR)) {
                    int headerH = source.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
                    double lastRelY = (double)(source.h - (source.h - headerH) % 25) / source.h;
                    if (Math.abs(CanvasPanel.this.parent.connectSourceRelY - lastRelY) < 0.02) {
                        triggerGlobalExtension();
                    }
                }
                // 2. 如果目标是生命线，且连在了目标生命线的最后一个点
                if (target.type.equals(FlowNode.TYPE_LIFELINE) || target.type.equals(FlowNode.TYPE_ACTOR)) {
                    int headerH = target.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
                    double lastRelY = (double)(target.h - (target.h - headerH) % 25) / target.h;
                    if (Math.abs(tgtRel.y - lastRelY) < 0.02) {
                        triggerGlobalExtension();
                    }
                }
                
                CanvasPanel.this.parent.selectedEdge = edge;
                CanvasPanel.this.parent.selectedNode = null;
                CanvasPanel.this.parent.updatePropertyPanel();
            }
        }
    }

    private void triggerGlobalExtension() {
        if (isExtending) return;
        isExtending = true;
        try {
            for (FlowNode n : CanvasPanel.this.parent.nodes) {
                if (n.type.equals(FlowNode.TYPE_LIFELINE) || n.type.equals(FlowNode.TYPE_ACTOR)) {
                    double oldH = n.h;
                    n.h += 75;
                    double newH = n.h;
                    for (FlowEdge e : CanvasPanel.this.parent.edges) {
                        if (e.source == n) {
                            double oldAbsY = n.y + e.sourceRelY * oldH;
                            e.sourceRelY = (oldAbsY - n.y) / newH;
                        }
                        if (e.target == n) {
                            double oldAbsY = n.y + e.targetRelY * oldH;
                            e.targetRelY = (oldAbsY - n.y) / newH;
                        }
                    }
                }
            }
            CanvasPanel.this.parent.adjustCanvasSize();
            repaint();
        } finally {
            isExtending = false;
        }
    }

    private void deleteNode(FlowNode node) {
        CanvasPanel.this.parent.saveState();
        CanvasPanel.this.parent.nodes.remove(node);
        CanvasPanel.this.parent.edges.removeIf(e -> e.source == node || e.target == node);
        CanvasPanel.this.parent.clearSelection();
        repaint();
    }

    private void deleteEdge(FlowEdge edge) {
        CanvasPanel.this.parent.saveState();
        CanvasPanel.this.parent.edges.remove(edge);
        CanvasPanel.this.parent.clearSelection();
        repaint();
    }

    private void editNodeName(FlowNode node) {
        String newName = UIUtils.input(CanvasPanel.this.parent.getView(), "请输入新名称文本:", node.name);
        if (newName != null && !newName.trim().equals(node.name)) {
            CanvasPanel.this.parent.saveState();
            node.name = newName.trim();
            CanvasPanel.this.parent.updatePropertyPanel();
            repaint();
        }
    }

    private void editEdgeLabel(FlowEdge edge) {
        String newLabel = UIUtils.input(CanvasPanel.this.parent.getView(), "请输入连线条件文本:", edge.label);
        if (newLabel != null && !newLabel.trim().equals(edge.label)) {
            CanvasPanel.this.parent.saveState();
            edge.label = newLabel.trim();
            CanvasPanel.this.parent.updatePropertyPanel();
            repaint();
        }
    }

    private void deleteSelected() {
        if (!CanvasPanel.this.parent.selectedNodes.isEmpty() || CanvasPanel.this.parent.selectedEdge != null) {
            CanvasPanel.this.parent.saveState();
        }
        if (!CanvasPanel.this.parent.selectedNodes.isEmpty()) {
            for (FlowNode n : new ArrayList<>(CanvasPanel.this.parent.selectedNodes)) {
                CanvasPanel.this.parent.nodes.remove(n);
                CanvasPanel.this.parent.edges.removeIf(e -> e.source == n || e.target == n);
            }
            CanvasPanel.this.parent.clearSelection();
            repaint();
        } else if (CanvasPanel.this.parent.selectedEdge != null) {
            CanvasPanel.this.parent.edges.remove(CanvasPanel.this.parent.selectedEdge);
            CanvasPanel.this.parent.clearSelection();
            repaint();
        }
    }

    // --- 拦截并对准缩放的鼠标事件 ---

    private Point getZoomedPoint(Point p) {
        double zoom = CanvasPanel.this.parent.zoomFactor;
        return new Point((int) (p.x / zoom), (int) (p.y / zoom));
    }

    @Override
    protected void processMouseEvent(MouseEvent e) {
        double zoom = parent.zoomFactor;
        if (zoom != 1.0) {
            Point p = e.getPoint();
            int lx = (int) (p.x / zoom);
            int ly = (int) (p.y / zoom);
            e.translatePoint(lx - p.x, ly - p.y);
        }
        super.processMouseEvent(e);
    }

    @Override
    protected void processMouseMotionEvent(MouseEvent e) {
        double zoom = parent.zoomFactor;
        if (zoom != 1.0) {
            Point p = e.getPoint();
            int lx = (int) (p.x / zoom);
            int ly = (int) (p.y / zoom);
            e.translatePoint(lx - p.x, ly - p.y);
        }
        super.processMouseMotionEvent(e);
    }

    private int getWaypointHandleAt(FlowEdge edge, Point p) {
        for (int i = 0; i < edge.waypoints.size(); i++) {
            Point wp = edge.waypoints.get(i);
            if (p.distance(wp) < 6.0) {
                return i;
            }
        }
        return -1;
    }

    private int getMidpointHandleAt(FlowEdge edge, Point p) {
        List<Point> pts = edge.getPoints();
        for (int i = 0; i < pts.size() - 1; i++) {
            Point p1 = pts.get(i);
            Point p2 = pts.get(i + 1);
            Point mid = new Point((p1.x + p2.x) / 2, (p1.y + p2.y) / 2);
            if (p.distance(mid) < 6.0) {
                return i;
            }
        }
        return -1;
    }

    private FlowchartPanel.DragState getResizeHandleState(FlowNode node, Point p) {
        int handleSize = 6;
        int half = handleSize / 2;
        int x = node.x;
        int y = node.y;
        int w = node.w;
        int h = node.h;

        if (new Rectangle(x - half, y - half, handleSize, handleSize).contains(p)) return FlowchartPanel.DragState.RESIZE_TL;
        if (new Rectangle(x + w/2 - half, y - half, handleSize, handleSize).contains(p)) return FlowchartPanel.DragState.RESIZE_TC;
        if (new Rectangle(x + w - half, y - half, handleSize, handleSize).contains(p)) return FlowchartPanel.DragState.RESIZE_TR;
        if (new Rectangle(x - half, y + h/2 - half, handleSize, handleSize).contains(p)) return FlowchartPanel.DragState.RESIZE_ML;
        if (new Rectangle(x + w - half, y + h/2 - half, handleSize, handleSize).contains(p)) return FlowchartPanel.DragState.RESIZE_MR;
        if (new Rectangle(x - half, y + h - half, handleSize, handleSize).contains(p)) return FlowchartPanel.DragState.RESIZE_BL;
        if (new Rectangle(x + w/2 - half, y + h - half, handleSize, handleSize).contains(p)) return FlowchartPanel.DragState.RESIZE_BC;
        if (new Rectangle(x + w - half, y + h - half, handleSize, handleSize).contains(p)) return FlowchartPanel.DragState.RESIZE_BR;

        return FlowchartPanel.DragState.NONE;
    }

    private boolean isResizeState(FlowchartPanel.DragState s) {
        return s == FlowchartPanel.DragState.RESIZE_TL || s == FlowchartPanel.DragState.RESIZE_TC || s == FlowchartPanel.DragState.RESIZE_TR ||
               s == FlowchartPanel.DragState.RESIZE_ML || s == FlowchartPanel.DragState.RESIZE_MR ||
               s == FlowchartPanel.DragState.RESIZE_BL || s == FlowchartPanel.DragState.RESIZE_BC || s == FlowchartPanel.DragState.RESIZE_BR;
    }

    private void performResize(FlowNode node, FlowchartPanel.DragState state, int dx, int dy) {
        int minW = 30;
        int minH = 20;
        switch (state) {
            case RESIZE_TL:
                if (node.w - dx >= minW) { node.x += dx; node.w -= dx; }
                if (node.h - dy >= minH) { node.y += dy; node.h -= dy; }
                break;
            case RESIZE_TC:
                if (node.h - dy >= minH) { node.y += dy; node.h -= dy; }
                break;
            case RESIZE_TR:
                if (node.w + dx >= minW) { node.w += dx; }
                if (node.h - dy >= minH) { node.y += dy; node.h -= dy; }
                break;
            case RESIZE_ML:
                if (node.w - dx >= minW) { node.x += dx; node.w -= dx; }
                break;
            case RESIZE_MR:
                if (node.w + dx >= minW) { node.w += dx; }
                break;
            case RESIZE_BL:
                if (node.w - dx >= minW) { node.x += dx; node.w -= dx; }
                if (node.h + dy >= minH) { node.h += dy; }
                break;
            case RESIZE_BC:
                if (node.h + dy >= minH) { node.h += dy; }
                break;
            case RESIZE_BR:
                if (node.w + dx >= minW) { node.w += dx; }
                if (node.h + dy >= minH) { node.h += dy; }
                break;
        }
    }

    private void drawResizeHandles(Graphics2D g2, FlowNode node) {
        int handleSize = 6;
        int half = handleSize / 2;
        int x = node.x;
        int y = node.y;
        int w = node.w;
        int h = node.h;

        int[] xs = {x, x + w/2, x + w};
        int[] ys = {y, y + h/2, y + h};

        g2.setColor(UIManager.getColor("Component.focusColor"));
        if (g2.getColor() == null) g2.setColor(Color.BLUE);

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (r == 1 && c == 1) continue; // center has no handle
                g2.fillRect(xs[c] - half, ys[r] - half, handleSize, handleSize);
                g2.setColor(Color.WHITE);
                g2.drawRect(xs[c] - half, ys[r] - half, handleSize, handleSize);
                g2.setColor(UIManager.getColor("Component.focusColor"));
                if (g2.getColor() == null) g2.setColor(Color.BLUE);
            }
        }
    }

    private void drawEdgeHandles(Graphics2D g2, FlowEdge edge) {
        Point p1 = edge.source.getConnectionPoint(edge.sourceRelX, edge.sourceRelY);
        Point p2 = edge.target.getConnectionPoint(edge.targetRelX, edge.targetRelY);

        if (edge == CanvasPanel.this.parent.draggedEdge) {
            if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_EDGE_START && CanvasPanel.this.parent.tempDragPoint != null) {
                p1 = CanvasPanel.this.parent.tempDragPoint;
            } else if (CanvasPanel.this.parent.currentDragState == FlowchartPanel.DragState.DRAG_EDGE_END && CanvasPanel.this.parent.tempDragPoint != null) {
                p2 = CanvasPanel.this.parent.tempDragPoint;
            }
        }

        // Draw start handle (green circle)
        g2.setColor(Color.GREEN);
        g2.fillOval(p1.x - 5, p1.y - 5, 10, 10);
        g2.setColor(Color.DARK_GRAY);
        g2.drawOval(p1.x - 5, p1.y - 5, 10, 10);

        // Draw end handle (red circle)
        g2.setColor(Color.RED);
        g2.fillOval(p2.x - 5, p2.y - 5, 10, 10);
        g2.setColor(Color.DARK_GRAY);
        g2.drawOval(p2.x - 5, p2.y - 5, 10, 10);

        // Draw waypoint handles (blue squares)
        for (Point wp : edge.waypoints) {
            g2.setColor(Color.BLUE);
            g2.fillRect(wp.x - 4, wp.y - 4, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawRect(wp.x - 4, wp.y - 4, 8, 8);
        }

        // Draw midpoint handles (semi-transparent purple/yellow circles)
        List<Point> pts = edge.getPoints();
        for (int i = 0; i < pts.size() - 1; i++) {
            Point pt1 = pts.get(i);
            Point pt2 = pts.get(i + 1);
            Point mid = new Point((pt1.x + pt2.x) / 2, (pt1.y + pt2.y) / 2);
            g2.setColor(new Color(153, 50, 204, 180)); // Purple-ish
            g2.fillOval(mid.x - 3, mid.y - 3, 6, 6);
            g2.setColor(Color.WHITE);
            g2.drawOval(mid.x - 3, mid.y - 3, 6, 6);
        }
    }

    private int getConnectionMarkerAt(FlowNode node, Point p) {
        if (node == null) return -1;
        if (node.type.equals(FlowNode.TYPE_LIFELINE) || node.type.equals(FlowNode.TYPE_ACTOR)) {
            int headerH = node.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
            int cx = node.x + node.w / 2;
            int startY = node.y + headerH;
            int endY = node.y + node.h;
            int gap = 25;
            
            int idx = 0;
            for (int py = startY; py <= endY; py += gap) {
                if (p.distance(new Point(cx, py)) < 10.0) {
                    return idx;
                }
                idx++;
            }
            return -1;
        }
        
        if (node.type.equals(FlowNode.TYPE_ACTIVATION)) {
            Point[] pts = {
                new Point(node.x + node.w / 2, node.y),
                new Point(node.x + node.w / 2, node.y + node.h / 2),
                new Point(node.x + node.w / 2, node.y + node.h)
            };
            for (int i = 0; i < pts.length; i++) {
                if (p.distance(pts[i]) < 8.0) {
                    return i;
                }
            }
            return -1;
        }

        // 所有模式下都允许通过8个控制点检测并拖出线/连线
        double[][] relCoords = {
            {0.5, 0.0}, {1.0, 0.5}, {0.5, 1.0}, {0.0, 0.5},
            {0.0, 0.0}, {1.0, 0.0}, {0.0, 1.0}, {1.0, 1.0}
        };
        for (int i = 0; i < relCoords.length; i++) {
            Point pt = node.getConnectionPoint(relCoords[i][0], relCoords[i][1]);
            if (p.distance(pt) < 8.0) {
                return i;
            }
        }
        return -1;
    }

    private void drawHoveredNodeMarkers(Graphics2D g2, FlowNode node) {
        if (node == null) return;
        g2.setStroke(new BasicStroke(1.0f));

        // 1. 如果当前正在创建连线 (isConnecting) 或者正处于连线模式下悬停
        if (CanvasPanel.this.parent.isConnecting || CanvasPanel.this.parent.currentMode == FlowchartPanel.Mode.CONNECT) {
            if (CanvasPanel.this.parent.isConnecting && node == CanvasPanel.this.parent.connectSourceNode) {
                return;
            }
            if (CanvasPanel.this.parent.isConnecting && CanvasPanel.this.parent.tempTargetNode != null && node != CanvasPanel.this.parent.tempTargetNode) {
                return;
            }
            
            if (node.type.equals(FlowNode.TYPE_LIFELINE) || node.type.equals(FlowNode.TYPE_ACTOR)) {
                int headerH = node.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
                int cx = node.x + node.w / 2;
                int startY = node.y + headerH;
                int endY = node.y + node.h;
                int gap = 25;
                for (int py = startY; py <= endY; py += gap) {
                    g2.setColor(new Color(64, 158, 255, 180));
                    g2.fillOval(cx - 4, py - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.fillOval(cx - 2, py - 2, 4, 4);
                    g2.setColor(new Color(64, 158, 255));
                    g2.drawOval(cx - 4, py - 4, 8, 8);
                }
            } else if (node.type.equals(FlowNode.TYPE_ACTIVATION)) {
                Point[] pts = {
                    new Point(node.x + node.w / 2, node.y),
                    new Point(node.x + node.w / 2, node.y + node.h / 2),
                    new Point(node.x + node.w / 2, node.y + node.h)
                };
                for (Point pt : pts) {
                    g2.setColor(new Color(64, 158, 255, 180));
                    g2.fillOval(pt.x - 4, pt.y - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.fillOval(pt.x - 2, pt.y - 2, 4, 4);
                    g2.setColor(new Color(64, 158, 255));
                    g2.drawOval(pt.x - 4, pt.y - 4, 8, 8);
                }
            } else {
                // 普通目标节点显示 8 个吸附点
                double[][] relCoords = {
                    {0.5, 0.0}, {1.0, 0.5}, {0.5, 1.0}, {0.0, 0.5},
                    {0.0, 0.0}, {1.0, 0.0}, {0.0, 1.0}, {1.0, 1.0}
                };
                for (double[] rel : relCoords) {
                    Point pt = node.getConnectionPoint(rel[0], rel[1]);
                    g2.setColor(new Color(64, 158, 255, 180));
                    g2.fillOval(pt.x - 4, pt.y - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.fillOval(pt.x - 2, pt.y - 2, 4, 4);
                    g2.setColor(new Color(64, 158, 255));
                    g2.drawOval(pt.x - 4, pt.y - 4, 8, 8);
                }
            }
        } else {
            // 2. 如果是非连线状态下鼠标移动悬停 (SELECT 模式下高亮拖拽线引导)
            if (node.type.equals(FlowNode.TYPE_LIFELINE) || node.type.equals(FlowNode.TYPE_ACTOR)) {
                int headerH = node.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
                int cx = node.x + node.w / 2;
                int startY = node.y + headerH;
                int endY = node.y + node.h;
                int gap = 25;
                for (int py = startY; py <= endY; py += gap) {
                    g2.setColor(new Color(64, 158, 255, 180));
                    g2.fillOval(cx - 4, py - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.fillOval(cx - 2, py - 2, 4, 4);
                    g2.setColor(new Color(64, 158, 255));
                    g2.drawOval(cx - 4, py - 4, 8, 8);
                }
            } else if (node.type.equals(FlowNode.TYPE_ACTIVATION)) {
                Point[] pts = {
                    new Point(node.x + node.w / 2, node.y),
                    new Point(node.x + node.w / 2, node.y + node.h / 2),
                    new Point(node.x + node.w / 2, node.y + node.h)
                };
                for (Point pt : pts) {
                    g2.setColor(new Color(64, 158, 255, 180));
                    g2.fillOval(pt.x - 4, pt.y - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.fillOval(pt.x - 2, pt.y - 2, 4, 4);
                    g2.setColor(new Color(64, 158, 255));
                    g2.drawOval(pt.x - 4, pt.y - 4, 8, 8);
                }
            } else {
                // 普通节点同样在 SELECT 悬停时高亮 8 个端口，供用户直接拖出新连线
                double[][] relCoords = {
                    {0.5, 0.0}, {1.0, 0.5}, {0.5, 1.0}, {0.0, 0.5},
                    {0.0, 0.0}, {1.0, 0.0}, {0.0, 1.0}, {1.0, 1.0}
                };
                for (double[] rel : relCoords) {
                    Point pt = node.getConnectionPoint(rel[0], rel[1]);
                    g2.setColor(new Color(64, 158, 255, 180));
                    g2.fillOval(pt.x - 4, pt.y - 4, 8, 8);
                    g2.setColor(Color.WHITE);
                    g2.fillOval(pt.x - 2, pt.y - 2, 4, 4);
                    g2.setColor(new Color(64, 158, 255));
                    g2.drawOval(pt.x - 4, pt.y - 4, 8, 8);
                }
            }
        }
    }

    private java.awt.geom.Point2D.Double snapToBestPosition(FlowNode target, Point mousePt, Point[] outSnapPoint) {
        if (target == null) return new java.awt.geom.Point2D.Double(0.5, 0.5);
        
        if (target.type.equals(FlowNode.TYPE_LIFELINE) || target.type.equals(FlowNode.TYPE_ACTOR)) {
            int headerH = target.type.equals(FlowNode.TYPE_LIFELINE) ? 35 : 50;
            int cx = target.x + target.w / 2;
            int startY = target.y + headerH;
            int endY = target.y + target.h;
            int gap = 25;
            
            int totalPoints = 0;
            for (int py = startY; py <= endY; py += gap) {
                totalPoints++;
            }
            
            double minDist = Double.MAX_VALUE;
            double bestRy = 0.5;
            Point bestPt = new Point(cx, target.y + target.h / 2);
            int bestIdx = -1;
            
            int idx = 0;
            for (int py = startY; py <= endY; py += gap) {
                Point pt = new Point(cx, py);
                double dist = mousePt.distance(pt);
                if (dist < minDist) {
                    minDist = dist;
                    bestRy = (double)(py - target.y) / target.h;
                    bestPt = pt;
                    bestIdx = idx;
                }
                idx++;
            }
            
            // 仅进行定位吸附计算，不再拖拽中途触发延长
            outSnapPoint[0] = bestPt;
            return new java.awt.geom.Point2D.Double(0.5, bestRy);
        }

        if (target.type.equals(FlowNode.TYPE_ACTIVATION)) {
            Point[] pts = {
                new Point(target.x + target.w / 2, target.y),
                new Point(target.x + target.w / 2, target.y + target.h / 2),
                new Point(target.x + target.w / 2, target.y + target.h)
            };
            double[] relYs = {0.0, 0.5, 1.0};
            double minDist = Double.MAX_VALUE;
            int bestIdx = 1;
            for (int i = 0; i < pts.length; i++) {
                double dist = mousePt.distance(pts[i]);
                if (dist < minDist) {
                    minDist = dist;
                    bestIdx = i;
                }
            }
            outSnapPoint[0] = pts[bestIdx];
            return new java.awt.geom.Point2D.Double(0.5, relYs[bestIdx]);
        }
        
        // 普通节点 8 个推荐候选位置 (上、右、下、左、左上、右上、左下、右下)
        double[][] candidates = {
            {0.5, 0.0}, // 上
            {1.0, 0.5}, // 右
            {0.5, 1.0}, // 下
            {0.0, 0.5}, // 左
            {0.0, 0.0}, // 左上
            {1.0, 0.0}, // 右上
            {0.0, 1.0}, // 左下
            {1.0, 1.0}  // 右下
        };
        
        double minDist = Double.MAX_VALUE;
        double bestRx = 0.5;
        double bestRy = 0.5;
        Point bestPt = null;
        
        for (double[] cand : candidates) {
            Point candPt = target.getConnectionPoint(cand[0], cand[1]);
            double dist = mousePt.distance(candPt);
            if (dist < minDist) {
                minDist = dist;
                bestRx = cand[0];
                bestRy = cand[1];
                bestPt = candPt;
            }
        }
        
        // 如果离推荐的 8 个吸附点较近（小于18像素），直接吸附到该推荐位置上
        if (minDist < 18.0) {
            outSnapPoint[0] = bestPt;
            return new java.awt.geom.Point2D.Double(bestRx, bestRy);
        }
        
        java.awt.geom.Point2D.Double rel = target.getClosestRelativePoint(mousePt);
        outSnapPoint[0] = target.getConnectionPoint(rel.x, rel.y);
        return rel;
    }
}
