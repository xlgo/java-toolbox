package com.aqishi.toolbox.algo;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Stack;

/**
 * 汉诺塔（Tower of Hanoi）算法与交互展示面板。
 * 支持手动操作和自动演示两种模式，包含动画播放控制与速度滑块。
 */
public class HanoiPanel extends ToolPanel {

    private static final int MIN_DISKS = 3;
    private static final int MAX_DISKS = 8;
    private static final Color[] DISK_COLORS = {
            new Color(239, 83, 80),   // 红色
            new Color(236, 64, 122),  // 粉红
            new Color(171, 71, 188),  // 紫色
            new Color(126, 87, 194),  // 深紫
            new Color(92, 107, 192),  // 靛蓝
            new Color(66, 165, 245),  // 蓝色
            new Color(38, 166, 154),  // 青色
            new Color(102, 187, 106)  // 绿色
    };

    // 核心状态数据
    @SuppressWarnings("unchecked")
    private final Stack<Integer>[] towers = new Stack[3];
    private final List<Move> autoMoves = new ArrayList<>();
    private int currentStepIndex = -1; // 自动演示当前步骤索引
    private int manualMovesCount = 0;  // 手动操作步数
    private int selectedRod = -1;      // 手动模式选中的柱子 (-1表示未选中)
    private boolean isAutoMode = false;
    private boolean isFinished = false;

    // 拖拽相关状态
    private int draggedDisk = -1;
    private int draggedFromRod = -1;
    private boolean isDragging = false;
    private int dragX = 0;
    private int dragY = 0;

    // 定时器
    private Timer timer;

    // UI 组件
    private JComboBox<Integer> diskCountBox;
    private JComboBox<String> modeBox;
    private JButton startBtn;
    private JButton pauseBtn;
    private JButton stepBtn;
    private JButton resetBtn;
    private JSlider speedSlider;
    private HanoiCanvas canvas;
    private JLabel statusLabel;

