package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.algo.SearchPanel;
import com.aqishi.toolbox.algo.SortPanel;
import com.aqishi.toolbox.algo.HanoiPanel;
import com.aqishi.toolbox.calc.CalculatorPanel;
import com.aqishi.toolbox.calc.StatisticsPanel;
import com.aqishi.toolbox.convert.Base64ImagePanel;
import com.aqishi.toolbox.convert.ConvertPanel;
import com.aqishi.toolbox.convert.TimePanel;
import com.aqishi.toolbox.convert.FormatConvertPanel;
import com.aqishi.toolbox.crypto.AsymmetricPanel;
import com.aqishi.toolbox.crypto.CryptoPanel;
import com.aqishi.toolbox.crypto.SymmetricPanel;
import com.aqishi.toolbox.misc.ColorPanel;
import com.aqishi.toolbox.misc.CertPanel;
import com.aqishi.toolbox.misc.K8sPanel;
import com.aqishi.toolbox.misc.JsonPanel;
import com.aqishi.toolbox.misc.JwtPanel;
import com.aqishi.toolbox.misc.PasswordPanel;
import com.aqishi.toolbox.misc.RegexPanel;
import com.aqishi.toolbox.misc.UuidPanel;
import com.aqishi.toolbox.misc.XmlPanel;
import com.aqishi.toolbox.misc.SqlPanel;
import com.aqishi.toolbox.misc.CronPanel;
import com.aqishi.toolbox.misc.TextDiffPanel;
import com.aqishi.toolbox.misc.DockerComposePanel;
import com.aqishi.toolbox.misc.SubnetPanel;
import com.aqishi.toolbox.misc.HttpTestPanel;
import com.aqishi.toolbox.misc.CallbackTestPanel;
import com.aqishi.toolbox.monitor.VideoMonitorPanel;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.util.ConfigManager;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主窗口：顶部标题栏+主题切换，中间 JTabbedPane（一级大类），
 * 每个 Tab 内左侧 JList（二级工具）+ 右侧 CardLayout 内容区。
 * <p>所有颜色由 FlatLaf 外观包提供，切换主题即整窗刷新。</p>
 */
public class MainFrame extends JFrame {

    private JLabel statusLabel;
    private JTabbedPane tabs;
    private final Map<String, JList<String>> groupListMap = new java.util.HashMap<>();
    private final Map<String, CardLayout> groupCardMap = new java.util.HashMap<>();
    private final Map<String, JPanel> groupContentMap = new java.util.HashMap<>();
    private javax.swing.Timer statusTimer;
    
    private ToolPanel[] tools = createTools();

    private static ToolPanel[] createTools() {
        return new ToolPanel[]{
                new CryptoPanel(),
                new SymmetricPanel(),
                new AsymmetricPanel(),
                new ConvertPanel(),
                new TimePanel(),
                new Base64ImagePanel(),
                new FormatConvertPanel(),
                new JsonPanel(),
                new XmlPanel(),
                new SqlPanel(),
                new RegexPanel(),
                new JwtPanel(),
                new CronPanel(),
                new TextDiffPanel(),
                new DockerComposePanel(),
                new SubnetPanel(),
                new HttpTestPanel(),
                new CallbackTestPanel(),
                new ColorPanel(),
                new CertPanel(),
                new K8sPanel(),
                new UuidPanel(),
                new PasswordPanel(),
                new CalculatorPanel(),
                new StatisticsPanel(),
                new SortPanel(),
                new SearchPanel(),
                new HanoiPanel(),
                new VideoMonitorPanel(),
        };
    }

