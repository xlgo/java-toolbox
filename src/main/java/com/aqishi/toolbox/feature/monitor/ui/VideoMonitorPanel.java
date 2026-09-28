package com.aqishi.toolbox.feature.monitor.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.monitor.application.VideoPlayer;
import com.aqishi.toolbox.feature.monitor.application.VideoPlayerFactory;
import com.aqishi.toolbox.feature.monitor.application.VideoWallPlayers;
import com.aqishi.toolbox.feature.monitor.application.VideoWallStore;
import com.aqishi.toolbox.feature.monitor.domain.VideoSource;
import com.aqishi.toolbox.feature.monitor.domain.VideoWallModel;
import com.aqishi.toolbox.feature.monitor.domain.WallLayout;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.util.*;
import java.util.List;

/**
 * 视频监控面板。
 * <p>左侧设备树，右侧 GridBagLayout 驱动的视频网格。</p>
 * <p>支持：Ctrl+多选 → 合并格子；单选合并格 → 拆分；保存命名布局并持久化恢复。</p>
 *
 * <p>布局与通道分配在 {@link VideoWallModel} 里，播放器生命周期在 {@link VideoWallPlayers} 里，
 * 持久化在 {@link VideoWallStore} 里；本类只负责把它们画出来。换布局只换几何：
 * 仍可见的源继续播放，缩小时隐藏的源保留在配置里，放大后回来。</p>
 */
public class VideoMonitorPanel extends ToolPanel implements ManagedResourceOwner {

    private static final String[] PRESET_LABELS = {"1", "4", "5", "9", "16", "25"};

    /* 颜色常量（外层以兼容 JDK 8 内部非静态类限制） */
    static final Color C_BG     = new Color(22, 25, 30);
    static final Color C_SEL    = new Color(0, 120, 212);
    static final Color C_MULTI  = new Color(0, 160, 90);
    static final Color C_HOVER  = new Color(40, 45, 55);
    static final Color C_ACTIVE = new Color(15, 20, 28);
    static final Color C_DIM    = new Color(120, 130, 145);
    static final Color C_BRIGHT = new Color(200, 210, 220);
    /** 画面墙底色：内容区底色，不跟随主题 */
    static final Color GRID_BG  = new Color(18, 18, 20);

    private final VideoWallStore store;
    private final VideoWallPlayers players;
    private final SecretStore.Listener secretListener = status ->
            SwingUtilities.invokeLater(() -> onSecretStatus(status));

    private VideoWallModel model = new VideoWallModel(WallLayout.preset(0));
    /** 下拉框选中项：{@code preset:N} / {@code saved:名称} / {@code custom} */
    private String selection = "preset:0";

    private JPanel videoGrid;
    private JLabel hiddenLabel;
    private final List<VideoCell> cells       = new ArrayList<>();
    private final List<VideoCell> multiSel    = new ArrayList<>();
    private VideoCell primaryCell = null;
    private VideoCell fullscreenCell = null;

    private JComboBox<String> layoutCombo;
    private boolean updatingCombo;
    private final List<String> savedNames = new ArrayList<>();
    private volatile boolean closed;

    public VideoMonitorPanel() {
        this(SecretStore.disabled());
    }

    /** 生产入口：配置走 ConfigManager，流地址里的密码走保险库。 */
    public VideoMonitorPanel(SecretStore secrets) {
        this(new VideoWallStore(VideoWallStore.Settings.configManager(), secrets), PreviewVideoPlayer::new);
    }

    /** 测试入口：注入隔离的配置与假播放器。 */
    VideoMonitorPanel(VideoWallStore store, VideoPlayerFactory playerFactory) {
        super(ToolCatalog.VIDEO_MONITOR);
        this.store = Objects.requireNonNull(store, "store");
        this.players = new VideoWallPlayers(playerFactory, this::onFrame);
    }

    // ==================== 构建主面板 ====================
    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // 布局选择与格子操作都是低频动作，压成一条按首选高度占页首的紧凑卡片，
        // 剩下的纵向空间整块留给画面墙。
        root.add(buildControlCard(), BorderLayout.NORTH);

        // 设备树只是「把摄像头分配进格子」的来源：weight 0 让窗口变宽时多出来的宽度全部给画面
        root.add(Layouts.splitHorizontal(
                buildDeviceTree(), buildVideoArea(), 0.0, 0.22), BorderLayout.CENTER);

