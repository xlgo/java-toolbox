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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 流程图绘制设计器 (Flowchart Designer)：支持拖拽移动、从侧边图形库拖放(Drag & Drop)、一键连线磁吸、框选、右键菜单、自动布局，以及极度丰富的元素样式（边框、颜色、连线样式）设置。
 */
public class FlowchartPanel extends ToolPanel {

    // 节点类型定义
    public static final String TYPE_START_END = FlowNode.TYPE_START_END;       // 起止框 (椭圆)
    public static final String TYPE_PROCESS = FlowNode.TYPE_PROCESS;           // 步骤框 (矩形)
    public static final String TYPE_DECISION = FlowNode.TYPE_DECISION;         // 判定框 (菱形)
    public static final String TYPE_DATA = FlowNode.TYPE_DATA;                 // 输入/输出 (平行四边形)
    public static final String TYPE_DATABASE = FlowNode.TYPE_DATABASE;         // 数据库 (圆柱)
    public static final String TYPE_CLOUD = FlowNode.TYPE_CLOUD;               // 云服务 (云朵)
    public static final String TYPE_PREDEFINED = FlowNode.TYPE_PREDEFINED;     // 预设子过程 (双竖边矩形)
    public static final String TYPE_DOCUMENT = FlowNode.TYPE_DOCUMENT;         // 文档 (底部波浪矩形)
    public static final String TYPE_PREPARATION = FlowNode.TYPE_PREPARATION;   // 准备/初始化 (六边形)
    public static final String TYPE_MANUAL_INPUT = FlowNode.TYPE_MANUAL_INPUT; // 手工输入 (斜顶矩形)
    public static final String TYPE_ANNOTATION = FlowNode.TYPE_ANNOTATION;     // 注释文本 (半包围框)
    public static final String TYPE_TERMINATOR = FlowNode.TYPE_TERMINATOR;     // 终结符/起止 (圆角矩形)
    public static final String TYPE_CARD = FlowNode.TYPE_CARD;                 // 卡片 (右上剪角矩形)
    public static final String TYPE_DELAY = FlowNode.TYPE_DELAY;               // 延时 (左直右圆)
    public static final String TYPE_DISPLAY = FlowNode.TYPE_DISPLAY;           // 显示 (左尖右圆)
    public static final String TYPE_INTERNAL_STORAGE = FlowNode.TYPE_INTERNAL_STORAGE; // 内部存储
    public static final String TYPE_OFF_PAGE_CONNECTOR = FlowNode.TYPE_OFF_PAGE_CONNECTOR; // 离页连接符
    public static final String TYPE_LIFELINE = FlowNode.TYPE_LIFELINE;         // 生命线 (对象框+下方虚线)
    public static final String TYPE_ACTOR = FlowNode.TYPE_ACTOR;               // 角色生命线 (小人+下方虚线)
    public static final String TYPE_ACTIVATION = FlowNode.TYPE_ACTIVATION;     // 激活条 (细长垂直矩形)

    // 工作模式
    enum Mode {
        SELECT, CONNECT, ADD_START_END, ADD_PROCESS, ADD_DECISION, ADD_DATA, ADD_DATABASE, ADD_CLOUD, ADD_PREDEFINED, ADD_DOCUMENT, ADD_PREPARATION, ADD_MANUAL_INPUT, ADD_ANNOTATION,
        ADD_TERMINATOR, ADD_CARD, ADD_DELAY, ADD_DISPLAY, ADD_INTERNAL_STORAGE, ADD_OFF_PAGE_CONNECTOR
    }

    Mode currentMode = Mode.SELECT;

    // 数据模型
    final List<FlowNode> nodes = new ArrayList<>();
    final List<FlowEdge> edges = new ArrayList<>();

    // 选中的元素
    final List<FlowNode> selectedNodes = new ArrayList<>();
    FlowNode selectedNode = null;
    FlowEdge selectedEdge = null;

    // 交互辅助变量
    enum DragState {
        NONE, MOVE_NODE, MOVE_PORT, CREATE_CONNECTION,
        RESIZE_TL, RESIZE_TC, RESIZE_TR, RESIZE_ML, RESIZE_MR, RESIZE_BL, RESIZE_BC, RESIZE_BR,
        DRAG_WAYPOINT, DRAG_EDGE_START, DRAG_EDGE_END, DRAG_EDGE_LABEL
    }
    DragState currentDragState = DragState.NONE;
    FlowNode draggedNode = null;
    FlowNode connectSourceNode = null;
    int connectSourcePortIndex = -1; // 0:上, 1:右, 2:下, 3:左
    double connectSourceRelX = 0.5;
    double connectSourceRelY = 0.5;
    Point currentMousePoint = new Point();
    boolean isConnecting = false;

    // 连线与拐点拖拽交互变量
    FlowEdge draggedEdge = null;
    int draggedWaypointIndex = -1;
    Point tempDragPoint = null;

    // 磁吸临时目标
    FlowNode tempTargetNode = null;
    int tempTargetPortIndex = -1;
    double tempTargetRelX = 0.5;
    double tempTargetRelY = 0.5;

    // 框选变量
    Point selectionStart = null;
    Rectangle selectionRect = null;

    // 撤销与重做数据栈
    final java.util.Stack<String> undoStack = new java.util.Stack<>();
    final java.util.Stack<String> redoStack = new java.util.Stack<>();
    String dragStartState = null;
    private JButton undoBtn;
    private JButton redoBtn;

