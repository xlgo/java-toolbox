package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.catalog.ToolboxContext;
import com.aqishi.toolbox.catalog.ToolRegistry;
import com.aqishi.toolbox.feature.network.ssh.infra.SshTunnelBridge;
import com.aqishi.toolbox.feature.network.ssh.ui.SshHostKeyPrompts;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.FormatUtils;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.vault.SecureClipboard;
import com.aqishi.toolbox.vault.VaultBootstrap;
import com.aqishi.toolbox.vault.VaultService;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 主窗口：顶栏 + 侧栏 + 内容区（首页 / 工具页）+ 状态栏。
 *
 * <p>结构对应效果页：启动后停在首页的工具卡片网格，点卡片进入工具页，
 * 工具页左上角的「返回」回到首页。工具视图仍由 {@link ToolContentHost} 按需挂载，
 * 切走不销毁，因此在工具之间来回切换不会丢草稿。</p>
 *
 * <p>颜色统一由 {@link Tokens}、当前皮肤与强调色提供，切换外观即整窗刷新。</p>
 */
public class MainFrame extends JFrame {

    private static final DateTimeFormatter CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String VIEW_HOME = "home";
    private static final String VIEW_TOOL = "tool";
    private static final String LAST_CATEGORY_KEY = "nav.category";

    private final VaultService vaultService;
    private final SecureClipboard secureClipboard;

    private TopBar topBar;
    private ToolSidebar sidebar;
    private ToolHeader toolHeader;
    private ToolContentHost contentHost;
    private LauncherPanel home;
    private JPanel body;
    private JPanel views;
    private CardLayout viewLayout;
    private CenteredColumn toolColumn;

    private ToolNavigationModel navigationModel;
    private ToolRegistry toolRegistry;
    private ToolPanel[] tools;
    private ToolShortcuts shortcuts;

    private String currentToolId;
    private boolean sidebarCollapsed;
    private javax.swing.Timer statusTimer;

    private JLabel statusReadyLabel;
    private JLabel statusCurrentLabel;
    private JLabel statusFavoriteLabel;
    private JLabel statusJdkLabel;
    private JLabel statusCpuLabel;
    private JLabel statusMemoryLabel;
    private JLabel statusThemeLabel;
    private JLabel statusClockLabel;
    private List<JLabel> statusSegments;

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
        shortcuts = ToolShortcuts.load();

        try {
            java.net.URL iconUrl = MainFrame.class.getResource("icon.png");
            if (iconUrl != null) {
                setIconImage(new javax.swing.ImageIcon(iconUrl).getImage());
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
                SwingUtilities.invokeLater(() -> {
                    if (sidebar != null) sidebar.setCollapsed(sidebarCollapsed);
                    body.requestFocusInWindow();
                });
            }

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (statusTimer != null) {
                    try { statusTimer.stop(); } catch (Throwable ignored) { }
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
                try { secureClipboard.clearPending(); } catch (Throwable ignored) { }
                try { vaultService.close(); } catch (Throwable ignored) { }
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
            try { statusTimer.stop(); } catch (Throwable ignored) { }
        }
        closeManagedResources();
        try { secureClipboard.clearPending(); } catch (Throwable ignored) { }
        try { vaultService.close(); } catch (Throwable ignored) { }
        super.dispose();
    }

    private void closeManagedResources() {
        if (tools == null) return;
        for (ToolPanel tool : tools) {
            if (!(tool instanceof ManagedResourceOwner)) continue;
            try {
                ((ManagedResourceOwner) tool).closeResources();
            } catch (Throwable error) {
                System.err.println("[shutdown] " + tool.getClass().getSimpleName()
                        + " 资源释放失败: " + error.getMessage());
            }
        }
        SshTunnelBridge.shutdown();
        SshTunnelBridge.setHostKeyPrompt(null);
    }

