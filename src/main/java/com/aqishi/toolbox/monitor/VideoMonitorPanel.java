package com.aqishi.toolbox.monitor;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.time.LocalTime;
import java.util.*;
import java.util.List;

/**
 * 视频监控面板。
 * <p>左侧设备树，右侧 GridBagLayout 驱动的视频网格。</p>
 * <p>支持：Ctrl+多选 → 合并格子；单选合并格 → 拆分；保存命名布局并持久化恢复。</p>
 */
public class VideoMonitorPanel extends ToolPanel {

    /* 预设布局：[格数, 列数, 行数] */
    private static final int[][] PRESETS = {
            {1,  1, 1},
            {4,  2, 2},
            {5,  3, 4},   // 5画面：1大格+4小格，实际网格 4行×3列
            {9,  3, 3},
            {16, 4, 4},
            {25, 5, 5},
    };
    private static final String[] PRESET_LABELS = {"1", "4", "5", "9", "16", "25"};

    /* 颜色常量（外层以兼容 JDK 8 内部非静态类限制） */
    static final Color C_BG     = new Color(22, 25, 30);
    static final Color C_SEL    = new Color(0, 120, 212);
    static final Color C_MULTI  = new Color(0, 160, 90);
    static final Color C_HOVER  = new Color(40, 45, 55);
    static final Color C_ACTIVE = new Color(15, 20, 28);
    static final Color C_DIM    = new Color(120, 130, 145);
    static final Color C_BRIGHT = new Color(200, 210, 220);

    private JPanel videoGrid;
    private final List<VideoCell> cells       = new ArrayList<>();
    private final List<VideoCell> multiSel    = new ArrayList<>();
    private VideoCell primaryCell = null;
    private int gridRows = 1;
    private int gridCols = 1;

    private JComboBox<String> layoutCombo;
    private final List<String> savedNames = new ArrayList<>();

    public VideoMonitorPanel() {
        super("开发工具", "视频监控",
                "视频", "监控", "摄像头", "Video", "camera", "RTSP", "分屏", "合并", "直播");
    }

    // ==================== 构建主面板 ====================
    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.add(buildToolbar(), BorderLayout.NORTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildDeviceTree(), buildVideoArea());
        split.setDividerLocation(220);
        split.setDividerSize(4);
        split.setResizeWeight(0.0);
        root.add(split, BorderLayout.CENTER);

