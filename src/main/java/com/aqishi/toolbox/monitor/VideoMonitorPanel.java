package com.aqishi.toolbox.monitor;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频监控面板。
 * 左侧设备树，右侧支持 1/4/5/9/16/25 窗口布局的视频播放区域。
 * 视频单元格支持：双击全屏、拖拽分配、单击选中。
 */
public class VideoMonitorPanel extends ToolPanel {

    /** 所有支持的布局模式：[显示数量, 列数, 行数] */
    private static final int[][] LAYOUTS = {
            {1,  1, 1},
            {4,  2, 2},
            {5,  3, 2},  // 5窗口：1大+4小(特殊布局)
            {9,  3, 3},
            {16, 4, 4},
            {25, 5, 5},
    };
    private static final String[] LAYOUT_LABELS = {"1", "4", "5", "9", "16", "25"};

    // 视频单元格公用颜色常量（抽提到外层以兼容 JDK 8 内部类限制）
    private static final Color CELL_BG_COLOR    = new Color(22, 25, 30);
    private static final Color CELL_SEL_COLOR   = new Color(0, 120, 212);
    private static final Color CELL_HOVER_COLOR = new Color(40, 45, 55);
    private static final Color CELL_ACTIVE_BG   = new Color(15, 20, 28);
    private static final Color CELL_TEXT_DIM    = new Color(120, 130, 145);
    private static final Color CELL_TEXT_BRIGHT = new Color(200, 210, 220);

    private JPanel videoGrid;
    private final List<VideoCell> cells = new ArrayList<>();
    private int currentLayout = 0;   // 当前预设布局索引，-1 代表自定义
    private VideoCell selectedCell = null;
    private int customRows = 3;       // 自定义布局行数
    private int customCols = 3;       // 自定义布局列数
    private JComboBox<String> layoutCombo;  // 提升为字段供内部方法更新

    public VideoMonitorPanel() {
        super("开发工具", "视频监控",
                "视频", "监控", "摄像头", "Video", "camera", "player",
                "RTSP", "直播", "多画面", "分屏");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(0, 0));

        // ==================== 顶部工具栏 ====================
        JPanel toolbar = buildToolbar();
        root.add(toolbar, BorderLayout.NORTH);