    private void takeScreenshot(String path) {
        try {
            java.awt.image.BufferedImage img;
            if (Boolean.getBoolean("screenshot.offscreen")) {
                img = new java.awt.image.BufferedImage(
                        Math.max(1, getWidth()), Math.max(1, getHeight()),
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D graphics = img.createGraphics();
                try {
                    printAll(graphics);
                } finally {
                    graphics.dispose();
                }
            } else {
                java.awt.Robot robot = new java.awt.Robot();
                Rectangle bounds = getBounds();
                toFront();
                Thread.sleep(300);
                img = robot.createScreenCapture(bounds);
            }
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
        add(buildBody(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        installGlobalShortcuts();

        boolean restoredCollapsed = Boolean.parseBoolean(
                ConfigManager.get("nav.sidebarCollapsed", "false"));
        setSidebarCollapsed(restoredCollapsed);

        String restoredCategory = ConfigManager.get(LAST_CATEGORY_KEY, ToolSidebar.ALL);
        showHome(restoredCategory == null || restoredCategory.isEmpty()
                ? ToolSidebar.ALL : restoredCategory);
        SwingUtilities.invokeLater(() -> body.requestFocusInWindow());
    }

    private JComponent buildHeader() {
        topBar = new TopBar();
        topBar.setSidebarListener(() -> setSidebarCollapsed(!sidebarCollapsed));
        topBar.setQueryListener(query -> {
            if (home != null) {
                home.setQuery(query);
            }
            // 在工具页搜索时直接回到首页展示结果，避免「输入了但看不到变化」
            if (!isHomeVisible()) {
                sidebar.setActiveCategory(ToolSidebar.ALL);
                showView(VIEW_HOME);
            }
        });
        topBar.setSearchDownListener(() -> {
            if (home != null) {
                home.focusFirstTile();
            }
        });
        topBar.setSkinListener(skin -> {
            ThemeManager.applySkin(skin);
            afterThemeChange();
        });
        topBar.setAccentListener(rgb -> {
            if (rgb == 0) {
                ThemeManager.clearAccent();
            } else {
                ThemeManager.applyAccent(rgb);
            }
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

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(topBar, BorderLayout.CENTER);
        wrapper.add(new Card.Hairline(), BorderLayout.SOUTH);
        return wrapper;
    }

    private JComponent buildBody() {
        sidebar = new ToolSidebar(navigationModel, shortcuts, this::showHome,
                () -> setSidebarCollapsed(true));

        toolHeader = new ToolHeader();
        toolHeader.setFavoriteListener(this::toggleFavorite);
        toolHeader.setBackListener(() -> showHome(sidebar.getActiveCategory()));

        contentHost = new ToolContentHost(java.util.Arrays.asList(tools));
        contentHost.setBorder(BorderFactory.createEmptyBorder());
        contentHost.setOpaque(false);

        JPanel toolPage = new JPanel(new BorderLayout(0, 18));
        toolPage.setOpaque(false);
        toolPage.add(toolHeader, BorderLayout.NORTH);
        toolPage.add(contentHost, BorderLayout.CENTER);

        toolColumn = new CenteredColumn();
        toolColumn.setBorder(new EmptyBorder(
                Tokens.PAGE_PADDING_TOP, Tokens.PAGE_PADDING_X,
                Tokens.SPACE_LG, Tokens.PAGE_PADDING_X));
        toolColumn.add(toolPage);

        home = new LauncherPanel(navigationModel, shortcuts, this::selectTool);

        viewLayout = new CardLayout();
        views = new JPanel(viewLayout);
        views.setOpaque(false);
        views.add(home, VIEW_HOME);
        views.add(toolColumn, VIEW_TOOL);

        body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.setFocusable(true);
        body.add(sidebar, BorderLayout.WEST);
        body.add(views, BorderLayout.CENTER);
        return body;
    }

    // ---------------------------------------------------------------- 视图切换

    public ToolPanel findTool(String toolId) {
        return navigationModel != null ? navigationModel.findTool(toolId) : null;
    }

    /** 注册的工具总数，供外壳结构测试断言「功能零缺失」 */
    int toolCount() {
        return navigationModel == null ? 0 : navigationModel.getToolIds().size();
    }

    /** 从任意子组件回溯主窗口；工具面板用它打开跨工具跳转或刷新外壳。 */
    public static MainFrame getMainFrame(Component component) {
        java.awt.Window window = SwingUtilities.getWindowAncestor(component);
        return window instanceof MainFrame frame ? frame : null;
    }

    /** 打开工具页：惰性挂载视图，并同步标题、侧栏高亮与状态栏。 */
    public void selectTool(String toolId) {
        ToolPanel tool = navigationModel.findTool(toolId);
        if (tool == null || !contentHost.showTool(toolId)) return;
        currentToolId = toolId;
        shortcuts.markOpened(toolId);
        ConfigManager.set("nav.selectedTool", toolId);
        toolHeader.setTool(tool);
        toolHeader.setFavorite(shortcuts.isFavorite(toolId));
        sidebar.setCurrentTool(toolId, tool.getGroup());
        sidebar.setActiveCategory(tool.getGroup());
        showView(VIEW_TOOL);
        updateStatusBar();
        SwingUtilities.invokeLater(() -> toolColumn.scrollToTop());
    }

    /** 回到首页并展示指定分组 */
    public void showHome(String category) {
        currentToolId = null;
        String target = category == null || category.isEmpty() ? ToolSidebar.ALL : category;
        sidebar.setCurrentTool(null, null);
        sidebar.setActiveCategory(target);
        home.setCategory(target);
        showView(VIEW_HOME);
        ConfigManager.set(LAST_CATEGORY_KEY, target);
        updateStatusBar();
    }

    private void showView(String name) {
        viewLayout.show(views, name);
        views.revalidate();
        views.repaint();
    }

    private boolean isHomeVisible() {
        return home != null && home.isShowing();
    }

    private void toggleFavorite(String toolId) {
        boolean favorite = shortcuts.toggleFavorite(toolId);
        if (java.util.Objects.equals(currentToolId, toolId)) {
            toolHeader.setFavorite(favorite);
        }
        updateStatusBar();
    }

    private void setSidebarCollapsed(boolean collapsed) {
        sidebarCollapsed = collapsed;
        if (sidebar != null) sidebar.setVisible(!collapsed);
        if (topBar != null) topBar.setSidebarExpanded(!collapsed);
        if (sidebar != null) sidebar.setCollapsed(collapsed);
        ConfigManager.set("nav.sidebarCollapsed", Boolean.toString(collapsed));
        if (body != null) {
            body.revalidate();
            body.repaint();
        }
    }

    private void installGlobalShortcuts() {
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK),
                "nav.focusSearch");
        root.getActionMap().put("nav.focusSearch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (sidebarCollapsed) setSidebarCollapsed(false);
                SwingUtilities.invokeLater(topBar::focusSearch);
            }
        });

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK),
                "nav.toggleSidebar");
        root.getActionMap().put("nav.toggleSidebar", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                setSidebarCollapsed(!sidebarCollapsed);
            }
        });

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "nav.clearSearch");
        root.getActionMap().put("nav.clearSearch", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (!topBar.getSearchField().getText().isEmpty()) {
                    topBar.setQuery("");
                    home.setQuery("");
                } else if (!isHomeVisible()) {
                    showHome(sidebar.getActiveCategory());
                }
            }
        });

        for (int index = 1; index <= 9; index++) {
            final int position = index - 1;
            String key = "nav.quick." + index;
            root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                    KeyStroke.getKeyStroke(KeyEvent.VK_1 + position, InputEvent.CTRL_DOWN_MASK), key);
            root.getActionMap().put(key, new javax.swing.AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                    List<String> ids = new java.util.ArrayList<>(navigationModel.getToolIds());
                    if (position < ids.size()) selectTool(ids.get(position));
                }
            });
        }
    }

    private void persistNavigationState() {
        ConfigManager.set("nav.selectedTool", currentToolId == null ? "" : currentToolId);
        ConfigManager.set("nav.sidebarCollapsed", Boolean.toString(sidebarCollapsed));
        ConfigManager.set(LAST_CATEGORY_KEY, sidebar.getActiveCategory());
    }

    // ---------------------------------------------------------------- 外观刷新

    private void afterThemeChange() {
        ToolGlyph.invalidate();
        topBar.refreshThemeIndicators();
        sidebar.restyle();
        if (home != null) home.restyle();
        if (toolHeader != null) toolHeader.refresh();
        restyleAfterThemeChange();
        SwingUtilities.invokeLater(() -> {
            body.revalidate();
            body.repaint();
        });
    }

    private void restyleAfterThemeChange() {
        if (statusSegments != null) {
            for (JLabel label : statusSegments) {
                label.setForeground(Tokens.mutedForeground());
            }
        }
        if (statusCurrentLabel != null) {
            statusCurrentLabel.setForeground(Tokens.secondaryForeground());
        }
        if (statusFavoriteLabel != null) {
            statusFavoriteLabel.setForeground(Tokens.secondaryForeground());
        }
        if (statusClockLabel != null) {
            statusClockLabel.setFont(Tokens.fontMono().deriveFont(12f));
            statusClockLabel.setForeground(Tokens.secondaryForeground());
        }
        repaint();
    }

    private void reloadInPlace() {
        setTitle(I18n.get("app.title"));
        topBar.refreshLabels();
        sidebar.refreshLabels();
        home.refresh();
        toolHeader.refresh();
        updateStatusBar();
        revalidate();
        repaint();
    }

    // ---------------------------------------------------------------- 状态栏

    private JComponent buildStatusBar() {
        statusReadyLabel = statusSegment();
        statusReadyLabel.setIcon(WorkbenchIcons.status());
        statusReadyLabel.setIconTextGap(Tokens.SPACE_XS);
        statusCurrentLabel = statusSegment();
        statusCurrentLabel.setName("workbench.status.current");
        statusCurrentLabel.setForeground(Tokens.secondaryForeground());
        statusFavoriteLabel = statusSegment();
        statusFavoriteLabel.setForeground(Tokens.secondaryForeground());
        statusJdkLabel = statusSegment();
        statusCpuLabel = statusSegment();
        statusMemoryLabel = statusSegment();
        statusThemeLabel = statusSegment();
        statusThemeLabel.setName("workbench.status.theme");
        statusClockLabel = statusSegment();
        statusClockLabel.setName("workbench.clock");
        statusClockLabel.setFont(Tokens.fontMono().deriveFont(12f));
        statusClockLabel.setForeground(Tokens.secondaryForeground());
        statusSegments = java.util.Arrays.asList(
                statusReadyLabel, statusCurrentLabel, statusFavoriteLabel,
                statusJdkLabel, statusCpuLabel, statusMemoryLabel, statusThemeLabel);

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.setOpaque(false);
        left.add(statusReadyLabel);
        left.add(statusDivider());
        left.add(statusCurrentLabel);
        left.add(statusDivider());
        left.add(statusFavoriteLabel);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(statusJdkLabel);
        right.add(statusDivider());
        right.add(statusCpuLabel);
        right.add(statusDivider());
        right.add(statusMemoryLabel);
        right.add(statusDivider());
        right.add(statusThemeLabel);
        right.add(statusDivider());
        right.add(statusClockLabel);

        JPanel bar = chromePanel();
        bar.setLayout(new BorderLayout(UIUtils.SPACE_MD, 0));
        bar.setBorder(new EmptyBorder(4, 14, 4, 14));
        bar.add(left, BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(new Card.Hairline(), BorderLayout.NORTH);
        wrapper.add(bar, BorderLayout.CENTER);

        statusTimer = new javax.swing.Timer(1000, e -> updateStatusBar());
        statusTimer.setInitialDelay(0);
        statusTimer.start();
        return wrapper;
    }

    private static JPanel chromePanel() {
        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
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
        return new JComponent() {
            @Override public Dimension getPreferredSize() {
                return new Dimension(UIUtils.SPACE_MD * 2 + 1, Tokens.fontCaption().getSize());
            }
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Tokens.borderSubtle());
                g.fillRect(getWidth() / 2, 2, 1, getHeight() - 4);
            }
        };
    }

    private void updateStatusBar() {
        if (statusReadyLabel == null) return;
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        int memPct = max > 0 ? (int) (used * 100L / max) : 0;

        statusReadyLabel.setText(I18n.get("status.ready.short"));
        ToolPanel current = currentToolId == null ? null : navigationModel.findTool(currentToolId);
        statusCurrentLabel.setText(current == null
                ? java.text.MessageFormat.format(
                        I18n.get("status.toolCount"), model().getToolIds().size())
                : java.text.MessageFormat.format(
                        I18n.get("status.currentTool"), current.getLabel()));
        statusFavoriteLabel.setText(shortcuts.favoriteCount() == 0 ? ""
                : java.text.MessageFormat.format(
                        I18n.get("status.favorites"), shortcuts.favoriteCount()));
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
        statusCpuLabel.setText(cpuLoad >= 0 ? String.format("CPU %.1f%%", cpuLoad * 100) : "CPU --%");
        statusMemoryLabel.setText(java.text.MessageFormat.format(
                I18n.get("status.memory"),
                FormatUtils.bytes(used), FormatUtils.bytes(max), memPct));
        statusThemeLabel.setText(java.text.MessageFormat.format(
                I18n.get("status.theme"), ThemeManager.skin().label()));
        statusClockLabel.setText(LocalDateTime.now().format(CLOCK_FORMAT));
    }

    private ToolNavigationModel model() {
        return navigationModel;
    }

    /**
     * 工具页容器：内容限制在 1180px 并居中，超宽屏下表单不会被拉成极长的一行。
     *
     * <p>滚动由各工具面板自己负责（表格、终端、日志都需要独立滚动条），
     * 因此这里只做水平居中，不套第二层滚动视图。</p>
     */
    private static final class CenteredColumn extends JPanel {

        CenteredColumn() {
            super(null);
            setOpaque(false);
        }

        void scrollToTop() {
            // 工具页内部滚动由面板自己管理，这里不额外处理
        }

        @Override
        public void doLayout() {
            if (getComponentCount() == 0) return;
            java.awt.Insets insets = getInsets();
            int available = Math.max(1, getWidth() - insets.left - insets.right);
            int width = Math.min(Tokens.PAGE_MAX_WIDTH, available);
            int x = insets.left + Math.max(0, (available - width) / 2);
            int height = Math.max(1, getHeight() - insets.top - insets.bottom);
            getComponent(0).setBounds(x, insets.top, width, height);
        }

        @Override protected void paintComponent(Graphics g) {
            g.setColor(Tokens.surface());
            g.fillRect(0, 0, getWidth(), getHeight());
            super.paintComponent(g);
        }
    }
}