        savedNames.addAll(store.savedLayoutNames());
        rebuildCombo();
        restoreWall();
        store.secrets().addListener(secretListener);
        return root;
    }

    // ==================== 顶部控制条 ====================
    /**
     * 控制卡片：左侧选布局，右侧四个格子操作。
     *
     * <p>用 {@code ActionBar} 而不是 {@code FlowLayout(RIGHT)}：窗口变窄时先压缩中间的弹性空间，
     * 按钮不会折行，下拉框也不会被拉宽。</p>
     */
    private Card buildControlCard() {
        layoutCombo = new JComboBox<>();
        layoutCombo.setFont(Tokens.fontBody());
        // 下拉项是「16 画面」「⭐ 我的布局」这类短文本，固定宽度即可，跟着 ActionBar 一起不被拉伸
        layoutCombo.setPreferredSize(new Dimension(150, Tokens.CONTROL_HEIGHT));
        layoutCombo.setMinimumSize(new Dimension(110, Tokens.CONTROL_HEIGHT));
        layoutCombo.addActionListener(e -> {
            // 重建下拉项、恢复选中项时 JComboBox 也会发 ActionEvent，那不是用户换布局
            if (!updatingCombo) onComboSelected(layoutCombo.getSelectedIndex());
        });

        JButton mergeBtn = Buttons.primary("合并");
        mergeBtn.setToolTipText("Ctrl+点击选中多个相邻格后点此合并");
        mergeBtn.addActionListener(e -> mergeSelected());

        JButton splitBtn = Buttons.secondary("拆分");
        splitBtn.setToolTipText("将当前选中的合并格拆分回独立小格");
        splitBtn.addActionListener(e -> splitSelected());

        JButton saveBtn = Buttons.secondary("保存布局");
        saveBtn.setToolTipText("为当前布局命名保存，下次可从下拉框选用");
        saveBtn.addActionListener(e -> saveCurrentLayout());

        JButton clearBtn = Buttons.danger("清空");
        clearBtn.setToolTipText(I18n.get("tool.videomonitor.clear.tip"));
        clearBtn.addActionListener(e -> clearAllCells());

        hiddenLabel = Fields.label("");
        hiddenLabel.setForeground(C_DIM);

        ActionBar bar = new ActionBar();
        bar.left(Fields.label("布局:"));
        bar.left(layoutCombo);
        bar.left(hiddenLabel);
        bar.right(clearBtn);
        bar.right(saveBtn);
        bar.right(splitBtn);
        bar.right(mergeBtn);

        Card card = Card.titled("\uD83D\uDCF9 视频监控");
        card.setContent(bar);
        return card;
    }

    // ==================== 下拉框管理 ====================
    private void rebuildCombo() {
        if (layoutCombo == null) return;
        updatingCombo = true;
        try {
            layoutCombo.removeAllItems();
            for (String lbl : PRESET_LABELS) layoutCombo.addItem(lbl + " 画面");
            for (String name : savedNames)   layoutCombo.addItem("\u2B50 " + name);
            layoutCombo.addItem("自定义...");
        } finally {
            updatingCombo = false;
        }
        syncComboToSelection();
    }

    /** 让下拉框显示当前 {@link #selection}，不触发换布局。 */
    private void syncComboToSelection() {
        if (layoutCombo == null) return;
        int idx = 0;
        if (selection.startsWith("preset:")) {
            try {
                idx = Math.max(0, Math.min(Integer.parseInt(selection.substring(7)), PRESET_LABELS.length - 1));
            } catch (NumberFormatException ignore) { /* 配置值损坏时显示默认布局 */ }
        } else if (selection.startsWith("saved:") && savedNames.contains(selection.substring(6))) {
            idx = PRESET_LABELS.length + savedNames.indexOf(selection.substring(6));
        } else if (selection.equals("custom")) {
            idx = layoutCombo.getItemCount() - 1;
        }
        updatingCombo = true;
        try {
            if (idx < layoutCombo.getItemCount()) layoutCombo.setSelectedIndex(idx);
        } finally {
            updatingCombo = false;
        }
    }

    private void onComboSelected(int idx) {
        if (idx < 0) return;
        int presetCount = PRESET_LABELS.length;
        int savedCount  = savedNames.size();
        if (idx < presetCount) {
            applyLayout(WallLayout.preset(idx), "preset:" + idx);
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
        tree.setFont(Tokens.fontBody());
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

        // 原来的「设备列表」标题带与右侧分隔线交给卡片：标题带样式统一，也不会和分隔条画出双线
        Card card = Card.flush("设备列表");
        card.setContent(Fields.scroll(tree));
        return card;
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
    /**
     * 画面墙：无标题的铺满型卡片，把整块剩余空间交给画面。
     *
     * <p>底色保持原来的近黑色——这里是视频内容区而不是界面壳层，用卡片底色会让空格子发白、
     * 也看不出画面边界。</p>
     */
    private JComponent buildVideoArea() {
        videoGrid = new JPanel(new GridBagLayout());
        videoGrid.setBackground(GRID_BG);
        videoGrid.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
        JScrollPane sp = Fields.scroll(videoGrid);
        sp.getViewport().setBackground(GRID_BG);

        Card card = Card.plain().setFlush(true);
        card.setContent(sp);
        return card;
    }

    // ==================== 布局应用（核心） ====================
    /**
     * 换布局：只改几何与格子组件，通道分配在模型里原样保留。
     * 缩小时多出来的通道成为隐藏通道（仍保存），放大后回到原来的格子；仍在格子里的源继续播放。
     */
    private void applyLayout(WallLayout layout, String selection) {
        this.selection = selection;
        model.setLayout(layout);
        onWallChanged();
    }

    /**
     * 按当前模型重建格子组件并保存。
     *
     * <p>格子组件只是视图，每次都重建；播放器不随之重建：{@link VideoWallPlayers} 按源 id 复用，
     * 只有不再显示的源才释放。</p>
     */
    private void onWallChanged() {
        if (videoGrid == null || closed) return;
        multiSel.clear();
        primaryCell = null;
        cells.clear();
        videoGrid.removeAll();
        videoGrid.setLayout(new GridBagLayout());

        WallLayout layout = model.layout();
        for (int i = 0; i < model.cellCount(); i++) {
            WallLayout.Cell def = layout.cells().get(i);
            VideoCell cell = new VideoCell(i + 1, def);
            cells.add(cell);

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx      = def.col();
            gbc.gridy      = def.row();
            gbc.gridwidth  = def.colSpan();
            gbc.gridheight = def.rowSpan();
            gbc.weightx    = 1.0;
            gbc.weighty    = 1.0;
            gbc.fill       = GridBagConstraints.BOTH;
            gbc.insets     = new Insets(2, 2, 2, 2);
            videoGrid.add(cell, gbc);
        }

        players.sync(model.visibleSources());
        if (!cells.isEmpty()) selectCell(cells.get(0), false);
        updateHiddenLabel();
        videoGrid.revalidate();
        videoGrid.repaint();
        syncComboToSelection();
        persist();
    }

    /** 因布局缩小而暂时不显示的通道数，让用户知道它们没丢。 */
    private void updateHiddenLabel() {
        if (hiddenLabel == null) return;
        int hidden = model.hiddenSources().size();
        hiddenLabel.setText(hidden == 0 ? "" : I18n.get("tool.videomonitor.hidden", hidden));
    }

    private void persist() {
        try {
            store.save(model, selection).exceptionally(error -> {
                // 保险库写入失败：地址里的密码仍在内存里可用，下次保存再试
                Errors.log("Unable to store video stream passwords in the vault", error);
                return null;
            });
        } catch (RuntimeException error) {
            Errors.log("Unable to save the video wall configuration", error);
        }
    }

    /** 保险库解锁后把占位符换回密码，这些源随即开始播放；其他源不受影响。 */
    private void onSecretStatus(SecretStore.Status status) {
        if (status != SecretStore.Status.UNLOCKED) return;
        boolean changed = false;
        for (int i = 0; i < model.channels().size(); i++) {
            VideoSource source = model.channels().get(i);
            if (source == null || !source.credentialsPending()) continue;
            VideoSource resolved = store.resolve(source);
            if (resolved == source) continue;
            model.replace(resolved);
            changed = true;
        }
        if (videoGrid == null || closed) return;
        if (changed) {
            players.sync(model.visibleSources());   // 地址变了：换新播放器，旧的释放
            videoGrid.repaint();
        }
        // 保险库锁定期间输入的密码只在内存里，解锁后借这次保存写进保险库
        persist();
    }

    // ==================== 合并 ====================
    private void mergeSelected() {
        if (multiSel.size() < 2) {
            UIUtils.info(SwingUtilities.getWindowAncestor(videoGrid),
                    I18n.get("tool.videomonitor.merge.needSelection"));
            return;
        }
        List<Integer> selection = new ArrayList<>();
        for (VideoCell cell : multiSel) selection.add(cell.index - 1);
        if (!model.merge(selection)) {
            UIUtils.warn(SwingUtilities.getWindowAncestor(videoGrid),
                    I18n.get("tool.videomonitor.merge.notRectangle"),
                    I18n.get("tool.videomonitor.merge.notRectangle.title"));
            return;
        }
        // 合并格保留第一个所选格的源；被合并掉的其他源进入隐藏通道，提示条上会显示数量
        onWallChanged();
    }

    // ==================== 拆分 ====================
    private void splitSelected() {
        if (primaryCell == null) return;
        if (!model.split(primaryCell.index - 1)) {
            UIUtils.info(SwingUtilities.getWindowAncestor(videoGrid),
                    I18n.get("tool.videomonitor.split.atomic"));
            return;
        }
        onWallChanged();
    }

    // ==================== 自定义布局对话框 ====================
    private void showCustomDialog() {
        WallLayout current = model.layout();
        JSpinner rowSpin = new JSpinner(new SpinnerNumberModel(current.rows(), 1, WallLayout.MAX_DIMENSION, 1));
        JSpinner colSpin = new JSpinner(new SpinnerNumberModel(current.cols(), 1, WallLayout.MAX_DIMENSION, 1));
        rowSpin.setPreferredSize(new Dimension(60, 28));
        colSpin.setPreferredSize(new Dimension(60, 28));
        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0; gbc.gridy = 0; p.add(new JLabel(I18n.get("tool.videomonitor.custom.rows")), gbc);
        gbc.gridx = 1; p.add(rowSpin, gbc);
        gbc.gridx = 0; gbc.gridy = 1; p.add(new JLabel(I18n.get("tool.videomonitor.custom.cols")), gbc);
        gbc.gridx = 1; p.add(colSpin, gbc);

        int result = JOptionPane.showConfirmDialog(
                SwingUtilities.getWindowAncestor(videoGrid), p,
                I18n.get("tool.videomonitor.custom.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            int rows = (Integer) rowSpin.getValue();
            int cols = (Integer) colSpin.getValue();
            applyLayout(WallLayout.uniform(rows, cols), "custom");
        } else {
            // 取消：下拉框回到换布局前的选择（配置里仍是旧选择）
            syncComboToSelection();
        }
    }

    // ==================== 布局保存与恢复 ====================
    /** 启动时恢复上次的布局与全部通道（含隐藏通道）；从未保存过时用 1 画面。 */
    private void restoreWall() {
        VideoWallStore.Saved saved = store.load();
        if (saved != null) {
            model = saved.model();
            selection = saved.selection().isEmpty() ? "custom" : saved.selection();
        }
        onWallChanged();
    }

    private void saveCurrentLayout() {
        Window owner = SwingUtilities.getWindowAncestor(videoGrid);
        String name = UIUtils.input(owner, "请输入布局名称（已有同名则覆盖）:", "保存布局", null);
        if (name == null || name.trim().isEmpty()) return;
        // 名称列表以逗号分隔保存，名称里的逗号会把一个布局拆成两个
        name = name.trim().replace(',', ' ');

        store.saveNamedLayout(name, model.layout());
        if (!savedNames.contains(name)) savedNames.add(name);
        selection = "saved:" + name;
        rebuildCombo();   // 重建下拉项不再触发换布局，当前通道不受影响
        persist();

        UIUtils.info(owner, "布局 \"" + name + "\" 已保存！", "保存成功");
    }

    /** 命名布局只存几何：套用时通道分配照常保留。 */
    private void applySaved(String name) {
        WallLayout layout = store.namedLayout(name);
        if (layout == null) {
            syncComboToSelection();
            return;
        }
        applyLayout(layout, "saved:" + name);
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

    // ==================== 通道分配 ====================
    private void assignCamera(CameraInfo cam) {
        VideoSource source = VideoSource.create(cam.name, cam.url);
        if (primaryCell != null) {
            int idx = cells.indexOf(primaryCell);
            model.assign(idx, source);
            sourcesChanged();
            if (idx >= 0 && idx < cells.size() - 1) selectCell(cells.get(idx + 1), false);
        } else {
            int idx = model.firstEmptyCell();
            if (idx < 0) return;
            model.assign(idx, source);
            sourcesChanged();
            selectCell(cells.get(idx), false);
        }
    }

    /** 手动输入流地址。输入框不回填旧地址：旧地址里可能有密码。 */
    private void editStreamUrl(VideoCell cell) {
        Window owner = SwingUtilities.getWindowAncestor(videoGrid);
        String url = UIUtils.input(owner, I18n.get("tool.videomonitor.url.prompt"),
                I18n.get("tool.videomonitor.url.title"), null);
        if (url == null || url.trim().isEmpty()) return;
        VideoSource source = VideoSource.create(null, url);
        model.assign(cells.indexOf(cell), source);
        sourcesChanged();
        if (source.hasCredentials() && !store.canPersistSecrets()) {
            UIUtils.info(owner, I18n.get("tool.videomonitor.url.sessionOnly"));
        }
    }

    private void togglePlaying(VideoCell cell) {
        VideoSource source = model.source(cells.indexOf(cell));
        if (source == null) return;
        model.replace(source.withPlaying(!source.playing()));
        sourcesChanged();
    }

    private void clearCell(VideoCell cell) {
        if (model.clear(cells.indexOf(cell)) != null) sourcesChanged();
    }

    /** 清空全部通道，包括因布局缩小而隐藏的通道。 */
    private void clearAllCells() {
        model.clearAll();
        sourcesChanged();
    }

    /** 分配变了（布局没变）：播放器跟上、刷新提示与画面、保存。 */
    private void sourcesChanged() {
        if (closed || videoGrid == null) return;
        players.sync(model.visibleSources());
        updateHiddenLabel();
        videoGrid.repaint();
        persist();
    }

    /** 播放器线程报告新画面：按源 id 找当前显示它的格子，所以换布局后自动跟随。 */
    private void onFrame(String sourceId) {
        SwingUtilities.invokeLater(() -> {
            int idx = model.cellOf(sourceId);
            if (idx >= 0 && idx < cells.size()) cells.get(idx).repaint();
            VideoCell full = fullscreenCell;
            if (full != null && full.index == idx + 1) full.repaint();
        });
    }

    // ==================== 资源释放 ====================
    /** 释放全部播放器与保险库监听；配置在每次变更时已保存，这里不再写盘。可重复调用。 */
    @Override
    public void closeResources() {
        if (closed) return;
        closed = true;
        store.secrets().removeListener(secretListener);
        players.shutdown();
    }

    // ==================== 测试钩子 ====================
    JComboBox<String> layoutComboForTest() {
        return layoutCombo;
    }

    VideoWallModel modelForTest() {
        return model;
    }

    VideoWallPlayers playersForTest() {
        return players;
    }

    int cellCountForTest() {
        return cells.size();
    }

    void assignForTest(int cell, VideoSource source) {
        model.assign(cell, source);
        sourcesChanged();
    }

    void clearAllForTest() {
        clearAllCells();
    }

    void togglePlayingForTest(int cell) {
        togglePlaying(cells.get(cell));
    }

    void mergeForTest(int... cellIndexes) {
        selectCell(cells.get(cellIndexes[0]), false);
        for (int i = 1; i < cellIndexes.length; i++) selectCell(cells.get(cellIndexes[i]), true);
        mergeSelected();
    }

    // ==================== 内部类：VideoCell ====================
    /**
     * 一个格子的视图。只记住自己是第几个格子；显示什么源、用哪个播放器每次绘制时从模型里取，
     * 所以格子组件可以随布局随意重建。{@code def == null} 表示全屏窗口里的那一份。
     */
    private class VideoCell extends JPanel {
        /** 1 起的通道号（角标显示的数字） */
        final int index;
        final WallLayout.Cell def;
        boolean primary;
        boolean multiSel;
        private boolean hovered;

        VideoCell(int index, WallLayout.Cell def) {
            this.index = index;
            this.def = def;
            setOpaque(false);
            setMinimumSize(new Dimension(50, 40));
            if (def == null) return;   // 全屏副本：只显示，不参与选择与菜单

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    boolean ctrl = (e.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) != 0;
                    if (e.getClickCount() == 2 && !ctrl && source() != null) {
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
            JMenuItem urlItem = new JMenuItem(I18n.get("tool.videomonitor.menu.setUrl"));
            urlItem.addActionListener(e -> editStreamUrl(VideoCell.this));
            JMenuItem playItem = new JMenuItem(I18n.get("tool.videomonitor.menu.togglePlay"));
            playItem.addActionListener(e -> togglePlaying(VideoCell.this));
            JMenuItem clearItem = new JMenuItem("清除通道");
            clearItem.addActionListener(e -> clearCell(VideoCell.this));
            menu.add(fullItem);
            menu.addSeparator();
            menu.add(mergeItem);
            menu.add(splitItem);
            menu.addSeparator();
            menu.add(urlItem);
            menu.add(playItem);
            menu.add(clearItem);
            setComponentPopupMenu(menu);
        }

        VideoSource source() {
            return model.source(index - 1);
        }

        VideoPlayer player() {
            VideoSource s = source();
            return s == null ? null : players.player(s.id());
        }

        /** 全屏窗口与格子共用同一个播放器，关窗口不影响格子里的播放。 */
        private void openFullscreen() {
            VideoSource s = source();
            if (s == null) return;
            JDialog dlg = new JDialog(SwingUtilities.getWindowAncestor(this),
                    "全屏 - " + s.name(), Dialog.ModalityType.APPLICATION_MODAL);
            dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            VideoCell fc = new VideoCell(index, null);
            fc.setPreferredSize(new Dimension(960, 720));
            dlg.add(fc);
            dlg.pack();
            dlg.setLocationRelativeTo(null);
            fullscreenCell = fc;
            try {
                dlg.setVisible(true);
            } finally {
                fullscreenCell = null;
                dlg.dispose();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            if (w < 8 || h < 8) return;
            VideoSource source = source();

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            float arc = 6f;

            // 背景
            Color bg = source != null ? C_ACTIVE : (hovered ? C_HOVER : C_BG);
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
            if (source == null) drawEmpty(g2, w, h);
            else                drawCamera(g2, w, h, source, player());

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

        private void drawCamera(Graphics2D g2, int w, int h, VideoSource source, VideoPlayer player) {
            boolean live = source.playing() && player != null && player.isPlaying();
            BufferedImage frame = live ? player.frame() : null;
            if (frame != null && frame.getWidth() > 0 && frame.getHeight() > 0) {
                // 真实画面：等比缩放后裁切铺满格子
                double scale = Math.max((double) w / frame.getWidth(), (double) h / frame.getHeight());
                int fw = (int) Math.ceil(frame.getWidth() * scale);
                int fh = (int) Math.ceil(frame.getHeight() * scale);
                Graphics2D gi = (Graphics2D) g2.create();
                gi.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                gi.clip(new RoundRectangle2D.Float(2, 2, w - 4, h - 4, 5, 5));
                gi.drawImage(frame, (w - fw) / 2, (h - fh) / 2, fw, fh, null);
                gi.dispose();
            } else {
                // 没有画面（预览播放器、暂停、凭据未就绪）：渐变背景 + 扫描线
                g2.setPaint(new GradientPaint(0, 0, new Color(20, 35, 55), w, h, new Color(10, 20, 35)));
                g2.fillRoundRect(2, 2, w - 4, h - 4, 5, 5);
                g2.setColor(new Color(255, 255, 255, 8));
                for (int y = 2; y < h; y += 4) g2.drawLine(2, y, w - 2, y);
            }

            if (h <= 30) {
                // 极小格：只显示名称
                g2.setColor(C_BRIGHT);
                g2.setFont(UIUtils.plainFont().deriveFont(10f));
                drawTrunc(g2, source.name(), 28, h / 2 + 4, w - 32);
                return;
            }

            // 摄像头名称（顶部，留出角标空间）
            g2.setColor(C_BRIGHT);
            g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, 11f));
            drawTrunc(g2, source.name(), 30, 20, w - 58);

            // 状态角标（右上）：播放中为红色实况，暂停为灰色；凭据未就绪时在地址行说明
            if (live || !source.credentialsPending()) {
                g2.setFont(UIUtils.plainFont().deriveFont(9f));
                String badge = live ? "● 实况" : I18n.get("tool.videomonitor.paused");
                int bw = Math.max(38, g2.getFontMetrics().stringWidth(badge) + 8);
                g2.setColor(live ? new Color(210, 55, 55, 220) : new Color(90, 95, 110, 220));
                g2.fillRoundRect(w - bw - 6, 5, bw, 14, 4, 4);
                g2.setColor(Color.WHITE);
                g2.drawString(badge, w - bw - 3, 15);
            }

            if (h > 64) {
                // URL（底部左侧）：密码已打码；凭据还在保险库里时明确标出来
                g2.setColor(C_DIM);
                g2.setFont(UIUtils.plainFont().deriveFont(10f));
                String url = source.credentialsPending()
                        ? source.displayUrl() + " " + I18n.get("tool.videomonitor.credentialsLocked") : source.displayUrl();
                drawTrunc(g2, url, 6, h - 12, w - 80);

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
