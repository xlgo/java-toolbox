package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.*;
import java.awt.event.*;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 流程图绘制设计器 (Flowchart Designer)：支持拖拽移动、从侧边图形库拖放(Drag & Drop)、一键连线磁吸、框选、右键菜单、自动布局，以及极度丰富的元素样式（边框、颜色、连线样式）设置。
 */
public class FlowchartPanel extends ToolPanel {

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

    // 工作模式
    private enum Mode {
        SELECT, CONNECT, ADD_START_END, ADD_PROCESS, ADD_DECISION, ADD_DATA, ADD_DATABASE, ADD_CLOUD, ADD_PREDEFINED, ADD_DOCUMENT, ADD_PREPARATION, ADD_MANUAL_INPUT, ADD_ANNOTATION
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
    
    // 样式编辑组件
    private JComboBox<String> nodeBgCombo;
    private JComboBox<String> nodeBorderCombo;
    private JComboBox<String> nodeBorderThicknessCombo;
    private JComboBox<String> edgeColorCombo;
    private JComboBox<String> edgeStrokeCombo;
    private JComboBox<String> edgeRoutingCombo;
    
    private JPanel propPanel;
    private boolean updatingProperties = false;

    // 样式预设定义
    private static final Map<String, Color> COLOR_PRESETS = new HashMap<>();
    static {
        COLOR_PRESETS.put("亮蓝色", new Color(217, 237, 247, 220));
        COLOR_PRESETS.put("青绿绿", new Color(223, 240, 216, 220));
        COLOR_PRESETS.put("淡明黄", new Color(252, 248, 227, 220));
        COLOR_PRESETS.put("罗兰紫", new Color(235, 230, 245, 220));
        COLOR_PRESETS.put("警示红", new Color(242, 222, 222, 220));
        COLOR_PRESETS.put("极简灰", new Color(245, 245, 245, 220));
        COLOR_PRESETS.put("纯透明", new Color(0, 0, 0, 0));
        COLOR_PRESETS.put("经典黑", new Color(50, 50, 50));
    }

    public FlowchartPanel() {
        super("chart", "flowchart",
                "Flowchart", "流程图", "画图", "设计器", "ProcessOn", "Draw.io", "Diagram");
        initDefaultDiagram();
    }