        loadSavedNames();
        rebuildCombo();
        restoreLayout();
        return root;
    }

    // ==================== 顶部工具栏 ====================
    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                new javax.swing.border.EmptyBorder(6, 10, 6, 10)));

        JLabel title = new JLabel("  \uD83D\uDCF9  视频监控");
        title.setFont(UIUtils.titleFont().deriveFont(Font.BOLD, 14f));
        bar.add(title, BorderLayout.WEST);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));

        right.add(label("布局:"));
        layoutCombo = new JComboBox<>();
        layoutCombo.setFont(UIUtils.plainFont().deriveFont(12f));
        layoutCombo.setPreferredSize(new Dimension(130, 28));
        layoutCombo.addActionListener(e -> onComboSelected(layoutCombo.getSelectedIndex()));
        right.add(layoutCombo);

        right.add(Box.createHorizontalStrut(6));

        JButton mergeBtn = UIUtils.button("合并", 52);
        mergeBtn.setToolTipText("Ctrl+点击选中多个相邻格后点此合并");
        mergeBtn.addActionListener(e -> mergeSelected());
        right.add(mergeBtn);

        JButton splitBtn = UIUtils.button("拆分", 52);
        splitBtn.setToolTipText("将当前选中的合并格拆分回独立小格");
        splitBtn.addActionListener(e -> splitSelected());
        right.add(splitBtn);

        right.add(Box.createHorizontalStrut(6));

        JButton saveBtn = UIUtils.button("保存布局", 72);
        saveBtn.setToolTipText("为当前布局命名保存，下次可从下拉框选用");
        saveBtn.addActionListener(e -> saveCurrentLayout());
        right.add(saveBtn);

        JButton clearBtn = UIUtils.button("清空", 52);
        clearBtn.addActionListener(e -> clearAllCells());
        right.add(clearBtn);

        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(UIUtils.plainFont());
        return l;
    }

    // ==================== 下拉框管理 ====================
    private void rebuildCombo() {
        if (layoutCombo == null) return;
        int prevIdx = layoutCombo.getSelectedIndex();
        layoutCombo.removeAllItems();
        for (String lbl : PRESET_LABELS) layoutCombo.addItem(lbl + " 画面");
        for (String name : savedNames)   layoutCombo.addItem("\u2B50 " + name);
        layoutCombo.addItem("自定义...");
        // 恢复选择
        if (prevIdx >= 0 && prevIdx < layoutCombo.getItemCount()) {
            layoutCombo.setSelectedIndex(prevIdx);
        }
    }

    private void onComboSelected(int idx) {
        if (idx < 0) return;
        int presetCount = PRESET_LABELS.length;
        int savedCount  = savedNames.size();
        if (idx < presetCount) {
            applyPreset(idx);
        } else if (idx < presetCount + savedCount) {
            applySaved(savedNames.get(idx - presetCount));
        } else {
            showCustomDialog();
        }
    }

    // ==================== 左侧设备树 ====================
    private JComponent buildDeviceTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("\uD83D\uDCE1 所有设备");
        addArea(root, "\uD83C\uDFE2 一楼大厅",
                "门口摄像头-01,电梯口-02,前台区域-03",
                "rtsp://192.168.1.101,rtsp://192.168.1.102,rtsp://192.168.1.103");
        addArea(root, "\uD83C\uDFE2 二楼办公区",
                "会议室A-04,走廊东段-05,财务室-06",
                "rtsp://192.168.1.104,rtsp://192.168.1.105,rtsp://192.168.1.106");
        addArea(root, "\uD83C\uDD7F\uFE0F 停车场",
                "入口道闸-07,出口道闸-08,B1层全景-09,B2层全景-10",
                "rtsp://192.168.1.107,rtsp://192.168.1.108,rtsp://192.168.1.109,rtsp://192.168.1.110");
        addArea(root, "\uD83D\uDD12 安防周界",
                "北围墙-11,南围墙-12,东门岗亭-13,西门岗亭-14",
                "rtsp://192.168.1.111,rtsp://192.168.1.112,rtsp://192.168.1.113,rtsp://192.168.1.114");

        JTree tree = new JTree(root);
        tree.setFont(UIUtils.plainFont().deriveFont(12f));
        tree.setRowHeight(26);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.expandRow(0);
        tree.expandRow(1);

        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path == null) return;
                    Object obj = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                    if (obj instanceof CameraInfo) assignCamera((CameraInfo) obj);
                }
            }
        });

        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree t, Object value,
                    boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(t, value, sel, expanded, leaf, row, hasFocus);
                Object obj = ((DefaultMutableTreeNode) value).getUserObject();
                if (obj instanceof CameraInfo) setText("\uD83D\uDCF7 " + ((CameraInfo) obj).name);
                return this;
            }
        });

        JScrollPane sp = new JScrollPane(tree);
        sp.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1,
                UIManager.getColor("Component.borderColor")));

        JPanel wrap = new JPanel(new BorderLayout());
        JLabel hdr = new JLabel("  设备列表");
        hdr.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 12f));
        hdr.setOpaque(true);
        hdr.setBackground(UIManager.getColor("Panel.background"));
        hdr.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                new javax.swing.border.EmptyBorder(5, 8, 5, 8)));
        wrap.add(hdr, BorderLayout.NORTH);
        wrap.add(sp, BorderLayout.CENTER);
        return wrap;
    }

    private void addArea(DefaultMutableTreeNode root, String areaName, String names, String urls) {
        DefaultMutableTreeNode area = new DefaultMutableTreeNode(areaName);
        String[] ns = names.split(",");
        String[] us = urls.split(",");
        for (int i = 0; i < ns.length; i++) {
            area.add(new DefaultMutableTreeNode(new CameraInfo(ns[i].trim(), us[i].trim() + ":554/stream")));
        }
        root.add(area);
    }

    // ==================== 右侧视频区 ====================
    private JComponent buildVideoArea() {
        videoGrid = new JPanel(new GridBagLayout());
        videoGrid.setBackground(new Color(18, 18, 20));
        videoGrid.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        JScrollPane sp = new JScrollPane(videoGrid);
        sp.setBorder(null);
        sp.getViewport().setBackground(new Color(18, 18, 20));
        return sp;
    }

    // ==================== 预设布局 ====================
    private void applyPreset(int idx) {
        int[] p = PRESETS[idx];
        List<CellDef> defs;
        if (p[0] == 5) {
            // 5画面：左大格(4行2列) + 右侧4小格
            defs = new ArrayList<>();
            defs.add(new CellDef(0, 0, 4, 2));
            defs.add(new CellDef(0, 2, 1, 1));
            defs.add(new CellDef(1, 2, 1, 1));
            defs.add(new CellDef(2, 2, 1, 1));
            defs.add(new CellDef(3, 2, 1, 1));
            applyGrid(p[2], p[1], defs);
        } else {
            applyGrid(p[2], p[1], uniformDefs(p[2], p[1]));
        }
    }

    /** 生成均匀网格的 CellDef 列表 */
    private List<CellDef> uniformDefs(int rows, int cols) {
        List<CellDef> defs = new ArrayList<>();
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++)
                defs.add(new CellDef(r, c, 1, 1));
        return defs;
    }

    /** 核心布局应用：清除旧格、按 CellDef 列表重新添加 VideoCell */
    private void applyGrid(int rows, int cols, List<CellDef> defs) {
        this.gridRows = rows;
        this.gridCols = cols;

        List<CameraInfo> prev = collectCameras();
        cells.clear();
        multiSel.clear();
        primaryCell = null;
        videoGrid.removeAll();
        videoGrid.setLayout(new GridBagLayout());

        int idx = 0;
        for (CellDef def : defs) {
            VideoCell cell = new VideoCell(++idx, def);
            cells.add(cell);

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx      = def.col;
            gbc.gridy      = def.row;
            gbc.gridwidth  = def.colSpan;
            gbc.gridheight = def.rowSpan;
            gbc.weightx    = 1.0;
            gbc.weighty    = 1.0;
            gbc.fill       = GridBagConstraints.BOTH;
            gbc.insets     = new Insets(2, 2, 2, 2);
            videoGrid.add(cell, gbc);
        }

        for (int i = 0; i < Math.min(prev.size(), cells.size()); i++) {
            if (prev.get(i) != null) cells.get(i).def.camera = prev.get(i);
        }
        if (!cells.isEmpty()) selectCell(cells.get(0), false);
        videoGrid.revalidate();
        videoGrid.repaint();
    }

    // ==================== 合并 ====================
    private void mergeSelected() {
        if (multiSel.size() < 2) {
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(videoGrid),
                    "请先按住 Ctrl 并单击选中 2 个以上相邻格子，再点击合并。",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        // 包围矩形
        int minR = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE;
        int minC = Integer.MAX_VALUE, maxC = Integer.MIN_VALUE;
        for (VideoCell vc : multiSel) {
            CellDef d = vc.def;
            minR = Math.min(minR, d.row);
            maxR = Math.max(maxR, d.row + d.rowSpan - 1);
            minC = Math.min(minC, d.col);
            maxC = Math.max(maxC, d.col + d.colSpan - 1);
        }

        // 收集多选格覆盖的所有网格坐标
        Set<String> covered = new HashSet<>();
        for (VideoCell vc : multiSel) {
            CellDef d = vc.def;
            for (int r = d.row; r < d.row + d.rowSpan; r++)
                for (int c = d.col; c < d.col + d.colSpan; c++)
                    covered.add(r + "," + c);
        }
        // 验证包围矩形内全部已覆盖（即连续矩形）
        for (int r = minR; r <= maxR; r++) {
            for (int c = minC; c <= maxC; c++) {
                if (!covered.contains(r + "," + c)) {
                    JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(videoGrid),
                            "选中区域不是完整矩形，无法合并。\n请确保所选格子构成一个连续的矩形区域。",
                            "无法合并", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }
        }

        CellDef merged = new CellDef(minR, minC, maxR - minR + 1, maxC - minC + 1);
        merged.camera = multiSel.get(0).def.camera;   // 保留第一个格的摄像头

        Set<VideoCell> selSet = new HashSet<>(multiSel);
        List<CellDef> newDefs = new ArrayList<>();
        for (VideoCell vc : cells) {
            if (!selSet.contains(vc)) newDefs.add(vc.def);
        }
        newDefs.add(merged);
        newDefs.sort((a, b) -> a.row != b.row ? a.row - b.row : a.col - b.col);
        applyGrid(gridRows, gridCols, newDefs);
    }

    // ==================== 拆分 ====================
    private void splitSelected() {
        if (primaryCell == null) return;
        CellDef d = primaryCell.def;
        if (d.rowSpan == 1 && d.colSpan == 1) {
            JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(videoGrid),
                    "该格已是最小单元，无需拆分。", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        List<CellDef> newDefs = new ArrayList<>();
        for (VideoCell vc : cells) {
            if (vc == primaryCell) {
                for (int r = d.row; r < d.row + d.rowSpan; r++)
                    for (int c = d.col; c < d.col + d.colSpan; c++)
                        newDefs.add(new CellDef(r, c, 1, 1));
            } else {
                newDefs.add(vc.def);
            }
        }
        newDefs.sort((a, b) -> a.row != b.row ? a.row - b.row : a.col - b.col);
        applyGrid(gridRows, gridCols, newDefs);
    }

    // ==================== 自定义布局对话框 ====================
    private void showCustomDialog() {
        JSpinner rowSpin = new JSpinner(new SpinnerNumberModel(gridRows, 1, 10, 1));
        JSpinner colSpin = new JSpinner(new SpinnerNumberModel(gridCols, 1, 10, 1));
        rowSpin.setPreferredSize(new Dimension(60, 28));
        colSpin.setPreferredSize(new Dimension(60, 28));

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0; gbc.gridy = 0; p.add(new JLabel("行数 (1-10):"), gbc);
        gbc.gridx = 1; p.add(rowSpin, gbc);
        gbc.gridx = 0; gbc.gridy = 1; p.add(new JLabel("列数 (1-10):"), gbc);
        gbc.gridx = 1; p.add(colSpin, gbc);

        int result = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(videoGrid), p,
                "自定义布局", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            int rows = (Integer) rowSpin.getValue();
            int cols = (Integer) colSpin.getValue();
            applyGrid(rows, cols, uniformDefs(rows, cols));
            // 下拉框保持"自定义..."选中
        } else {
            // 取消：恢复下拉框到上次预设
            layoutCombo.setSelectedIndex(0);
        }
    }

    // ==================== 布局保存与恢复 ====================
    private void saveCurrentLayout() {
        Window owner = SwingUtilities.getWindowAncestor(videoGrid);
        String name = JOptionPane.showInputDialog(owner,
                "请输入布局名称（已有同名则覆盖）:", "保存布局", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();

        // 序列化：rows:cols,r:c:rs:cs,...
        StringBuilder sb = new StringBuilder().append(gridRows).append(":").append(gridCols);
        for (VideoCell vc : cells) {
            CellDef d = vc.def;
            sb.append(",").append(d.row).append(":").append(d.col)
              .append(":").append(d.rowSpan).append(":").append(d.colSpan);
        }
        ConfigManager.set("monitor.layout." + name, sb.toString());
        if (!savedNames.contains(name)) savedNames.add(name);
        ConfigManager.set("monitor.saved.names", String.join(",", savedNames));
        ConfigManager.save();

        // 重建下拉框并选中新名称
        rebuildCombo();
        layoutCombo.setSelectedIndex(PRESET_LABELS.length + savedNames.indexOf(name));

        JOptionPane.showMessageDialog(owner, "布局 \"" + name + "\" 已保存！",
                "保存成功", JOptionPane.INFORMATION_MESSAGE);
    }

    private void loadSavedNames() {
        savedNames.clear();
        String csv = ConfigManager.get("monitor.saved.names", "");
        if (!csv.isEmpty()) {
            for (String n : csv.split(",")) {
                String t = n.trim();
                if (!t.isEmpty()) savedNames.add(t);
            }
        }
    }

    private void applySaved(String name) {
        String data = ConfigManager.get("monitor.layout." + name, "");
        if (data.isEmpty()) return;
        String[] parts = data.split(",");
        String[] dims = parts[0].split(":");
        int rows = Integer.parseInt(dims[0]);
        int cols = Integer.parseInt(dims[1]);
        List<CellDef> defs = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String[] seg = parts[i].split(":");
            defs.add(new CellDef(
                    Integer.parseInt(seg[0]), Integer.parseInt(seg[1]),
                    Integer.parseInt(seg[2]), Integer.parseInt(seg[3])));
        }
        applyGrid(rows, cols, defs);
    }

    /** 启动时从配置恢复布局 */
    private void restoreLayout() {
        String saved = ConfigManager.get("monitor.layout", "0");
        if (saved.startsWith("saved:")) {
            String name = saved.substring(6);
            int idx = savedNames.indexOf(name);
            if (idx >= 0) {
                applySaved(name);
                layoutCombo.setSelectedIndex(PRESET_LABELS.length + idx);
                return;
            }
        }
        int idx = 0;
        try { idx = Integer.parseInt(saved); } catch (NumberFormatException ignore) {}
        idx = Math.max(0, Math.min(idx, PRESET_LABELS.length - 1));
        applyPreset(idx);
        if (layoutCombo != null) layoutCombo.setSelectedIndex(idx);
    }

    // ==================== 选择逻辑 ====================
    private void selectCell(VideoCell cell, boolean ctrl) {
        if (ctrl) {
            if (multiSel.contains(cell)) {
                multiSel.remove(cell);
                cell.multiSel = false;
                cell.primary  = false;
                if (cell == primaryCell) {
                    primaryCell = multiSel.isEmpty() ? null : multiSel.get(0);
                    if (primaryCell != null) primaryCell.primary = true;
                }
            } else {
                multiSel.add(cell);
                cell.multiSel = true;
            }
        } else {
            for (VideoCell vc : cells) { vc.primary = false; vc.multiSel = false; }
            multiSel.clear();
            primaryCell = cell;
            cell.primary = true;
            multiSel.add(cell);
        }
        videoGrid.repaint();
    }

    private void assignCamera(CameraInfo cam) {
        if (primaryCell != null) {
            primaryCell.def.camera = cam;
            primaryCell.repaint();
            int idx = cells.indexOf(primaryCell);
            if (idx >= 0 && idx < cells.size() - 1) selectCell(cells.get(idx + 1), false);
        } else {
            for (VideoCell vc : cells) {
                if (vc.def.camera == null) {
                    vc.def.camera = cam;
                    vc.repaint();
                    selectCell(vc, false);
                    return;
                }
            }
        }
    }

    private void clearAllCells() {
        for (VideoCell vc : cells) { vc.def.camera = null; vc.repaint(); }
    }

    private List<CameraInfo> collectCameras() {
        List<CameraInfo> list = new ArrayList<>();
        for (VideoCell vc : cells) list.add(vc.def.camera);
        return list;
    }

    // ==================== 内部类：CellDef ====================
    private static class CellDef {
        int row, col, rowSpan, colSpan;
        CameraInfo camera;

        CellDef(int row, int col, int rowSpan, int colSpan) {
            this.row = row; this.col = col;
            this.rowSpan = rowSpan; this.colSpan = colSpan;
        }
    }

    // ==================== 内部类：VideoCell ====================
    private class VideoCell extends JPanel {
        final int index;
        final CellDef def;
        boolean primary;
        boolean multiSel;
        private boolean hovered;

        VideoCell(int index, CellDef def) {
            this.index = index;
            this.def = def;
            setOpaque(false);
            setMinimumSize(new Dimension(50, 40));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    boolean ctrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
                    if (e.getClickCount() == 2 && !ctrl && def.camera != null) {
                        openFullscreen();
                    } else {
                        selectCell(VideoCell.this, ctrl);
                    }
                }
                @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hovered = false; repaint(); }
            });

            // 右键菜单
            JPopupMenu menu = new JPopupMenu();
            JMenuItem fullItem  = new JMenuItem("全屏查看");
            fullItem.addActionListener(e -> openFullscreen());
            JMenuItem mergeItem = new JMenuItem("合并（与多选格）");
            mergeItem.addActionListener(e -> mergeSelected());
            JMenuItem splitItem = new JMenuItem("拆分此格");
            splitItem.addActionListener(e -> { selectCell(VideoCell.this, false); splitSelected(); });
            JMenuItem clearItem = new JMenuItem("清除通道");
            clearItem.addActionListener(e -> { def.camera = null; repaint(); });
            menu.add(fullItem);
            menu.addSeparator();
            menu.add(mergeItem);
            menu.add(splitItem);
            menu.addSeparator();
            menu.add(clearItem);
            setComponentPopupMenu(menu);
        }

        private void openFullscreen() {
            if (def.camera == null) return;
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this),
                    "全屏 - " + def.camera.name, Dialog.ModalityType.APPLICATION_MODAL);
            VideoCell fc = new VideoCell(index, new CellDef(0, 0, 1, 1));
            fc.def.camera = this.def.camera;
            fc.setPreferredSize(new Dimension(960, 720));
            dlg.add(fc);
            dlg.pack();
            dlg.setLocationRelativeTo(null);
            dlg.setVisible(true);
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            if (w < 8 || h < 8) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            float arc = 6f;

            // 背景
            Color bg = def.camera != null ? C_ACTIVE : (hovered ? C_HOVER : C_BG);
            g2.setColor(bg);
            g2.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));

            // 边框
            if (primary) {
                g2.setColor(C_SEL); g2.setStroke(new BasicStroke(2.5f));
            } else if (multiSel) {
                g2.setColor(C_MULTI); g2.setStroke(new BasicStroke(2f));
            } else {
                g2.setColor(new Color(45, 50, 60)); g2.setStroke(new BasicStroke(1f));
            }
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 2, h - 2, arc, arc));

            // 内容
            if (def.camera == null) drawEmpty(g2, w, h);
            else                    drawCamera(g2, w, h);

            // 通道编号角标
            g2.setColor(primary ? C_SEL : new Color(50, 55, 70));
            g2.fillRoundRect(4, 4, 22, 14, 4, 4);
            g2.setColor(Color.WHITE);
            g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 9f));
            FontMetrics fm = g2.getFontMetrics();
            String num = String.valueOf(index);
            g2.drawString(num, 4 + (22 - fm.stringWidth(num)) / 2, 4 + 10);

            g2.dispose();
        }

        private void drawEmpty(Graphics2D g2, int w, int h) {
            if (w < 36 || h < 36) return;
            int cx = w / 2, cy = h / 2;
            int sz = Math.max(12, Math.min(w, h) / 6);
            g2.setColor(new Color(55, 60, 75));
            g2.setStroke(new BasicStroke(2f));
            int bw = sz * 2, bh = (int)(sz * 1.3);
            g2.drawRoundRect(cx - bw / 2, cy - bh / 2, bw, bh, 5, 5);
            int lr = sz / 2;
            g2.drawOval(cx - lr, cy - lr, lr * 2, lr * 2);
            if (h > 80) {
                g2.setColor(C_DIM);
                g2.setFont(UIUtils.plainFont().deriveFont(10.5f));
                String tip = "双击设备分配";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(tip, cx - fm.stringWidth(tip) / 2, cy + bh / 2 + 16);
            }
        }

        private void drawCamera(Graphics2D g2, int w, int h) {
            // 渐变背景
            g2.setPaint(new GradientPaint(0, 0, new Color(20, 35, 55), w, h, new Color(10, 20, 35)));
            g2.fillRoundRect(2, 2, w - 4, h - 4, 5, 5);
            // 扫描线
            g2.setColor(new Color(255, 255, 255, 8));
            for (int y = 2; y < h; y += 4) g2.drawLine(2, y, w - 2, y);

            if (h <= 30) {
                // 极小格：只显示名称
                g2.setColor(C_BRIGHT);
                g2.setFont(UIUtils.plainFont().deriveFont(10f));
                drawTrunc(g2, def.camera.name, 28, h / 2 + 4, w - 32);
                return;
            }

            // 摄像头名称（顶部，留出角标空间）
            g2.setColor(C_BRIGHT);
            g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 11f));
            drawTrunc(g2, def.camera.name, 30, 20, w - 58);

            // 实况角标（右上）
            g2.setColor(new Color(210, 55, 55, 220));
            g2.fillRoundRect(w - 44, 5, 38, 14, 4, 4);
            g2.setColor(Color.WHITE);
            g2.setFont(UIUtils.plainFont().deriveFont(9f));
            g2.drawString("\u25CF 实况", w - 41, 15);

            if (h > 64) {
                // URL（底部左侧）
                g2.setColor(C_DIM);
                g2.setFont(UIUtils.plainFont().deriveFont(10f));
                drawTrunc(g2, def.camera.url, 6, h - 12, w - 80);

                // 时间戳（底部右侧）
                g2.setColor(new Color(255, 230, 50, 200));
                g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 10f));
                String ts = String.format("%02d:%02d:%02d",
                        LocalTime.now().getHour(), LocalTime.now().getMinute(), LocalTime.now().getSecond());
                FontMetrics fm2 = g2.getFontMetrics();
                g2.drawString(ts, w - fm2.stringWidth(ts) - 6, h - 12);
            }
        }

        /**
         * 在 (x, y) 处绘制文字，超出 maxWidth 时截断并加省略号。
         */
        private void drawTrunc(Graphics2D g2, String text, int x, int y, int maxWidth) {
            if (text == null || text.isEmpty() || maxWidth <= 4) return;
            FontMetrics fm = g2.getFontMetrics();
            if (fm.stringWidth(text) <= maxWidth) {
                g2.drawString(text, x, y);
                return;
            }
            String ell = "...";
            int ellW = fm.stringWidth(ell);
            int avail = maxWidth - ellW;
            if (avail <= 0) { g2.drawString(ell, x, y); return; }
            int len = text.length();
            while (len > 0 && fm.stringWidth(text.substring(0, len)) > avail) len--;
            g2.drawString(text.substring(0, len) + ell, x, y);
        }
    }

    // ==================== 内部类：摄像头信息 ====================
    private static class CameraInfo {
        final String name;
        final String url;
        CameraInfo(String name, String url) { this.name = name; this.url = url; }
        @Override public String toString() { return name; }
    }
}
