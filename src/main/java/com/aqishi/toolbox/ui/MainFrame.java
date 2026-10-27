package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.catalog.ToolboxContext;
import com.aqishi.toolbox.catalog.ToolRegistry;
import com.aqishi.toolbox.feature.network.ssh.infra.SshTunnelBridge;
import com.aqishi.toolbox.feature.network.ssh.ui.SshHostKeyPrompts;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Skin;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.FormatUtils;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.SecureClipboard;
import com.aqishi.toolbox.vault.VaultBootstrap;
import com.aqishi.toolbox.vault.VaultService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * 主窗口：顶栏 + 一级分类导航 + 启动器/工具页 + 状态栏。
 *
 * <p>按效果图重组外壳：默认进入卡片启动器，点磁贴才切入工具页；顶栏常驻搜索与
 * 一键主题切换。工具页仍然是原来的 {@link ToolContentHost}，按需挂载、切走不销毁，
 * 因此在启动器和工具之间来回走不会丢草稿。</p>
 *
 * <p>颜色统一由 {@link Tokens} 与当前 {@link Skin} 提供，切换主题即整窗刷新。</p>
 */
public class MainFrame extends JFrame {

    private static final String CARD_LAUNCHER = "launcher";
    private static final String CARD_TOOL = "tool";

    private final VaultService vaultService;
    private final SecureClipboard secureClipboard;

    private TopBar topBar;
    private JLabel statusReadyLabel;
    private JLabel statusJdkLabel;
    private JLabel statusCpuLabel;
    private JLabel statusMemoryLabel;
    private List<JLabel> statusSegments;
    private JToggleButton darkModeToggle;

    private ToolNavigationModel navigationModel;
    private ToolRegistry toolRegistry;
    private ToolSidebar sidebar;
    private ToolContentHost contentHost;
    private LauncherPanel launcher;
    private JPanel contentSwitch;
    private CardLayout contentCards;
    private JSplitPane workspaceSplit;
    private String currentToolId;
    private int expandedSidebarWidth = UIUtils.SIDEBAR_DEFAULT_WIDTH;
    private boolean sidebarCollapsed;
    private javax.swing.Timer statusTimer;

    private ToolPanel[] tools;

    private void createTools() {
        toolRegistry = ToolRegistry.createDefault();
        ToolboxContext context = new ToolboxContext(vaultService, secureClipboard);
        java.util.List<ToolPanel> registered = toolRegistry.createAll(context);
        tools = registered.toArray(new ToolPanel[0]);
        for (int i = 0; i < tools.length; i++) {
            if (com.aqishi.toolbox.Main.startupProgressUpdater != null) {
                int percent = 40 + (int) (60.0 * (i + 1) / tools.length);
                String name = tools[i].getClass().getSimpleName().replace("Panel", "");
                com.aqishi.toolbox.Main.startupProgressUpdater.accept(percent, "正在载入工具组件: " + name);
            }
        }
    }

    public MainFrame() {
        this(VaultBootstrap.createDefault());
    }

    private MainFrame(VaultBootstrap.Components components) {
        this(components.getService(), components.getClipboard());
    }

