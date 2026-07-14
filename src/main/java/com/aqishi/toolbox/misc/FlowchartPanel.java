package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 流程图绘制设计器 (Flowchart Designer)：支持拖拽移动、连线吸附、框选、右键菜单、导出高清 PNG 和一键自动拓扑布局。
 */
public class FlowchartPanel extends ToolPanel {

    // 节点类型定义
    public static final String TYPE_START_END = "start_end";   // 起止框 (椭圆)
    public static final String TYPE_PROCESS = "process";       // 步骤框 (矩形)
    public static final String TYPE_DECISION = "decision";     // 判定框 (菱形)
    public static final String TYPE_DATA = "data";             // 输入/输出 (平行四边形)
    public static final String TYPE_DATABASE = "database";     // 数据库 (圆柱)
    public static final String TYPE_CLOUD = "cloud";           // 云服务 (云朵)

    // 工作模式
    private enum Mode {
        SELECT, CONNECT, ADD_START_END, ADD_PROCESS, ADD_DECISION, ADD_DATA, ADD_DATABASE, ADD_CLOUD
    }

    private Mode currentMode = Mode.SELECT;

    // 数据模型
    private final List<FlowNode> nodes = new ArrayList<>();
    private final List<FlowEdge> edges = new ArrayList<>();

    // 选中的元素
    private final List<FlowNode> selectedNodes = new ArrayList<>();
    private FlowNode selectedNode = null;
    private FlowEdge selectedEdge = null;

    // 交互辅助变量
    private FlowNode draggedNode = null;
    private FlowNode connectSourceNode = null;
    private int connectSourcePortIndex = -1; // 0:上, 1:右, 2:下, 3:左
    private Point currentMousePoint = new Point();
    private boolean isConnecting = false;

    // 磁吸临时目标
    private FlowNode tempTargetNode = null;
    private int tempTargetPortIndex = -1;

    // 框选变量
    private Point selectionStart = null;
    private Rectangle selectionRect = null;

    // UI 组件
    private CanvasPanel canvasPanel;
    private JScrollPane scrollPane;
    private JTextField nameField;
    private JTextField idField;
    private JTextField edgeLabelField;
    private JPanel propPanel;

    private boolean updatingProperties = false;

    public FlowchartPanel() {
        super("chart", "flowchart",
                "Flowchart", "流程图", "画图", "设计器", "ProcessOn", "Draw.io", "Diagram");
        initDefaultDiagram();
    }

    private void initDefaultDiagram() {
        FlowNode start = new FlowNode(TYPE_START_END, "start", "开始", 140, 60);
        FlowNode process1 = new FlowNode(TYPE_PROCESS, "process1", "数据初始化", 130, 160);
        FlowNode decision = new FlowNode(TYPE_DECISION, "decision1", "是否合格?", 150, 270);
        FlowNode db = new FlowNode(TYPE_DATABASE, "db1", "保存至数据库", 320, 285);
        FlowNode end = new FlowNode(TYPE_START_END, "end", "结束", 140, 410);

        nodes.add(start);
        nodes.add(process1);
        nodes.add(decision);
        nodes.add(db);
        nodes.add(end);

        edges.add(new FlowEdge("flow1", "", start, process1, 2, 0));
        edges.add(new FlowEdge("flow2", "", process1, decision, 2, 0));
        edges.add(new FlowEdge("flow3", "是", decision, db, 1, 3));
        edges.add(new FlowEdge("flow4", "否", decision, end, 2, 0));
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(5, 5));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 1. 顶部工具栏
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        ButtonGroup btnGroup = new ButtonGroup();

        // 快捷添加工具栏单选按钮的方法
        class ToolBarHelper {
            JToggleButton addToggle(String tooltip, Mode mode, String iconText) {
                JToggleButton btn = new JToggleButton(iconText);
                btn.setToolTipText(tooltip);
                btn.setFont(UIUtils.plainFont());
                btn.addActionListener(e -> setMode(mode));
                btnGroup.add(btn);
                toolBar.add(btn);
                return btn;
            }
        }
        ToolBarHelper helper = new ToolBarHelper();

        JToggleButton selectBtn = helper.addToggle("选择/移动 (支持框选)", Mode.SELECT, " 🖲️ 选择");
        selectBtn.setSelected(true);
        toolBar.addSeparator();

        helper.addToggle("起止框 (椭圆)", Mode.ADD_START_END, " 🟢 起止");
        helper.addToggle("步骤框 (矩形)", Mode.ADD_PROCESS, " 🟦 步骤");
        helper.addToggle("判定框 (菱形)", Mode.ADD_DECISION, " 🔶 判定");
        helper.addToggle("数据框 (平行四边形)", Mode.ADD_DATA, " ▱ 数据");
        helper.addToggle("数据库 (圆柱)", Mode.ADD_DATABASE, " 🛢️ 数据库");
        helper.addToggle("外部系统 (云朵)", Mode.ADD_CLOUD, " ☁️ 外部");
        