    public MainFrame() {
        super(I18n.get("app.title"));
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
                ConfigManager.save();
            }
        });

        initUI();

        // 如果指定了 -Dscreenshot=path，则延迟拍照后退出
        String ssPath = System.getProperty("screenshot");
        if (ssPath != null && !ssPath.isEmpty()) {
            final String path = ssPath;
            javax.swing.Timer ssTimer = new javax.swing.Timer(2000, e -> {
                takeScreenshot(path);
            });
            ssTimer.setRepeats(false);
            ssTimer.start();
        }
    }

    /** 对当前窗口截图并保存到文件 */
    private void takeScreenshot(String path) {
        try {
            Robot robot = new Robot();
            Rectangle bounds = getBounds();
            // 先确保窗口在最前面
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
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    /** 顶部栏：标题 + 搜索框 + 主题切换下拉框 */
    private JComponent buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                new EmptyBorder(8, 14, 8, 14)));

        JLabel title = new JLabel(I18n.get("top.title"));
        title.setFont(UIUtils.titleFont().deriveFont(16f));
        JPanel titleBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleBox.add(title);
        bar.add(titleBox, BorderLayout.WEST);

        // ===== 搜索框 =====
        JPanel searchBox = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        JTextField searchField = new JTextField();
        searchField.setPreferredSize(new Dimension(240, 30));
        searchField.putClientProperty("JTextField.placeholderText", I18n.get("top.search.placeholder"));
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchBox.add(searchField);
        bar.add(searchBox, BorderLayout.CENTER);

        JPopupMenu searchPopup = new JPopupMenu();
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { updateSearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { updateSearch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { updateSearch(); }

            private void updateSearch() {
                String q = searchField.getText().trim().toLowerCase();
                searchPopup.setVisible(false);
                searchPopup.removeAll();
                if (q.isEmpty()) return;

                int count = 0;
                for (ToolPanel t : tools) {
                    if (t.matchesSearch(q)) {
                        JMenuItem item = new JMenuItem(t.getGroupLabel() + " > " + t.getLabel());
                        item.setFont(UIUtils.plainFont());
                        item.addActionListener(ev -> {
                            selectTool(t);
                            SwingUtilities.invokeLater(() -> searchField.setText(""));
                        });
                        searchPopup.add(item);
                        count++;
                        if (count >= 10) break;
                    }
                }
                if (count > 0) {
                    searchPopup.show(searchField, 0, searchField.getHeight());
                    searchField.requestFocusInWindow();
                }
            }
        });

        // 右侧区：主题切换 + 语言切换
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        
        JLabel themeLabel = new JLabel(I18n.get("top.theme"));
        themeLabel.setFont(UIUtils.plainFont());
        JComboBox<String> themeBox = new JComboBox<>(ThemeManager.names());
        themeBox.setSelectedItem(ThemeManager.current().name);
        themeBox.setPreferredSize(new Dimension(160, 30));
        themeBox.addActionListener(e -> {
            String sel = (String) themeBox.getSelectedItem();
            ThemeManager.apply(sel);
        });
        right.add(themeLabel);
        right.add(themeBox);

        // 语言选择
        right.add(new JLabel("  ")); // Spacer
        JLabel langLabel = new JLabel(I18n.get("top.lang"));
        langLabel.setFont(UIUtils.plainFont());
        String currentLang = ConfigManager.get("locale", "zh_CN");
        JComboBox<String> langBox = new JComboBox<>(new String[]{"简体中文", "English"});
        langBox.setSelectedIndex("en_US".equals(currentLang) ? 1 : 0);
        langBox.setPreferredSize(new Dimension(100, 30));
        langBox.addActionListener(e -> {
            String selected = (String) langBox.getSelectedItem();
            String targetLocale = "English".equals(selected) ? "en_US" : "zh_CN";
            if (!targetLocale.equals(ConfigManager.get("locale", "zh_CN"))) {
                ConfigManager.set("locale", targetLocale);
                ConfigManager.save();
                I18n.init();
                reload(this);
            }
        });
        right.add(langLabel);
        right.add(langBox);

        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    private static void reload(MainFrame currentFrame) {
        ConfigManager.setInt("width", currentFrame.getWidth());
        ConfigManager.setInt("height", currentFrame.getHeight());
        Point p = currentFrame.getLocation();
        ConfigManager.setInt("x", p.x);
        ConfigManager.setInt("y", p.y);
        ConfigManager.save();

        SwingUtilities.invokeLater(() -> {
            currentFrame.reloadInPlace();
        });
    }

    /** 原地刷新界面文案，避免语言切换时销毁并重建窗口导致闪烁/消失。 */
    private void reloadInPlace() {
        if (statusTimer != null) {
            statusTimer.stop();
        }

        setTitle(I18n.get("app.title"));
        groupListMap.clear();
        groupCardMap.clear();
        groupContentMap.clear();
        tools = createTools();

        getContentPane().removeAll();
        initUI();
        revalidate();
        repaint();
    }

    /** 智能跳转选中特定的工具 */
    private void selectTool(ToolPanel targetTool) {
        String group = targetTool.getGroup();
        String groupTranslated = targetTool.getGroupLabel();
        int tabCount = tabs.getTabCount();
        for (int i = 0; i < tabCount; i++) {
            if (tabs.getTitleAt(i).equals(groupTranslated)) {
                tabs.setSelectedIndex(i);
                JList<String> list = groupListMap.get(group);
                CardLayout cards = groupCardMap.get(group);
                JPanel content = groupContentMap.get(group);
                if (list != null && cards != null && content != null) {
                    list.setSelectedValue(targetTool.getLabel(), true);
                    cards.show(content, targetTool.getName());
                }
                break;
            }
        }
    }

    /** 中部：页签 + 列表 + 内容区 */
    private JComponent buildCenter() {
        Map<String, java.util.List<ToolPanel>> grouped = new LinkedHashMap<>();
        for (ToolPanel t : tools)
            grouped.computeIfAbsent(t.getGroup(), k -> new java.util.ArrayList<>()).add(t);

        tabs = new JTabbedPane();
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setBorder(new EmptyBorder(4, 6, 6, 6));

        for (Map.Entry<String, java.util.List<ToolPanel>> entry : grouped.entrySet()) {
            tabs.addTab(I18n.get("group." + entry.getKey()), buildGroupTab(entry.getKey(), entry.getValue()));
        }
        return tabs;
    }

    /** 单个大类 Tab：左侧 JList + 右侧 CardLayout */
    private JComponent buildGroupTab(String groupName, java.util.List<ToolPanel> toolsList) {
        JPanel holder = new JPanel(new BorderLayout(0, 0));

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (ToolPanel t : toolsList) listModel.addElement(t.getLabel());
        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setFixedCellHeight(36);
        list.setFixedCellWidth(150);
        list.setFont(UIUtils.plainFont().deriveFont(14f));
        list.setBorder(new EmptyBorder(6, 8, 6, 8));
        
        groupListMap.put(groupName, list);

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setBorder(new MatteBorder(0, 0, 0, 1, UIManager.getColor("Component.borderColor")));
        listScroll.setPreferredSize(new Dimension(160, 0));

        CardLayout cards = new CardLayout();
        JPanel content = new JPanel(cards);
        content.setBorder(new EmptyBorder(2, 8, 4, 4));
        for (ToolPanel t : toolsList) content.add(t.getView(), t.getName());
        cards.show(content, toolsList.get(0).getName());
        
        groupCardMap.put(groupName, cards);
        groupContentMap.put(groupName, content);

        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int selectedIndex = list.getSelectedIndex();
            if (selectedIndex >= 0) cards.show(content, toolsList.get(selectedIndex).getName());
        });

        holder.add(listScroll, BorderLayout.WEST);
        holder.add(content, BorderLayout.CENTER);
        return holder;
    }

    /** 状态栏（含 JVM 内存/CPU 监控） */
    private JComponent buildStatusBar() {
        statusLabel = new JLabel();
        statusLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                new EmptyBorder(4, 8, 4, 8)));

        // 每秒刷新内存/CPU 信息
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

        // 进程 CPU 占用（非系统 CPU）
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
        String statusText = I18n.get("status.ready", jdkVer, cpuVal, formatBytes(used), formatBytes(max), memPct);
        statusLabel.setText(statusText);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1fMB", bytes / (1024.0 * 1024.0));
        return String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