    MainFrame(VaultService vaultService, SecureClipboard secureClipboard) {
        super(I18n.get("app.title"));
        this.vaultService = java.util.Objects.requireNonNull(vaultService, "vaultService");
        this.secureClipboard = java.util.Objects.requireNonNull(secureClipboard, "secureClipboard");
        createTools();
        // 数据库 / Redis / Kafka 等工具经 SSH 隧道首次连接新主机时，与 SSH 终端一样弹出指纹确认
        SshTunnelBridge.setHostKeyPrompt(SshHostKeyPrompts.dialogs(this));
        navigationModel = new ToolNavigationModel(java.util.Arrays.asList(tools));

        try {
            java.net.URL iconUrl = MainFrame.class.getResource("icon.png");
            if (iconUrl != null) {
                setIconImage(new ImageIcon(iconUrl).getImage());
            }
        } catch (Exception e) {
            Errors.ignored("窗口图标加载失败，回退到默认图标", e);
        }

        setDefaultCloseOperation(EXIT_ON_CLOSE);

        int width = ConfigManager.getInt("width", 1180);
        int height = ConfigManager.getInt("height", 760);
        int x = ConfigManager.getInt("x", -1);
        int y = ConfigManager.getInt("y", -1);
        setSize(width, height);
        setMinimumSize(new Dimension(900, 600));
        if (x != -1 && y != -1 && UIUtils.isWindowPositionVisible(x, y, width, height)) {
            setLocation(x, y);
        } else {
            setLocationRelativeTo(null);
        }

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                // 窗口真正显示后把焦点交给内容区：否则顶栏搜索框会顶着焦点环出现在首屏
                SwingUtilities.invokeLater(() -> {
                    contentSwitch.requestFocusInWindow();
                    if (!contentSwitch.hasFocus() && topBar.getSearchField().isFocusOwner()) {
                        contentSwitch.requestFocus();
                    }
                });
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (statusTimer != null) {
                    try {
                        statusTimer.stop();
                    } catch (Throwable ignored) {
                    }
                }
                try {
                    ConfigManager.setInt("width", getWidth());
                    ConfigManager.setInt("height", getHeight());
                    Point p = getLocation();
                    ConfigManager.setInt("x", p.x);
                    ConfigManager.setInt("y", p.y);
                    persistNavigationState();
                    ConfigManager.save();
                } catch (Throwable ignored) {
                }
                closeManagedResources();
                try {
                    secureClipboard.clearPending();
                } catch (Throwable ignored) {
                }
                try {
                    vaultService.close();
                } catch (Throwable ignored) {
                }
            }
        });

        initUI();

        String ssPath = System.getProperty("screenshot");
        if (ssPath != null && !ssPath.isEmpty()) {
            final String path = ssPath;
            javax.swing.Timer ssTimer = new javax.swing.Timer(2500, e -> takeScreenshot(path));
            ssTimer.setRepeats(false);
            ssTimer.start();
        }
    }

    VaultService getVaultServiceForTest() {
        return vaultService;
    }

    @Override
    public void dispose() {
        if (statusTimer != null) {
            try {
                statusTimer.stop();
            } catch (Throwable ignored) {
            }
        }
        closeManagedResources();
        try {
            secureClipboard.clearPending();
        } catch (Throwable ignored) {
        }
        try {
            vaultService.close();
        } catch (Throwable ignored) {
        }
        super.dispose();
    }

    /**
     * Releases resources held by lazily created tool panels before the process
     * exits. Each panel owns its own client details; the shell only knows the
     * common lifecycle boundary.
     */
    private void closeManagedResources() {
        if (tools == null) return;
        for (ToolPanel tool : tools) {
            if (!(tool instanceof ManagedResourceOwner)) {
                continue;
            }
            try {
                ((ManagedResourceOwner) tool).closeResources();
            } catch (Throwable error) {
                // One panel failing to release must not strand the sockets and
                // threads owned by every panel after it.
                System.err.println("[shutdown] " + tool.getClass().getSimpleName()
                        + " 资源释放失败: " + error.getMessage());
            }
        }
        SshTunnelBridge.shutdown();
        SshTunnelBridge.setHostKeyPrompt(null);
    }

    private void takeScreenshot(String path) {
        try {
            Robot robot = new Robot();
            Rectangle bounds = getBounds();
            toFront();
            Thread.sleep(300);
            java.awt.image.BufferedImage img = robot.createScreenCapture(bounds);
            javax.imageio.ImageIO.write(img, "png", new java.io.File(path));
            System.out.println("Screenshot saved: " + path);
        } catch (Exception ex) {
            System.err.println("Screenshot failed: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- 组装

    private void initUI() {
        setLayout(new BorderLayout(0, 0));
        add(buildHeader(), BorderLayout.NORTH);
        add(buildWorkbench(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        installGlobalShortcuts();

        expandedSidebarWidth = ToolNavigationState.clampSidebarWidth(
                ConfigManager.getInt("nav.sidebarWidth", UIUtils.SIDEBAR_DEFAULT_WIDTH));
        boolean restoredCollapsed = Boolean.parseBoolean(
                ConfigManager.get("nav.sidebarCollapsed", "false"));
        sidebarCollapsed = false;
        SwingUtilities.invokeLater(() -> {
            workspaceSplit.setDividerLocation(expandedSidebarWidth);
            setSidebarCollapsed(restoredCollapsed);
        });

        restoreLastView();
        SwingUtilities.invokeLater(contentSwitch::requestFocusInWindow);
    }

    /** 恢复上次停留的工具；首次启动（无记录）直接进启动器 */
    private void restoreLastView() {
        String saved = ConfigManager.get("nav.selectedTool", null);
        if (saved != null && !saved.isEmpty() && navigationModel.findTool(saved) != null) {
            selectTool(saved);
            return;
        }
        showLauncher();
    }

    private JComponent buildHeader() {
        topBar = new TopBar();
        topBar.setName("workbench.topbar");
        topBar.setQueryListener(query -> {
            launcher.setQuery(query);
            if (!query.isEmpty()) {
                showLauncher();
                sidebar.setSelectedCategory(ToolSidebar.ALL);
            }
        });
        topBar.setFavoriteListener(only -> {
            launcher.setFavoriteOnly(only);
            showLauncher();
        });
        topBar.setBackListener(this::showLauncher);
        topBar.setExpandNavListener(() -> setSidebarCollapsed(false));
        topBar.setSearchDownListener(() -> launcher.focusFirstTile());
        topBar.setThemeToggleListener(() -> {
            ThemeManager.toggleDarkLight();
            afterThemeChange();
        });
        topBar.setSkinListener(skin -> {
            ThemeManager.applySkin(skin);
            afterThemeChange();
        });
        topBar.setThemeListener(name -> {
            ThemeManager.apply(name);
            afterThemeChange();
        });
        topBar.setLocaleListener(language -> {
            if (!language.equals(ConfigManager.get("locale", "zh_CN"))) {
                ConfigManager.set("locale", language);
                ConfigManager.save();
                I18n.init();
                reloadInPlace();
            }
        });
        topBar.getLanguageBox().setSelectedIndex(
                "en_US".equals(ConfigManager.get("locale", "zh_CN")) ? 1 : 0);

        JPanel wrapper = new JPanel(new BorderLayout(0, 0));
        wrapper.setOpaque(false);
        wrapper.add(topBar, BorderLayout.CENTER);
        wrapper.add(new Card.Hairline(), BorderLayout.SOUTH);
        return wrapper;
    }

    private JComponent buildWorkbench() {
        sidebar = new ToolSidebar(
                navigationModel,
                this::selectCategory,
                () -> setSidebarCollapsed(true));
        sidebar.setSearchAction(() -> topBar.focusSearch());
        sidebar.setFooter(buildSidebarFooter());
        sidebar.setMinimumSize(new Dimension(UIUtils.SIDEBAR_MIN_WIDTH, 0));
        sidebar.setPreferredSize(new Dimension(UIUtils.SIDEBAR_DEFAULT_WIDTH, 0));

        launcher = new LauncherPanel(navigationModel, this::selectTool);

        contentHost = new ToolContentHost(java.util.Arrays.asList(tools));
        // 外边距由每个工具面板自己的页面容器提供，这里不再叠加，避免双层留白
        contentHost.setBorder(BorderFactory.createEmptyBorder());

        contentCards = new CardLayout();
        contentSwitch = new JPanel(contentCards);
        contentSwitch.setOpaque(false);
        // 让内容区可以接管焦点：否则窗口第一次显示时焦点会落在搜索框上，
        // 启动画面就带一圈醒目的焦点环
        contentSwitch.setFocusable(true);
        contentSwitch.add(launcher, CARD_LAUNCHER);
        contentSwitch.add(contentHost, CARD_TOOL);

        workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, contentSwitch);
        workspaceSplit.setBorder(null);
        workspaceSplit.setContinuousLayout(true);
        workspaceSplit.setResizeWeight(0.0);
        workspaceSplit.setDividerSize(UIUtils.WORKBENCH_DIVIDER_SIZE);
        workspaceSplit.setDividerLocation(UIUtils.SIDEBAR_DEFAULT_WIDTH);
        workspaceSplit.addPropertyChangeListener(
                JSplitPane.DIVIDER_LOCATION_PROPERTY,
                event -> {
                    int location = workspaceSplit.getDividerLocation();
                    if (sidebarCollapsed || location <= 0) return;
                    int clamped = ToolNavigationState.clampSidebarWidth(location);
                    expandedSidebarWidth = clamped;
                    if (location != clamped) {
                        SwingUtilities.invokeLater(() -> {
                            if (!sidebarCollapsed) workspaceSplit.setDividerLocation(clamped);
                        });
                    }
                });
        return workspaceSplit;
    }

    /**
     * 侧栏底部的主题区：与效果图一致，一个「主题设置」入口加一个暗黑模式开关。
     */
    private JComponent buildSidebarFooter() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JButton appearance = footerButton(I18n.get("launcher.appearance.title"), WorkbenchIcons.gear(16));
        appearance.setName("workbench.appearanceFooter");
        appearance.addActionListener(event -> topBar.showAppearanceMenu());

        darkModeToggle = new JToggleButton(I18n.get("launcher.darkMode"));
        darkModeToggle.setName("workbench.darkMode");
        darkModeToggle.setFont(Tokens.fontBody());
        darkModeToggle.setIcon(WorkbenchIcons.moon(16));
        darkModeToggle.setHorizontalAlignment(SwingConstants.LEFT);
        darkModeToggle.setIconTextGap(Tokens.SPACE_SM);
        darkModeToggle.setFocusPainted(true);
        darkModeToggle.setOpaque(false);
        darkModeToggle.setContentAreaFilled(false);
        darkModeToggle.setBorderPainted(false);
        darkModeToggle.putClientProperty("JButton.buttonType", "borderless");
        darkModeToggle.setBorder(new EmptyBorder(6, 10, 6, 10));
        darkModeToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, Tokens.RAIL_ROW_HEIGHT));
        darkModeToggle.setAlignmentX(Component.LEFT_ALIGNMENT);
        darkModeToggle.addActionListener(event -> {
            ThemeManager.applySkin(darkModeToggle.isSelected() ? Skin.DARK : Skin.LIGHT);
            afterThemeChange();
        });

        appearance.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(appearance);
        panel.add(Box.createVerticalStrut(2));
        panel.add(darkModeToggle);
        updateDarkModeToggle();
        return panel;
    }

    private JButton footerButton(String text, Icon icon) {
        JButton button = new JButton(text);
        button.setFont(Tokens.fontBody());
        button.setIcon(icon);
        button.setIconTextGap(Tokens.SPACE_SM);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setFocusPainted(true);
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.putClientProperty("JButton.buttonType", "borderless");
        button.setBorder(new EmptyBorder(6, 10, 6, 10));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, Tokens.RAIL_ROW_HEIGHT));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }

    // ---------------------------------------------------------------- 视图切换

    /** 点侧栏分类：进入启动器的该分类视图，并清空搜索（否则用户会以为工具少了） */
    private void selectCategory(String categoryId) {
        boolean all = categoryId == null || ToolSidebar.ALL.equals(categoryId);
        launcher.setQuery("");
        topBar.setQuery("");
        topBar.setFavoriteSelected(false);
        launcher.setCategoryFilter(all ? null : categoryId);
        sidebar.setSelectedCategory(all ? ToolSidebar.ALL : categoryId);
        showLauncher();
    }

    /** 进入启动器（返回按钮、Esc、点击侧栏分类都会走到这里） */
    public void showLauncher() {
        if (contentCards == null) return;
        contentCards.show(contentSwitch, CARD_LAUNCHER);
        topBar.setContext("", false);
        topBar.setFavoriteSelected(launcher.isFavoriteOnly());
        revalidate();
        repaint();
    }

    public static MainFrame getMainFrame(Component comp) {
        Window window = SwingUtilities.getWindowAncestor(comp);
        if (window instanceof MainFrame) {
            return (MainFrame) window;
        }
        return null;
    }

    public ToolPanel findTool(String toolId) {
        return navigationModel != null ? navigationModel.findTool(toolId) : null;
    }

    /** 打开工具页：惰性挂载视图，并同步顶栏标题与侧栏分类高亮 */
    public void selectTool(String toolId) {
        ToolPanel tool = navigationModel.findTool(toolId);
        if (tool == null || !contentHost.showTool(toolId)) {
            return;
        }
        currentToolId = toolId;
        ConfigManager.set("nav.selectedTool", toolId);
        contentCards.show(contentSwitch, CARD_TOOL);
        topBar.setContext(tool.getLabel(), true);
        if (sidebar != null) {
            sidebar.setSelectedTool(toolId);
            sidebar.setSelectedCategory(tool.getGroup());
        }
        revalidate();
        repaint();
    }

    private void setSidebarCollapsed(boolean collapsed) {
        if (workspaceSplit == null) return;
        if (collapsed && !sidebarCollapsed && workspaceSplit.getDividerLocation() > 0) {
            expandedSidebarWidth = ToolNavigationState.clampSidebarWidth(
                    workspaceSplit.getDividerLocation());
        }
        sidebarCollapsed = collapsed;
        sidebar.setVisible(!collapsed);
        workspaceSplit.setDividerSize(collapsed ? 0 : UIUtils.WORKBENCH_DIVIDER_SIZE);
        workspaceSplit.setDividerLocation(collapsed ? 0 : expandedSidebarWidth);
        topBar.setNavigationExpanded(!collapsed);
        ConfigManager.set("nav.sidebarCollapsed", Boolean.toString(collapsed));
        workspaceSplit.revalidate();
        workspaceSplit.repaint();
    }

    private void installGlobalShortcuts() {
        JRootPane root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK),
                "nav.focusSearch");
        root.getActionMap().put("nav.focusSearch", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (sidebarCollapsed) {
                    setSidebarCollapsed(false);
                }
                SwingUtilities.invokeLater(topBar::focusSearch);
            }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "nav.back");
        root.getActionMap().put("nav.back", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                // Esc 先清搜索，再退回到启动器，避免一次按键跳两层
                if (!topBar.getSearchField().getText().isEmpty()) {
                    topBar.setQuery("");
                    launcher.setQuery("");
                } else {
                    showLauncher();
                }
            }
        });
    }

    private void persistNavigationState() {
        ConfigManager.set("nav.selectedTool", currentToolId == null ? "" : currentToolId);
        ConfigManager.setInt("nav.sidebarWidth", expandedSidebarWidth);
        ConfigManager.set("nav.sidebarCollapsed", Boolean.toString(sidebarCollapsed));
    }

    // ---------------------------------------------------------------- 外观刷新

    /**
     * 主题切换后的统一收尾：清图标色缓存、刷新自绘组件、重取状态栏颜色。
     *
     * <p>卡片与导航项在绘制时取色，理论上只要重绘即可；但分区标题、状态栏文本
     * 是构造时设置的，必须重新赋值，否则会残留上一套主题的前景色。</p>
     */
    private void afterThemeChange() {
        ToolGlyph.invalidate();
        topBar.refreshThemeIndicators();
        sidebar.restyle();
        if (launcher != null) {
            launcher.refresh();
        }
        updateDarkModeToggle();
        restyleAfterThemeChange();
        SwingUtilities.invokeLater(() -> {
            if (!sidebarCollapsed) {
                workspaceSplit.setDividerLocation(expandedSidebarWidth);
            }
        });
    }

    private void updateDarkModeToggle() {
        if (darkModeToggle == null) return;
        darkModeToggle.setSelected(ThemeManager.skin().isDark());
        darkModeToggle.setIcon(ThemeManager.skin().isDark()
                ? WorkbenchIcons.moon(16)
                : WorkbenchIcons.sun(16));
    }

    /** 主题切换后重新取色：这些标签的前景色是手动设置的，不会随 LAF 自动更新 */
    private void restyleAfterThemeChange() {
        if (statusSegments != null) {
            for (JLabel label : statusSegments) {
                label.setForeground(Tokens.mutedForeground());
            }
        }
        repaint();
    }

    private void reloadInPlace() {
        setTitle(I18n.get("app.title"));
        topBar.refreshLabels();
        sidebar.refreshLabels();
        launcher.refresh();
        if (currentToolId != null && contentCards != null
                && contentHost.isMounted(currentToolId)) {
            ToolPanel tool = navigationModel.findTool(currentToolId);
            topBar.setContext(tool == null ? "" : tool.getLabel(), tool != null);
        }
        updateStatusBar();
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------- 状态栏

    /**
     * 状态栏：左侧运行状态，右侧运行时指标，各段之间用细竖线分隔。
     *
     * <p>原来是一整条拼接字符串，分段之后每项都能独立本地化，也更容易扫读。</p>
     */
    private JComponent buildStatusBar() {
        statusReadyLabel = statusSegment();
        statusReadyLabel.setIcon(WorkbenchIcons.status());
        statusReadyLabel.setIconTextGap(Tokens.SPACE_XS);
        statusJdkLabel = statusSegment();
        statusCpuLabel = statusSegment();
        statusMemoryLabel = statusSegment();
        statusSegments = java.util.Arrays.asList(
                statusReadyLabel, statusJdkLabel, statusCpuLabel, statusMemoryLabel);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(statusReadyLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(statusJdkLabel);
        right.add(statusDivider());
        right.add(statusCpuLabel);
        right.add(statusDivider());
        right.add(statusMemoryLabel);

        JPanel bar = chromePanel();
        bar.setLayout(new BorderLayout(UIUtils.SPACE_MD, 0));
        bar.setBorder(new EmptyBorder(
                UIUtils.SPACE_XS + 1, UIUtils.SPACE_LG, UIUtils.SPACE_XS + 1, UIUtils.SPACE_LG));
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);

        JPanel wrapper = new JPanel(new BorderLayout(0, 0));
        wrapper.setOpaque(false);
        wrapper.add(new Card.Hairline(), BorderLayout.NORTH);
        wrapper.add(bar, BorderLayout.CENTER);

        statusTimer = new javax.swing.Timer(2000, e -> updateStatusBar());
        statusTimer.setInitialDelay(0);
        statusTimer.start();

        return wrapper;
    }

    /** Read the surface at paint time so every theme, including gallery themes, follows. */
    private static JPanel chromePanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Tokens.chromeBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        panel.setOpaque(false);
        return panel;
    }

    private JLabel statusSegment() {
        JLabel label = new JLabel();
        label.setFont(Tokens.fontCaption());
        label.setForeground(Tokens.mutedForeground());
        return label;
    }

    private JComponent statusDivider() {
        JComponent divider = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(UIUtils.SPACE_MD * 2 + 1, Tokens.fontCaption().getSize());
            }

            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Tokens.borderSubtle());
                g.fillRect(getWidth() / 2, 1, 1, getHeight() - 2);
            }
        };
        return divider;
    }

    private void updateStatusBar() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        int memPct = max > 0 ? (int) (used * 100L / max) : 0;

        statusReadyLabel.setText(localized("status.ready.short", "就绪"));
        statusJdkLabel.setText("JDK " + System.getProperty("java.version"));

        double cpuLoad = -1;
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                cpuLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
            }
        } catch (Exception ignored) {
        }
        statusCpuLabel.setText(cpuLoad >= 0
                ? String.format("CPU %.1f%%", cpuLoad * 100)
                : "CPU --%");

        String memoryTemplate = localized("status.memory", "内存 {0} / {1} ({2}%)");
        statusMemoryLabel.setText(java.text.MessageFormat.format(
                memoryTemplate, FormatUtils.bytes(used), FormatUtils.bytes(max), memPct));
    }

    /** 取本地化文案，键缺失时回退到内置中文，避免界面上出现原始 key */
    private static String localized(String key, String fallback) {
        String value = I18n.get(key);
        return key.equals(value) ? fallback : value;
    }
}