    // 缩放因子
    double zoomFactor = 1.0;

    // UI 组件
    private CanvasPanel canvasPanel;
    private JScrollPane scrollPane;
    private JTextField nameField;
    private JTextField idField;
    private JTextField edgeLabelField;
    
    // 样式编辑组件
    private JComboBox<String> nodeBgCombo;
    private JComboBox<String> nodeBorderColorCombo;
    private JComboBox<String> nodeBorderCombo;
    private JComboBox<String> nodeBorderThicknessCombo;
    private JComboBox<String> nodeTextColorCombo;
    private JSpinner nodeFontSizeSpinner;
    private JCheckBox nodeBoldToggle;
    private JComboBox<String> edgeColorCombo;
    private JComboBox<String> edgeStrokeCombo;
    private JComboBox<String> edgeRoutingCombo;
    JSlider edgeLabelPosSlider;
    
    private JPanel propPanel;
    boolean updatingProperties = false;

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
        COLOR_PRESETS.put("经典白", new Color(255, 255, 255));
        COLOR_PRESETS.put("深蓝色", new Color(51, 122, 183));
        COLOR_PRESETS.put("森林绿", new Color(70, 136, 71));
        COLOR_PRESETS.put("深黄色", new Color(138, 109, 59));
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
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(5, 5));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 1. 左侧图形备选面板（图形库 Shapes Library）
        JPanel shapesPanel = new JPanel(new BorderLayout(6, 6));
        shapesPanel.setPreferredSize(new Dimension(190, 0));
        shapesPanel.setBorder(BorderFactory.createTitledBorder(
                null, "图形备用库 (可拖动)", TitledBorder.DEFAULT_JUSTIFICATION,
                TitledBorder.DEFAULT_POSITION, UIUtils.plainFont(),
                UIManager.getColor("Component.accentColor")));

        JPanel accordionPanel = new ScrollablePanel();
        accordionPanel.setLayout(new BoxLayout(accordionPanel, BoxLayout.Y_AXIS));

        // 1. 基础流程
        JPanel flowGrid = new JPanel(new GridLayout(0, 2, 4, 4));
        flowGrid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        flowGrid.add(new ShapeDragLabel("🟢", "起止框", TYPE_START_END));
        flowGrid.add(new ShapeDragLabel("🟦", "步骤框", TYPE_PROCESS));
        flowGrid.add(new ShapeDragLabel("🔶", "判定框", TYPE_DECISION));
        flowGrid.add(new ShapeDragLabel("▱", "数据框", TYPE_DATA));
        flowGrid.add(new ShapeDragLabel("♊", "预设子过程", TYPE_PREDEFINED));
        flowGrid.add(new ShapeDragLabel("🔘", "终结符/起止", TYPE_TERMINATOR));
        flowGrid.add(new ShapeDragLabel("⛛", "离页连接符", TYPE_OFF_PAGE_CONNECTOR));
        addAccordionGroup("基础流程", flowGrid, accordionPanel);

        // 2. 系统/数据
        JPanel sysGrid = new JPanel(new GridLayout(0, 2, 4, 4));
        sysGrid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        sysGrid.add(new ShapeDragLabel("🛢️", "数据库", TYPE_DATABASE));
        sysGrid.add(new ShapeDragLabel("☁️", "外部系统", TYPE_CLOUD));
        sysGrid.add(new ShapeDragLabel("📑", "文档框", TYPE_DOCUMENT));
        sysGrid.add(new ShapeDragLabel("⬡", "准备工作", TYPE_PREPARATION));
        sysGrid.add(new ShapeDragLabel("⧄", "手工输入", TYPE_MANUAL_INPUT));
        sysGrid.add(new ShapeDragLabel("⏳", "延时符", TYPE_DELAY));
        sysGrid.add(new ShapeDragLabel("📺", "显示器", TYPE_DISPLAY));
        sysGrid.add(new ShapeDragLabel("💾", "内部存储", TYPE_INTERNAL_STORAGE));
        sysGrid.add(new ShapeDragLabel("🃏", "卡片登记", TYPE_CARD));
        sysGrid.add(new ShapeDragLabel("💬", "注释文本", TYPE_ANNOTATION));
        addAccordionGroup("系统/数据", sysGrid, accordionPanel);

        // 3. 时序图组件
        JPanel seqGrid = new JPanel(new GridLayout(0, 2, 4, 4));
        seqGrid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        seqGrid.add(new ShapeDragLabel("💈", "生命线", TYPE_LIFELINE));
        seqGrid.add(new ShapeDragLabel("🧍", "角色线", TYPE_ACTOR));
        seqGrid.add(new ShapeDragLabel("▮", "激活条", TYPE_ACTIVATION));
        addAccordionGroup("时序组件", seqGrid, accordionPanel);

        // 添加垂直胶水，使折叠时内容紧贴顶部，不被拉长拉高
        accordionPanel.add(Box.createVerticalGlue());

        JScrollPane shapesScrollPane = new JScrollPane(accordionPanel);
        shapesScrollPane.setBorder(BorderFactory.createEmptyBorder());
        shapesScrollPane.getVerticalScrollBar().setUnitIncrement(10);
        shapesPanel.add(shapesScrollPane, BorderLayout.CENTER);
        
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

        toolBar.addSeparator();

        undoBtn = new JButton(" ↩️ 撤销");
        undoBtn.setFont(UIUtils.plainFont());
        undoBtn.setToolTipText("撤销上一步操作 (Ctrl+Z)");
        undoBtn.setEnabled(false);
        undoBtn.addActionListener(e -> undo());
        toolBar.add(undoBtn);

        redoBtn = new JButton(" ↪️ 重做");
        redoBtn.setFont(UIUtils.plainFont());
        redoBtn.setToolTipText("重做上一步撤销的操作 (Ctrl+Y)");
        redoBtn.setEnabled(false);
        redoBtn.addActionListener(e -> redo());
        toolBar.add(redoBtn);

        toolBar.addSeparator();

        JButton zoomInBtn = new JButton(" 🔍+ 放大");
        zoomInBtn.setFont(UIUtils.plainFont());
        zoomInBtn.setToolTipText("放大画布 (Ctrl + Mouse Wheel Up / Ctrl + =)");
        zoomInBtn.addActionListener(e -> {
            zoomFactor = Math.min(3.0, zoomFactor + 0.1);
            adjustCanvasSize();
            canvasPanel.repaint();
        });
        toolBar.add(zoomInBtn);

        JButton zoomOutBtn = new JButton(" 🔍- 缩小");
        zoomOutBtn.setFont(UIUtils.plainFont());
        zoomOutBtn.setToolTipText("缩小画布 (Ctrl + Mouse Wheel Down / Ctrl + -)");
        zoomOutBtn.addActionListener(e -> {
            zoomFactor = Math.max(0.3, zoomFactor - 0.1);
            adjustCanvasSize();
            canvasPanel.repaint();
        });
        toolBar.add(zoomOutBtn);

        JButton zoomResetBtn = new JButton(" 100%");
        zoomResetBtn.setFont(UIUtils.plainFont());
        zoomResetBtn.setToolTipText("恢复原始大小 (Ctrl+0)");
        zoomResetBtn.addActionListener(e -> {
            zoomFactor = 1.0;
            adjustCanvasSize();
            canvasPanel.repaint();
        });
        toolBar.add(zoomResetBtn);

        toolBar.addSeparator();

        JButton exportBtn = new JButton(" 💾 导出图片");
        exportBtn.setFont(UIUtils.plainFont());
        exportBtn.addActionListener(e -> exportImage());
        toolBar.add(exportBtn);

        JButton importBtn = new JButton(" 📂 导入图表");
        importBtn.setFont(UIUtils.plainFont());
        importBtn.addActionListener(e -> importDiagram());
        toolBar.add(importBtn);

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
        JPanel bgPanel = new JPanel(new BorderLayout(4, 0));
        bgPanel.setOpaque(false);
        nodeBgCombo = new JComboBox<>(new String[]{"亮蓝色", "青绿绿", "淡明黄", "罗兰紫", "警示红", "极简灰", "纯透明"});
        nodeBgCombo.putClientProperty("JComponent.roundRect", true);
        JButton customBgBtn = new JButton("自定义");
        customBgBtn.setFont(UIUtils.plainFont().deriveFont(11f));
        customBgBtn.setMargin(new Insets(2, 4, 2, 4));
        bgPanel.add(nodeBgCombo, BorderLayout.CENTER);
        bgPanel.add(customBgBtn, BorderLayout.EAST);
        propPanel.add(bgPanel, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("节点边框颜色:"), getGbc(gbc, gridy++));
        JPanel borderColorPanel = new JPanel(new BorderLayout(4, 0));
        borderColorPanel.setOpaque(false);
        nodeBorderColorCombo = new JComboBox<>(new String[]{"深蓝色", "森林绿", "深黄色", "罗兰紫", "警示红", "极简灰", "经典黑"});
        nodeBorderColorCombo.putClientProperty("JComponent.roundRect", true);
        JButton customBorderColorBtn = new JButton("自定义");
        customBorderColorBtn.setFont(UIUtils.plainFont().deriveFont(11f));
        customBorderColorBtn.setMargin(new Insets(2, 4, 2, 4));
        borderColorPanel.add(nodeBorderColorCombo, BorderLayout.CENTER);
        borderColorPanel.add(customBorderColorBtn, BorderLayout.EAST);
        propPanel.add(borderColorPanel, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("节点边框类型:"), getGbc(gbc, gridy++));
        nodeBorderCombo = new JComboBox<>(new String[]{"实线边框", "虚线边框"});
        nodeBorderCombo.putClientProperty("JComponent.roundRect", true);
        propPanel.add(nodeBorderCombo, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("节点边框粗细:"), getGbc(gbc, gridy++));
        nodeBorderThicknessCombo = new JComboBox<>(new String[]{"细线 (1.5px)", "中等 (2.5px)", "粗线 (4.0px)"});
        nodeBorderThicknessCombo.putClientProperty("JComponent.roundRect", true);
        propPanel.add(nodeBorderThicknessCombo, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("文本字体颜色:"), getGbc(gbc, gridy++));
        JPanel textColorPanel = new JPanel(new BorderLayout(4, 0));
        textColorPanel.setOpaque(false);
        nodeTextColorCombo = new JComboBox<>(new String[]{"经典黑", "经典白", "警示红", "亮蓝色"});
        nodeTextColorCombo.putClientProperty("JComponent.roundRect", true);
        JButton customTextColorBtn = new JButton("自定义");
        customTextColorBtn.setFont(UIUtils.plainFont().deriveFont(11f));
        customTextColorBtn.setMargin(new Insets(2, 4, 2, 4));
        textColorPanel.add(nodeTextColorCombo, BorderLayout.CENTER);
        textColorPanel.add(customTextColorBtn, BorderLayout.EAST);
        propPanel.add(textColorPanel, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("字体大小与加粗:"), getGbc(gbc, gridy++));
        JPanel fontStylePanel = new JPanel(new BorderLayout(4, 0));
        fontStylePanel.setOpaque(false);
        nodeFontSizeSpinner = new JSpinner(new SpinnerNumberModel(12, 8, 36, 1));
        nodeBoldToggle = new JCheckBox("加粗");
        nodeBoldToggle.setFont(UIUtils.plainFont());
        nodeBoldToggle.setOpaque(false);
        fontStylePanel.add(nodeFontSizeSpinner, BorderLayout.CENTER);
        fontStylePanel.add(nodeBoldToggle, BorderLayout.EAST);
        propPanel.add(fontStylePanel, getGbc(gbc, gridy++));

        // --- 连线样式设置 ---
        propPanel.add(new JLabel("连线色彩:"), getGbc(gbc, gridy++));
        JPanel edgeColorPanel = new JPanel(new BorderLayout(4, 0));
        edgeColorPanel.setOpaque(false);
        edgeColorCombo = new JComboBox<>(new String[]{"经典黑", "亮蓝色", "青绿绿", "警示红"});
        edgeColorCombo.putClientProperty("JComponent.roundRect", true);
        JButton customEdgeColorBtn = new JButton("自定义");
        customEdgeColorBtn.setFont(UIUtils.plainFont().deriveFont(11f));
        customEdgeColorBtn.setMargin(new Insets(2, 4, 2, 4));
        edgeColorPanel.add(edgeColorCombo, BorderLayout.CENTER);
        edgeColorPanel.add(customEdgeColorBtn, BorderLayout.EAST);
        propPanel.add(edgeColorPanel, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("连线类型:"), getGbc(gbc, gridy++));
        edgeStrokeCombo = new JComboBox<>(new String[]{"实线", "虚线"});
        edgeStrokeCombo.putClientProperty("JComponent.roundRect", true);
        propPanel.add(edgeStrokeCombo, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("连线路径样式:"), getGbc(gbc, gridy++));
        edgeRoutingCombo = new JComboBox<>(new String[]{"直角折线", "直连实线", "贝塞尔曲线"});
        edgeRoutingCombo.putClientProperty("JComponent.roundRect", true);
        propPanel.add(edgeRoutingCombo, getGbc(gbc, gridy++));

        propPanel.add(new JLabel("文本在连线的位置 (0%-100%):"), getGbc(gbc, gridy++));
        edgeLabelPosSlider = new JSlider(0, 100, 50);
        edgeLabelPosSlider.setOpaque(false);
        edgeLabelPosSlider.putClientProperty("JComponent.roundRect", true);
        propPanel.add(edgeLabelPosSlider, getGbc(gbc, gridy++));

        gbc.gridy = gridy++;
        gbc.weighty = 1.0;
        propPanel.add(new JPanel(), gbc);

        // 声明 focus 适配器来跟踪输入状态以实现高级撤销
        class Typelistener extends FocusAdapter implements ActionListener {
            private boolean isTyping = false;
            
            void startTyping() {
                if (!isTyping) {
                    saveState();
                    isTyping = true;
                }
            }
            
            void reset() {
                isTyping = false;
            }

            @Override
            public void focusGained(FocusEvent e) {
                reset();
            }

            @Override
            public void focusLost(FocusEvent e) {
                reset();
            }

            @Override
            public void actionPerformed(ActionEvent e) {
                reset();
            }
        }
        
        Typelistener nameFieldListener = new Typelistener();
        nameField.addFocusListener(nameFieldListener);
        nameField.addActionListener(nameFieldListener);
        
        Typelistener edgeFieldListener = new Typelistener();
        edgeLabelField.addFocusListener(edgeFieldListener);
        edgeLabelField.addActionListener(edgeFieldListener);

        // 绑定字段监听
        nameField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (updatingProperties) return;
            if (selectedNode != null) {
                nameFieldListener.startTyping();
                selectedNode.name = nameField.getText().trim();
                canvasPanel.repaint();
            }
        }));

        edgeLabelField.getDocument().addDocumentListener(new SimpleDocumentListener(() -> {
            if (updatingProperties) return;
            if (selectedEdge != null) {
                edgeFieldListener.startTyping();
                selectedEdge.label = edgeLabelField.getText().trim();
                canvasPanel.repaint();
            }
        }));

        // 样式下拉绑定与自定义颜色绑定
        nodeBgCombo.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            saveState();
            String key = (String) nodeBgCombo.getSelectedItem();
            selectedNode.bgColor = COLOR_PRESETS.get(key);
            canvasPanel.repaint();
        });

        customBgBtn.addActionListener(e -> {
            if (selectedNode == null) return;
            Color chosen = JColorChooser.showDialog(getView(), "自定义背景填充色", selectedNode.bgColor);
            if (chosen != null) {
                saveState();
                selectedNode.bgColor = chosen;
                canvasPanel.repaint();
            }
        });

        nodeBorderColorCombo.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            saveState();
            String key = (String) nodeBorderColorCombo.getSelectedItem();
            selectedNode.borderColor = COLOR_PRESETS.get(key);
            canvasPanel.repaint();
        });

        customBorderColorBtn.addActionListener(e -> {
            if (selectedNode == null) return;
            Color chosen = JColorChooser.showDialog(getView(), "自定义边框颜色", selectedNode.borderColor);
            if (chosen != null) {
                saveState();
                selectedNode.borderColor = chosen;
                canvasPanel.repaint();
            }
        });

        nodeBorderCombo.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            saveState();
            selectedNode.isDashedBorder = nodeBorderCombo.getSelectedIndex() == 1;
            canvasPanel.repaint();
        });

        nodeBorderThicknessCombo.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            saveState();
            int idx = nodeBorderThicknessCombo.getSelectedIndex();
            selectedNode.borderThickness = idx == 0 ? 1.5f : (idx == 1 ? 2.5f : 4.0f);
            canvasPanel.repaint();
        });

        nodeTextColorCombo.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            saveState();
            String key = (String) nodeTextColorCombo.getSelectedItem();
            selectedNode.textColor = COLOR_PRESETS.get(key);
            canvasPanel.repaint();
        });

        customTextColorBtn.addActionListener(e -> {
            if (selectedNode == null) return;
            Color chosen = JColorChooser.showDialog(getView(), "自定义文本颜色", selectedNode.textColor);
            if (chosen != null) {
                saveState();
                selectedNode.textColor = chosen;
                canvasPanel.repaint();
            }
        });

        nodeFontSizeSpinner.addChangeListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            saveState();
            selectedNode.fontSize = (Integer) nodeFontSizeSpinner.getValue();
            canvasPanel.repaint();
        });

        nodeBoldToggle.addActionListener(e -> {
            if (updatingProperties || selectedNode == null) return;
            saveState();
            selectedNode.isBold = nodeBoldToggle.isSelected();
            canvasPanel.repaint();
        });

        edgeColorCombo.addActionListener(e -> {
            if (updatingProperties || selectedEdge == null) return;
            saveState();
            String key = (String) edgeColorCombo.getSelectedItem();
            selectedEdge.lineColor = COLOR_PRESETS.get(key);
            canvasPanel.repaint();
        });

        customEdgeColorBtn.addActionListener(e -> {
            if (selectedEdge == null) return;
            Color chosen = JColorChooser.showDialog(getView(), "自定义连线颜色", selectedEdge.lineColor);
            if (chosen != null) {
                saveState();
                selectedEdge.lineColor = chosen;
                canvasPanel.repaint();
            }
        });

        edgeStrokeCombo.addActionListener(e -> {
            if (updatingProperties || selectedEdge == null) return;
            saveState();
            selectedEdge.isDashed = edgeStrokeCombo.getSelectedIndex() == 1;
            canvasPanel.repaint();
        });

        edgeRoutingCombo.addActionListener(e -> {
            if (updatingProperties || selectedEdge == null) return;
            saveState();
            int idx = edgeRoutingCombo.getSelectedIndex();
            selectedEdge.routingType = idx == 0 ? "manhattan" : (idx == 1 ? "straight" : "bezier");
            canvasPanel.repaint();
        });

        edgeLabelPosSlider.addChangeListener(e -> {
            if (updatingProperties || selectedEdge == null) return;
            if (!edgeLabelPosSlider.getValueIsAdjusting()) {
                saveState();
            }
            selectedEdge.labelPosition = edgeLabelPosSlider.getValue() / 100.0;
            canvasPanel.repaint();
        });

        root.add(propPanel, BorderLayout.EAST);

        // 4. 中间画布区
        canvasPanel = new CanvasPanel(this);
        scrollPane = new JScrollPane(canvasPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setUnitIncrement(16); // 增加纵向滚轮单位以提高顺滑度
        scrollPane.getHorizontalScrollBar().setUnitIncrement(16); // 增加横向滚轮单位
        root.add(scrollPane, BorderLayout.CENTER);

        // 全局快捷键注册以支持撤销和重做 (Ctrl+Z, Ctrl+Y, Ctrl+Shift+Z)
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undoAction");
        root.getActionMap().put("undoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                undo();
            }
        });

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redoAction");
        root.getActionMap().put("redoAction", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                redo();
            }
        });

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "redoAction");

        // 全局快捷键注册以支持画布缩放 (Ctrl+=, Ctrl+-, Ctrl+0)
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK), "zoomIn");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, InputEvent.CTRL_DOWN_MASK), "zoomIn");
        root.getActionMap().put("zoomIn", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                zoomFactor = Math.min(3.0, zoomFactor + 0.1);
                adjustCanvasSize();
                canvasPanel.repaint();
            }
        });

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK), "zoomOut");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, InputEvent.CTRL_DOWN_MASK), "zoomOut");
        root.getActionMap().put("zoomOut", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                zoomFactor = Math.max(0.3, zoomFactor - 0.1);
                adjustCanvasSize();
                canvasPanel.repaint();
            }
        });

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK), "zoomReset");
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_NUMPAD0, InputEvent.CTRL_DOWN_MASK), "zoomReset");
        root.getActionMap().put("zoomReset", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent evt) {
                zoomFactor = 1.0;
                adjustCanvasSize();
                canvasPanel.repaint();
            }
        });

        updatePropertyPanel();

        return root;
    }

    private void addAccordionGroup(String title, JPanel contentPanel, JPanel container) {
        JButton headerBtn = new JButton("▼ " + title);
        headerBtn.setFont(UIUtils.titleFont().deriveFont(12f));
        headerBtn.setHorizontalAlignment(SwingConstants.LEFT);
        headerBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        headerBtn.setPreferredSize(new Dimension(170, 30));
        headerBtn.putClientProperty("JButton.buttonType", "square");
        headerBtn.setFocusPainted(false);
        headerBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        contentPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        headerBtn.addActionListener(e -> {
            boolean visible = !contentPanel.isVisible();
            contentPanel.setVisible(visible);
            headerBtn.setText((visible ? "▼ " : "▶ ") + title);
            container.revalidate();
            container.repaint();
        });
        
        container.add(headerBtn);
        container.add(contentPanel);
        
        Component strut = Box.createVerticalStrut(2);
        if (strut instanceof JComponent) {
            ((JComponent) strut).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        container.add(strut);
    }

    private GridBagConstraints getGbc(GridBagConstraints gbc, int y) {
        gbc.gridy = y;
        gbc.weighty = 0.0;
        return gbc;
    }

    void setMode(Mode mode) {
        this.currentMode = mode;
        clearSelection();
        canvasPanel.repaint();
    }

    void clearSelection() {
        selectedNodes.clear();
        selectedNode = null;
        selectedEdge = null;
        updatePropertyPanel();
    }

    void updatePropertyPanel() {
        updatingProperties = true;

        if (selectedNode != null) {
            nameField.setEditable(true);
            nameField.setText(selectedNode.name);
            idField.setText(selectedNode.id);
            edgeLabelField.setEditable(false);
            edgeLabelField.setText("");

            // 启用并定位节点样式
            nodeBgCombo.setEnabled(true);
            nodeBorderColorCombo.setEnabled(true);
            nodeBorderCombo.setEnabled(true);
            nodeBorderThicknessCombo.setEnabled(true);
            nodeTextColorCombo.setEnabled(true);
            nodeFontSizeSpinner.setEnabled(true);
            nodeBoldToggle.setEnabled(true);
            
            // 匹配背景色、边框色、文本色、字号和粗体
            nodeBgCombo.setSelectedItem(getKeyByColor(selectedNode.bgColor));
            nodeBorderColorCombo.setSelectedItem(getKeyByColor(selectedNode.borderColor));
            nodeBorderCombo.setSelectedIndex(selectedNode.isDashedBorder ? 1 : 0);
            nodeBorderThicknessCombo.setSelectedIndex(selectedNode.borderThickness == 1.5f ? 0 : (selectedNode.borderThickness == 2.5f ? 1 : 2));
            nodeTextColorCombo.setSelectedItem(getKeyByColor(selectedNode.textColor));
            nodeFontSizeSpinner.setValue(selectedNode.fontSize);
            nodeBoldToggle.setSelected(selectedNode.isBold);

            // 禁用连线样式
            edgeColorCombo.setEnabled(false);
            edgeStrokeCombo.setEnabled(false);
            edgeRoutingCombo.setEnabled(false);
            edgeLabelPosSlider.setEnabled(false);

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
            edgeLabelPosSlider.setEnabled(true);

            edgeColorCombo.setSelectedItem(getKeyByColor(selectedEdge.lineColor));
            edgeStrokeCombo.setSelectedIndex(selectedEdge.isDashed ? 1 : 0);
            edgeRoutingCombo.setSelectedItem("manhattan".equals(selectedEdge.routingType) ? "直角折线" : 
                                            ("straight".equals(selectedEdge.routingType) ? "直连实线" : "贝塞尔曲线"));
            edgeLabelPosSlider.setValue((int) (selectedEdge.labelPosition * 100));

            // 禁用节点样式
            nodeBgCombo.setEnabled(false);
            nodeBorderColorCombo.setEnabled(false);
            nodeBorderCombo.setEnabled(false);
            nodeBorderThicknessCombo.setEnabled(false);
            nodeTextColorCombo.setEnabled(false);
            nodeFontSizeSpinner.setEnabled(false);
            nodeBoldToggle.setEnabled(false);

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
            nodeBorderColorCombo.setEnabled(false);
            nodeBorderCombo.setEnabled(false);
            nodeBorderThicknessCombo.setEnabled(false);
            nodeTextColorCombo.setEnabled(false);
            nodeFontSizeSpinner.setEnabled(false);
            nodeBoldToggle.setEnabled(false);
            edgeColorCombo.setEnabled(false);
            edgeStrokeCombo.setEnabled(false);
            edgeRoutingCombo.setEnabled(false);
            edgeLabelPosSlider.setEnabled(false);
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
            saveState();
            nodes.clear();
            edges.clear();
            clearSelection();
            canvasPanel.repaint();
        }
    }

    // --- 序列化/反序列化相关 DTO 与方法 ---


    String serializeToJson() throws Exception {
        DiagramData data = new DiagramData();
        data.nodes = new ArrayList<>();
        for (FlowNode node : nodes) {
            NodeDto n = new NodeDto();
            n.id = node.id;
            n.type = node.type;
            n.name = node.name;
            n.x = node.x;
            n.y = node.y;
            n.w = node.w;
            n.h = node.h;
            n.bgColor = node.bgColor.getRGB();
            n.borderColor = node.borderColor.getRGB();
            n.textColor = node.textColor.getRGB();
            n.fontSize = node.fontSize;
            n.isBold = node.isBold;
            n.isDashedBorder = node.isDashedBorder;
            n.borderThickness = node.borderThickness;
            data.nodes.add(n);
        }
        data.edges = new ArrayList<>();
        for (FlowEdge edge : edges) {
            EdgeDto e = new EdgeDto();
            e.id = edge.id;
            e.label = edge.label;
            e.sourceId = edge.source.id;
            e.targetId = edge.target.id;
            e.sourcePort = edge.sourcePort;
            e.targetPort = edge.targetPort;
            e.sourceRelX = edge.sourceRelX;
            e.sourceRelY = edge.sourceRelY;
            e.targetRelX = edge.targetRelX;
            e.targetRelY = edge.targetRelY;
            e.lineColor = edge.lineColor.getRGB();
            e.isDashed = edge.isDashed;
            e.routingType = edge.routingType;
            e.labelPosition = edge.labelPosition;
            e.waypoints = new ArrayList<>();
            if (edge.waypoints != null) {
                for (Point wp : edge.waypoints) {
                    e.waypoints.add(new PointDto(wp.x, wp.y));
                }
            }
            data.edges.add(e);
        }
        ObjectMapper mapper = new ObjectMapper();
        return mapper.writeValueAsString(data);
    }

    private void deserializeFromJson(String json) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DiagramData data = mapper.readValue(json, DiagramData.class);
        
        // Restore nodes
        Map<String, FlowNode> nodeMap = new HashMap<>();
        List<FlowNode> newNodes = new ArrayList<>();
        for (NodeDto nd : data.nodes) {
            FlowNode node = new FlowNode(nd.type, nd.id, nd.name, nd.x, nd.y);
            node.w = nd.w;
            node.h = nd.h;
            node.bgColor = new Color(nd.bgColor, true);
            node.borderColor = new Color(nd.borderColor, true);
            node.textColor = new Color(nd.textColor, true);
            node.fontSize = nd.fontSize;
            node.isBold = nd.isBold;
            node.isDashedBorder = nd.isDashedBorder;
            node.borderThickness = nd.borderThickness;
            newNodes.add(node);
            nodeMap.put(node.id, node);
        }

        // Restore edges
        List<FlowEdge> newEdges = new ArrayList<>();
        for (EdgeDto ed : data.edges) {
            FlowNode src = nodeMap.get(ed.sourceId);
            FlowNode tgt = nodeMap.get(ed.targetId);
            if (src != null && tgt != null) {
                FlowEdge edge = new FlowEdge(ed.id, ed.label, src, tgt, ed.sourcePort, ed.targetPort);
                if (ed.sourceRelX != null) edge.sourceRelX = ed.sourceRelX;
                if (ed.sourceRelY != null) edge.sourceRelY = ed.sourceRelY;
                if (ed.targetRelX != null) edge.targetRelX = ed.targetRelX;
                if (ed.targetRelY != null) edge.targetRelY = ed.targetRelY;
                edge.lineColor = new Color(ed.lineColor, true);
                edge.isDashed = ed.isDashed;
                edge.routingType = ed.routingType;
                edge.labelPosition = ed.labelPosition;
                edge.waypoints = new ArrayList<>();
                if (ed.waypoints != null) {
                    for (PointDto p : ed.waypoints) {
                        edge.waypoints.add(new Point(p.x, p.y));
                    }
                }
                newEdges.add(edge);
            }
        }

        this.nodes.clear();
        this.nodes.addAll(newNodes);
        this.edges.clear();
        this.edges.addAll(newEdges);
        clearSelection();
        adjustCanvasSize();
        canvasPanel.repaint();
    }

    private void importDiagram() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("可编辑 PNG图片 / JSON图表数据 (*.png, *.json)", "png", "json"));
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File src = chooser.getSelectedFile();
            try {
                byte[] fileBytes = Files.readAllBytes(src.toPath());
                String content = new String(fileBytes, StandardCharsets.UTF_8);
                
                String json = null;
                int index = content.indexOf("\n--FLOWCHART_DATA_START--\n");
                if (index != -1) {
                    json = content.substring(index + "\n--FLOWCHART_DATA_START--\n".length()).trim();
                } else if (src.getName().toLowerCase().endsWith(".json")) {
                    json = content.trim();
                }

                if (json != null && !json.isEmpty()) {
                    deserializeFromJson(json);
                    UIUtils.info(getView(), "导入成功，已恢复可编辑状态！");
                } else {
                    UIUtils.error(getView(), "导入失败：选择的文件不包含有效的图表编辑数据！");
                }
            } catch (Exception e) {
                UIUtils.error(getView(), "导入失败:\n" + e.getMessage());
            }
        }
    }

    private void exportImage() {
        if (nodes.isEmpty()) {
            UIUtils.error(getView(), "画布为空，无法导出图片！");
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("flowchart.png"));
        chooser.setFileFilter(new FileNameExtensionFilter("可编辑 PNG图片 (*.png)", "png"));
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
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                byte[] pngBytes = baos.toByteArray();

                String json = serializeToJson();
                byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

                try (FileOutputStream fos = new FileOutputStream(dest)) {
                    fos.write(pngBytes);
                    fos.write("\n--FLOWCHART_DATA_START--\n".getBytes(StandardCharsets.UTF_8));
                    fos.write(jsonBytes);
                }

                UIUtils.info(getView(), "成功导出可再次编辑的高清图片！");
            } catch (Exception e) {
                UIUtils.error(getView(), "导出失败:\n" + e.getMessage());
            }
        }
    }

    public void saveState() {
        try {
            String state = serializeToJson();
            if (undoStack.isEmpty() || !undoStack.peek().equals(state)) {
                undoStack.push(state);
                redoStack.clear();
                updateUndoRedoButtons();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void undo() {
        if (undoStack.isEmpty()) return;
        try {
            String currentState = serializeToJson();
            redoStack.push(currentState);
            String prevState = undoStack.pop();
            deserializeFromJson(prevState);
            updateUndoRedoButtons();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void redo() {
        if (redoStack.isEmpty()) return;
        try {
            String currentState = serializeToJson();
            undoStack.push(currentState);
            String nextState = redoStack.pop();
            deserializeFromJson(nextState);
            updateUndoRedoButtons();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void updateUndoRedoButtons() {
        if (undoBtn != null) {
            undoBtn.setEnabled(!undoStack.isEmpty());
        }
        if (redoBtn != null) {
            redoBtn.setEnabled(!redoStack.isEmpty());
        }
    }

    private void autoLayout() {
        if (nodes.isEmpty()) return;
        saveState();

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

    void addNewNodeAt(String shapeType, Point p) {
        saveState();
        String name = "节点";
        switch (shapeType) {
            case FlowNode.TYPE_START_END: name = "起止"; break;
            case FlowNode.TYPE_DECISION: name = "是否合格?"; break;
            case FlowNode.TYPE_DATA: name = "输入数据"; break;
            case FlowNode.TYPE_DATABASE: name = "数据库"; break;
            case FlowNode.TYPE_CLOUD: name = "云服务"; break;
            case FlowNode.TYPE_PREDEFINED: name = "预设子过程"; break;
            case FlowNode.TYPE_DOCUMENT: name = "文档内容"; break;
            case FlowNode.TYPE_PREPARATION: name = "准备工作"; break;
            case FlowNode.TYPE_MANUAL_INPUT: name = "手工输入"; break;
            case FlowNode.TYPE_ANNOTATION: name = "注释文本"; break;
            case FlowNode.TYPE_TERMINATOR: name = "终端"; break;
            case FlowNode.TYPE_CARD: name = "卡片登记"; break;
            case FlowNode.TYPE_DELAY: name = "延时符"; break;
            case FlowNode.TYPE_DISPLAY: name = "显示器"; break;
            case FlowNode.TYPE_INTERNAL_STORAGE: name = "内部存储"; break;
            case FlowNode.TYPE_OFF_PAGE_CONNECTOR: name = "离页连接"; break;
            case FlowNode.TYPE_LIFELINE: name = "对象生命线"; break;
            case FlowNode.TYPE_ACTOR: name = "用户"; break;
            case FlowNode.TYPE_ACTIVATION: name = "激活条"; break;
        }

        String id = "Node_" + UUID.randomUUID().toString().substring(0, 8);
        FlowNode node = new FlowNode(shapeType, id, name, p.x, p.y);
        node.x = p.x;
        node.y = p.y;
        nodes.add(node);
        
        selectedNodes.clear();
        selectedNodes.add(node);
        selectedNode = node;
        selectedEdge = null;
        
        adjustCanvasSize();
        updatePropertyPanel();
        canvasPanel.repaint();
    }

    void adjustCanvasSize() {
        int maxX = 1000;
        int maxY = 800;
        for (FlowNode n : nodes) {
            if (n.x + n.w + 150 > maxX) maxX = n.x + n.w + 150;
            if (n.y + n.h + 150 > maxY) maxY = n.y + n.h + 150;
        }
        canvasPanel.setPreferredSize(new Dimension((int) (maxX * zoomFactor), (int) (maxY * zoomFactor)));
        canvasPanel.revalidate();
    }



    // ==========================================
    // 侧边图形备选列表小组件 ShapeDragLabel
    // ==========================================
    private static class ShapeDragLabel extends JLabel {
        private final String shapeType;

        ShapeDragLabel(String emoji, String name, String type) {
            super(emoji, SwingConstants.CENTER);
            this.shapeType = type;
            setToolTipText(name);
            setFont(UIUtils.plainFont().deriveFont(15f));
            setPreferredSize(new Dimension(72, 32));
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                    BorderFactory.createEmptyBorder(3, 3, 3, 3)
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



    /** 极简 DocumentListener 适配器 */
    private static class SimpleDocumentListener implements DocumentListener {
        private final Runnable r;
        SimpleDocumentListener(Runnable r) { this.r = r; }
        public void insertUpdate(DocumentEvent e) { r.run(); }
        public void removeUpdate(DocumentEvent e) { r.run(); }
        public void changedUpdate(DocumentEvent e) { r.run(); }
    }

    /** 追踪视口宽度的滚动面板容器 */
    private static class ScrollablePanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }
        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 10;
        }
        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 100;
        }
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
