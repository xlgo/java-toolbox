package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.algo.HanoiPanel;
import com.aqishi.toolbox.algo.SearchPanel;
import com.aqishi.toolbox.algo.SortPanel;
import com.aqishi.toolbox.calc.CalculatorPanel;
import com.aqishi.toolbox.calc.StatisticsPanel;
import com.aqishi.toolbox.convert.Base64ImagePanel;
import com.aqishi.toolbox.convert.ConvertPanel;
import com.aqishi.toolbox.convert.FormatConvertPanel;
import com.aqishi.toolbox.convert.TimePanel;
import com.aqishi.toolbox.crypto.AsymmetricPanel;
import com.aqishi.toolbox.crypto.CryptoPanel;
import com.aqishi.toolbox.crypto.SymmetricPanel;
import com.aqishi.toolbox.misc.AccountManagerPanel;
import com.aqishi.toolbox.misc.BpmnPanel;
import com.aqishi.toolbox.misc.CallbackTestPanel;
import com.aqishi.toolbox.misc.CertPanel;
import com.aqishi.toolbox.misc.ColorPanel;
import com.aqishi.toolbox.misc.CronPanel;
import com.aqishi.toolbox.misc.DatabasePanel;
import com.aqishi.toolbox.misc.DockerComposePanel;
import com.aqishi.toolbox.misc.FlowchartPanel;
import com.aqishi.toolbox.misc.HttpTestPanel;
import com.aqishi.toolbox.misc.JsonPanel;
import com.aqishi.toolbox.misc.JwtPanel;
import com.aqishi.toolbox.misc.K8sManagerPanel;
import com.aqishi.toolbox.misc.K8sPanel;
import com.aqishi.toolbox.misc.KafkaPanel;
import com.aqishi.toolbox.misc.MermaidPanel;
import com.aqishi.toolbox.misc.PasswordPanel;
import com.aqishi.toolbox.misc.RandomNumberPanel;
import com.aqishi.toolbox.misc.RedisPanel;
import com.aqishi.toolbox.misc.RegexPanel;
import com.aqishi.toolbox.misc.SqlPanel;
import com.aqishi.toolbox.misc.StringToolPanel;
import com.aqishi.toolbox.misc.SubnetPanel;
import com.aqishi.toolbox.misc.TextDiffPanel;
import com.aqishi.toolbox.misc.TotpPanel;
import com.aqishi.toolbox.misc.UuidPanel;
import com.aqishi.toolbox.misc.WeChatPanel;
import com.aqishi.toolbox.misc.XmlPanel;
import com.aqishi.toolbox.monitor.RemoteDesktopPanel;
import com.aqishi.toolbox.monitor.VideoMonitorPanel;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * 主窗口：统一分组侧边导航、当前工具栏、CardLayout 内容区与状态栏。
 * 所有颜色由 FlatLaf 外观包提供，切换主题即整窗刷新。
 */
public class MainFrame extends JFrame {

    private JLabel statusLabel;
    private JLabel currentToolLabel;
    private JLabel topThemeLabel;
    private JLabel topLangLabel;
    private JButton expandSidebarButton;
    private ToolNavigationModel navigationModel;
    private ToolSidebar sidebar;
    private ToolContentHost contentHost;
    private JSplitPane workspaceSplit;
    private String currentToolId;
    private int expandedSidebarWidth = UIUtils.SIDEBAR_DEFAULT_WIDTH;
    private boolean sidebarCollapsed;
    private javax.swing.Timer statusTimer;

    private ToolPanel[] tools;

    private void createTools() {
        java.util.function.Supplier<ToolPanel>[] creators = new java.util.function.Supplier[]{
            CryptoPanel::new, SymmetricPanel::new, AsymmetricPanel::new, AccountManagerPanel::new, TotpPanel::new,
            ConvertPanel::new, TimePanel::new, Base64ImagePanel::new, FormatConvertPanel::new,
            JsonPanel::new, XmlPanel::new, SqlPanel::new, RegexPanel::new, JwtPanel::new,
            CronPanel::new, TextDiffPanel::new, DockerComposePanel::new, SubnetPanel::new,
            HttpTestPanel::new, CallbackTestPanel::new, ColorPanel::new, CertPanel::new,
            K8sPanel::new, K8sManagerPanel::new, UuidPanel::new, PasswordPanel::new,
            RandomNumberPanel::new, CalculatorPanel::new, StatisticsPanel::new, SortPanel::new,
            SearchPanel::new, HanoiPanel::new, VideoMonitorPanel::new, RemoteDesktopPanel::new, RedisPanel::new, BpmnPanel::new,
            DatabasePanel::new, StringToolPanel::new, KafkaPanel::new, WeChatPanel::new, MermaidPanel::new, FlowchartPanel::new
        };

        tools = new ToolPanel[creators.length];
        for (int i = 0; i < creators.length; i++) {
            tools[i] = creators[i].get();
            if (com.aqishi.toolbox.Main.startupProgressUpdater != null) {
                int percent = 40 + (int) (60.0 * (i + 1) / creators.length);
                String name = tools[i].getClass().getSimpleName().replace("Panel", "");
                com.aqishi.toolbox.Main.startupProgressUpdater.accept(percent, "正在载入工具组件: " + name);
            }
        }
    }

