package com.aqishi.toolbox.ui;

import com.aqishi.toolbox.algo.SearchPanel;
import com.aqishi.toolbox.algo.SortPanel;
import com.aqishi.toolbox.calc.CalculatorPanel;
import com.aqishi.toolbox.calc.StatisticsPanel;
import com.aqishi.toolbox.convert.Base64ImagePanel;
import com.aqishi.toolbox.convert.ConvertPanel;
import com.aqishi.toolbox.convert.TimePanel;
import com.aqishi.toolbox.crypto.AsymmetricPanel;
import com.aqishi.toolbox.crypto.CryptoPanel;
import com.aqishi.toolbox.crypto.SymmetricPanel;
import com.aqishi.toolbox.misc.ColorPanel;
import com.aqishi.toolbox.misc.JsonPanel;
import com.aqishi.toolbox.misc.JwtPanel;
import com.aqishi.toolbox.misc.PasswordPanel;
import com.aqishi.toolbox.misc.RegexPanel;
import com.aqishi.toolbox.misc.UuidPanel;
import com.aqishi.toolbox.util.UIUtils;

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

    public MainFrame() {
        super("Java 工具箱 v1.2");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1024, 660);
        setMinimumSize(new Dimension(820, 520));
        setLocationRelativeTo(null);
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(0, 0));
        add(buildTopBar(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
    }

    /** 顶部栏：标题 + 主题切换下拉框 */
    private JComponent buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                new EmptyBorder(8, 14, 8, 14)));

        JLabel title = new JLabel("Java 工具箱");
        title.setFont(UIUtils.titleFont().deriveFont(16f));
        JLabel subtitle = new JLabel("  ·  算法 / 加密 / 转换 / 计算 / 杂项");
        subtitle.setFont(UIUtils.plainFont());
        subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel titleBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        titleBox.add(title);
        titleBox.add(subtitle);
        bar.add(titleBox, BorderLayout.WEST);

        // 主题切换
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JLabel themeLabel = new JLabel("主题");
        themeLabel.setFont(UIUtils.plainFont());
        JComboBox<String> themeBox = new JComboBox<>(ThemeManager.names());
        themeBox.setSelectedItem(ThemeManager.current().name);
        themeBox.setPreferredSize(new Dimension(170, 30));
        themeBox.addActionListener(e -> {
            String sel = (String) themeBox.getSelectedItem();
            ThemeManager.apply(sel);
        });
        right.add(themeLabel);
        right.add(themeBox);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    /** 中部：页签 + 列表 + 内容区 */
    private JComponent buildCenter() {
        ToolPanel[] tools = {
                new CryptoPanel(),
                new SymmetricPanel(),
                new AsymmetricPanel(),
                new ConvertPanel(),
                new TimePanel(),
                new Base64ImagePanel(),
                new SortPanel(),
                new SearchPanel(),
                new CalculatorPanel(),
                new StatisticsPanel(),
                new RegexPanel(),
                new UuidPanel(),
                new PasswordPanel(),
                new JwtPanel(),
                new JsonPanel(),
                new ColorPanel(),
        };
        Map<String, java.util.List<ToolPanel>> grouped = new LinkedHashMap<>();
        for (ToolPanel t : tools)
            grouped.computeIfAbsent(t.getGroup(), k -> new java.util.ArrayList<>()).add(t);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        tabs.setBorder(new EmptyBorder(4, 6, 6, 6));

        for (Map.Entry<String, java.util.List<ToolPanel>> entry : grouped.entrySet()) {
            tabs.addTab(entry.getKey(), buildGroupTab(entry.getValue()));
        }
        return tabs;
    }

    /** 单个大类 Tab：左侧 JList + 右侧 CardLayout */
    private JComponent buildGroupTab(java.util.List<ToolPanel> tools) {
        JPanel holder = new JPanel(new BorderLayout(0, 0));

        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (ToolPanel t : tools) listModel.addElement(t.getName());
        JList<String> list = new JList<>(listModel);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        list.setFixedCellHeight(36);
        list.setFixedCellWidth(150);
        list.setFont(UIUtils.plainFont().deriveFont(14f));
        list.setBorder(new EmptyBorder(6, 8, 6, 8));

        JScrollPane listScroll = new JScrollPane(list);
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        listScroll.setBorder(new MatteBorder(0, 0, 0, 1, UIManager.getColor("Component.borderColor")));
        listScroll.setPreferredSize(new Dimension(160, 0));

        CardLayout cards = new CardLayout();
        JPanel content = new JPanel(cards);
        content.setBorder(new EmptyBorder(2, 8, 4, 4));
        for (ToolPanel t : tools) content.add(t.getView(), t.getName());
        cards.show(content, tools.get(0).getName());

        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            String sel = list.getSelectedValue();
            if (sel != null) cards.show(content, sel);
        });

        holder.add(listScroll, BorderLayout.WEST);
        holder.add(content, BorderLayout.CENTER);
        return holder;
    }

    /** 状态栏 */
    private JComponent buildStatusBar() {
        statusLabel = new JLabel("  就绪  |  JDK " + System.getProperty("java.version")
                + "  |  双击 run.bat 或 java -jar java-toolbox.jar 启动");
        statusLabel.setFont(UIUtils.plainFont().deriveFont(12f));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                new EmptyBorder(4, 8, 4, 8)));
        return statusLabel;
    }
}
