package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BPMN 2.0 流程图设计器：支持节点连接点及吸附连线、框选批量移动、右键上下文菜单、无限画布自适应扩容、一键自动拓扑排版。
 */
public class BpmnPanel extends ToolPanel {

    // 核心节点类型定义
    public static final String TYPE_START = "startEvent";
    public static final String TYPE_END = "endEvent";
    public static final String TYPE_USER_TASK = "userTask";
    public static final String TYPE_SERVICE_TASK = "serviceTask";
    public static final String TYPE_RECEIVE_TASK = "receiveTask";
    public static final String TYPE_SEND_TASK = "sendTask";
    public static final String TYPE_SCRIPT_TASK = "scriptTask";
    public static final String TYPE_BUSINESS_RULE_TASK = "businessRuleTask";
    public static final String TYPE_GATEWAY = "exclusiveGateway";
    public static final String TYPE_PARALLEL_GATEWAY = "parallelGateway";
    public static final String TYPE_EVENT_GATEWAY = "eventGateway";

    // 设计器工作模式
    private enum Mode {
        SELECT, SELECT_BOX, ADD_START, ADD_END, ADD_USER, ADD_SERVICE, ADD_RECEIVE, ADD_SEND, ADD_SCRIPT, ADD_RULE, ADD_EXCLUSIVE, ADD_PARALLEL, ADD_EVENT, CONNECT
    }

    private Mode currentMode = Mode.SELECT;

    // 数据模型
    private final List<BpmnNode> nodes = new ArrayList<>();
    private final List<BpmnEdge> edges = new ArrayList<>();

    // 选中元素管理
    private final List<BpmnNode> selectedNodes = new ArrayList<>();
    private BpmnNode selectedNode = null; 
    private BpmnEdge selectedEdge = null;

    // 交互辅助状态
    private BpmnNode draggedNode = null;
    private BpmnNode connectSourceNode = null;
    private int connectSourcePortIndex = 1; 
    private boolean isConnectingViaHandle = false;

    // 磁吸状态
    private BpmnNode tempTargetNode = null;
    private int tempTargetPortIndex = -1;

    // 框选辅助状态
    private Point selectionStart = null;
    private Rectangle selectionRect = null;

    private Point lastMousePoint = new Point();
    private Point currentMousePoint = new Point();

    // UI 组件
    private CanvasPanel canvasPanel;
    private JScrollPane scrollPane; // 提取为成员变量，用于无限画布视口位移补偿
    private JTextField idField;
    private JTextField nameField;
    private JTextField condField;
    private JPanel propPanel;
    private JLabel modeLabel;

    private boolean updatingProperties = false;

    public BpmnPanel() {
        super("chart", "bpmn.designer",
                "BPMN", "工作流", "设计器", "Workflow", "Process", "流程图", "Camunda", "Activiti");
        
        // 初始化默认流程
        initDefaultProcess();
    }

    private void initDefaultProcess() {
        BpmnNode start = new BpmnNode(TYPE_START, "StartEvent_1", "开始", 60, 180);
        BpmnNode task = new BpmnNode(TYPE_USER_TASK, "Activity_1", "用户审批", 180, 170);
        BpmnNode gateway = new BpmnNode(TYPE_GATEWAY, "Gateway_1", "是否同意", 350, 175);
        BpmnNode serviceTask = new BpmnNode(TYPE_SERVICE_TASK, "Activity_2", "发送通知", 460, 80);
        BpmnNode end1 = new BpmnNode(TYPE_END, "EndEvent_1", "结束", 620, 180);
        
        nodes.add(start);
        nodes.add(task);
        nodes.add(gateway);
        nodes.add(serviceTask);
        nodes.add(end1);

        edges.add(new BpmnEdge("Flow_1", "", start, task, 1, 3));
        edges.add(new BpmnEdge("Flow_2", "", task, gateway, 1, 3));
        edges.add(new BpmnEdge("Flow_3", "同意", gateway, serviceTask, 0, 3));
        edges.add(new BpmnEdge("Flow_4", "", serviceTask, end1, 1, 0));
        
        SwingUtilities.invokeLater(this::adjustCanvasSize);
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(5, 5));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 顶部工具栏
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        ButtonGroup btnGroup = new ButtonGroup();

        // 辅助方法：快速创建工具栏图标按钮
        class ToolBarHelper {
            JToggleButton addToggle(String tooltip, Mode mode, String iconType) {
                JToggleButton btn = new JToggleButton(new BpmnIcon(iconType));
                btn.setToolTipText(tooltip);
                btn.addActionListener(e -> setMode(mode));
                btnGroup.add(btn);
                toolBar.add(btn);
                return btn;
            }
        }
        ToolBarHelper helper = new ToolBarHelper();

        JToggleButton selectBtn = helper.addToggle("选择/移动 (支持框选)", Mode.SELECT, "SELECT");
        toolBar.addSeparator();

        helper.addToggle("开始事件 (Start Event)", Mode.ADD_START, TYPE_START);
        helper.addToggle("结束事件 (End Event)", Mode.ADD_END, TYPE_END);
        toolBar.addSeparator();
        
        helper.addToggle("用户任务 (User Task)", Mode.ADD_USER, TYPE_USER_TASK);
        helper.addToggle("服务任务 (Service Task)", Mode.ADD_SERVICE, TYPE_SERVICE_TASK);
        helper.addToggle("接收任务 (Receive Task)", Mode.ADD_RECEIVE, TYPE_RECEIVE_TASK);
        helper.addToggle("发送任务 (Send Task)", Mode.ADD_SEND, TYPE_SEND_TASK);
        helper.addToggle("脚本任务 (Script Task)", Mode.ADD_SCRIPT, TYPE_SCRIPT_TASK);
        helper.addToggle("业务规则任务 (Business Rule Task)", Mode.ADD_RULE, TYPE_BUSINESS_RULE_TASK);
        toolBar.addSeparator();

        helper.addToggle("互斥网关 (Exclusive Gateway)", Mode.ADD_EXCLUSIVE, TYPE_GATEWAY);
        helper.addToggle("并行网关 (Parallel Gateway)", Mode.ADD_PARALLEL, TYPE_PARALLEL_GATEWAY);
        helper.addToggle("事件网关 (Event Gateway)", Mode.ADD_EVENT, TYPE_EVENT_GATEWAY);
        toolBar.addSeparator();

        helper.addToggle("连线 (Sequence Flow)", Mode.CONNECT, "CONNECT");
        toolBar.addSeparator();

        JButton deleteBtn = new JButton("删除选中");
        deleteBtn.addActionListener(e -> deleteSelected());
        toolBar.add(deleteBtn);