    public MainFrame() {
        super(I18n.get("app.title"));
        createTools();
        navigationModel = new ToolNavigationModel(java.util.Arrays.asList(tools));

        try {
            java.net.URL iconUrl = MainFrame.class.getResource("icon.png");
            if (iconUrl != null) {
                setIconImage(new ImageIcon(iconUrl).getImage());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        setDefaultCloseOperation(EXIT_ON_CLOSE);

        int width = ConfigManager.getInt("width", 1024);
        int height = ConfigManager.getInt("height", 660);
        int x = ConfigManager.getInt("x", -1);
        int y = ConfigManager.getInt("y", -1);
        setSize(width, height);
        setMinimumSize(new Dimension(820, 520));
        if (x != -1 && y != -1) {
            setLocation(x, y);
        } else {
            setLocationRelativeTo(null);
        }

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                ConfigManager.setInt("width", getWidth());
                ConfigManager.setInt("height", getHeight());
                Point p = getLocation();
                ConfigManager.setInt("x", p.x);
                ConfigManager.setInt("y", p.y);
                persistNavigationState();
                ConfigManager.save();
            }
        });

        initUI();

        String ssPath = System.getProperty("screenshot");
        if (ssPath != null && !ssPath.isEmpty()) {
            final String path = ssPath;
            javax.swing.Timer ssTimer = new javax.swing.Timer(2000, e -> takeScreenshot(path));
            ssTimer.setRepeats(false);
            ssTimer.start();
        }
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

