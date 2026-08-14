package com.aqishi.toolbox.algo;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.ConfigManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 见缝插针（AA Pin Game）游戏面板。
 * <p>极致性能优化版：纳秒级 Delta-Time 物理微积分、显存同步、对象复用与双缓冲。</p>
 */
public class PinGamePanel extends ToolPanel {

    // 难度枚举
    public enum Difficulty {
        EASY("轻松模式", 0.75, 0.80),
        NORMAL("普通模式", 1.00, 0.86),
        HARD("挑战模式", 1.25, 0.93);

        private final String label;
        private final double speedScale;
        private final double hitTolerance; // 碰撞判定容错因子

        Difficulty(String label, double speedScale, double hitTolerance) {
            this.label = label;
            this.speedScale = speedScale;
            this.hitTolerance = hitTolerance;
        }

        public String getLabel() {
            return label;
        }
    }

    // 针实体定义
    public static class Pin {
        double angle; // 弧度 [0, 2π)
        int number;   // 针显示的数字序号
        boolean isInitial; // 是否为关卡预设针

        public Pin(double angle, int number, boolean isInitial) {
            this.angle = angle;
            this.number = number;
            this.isInitial = isInitial;
        }
    }

    // 游戏状态枚举
    private enum GameState {
        READY, PLAYING, PAUSED, GAME_OVER, VICTORY
    }

    // 核心持久化与状态变量
    private GameState state = GameState.READY;
    private Difficulty currentDifficulty = Difficulty.NORMAL;
    private int currentLevel = 1;
    private int maxUnlockedLevel = 1;
    private int score = 0;
    private int highScore = 0;

    // 关卡参数数据
    private int totalNeedShootCount;    // 本关需发射的针数
    private int remainingShootCount;    // 剩余未发射针数
    private int nextPinNumber;          // 下一次待发射针的序号
    private final List<Pin> attachedPins = new ArrayList<>();

    // 物理与高精度帧率控制
    private double currentDiskAngle = 0; // 当前中心盘旋转累计角度 (rad)
    private double gameTime = 0;         // 关卡运行累计时间 (秒)
    private double lockTimerRemaining = 0.0; // 关卡结束 3 秒强制停留锁存倒计时 (秒)
    private long lastNanoTime = 0;       // 纳秒级帧步进计时器
    private Timer gameLoopTimer;

    // 飞针物理状态（基于秒的平滑速度）
    private boolean isFlying = false;
    private double flyingPinY = 0;           // 当前飞行针的精确 Y 坐标
    private int flyingPinNumber = 0;         // 当前飞行针的序号
    private static final double FLY_SPEED_PER_SEC = 1500.0; // 飞针速度 1500 px/s，平滑极速

    // 碰撞与闪烁动画效果
    private Pin collidedPin1 = null;
    private Pin collidedPin2 = null;
    private int animEffectTimer = 0; // 动画特效计时帧数
    private String statusMessage = "点击【开始游戏】或按下 [空格键] 发射针！";

    // UI 组件
    private PinCanvas canvas;
    private JComboBox<String> difficultyBox;
    private JComboBox<Integer> levelBox;
    private JButton mainActionBtn;
    private JButton shootBtn;
    private JButton restartBtn;
    private JButton startLatestBtn;
    private JLabel levelLabel;
    private JLabel remainingPinsLabel;
    private JLabel scoreLabel;
    private JLabel highScoreLabel;