        // ==================== 主体：左右分栏 ====================
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildDeviceTree(), buildVideoArea());
        splitPane.setDividerLocation(220);
        splitPane.setDividerSize(4);
        splitPane.setResizeWeight(0.0);
        root.add(splitPane, BorderLayout.CENTER);

        // 恢复上次保存的布局
        restoreLayout();

        return root;
    }

    // ==================== 顶部工具栏 ====================
    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                new javax.swing.border.EmptyBorder(6, 10, 6, 10)));

        JLabel titleLbl = new JLabel("  📹  视频监控");
        titleLbl.setFont(UIUtils.titleFont().deriveFont(Font.BOLD, 14f));
        bar.add(titleLbl, BorderLayout.WEST);

        // 布局切换下拉框（含自定义选项）
        JPanel layoutPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JLabel layoutLbl = new JLabel("分屏布局:");
        layoutLbl.setFont(UIUtils.plainFont());
        layoutPanel.add(layoutLbl);

        // 预设选项 + 自定义
        String[] comboItems = new String[LAYOUT_LABELS.length + 1];
        for (int i = 0; i < LAYOUT_LABELS.length; i++) {
            comboItems[i] = LAYOUT_LABELS[i] + " 画面";
        }
        comboItems[LAYOUT_LABELS.length] = "自定义...";

        layoutCombo = new JComboBox<>(comboItems);
        layoutCombo.setFont(UIUtils.plainFont().deriveFont(12f));
        layoutCombo.setPreferredSize(new Dimension(110, 28));
        layoutCombo.setSelectedIndex(0);
        layoutCombo.addActionListener(e -> {
            int idx = layoutCombo.getSelectedIndex();
            if (idx == LAYOUT_LABELS.length) {
                // 选择「自定义...」时弹出对话框
                showCustomLayoutDialog();
            } else {
                applyLayout(idx);
            }
        });
        layoutPanel.add(layoutCombo);

        // 保存布局按钮
        layoutPanel.add(Box.createHorizontalStrut(2));
        JButton saveBtn = UIUtils.button("保存布局", 70);
        saveBtn.setToolTipText("将当前分屏布局保存，下次启动自动恢复");
        saveBtn.addActionListener(e -> saveLayout());
        layoutPanel.add(saveBtn);

        // 全部清空按钮
        layoutPanel.add(Box.createHorizontalStrut(2));
        JButton clearBtn = UIUtils.button("清空", 55);
        clearBtn.addActionListener(e -> clearAllCells());
        layoutPanel.add(clearBtn);

        bar.add(layoutPanel, BorderLayout.EAST);
        return bar;
    }

    // ==================== 自定义布局对话框 ====================
    /**
     * 弹出行列输入对话框，确认后应用并更新下拉框标签。
     */
    private void showCustomLayoutDialog() {
        JSpinner rowSpinner = new JSpinner(new SpinnerNumberModel(customRows, 1, 10, 1));
        JSpinner colSpinner = new JSpinner(new SpinnerNumberModel(customCols, 1, 10, 1));
        rowSpinner.setPreferredSize(new Dimension(60, 28));
        colSpinner.setPreferredSize(new Dimension(60, 28));

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("行数 (1-10):"), gbc);
        gbc.gridx = 1;
        panel.add(rowSpinner, gbc);
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("列数 (1-10):"), gbc);
        gbc.gridx = 1;
        panel.add(colSpinner, gbc);

        Window owner = SwingUtilities.getWindowAncestor(videoGrid);
        int result = JOptionPane.showConfirmDialog(
                owner, panel, "自定义布局",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            customRows = (Integer) rowSpinner.getValue();
            customCols = (Integer) colSpinner.getValue();
            applyCustomLayout(customRows, customCols);
            // 更新下拉框：将「自定义...」替换为实际尺寸标签
            String label = customRows + "×" + customCols + " 自定义";
            if (layoutCombo.getItemCount() > LAYOUT_LABELS.length
                    && layoutCombo.getItemAt(LAYOUT_LABELS.length).startsWith("自定义")) {
                layoutCombo.removeItemAt(LAYOUT_LABELS.length);
            }
            layoutCombo.addItem(label);
            layoutCombo.setSelectedIndex(layoutCombo.getItemCount() - 1);
        } else {
            // 取消时回到上一个选中项
            if (currentLayout >= 0 && currentLayout < LAYOUT_LABELS.length) {
                layoutCombo.setSelectedIndex(currentLayout);
            } else {
                layoutCombo.setSelectedIndex(layoutCombo.getItemCount() - 1);
            }
        }
    }

    // ==================== 布局持久化 ====================
    /** 保存当前布局到配置文件 */
    private void saveLayout() {
        if (currentLayout == -1) {
            // 自定义布局
            ConfigManager.set("monitor.layout", "custom");
            ConfigManager.set("monitor.layout.rows", String.valueOf(customRows));
            ConfigManager.set("monitor.layout.cols", String.valueOf(customCols));
        } else {
            ConfigManager.set("monitor.layout", String.valueOf(currentLayout));
            ConfigManager.set("monitor.layout.rows", "");
            ConfigManager.set("monitor.layout.cols", "");
        }
        ConfigManager.save();
        JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(videoGrid),
                "布局已保存，下次启动将自动恢复。",
                "保存成功", JOptionPane.INFORMATION_MESSAGE);
    }

    /** 启动时从配置文件恢复布局 */
    private void restoreLayout() {
        String saved = ConfigManager.get("monitor.layout", "0");
        if ("custom".equals(saved)) {
            customRows = ConfigManager.getInt("monitor.layout.rows", 3);
            customCols = ConfigManager.getInt("monitor.layout.cols", 3);
            applyCustomLayout(customRows, customCols);
            // 更新下拉框显示
            if (layoutCombo != null) {
                String label = customRows + "×" + customCols + " 自定义";
                if (layoutCombo.getItemAt(LAYOUT_LABELS.length).equals("自定义...")) {
                    layoutCombo.removeItemAt(LAYOUT_LABELS.length);
                    layoutCombo.addItem(label);
                }
                layoutCombo.setSelectedIndex(layoutCombo.getItemCount() - 1);
            }
        } else {
            int idx = 0;
            try { idx = Integer.parseInt(saved); } catch (NumberFormatException ignore) {}
            idx = Math.max(0, Math.min(idx, LAYOUT_LABELS.length - 1));
            applyLayout(idx);
            if (layoutCombo != null) {
                layoutCombo.setSelectedIndex(idx);
            }
        }
    }

    // ==================== 左侧设备树 ====================
    private JComponent buildDeviceTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("📡 所有设备");

        // 示例设备组织结构
        DefaultMutableTreeNode area1 = new DefaultMutableTreeNode("🏢 一楼大厅");
        area1.add(cameraNode("门口摄像头-01", "rtsp://192.168.1.101:554/stream"));
        area1.add(cameraNode("电梯口-02",     "rtsp://192.168.1.102:554/stream"));
        area1.add(cameraNode("前台区域-03",   "rtsp://192.168.1.103:554/stream"));
        root.add(area1);

        DefaultMutableTreeNode area2 = new DefaultMutableTreeNode("🏢 二楼办公区");
        area2.add(cameraNode("会议室A-04",    "rtsp://192.168.1.104:554/stream"));
        area2.add(cameraNode("走廊东段-05",   "rtsp://192.168.1.105:554/stream"));
        area2.add(cameraNode("财务室-06",     "rtsp://192.168.1.106:554/stream"));
        root.add(area2);

        DefaultMutableTreeNode area3 = new DefaultMutableTreeNode("🅿️ 停车场");
        area3.add(cameraNode("入口道闸-07",   "rtsp://192.168.1.107:554/stream"));
        area3.add(cameraNode("出口道闸-08",   "rtsp://192.168.1.108:554/stream"));
        area3.add(cameraNode("B1层全景-09",   "rtsp://192.168.1.109:554/stream"));
        area3.add(cameraNode("B2层全景-10",   "rtsp://192.168.1.110:554/stream"));
        root.add(area3);

        DefaultMutableTreeNode area4 = new DefaultMutableTreeNode("🔒 安防周界");
        area4.add(cameraNode("北围墙-11",     "rtsp://192.168.1.111:554/stream"));
        area4.add(cameraNode("南围墙-12",     "rtsp://192.168.1.112:554/stream"));
        area4.add(cameraNode("东门岗亭-13",   "rtsp://192.168.1.113:554/stream"));
        area4.add(cameraNode("西门岗亭-14",   "rtsp://192.168.1.114:554/stream"));
        root.add(area4);

        JTree tree = new JTree(root);
        tree.setFont(UIUtils.plainFont().deriveFont(12f));
        tree.setRowHeight(26);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.expandRow(0);
        tree.expandRow(1);

        // 双击节点 -> 分配到当前选中格（或下一个空格）
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path == null) return;
                    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                    if (node.getUserObject() instanceof CameraInfo) {
                        CameraInfo cam = (CameraInfo) node.getUserObject();
                        assignCameraToCell(cam);
                    }
                }
            }
        });

        // 自定义渲染器：区分设备组和摄像头叶子节点
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                Object obj = ((DefaultMutableTreeNode) value).getUserObject();
                if (obj instanceof CameraInfo) {
                    setText("📷 " + ((CameraInfo) obj).name);
                }
                return this;
            }
        });

        JScrollPane sp = new JScrollPane(tree);
        sp.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1,
                UIManager.getColor("Component.borderColor")));
        sp.setPreferredSize(new Dimension(220, 0));

        // 树顶部标题
        JPanel wrapper = new JPanel(new BorderLayout());
        JLabel treeTitleLbl = new JLabel("  设备列表", JLabel.LEFT);
        treeTitleLbl.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 12f));
        treeTitleLbl.setBorder(new javax.swing.border.EmptyBorder(6, 6, 6, 0));
        treeTitleLbl.setOpaque(true);
        treeTitleLbl.setBackground(UIManager.getColor("Panel.background"));
        treeTitleLbl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                new javax.swing.border.EmptyBorder(5, 8, 5, 8)));
        wrapper.add(treeTitleLbl, BorderLayout.NORTH);
        wrapper.add(sp, BorderLayout.CENTER);
        return wrapper;
    }

    private DefaultMutableTreeNode cameraNode(String name, String url) {
        return new DefaultMutableTreeNode(new CameraInfo(name, url));
    }

    // ==================== 右侧视频区 ====================
    private JComponent buildVideoArea() {
        videoGrid = new JPanel();
        videoGrid.setBackground(new Color(18, 18, 20));
        videoGrid.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JScrollPane sp = new JScrollPane(videoGrid);
        sp.setBorder(null);
        sp.getViewport().setBackground(new Color(18, 18, 20));
        return sp;
    }

    // ==================== 布局切换 ====================
    /** 应用预设布局 */
    private void applyLayout(int layoutIdx) {
        this.currentLayout = layoutIdx;
        int[] layout = LAYOUTS[layoutIdx];
        int count = layout[0];
        int cols  = layout[1];
        int rows  = layout[2];

        List<CameraInfo> prevCameras = collectCameras();
        cells.clear();
        videoGrid.removeAll();

        if (count == 5) {
            buildLayout5();
        } else {
            videoGrid.setLayout(new GridLayout(rows, cols, 3, 3));
            for (int i = 0; i < count; i++) {
                VideoCell cell = new VideoCell(i + 1);
                cells.add(cell);
                videoGrid.add(cell);
            }
        }

        restoreCameras(prevCameras);
        if (!cells.isEmpty()) selectCell(cells.get(0));
        videoGrid.revalidate();
        videoGrid.repaint();
    }

    /** 应用自定义行列布局 */
    private void applyCustomLayout(int rows, int cols) {
        this.currentLayout = -1;  // 标记为自定义
        int count = rows * cols;

        List<CameraInfo> prevCameras = collectCameras();
        cells.clear();
        videoGrid.removeAll();

        videoGrid.setLayout(new GridLayout(rows, cols, 3, 3));
        for (int i = 0; i < count; i++) {
            VideoCell cell = new VideoCell(i + 1);
            cells.add(cell);
            videoGrid.add(cell);
        }

        restoreCameras(prevCameras);
        if (!cells.isEmpty()) selectCell(cells.get(0));
        videoGrid.revalidate();
        videoGrid.repaint();
    }

    /** 收集当前所有格的摄像头分配 */
    private List<CameraInfo> collectCameras() {
        List<CameraInfo> list = new ArrayList<>();
        for (VideoCell cell : cells) {
            list.add(cell.getCamera());
        }
        return list;
    }

    /** 将之前的摄像头分配按序恢复到新格中 */
    private void restoreCameras(List<CameraInfo> prevCameras) {
        for (int i = 0; i < Math.min(prevCameras.size(), cells.size()); i++) {
            if (prevCameras.get(i) != null) {
                cells.get(i).setCamera(prevCameras.get(i));
            }
        }
    }

    /** 5画面特殊布局（1大+4小） */
    private void buildLayout5() {
        videoGrid.setLayout(new BorderLayout(3, 3));

        VideoCell mainCell = new VideoCell(1);
        cells.add(mainCell);

        JPanel rightPanel = new JPanel(new GridLayout(2, 2, 3, 3));
        rightPanel.setBackground(new Color(18, 18, 20));
        rightPanel.setPreferredSize(new Dimension(240, 0));

        for (int i = 1; i <= 4; i++) {
            VideoCell cell = new VideoCell(i + 1);
            cells.add(cell);
            rightPanel.add(cell);
        }

        videoGrid.add(mainCell, BorderLayout.CENTER);
        videoGrid.add(rightPanel, BorderLayout.EAST);
    }

    // ==================== 单元格操作 ====================
    private void selectCell(VideoCell cell) {
        if (selectedCell != null) selectedCell.setSelected(false);
        selectedCell = cell;
        if (selectedCell != null) selectedCell.setSelected(true);
    }

    private void assignCameraToCell(CameraInfo cam) {
        // 优先分配到选中格，否则找第一个空格
        if (selectedCell != null) {
            selectedCell.setCamera(cam);
            // 自动移到下一格
            int idx = cells.indexOf(selectedCell);
            if (idx < cells.size() - 1) {
                selectCell(cells.get(idx + 1));
            }
        } else {
            for (VideoCell cell : cells) {
                if (cell.getCamera() == null) {
                    cell.setCamera(cam);
                    selectCell(cell);
                    return;
                }
            }
        }
    }

    private void clearAllCells() {
        for (VideoCell cell : cells) {
            cell.setCamera(null);
        }
        videoGrid.repaint();
    }

    // ==================== 内部类：视频单元格 ====================
    private class VideoCell extends JPanel {
        private final int index;
        private CameraInfo camera;
        private boolean selected;
        private boolean hovered;

        VideoCell(int index) {
            this.index = index;
            setOpaque(false);
            setBackground(CELL_BG_COLOR);
            setMinimumSize(new Dimension(100, 80));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && camera != null) {
                        openFullscreen();
                    } else {
                        selectCell(VideoCell.this);
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }
            });

            // 右键菜单
            JPopupMenu menu = new JPopupMenu();
            JMenuItem clearItem = new JMenuItem("清除此通道");
            clearItem.addActionListener(e -> setCamera(null));
            JMenuItem fullItem = new JMenuItem("全屏查看");
            fullItem.addActionListener(e -> openFullscreen());
            menu.add(fullItem);
            menu.addSeparator();
            menu.add(clearItem);
            setComponentPopupMenu(menu);
        }

        CameraInfo getCamera() { return camera; }

        void setCamera(CameraInfo cam) {
            this.camera = cam;
            repaint();
        }

        void setSelected(boolean sel) {
            this.selected = sel;
            repaint();
        }

        private void openFullscreen() {
            if (camera == null) return;
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this),
                    "全屏 - " + camera.name, Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setUndecorated(false);
            VideoCell fullCell = new VideoCell(index);
            fullCell.camera = this.camera;
            fullCell.setPreferredSize(new Dimension(960, 720));
            dlg.add(fullCell);
            dlg.pack();
            dlg.setLocationRelativeTo(null);
            dlg.setVisible(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            float arc = 6f;

            // 背景
            Color bg = camera != null ? CELL_ACTIVE_BG : (hovered ? CELL_HOVER_COLOR : CELL_BG_COLOR);
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));

            // 选中边框
            if (selected) {
                g2.setColor(CELL_SEL_COLOR);
                g2.setStroke(new BasicStroke(2.5f));
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));
            } else {
                g2.setColor(new Color(45, 50, 60));
                g2.setStroke(new BasicStroke(1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
            }

            if (camera == null) {
                // 无信号占位图
                drawNoSignal(g2, w, h);
            } else {
                // 有摄像头：绘制模拟画面占位
                drawCameraPlaceholder(g2, w, h);
            }

            // 通道编号角标
            g2.setColor(selected ? CELL_SEL_COLOR : new Color(60, 65, 80));
            g2.fillRoundRect(4, 4, 26, 16, 4, 4);
            g2.setColor(Color.WHITE);
            g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 10f));
            FontMetrics fm = g2.getFontMetrics();
            String numStr = String.valueOf(index);
            g2.drawString(numStr, 4 + (26 - fm.stringWidth(numStr)) / 2, 4 + 12);

            g2.dispose();
        }

        private void drawNoSignal(Graphics2D g2, int w, int h) {
            // 摄像头图标（简单绘制）
            int cx = w / 2, cy = h / 2;
            int iconSize = Math.min(w, h) / 4;
            iconSize = Math.max(iconSize, 20);

            g2.setColor(new Color(55, 60, 75));
            g2.setStroke(new BasicStroke(2.5f));

            // 机身
            int bw = iconSize * 2, bh = (int)(iconSize * 1.4);
            g2.drawRoundRect(cx - bw / 2, cy - bh / 2, bw, bh, 6, 6);
            // 镜头
            int lr = iconSize / 2;
            g2.drawOval(cx - lr, cy - lr, lr * 2, lr * 2);
            // 取景器突起
            g2.drawRect(cx + bw / 2, cy - bh / 4, bh / 5, bh / 2);

            // 提示文字
            g2.setColor(CELL_TEXT_DIM);
            g2.setFont(UIUtils.plainFont().deriveFont(11f));
            String tip = "双击设备添加";
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(tip, cx - fm.stringWidth(tip) / 2, cy + bh / 2 + 18);
        }

        private void drawCameraPlaceholder(Graphics2D g2, int w, int h) {
            // 模拟视频画面：渐变背景
            GradientPaint gp = new GradientPaint(
                    0, 0, new Color(20, 35, 55),
                    w, h, new Color(10, 20, 35));
            g2.setPaint(gp);
            g2.fillRoundRect(2, 2, w - 4, h - 4, 5, 5);

            // 模拟扫描线效果（纹理感）
            g2.setColor(new Color(255, 255, 255, 8));
            for (int y = 0; y < h; y += 4) {
                g2.drawLine(2, y, w - 2, y);
            }

            // 摄像头名称
            g2.setColor(CELL_TEXT_BRIGHT);
            g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 12f));
            FontMetrics fm = g2.getFontMetrics();
            String name = camera.name;
            if (fm.stringWidth(name) > w - 20) {
                name = name.substring(0, Math.max(1, name.length() * (w - 24) / (fm.stringWidth(name)))) + "...";
            }
            g2.drawString(name, 8, 26);

            // 地址标签
            g2.setColor(CELL_TEXT_DIM);
            g2.setFont(UIUtils.plainFont().deriveFont(10f));
            String urlStr = camera.url.length() > 32 ? camera.url.substring(0, 32) + "..." : camera.url;
            g2.drawString(urlStr, 8, h - 12);

            // 右下角：模拟时间戳（实际可替换为当前时间）
            g2.setColor(new Color(255, 230, 50, 200));
            g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 10f));
            java.time.LocalTime now = java.time.LocalTime.now();
            String ts = String.format("%02d:%02d:%02d", now.getHour(), now.getMinute(), now.getSecond());
            fm = g2.getFontMetrics();
            g2.drawString(ts, w - fm.stringWidth(ts) - 8, h - 12);

            // "实况" 角标
            g2.setColor(new Color(220, 60, 60, 220));
            g2.fillRoundRect(w - 48, 6, 40, 16, 4, 4);
            g2.setColor(Color.WHITE);
            g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 10f));
            g2.drawString("● 实况", w - 45, 18);
        }
    }

    // ==================== 内部类：摄像头信息 ====================
    private static class CameraInfo {
        final String name;
        final String url;

        CameraInfo(String name, String url) {
            this.name = name;
            this.url = url;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