    private void initUI() {
        setLayout(new BorderLayout(0, 0));
        add(buildWorkbench(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        installGlobalShortcuts();

        String restoredId = ToolNavigationState.resolveToolId(
                navigationModel,
                ConfigManager.get("nav.selectedTool", null));
        java.util.Set<String> expandedGroups = ToolNavigationState.parseExpandedGroups(
                ConfigManager.get("nav.expandedGroups", null),
                navigationModel.getGroupIds());
        sidebar.setExpandedGroupIds(expandedGroups);
        selectTool(restoredId);
        sidebar.setSelectedTool(restoredId);

        expandedSidebarWidth = ToolNavigationState.clampSidebarWidth(
                ConfigManager.getInt("nav.sidebarWidth", UIUtils.SIDEBAR_DEFAULT_WIDTH));
        boolean restoredCollapsed = Boolean.parseBoolean(
                ConfigManager.get("nav.sidebarCollapsed", "false"));
        sidebarCollapsed = false;
        SwingUtilities.invokeLater(() -> {
            workspaceSplit.setDividerLocation(expandedSidebarWidth);
            setSidebarCollapsed(restoredCollapsed);
        });
    }

    private JComponent buildWorkbench() {
        sidebar = new ToolSidebar(
                navigationModel,
                this::selectTool,
                () -> setSidebarCollapsed(true));
        sidebar.setMinimumSize(new Dimension(UIUtils.SIDEBAR_MIN_WIDTH, 0));
        sidebar.setPreferredSize(new Dimension(UIUtils.SIDEBAR_DEFAULT_WIDTH, 0));

        contentHost = new ToolContentHost(java.util.Arrays.asList(tools));
        contentHost.setBorder(new EmptyBorder(2, UIUtils.SPACE_SM, UIUtils.SPACE_XS, UIUtils.SPACE_XS));

        JPanel contentArea = new JPanel(new BorderLayout(0, 0));
        contentArea.add(buildToolBar(), BorderLayout.NORTH);
        contentArea.add(contentHost, BorderLayout.CENTER);

        workspaceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sidebar, contentArea);
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

    private JComponent buildToolBar() {
        JPanel bar = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
        bar.setBorder(new EmptyBorder(
                UIUtils.SPACE_SM, UIUtils.SPACE_MD,
                UIUtils.SPACE_SM, UIUtils.SPACE_MD));

        JPanel location = new JPanel(new BorderLayout(UIUtils.SPACE_SM, 0));
        expandSidebarButton = new JButton(I18n.get("nav.expand"));
        expandSidebarButton.addActionListener(event -> setSidebarCollapsed(false));
        expandSidebarButton.setVisible(false);
        currentToolLabel = new JLabel();
        currentToolLabel.setFont(UIUtils.titleFont());
        location.add(expandSidebarButton, BorderLayout.WEST);
        location.add(currentToolLabel, BorderLayout.CENTER);
        bar.add(location, BorderLayout.CENTER);

        JPanel settings = new JPanel(new FlowLayout(FlowLayout.RIGHT, UIUtils.SPACE_XS, 0));
        topThemeLabel = new JLabel(I18n.get("top.theme"));
        topThemeLabel.setFont(UIUtils.plainFont());
        JComboBox<String> themeBox = new JComboBox<>(ThemeManager.names());
        themeBox.setSelectedItem(ThemeManager.current().name);
        themeBox.setPreferredSize(new Dimension(160, 32));
        themeBox.addActionListener(event -> {
            ThemeManager.apply((String) themeBox.getSelectedItem());
            SwingUtilities.invokeLater(() -> {
                if (!sidebarCollapsed) workspaceSplit.setDividerLocation(expandedSidebarWidth);
            });
        });
        settings.add(topThemeLabel);
        settings.add(themeBox);

        topLangLabel = new JLabel(I18n.get("top.lang"));
        topLangLabel.setFont(UIUtils.plainFont());
        JComboBox<String> langBox = new JComboBox<>(new String[]{"简体中文", "English"});
        langBox.setSelectedIndex(
                "en_US".equals(ConfigManager.get("locale", "zh_CN")) ? 1 : 0);
        langBox.setPreferredSize(new Dimension(100, 32));
        langBox.addActionListener(event -> {
            String target = "English".equals(langBox.getSelectedItem()) ? "en_US" : "zh_CN";
            if (!target.equals(ConfigManager.get("locale", "zh_CN"))) {
                ConfigManager.set("locale", target);
                ConfigManager.save();
                I18n.init();
                reloadInPlace();
            }
        });
        settings.add(topLangLabel);
        settings.add(langBox);
        bar.add(settings, BorderLayout.EAST);

        JPanel wrapper = new JPanel(new BorderLayout(0, 0));
        wrapper.add(bar, BorderLayout.CENTER);
        wrapper.add(new JSeparator(), BorderLayout.SOUTH);
        return wrapper;
    }

    private void selectTool(String toolId) {
        ToolPanel tool = navigationModel.findTool(toolId);
        if (tool == null || !contentHost.showTool(toolId)) {
            return;
        }
        currentToolId = toolId;
        ConfigManager.set("nav.selectedTool", toolId);
        updateCurrentToolLabel();
    }

    private void updateCurrentToolLabel() {
        ToolPanel tool = navigationModel.findTool(currentToolId);
        String label = tool == null ? "" : tool.getGroupLabel() + " / " + tool.getLabel();
        currentToolLabel.setText(label);
        currentToolLabel.setToolTipText(label);
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
        expandSidebarButton.setVisible(collapsed);
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
                SwingUtilities.invokeLater(sidebar::focusSearch);
            }
        });
    }

    private void persistNavigationState() {
        ConfigManager.set("nav.selectedTool", currentToolId == null ? "" : currentToolId);
        ConfigManager.setInt("nav.sidebarWidth", expandedSidebarWidth);
        ConfigManager.set("nav.sidebarCollapsed", Boolean.toString(sidebarCollapsed));
        ConfigManager.set(
                "nav.expandedGroups",
                ToolNavigationState.serializeExpandedGroups(sidebar.getExpandedGroupIds()));
    }

    private void reloadInPlace() {
        setTitle(I18n.get("app.title"));
        topThemeLabel.setText(I18n.get("top.theme"));
        topLangLabel.setText(I18n.get("top.lang"));
        expandSidebarButton.setText(I18n.get("nav.expand"));
        sidebar.refreshLabels();
        sidebar.setSelectedTool(currentToolId);
        updateCurrentToolLabel();
        revalidate();
        repaint();
    }

    private JComponent buildStatusBar() {
        statusLabel = new JLabel();
        statusLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                new EmptyBorder(4, 8, 4, 8)));

        statusTimer = new javax.swing.Timer(2000, e -> updateStatusBar());
        statusTimer.setInitialDelay(0);
        statusTimer.start();

        return statusLabel;
    }

    private void updateStatusBar() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long max = rt.maxMemory();
        int memPct = max > 0 ? (int) (used * 100L / max) : 0;
        String jdkVer = System.getProperty("java.version");

        String cpuStr = "";
        try {
            java.lang.management.OperatingSystemMXBean osBean =
                    java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                double cpuLoad = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad();
                if (cpuLoad >= 0) {
                    cpuStr = String.format("CPU %.1f%%", cpuLoad * 100);
                }
            }
        } catch (Exception ignored) {
        }

        String cpuVal = cpuStr.isEmpty() ? "CPU --%" : cpuStr;
        String statusText = I18n.get(
                "status.ready", jdkVer, cpuVal, formatBytes(used), formatBytes(max), memPct);
        statusLabel.setText(statusText);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        return String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