    // 性能优化：全局复用的图形对象，避免 paintComponent 频繁堆内存分配
    private static final BasicStroke STROKE_PIN = new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_PIN_THIN = new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final BasicStroke STROKE_DISK_BORDER = new BasicStroke(3.0f);
    private static final BasicStroke STROKE_AIM_LINE = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0, new float[]{6, 6}, 0);

    private static final Color COLOR_BG = new Color(24, 26, 32);
    private static final Color COLOR_PIN_LINE = new Color(210, 215, 225);
    private static final Color COLOR_PIN_INITIAL = new Color(140, 140, 140);
    private static final Color COLOR_PIN_WHITE = new Color(255, 255, 255);
    private static final Color COLOR_PIN_COLLISION = new Color(245, 34, 45);
    private static final Color COLOR_GOLD = new Color(230, 162, 60);

    private static final Font FONT_LEVEL_BIG = new Font(Font.DIALOG, Font.BOLD, 36);
    private static final Font FONT_LEVEL_SUB = new Font(Font.DIALOG, Font.PLAIN, 11);
    private static final Font FONT_PIN_NUM = new Font(Font.DIALOG, Font.BOLD, 12);
    private static final Font FONT_BANNER = new Font(Font.DIALOG, Font.PLAIN, 13);

    public PinGamePanel() {
        super("algo", "pingame", "见缝插针", "AA Pin Game", "Pin Game", "游戏", "AA", "见缝插针游戏");
        loadSavedStats();
    }

    private void loadSavedStats() {
        maxUnlockedLevel = Math.max(1, ConfigManager.getInt("pingame.maxlevel", 1));
        highScore = ConfigManager.getInt("pingame.highscore", 0);
        currentLevel = Math.min(maxUnlockedLevel, ConfigManager.getInt("pingame.lastlevel", 1));
    }

    private void saveStats() {
        ConfigManager.setInt("pingame.maxlevel", maxUnlockedLevel);
        ConfigManager.setInt("pingame.highscore", highScore);
        ConfigManager.setInt("pingame.lastlevel", currentLevel);
        ConfigManager.save();
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // 顶部控制与状态工具栏
        Card toolCard = Card.plain();
        toolCard.setLayout(new BorderLayout(12, 8));

        JPanel leftControlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftControlPanel.setOpaque(false);

        // 难度选择
        String[] diffNames = new String[]{Difficulty.EASY.getLabel(), Difficulty.NORMAL.getLabel(), Difficulty.HARD.getLabel()};
        difficultyBox = Fields.combo(diffNames, 110);
        difficultyBox.setSelectedIndex(currentDifficulty.ordinal());
        difficultyBox.addActionListener(e -> {
            int idx = difficultyBox.getSelectedIndex();
            if (idx >= 0 && idx < Difficulty.values().length) {
                currentDifficulty = Difficulty.values()[idx];
                initLevel(currentLevel);
            }
        });

        // 关卡选择
        levelBox = Fields.combo(generateLevelOptions(), 90);
        levelBox.setSelectedItem(currentLevel);
        levelBox.addActionListener(e -> {
            Integer sel = (Integer) levelBox.getSelectedItem();
            if (sel != null && sel != currentLevel) {
                currentLevel = sel;
                initLevel(currentLevel);
            }
        });

        mainActionBtn = Buttons.primary("开始游戏");
        mainActionBtn.setFocusable(false);
        mainActionBtn.addActionListener(e -> handleCanvasOrSpaceAction());

        shootBtn = Buttons.primary("发射 (Space)");
        shootBtn.setFont(new Font(Font.DIALOG, Font.BOLD, 14));
        shootBtn.setFocusable(false);
        shootBtn.setEnabled(false);
        shootBtn.addActionListener(e -> triggerShoot());

        restartBtn = Buttons.secondary("重置本关");
        restartBtn.setFocusable(false);
        restartBtn.addActionListener(e -> initLevel(currentLevel));

        startLatestBtn = Buttons.secondary("最新关卡 (" + maxUnlockedLevel + ")");
        startLatestBtn.setFocusable(false);
        startLatestBtn.addActionListener(e -> {
            currentLevel = maxUnlockedLevel;
            levelBox.setSelectedItem(currentLevel);
            initLevel(currentLevel);
            state = GameState.PLAYING;
            lastNanoTime = System.nanoTime();
            gameLoopTimer.start();
            updateUIStatus();
        });

        difficultyBox.setFocusable(false);
        levelBox.setFocusable(false);

        leftControlPanel.add(new JLabel("难度:"));
        leftControlPanel.add(difficultyBox);
        leftControlPanel.add(Box.createHorizontalStrut(6));
        leftControlPanel.add(new JLabel("关卡:"));
        leftControlPanel.add(levelBox);
        leftControlPanel.add(startLatestBtn);
        leftControlPanel.add(Box.createHorizontalStrut(10));
        leftControlPanel.add(mainActionBtn);
        leftControlPanel.add(shootBtn);
        leftControlPanel.add(restartBtn);

        // 右侧状态与得分显示
        JPanel rightInfoPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 16, 0));
        rightInfoPanel.setOpaque(false);
        levelLabel = new JLabel("关卡: " + currentLevel);
        levelLabel.setFont(new Font(Font.DIALOG, Font.BOLD, 14));
        levelLabel.setForeground(Tokens.accent());

        remainingPinsLabel = new JLabel("剩余针数: --");
        remainingPinsLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));

        scoreLabel = new JLabel("得分: 0");
        scoreLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 13));

        highScoreLabel = new JLabel("最高纪录: " + highScore);
        highScoreLabel.setFont(new Font(Font.DIALOG, Font.BOLD, 13));
        highScoreLabel.setForeground(COLOR_GOLD);

        rightInfoPanel.add(levelLabel);
        rightInfoPanel.add(remainingPinsLabel);
        rightInfoPanel.add(scoreLabel);
        rightInfoPanel.add(highScoreLabel);

        toolCard.add(leftControlPanel, BorderLayout.WEST);
        toolCard.add(rightInfoPanel, BorderLayout.EAST);

        // 主自绘画布 Card
        Card canvasCard = Card.plain();
        canvasCard.setLayout(new BorderLayout());

        canvas = new PinCanvas();
        canvasCard.add(canvas, BorderLayout.CENTER);

        root.add(toolCard, BorderLayout.NORTH);
        root.add(canvasCard, BorderLayout.CENTER);

        // 极致平滑动画 Timer (设置为 15ms，即约 67 FPS 高刷新)
        gameLoopTimer = new Timer(15, e -> updatePhysicsAndAnimation());

        // 初始化关卡数据
        initLevel(currentLevel);

        // 绑定键盘 [空格键] 快捷键发射
        bindKeyBindings(root);

        return root;
    }

    private Integer[] generateLevelOptions() {
        Integer[] opts = new Integer[maxUnlockedLevel];
        for (int i = 0; i < maxUnlockedLevel; i++) {
            opts[i] = i + 1;
        }
        return opts;
    }

    private void refreshLevelBoxOptions() {
        levelBox.removeAllItems();
        for (int i = 1; i <= maxUnlockedLevel; i++) {
            levelBox.addItem(i);
        }
        levelBox.setSelectedItem(currentLevel);
        if (startLatestBtn != null) {
            startLatestBtn.setText("最新关卡 (" + maxUnlockedLevel + ")");
        }
    }

    private void handleCanvasOrSpaceAction() {
        // 如果处于关卡结束 3 秒强制定留倒计时锁定期，忽略空格与点击，防止误触直接跳过提示
        if (lockTimerRemaining > 0) {
            return;
        }

        if (state == GameState.PLAYING) {
            triggerShoot();
        } else if (state == GameState.GAME_OVER || state == GameState.VICTORY) {
            initLevel(currentLevel);
            state = GameState.PLAYING;
            lastNanoTime = System.nanoTime();
            gameLoopTimer.start();
            updateUIStatus();
        } else if (state == GameState.READY || state == GameState.PAUSED) {
            togglePlayPause();
        }
    }

    private void initLevel(int level) {
        this.currentLevel = level;
        this.gameTime = 0;
        this.currentDiskAngle = 0;
        this.lockTimerRemaining = 0.0;
        this.isFlying = false;
        this.collidedPin1 = null;
        this.collidedPin2 = null;
        this.animEffectTimer = 0;
        this.lastNanoTime = System.nanoTime();

        this.totalNeedShootCount = Math.min(18, 6 + (level - 1) / 2);
        this.remainingShootCount = totalNeedShootCount;
        this.nextPinNumber = totalNeedShootCount;

        int initialPinCount = Math.min(6, 2 + (level - 1) / 3);

        attachedPins.clear();
        double angleStep = 2.0 * Math.PI / initialPinCount;
        for (int i = 0; i < initialPinCount; i++) {
            attachedPins.add(new Pin(i * angleStep, 0, true));
        }

        if (state == GameState.PLAYING) {
            statusMessage = "关卡 " + currentLevel + " 开始！按下 [空格键] 或点击画面发射！";
        } else {
            statusMessage = "准备就绪 - 关卡 " + currentLevel + " (共 " + totalNeedShootCount + " 针)";
        }

        updateUIStatus();
        if (canvas != null) {
            canvas.repaint();
        }
    }

    private void updateUIStatus() {
        levelLabel.setText("关卡: " + currentLevel);
        remainingPinsLabel.setText("剩余针数: " + remainingShootCount);
        scoreLabel.setText("得分: " + score);
        highScoreLabel.setText("最高纪录: " + highScore);

        if (state == GameState.PLAYING) {
            mainActionBtn.setText("暂停");
            shootBtn.setEnabled(true);
        } else if (state == GameState.PAUSED) {
            mainActionBtn.setText("继续游戏");
            shootBtn.setEnabled(false);
        } else {
            mainActionBtn.setText("开始游戏");
            shootBtn.setEnabled(false);
        }
    }

    private void togglePlayPause() {
        if (state == GameState.PLAYING) {
            state = GameState.PAUSED;
            gameLoopTimer.stop();
            statusMessage = "游戏已暂停";
        } else {
            state = GameState.PLAYING;
            lastNanoTime = System.nanoTime();
            gameLoopTimer.start();
            statusMessage = "游戏进行中... 按 [空格键] 射针！";
        }
        updateUIStatus();
        canvas.repaint();
    }

    private void triggerShoot() {
        if (state != GameState.PLAYING || isFlying || remainingShootCount <= 0) {
            return;
        }

        // 启动飞针
        isFlying = true;
        flyingPinY = canvas.getHeight() - 70; // 底部起点
        flyingPinNumber = nextPinNumber;

        canvas.repaint();
    }

    /**
     * 高精度 Delta-Time 物理步进与 60+ FPS 渲染循环
     */
    private void updatePhysicsAndAnimation() {
        // 计算精确到纳秒的时间增量 dt (单位: 秒)
        long now = System.nanoTime();
        double dt = (now - lastNanoTime) / 1_000_000_000.0;
        lastNanoTime = now;

        // 预防后台挂起或休眠导致 dt 异常庞大
        if (dt > 0.05) dt = 0.016;

        // ===== 关卡结束（通关或失败）3秒强制提示与停留处理 =====
        if (state == GameState.VICTORY || state == GameState.GAME_OVER) {
            if (lockTimerRemaining > 0) {
                lockTimerRemaining -= dt;
                if (lockTimerRemaining < 0) lockTimerRemaining = 0;

                int secs = (int) Math.ceil(lockTimerRemaining);
                if (state == GameState.VICTORY) {
                    statusMessage = "🎉 恭喜通关第 " + (currentLevel - 1) + " 关！将在 " + secs + " 秒后进入第 " + currentLevel + " 关...";
                    mainActionBtn.setText(secs > 0 ? "通关冷却 (" + secs + "s)" : "进入第 " + currentLevel + " 关");
                } else {
                    statusMessage = "💥 碰撞失败！将在 " + secs + " 秒后自动重置第 " + currentLevel + " 关...";
                    mainActionBtn.setText(secs > 0 ? "重置冷却 (" + secs + "s)" : "重新开始第 " + currentLevel + " 关");
                }

                // 3 秒强制停留结束，自动切入下一关或重置
                if (lockTimerRemaining <= 0) {
                    initLevel(currentLevel);
                    state = GameState.PLAYING;
                    updateUIStatus();
                }
            }

            // 游戏结束（通关/碰撞失败）：大圆盘停止旋转，定格现场画面
            if (animEffectTimer > 0) animEffectTimer--;
            canvas.repaint();
            return;
        }

        if (state != GameState.PLAYING && animEffectTimer <= 0) {
            return;
        }

        gameTime += dt;

        // ===== 1. 旋转物理计算 =====
        double currentAngularVelocity = calculateAngularVelocity(currentLevel, gameTime);
        currentDiskAngle += currentAngularVelocity * dt * currentDifficulty.speedScale;
        currentDiskAngle %= (2 * Math.PI);

        // ===== 2. 飞针物理与高精度碰撞检测 =====
        if (isFlying) {
            flyingPinY -= FLY_SPEED_PER_SEC * dt;

            int centerX = canvas.getWidth() / 2;
            int centerY = canvas.getHeight() / 2 - 40;
            int centerRadius = 65;
            int pinLineLength = 110;
            int pinBallRadius = 13;
            double targetY = centerY + centerRadius + pinLineLength;

            // 飞针到达贴紧中心大圆盘的切点
            if (flyingPinY <= targetY) {
                isFlying = false;

                double relativeAngle = (Math.PI / 2 - currentDiskAngle) % (2 * Math.PI);
                if (relativeAngle < 0) relativeAngle += 2 * Math.PI;

                Pin newPin = new Pin(relativeAngle, flyingPinNumber, false);

                // ===== 欧氏距离碰撞判定 =====
                Pin conflict = checkCollision(newPin, centerRadius, pinLineLength, pinBallRadius);

                if (conflict != null) {
                    // 发生碰撞！关卡失败，触发 3 秒强制停留提示
                    state = GameState.GAME_OVER;
                    lockTimerRemaining = 3.0;
                    collidedPin1 = newPin;
                    collidedPin2 = conflict;
                    animEffectTimer = 40;
                    shootBtn.setEnabled(false);
                } else {
                    // 插针成功！
                    attachedPins.add(newPin);
                    remainingShootCount--;
                    nextPinNumber--;
                    score += 10 * currentLevel;
                    if (score > highScore) {
                        highScore = score;
                    }

                    // 检查是否通关本关，触发 3 秒强制停留提示
                    if (remainingShootCount <= 0) {
                        state = GameState.VICTORY;
                        lockTimerRemaining = 3.0;
                        animEffectTimer = 50;

                        if (currentLevel >= maxUnlockedLevel) {
                            maxUnlockedLevel = currentLevel + 1;
                            refreshLevelBoxOptions();
                        }
                        currentLevel++;
                        saveStats();
                        shootBtn.setEnabled(false);
                    }
                }
                updateUIStatus();
            }
        }

        if (animEffectTimer > 0) {
            animEffectTimer--;
        }

        canvas.repaint();
    }

    /**
     * 动态角速度计算：随 Level 递进，正弦变速与平滑反向
     */
    private double calculateAngularVelocity(int level, double t) {
        double baseSpeed = 1.6 + Math.min(2.0, (level - 1) * 0.15); // 基础角速度 rad/s

        if (level <= 3) {
            return baseSpeed;
        } else if (level <= 7) {
            double wave = Math.sin(t * 1.8);
            return baseSpeed * (1.0 + 0.45 * wave);
        } else if (level <= 12) {
            double wave = Math.cos(t * 1.2);
            return baseSpeed * 1.2 * wave;
        } else {
            double wave = Math.sin(t * 1.5) + 0.5 * Math.cos(t * 2.5);
            return baseSpeed * (0.8 + 0.5 * wave);
        }
    }

    private Pin checkCollision(Pin newPin, int centerR, int lineLen, int ballR) {
        double centerDistance = centerR + lineLen;

        double newAbsoluteAngle = currentDiskAngle + newPin.angle;
        double newX = centerDistance * Math.cos(newAbsoluteAngle);
        double newY = centerDistance * Math.sin(newAbsoluteAngle);

        double minSafeDistance = 2.0 * ballR * currentDifficulty.hitTolerance;

        for (Pin existing : attachedPins) {
            double existAbsoluteAngle = currentDiskAngle + existing.angle;
            double exX = centerDistance * Math.cos(existAbsoluteAngle);
            double exY = centerDistance * Math.sin(existAbsoluteAngle);

            double dist = Math.hypot(newX - exX, newY - exY);
            if (dist < minSafeDistance) {
                return existing;
            }
        }
        return null;
    }

    private void bindKeyBindings(JComponent comp) {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(e -> {
            if (!comp.isShowing()) {
                return false; // 当前游戏工具页未处于显示状态，不拦截
            }
            if (e.getID() == KeyEvent.KEY_PRESSED && e.getKeyCode() == KeyEvent.VK_SPACE) {
                handleCanvasOrSpaceAction();
                return true; // 拦截并完全消费该空格键事件
            }
            return false;
        });
    }

    /**
     * 高品质且高性能自绘画布组件（开启双缓冲与 OS 显存同步）
     */
    private class PinCanvas extends JPanel {

        public PinCanvas() {
            setBackground(COLOR_BG);
            setDoubleBuffered(true); // 开启显式双缓冲
            setFocusable(true);

            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    handleCanvasOrSpaceAction();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g.create();

            // 高品质与平滑描边渲染选项
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            int width = getWidth();
            int height = getHeight();

            int centerX = width / 2;
            int centerY = height / 2 - 40;
            int diskRadius = 65;
            int pinLength = 110;
            int ballRadius = 13;

            // 绘制轻松模式辅助导向线
            if (currentDifficulty == Difficulty.EASY && state == GameState.PLAYING) {
                g2d.setColor(new Color(64, 158, 255, 70));
                g2d.setStroke(STROKE_AIM_LINE);
                g2d.draw(new Line2D.Double(centerX, centerY + diskRadius, centerX, height - 70));
            }

            // ===== 1. 绘制已插入的针 =====
            for (Pin pin : attachedPins) {
                double absAngle = currentDiskAngle + pin.angle;
                drawPinOnDisk(g2d, centerX, centerY, diskRadius, pinLength, ballRadius, absAngle, pin, false);
            }

            // ===== 2. 碰撞闪烁效果 =====
            if (state == GameState.GAME_OVER && collidedPin1 != null) {
                boolean flash = (animEffectTimer / 5) % 2 == 0;
                if (flash && collidedPin2 != null) {
                    double absAngle1 = currentDiskAngle + collidedPin1.angle;
                    double absAngle2 = currentDiskAngle + collidedPin2.angle;
                    drawPinOnDisk(g2d, centerX, centerY, diskRadius, pinLength, ballRadius, absAngle1, collidedPin1, true);
                    drawPinOnDisk(g2d, centerX, centerY, diskRadius, pinLength, ballRadius, absAngle2, collidedPin2, true);
                }
            }

            // ===== 3. 绘制中心大圆盘 =====
            Color diskColor = (state == GameState.VICTORY) ? new Color(103, 194, 58) :
                    (state == GameState.GAME_OVER ? new Color(245, 108, 108) : new Color(33, 150, 243));

            // 外发光环
            g2d.setColor(new Color(diskColor.getRed(), diskColor.getGreen(), diskColor.getBlue(), 45));
            g2d.fill(new Ellipse2D.Double(centerX - diskRadius - 8, centerY - diskRadius - 8, (diskRadius + 8) * 2, (diskRadius + 8) * 2));

            // 主体渐变
            GradientPaint diskGrad = new GradientPaint(
                    centerX - diskRadius, centerY - diskRadius, diskColor.brighter(),
                    centerX + diskRadius, centerY + diskRadius, diskColor.darker()
            );
            g2d.setPaint(diskGrad);
            g2d.fill(new Ellipse2D.Double(centerX - diskRadius, centerY - diskRadius, diskRadius * 2, diskRadius * 2));

            // 边框
            g2d.setColor(new Color(255, 255, 255, 190));
            g2d.setStroke(STROKE_DISK_BORDER);
            g2d.draw(new Ellipse2D.Double(centerX - diskRadius, centerY - diskRadius, diskRadius * 2, diskRadius * 2));

            // 圆盘中央数字
            g2d.setColor(Color.WHITE);
            g2d.setFont(FONT_LEVEL_BIG);
            String lvlStr = String.valueOf(currentLevel);
            FontMetrics fm = g2d.getFontMetrics();
            int strX = centerX - fm.stringWidth(lvlStr) / 2;
            int strY = centerY + fm.getAscent() / 2 - 4;
            g2d.drawString(lvlStr, strX, strY);

            g2d.setFont(FONT_LEVEL_SUB);
            g2d.setColor(new Color(220, 220, 220));
            String subStr = "LEVEL";
            FontMetrics fmSub = g2d.getFontMetrics();
            g2d.drawString(subStr, centerX - fmSub.stringWidth(subStr) / 2, centerY - 20);

            // ===== 4. 绘制飞针 =====
            if (isFlying) {
                drawFlyingPin(g2d, centerX, flyingPinY, pinLength, ballRadius, flyingPinNumber);
            }

            // ===== 5. 绘制待发射针队列 =====
            if (state == GameState.PLAYING || state == GameState.READY || state == GameState.PAUSED) {
                drawPendingPinsQueue(g2d, centerX, height - 70, pinLength, ballRadius);
            }

            // ===== 6. 状态提示 Banner =====
            drawOverlayStatusBanner(g2d, width, height);

            g2d.dispose();

            // 关键优化：Windows 平台强制与系统显存与绘图管道同步，消除画面微抖动 (Stuttering)
            Toolkit.getDefaultToolkit().sync();
        }

        private void drawPinOnDisk(Graphics2D g2d, int cx, int cy, int diskR, int pinLen, int ballR, double angleRad, Pin pin, boolean isCollisionHighlight) {
            double startX = cx + diskR * Math.cos(angleRad);
            double startY = cy + diskR * Math.sin(angleRad);
            double endX = cx + (diskR + pinLen) * Math.cos(angleRad);
            double endY = cy + (diskR + pinLen) * Math.sin(angleRad);

            // 绘制线段
            g2d.setColor(isCollisionHighlight ? COLOR_PIN_COLLISION : COLOR_PIN_LINE);
            g2d.setStroke(STROKE_PIN);
            g2d.draw(new Line2D.Double(startX, startY, endX, endY));

            // 头部小球
            Color ballColor = isCollisionHighlight ? COLOR_PIN_COLLISION : (pin.isInitial ? COLOR_PIN_INITIAL : COLOR_PIN_WHITE);
            g2d.setColor(ballColor);
            g2d.fill(new Ellipse2D.Double(endX - ballR, endY - ballR, ballR * 2, ballR * 2));

            g2d.setColor(isCollisionHighlight ? Color.YELLOW : new Color(40, 40, 40));
            g2d.setStroke(STROKE_PIN_THIN);
            g2d.draw(new Ellipse2D.Double(endX - ballR, endY - ballR, ballR * 2, ballR * 2));

            // 数字
            if (pin.number > 0) {
                g2d.setFont(FONT_PIN_NUM);
                g2d.setColor(isCollisionHighlight ? Color.WHITE : new Color(20, 20, 20));
                String numStr = String.valueOf(pin.number);
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(numStr, (float) (endX - fm.stringWidth(numStr) / 2.0), (float) (endY + fm.getAscent() / 2.0 - 2));
            }
        }

        private void drawFlyingPin(Graphics2D g2d, int centerX, double pinY, int pinLen, int ballR, int num) {
            g2d.setColor(COLOR_PIN_WHITE);
            g2d.setStroke(STROKE_PIN);
            g2d.draw(new Line2D.Double(centerX, pinY, centerX, pinY + pinLen));

            g2d.setColor(new Color(64, 158, 255));
            g2d.fill(new Ellipse2D.Double(centerX - ballR, pinY - ballR, ballR * 2, ballR * 2));

            g2d.setFont(FONT_PIN_NUM);
            g2d.setColor(Color.WHITE);
            String numStr = String.valueOf(num);
            FontMetrics fm = g2d.getFontMetrics();
            g2d.drawString(numStr, (float) (centerX - fm.stringWidth(numStr) / 2.0), (float) (pinY + fm.getAscent() / 2.0 - 2));
        }

        private void drawPendingPinsQueue(Graphics2D g2d, int centerX, int firstPinY, int pinLen, int ballR) {
            int displayCount = Math.min(remainingShootCount, 6);
            int spacingY = 38;

            for (int i = 0; i < displayCount; i++) {
                int pinNum = remainingShootCount - i;
                int currentY = firstPinY + i * spacingY;

                if (isFlying && i == 0) continue;

                boolean isNext = (i == 0);
                Color ballColor = isNext ? COLOR_PIN_WHITE : new Color(160, 170, 185);

                g2d.setColor(ballColor);
                g2d.setStroke(isNext ? STROKE_PIN : STROKE_PIN_THIN);
                g2d.draw(new Line2D.Double(centerX, currentY, centerX, currentY + pinLen));

                g2d.setColor(ballColor);
                g2d.fill(new Ellipse2D.Double(centerX - ballR, currentY - ballR, ballR * 2, ballR * 2));

                g2d.setFont(FONT_PIN_NUM);
                g2d.setColor(new Color(30, 30, 30));
                String numStr = String.valueOf(pinNum);
                FontMetrics fm = g2d.getFontMetrics();
                g2d.drawString(numStr, (float) (centerX - fm.stringWidth(numStr) / 2.0), (float) (currentY + fm.getAscent() / 2.0 - 2));
            }
        }

        private void drawOverlayStatusBanner(Graphics2D g2d, int width, int height) {
            int bannerWidth = Math.min(520, width - 40);
            int bannerHeight = 36;
            int bannerX = (width - bannerWidth) / 2;
            int bannerY = 16;

            g2d.setColor(new Color(0, 0, 0, 160));
            g2d.fill(new RoundRectangle2D.Double(bannerX, bannerY, bannerWidth, bannerHeight, 12, 12));

            g2d.setColor(new Color(255, 255, 255, 220));
            g2d.setFont(FONT_BANNER);
            FontMetrics fm = g2d.getFontMetrics();
            int textX = (width - fm.stringWidth(statusMessage)) / 2;
            int textY = bannerY + bannerHeight / 2 + fm.getAscent() / 2 - 2;
            g2d.drawString(statusMessage, textX, textY);
        }
    }
}