        JButton clearBtn = new JButton("清空画布");
        clearBtn.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(root, "确定要清空画布吗？", "确认", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                nodes.clear();
                edges.clear();
                clearSelection();
                adjustCanvasSize();
                canvasPanel.repaint();
            }
        });
        toolBar.add(clearBtn);

        toolBar.addSeparator();

        // 一键排版按钮
        JButton layoutBtn = new JButton(new BpmnIcon("LAYOUT"));
        layoutBtn.setToolTipText("一键智能拓扑排版 (Auto Layout)");
        layoutBtn.addActionListener(e -> autoLayout());
        toolBar.add(layoutBtn);

        toolBar.add(Box.createHorizontalGlue());

        JButton exportXmlBtn = new JButton("导出 XML");
        exportXmlBtn.addActionListener(e -> exportBpmXml());
        toolBar.add(exportXmlBtn);

        JButton exportPngBtn = new JButton("导出图片");
        exportPngBtn.addActionListener(e -> exportPng());
        toolBar.add(exportPngBtn);

        root.add(toolBar, BorderLayout.NORTH);

        // 中间主画布
        canvasPanel = new CanvasPanel();
        canvasPanel.setBackground(UIManager.getColor("TextArea.background"));
        scrollPane = new JScrollPane(canvasPanel);
        scrollPane.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));

        // 右侧属性面板
        propPanel = new JPanel();
        propPanel.setPreferredSize(new Dimension(240, 0));
        propPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, UIManager.getColor("Component.borderColor")),
                " 属性编辑 ", TitledBorder.LEFT, TitledBorder.TOP, UIUtils.plainFont()));
        propPanel.setLayout(new BoxLayout(propPanel, BoxLayout.Y_AXIS));
        propPanel.setBorder(BorderFactory.createCompoundBorder(propPanel.getBorder(), new EmptyBorder(10, 10, 10, 10)));

        modeLabel = new JLabel("模式：选择/移动");
        modeLabel.setFont(UIUtils.plainFont());
        modeLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        propPanel.add(modeLabel);
        propPanel.add(Box.createVerticalStrut(15));

        idField = new JTextField();
        nameField = new JTextField();
        condField = new JTextField();

        addPropertyField("元素 ID:", idField);
        addPropertyField("元素名称:", nameField);
        addPropertyField("流转条件:", condField);

        DocumentListener docListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateSelectedProperties(); }
            public void removeUpdate(DocumentEvent e) { updateSelectedProperties(); }
            public void changedUpdate(DocumentEvent e) { updateSelectedProperties(); }
        };

        idField.getDocument().addDocumentListener(docListener);
        nameField.getDocument().addDocumentListener(docListener);
        condField.getDocument().addDocumentListener(docListener);

        updatePropertyPanelVisibility();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scrollPane, propPanel);
        splitPane.setResizeWeight(0.85);
        splitPane.setDividerLocation(750);
        root.add(splitPane, BorderLayout.CENTER);

        // 快捷键支持
        canvasPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteAction");
        canvasPanel.getActionMap().put("deleteAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteSelected();
            }
        });

        // 鼠标交互监听
        MouseAdapter mouseAdapter = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (checkPopupTrigger(e)) return;

                if (e.getClickCount() == 2 && currentMode == Mode.SELECT) {
                    Point p = e.getPoint();
                    BpmnEdge clickedEdge = findEdgeAt(p);
                    if (clickedEdge != null) {
                        // 在连线中间双击插入节点
                        Point mid = new Point((clickedEdge.source.getPortPoint(clickedEdge.sourcePortIndex).x + clickedEdge.target.getPortPoint(clickedEdge.targetPortIndex).x) / 2,
                                              (clickedEdge.source.getPortPoint(clickedEdge.sourcePortIndex).y + clickedEdge.target.getPortPoint(clickedEdge.targetPortIndex).y) / 2);
                        String randId = "UserTask_" + UUID.randomUUID().toString().substring(0, 6);
                        BpmnNode newNode = new BpmnNode(TYPE_USER_TASK, randId, "新任务节点", mid.x - 50, mid.y - 30);
                        nodes.add(newNode);

                        BpmnNode source = clickedEdge.source;
                        BpmnNode target = clickedEdge.target;
                        int sPort = clickedEdge.sourcePortIndex;
                        int tPort = clickedEdge.targetPortIndex;
                        edges.remove(clickedEdge);

                        edges.add(new BpmnEdge("Flow_" + UUID.randomUUID().toString().substring(0, 6), "", source, newNode, sPort, 3));
                        edges.add(new BpmnEdge("Flow_" + UUID.randomUUID().toString().substring(0, 6), "", newNode, target, 1, tPort));

                        setSelectedNode(newNode);
                        setSelectedEdge(null);
                        adjustCanvasSize();
                        canvasPanel.repaint();
                        return;
                    }
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                canvasPanel.requestFocusInWindow();
                if (checkPopupTrigger(e)) return;

                Point p = e.getPoint();
                lastMousePoint = p;

                if (currentMode == Mode.SELECT) {
                    if (selectedNode != null && isOverConnectionHandle(p, selectedNode)) {
                        isConnectingViaHandle = true;
                        connectSourceNode = selectedNode;
                        connectSourcePortIndex = 1; 
                        currentMousePoint = p;
                        canvasPanel.repaint();
                        return;
                    }

                    for (BpmnNode node : nodes) {
                        for (int i = 0; i < 4; i++) {
                            if (node.getPortPoint(i).distance(p) < 8.0) {
                                isConnectingViaHandle = true;
                                connectSourceNode = node;
                                connectSourcePortIndex = i;
                                currentMousePoint = p;
                                canvasPanel.repaint();
                                return;
                            }
                        }
                    }

                    BpmnNode clickedNode = findNodeAt(p);
                    if (clickedNode != null) {
                        if (selectedNodes.contains(clickedNode)) {
                            draggedNode = clickedNode;
                        } else {
                            selectedNodes.clear();
                            selectedNodes.add(clickedNode);
                            draggedNode = clickedNode;
                            setSelectedNode(clickedNode);
                            setSelectedEdge(null);
                        }
                    } else {
                        BpmnEdge clickedEdge = findEdgeAt(p);
                        if (clickedEdge != null) {
                            setSelectedEdge(clickedEdge);
                            setSelectedNode(null);
                            selectedNodes.clear();
                        } else {
                            setSelectedNode(null);
                            setSelectedEdge(null);
                            selectedNodes.clear();
                            selectionStart = p;
                            selectionRect = new Rectangle(p.x, p.y, 0, 0);
                            currentMode = Mode.SELECT_BOX;
                        }
                    }
                    canvasPanel.repaint();
                } else if (currentMode == Mode.CONNECT) {
                    BpmnNode clickedNode = findNodeAt(p);
                    if (clickedNode != null) {
                        connectSourceNode = clickedNode;
                        connectSourcePortIndex = getClosestPortIndex(clickedNode, p);
                        currentMousePoint = p;
                    }
                } else {
                    // 新增节点
                    String type = "";
                    String prefix = "";
                    int w = 100, h = 60;
                    switch (currentMode) {
                        case ADD_START: type = TYPE_START; prefix = "StartEvent_"; w = 40; h = 40; break;
                        case ADD_END: type = TYPE_END; prefix = "EndEvent_"; w = 40; h = 40; break;
                        case ADD_USER: type = TYPE_USER_TASK; prefix = "UserTask_"; break;
                        case ADD_SERVICE: type = TYPE_SERVICE_TASK; prefix = "ServiceTask_"; break;
                        case ADD_RECEIVE: type = TYPE_RECEIVE_TASK; prefix = "ReceiveTask_"; break;
                        case ADD_SEND: type = TYPE_SEND_TASK; prefix = "SendTask_"; break;
                        case ADD_SCRIPT: type = TYPE_SCRIPT_TASK; prefix = "ScriptTask_"; break;
                        case ADD_RULE: type = TYPE_BUSINESS_RULE_TASK; prefix = "BusinessRuleTask_"; break;
                        case ADD_EXCLUSIVE: type = TYPE_GATEWAY; prefix = "ExclusiveGateway_"; w = 50; h = 50; break;
                        case ADD_PARALLEL: type = TYPE_PARALLEL_GATEWAY; prefix = "ParallelGateway_"; w = 50; h = 50; break;
                        case ADD_EVENT: type = TYPE_EVENT_GATEWAY; prefix = "EventGateway_"; w = 50; h = 50; break;
                    }
                    if (!type.isEmpty()) {
                        String randId = prefix + UUID.randomUUID().toString().substring(0, 6);
                        BpmnNode node = new BpmnNode(type, randId, "新节点", p.x - w / 2, p.y - h / 2);
                        nodes.add(node);
                        setSelectedNode(node);
                        setSelectedEdge(null);
                        adjustCanvasSize();
                        selectBtn.setSelected(true);
                        setMode(Mode.SELECT);
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (checkPopupTrigger(e)) return;

                if (currentMode == Mode.SELECT) {
                    if (isConnectingViaHandle) {
                        if (tempTargetNode != null && tempTargetNode != connectSourceNode && tempTargetPortIndex != -1) {
                            String flowId = "Flow_" + UUID.randomUUID().toString().substring(0, 6);
                            edges.add(new BpmnEdge(flowId, "", connectSourceNode, tempTargetNode, connectSourcePortIndex, tempTargetPortIndex));
                            setSelectedEdge(edges.get(edges.size() - 1));
                            setSelectedNode(null);
                        }
                        isConnectingViaHandle = false;
                        connectSourceNode = null;
                        tempTargetNode = null;
                        tempTargetPortIndex = -1;
                        canvasPanel.repaint();
                    }
                    if (draggedNode != null) {
                        adjustCanvasSize();
                    }
                    draggedNode = null;
                } else if (currentMode == Mode.SELECT_BOX) {
                    if (selectionRect != null) {
                        for (BpmnNode node : nodes) {
                            if (selectionRect.intersects(new Rectangle(node.x, node.y, node.w, node.h))) {
                                selectedNodes.add(node);
                            }
                        }
                        if (!selectedNodes.isEmpty()) {
                            setSelectedNode(selectedNodes.get(0));
                        }
                    }
                    selectionStart = null;
                    selectionRect = null;
                    currentMode = Mode.SELECT;
                    canvasPanel.repaint();
                } else if (currentMode == Mode.CONNECT && connectSourceNode != null) {
                    if (tempTargetNode != null && tempTargetNode != connectSourceNode && tempTargetPortIndex != -1) {
                        String flowId = "Flow_" + UUID.randomUUID().toString().substring(0, 6);
                        edges.add(new BpmnEdge(flowId, "", connectSourceNode, tempTargetNode, connectSourcePortIndex, tempTargetPortIndex));
                        setSelectedEdge(edges.get(edges.size() - 1));
                        setSelectedNode(null);
                        selectBtn.setSelected(true);
                        setMode(Mode.SELECT);
                    }
                    connectSourceNode = null;
                    tempTargetNode = null;
                    tempTargetPortIndex = -1;
                    adjustCanvasSize();
                    canvasPanel.repaint();
                }
            }
        };

        canvasPanel.addMouseListener(mouseAdapter);
        canvasPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                Point p = e.getPoint();
                if (currentMode == Mode.SELECT) {
                    if (isConnectingViaHandle) {
                        checkForMagnet(p);
                        canvasPanel.repaint();
                    } else if (draggedNode != null) {
                        int dx = p.x - lastMousePoint.x;
                        int dy = p.y - lastMousePoint.y;
                        for (BpmnNode sel : selectedNodes) {
                            sel.x = sel.x + dx; // 解除左边界硬限制以实现左上方向无限画布
                            sel.y = sel.y + dy;
                        }
                        lastMousePoint = p;
                        
                        adjustCanvasSize();
                        canvasPanel.repaint();
                    }
                } else if (currentMode == Mode.SELECT_BOX) {
                    if (selectionStart != null) {
                        int x1 = Math.min(selectionStart.x, p.x);
                        int y1 = Math.min(selectionStart.y, p.y);
                        int w1 = Math.abs(selectionStart.x - p.x);
                        int h1 = Math.abs(selectionStart.y - p.y);
                        selectionRect = new Rectangle(x1, y1, w1, h1);
                        canvasPanel.repaint();
                    }
                } else if (currentMode == Mode.CONNECT && connectSourceNode != null) {
                    checkForMagnet(p);
                    canvasPanel.repaint();
                }
            }
        });

        return root;
    }

    private void adjustCanvasSize() {
        if (nodes.isEmpty()) return;

        // 1. 动态扫描左侧和上侧边界，检测是否发生越界
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (BpmnNode node : nodes) {
            minX = Math.min(minX, node.x);
            minY = Math.min(minY, node.y);
        }

        int shiftX = 0;
        int shiftY = 0;
        if (minX < 50) {
            shiftX = 50 - minX;
        }
        if (minY < 50) {
            shiftY = 50 - minY;
        }

        // 2. 如果左/上越界，将所有节点集体向右/向下偏移，实现左上无限画布
        if (shiftX > 0 || shiftY > 0) {
            for (BpmnNode node : nodes) {
                node.x += shiftX;
                node.y += shiftY;
            }

            // 重新计算画布宽度并进行视口位置微调，避免视图跳动
            int maxX = 1500;
            int maxY = 1000;
            for (BpmnNode node : nodes) {
                maxX = Math.max(maxX, node.x + node.w + 150);
                maxY = Math.max(maxY, node.y + node.h + 150);
            }
            canvasPanel.setPreferredSize(new Dimension(maxX, maxY));
            canvasPanel.revalidate();

            if (scrollPane != null) {
                final int finalShiftX = shiftX;
                final int finalShiftY = shiftY;
                final Point oldViewPos = scrollPane.getViewport().getViewPosition();
                SwingUtilities.invokeLater(() -> {
                    scrollPane.getViewport().setViewPosition(new Point(oldViewPos.x + finalShiftX, oldViewPos.y + finalShiftY));
                });
            }
        } else {
            // 右侧和下侧扩容
            int maxX = 1500;
            int maxY = 1000;
            for (BpmnNode node : nodes) {
                maxX = Math.max(maxX, node.x + node.w + 150);
                maxY = Math.max(maxY, node.y + node.h + 150);
            }
            Dimension currentSize = canvasPanel.getPreferredSize();
            if (currentSize.width != maxX || currentSize.height != maxY) {
                canvasPanel.setPreferredSize(new Dimension(maxX, maxY));
                canvasPanel.revalidate();
            }
        }
    }

    private void autoLayout() {
        if (nodes.isEmpty()) return;

        // 1. 初始化每个节点的 level = -1
        for (BpmnNode node : nodes) {
            node.level = -1;
        }

        // 计算入度
        java.util.Map<BpmnNode, Integer> inDegree = new java.util.HashMap<>();
        for (BpmnNode node : nodes) {
            inDegree.put(node, 0);
        }
        for (BpmnEdge edge : edges) {
            inDegree.put(edge.target, inDegree.get(edge.target) + 1);
        }

        // 2. 查找开始节点作为最顶端层次起点
        java.util.Queue<BpmnNode> queue = new java.util.LinkedList<>();
        for (BpmnNode node : nodes) {
            if (node.type.equals(TYPE_START) && inDegree.get(node) == 0) {
                node.level = 0;
                queue.add(node);
            }
        }
        // 查找其他入度为 0 的节点
        for (BpmnNode node : nodes) {
            if (node.level == -1 && inDegree.get(node) == 0) {
                node.level = 0;
                queue.add(node);
            }
        }
        // 若全部都有环路入度，随机选第一个作为第 0 层开始
        if (queue.isEmpty()) {
            BpmnNode first = nodes.get(0);
            first.level = 0;
            queue.add(first);
        }

        // 3. BFS 分层
        while (!queue.isEmpty()) {
            BpmnNode curr = queue.poll();
            for (BpmnEdge edge : edges) {
                if (edge.source == curr) {
                    BpmnNode next = edge.target;
                    if (next.level < curr.level + 1) {
                        next.level = curr.level + 1;
                        queue.add(next);
                    }
                }
            }
        }

        // 孤立节点设为 0
        for (BpmnNode node : nodes) {
            if (node.level == -1) {
                node.level = 0;
            }
        }

        // 4. 根据 level 分组排序
        java.util.Map<Integer, List<BpmnNode>> levelMap = new java.util.TreeMap<>();
        for (BpmnNode node : nodes) {
            levelMap.computeIfAbsent(node.level, k -> new ArrayList<>()).add(node);
        }

        // 5. 分配物理坐标
        int colGap = 220;
        int rowGap = 120;
        int startX = 80;
        int startY = 220;

        for (java.util.Map.Entry<Integer, List<BpmnNode>> entry : levelMap.entrySet()) {
            int level = entry.getKey();
            List<BpmnNode> list = entry.getValue();
            int size = list.size();
            for (int i = 0; i < size; i++) {
                BpmnNode n = list.get(i);
                n.x = startX + level * colGap;
                n.y = startY + i * rowGap - (size - 1) * rowGap / 2;
                if (n.y < 40) n.y = 40;
            }
        }

        // 6. 智能连线端口自动校准指向
        for (BpmnEdge edge : edges) {
            BpmnNode s = edge.source;
            BpmnNode t = edge.target;
            int dx = t.x - s.x;
            int dy = t.y - s.y;

            if (Math.abs(dx) >= Math.abs(dy)) {
                if (dx >= 0) {
                    edge.sourcePortIndex = 1; // 右出
                    edge.targetPortIndex = 3; // 左入
                } else {
                    edge.sourcePortIndex = 3; // 左出
                    edge.targetPortIndex = 1; // 右入
                }
            } else {
                if (dy >= 0) {
                    edge.sourcePortIndex = 2; // 下出
                    edge.targetPortIndex = 0; // 上入
                } else {
                    edge.sourcePortIndex = 0; // 上出
                    edge.targetPortIndex = 2; // 下入
                }
            }
        }

        adjustCanvasSize();
        canvasPanel.repaint();
    }

    private void addPropertyField(String label, JTextField field) {
        JLabel lbl = new JLabel(label);
        lbl.setFont(UIUtils.plainFont());
        lbl.setAlignmentX(Component.LEFT_ALIGNMENT);
        propPanel.add(lbl);
        propPanel.add(Box.createVerticalStrut(3));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        field.setPreferredSize(new Dimension(200, 30));
        field.setFont(UIUtils.plainFont());
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        propPanel.add(field);
        propPanel.add(Box.createVerticalStrut(10));
    }

    private void setMode(Mode mode) {
        this.currentMode = mode;
        switch (mode) {
            case SELECT: modeLabel.setText("模式：选择/移动"); break;
            case CONNECT: modeLabel.setText("模式：边界点连线"); break;
            default: modeLabel.setText("模式：添加节点"); break;
        }
        connectSourceNode = null;
        draggedNode = null;
        clearSelection();
        canvasPanel.repaint();
    }

    private void clearSelection() {
        selectedNode = null;
        selectedEdge = null;
        selectedNodes.clear();
        updatePropertyPanelVisibility();
    }

    private void deleteSelected() {
        if (selectedNode != null) {
            edges.removeIf(edge -> selectedNodes.contains(edge.source) || selectedNodes.contains(edge.target));
            nodes.removeAll(selectedNodes);
            clearSelection();
            adjustCanvasSize();
        } else if (selectedEdge != null) {
            edges.remove(selectedEdge);
            clearSelection();
        }
        canvasPanel.repaint();
    }

    private void setSelectedNode(BpmnNode node) {
        this.selectedNode = node;
        updatePropertyPanelVisibility();
        if (node != null) {
            updatingProperties = true;
            idField.setText(node.id);
            nameField.setText(node.name);
            condField.setText("");
            updatingProperties = false;
        }
    }

    private void setSelectedEdge(BpmnEdge edge) {
        this.selectedEdge = edge;
        updatePropertyPanelVisibility();
        if (edge != null) {
            updatingProperties = true;
            idField.setText(edge.id);
            nameField.setText(edge.name);
            condField.setText(edge.condition);
            updatingProperties = false;
        }
    }

    private void updatePropertyPanelVisibility() {
        boolean showIdAndName = (selectedNode != null || selectedEdge != null);
        boolean showCond = (selectedEdge != null);

        idField.setVisible(showIdAndName);
        nameField.setVisible(showIdAndName);
        condField.setVisible(showCond);

        for (int i = 0; i < propPanel.getComponentCount(); i++) {
            Component c = propPanel.getComponent(i);
            if (c instanceof JLabel) {
                JLabel l = (JLabel) c;
                if (l.getText().startsWith("元素 ID")) {
                    l.setVisible(showIdAndName);
                } else if (l.getText().startsWith("元素名称")) {
                    l.setVisible(showIdAndName);
                } else if (l.getText().startsWith("流转条件")) {
                    l.setVisible(showCond);
                }
            }
        }
        propPanel.revalidate();
        propPanel.repaint();
    }

    private void updateSelectedProperties() {
        if (updatingProperties) return;
        if (selectedNode != null) {
            selectedNode.id = idField.getText().trim();
            selectedNode.name = nameField.getText().trim();
            canvasPanel.repaint();
        } else if (selectedEdge != null) {
            selectedEdge.id = idField.getText().trim();
            selectedEdge.name = nameField.getText().trim();
            selectedEdge.condition = condField.getText().trim();
            canvasPanel.repaint();
        }
    }

    private BpmnNode findNodeAt(Point p) {
        for (BpmnNode node : nodes) {
            if (node.contains(p)) return node;
        }
        return null;
    }

    private BpmnEdge findEdgeAt(Point p) {
        for (BpmnEdge edge : edges) {
            Point p1 = edge.getIntersectionPointSource();
            Point p2 = edge.getIntersectionPointTarget();
            double dist = Line2D.ptSegDist(p1.x, p1.y, p2.x, p2.y, p.x, p.y);
            if (dist < 6.0) return edge;
        }
        return null;
    }

    private boolean isOverConnectionHandle(Point p, BpmnNode node) {
        if (node == null) return false;
        int hx = node.x + node.w + 12;
        int hy = node.y + node.h / 2 - 8;
        return p.x >= hx && p.x <= hx + 16 && p.y >= hy && p.y <= hy + 16;
    }

    private int getClosestPortIndex(BpmnNode node, Point p) {
        double minDist = Double.MAX_VALUE;
        int best = 0;
        for (int i = 0; i < 4; i++) {
            double d = node.getPortPoint(i).distance(p);
            if (d < minDist) {
                minDist = d;
                best = i;
            }
        }
        return best;
    }

    private void checkForMagnet(Point p) {
        BpmnNode bestNode = null;
        int bestPort = -1;
        double bestDist = 18.0; 

        for (BpmnNode node : nodes) {
            if (node == connectSourceNode) continue;
            for (int i = 0; i < 4; i++) {
                double d = node.getPortPoint(i).distance(p);
                if (d < bestDist) {
                    bestDist = d;
                    bestNode = node;
                    bestPort = i;
                }
            }
        }

        if (bestNode != null) {
            tempTargetNode = bestNode;
            tempTargetPortIndex = bestPort;
            currentMousePoint = bestNode.getPortPoint(bestPort);
        } else {
            tempTargetNode = null;
            tempTargetPortIndex = -1;
            currentMousePoint = p;
        }
    }

    private boolean checkPopupTrigger(MouseEvent e) {
        if (e.isPopupTrigger()) {
            Point p = e.getPoint();
            BpmnNode node = findNodeAt(p);
            if (node != null) {
                showNodeContextMenu(node, e.getComponent(), e.getX(), e.getY());
            } else {
                BpmnEdge edge = findEdgeAt(p);
                if (edge != null) {
                    showEdgeContextMenu(edge, e.getComponent(), e.getX(), e.getY());
                } else {
                    showCanvasContextMenu(p, e.getComponent(), e.getX(), e.getY());
                }
            }
            return true;
        }
        return false;
    }

    private void showCanvasContextMenu(Point p, Component comp, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem startItem = new JMenuItem("新增开始事件");
        startItem.addActionListener(ev -> addNodeAtPoint(TYPE_START, p, 40, 40, "开始"));
        menu.add(startItem);

        JMenuItem endItem = new JMenuItem("新增结束事件");
        endItem.addActionListener(ev -> addNodeAtPoint(TYPE_END, p, 40, 40, "结束"));
        menu.add(endItem);

        menu.addSeparator();

        JMenu taskMenu = new JMenu("新增任务");
        addTaskMenuItem(taskMenu, "用户任务 (User Task)", TYPE_USER_TASK, p, "用户审批");
        addTaskMenuItem(taskMenu, "服务任务 (Service Task)", TYPE_SERVICE_TASK, p, "服务处理");
        addTaskMenuItem(taskMenu, "接收任务 (Receive Task)", TYPE_RECEIVE_TASK, p, "接收消息");
        addTaskMenuItem(taskMenu, "发送任务 (Send Task)", TYPE_SEND_TASK, p, "发送消息");
        addTaskMenuItem(taskMenu, "脚本任务 (Script Task)", TYPE_SCRIPT_TASK, p, "运行脚本");
        addTaskMenuItem(taskMenu, "业务规则任务", TYPE_BUSINESS_RULE_TASK, p, "规则执行");
        menu.add(taskMenu);

        JMenu gwMenu = new JMenu("新增网关");
        addGwMenuItem(gwMenu, "排他网关 (Exclusive)", TYPE_GATEWAY, p, "是否决策");
        addGwMenuItem(gwMenu, "并行网关 (Parallel)", TYPE_PARALLEL_GATEWAY, p, "并行分流");
        addGwMenuItem(gwMenu, "事件网关 (Event-Based)", TYPE_EVENT_GATEWAY, p, "事件分流");
        menu.add(gwMenu);

        menu.addSeparator();

        JMenuItem selectAll = new JMenuItem("全选节点");
        selectAll.addActionListener(ev -> {
            selectedNodes.clear();
            selectedNodes.addAll(nodes);
            if (!selectedNodes.isEmpty()) setSelectedNode(selectedNodes.get(0));
            canvasPanel.repaint();
        });
        menu.add(selectAll);

        JMenuItem autoLayoutItem = new JMenuItem("一键排版流程图");
        autoLayoutItem.addActionListener(ev -> autoLayout());
        menu.add(autoLayoutItem);

        JMenuItem clearAll = new JMenuItem("清空画布");
        clearAll.addActionListener(ev -> {
            if (JOptionPane.showConfirmDialog(comp, "确定要清空画布吗？", "确认", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                nodes.clear();
                edges.clear();
                clearSelection();
                adjustCanvasSize();
                canvasPanel.repaint();
            }
        });
        menu.add(clearAll);

        menu.show(comp, x, y);
    }

    private void addTaskMenuItem(JMenu parent, String name, String type, Point p, String defName) {
        JMenuItem item = new JMenuItem(name);
        item.addActionListener(ev -> addNodeAtPoint(type, p, 100, 60, defName));
        parent.add(item);
    }

    private void addGwMenuItem(JMenu parent, String name, String type, Point p, String defName) {
        JMenuItem item = new JMenuItem(name);
        item.addActionListener(ev -> addNodeAtPoint(type, p, 50, 50, defName));
        parent.add(item);
    }

    private void addNodeAtPoint(String type, Point p, int w, int h, String defName) {
        String randId = type + "_" + UUID.randomUUID().toString().substring(0, 6);
        BpmnNode node = new BpmnNode(type, randId, defName, p.x - w / 2, p.y - h / 2);
        nodes.add(node);
        setSelectedNode(node);
        selectedNodes.clear();
        selectedNodes.add(node);
        adjustCanvasSize();
        canvasPanel.repaint();
    }

    private void showNodeContextMenu(BpmnNode node, Component comp, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem rename = new JMenuItem("重命名 (Rename)");
        rename.addActionListener(ev -> {
            String input = JOptionPane.showInputDialog(comp, "输入节点新名称：", node.name);
            if (input != null) {
                node.name = input.trim();
                setSelectedNode(node);
                canvasPanel.repaint();
            }
        });
        menu.add(rename);

        JMenu typeMenu = new JMenu("更改节点类型");
        addChangeTypeItem(typeMenu, node, "用户任务", TYPE_USER_TASK, 100, 60);
        addChangeTypeItem(typeMenu, node, "服务任务", TYPE_SERVICE_TASK, 100, 60);
        addChangeTypeItem(typeMenu, node, "接收任务", TYPE_RECEIVE_TASK, 100, 60);
        addChangeTypeItem(typeMenu, node, "发送任务", TYPE_SEND_TASK, 100, 60);
        addChangeTypeItem(typeMenu, node, "脚本任务", TYPE_SCRIPT_TASK, 100, 60);
        addChangeTypeItem(typeMenu, node, "业务规则任务", TYPE_BUSINESS_RULE_TASK, 100, 60);
        typeMenu.addSeparator();
        addChangeTypeItem(typeMenu, node, "排他网关", TYPE_GATEWAY, 50, 50);
        addChangeTypeItem(typeMenu, node, "并行网关", TYPE_PARALLEL_GATEWAY, 50, 50);
        addChangeTypeItem(typeMenu, node, "事件网关", TYPE_EVENT_GATEWAY, 50, 50);
        menu.add(typeMenu);

        menu.addSeparator();

        JMenuItem del = new JMenuItem("删除 (Delete)");
        del.addActionListener(ev -> {
            selectedNodes.clear();
            selectedNodes.add(node);
            setSelectedNode(node);
            deleteSelected();
        });
        menu.add(del);

        menu.show(comp, x, y);
    }

    private void addChangeTypeItem(JMenu parent, BpmnNode node, String label, String targetType, int targetW, int targetH) {
        JMenuItem item = new JMenuItem(label);
        item.setEnabled(!node.type.equals(targetType));
        item.addActionListener(ev -> {
            node.type = targetType;
            node.w = targetW;
            node.h = targetH;
            setSelectedNode(node);
            canvasPanel.repaint();
        });
        parent.add(item);
    }

    private void showEdgeContextMenu(BpmnEdge edge, Component comp, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem rename = new JMenuItem("编辑名称");
        rename.addActionListener(ev -> {
            String input = JOptionPane.showInputDialog(comp, "输入连线名称：", edge.name);
            if (input != null) {
                edge.name = input.trim();
                setSelectedEdge(edge);
                canvasPanel.repaint();
            }
        });
        menu.add(rename);

        JMenuItem cond = new JMenuItem("编辑流转条件");
        cond.addActionListener(ev -> {
            String input = JOptionPane.showInputDialog(comp, "输入流转条件表达式：", edge.condition);
            if (input != null) {
                edge.condition = input.trim();
                setSelectedEdge(edge);
                canvasPanel.repaint();
            }
        });
        menu.add(cond);

        menu.addSeparator();

        JMenuItem del = new JMenuItem("删除 (Delete)");
        del.addActionListener(ev -> {
            setSelectedEdge(edge);
            setSelectedNode(null);
            deleteSelected();
        });
        menu.add(del);

        menu.show(comp, x, y);
    }

    // ==========================================
    // 导出逻辑：XML 与 图片
    // ==========================================

    private void exportBpmXml() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("process.bpmn"));
        chooser.setDialogTitle("导出 BPMN 2.0 XML");
        if (chooser.showSaveDialog(canvasPanel) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(generateXmlString());
                JOptionPane.showMessageDialog(canvasPanel, "导出成功：" + file.getName());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(canvasPanel, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportPng() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("process.png"));
        chooser.setDialogTitle("导出流程图图片");
        if (chooser.showSaveDialog(canvasPanel) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
                int maxX = 0, maxY = 0;
                for (BpmnNode node : nodes) {
                    minX = Math.min(minX, node.x);
                    minY = Math.min(minY, node.y);
                    maxX = Math.max(maxX, node.x + node.w);
                    maxY = Math.max(maxY, node.y + node.h);
                }

                if (nodes.isEmpty()) {
                    minX = 0; minY = 0; maxX = 400; maxY = 300;
                } else {
                    minX = Math.max(0, minX - 40);
                    minY = Math.max(0, minY - 40);
                    maxX = maxX + 40;
                    maxY = maxY + 40;
                }

                int w = maxX - minX;
                int h = maxY - minY;

                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = image.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                g2.setColor(canvasPanel.getBackground());
                g2.fillRect(0, 0, w, h);

                g2.translate(-minX, -minY);
                canvasPanel.drawAll(g2);
                g2.dispose();

                ImageIO.write(image, "png", file);
                JOptionPane.showMessageDialog(canvasPanel, "导出成功：" + file.getName());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(canvasPanel, "导出失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private String generateXmlString() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\"\n");
        sb.append("                  xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\"\n");
        sb.append("                  xmlns:dc=\"http://www.omg.org/spec/DD/20100524/DC\"\n");
        sb.append("                  xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\"\n");
        sb.append("                  id=\"Definitions_1\" targetNamespace=\"http://bpmn.io/schema/bpmn\">\n");

        sb.append("  <bpmn:process id=\"Process_1\" isExecutable=\"true\">\n");

        for (BpmnNode node : nodes) {
            sb.append("    <bpmn:").append(node.type).append(" id=\"").append(node.id).append("\" name=\"").append(escapeXml(node.name)).append("\" />\n");
        }

        for (BpmnEdge edge : edges) {
            sb.append("    <bpmn:sequenceFlow id=\"").append(edge.id).append("\" name=\"").append(escapeXml(edge.name)).append("\"")
                    .append(" sourceRef=\"").append(edge.source.id).append("\" targetRef=\"").append(edge.target.id).append("\"");
            if (edge.condition != null && !edge.condition.trim().isEmpty()) {
                sb.append(">\n");
                sb.append("      <bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">")
                        .append(escapeXml(edge.condition)).append("</bpmn:conditionExpression>\n");
                sb.append("    </bpmn:sequenceFlow>\n");
            } else {
                sb.append(" />\n");
            }
        }

        sb.append("  </bpmn:process>\n");

        sb.append("  <bpmndi:BPMNDiagram id=\"BPMNDiagram_1\">\n");
        sb.append("    <bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"Process_1\">\n");

        for (BpmnNode node : nodes) {
            sb.append("      <bpmndi:BPMNShape id=\"").append(node.id).append("_di\" bpmnElement=\"").append(node.id).append("\">\n");
            sb.append("        <dc:Bounds x=\"").append(node.x).append("\" y=\"").append(node.y)
                    .append("\" width=\"").append(node.w).append("\" height=\"").append(node.h).append("\" />\n");
            sb.append("      </bpmndi:BPMNShape>\n");
        }

        for (BpmnEdge edge : edges) {
            Point p1 = edge.getIntersectionPointSource();
            Point p2 = edge.getIntersectionPointTarget();

            sb.append("      <bpmndi:BPMNEdge id=\"").append(edge.id).append("_di\" bpmnElement=\"").append(edge.id).append("\">\n");
            sb.append("        <di:waypoint x=\"").append(p1.x).append("\" y=\"").append(p1.y).append("\" />\n");
            sb.append("        <di:waypoint x=\"").append(p2.x).append("\" y=\"").append(p2.y).append("\" />\n");
            sb.append("      </bpmndi:BPMNEdge>\n");
        }

        sb.append("    </bpmndi:BPMNPlane>\n");
        sb.append("  </bpmndi:BPMNDiagram>\n");
        sb.append("</bpmn:definitions>\n");

        return sb.toString();
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    // ==========================================
    // 内部类：节点与线模型
    // ==========================================

    private static class BpmnNode {
        String type;
        String id;
        String name;
        int x, y;
        int w, h;
        int level = -1; // 自动布局层级

        BpmnNode(String type, String id, String name, int x, int y) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;

            if (type.equals(TYPE_START) || type.equals(TYPE_END)) {
                this.w = 40;
                this.h = 40;
            } else if (type.equals(TYPE_GATEWAY) || type.equals(TYPE_PARALLEL_GATEWAY) || type.equals(TYPE_EVENT_GATEWAY)) {
                this.w = 50;
                this.h = 50;
            } else {
                this.w = 100;
                this.h = 60;
            }
        }

        Point getCenter() {
            return new Point(x + w / 2, y + h / 2);
        }

        Point getPortPoint(int index) {
            switch (index) {
                case 0: return new Point(x + w / 2, y); // TOP
                case 1: return new Point(x + w, y + h / 2); // RIGHT
                case 2: return new Point(x + w / 2, y + h); // BOTTOM
                case 3: return new Point(x, y + h / 2); // LEFT
                default: return getCenter();
            }
        }

        boolean contains(Point p) {
            return p.x >= x && p.x <= x + w && p.y >= y && p.y <= y + h;
        }
    }

    private static class BpmnEdge {
        String id;
        String name;
        String condition = "";
        BpmnNode source;
        BpmnNode target;
        int sourcePortIndex = 1; 
        int targetPortIndex = 3;

        BpmnEdge(String id, String name, BpmnNode source, BpmnNode target, int sPort, int tPort) {
            this.id = id;
            this.name = name;
            this.source = source;
            this.target = target;
            this.sourcePortIndex = sPort;
            this.targetPortIndex = tPort;
        }

        Point getIntersectionPointSource() {
            return source.getPortPoint(sourcePortIndex);
        }

        Point getIntersectionPointTarget() {
            return target.getPortPoint(targetPortIndex);
        }
    }

    // ==========================================
    // 自绘制 Icon 类：实现扁平矢量化按钮小图标
    // ==========================================

    private static class BpmnIcon implements Icon {
        private final String iconType;
        private final int width = 18;
        private final int height = 18;

        BpmnIcon(String iconType) {
            this.iconType = iconType;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            
            Color themeColor = UIManager.getColor("Label.foreground");
            if (themeColor == null) themeColor = Color.BLACK;
            g2.setColor(themeColor);

            if (iconType.equals("SELECT")) {
                g2.setStroke(new BasicStroke(1.5f));
                int[] xp = {x + 2, x + 10, x + 7, x + 12, x + 10, x + 6, x + 2};
                int[] yp = {y + 2, y + 8, y + 9, y + 14, y + 15, y + 10, y + 10};
                g2.drawPolygon(xp, yp, 7);
            } else if (iconType.equals(TYPE_START)) {
                g2.setColor(new Color(70, 136, 71));
                g2.fillOval(x + 2, y + 2, 14, 14);
            } else if (iconType.equals(TYPE_END)) {
                g2.setColor(new Color(169, 68, 66));
                g2.setStroke(new BasicStroke(2.5f));
                g2.drawOval(x + 2, y + 2, 14, 14);
            } else if (iconType.equals(TYPE_USER_TASK)) {
                g2.setColor(new Color(0, 122, 255));
                g2.drawRoundRect(x + 1, y + 3, 16, 12, 4, 4);
                g2.drawOval(x + 7, y + 5, 4, 4);
                g2.drawArc(x + 4, y + 10, 10, 5, 0, 180);
            } else if (iconType.equals(TYPE_SERVICE_TASK)) {
                g2.setColor(new Color(0, 122, 255));
                g2.drawRoundRect(x + 1, y + 3, 16, 12, 4, 4);
                g2.drawOval(x + 6, y + 6, 6, 6);
                g2.drawLine(x + 9, y + 4, x + 9, y + 14);
                g2.drawLine(x + 4, y + 9, x + 14, y + 9);
            } else if (iconType.equals(TYPE_RECEIVE_TASK)) {
                g2.setColor(new Color(0, 122, 255));
                g2.drawRoundRect(x + 1, y + 3, 16, 12, 4, 4);
                g2.drawRect(x + 4, y + 6, 10, 6);
                g2.drawLine(x + 4, y + 6, x + 9, y + 9);
                g2.drawLine(x + 14, y + 6, x + 9, y + 9);
            } else if (iconType.equals(TYPE_SEND_TASK)) {
                g2.setColor(new Color(0, 122, 255));
                g2.drawRoundRect(x + 1, y + 3, 16, 12, 4, 4);
                g2.fillRect(x + 4, y + 6, 10, 6);
                g2.setColor(Color.WHITE);
                g2.drawLine(x + 4, y + 6, x + 9, y + 9);
                g2.drawLine(x + 14, y + 6, x + 9, y + 9);
            } else if (iconType.equals(TYPE_SCRIPT_TASK)) {
                g2.setColor(new Color(0, 122, 255));
                g2.drawRoundRect(x + 1, y + 3, 16, 12, 4, 4);
                g2.drawLine(x + 4, y + 6, x + 13, y + 6);
                g2.drawLine(x + 4, y + 9, x + 11, y + 9);
                g2.drawLine(x + 4, y + 12, x + 13, y + 12);
            } else if (iconType.equals(TYPE_BUSINESS_RULE_TASK)) {
                g2.setColor(new Color(0, 122, 255));
                g2.drawRoundRect(x + 1, y + 3, 16, 12, 4, 4);
                g2.drawRect(x + 4, y + 5, 10, 8);
                g2.drawLine(x + 4, y + 9, x + 14, y + 9);
                g2.drawLine(x + 9, y + 5, x + 9, y + 13);
            } else if (iconType.equals(TYPE_GATEWAY)) {
                g2.setColor(new Color(138, 109, 59));
                int[] xp = {x + 9, x + 17, x + 9, x + 1};
                int[] yp = {y + 1, y + 9, y + 17, y + 9};
                g2.drawPolygon(xp, yp, 4);
                g2.setFont(new Font("Arial", Font.BOLD, 10));
                g2.drawString("X", x + 6, y + 12);
            } else if (iconType.equals(TYPE_PARALLEL_GATEWAY)) {
                g2.setColor(new Color(138, 109, 59));
                int[] xp = {x + 9, x + 17, x + 9, x + 1};
                int[] yp = {y + 1, y + 9, y + 17, y + 9};
                g2.drawPolygon(xp, yp, 4);
                g2.drawLine(x + 9, y + 5, x + 9, y + 13);
                g2.drawLine(x + 5, y + 9, x + 13, y + 9);
            } else if (iconType.equals(TYPE_EVENT_GATEWAY)) {
                g2.setColor(new Color(138, 109, 59));
                int[] xp = {x + 9, x + 17, x + 9, x + 1};
                int[] yp = {y + 1, y + 9, y + 17, y + 9};
                g2.drawPolygon(xp, yp, 4);
                g2.drawOval(x + 6, y + 6, 6, 6);
            } else if (iconType.equals("CONNECT")) {
                g2.setStroke(new BasicStroke(1.8f));
                g2.drawLine(x + 2, y + 14, x + 14, y + 2);
                g2.drawLine(x + 14, y + 2, x + 8, y + 2);
                g2.drawLine(x + 14, y + 2, x + 14, y + 8);
            } else if (iconType.equals("LAYOUT")) {
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRect(x + 2, y + 7, 4, 4);
                g2.drawRect(x + 12, y + 2, 4, 4);
                g2.drawRect(x + 12, y + 12, 4, 4);
                g2.drawLine(x + 6, y + 9, x + 9, y + 9);
                g2.drawLine(x + 9, y + 4, x + 12, y + 4);
                g2.drawLine(x + 9, y + 14, x + 12, y + 14);
                g2.drawLine(x + 9, y + 4, x + 9, y + 14);
            }

            g2.dispose();
        }

        @Override
        public int getIconWidth() { return width; }
        @Override
        public int getIconHeight() { return height; }
    }

    // ==========================================
    // 渲染面板 (CanvasPanel)
    // ==========================================

    private class CanvasPanel extends JPanel {

        CanvasPanel() {
            setPreferredSize(new Dimension(1500, 1000));
            setFocusable(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 绘制网格
            drawGrid(g2);

            // 绘制所有连线
            drawAllEdges(g2);

            // 绘制框选虚线范围矩形
            if (currentMode == Mode.SELECT_BOX && selectionRect != null) {
                g2.setColor(new Color(0, 122, 255, 30));
                g2.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
                g2.setColor(new Color(0, 122, 255, 180));
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4f}, 0.0f));
                g2.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
            }

            // 绘制连接临时虚线与吸附磁感高亮
            if (isConnectingViaHandle && connectSourceNode != null) {
                g2.setColor(UIManager.getColor("Component.warningBorderColor"));
                if (g2.getColor() == null) g2.setColor(Color.ORANGE);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5f}, 0.0f));
                Point p1 = connectSourceNode.getPortPoint(connectSourcePortIndex);
                g2.drawLine(p1.x, p1.y, currentMousePoint.x, currentMousePoint.y);

                if (tempTargetNode != null && tempTargetPortIndex != -1) {
                    Point mPt = tempTargetNode.getPortPoint(tempTargetPortIndex);
                    g2.setColor(Color.RED);
                    g2.setStroke(new BasicStroke(2.0f));
                    g2.drawOval(mPt.x - 5, mPt.y - 5, 10, 10);
                }
            }

            // 绘制所有节点
            drawAllNodes(g2);

            // 绘制选中节点右侧的快捷连线手柄
            if (currentMode == Mode.SELECT && selectedNode != null) {
                drawConnectionHandle(g2, selectedNode);
            }

            // 在连接模式或者正在连线时，绘制所有连接点锚点
            if (currentMode == Mode.CONNECT || isConnectingViaHandle) {
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
            for (BpmnNode node : nodes) {
                boolean isSelected = selectedNodes.contains(node);
                Color borderTheme = isSelected ? UIManager.getColor("Component.focusColor") : UIManager.getColor("Component.borderColor");
                if (borderTheme == null) borderTheme = isSelected ? Color.BLUE : Color.GRAY;

                g2.setStroke(new BasicStroke(isSelected ? 2.5f : 1.5f));

                if (node.type.equals(TYPE_START)) {
                    g2.setColor(new Color(223, 240, 216, 220));
                    g2.fillOval(node.x, node.y, node.w, node.h);
                    g2.setColor(new Color(70, 136, 71));
                    if (isSelected) g2.setColor(borderTheme);
                    g2.drawOval(node.x, node.y, node.w, node.h);

                    drawNodeName(g2, node);
                } else if (node.type.equals(TYPE_END)) {
                    g2.setColor(new Color(242, 222, 222, 220));
                    g2.fillOval(node.x, node.y, node.w, node.h);
                    g2.setColor(new Color(169, 68, 66));
                    if (isSelected) g2.setColor(borderTheme);
                    g2.setStroke(new BasicStroke(isSelected ? 3.5f : 3.0f));
                    g2.drawOval(node.x, node.y, node.w, node.h);

                    drawNodeName(g2, node);
                } else if (node.type.equals(TYPE_GATEWAY) || node.type.equals(TYPE_PARALLEL_GATEWAY) || node.type.equals(TYPE_EVENT_GATEWAY)) {
                    int[] xPoints = {node.x + node.w / 2, node.x + node.w, node.x + node.w / 2, node.x};
                    int[] yPoints = {node.y, node.y + node.h / 2, node.y + node.h, node.y + node.h / 2};
                    g2.setColor(new Color(252, 248, 227, 220));
                    g2.fillPolygon(xPoints, yPoints, 4);
                    g2.setColor(new Color(138, 109, 59));
                    if (isSelected) g2.setColor(borderTheme);
                    g2.drawPolygon(xPoints, yPoints, 4);

                    if (node.type.equals(TYPE_GATEWAY)) {
                        g2.setFont(new Font("Arial", Font.BOLD, 18));
                        g2.drawString("X", node.x + node.w / 2 - 6, node.y + node.h / 2 + 7);
                    } else if (node.type.equals(TYPE_PARALLEL_GATEWAY)) {
                        g2.setStroke(new BasicStroke(3.0f));
                        g2.drawLine(node.x + node.w / 2, node.y + 12, node.x + node.w / 2, node.y + node.h - 12);
                        g2.drawLine(node.x + 12, node.y + node.h / 2, node.x + node.w - 12, node.y + node.h / 2);
                    } else {
                        g2.setStroke(new BasicStroke(1.0f));
                        g2.drawOval(node.x + 15, node.y + 15, 20, 20);
                        g2.drawOval(node.x + 18, node.y + 18, 14, 14);
                    }

                    drawNodeName(g2, node);
                } else {
                    g2.setColor(UIManager.getColor("Panel.background"));
                    if (g2.getColor() == null) g2.setColor(new Color(245, 245, 245));
                    g2.fillRoundRect(node.x, node.y, node.w, node.h, 10, 10);
                    
                    g2.setColor(borderTheme);
                    g2.drawRoundRect(node.x, node.y, node.w, node.h, 10, 10);

                    g2.setColor(UIManager.getColor("Label.foreground"));
                    if (node.type.equals(TYPE_USER_TASK)) {
                        g2.drawOval(node.x + 10, node.y + 10, 8, 8);
                        g2.drawArc(node.x + 6, node.y + 18, 16, 7, 0, 180);
                    } else if (node.type.equals(TYPE_SERVICE_TASK)) {
                        g2.drawOval(node.x + 10, node.y + 10, 10, 10);
                        g2.drawOval(node.x + 13, node.y + 13, 4, 4);
                        g2.drawLine(node.x + 15, node.y + 7, node.x + 15, node.y + 23);
                        g2.drawLine(node.x + 7, node.y + 15, node.x + 23, node.y + 15);
                    } else if (node.type.equals(TYPE_RECEIVE_TASK)) {
                        g2.drawRect(node.x + 8, node.y + 8, 14, 10);
                        g2.drawLine(node.x + 8, node.y + 8, node.x + 15, node.y + 13);
                        g2.drawLine(node.x + 22, node.y + 8, node.x + 15, node.y + 13);
                    } else if (node.type.equals(TYPE_SEND_TASK)) {
                        g2.fillRect(node.x + 8, node.y + 8, 14, 10);
                        g2.setColor(UIManager.getColor("Panel.background"));
                        g2.drawLine(node.x + 8, node.y + 8, node.x + 15, node.y + 13);
                        g2.drawLine(node.x + 22, node.y + 8, node.x + 15, node.y + 13);
                    } else if (node.type.equals(TYPE_SCRIPT_TASK)) {
                        g2.drawLine(node.x + 8, node.y + 10, node.x + 22, node.y + 10);
                        g2.drawLine(node.x + 8, node.y + 14, node.x + 18, node.y + 14);
                        g2.drawLine(node.x + 8, node.y + 18, node.x + 22, node.y + 18);
                    } else if (node.type.equals(TYPE_BUSINESS_RULE_TASK)) {
                        g2.drawRect(node.x + 8, node.y + 8, 14, 11);
                        g2.drawLine(node.x + 8, node.y + 13, node.x + 22, node.y + 13);
                        g2.drawLine(node.x + 15, node.y + 8, node.x + 15, node.y + 19);
                    }

                    g2.setFont(UIUtils.plainFont().deriveFont(12f));
                    g2.setColor(UIManager.getColor("Label.foreground"));
                    FontMetrics fm = g2.getFontMetrics();
                    int textY = node.y + 35;
                    String[] lines = wrapText(node.name, node.w - 15, fm);
                    for (String line : lines) {
                        int textX = node.x + (node.w - fm.stringWidth(line)) / 2;
                        g2.drawString(line, textX, textY);
                        textY += fm.getHeight();
                    }
                }
            }
        }

        private void drawNodeName(Graphics2D g2, BpmnNode node) {
            g2.setFont(UIUtils.plainFont().deriveFont(12f));
            g2.setColor(UIManager.getColor("Label.foreground"));
            FontMetrics fm = g2.getFontMetrics();
            int textX = node.x + (node.w - fm.stringWidth(node.name)) / 2;
            int textY = node.y + node.h + 16;
            g2.drawString(node.name, textX, textY);
        }

        private void drawConnectionHandle(Graphics2D g2, BpmnNode node) {
            int hx = node.x + node.w + 12;
            int hy = node.y + node.h / 2 - 8;

            g2.setColor(UIManager.getColor("Component.focusColor"));
            if (g2.getColor() == null) g2.setColor(Color.BLUE);
            g2.setStroke(new BasicStroke(1.5f));
            g2.drawOval(hx, hy, 16, 16);
            g2.drawLine(hx + 4, hy + 8, hx + 12, hy + 8);
            g2.drawLine(hx + 9, hy + 5, hx + 12, hy + 8);
            g2.drawLine(hx + 9, hy + 11, hx + 12, hy + 8);
        }

        private void drawAllPorts(Graphics2D g2) {
            g2.setColor(new Color(0, 122, 255, 180));
            for (BpmnNode node : nodes) {
                for (int i = 0; i < 4; i++) {
                    Point pt = node.getPortPoint(i);
                    g2.fillOval(pt.x - 3, pt.y - 3, 6, 6);
                    g2.setColor(Color.WHITE);
                    g2.drawOval(pt.x - 3, pt.y - 3, 6, 6);
                    g2.setColor(new Color(0, 122, 255, 180));
                }
            }
        }

        private void drawAllEdges(Graphics2D g2) {
            for (BpmnEdge edge : edges) {
                boolean isSelected = (edge == selectedEdge);
                g2.setColor(isSelected ? UIManager.getColor("Component.focusColor") : UIManager.getColor("Label.foreground"));
                if (g2.getColor() == null) g2.setColor(isSelected ? Color.BLUE : Color.BLACK);

                g2.setStroke(new BasicStroke(isSelected ? 2.5f : 1.5f));

                Point p1 = edge.getIntersectionPointSource();
                Point p2 = edge.getIntersectionPointTarget();

                g2.drawLine(p1.x, p1.y, p2.x, p2.y);
                drawArrow(g2, p1, p2);

                String text = edge.name;
                if (edge.condition != null && !edge.condition.isEmpty()) {
                    text = (text.isEmpty() ? "" : text + " ") + "[" + edge.condition + "]";
                }
                if (!text.isEmpty()) {
                    g2.setFont(UIUtils.plainFont().deriveFont(11f));
                    FontMetrics fm = g2.getFontMetrics();
                    int midX = (p1.x + p2.x) / 2;
                    int midY = (p1.y + p2.y) / 2 - 5;
                    g2.drawString(text, midX - fm.stringWidth(text) / 2, midY);
                }
            }
        }

        private void drawArrow(Graphics2D g2, Point p1, Point p2) {
            double dx = p2.x - p1.x;
            double dy = p2.y - p1.y;
            double angle = Math.atan2(dy, dx);
            int len = 8;
            double arrowAngle = Math.PI / 6;

            int x1 = (int) (p2.x - len * Math.cos(angle - arrowAngle));
            int y1 = (int) (p2.y - len * Math.sin(angle - arrowAngle));
            int x2 = (int) (p2.x - len * Math.cos(angle + arrowAngle));
            int y2 = (int) (p2.y - len * Math.sin(angle + arrowAngle));

            int[] xPoints = {p2.x, x1, x2};
            int[] yPoints = {p2.y, y1, y2};

            g2.fillPolygon(xPoints, yPoints, 3);
        }

        private String[] wrapText(String text, int maxWidth, FontMetrics fm) {
            List<String> list = new ArrayList<>();
            StringBuilder currentLine = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                currentLine.append(c);
                if (fm.stringWidth(currentLine.toString()) > maxWidth) {
                    currentLine.setLength(currentLine.length() - 1);
                    list.add(currentLine.toString());
                    currentLine.setLength(0);
                    currentLine.append(c);
                }
            }
            if (currentLine.length() > 0) {
                list.add(currentLine.toString());
            }
            return list.toArray(new String[0]);
        }
    }
}