    private void initDefaultDiagram() {
        FlowNode start = new FlowNode(TYPE_START_END, "start", "开始", 140, 40);
        FlowNode process1 = new FlowNode(TYPE_PROCESS, "process1", "数据初始化", 130, 140);
        FlowNode decision = new FlowNode(TYPE_DECISION, "decision1", "是否合格?", 150, 240);
        FlowNode db = new FlowNode(TYPE_DATABASE, "db1", "保存至数据库", 320, 255);
        FlowNode end = new FlowNode(TYPE_START_END, "end", "结束", 140, 380);

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

        // 1. 左侧图形备选面板（图形库 Shapes Library）
        JPanel shapesPanel = new JPanel(new BorderLayout(6, 6));
        shapesPanel.setPreferredSize(new Dimension(160, 0));
        shapesPanel.setBorder(BorderFactory.createTitledBorder(
                null, "图形备用库 (可拖动)", TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION, UIUtils.plainFont(),
                UIManager.getColor("Component.accentColor")));

        JPanel shapeGrid = new JPanel(new GridLayout(11, 1, 4, 6));
        shapeGrid.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // 增加各种类型的拖拽小卡片
        shapeGrid.add(new ShapeDragLabel("🟢 起止框", TYPE_START_END));
        shapeGrid.add(new ShapeDragLabel("🟦 步骤框", TYPE_PROCESS));
        shapeGrid.add(new ShapeDragLabel("🔶 判定框", TYPE_DECISION));
        shapeGrid.add(new ShapeDragLabel("▱ 数据框", TYPE_DATA));
        shapeGrid.add(new ShapeDragLabel("🛢️ 数据库", TYPE_DATABASE));
        shapeGrid.add(new ShapeDragLabel("☁️ 外部系统", TYPE_CLOUD));
        shapeGrid.add(new ShapeDragLabel("♊ 预设子过程", TYPE_PREDEFINED));
        shapeGrid.add(new ShapeDragLabel("📑 文档框", TYPE_DOCUMENT));
        shapeGrid.add(new ShapeDragLabel("⬡ 准备工作", TYPE_PREPARATION));
        shapeGrid.add(new ShapeDragLabel("⧄ 手工输入", TYPE_MANUAL_INPUT));
        shapeGrid.add(new ShapeDragLabel("💬 注释文本", TYPE_ANNOTATION));

        shapesPanel.add(new JScrollPane(shapeGrid), BorderLayout.CENTER);
        root.add(shapesPanel, BorderLayout.WEST);

        // 2. 顶部工具栏
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")));

        ButtonGroup btnGroup = new ButtonGroup();

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

        JToggleButton connectBtn = helper.addToggle("建立吸附连线", Mode.CONNECT, " 🔗 建立连线");
        toolBar.addSeparator();
        
        JButton layoutBtn = new JButton(" 📐 自动排版");
        layoutBtn.setFont(UIUtils.plainFont());
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

        // 3. 右侧属性与样式编辑面板
        propPanel = new JPanel(new GridBagLayout());
        propPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, UIManager.getColor("Component.borderColor")),
                "属性与样式", TitledBorder.DEFAULT_JUSTIFICATION, TitledBorder.DEFAULT_POSITION,
                UIUtils.titleFont(), UIManager.getColor("Component.accentColor")));
        propPanel.setPreferredSize(new Dimension(250, 0));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.weightx = 1.0;
        gbc.gridx = 0;

        int gridy = 0;

        // 元素名/文本
        propPanel.add(new JLabel("文本内容:"), getGbc(gbc, gridy++));
        nameField = new JTextField();
        nameField.setFont(UIUtils.plainFont());
        propPanel.add(nameField, getGbc(gbc, gridy++));

        // 元素ID
        propPanel.add(new JLabel("唯一标识 ID:"), getGbc(gbc, gridy++));
        idField = new JTextField();
        idField.setFont(UIUtils.plainFont());
        idField.setEditable(false);
        propPanel.add(idField, getGbc(gbc, gridy++));

        // 连线文本
        propPanel.add(new JLabel("连线标签/条件:"), getGbc(gbc, gridy++));
        edgeLabelField = new JTextField();
        edgeLabelField.setFont(UIUtils.plainFont());
        propPanel.add(edgeLabelField, getGbc(gbc, gridy++));

        // 分割线
        JSeparator sep = new JSeparator();
        propPanel.add(sep, getGbc(gbc, gridy++));

        // --- 节点样式设置 ---
        propPanel.add(new JLabel("节点背景填充:"), getGbc(gbc, gridy++));
        nodeBgCombo = new JComboBox<>(new String[]{"亮蓝色", "青绿绿", "淡明黄", "罗兰紫", "警示红", "极简灰", "纯透明"});
        propPanel.add(nodeBgCombo, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("节点边框类型:"), getGbc(gbc, gridy++));
        nodeBorderCombo = new JComboBox<>(new String[]{"实线边框", "虚线边框"});
        propPanel.add(nodeBorderCombo, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("节点边框粗细:"), getGbc(gbc, gridy++));
        nodeBorderThicknessCombo = new JComboBox<>(new String[]{"细线 (1.5px)", "中等 (2.5px)", "粗线 (4.0px)"});
        propPanel.add(nodeBorderThicknessCombo, getGbc(gbc, gridy++));

        // --- 连线样式设置 ---
        propPanel.add(new JLabel("连线色彩:"), getGbc(gbc, gridy++));
        edgeColorCombo = new JComboBox<>(new String[]{"经典黑", "亮蓝色", "青绿绿", "警示红"});
        propPanel.add(edgeColorCombo, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("连线类型:"), getGbc(gbc, gridy++));
        edgeStrokeCombo = new JComboBox<>(new String[]{"实线", "虚线"});
        propPanel.add(edgeStrokeCombo, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("连线路径样式:"), getGbc(gbc, gridy++));
        edgeRoutingCombo = new JComboBox<>(new String[]{"直角折线", "直连实线", "贝塞尔曲线"});
        propPanel.add(edgeRoutingCombo, getGbc(gbc, gridy++));

        gbc.gridy = gridy++;
        gbc.weighty = 1.0;
        propPanel.add(new JPanel(), gbc);

        // 绑定字段监听
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

        // 样式下拉绑定
        nodeBgCombo.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            String key = (String) nodeBgCombo.getSelectedItem();
            selectedNode.bgColor = COLOR_PRESETS.get(key);
            canvasPanel.repaint();
        });

        nodeBorderCombo.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            selectedNode.isDashedBorder = nodeBorderCombo.getSelectedIndex() == 1;
            canvasPanel.repaint();
        });

        nodeBorderThicknessCombo.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            int idx = nodeBorderThicknessCombo.getSelectedIndex();
            selectedNode.borderThickness = idx == 0 ? 1.5f : (idx == 1 ? 2.5f : 4.0f);
            canvasPanel.repaint();
        });

        edgeColorCombo.addActionListener(e -> {
            if (updatingProperties || selectedEdge == null) return;
            String key = (String) edgeColorCombo.getSelectedItem();
            selectedEdge.lineColor = COLOR_PRESETS.get(key);
            canvasPanel.repaint();
        });

        edgeStrokeCombo.addActionListener(e -> {
            if (updatingProperties || selectedEdge == null) return;
            selectedEdge.isDashed = edgeStrokeCombo.getSelectedIndex() == 1;
            canvasPanel.repaint();
        });

        edgeRoutingCombo.addActionListener(e -> {
            if (updatingProperties || selectedEdge == null) return;
            int idx = edgeRoutingCombo.getSelectedIndex();
            selectedEdge.routingType = idx == 0 ? "manhattan" : (idx == 1 ? "straight" : "bezier");
            canvasPanel.repaint();
        });

        root.add(propPanel, BorderLayout.EAST);

        // 4. 中间画布区
        canvasPanel = new CanvasPanel();
        scrollPane = new JScrollPane(canvasPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        root.add(scrollPane, BorderLayout.CENTER);

        updatePropertyPanel();

        return root;
    }

    private GridBagConstraints getGbc(GridBagConstraints gbc, int y) {
        gbc.gridy = y;
        gbc.weighty = 0.0;
        return gbc;
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

            // 启用并定位节点样式
            nodeBgCombo.setEnabled(true);
            nodeBorderCombo.setEnabled(true);
            nodeBorderThicknessCombo.setEnabled(true);
            
            // 匹配背景色
            nodeBgCombo.setSelectedItem(getKeyByColor(selectedNode.bgColor));
            nodeBorderCombo.setSelectedIndex(selectedNode.isDashedBorder ? 1 : 0);
            nodeBorderThicknessCombo.setSelectedIndex(selectedNode.borderThickness == 1.5f ? 0 : (selectedNode.borderThickness == 2.5f ? 1 : 2));

            // 禁用连线样式
            edgeColorCombo.setEnabled(false);
            edgeStrokeCombo.setEnabled(false);
            edgeRoutingCombo.setEnabled(false);

        } else if (selectedEdge != null) {
            nameField.setEditable(false);
            nameField.setText("");
            idField.setText(selectedEdge.id);
            edgeLabelField.setEditable(true);
            edgeLabelField.setText(selectedEdge.label);

            // 启用连线样式
            edgeColorCombo.setEnabled(true);
            edgeStrokeCombo.setEnabled(true);
            edgeRoutingCombo.setEnabled(true);

            edgeColorCombo.setSelectedItem(getKeyByColor(selectedEdge.lineColor));
            edgeStrokeCombo.setSelectedIndex(selectedEdge.isDashed ? 1 : 0);
            edgeRoutingCombo.setSelectedItem("manhattan".equals(selectedEdge.routingType) ? "直角折线" : 
                                            ("straight".equals(selectedEdge.routingType) ? "直连实线" : "贝塞尔曲线"));

            // 禁用节点样式
            nodeBgCombo.setEnabled(false);
            nodeBorderCombo.setEnabled(false);
            nodeBorderThicknessCombo.setEnabled(false);

            // 【重磅体验优化】：选中连线时，连线标签输入框直接获取焦点并全选，支持秒级打字输入！
            SwingUtilities.invokeLater(() -> {
                edgeLabelField.requestFocusInWindow();
                edgeLabelField.selectAll();
            });

        } else {
            nameField.setEditable(false);
            nameField.setText("");
            idField.setText("");
            edgeLabelField.setEditable(false);
            edgeLabelField.setText("");

            nodeBgCombo.setEnabled(false);
            nodeBorderCombo.setEnabled(false);
            nodeBorderThicknessCombo.setEnabled(false);
            edgeColorCombo.setEnabled(false);
            edgeStrokeCombo.setEnabled(false);
            edgeRoutingCombo.setEnabled(false);
        }

        updatingProperties = false;
    }

    private String getKeyByColor(Color c) {
        if (c == null) return "亮蓝色";
        for (Map.Entry<String, Color> e : COLOR_PRESETS.entrySet()) {
            if (e.getValue().getRGB() == c.getRGB()) {
                return e.getKey();
            }
        }
        return "亮蓝色";
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

            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, width, height);

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

    private void autoLayout() {
        if (nodes.isEmpty()) return;

        int[] inDegree = new int[nodes.size()];
        for (FlowEdge edge : edges) {
            int targetIdx = nodes.indexOf(edge.target);
            if (targetIdx != -1) {
                inDegree[targetIdx]++;
            }
        }

        List<List<FlowNode>> layers = new ArrayList<>();
        List<FlowNode> currentLayer = new ArrayList<>();
        
        for (int i = 0; i < nodes.size(); i++) {
            if (inDegree[i] == 0) {
                currentLayer.add(nodes.get(i));
            }
        }

        if (currentLayer.isEmpty()) {
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

        List<FlowNode> orphanLayer = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (!visited[i]) {
                orphanLayer.add(nodes.get(i));
            }
        }
        if (!orphanLayer.isEmpty()) {
            layers.add(orphanLayer);
        }

        int startY = 60;
        int layerGapY = 100;
        int nodeGapX = 150;

        for (int layerIdx = 0; layerIdx < layers.size(); layerIdx++) {
            List<FlowNode> layerNodes = layers.get(layerIdx);
            int layerWidth = (layerNodes.size() - 1) * nodeGapX;
            int startX = Math.max(100, 320 - layerWidth / 2);

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
                            addNewNodeAt(shapeType, dropPoint);
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
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    currentMousePoint = e.getPoint();

                    if (SwingUtilities.isRightMouseButton(e)) {
                        showContextMenu(e);
                        return;
                    }

                    // 1. 快捷添加模式：添加一次后立马重置为选择模式，防止误触多加
                    if (currentMode != Mode.SELECT && currentMode != Mode.CONNECT) {
                        addNewNodeAt(getShapeTypeFromMode(currentMode), e.getPoint());
                        setMode(Mode.SELECT); // 恢复为选择状态
                        return;
                    }

                    // 2. 连线模式
                    if (currentMode == Mode.CONNECT) {
                        initiateConnection(e.getPoint());
                        return;
                    }

                    // 3. 选择/移动模式
                    if (currentMode == Mode.SELECT) {
                        // 连线快捷手柄判定
                        if (selectedNode != null && isPointInConnectionHandle(selectedNode, e.getPoint())) {
                            isConnecting = true;
                            connectSourceNode = selectedNode;
                            connectSourcePortIndex = 1; // 默认右边出发
                            return;
                        }

                        // 点中节点判定
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

                        // 点中连线判定
                        FlowEdge clickedEdge = getEdgeAtPoint(e.getPoint());
                        if (clickedEdge != null) {
                            clearSelection();
                            selectedEdge = clickedEdge;
                            updatePropertyPanel();
                            repaint();
                            return;
                        }

                        // 框选判定
                        clearSelection();
                        selectionStart = e.getPoint();
                        selectionRect = new Rectangle(selectionStart);
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
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
                    // 连接连线拖拽
                    if (isConnecting && connectSourceNode != null) {
                        currentMousePoint = e.getPoint();
                        tempTargetPortIndex = -1;
                        tempTargetNode = null;
                        
                        FlowNode target = getNodeAtPoint(e.getPoint());
                        if (target != null && target != connectSourceNode) {
                            tempTargetNode = target;
                            tempTargetPortIndex = getClosestPortIndex(target, e.getPoint());
                        }
                        repaint();
                        return;
                    }

                    // 移动节点拖拽 (支持多选批量移动)
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

                    // 框选范围拖拽
                    if (selectionStart != null) {
                        int x = Math.min(selectionStart.x, e.getX());
                        int y = Math.min(selectionStart.y, e.getY());
                        int w = Math.abs(selectionStart.x - e.getX());
                        int h = Math.abs(selectionStart.y - e.getY());
                        selectionRect.setBounds(x, y, w, h);

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

            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_DELETE || e.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
                        deleteSelected();
                    }
                }
            });
        }

        private String getShapeTypeFromMode(Mode mode) {
            switch (mode) {
                case ADD_START_END: return TYPE_START_END;
                case ADD_DECISION: return TYPE_DECISION;
                case ADD_DATA: return TYPE_DATA;
                case ADD_DATABASE: return TYPE_DATABASE;
                case ADD_CLOUD: return TYPE_CLOUD;
                case ADD_PREDEFINED: return TYPE_PREDEFINED;
                case ADD_DOCUMENT: return TYPE_DOCUMENT;
                case ADD_PREPARATION: return TYPE_PREPARATION;
                case ADD_MANUAL_INPUT: return TYPE_MANUAL_INPUT;
                case ADD_ANNOTATION: return TYPE_ANNOTATION;
                default: return TYPE_PROCESS;
            }
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
                
                JMenuItem renameItem = new JMenuItem("编辑文本内容");
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

            // 绘制框选矩形
            if (selectionRect != null) {
                g2.setColor(new Color(64, 158, 255, 30));
                g2.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
                g2.setColor(new Color(64, 158, 255, 180));
                g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{4f}, 0.0f));
                g2.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
            }

            // 连线建立虚线
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

            // 绘制选中节点快捷手柄
            if (currentMode == Mode.SELECT && selectedNode != null && selectedNodes.size() <= 1) {
                drawConnectionHandle(g2, selectedNode);
            }

            // 建立连线时高亮锚点
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
                Color borderTheme = isSelected ? UIManager.getColor("Component.focusColor") : node.borderColor;
                if (borderTheme == null) borderTheme = isSelected ? Color.BLUE : Color.GRAY;

                // 粗细样式应用
                float thick = isSelected ? node.borderThickness + 1.0f : node.borderThickness;
                Stroke stroke = node.isDashedBorder ? 
                        new BasicStroke(thick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5f}, 0.0f) :
                        new BasicStroke(thick);

                g2.setStroke(stroke);

                if (node.type.equals(TYPE_START_END)) {
                    // 起止框 (椭圆)
                    g2.setColor(node.bgColor);
                    g2.fillOval(node.x, node.y, node.w, node.h);
                    g2.setColor(borderTheme);
                    g2.drawOval(node.x, node.y, node.w, node.h);

                } else if (node.type.equals(TYPE_DECISION)) {
                    // 判定框 (菱形)
                    int[] xPoints = {node.x + node.w / 2, node.x + node.w, node.x + node.w / 2, node.x};
                    int[] yPoints = {node.y, node.y + node.h / 2, node.y + node.h, node.y + node.h / 2};
                    g2.setColor(node.bgColor);
                    g2.fillPolygon(xPoints, yPoints, 4);
                    g2.setColor(borderTheme);
                    g2.drawPolygon(xPoints, yPoints, 4);

                } else if (node.type.equals(TYPE_DATA)) {
                    // 数据框 (平行四边形)
                    int skew = 15;
                    int[] xPoints = {node.x + skew, node.x + node.w, node.x + node.w - skew, node.x};
                    int[] yPoints = {node.y, node.y, node.y + node.h, node.y + node.h};
                    g2.setColor(node.bgColor);
                    g2.fillPolygon(xPoints, yPoints, 4);
                    g2.setColor(borderTheme);
                    g2.drawPolygon(xPoints, yPoints, 4);

                } else if (node.type.equals(TYPE_DATABASE)) {
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

                } else if (node.type.equals(TYPE_CLOUD)) {
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

                } else if (node.type.equals(TYPE_PREDEFINED)) {
                    // 预设子过程 (左右双竖线矩形)
                    g2.setColor(node.bgColor);
                    g2.fillRect(node.x, node.y, node.w, node.h);
                    g2.setColor(borderTheme);
                    g2.drawRect(node.x, node.y, node.w, node.h);
                    g2.drawLine(node.x + 8, node.y, node.x + 8, node.y + node.h);
                    g2.drawLine(node.x + node.w - 8, node.y, node.x + node.w - 8, node.y + node.h);

                } else if (node.type.equals(TYPE_DOCUMENT)) {
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

                } else if (node.type.equals(TYPE_PREPARATION)) {
                    // 准备 (六边形)
                    int[] xPoints = {node.x + 15, node.x + node.w - 15, node.x + node.w, node.x + node.w - 15, node.x + 15, node.x};
                    int[] yPoints = {node.y, node.y, node.y + node.h / 2, node.y + node.h, node.y + node.h, node.y + node.h / 2};
                    g2.setColor(node.bgColor);
                    g2.fillPolygon(xPoints, yPoints, 6);
                    g2.setColor(borderTheme);
                    g2.drawPolygon(xPoints, yPoints, 6);

                } else if (node.type.equals(TYPE_MANUAL_INPUT)) {
                    // 手工输入 (斜顶四边形)
                    int[] xPoints = {node.x, node.x + node.w, node.x + node.w, node.x};
                    int[] yPoints = {node.y + 10, node.y, node.y + node.h, node.y + node.h};
                    g2.setColor(node.bgColor);
                    g2.fillPolygon(xPoints, yPoints, 4);
                    g2.setColor(borderTheme);
                    g2.drawPolygon(xPoints, yPoints, 4);

                } else if (node.type.equals(TYPE_ANNOTATION)) {
                    // 注释 (半包围线，背景透明)
                    g2.setColor(borderTheme);
                    g2.drawLine(node.x + 12, node.y, node.x, node.y);
                    g2.drawLine(node.x, node.y, node.x, node.y + node.h);
                    g2.drawLine(node.x, node.y + node.h, node.x + 12, node.y + node.h);

                } else {
                    // 默认步骤框 (矩形)
                    g2.setColor(node.bgColor);
                    g2.fillRect(node.x, node.y, node.w, node.h);
                    g2.setColor(borderTheme);
                    g2.drawRect(node.x, node.y, node.w, node.h);
                }

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

            // 对长文本自动换行
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
                g2.setColor(isSelected ? UIManager.getColor("Component.focusColor") : edge.lineColor);
                if (g2.getColor() == null) g2.setColor(isSelected ? Color.BLUE : Color.DARK_GRAY);

                float thick = isSelected ? 2.5f : 1.5f;
                g2.setStroke(edge.isDashed ? 
                        new BasicStroke(thick, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10.0f, new float[]{5f}, 0.0f) :
                        new BasicStroke(thick));

                Point p1 = edge.source.getPortPoint(edge.sourcePort);
                Point p2 = edge.target.getPortPoint(edge.targetPort);

                // 根据连线模式绘制：Manhattan折线 / Straight直线 / Bezier贝塞尔曲线
                if ("bezier".equals(edge.routingType)) {
                    drawBezierLine(g2, p1, p2, edge.sourcePort, edge.targetPort);
                } else if ("straight".equals(edge.routingType)) {
                    drawStraightLine(g2, p1, p2);
                } else {
                    drawManhattanLine(g2, p1, p2, edge.sourcePort, edge.targetPort);
                }

                if (edge.label != null && !edge.label.trim().isEmpty()) {
                    drawEdgeLabel(g2, p1, p2, edge.label);
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
            
            // 依据起始方向，推算曲线拉伸控制点
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

        private void drawEdgeLabel(Graphics2D g2, Point p1, Point p2, String label) {
            g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            FontMetrics fm = g2.getFontMetrics();
            int cx = (p1.x + p2.x) / 2;
            int cy = (p1.y + p2.y) / 2 - 4;

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

        private void addNewNodeAt(String shapeType, Point p) {
            String name = "节点";
            switch (shapeType) {
                case TYPE_START_END: name = "起止"; break;
                case TYPE_DECISION: name = "是否合格?"; break;
                case TYPE_DATA: name = "输入数据"; break;
                case TYPE_DATABASE: name = "数据库"; break;
                case TYPE_CLOUD: name = "云服务"; break;
                case TYPE_PREDEFINED: name = "预设子过程"; break;
                case TYPE_DOCUMENT: name = "文档内容"; break;
                case TYPE_PREPARATION: name = "准备工作"; break;
                case TYPE_MANUAL_INPUT: name = "手工输入"; break;
                case TYPE_ANNOTATION: name = "注释文本"; break;
            }

            String id = "Node_" + UUID.randomUUID().toString().substring(0, 8);
            FlowNode node = new FlowNode(shapeType, id, name, p.x - 50, p.y - 25);
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
            String newName = UIUtils.input(getView(), "请输入新名称文本:", node.name);
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
    // 侧边图形备选列表小组件 ShapeDragLabel
    // ==========================================
    private static class ShapeDragLabel extends JLabel {
        private final String shapeType;

        ShapeDragLabel(String name, String type) {
            super(name, SwingConstants.LEFT);
            this.shapeType = type;
            setFont(UIUtils.plainFont());
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                    BorderFactory.createEmptyBorder(6, 12, 6, 12)
            ));
            setOpaque(true);
            setBackground(UIManager.getColor("Panel.background"));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            // 实现标准 Swing DND 拖放机制
            setTransferHandler(new TransferHandler() {
                @Override
                public int getSourceActions(JComponent c) {
                    return COPY;
                }
                @Override
                protected Transferable createTransferable(JComponent c) {
                    return new StringSelection(shapeType);
                }
            });

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    JComponent c = (JComponent) e.getSource();
                    TransferHandler th = c.getTransferHandler();
                    th.exportAsDrag(c, e, TransferHandler.COPY);
                }
            });
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
        
        // 样式可变配置
        public Color bgColor = new Color(217, 237, 247, 220);
        public Color borderColor = new Color(51, 122, 183);
        public boolean isDashedBorder = false;
        public float borderThickness = 1.5f;

        public FlowNode(String type, String id, String name, int x, int y) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.x = x;
            this.y = y;
            
            // 节点预置宽高比
            this.w = type.equals(TYPE_DECISION) ? 80 : (type.equals(TYPE_START_END) || type.equals(TYPE_CLOUD) ? 100 : 120);
            this.h = type.equals(TYPE_DECISION) ? 80 : 50;

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
        
        // 样式可变配置
        public Color lineColor = new Color(50, 50, 50);
        public boolean isDashed = false;
        public String routingType = "manhattan"; // manhattan, straight, bezier

        public FlowEdge(String id, String label, FlowNode source, FlowNode target, int sourcePort, int targetPort) {
            this.id = id;
            this.label = label;
            this.source = source;
            this.target = target;
            this.sourcePort = sourcePort;
            this.targetPort = targetPort;
        }
    }

    /** 极简 DocumentListener 适配器 */
    private static class SimpleDocumentListener implements DocumentListener {
        private final Runnable r;
        SimpleDocumentListener(Runnable r) { this.r = r; }
        public void insertUpdate(DocumentEvent e) { r.run(); }
        public void removeUpdate(DocumentEvent e) { r.run(); }
        public void changedUpdate(DocumentEvent e) { r.run(); }
    }
}