        toolBar.addSeparator();
        JToggleButton connectBtn = helper.addToggle("建立吸附连线", Mode.CONNECT, " 🔗 连线");
        
        toolBar.addSeparator();
        
        JButton layoutBtn = new JButton(" 📐 自动排版");
        layoutBtn.setFont(UIUtils.plainFont());
        layoutBtn.setToolTipText("一键使用拓扑排版算法整齐排列图表");
        layoutBtn.addActionListener(e -> autoLayout());
        toolBar.add(layoutBtn);

        JButton clearBtn = new JButton(" 🗑️ 清空");
        clearBtn.setFont(UIUtils.plainFont());
        clearBtn.addActionListener(e -> clearCanvas());
        toolBar.add(clearBtn);

        JButton exportBtn = new JButton(" 💾 导出图片");
        exportBtn.setFont(UIUtils.plainFont());
        exportBtn.addActionListener(e -> exportImage());
        toolBar.add(exportBtn);

        root.add(toolBar, BorderLayout.NORTH);

        // 2. 右侧属性编辑栏
        propPanel = new JPanel(new GridBagLayout());
        propPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, UIManager.getColor("Component.borderColor")),
                "属性编辑", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION,
                UIUtils.titleFont(), UIManager.getColor("Component.accentColor")));
        propPanel.setPreferredSize(new Dimension(240, 0));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.weightx = 1.0;
        gbc.gridx = 0;

        // 节点名/文本
        gbc.gridy = 0;
        propPanel.add(new JLabel("元素名称 (Name):"), gbc);
        gbc.gridy = 1;
        nameField = new JTextField();
        nameField.setFont(UIUtils.plainFont());
        propPanel.add(nameField, gbc);

        // 元素ID
        gbc.gridy = 2;
        propPanel.add(new JLabel("元素 ID (唯一标识):"), gbc);
        gbc.gridy = 3;
        idField = new JTextField();
        idField.setFont(UIUtils.plainFont());
        idField.setEditable(false);
        propPanel.add(idField, gbc);

        // 连线文本
        gbc.gridy = 4;
        propPanel.add(new JLabel("连线条件/标签 (Label):"), gbc);
        gbc.gridy = 5;
        edgeLabelField = new JTextField();
        edgeLabelField.setFont(UIUtils.plainFont());
        propPanel.add(edgeLabelField, gbc);

        gbc.gridy = 6;
        gbc.weighty = 1.0; // 填满垂直空间
        propPanel.add(new JPanel(), gbc);

        // 属性监听事件
        nameField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (updatingProperties) return;
            if (selectedNode != null) {
                selectedNode.name = nameField.getText().trim();
                canvasPanel.repaint();
            }
        }));

        edgeLabelField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (updatingProperties) return;
            if (selectedEdge != null) {
                selectedEdge.label = edgeLabelField.getText().trim();
                canvasPanel.repaint();
            }
        }));

        root.add(propPanel, BorderLayout.EAST);

        // 3. 中间画布区
        canvasPanel = new CanvasPanel();
        scrollPane = new JScrollPane(canvasPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        root.add(scrollPane, BorderLayout.CENTER);

        updatePropertyPanel();

        return root;
    }

    private void setMode(Mode mode) {
        this.currentMode = mode;
        clearSelection();
        canvasPanel.repaint();
    }

    private void clearSelection() {
        selectedNodes.clear();
        selectedNode = null;
        selectedEdge = null;
        updatePropertyPanel();
    }

    private void updatePropertyPanel() {
        updatingProperties = true;
        if (selectedNode != null) {
            nameField.setEditable(true);
            nameField.setText(selectedNode.name);
            idField.setText(selectedNode.id);
            edgeLabelField.setEditable(false);
            edgeLabelField.setText("");
        } else if (selectedEdge != null) {
            nameField.setEditable(false);
            nameField.setText("");
            idField.setText(selectedEdge.id);
            edgeLabelField.setEditable(true);
            edgeLabelField.setText(selectedEdge.label);
        } else {
            nameField.setEditable(false);
            nameField.setText("");
            idField.setText("");
            edgeLabelField.setEditable(false);
            edgeLabelField.setText("");
        }
        updatingProperties = false;
    }

    private void clearCanvas() {
        if (JOptionPane.showConfirmDialog(getView(), "确定要清空画布吗?", "提示", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            nodes.clear();
            edges.clear();
            clearSelection();
            canvasPanel.repaint();
        }
    }

    private void exportImage() {
        if (nodes.isEmpty()) {
            UIUtils.error(getView(), "画布为空，无法导出图片！");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("flowchart.png"));
        chooser.setFileFilter(new FileNameExtensionFilter("PNG图片 (*.png)", "png"));
        if (chooser.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File dest = chooser.getSelectedFile();
            if (!dest.getName().toLowerCase().endsWith(".png")) {
                dest = new File(dest.getParentFile(), dest.getName() + ".png");
            }

            // 计算包含所有元素的最小包围矩形
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = 0, maxY = 0;
            for (FlowNode n : nodes) {
                if (n.x < minX) minX = n.x;
                if (n.y < minY) minY = n.y;
                if (n.x + n.w > maxX) maxX = n.x + n.w;
                if (n.y + n.h > maxY) maxY = n.y + n.h;
            }

            int padding = 40;
            minX = Math.max(0, minX - padding);
            minY = Math.max(0, minY - padding);
            int width = (maxX - minX) + padding * 2;
            int height = (maxY - minY) + padding * 2;

            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            // 白色底色
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, width, height);

            // 移动原点进行包围框裁剪绘制
            g2.translate(-minX, -minY);
            canvasPanel.drawAll(g2);
            g2.dispose();

            try {
                ImageIO.write(img, "png", dest);
                UIUtils.info(getView(), "成功导出高清图片！");
            } catch (IOException e) {
                UIUtils.error(getView(), "导出失败:\n" + e.getMessage());
            }
        }
    }

    /**
     * 拓扑分层排版算法实现：一键让图表整齐排版
     */
    private void autoLayout() {
        if (nodes.isEmpty()) return;

        // 1. 计算入度
        int[] inDegree = new int[nodes.size()];
        for (FlowEdge edge : edges) {
            int targetIdx = nodes.indexOf(edge.target);
            if (targetIdx != -1) {
                inDegree[targetIdx]++;
            }
        }

        // 2. 拓扑分层
        List<List<FlowNode>> layers = new ArrayList<>();
        List<FlowNode> currentLayer = new ArrayList<>();
        
        for (int i = 0; i < nodes.size(); i++) {
            if (inDegree[i] == 0) {
                currentLayer.add(nodes.get(i));
            }
        }

        if (currentLayer.isEmpty()) {
            // 环路或无源头图，默认直接使用首个
            currentLayer.add(nodes.get(0));
        }

        boolean[] visited = new boolean[nodes.size()];
        for (FlowNode n : currentLayer) {
            visited[nodes.indexOf(n)] = true;
        }

        layers.add(currentLayer);

        while (true) {
            List<FlowNode> nextLayer = new ArrayList<>();
            for (FlowNode u : currentLayer) {
                for (FlowEdge edge : edges) {
                    if (edge.source == u) {
                        int vIdx = nodes.indexOf(edge.target);
                        if (vIdx != -1 && !visited[vIdx]) {
                            visited[vIdx] = true;
                            nextLayer.add(edge.target);
                        }
                    }
                }
            }
            if (nextLayer.isEmpty()) break;
            layers.add(nextLayer);
            currentLayer = nextLayer;
        }

        // 补漏（防止悬空孤立节点被丢弃）
        List<FlowNode> orphanLayer = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (!visited[i]) {
                orphanLayer.add(nodes.get(i));
            }
        }
        if (!orphanLayer.isEmpty()) {
            layers.add(orphanLayer);
        }

        // 3. 计算坐标 (从上至下排列)
        int startY = 60;
        int layerGapY = 100;
        int nodeGapX = 140;

        for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
            List<FlowNode> layerNodes = layers.get(layerIdx);
            int layerWidth = (layerNodes.size() - 1) * nodeGapX;
            int startX = Math.max(100, 300 - layerWidth / 2);

            for (int i = 0; i < layerNodes.size(); i++) {
                FlowNode n = layerNodes.get(i);
                n.x = startX + i * nodeGapX - n.w / 2;
                n.y = startY + layerIdx * layerGapY;
            }
        }

        adjustCanvasSize();
        canvasPanel.repaint();
    }

    private void adjustCanvasSize() {
        int maxX = 1000;
        int maxY = 800;
        for (FlowNode n : nodes) {
            if (n.x + n.w + 150 > maxX) maxX = n.x + n.w + 150;
            if (n.y + n.h + 150 > maxY) maxY = n.y + n.h + 150;
        }
        canvasPanel.setPreferredSize(new Dimension(maxX, maxY));
        canvasPanel.revalidate();
    }

    // ==========================================
    // 画布面板类 CanvasPanel
    // ==========================================
    private class CanvasPanel extends JPanel {

        CanvasPanel() {
            setPreferredSize(new Dimension(1000, 800));
            setBackground(UIManager.getColor("Panel.background"));
            setFocusable(true);

            // 监听鼠标交互
            MouseAdapter listener = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    currentMousePoint = e.getPoint();

                    // 右键菜单
                    if (SwingUtilities.isRightMouseButton(e)) {
                        showContextMenu(e);
                        return;
                    }

                    // 1. 添加节点模式
                    if (currentMode != Mode.SELECT && currentMode != Mode.CONNECT) {
                        addNewNode(currentMode, e.getPoint());
                        return;
                    }

                    // 2. 连接连线模式
                    if (currentMode == Mode.CONNECT) {
                        initiateConnection(e.getPoint());
                        return;
                    }

                    // 3. 选择/移动模式
                    if (currentMode == Mode.SELECT) {
                        // 判定是否点中了快捷连线小手柄
                        if (selectedNode != null && isPointInConnectionHandle(selectedNode, e.getPoint())) {
                            isConnecting = true;
                            connectSourceNode = selectedNode;
                            connectSourcePortIndex = 1; // 右侧默认
                            return;
                        }

                        // 判定是否点中了节点
                        FlowNode clickedNode = getNodeAtPoint(e.getPoint());
                        if (clickedNode != null) {
                            if (e.isControlDown()) {
                                if (selectedNodes.contains(clickedNode)) {
                                    selectedNodes.remove(clickedNode);
                                } else {
                                    selectedNodes.add(clickedNode);
                                }
                            } else {
                                if (!selectedNodes.contains(clickedNode)) {
                                    selectedNodes.clear();
                                    selectedNodes.add(clickedNode);
                                }
                            }
                            selectedNode = clickedNode;
                            selectedEdge = null;
                            draggedNode = clickedNode;
                            updatePropertyPanel();
                            repaint();
                            return;
                        }

                        // 判定是否点中连线
                        FlowEdge clickedEdge = getEdgeAtPoint(e.getPoint());
                        if (clickedEdge != null) {
                            clearSelection();
                            selectedEdge = clickedEdge;
                            updatePropertyPanel();
                            repaint();
                            return;
                        }

                        // 空白处：开始框选
                        clearSelection();
                        selectionStart = e.getPoint();
                        selectionRect = new Rectangle(selectionStart);
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    // 完成连线
                    if (isConnecting && connectSourceNode != null) {
                        completeConnection(e.getPoint());
                    }

                    isConnecting = false;
                    connectSourceNode = null;
                    draggedNode = null;
                    selectionStart = null;
                    selectionRect = null;
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    currentMousePoint = e.getPoint();

                    // 连线建立拖拽
                    if (isConnecting && connectSourceNode != null) {
                        tempTargetPortIndex = -1;
                        tempTargetNode = null;
                        
                        // 寻找磁吸目标
                        FlowNode target = getNodeAtPoint(e.getPoint());
                        if (target != null && target != connectSourceNode) {
                            tempTargetNode = target;
                            tempTargetPortIndex = getClosestPortIndex(target, e.getPoint());
                        }
                        repaint();
                        return;
                    }

                    // 移动节点拖拽
                    if (draggedNode != null) {
                        int dx = e.getX() - currentMousePoint.x;
                        int dy = e.getY() - currentMousePoint.y;
                        for (FlowNode n : selectedNodes) {
                            n.x += dx;
                            n.y += dy;
                        }
                        currentMousePoint = e.getPoint();
                        adjustCanvasSize();
                        repaint();
                        return;
                    }

                    // 框选拖拽
                    if (selectionStart != null) {
                        int x = Math.min(selectionStart.x, e.getX());
                        int y = Math.min(selectionStart.y, e.getY());
                        int w = Math.abs(selectionStart.x - e.getX());
                        int h = Math.abs(selectionStart.y - e.getY());
                        selectionRect.setBounds(x, y, w, h);

                        // 批量选中框内的节点
                        selectedNodes.clear();
                        for (FlowNode n : nodes) {
                            if (selectionRect.intersects(new Rectangle(n.x, n.y, n.w, n.h))) {
                                selectedNodes.add(n);
                            }
                        }
                        repaint();
                    }
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    // 平时滑过连线手柄时显示手型
                    if (currentMode == Mode.SELECT && selectedNode != null) {
                        if (isPointInConnectionHandle(selectedNode, e.getPoint())) {
                            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                            return;
                        }
                    }
                    setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
                }
            };

            addMouseListener(listener);
            addMouseMotionListener(listener);

            // 键盘删除支持
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                        deleteSelected();
                    }
                }
            });
        }

        private void showContextMenu(MouseEvent e) {
            JPopupMenu menu = new JPopupMenu();
            
            FlowNode nodeUnder = getNodeAtPoint(e.getPoint());
            FlowEdge edgeUnder = getEdgeAtPoint(e.getPoint());

            if (nodeUnder != null) {
                selectedNode = nodeUnder;
                selectedEdge = null;
                updatePropertyPanel();
                repaint();
                
                JMenuItem delNodeItem = new JMenuItem("删除该节点");
                delNodeItem.addActionListener(evt -> deleteNode(nodeUnder));
                menu.add(delNodeItem);
                
                JMenuItem renameItem = new JMenuItem("编辑节点名称");
                renameItem.addActionListener(evt -> editNodeName(nodeUnder));
                menu.add(renameItem);
            } else if (edgeUnder != null) {
                selectedEdge = edgeUnder;
                selectedNode = null;
                updatePropertyPanel();
                repaint();
                
                JMenuItem delEdgeItem = new JMenuItem("删除该连线");
                delEdgeItem.addActionListener(evt -> deleteEdge(edgeUnder));
                menu.add(delEdgeItem);
            } else {
                JMenuItem layoutItem = new JMenuItem("📐 自动整理排版");
                layoutItem.addActionListener(evt -> autoLayout());
                menu.add(layoutItem);
            }

            if (menu.getComponentCount() > 0) {
                menu.show(canvasPanel, e.getX(), e.getY());
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            drawGrid(g2);
            drawAll(g2);

            // 绘制框选虚线
            if (selectionRect != null) {
                g2.setColor(new Color(64, 158, 255, 30));
                g2.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
                g2.setColor(new Color(64, 158, 255, 180));
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4f}, 0.0f));
                g2.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
            }

            // 绘制连线临时虚线与吸附磁感圆
            if (isConnecting && connectSourceNode != null) {
                g2.setColor(UIManager.getColor("Component.warningBorderColor"));
                if (g2.getColor() == null) g2.setColor(Color.ORANGE);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5f}, 0.0f));
                
                Point p1 = connectSourceNode.getPortPoint(connectSourcePortIndex == -1 ? 1 : connectSourcePortIndex);
                g2.drawLine(p1.x, p1.y, currentMousePoint.x, currentMousePoint.y);

                if (tempTargetNode != null && tempTargetPortIndex != -1) {
                    Point mPt = tempTargetNode.getPortPoint(tempTargetPortIndex);
                    g2.setColor(Color.RED);
                    g2.setStroke(new BasicStroke(2.0f));
                    g2.drawOval(mPt.x - 5, mPt.y - 5, 10, 10);
                }
            }

            // 绘制选中节点快捷连线手柄
            if (currentMode == Mode.SELECT && selectedNode != null && selectedNodes.size() <= 1) {
                drawConnectionHandle(g2, selectedNode);
            }

            // 连线建立时绘制各个节点的磁吸端口
            if (currentMode == Mode.CONNECT || isConnecting) {
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
            for (FlowNode node : nodes) {
                boolean isSelected = selectedNodes.contains(node);
                Color borderTheme = isSelected ? UIManager.getColor("Component.focusColor") : UIManager.getColor("Component.borderColor");
                if (borderTheme == null) borderTheme = isSelected ? Color.BLUE : Color.GRAY;

                g2.setStroke(new BasicStroke(isSelected ? 2.5f : 1.5f));

                if (node.type.equals(TYPE_START_END)) {
                    // 起止框 (椭圆)
                    g2.setColor(new Color(223, 240, 216, 220));
                    g2.fillOval(node.x, node.y, node.w, node.h);
                    g2.setColor(new Color(70, 136, 71));
                    if (isSelected) g2.setColor(borderTheme);
                    g2.drawOval(node.x, node.y, node.w, node.h);

                } else if (node.type.equals(TYPE_DECISION)) {
                    // 判定框 (菱形)
                    int[] xPoints = {node.x + node.w / 2, node.x + node.w, node.x + node.w / 2, node.x};
                    int[] yPoints = {node.y, node.y + node.h / 2, node.y + node.h, node.y + node.h / 2};
                    g2.setColor(new Color(252, 248, 227, 220));
                    g2.fillPolygon(xPoints, yPoints, 4);
                    g2.setColor(new Color(138, 109, 59));
                    if (isSelected) g2.setColor(borderTheme);
                    g2.drawPolygon(xPoints, yPoints, 4);

                } else if (node.type.equals(TYPE_DATA)) {
                    // 数据框 (平行四边形)
                    int skew = 15;
                    int[] xPoints = {node.x + skew, node.x + node.w, node.x + node.w - skew, node.x};
                    int[] yPoints = {node.y, node.y, node.y + node.h, node.y + node.h};
                    g2.setColor(new Color(235, 230, 245, 220));
                    g2.fillPolygon(xPoints, yPoints, 4);
                    g2.setColor(new Color(117, 85, 175));
                    if (isSelected) g2.setColor(borderTheme);
                    g2.drawPolygon(xPoints, yPoints, 4);

                } else if (node.type.equals(TYPE_DATABASE)) {
                    // 数据库 (圆柱体)
                    int dy = 10;
                    g2.setColor(new Color(217, 237, 247, 220));
                    g2.fillRect(node.x, node.y + dy, node.w, node.h - dy * 2);
                    g2.fillOval(node.x, node.y, node.w, dy * 2);
                    g2.fillOval(node.x, node.y + node.h - dy * 2, node.w, dy * 2);

                    g2.setColor(new Color(58, 135, 173));
                    if (isSelected) g2.setColor(borderTheme);
                    
                    g2.drawOval(node.x, node.y, node.w, dy * 2);
                    g2.drawArc(node.x, node.y + node.h - dy * 2, node.w, dy * 2, 180, 180);
                    g2.drawLine(node.x, node.y + dy, node.x, node.y + node.h - dy);
                    g2.drawLine(node.x + node.w, node.y + dy, node.x + node.w, node.y + node.h - dy);

                } else if (node.type.equals(TYPE_CLOUD)) {
                    // 云朵 (云服务)
                    g2.setColor(new Color(240, 248, 255, 220));
                    int cx = node.x, cy = node.y, cw = node.w, ch = node.h;
                    g2.fillOval(cx + 10, cy + 12, 35, 35);
                    g2.fillOval(cx + 35, cy + 5, 45, 45);
                    g2.fillOval(cx + 65, cy + 15, 30, 30);
                    g2.fillRect(cx + 25, cy + 20, 50, 25);
                    
                    g2.setColor(new Color(70, 130, 180));
                    if (isSelected) g2.setColor(borderTheme);
                    g2.drawArc(cx + 10, cy + 12, 35, 35, 75, 180);
                    g2.drawArc(cx + 35, cy + 5, 45, 45, 10, 160);
                    g2.drawArc(cx + 65, cy + 15, 30, 30, -80, 170);
                    g2.drawLine(cx + 25, cy + 45, cx + 75, cy + 45);

                } else {
                    // 默认步骤框 (矩形)
                    g2.setColor(new Color(217, 237, 247, 220));
                    g2.fillRect(node.x, node.y, node.w, node.h);
                    g2.setColor(new Color(51, 122, 183));
                    if (isSelected) g2.setColor(borderTheme);
                    g2.drawRect(node.x, node.y, node.w, node.h);
                }

                // 绘制文本名称 (自动折行或居中展示)
                drawNodeName(g2, node);
            }
        }

        private void drawNodeName(Graphics2D g2, FlowNode node) {
            g2.setFont(UIUtils.plainFont());
            g2.setColor(UIManager.getColor("Label.foreground"));
            if (g2.getColor() == null) g2.setColor(Color.BLACK);
            
            FontMetrics fm = g2.getFontMetrics();
            String name = node.name == null ? "" : node.name;
            int textWidth = fm.stringWidth(name);

            // 当文字较宽时，自动折行绘制
            if (textWidth > node.w - 12 && name.length() > 4) {
                int mid = name.length() / 2;
                String part1 = name.substring(0, mid);
                String part2 = name.substring(mid);
                g2.drawString(part1, node.x + (node.w - fm.stringWidth(part1)) / 2, node.y + node.h / 2 - 2);
                g2.drawString(part2, node.x + (node.w - fm.stringWidth(part2)) / 2, node.y + node.h / 2 + 12);
            } else {
                g2.drawString(name, node.x + (node.w - textWidth) / 2, node.y + node.h / 2 + fm.getAscent() / 2 - 2);
            }
        }

        private void drawAllEdges(Graphics2D g2) {
            for (FlowEdge edge : edges) {
                boolean isSelected = (selectedEdge == edge);
                g2.setColor(isSelected ? UIManager.getColor("Component.focusColor") : UIManager.getColor("Label.foreground"));
                if (g2.getColor() == null) g2.setColor(isSelected ? Color.BLUE : Color.DARK_GRAY);

                g2.setStroke(new BasicStroke(isSelected ? 2.5f : 1.5f));

                Point p1 = edge.source.getPortPoint(edge.sourcePort);
                Point p2 = edge.target.getPortPoint(edge.targetPort);

                // Manhattan 直角弯折连线生成路径
                drawManhattanLine(g2, p1, p2, edge.sourcePort, edge.targetPort);

                // 绘制连线上的 Label 文本
                if (edge.label != null && !edge.label.trim().isEmpty()) {
                    drawEdgeLabel(g2, p1, p2, edge.label);
                }
            }
        }

        // Manhattan 路径及端点箭头绘制
        private void drawManhattanLine(Graphics2D g2, Point p1, Point p2, int port1, int port2) {
            List<Point> pts = new ArrayList<>();
            pts.add(p1);

            int dist = 24; // 离开端口的初始直行延伸长度
            Point startDir = getDirectionOffset(port1, dist);
            Point s1 = new Point(p1.x + startDir.x, p1.y + startDir.y);
            pts.add(s1);

            Point endDir = getDirectionOffset(port2, dist);
            Point s2 = new Point(p2.x + endDir.x, p2.y + endDir.y);

            // 根据相对坐标判定在中间产生弯折
            if (port1 == 1 || port1 == 3) { // 左右横向出发
                pts.add(new Point(s1.x, s2.y));
            } else { // 上下纵向出发
                pts.add(new Point(s2.x, s1.y));
            }

            pts.add(s2);
            pts.add(p2);

            // 依次画线
            for (int i = 0; i < pts.size() - 1; i++) {
                g2.drawLine(pts.get(i).x, pts.get(i).y, pts.get(i + 1).x, pts.get(i + 1).y);
            }

            // 在终点 p2 处画实心连接箭头
            drawArrow(g2, pts.get(pts.size() - 2), p2);
        }

        private Point getDirectionOffset(int portIndex, int d) {
            switch (portIndex) {
                case 0: return new Point(0, -d); // 上
                case 1: return new Point(d, 0);  // 右
                case 2: return new Point(0, d);  // 下
                case 3: return new Point(-d, 0); // 左
                default: return new Point(0, 0);
            }
        }

        private void drawArrow(Graphics2D g2, Point from, Point to) {
            double dx = to.x - from.x;
            double dy = to.y - from.y;
            double angle = Math.atan2(dy, dx);
            int len = 8; // 箭头长度

            Polygon arrow = new Polygon();
            arrow.addPoint(to.x, to.y);
            arrow.addPoint((int) (to.x - len * Math.cos(angle - Math.PI / 6)), (int) (to.y - len * Math.sin(angle - Math.PI / 6)));
            arrow.addPoint((int) (to.x - len * Math.cos(angle + Math.PI / 6)), (int) (to.y - len * Math.sin(angle + Math.PI / 6)));
            
            g2.fillPolygon(arrow);
        }

        private void drawEdgeLabel(Graphics2D g2, Point p1, Point p2, String label) {
            g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int cx = (p1.x + p2.x) / 2;
            int cy = (p1.y + p2.y) / 2 - 4;

            // 绘制文字白底防止与线条重合
            g2.setColor(UIManager.getColor("Panel.background"));
            if (g2.getColor() == null) g2.setColor(Color.WHITE);
            g2.fillRect(cx - fm.stringWidth(label) / 2 - 2, cy - fm.getAscent() + 2, fm.stringWidth(label) + 4, fm.getHeight());

            g2.setColor(UIManager.getColor("Label.foreground"));
            if (g2.getColor() == null) g2.setColor(Color.BLACK);
            g2.drawString(label, cx - fm.stringWidth(label) / 2, cy + fm.getAscent() / 2);
        }

        private void drawConnectionHandle(Graphics2D g2, FlowNode node) {
            // 在选中节点右侧画一个悬浮快捷连线手柄 🔗
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
            // 连线连接过程中，绘制全图各节点的 4 个连接锚点圆
            g2.setColor(Color.GRAY);
            g2.setStroke(new BasicStroke(1.0f));
            for (FlowNode node : nodes) {
                if (node == connectSourceNode) continue;
                for (int i = 0; i < 4; i++) {
                    Point p = node.getPortPoint(i);
                    g2.drawOval(p.x - 3, p.y - 3, 6, 6);
                    g2.setColor(new Color(250, 250, 250));
                    g2.fillOval(p.x - 2, p.y - 2, 4, 4);
                    g2.setColor(Color.GRAY);
                }
            }
        }

        private boolean isPointInConnectionHandle(FlowNode node, Point p) {
            int hx = node.x + node.w + 14;
            int hy = node.y + node.h / 2 - 9;
            return p.x >= hx && p.x <= hx + 18 && p.y >= hy && p.y <= hy + 18;
        }

        private FlowNode getNodeAtPoint(Point p) {
            // 反向获取，优先返回上方元素
            for (int i = nodes.size() - 1; i >= 0; i--) {
                FlowNode node = nodes.get(i);
                if (p.x >= node.x && p.x <= node.x + node.w && p.y >= node.y && p.y <= node.y + node.h) {
                    return node;
                }
            }
            return null;
        }

        private FlowEdge getEdgeAtPoint(Point p) {
            for (FlowEdge edge : edges) {
                Point p1 = edge.source.getPortPoint(edge.sourcePort);
                Point p2 = edge.target.getPortPoint(edge.targetPort);
                double d = Line2D.ptSegDist(p1.x, p1.y, p2.x, p2.y, p.x, p.y);
                if (d < 7.0) {
                    return edge;
                }
            }
            return null;
        }

        private int getClosestPortIndex(FlowNode node, Point p) {
            double minDist = Double.MAX_VALUE;
            int bestIdx = 0;
            for (int i = 0; i < 4; i++) {
                Point pt = node.getPortPoint(i);
                double dist = p.distance(pt);
                if (dist < minDist) {
                    minDist = dist;
                    bestIdx = i;
                }
            }
            return bestIdx;
        }

        private void addNewNode(Mode mode, Point p) {
            String type = TYPE_PROCESS;
            String name = "步骤";
            switch (mode) {
                case ADD_START_END:
                    type = TYPE_START_END;
                    name = "起止";
                    break;
                case ADD_DECISION:
                    type = TYPE_DECISION;
                    name = "是否合格?";
                    break;
                case ADD_DATA:
                    type = TYPE_DATA;
                    name = "输入数据";
                    break;
                case ADD_DATABASE:
                    type = TYPE_DATABASE;
                    name = "数据库";
                    break;
                case ADD_CLOUD:
                    type = TYPE_CLOUD;
                    name = "云服务";
                    break;
            }

            String id = "Node_" + UUID.randomUUID().toString().substring(0, 8);
            FlowNode node = new FlowNode(type, id, name, p.x - 60, p.y - 25);
            nodes.add(node);
            
            selectedNodes.clear();
            selectedNodes.add(node);
            selectedNode = node;
            selectedEdge = null;
            
            adjustCanvasSize();
            updatePropertyPanel();
            repaint();
        }

        private void initiateConnection(Point p) {
            FlowNode node = getNodeAtPoint(p);
            if (node != null) {
                isConnecting = true;
                connectSourceNode = node;
                connectSourcePortIndex = getClosestPortIndex(node, p);
            }
        }

        private void completeConnection(Point p) {
            FlowNode target = getNodeAtPoint(p);
            if (target != null && target != connectSourceNode) {
                int targetPort = getClosestPortIndex(target, p);
                
                // 检查是否已经有相同的连线了
                boolean exist = false;
                for (FlowEdge e : edges) {
                    if (e.source == connectSourceNode && e.target == target && e.sourcePort == connectSourcePortIndex && e.targetPort == targetPort) {
                        exist = true;
                        break;
                    }
                }
                if (!exist) {
                    String id = "Flow_" + UUID.randomUUID().toString().substring(0, 8);
                    FlowEdge edge = new FlowEdge(id, "", connectSourceNode, target, connectSourcePortIndex, targetPort);
                    edges.add(edge);
                    selectedEdge = edge;
                    selectedNode = null;
                    updatePropertyPanel();
                }
            }
        }

        private void deleteNode(FlowNode node) {
            nodes.remove(node);
            edges.removeIf(e -> e.source == node || e.target == node);
            clearSelection();
            repaint();
        }

        private void deleteEdge(FlowEdge edge) {
            edges.remove(edge);
            clearSelection();
            repaint();
        }

        private void editNodeName(FlowNode node) {
            String newName = UIUtils.input(getView(), "请输入新元素名称:", node.name);
            if (newName != null) {
                node.name = newName.trim();
                updatePropertyPanel();
                repaint();
            }
        }

        private void deleteSelected() {
            if (!selectedNodes.isEmpty()) {
                for (FlowNode n : new ArrayList<>(selectedNodes)) {
                    nodes.remove(n);
                    edges.removeIf(e -> e.source == n || e.target == n);
                }
                clearSelection();
                repaint();
            } else if (selectedEdge != null) {
                edges.remove(selectedEdge);
                clearSelection();
                repaint();
            }
        }
    }

    // ==========================================
    // 基础数据节点定义 FlowNode
    // ==========================================
    public static class FlowNode {
        public String id;
        public String type;
        public String name;
        public int x, y, w, h;

        public FlowNode(String type, String id, String name, int x, int y) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
            // 不同形状预设宽高比例
            this.w = type.equals(TYPE_DECISION) ? 80 : (type.equals(TYPE_START_END) || type.equals(TYPE_CLOUD) ? 100 : 120);
            this.h = type.equals(TYPE_DECISION) ? 80 : 50;
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
    }

    // ==========================================
    // 基础连线定义 FlowEdge
    // ==========================================
    public static class FlowEdge {
        public String id;
        public String label;
        public FlowNode source;
        public FlowNode target;
        public int sourcePort;
        public int targetPort;

        public FlowEdge(String id, String label, FlowNode source, FlowNode target, int sourcePort, int targetPort) {
            this.id = id;
            this.label = label;
            this.source = source;
            this.target = target;
            this.sourcePort = sourcePort;
            this.targetPort = targetPort;
        }
    }

    /** 极简文档监听器适配器 */
    private static class SimpleDocumentListener implements DocumentListener {
        private final Runnable r;
        SimpleDocumentListener(Runnable r) { this.r = r; }
        public void insertUpdate(DocumentEvent e) { r.run(); }
        public void removeUpdate(DocumentEvent e) { r.run(); }
        public void changedUpdate(DocumentEvent e) { r.run(); }
    }
}