    public HanoiPanel() {
        super("algo", "hanoi", "Hanoi", "汉诺塔", "递归", "Tower of Hanoi");
        for (int i = 0; i < 3; i++) {
            towers[i] = new Stack<>();
        }
        resetGame(4); // 默认4个盘子
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 顶部控制面板：参数与播放控制按表单列对齐，画布因此能占满剩余高度 =====
        Integer[] diskOptions = new Integer[MAX_DISKS - MIN_DISKS + 1];
        for (int i = 0; i <= MAX_DISKS - MIN_DISKS; i++) {
            diskOptions[i] = MIN_DISKS + i;
        }
        diskCountBox = Fields.combo(diskOptions, 90);
        diskCountBox.setSelectedItem(4);
        diskCountBox.addActionListener(e -> resetGame((Integer) diskCountBox.getSelectedItem()));

        modeBox = Fields.combo(new String[]{"手动操作", "自动演示"}, 130);
        modeBox.addActionListener(e -> {
            isAutoMode = modeBox.getSelectedIndex() == 1;
            resetGame((Integer) diskCountBox.getSelectedItem());
            updateControlsState();
        });

        resetBtn = Buttons.secondary("重置");
        resetBtn.addActionListener(e -> resetGame((Integer) diskCountBox.getSelectedItem()));

        JButton helpBtn = Buttons.ghost("玩法说明");
        helpBtn.addActionListener(e -> showHelpDialog());

        // 自动演示专属按钮
        startBtn = Buttons.primary("开始演示");
        startBtn.addActionListener(e -> startAnimation());

        pauseBtn = Buttons.secondary("暂停");
        pauseBtn.addActionListener(e -> pauseAnimation());

        stepBtn = Buttons.secondary("单步");
        stepBtn.addActionListener(e -> stepAnimation());

        speedSlider = new JSlider(100, 2000, 1000); // 100ms - 2000ms
        speedSlider.setOpaque(false);
        speedSlider.addChangeListener(e -> {
            if (timer != null && timer.isRunning()) {
                timer.setDelay(speedSlider.getValue());
            }
        });

        // 分成左右两栏：控制项只占两行高，剩下的高度全留给画布
        // （四行一栏时，520px 高的窄窗口会把 rodHeight 压到盘子只剩几像素）
        FormGrid basics = new FormGrid();
        // 下拉框靠左包一层，保留自身宽度而不是被表单拉满整行
        basics.row("盘子数量", leading(diskCountBox));
        basics.row("模式", leading(modeBox));

        FormGrid playback = new FormGrid();
        playback.row("速度", speedSlider);
        // 三个播放按钮同属自动演示，合成一组挂在同一行，避免散落成一排 FlowLayout
        playback.row("自动演示", leading(startBtn, pauseBtn, stepBtn));

        Card config = Card.titled("汉诺塔设置");
        config.setContent(Layouts.columns(Tokens.SPACE_XL, basics, playback));
        config.addHeaderAction(helpBtn);
        config.addHeaderAction(resetBtn);

        root.add(config, BorderLayout.NORTH);

        // ===== 中间渲染画布 =====
        canvas = new HanoiCanvas();
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (isAutoMode || isFinished) return;
                int w = canvas.getWidth();
                int rodWidth = w / 3;
                if (rodWidth <= 0) return;
                int clickedRod = e.getX() / rodWidth;
                if (clickedRod < 0) clickedRod = 0;
                if (clickedRod > 2) clickedRod = 2;

                if (!towers[clickedRod].isEmpty()) {
                    draggedDisk = towers[clickedRod].peek();
                    draggedFromRod = clickedRod;
                    isDragging = false;
                    dragX = e.getX();
                    dragY = e.getY();
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (draggedDisk != -1) {
                    isDragging = true;
                    dragX = e.getX();
                    dragY = e.getY();
                    canvas.repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (draggedDisk != -1) {
                    if (isDragging) {
                        int w = canvas.getWidth();
                        int rodWidth = w / 3;
                        if (rodWidth > 0) {
                            int releasedRod = e.getX() / rodWidth;
                            if (releasedRod < 0) releasedRod = 0;
                            if (releasedRod > 2) releasedRod = 2;

                            if (releasedRod != draggedFromRod) {
                                if (isValidMove(draggedFromRod, releasedRod)) {
                                    towers[draggedFromRod].pop();
                                    towers[releasedRod].push(draggedDisk);
                                    manualMovesCount++;
                                    checkFinished();
                                } else {
                                    Toolkit.getDefaultToolkit().beep();
                                    statusLabel.setText("非法移动：大盘子不能压在小盘子上面！");
                                }
                            }
                        }
                    } else {
                        // 这是一个普通的点击选择事件
                        int w = canvas.getWidth();
                        int rodWidth = w / 3;
                        if (rodWidth > 0) {
                            int clickedRod = e.getX() / rodWidth;
                            if (clickedRod < 0) clickedRod = 0;
                            if (clickedRod > 2) clickedRod = 2;

                            if (selectedRod == -1) {
                                if (!towers[clickedRod].isEmpty()) {
                                    selectedRod = clickedRod;
                                }
                            } else {
                                if (selectedRod != clickedRod) {
                                    if (isValidMove(selectedRod, clickedRod)) {
                                        int disk = towers[selectedRod].pop();
                                        towers[clickedRod].push(disk);
                                        manualMovesCount++;
                                        checkFinished();
                                    } else {
                                        Toolkit.getDefaultToolkit().beep();
                                        statusLabel.setText("非法移动：大盘子不能压在小盘子上面！");
                                    }
                                }
                                selectedRod = -1;
                            }
                        }
                    }
                    draggedDisk = -1;
                    draggedFromRod = -1;
                    isDragging = false;
                    canvas.repaint();
                    updateStatus();
                }
            }
        };
        canvas.addMouseListener(mouseHandler);
        canvas.addMouseMotionListener(mouseHandler);

        // ===== 底部状态栏：并入画布卡片，进度/评级紧挨着它描述的那张图 =====
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(Tokens.fontBody());
        statusLabel.setForeground(Tokens.foreground());
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                KitBorders.lineSubtle(1, 0, 0, 0),
                KitBorders.padding(Tokens.SPACE_SM, Tokens.CARD_PADDING,
                        Tokens.SPACE_SM, Tokens.CARD_PADDING)));

        // 底座按画布宽度铺开，外面套一层留白容器，底座两端才不会顶到卡片描边
        JPanel canvasPad = Layouts.box();
        canvasPad.setBorder(KitBorders.padding(Tokens.SPACE_SM, Tokens.SPACE_SM,
                Tokens.SPACE_SM, Tokens.SPACE_SM));
        canvasPad.add(canvas, BorderLayout.CENTER);

        JPanel canvasBody = Layouts.box();
        canvasBody.add(canvasPad, BorderLayout.CENTER);
        canvasBody.add(statusLabel, BorderLayout.SOUTH);

        Card board = Card.flush("演示区");
        board.setContent(canvasBody);
        root.add(board, BorderLayout.CENTER);

        // 初始化按钮状态
        updateControlsState();
        updateStatus();

        return root;
    }

    /**
     * 把窄控件左对齐地包一层，使其在 FormGrid 里保留自身首选宽度。
     *
     * <p>{@code FlowLayout} 的 hgap 会同时留在行首，导致包过的控件比同列的滑块右移，
     * 所以这里 hgap 取 0，控件之间的间距改用 strut 补。</p>
     */
    private static JPanel leading(Component... items) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setOpaque(false);
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                panel.add(Box.createHorizontalStrut(Tokens.SPACE_SM));
            }
            panel.add(items[i]);
        }
        return panel;
    }

    private void showHelpDialog() {
        String msg = "【汉诺塔游戏规则】\n\n" +
                "1. 每次只能移动一个盘子。\n" +
                "2. 只能移动柱子最顶端的盘子。\n" +
                "3. 大盘子不能压在小盘子上面。\n\n" +
                "【移动目标】\n" +
                "将 A 柱上的所有盘子全部移动到 C 柱。\n\n" +
                "【操作方式】\n" +
                "- 拖动模式：鼠标按住盘子并拖拽到目标柱子上松开。\n" +
                "- 点击模式：先点击起始柱子选中它，然后再点击目标柱子完成移动。";
        JOptionPane.showMessageDialog(getView(), msg, "玩法说明", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateControlsState() {
        if (startBtn == null) return;
        boolean autoEnabled = isAutoMode && !isFinished;
        startBtn.setEnabled(autoEnabled);
        pauseBtn.setEnabled(autoEnabled && timer != null && timer.isRunning());
        stepBtn.setEnabled(autoEnabled);
        speedSlider.setEnabled(isAutoMode);
        diskCountBox.setEnabled(true);
    }

    /**
     * 重置游戏状态
     */
    private void resetGame(int diskCount) {
        if (timer != null) {
            timer.stop();
        }

        // 清空柱子
        for (int i = 0; i < 3; i++) {
            towers[i].clear();
        }

        // 将所有盘子压入 A 柱（索引 0）
        for (int i = diskCount; i >= 1; i--) {
            towers[0].push(i);
        }

        // 重新计算自动演示步骤
        autoMoves.clear();
        if (isAutoMode) {
            generateHanoiMoves(diskCount, 0, 2, 1);
        }

        currentStepIndex = -1;
        manualMovesCount = 0;
        selectedRod = -1;
        isFinished = false;

        updateControlsState();
        updateStatus();
        if (canvas != null) {
            canvas.repaint();
        }
    }

    /**
     * 递归生成汉诺塔的所有步骤
     */
    private void generateHanoiMoves(int n, int from, int to, int aux) {
        if (n == 1) {
            autoMoves.add(new Move(from, to));
            return;
        }
        generateHanoiMoves(n - 1, from, aux, to);
        autoMoves.add(new Move(from, to));
        generateHanoiMoves(n - 1, aux, to, from);
    }

    /**
     * 状态更新
     */
    private void updateStatus() {
        if (statusLabel == null) return;
        if (isFinished) {
            if (isAutoMode) {
                statusLabel.setText("自动演示完成！共 " + autoMoves.size() + " 步。");
            } else {
                int diskCount = (Integer) diskCountBox.getSelectedItem();
                int minMoves = (1 << diskCount) - 1; // 2^n - 1 最优解步数
                String stars;
                if (manualMovesCount == minMoves) {
                    stars = "⭐⭐⭐⭐⭐ (完美最优解!)";
                } else if (manualMovesCount <= minMoves + 3) {
                    stars = "⭐⭐⭐⭐ (优秀!)";
                } else if (manualMovesCount <= minMoves * 1.5) {
                    stars = "⭐⭐⭐ (良好)";
                } else if (manualMovesCount <= minMoves * 2) {
                    stars = "⭐⭐ (继续加油)";
                } else {
                    stars = "⭐ (完成但步数较多)";
                }
                statusLabel.setText("恭喜！成功完成汉诺塔。最少需 " + minMoves + " 步，您用了 " + manualMovesCount + " 步。得分评级: " + stars);
            }
            return;
        }

        if (isAutoMode) {
            int step = currentStepIndex + 1;
            statusLabel.setText("自动模式 | 进度: " + step + " / " + autoMoves.size() + " 步");
        } else {
            String selectionText = selectedRod == -1 ? "无" : (char)('A' + selectedRod) + " 柱";
            statusLabel.setText("手动模式 | 已移动: " + manualMovesCount + " 步 | 当前选中: " + selectionText);
        }
    }



    private boolean isValidMove(int from, int to) {
        if (towers[from].isEmpty()) return false;
        if (towers[to].isEmpty()) return true;
        return towers[from].peek() < towers[to].peek();
    }

    private void checkFinished() {
        // 游戏结束条件：A柱和B柱均为空，且所有盘子都到了C柱
        int totalDisks = (Integer) diskCountBox.getSelectedItem();
        if (towers[2].size() == totalDisks) {
            isFinished = true;
            updateControlsState();
        }
    }

    // ===== 自动演示播放控制 =====

    private void startAnimation() {
        if (isFinished) return;
        if (timer == null) {
            timer = new Timer(speedSlider.getValue(), e -> executeNextAutoMove());
        }
        timer.setDelay(speedSlider.getValue());
        timer.start();
        startBtn.setEnabled(false);
        pauseBtn.setEnabled(true);
        stepBtn.setEnabled(false);
    }

    private void pauseAnimation() {
        if (timer != null) {
            timer.stop();
        }
        startBtn.setEnabled(true);
        pauseBtn.setEnabled(false);
        stepBtn.setEnabled(true);
    }

    private void stepAnimation() {
        executeNextAutoMove();
    }

    private void executeNextAutoMove() {
        if (currentStepIndex + 1 < autoMoves.size()) {
            currentStepIndex++;
            Move move = autoMoves.get(currentStepIndex);
            int disk = towers[move.from].pop();
            towers[move.to].push(disk);

            if (currentStepIndex + 1 == autoMoves.size()) {
                isFinished = true;
                if (timer != null) {
                    timer.stop();
                }
            }
            updateStatus();
            updateControlsState();
            canvas.repaint();
        }
    }

    // ===== 移动步骤记录类 =====
    private static class Move {
        final int from;
        final int to;

        Move(int from, int to) {
            this.from = from;
            this.to = to;
        }
    }

    // ===== 自定义画布组件 =====
    private class HanoiCanvas extends JPanel {

        public HanoiCanvas() {
            // 画布现在坐在卡片里，底色跟随卡片而不是工作区
            setBackground(Tokens.cardBackground());
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            // 绘制底座阴影
            g2.setColor(new Color(0, 0, 0, 30));
            int baseHeight = 22;
            int baseY = h - 45;
            g2.fillRoundRect(23, baseY + 5, w - 46, baseHeight, 10, 10);

            // 绘制底座渐变块（描边色走 Tokens，Metal 兜底外观下 Component.borderColor 为 null 会导致底座失色）
            Color c1 = Tokens.border();
            Color c2 = new Color(Math.max(0, c1.getRed() - 35), Math.max(0, c1.getGreen() - 35), Math.max(0, c1.getBlue() - 35));
            GradientPaint baseGp = new GradientPaint(0, baseY, c1, 0, baseY + baseHeight, c2);
            g2.setPaint(baseGp);
            g2.fillRoundRect(20, baseY, w - 40, baseHeight, 8, 8);

            // 绘制底座金属高亮边框
            g2.setColor(new Color(255, 255, 255, 80));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(20, baseY, w - 40, baseHeight, 8, 8);

            // 三根柱子的中心点 X 坐标
            int rodSpacing = w / 3;
            int[] rodX = {
                    rodSpacing / 2,
                    rodSpacing / 2 + rodSpacing,
                    rodSpacing / 2 + 2 * rodSpacing
            };

            int rodHeight = h - 125;
            int rodTopY = baseY - rodHeight;
            int rodThickness = 8;

            // 绘制柱子和字母标签
            g2.setFont(UIUtils.titleFont());
            for (int i = 0; i < 3; i++) {
                // 绘制插座金属环座
                Color ringColor = Tokens.border();
                g2.setColor(ringColor.darker());
                g2.fillRoundRect(rodX[i] - 16, baseY - 5, 32, 7, 3, 3);
                g2.setColor(new Color(255, 255, 255, 60));
                g2.drawRoundRect(rodX[i] - 16, baseY - 5, 32, 7, 3, 3);

                // 如果是手动模式且选中了这根柱子，进行高亮显示（选中=强调色，未选中=描边色）
                if (i == selectedRod) {
                    g2.setColor(Tokens.accent());
                } else {
                    g2.setColor(Tokens.border());
                }

                // 绘制柱子
                g2.fillRect(rodX[i] - rodThickness / 2, rodTopY, rodThickness, rodHeight);

                // 绘制柱子下面的字母标签
                g2.setColor(Tokens.foreground());
                String label = String.valueOf((char) ('A' + i));
                FontMetrics fm = g2.getFontMetrics();
                int labelX = rodX[i] - fm.stringWidth(label) / 2;
                g2.drawString(label, labelX, baseY + baseHeight + 18);
            }

            // 绘制盘子
            int maxDiskCount = (Integer) diskCountBox.getSelectedItem();
            int maxDiskWidth = rodSpacing - 30;
            int minDiskWidth = 40;
            int diskHeight = Math.min(24, rodHeight / (maxDiskCount + 2));

            for (int r = 0; r < 3; r++) {
                Stack<Integer> stack = towers[r];
                // 转换为数组以便从底向上绘制
                Object[] arr = stack.toArray();
                for (int level = 0; level < arr.length; level++) {
                    int diskSize = (Integer) arr[level];

                    // 如果当前盘子正在被拖拽，且是当前柱子最顶部的盘子，则不在这里绘制
                    if (isDragging && r == draggedFromRod && level == arr.length - 1) {
                        continue;
                    }

                    // 计算宽度
                    double ratio = (double) (diskSize - 1) / (maxDiskCount - 1);
                    int diskWidth = minDiskWidth + (int) (ratio * (maxDiskWidth - minDiskWidth));

                    // 计算位置
                    int x = rodX[r] - diskWidth / 2;
                    int y = baseY - (level + 1) * diskHeight;

                    // 设置颜色并绘制
                    g2.setColor(DISK_COLORS[(diskSize - 1) % DISK_COLORS.length]);
                    g2.fillRoundRect(x, y, diskWidth, diskHeight - 2, 8, 8);

                    // 绘制边框
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(1.0f));
                    g2.drawRoundRect(x, y, diskWidth, diskHeight - 2, 8, 8);

                    // 绘制盘子编号数字（居中）
                    g2.setColor(Color.WHITE);
                    g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, Math.max(10f, diskHeight * 0.6f)));
                    String numStr = String.valueOf(diskSize);
                    FontMetrics fm = g2.getFontMetrics();
                    int numX = rodX[r] - fm.stringWidth(numStr) / 2;
                    int numY = y + (diskHeight - 2 + fm.getAscent() - fm.getDescent()) / 2;
                    g2.drawString(numStr, numX, numY);
                }
            }

            // 绘制被拖拽的盘子
            if (isDragging && draggedDisk != -1) {
                double ratio = (double) (draggedDisk - 1) / (maxDiskCount - 1);
                int diskWidth = minDiskWidth + (int) (ratio * (maxDiskWidth - minDiskWidth));
                int x = dragX - diskWidth / 2;
                int y = dragY - diskHeight / 2;

                // 设置颜色并绘制
                g2.setColor(DISK_COLORS[(draggedDisk - 1) % DISK_COLORS.length]);
                g2.fillRoundRect(x, y, diskWidth, diskHeight - 2, 8, 8);

                // 绘制边框
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.0f));
                g2.drawRoundRect(x, y, diskWidth, diskHeight - 2, 8, 8);

                // 绘制盘子编号数字
                g2.setColor(Color.WHITE);
                g2.setFont(UIUtils.plainFont().deriveFont(Font.BOLD, Math.max(10f, diskHeight * 0.6f)));
                String numStr = String.valueOf(draggedDisk);
                FontMetrics fm = g2.getFontMetrics();
                int numX = dragX - fm.stringWidth(numStr) / 2;
                int numY = y + (diskHeight - 2 + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(numStr, numX, numY);
            }
        }
    }
}
