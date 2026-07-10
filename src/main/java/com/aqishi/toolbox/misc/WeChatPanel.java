package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 微信群发助手面板：通过 Java Robot 模拟按键实现微信 PC 端自动搜索联系人并发送消息。
 * 支持手动录入、导入 Excel 以及导出模板。
 */
public class WeChatPanel extends ToolPanel {

    private JTable contactTable;
    private DefaultTableModel tableModel;
    private JTextArea logArea;
    private JButton startBtn;
    private JButton stopBtn;
    private JButton importBtn;
    private JButton exportTplBtn;
    private JButton addRowBtn;
    private JButton addImgRowBtn;
    private JButton clearBtn;
    private JProgressBar progressBar;

    // Config inputs
    private JTextField wakeupDelayField;
    private JTextField searchDelayField;
    private JTextField intervalField;
    private JComboBox<String> sendKeyCombo;
    private JCheckBox confirmBeforeSendCb;
    private JCheckBox enableGlobalImgCb;
    private DefaultListModel<String> globalImgListModel;
    private JList<String> globalImgList;
    private JButton addGlobalImgBtn;
    private JButton delGlobalImgBtn;
    private JButton clearGlobalImgBtn;

    // 通讯录管理新增字段
    private JTabbedPane tabbedPane;
    private JTable wxcTable;
    private DefaultTableModel wxcTableModel;
    private JTextField wxcDbPathField;
    private JButton wxcBrowseBtn;
    private JButton wxcLoadBtn;
    private JTextField wxcSearchField;
    private JComboBox<String> wxcFilterCombo;
    private JButton wxcAddToSendBtn;
    private JButton wxcExportBtn;
    private JButton wxcDownloadAvatarBtn;
    private JButton wxcSelectAllBtn;
    private JLabel wxcStatsLabel;
    private JProgressBar wxcProgressBar;
    private java.util.List<WeChatContactReader.ContactInfo> allContacts = new java.util.ArrayList<>();

    private volatile boolean stopRequested = false;
    private Thread sendThread = null;

    public WeChatPanel() {
        super("dev", "wechat.sender",
                "微信", "群发", "WeChat", "批量", "发送", "模拟按键", "联系人");
    }

    @Override
    protected JComponent build() {
        tabbedPane = new JTabbedPane();

        JPanel sendPanel = buildSendPanel();
        JPanel contactPanel = buildContactPanel();

        tabbedPane.addTab("群发助手", sendPanel);
        tabbedPane.addTab("通讯录管理", contactPanel);

        setupListeners();
        setupContactListeners();

        return tabbedPane;
    }

    private JPanel buildSendPanel() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // 1. Top Settings Panel
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        settingsPanel.setBorder(BorderFactory.createTitledBorder("微信群发配置参数"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(4, 8, 4, 8);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        settingsPanel.add(new JLabel("唤醒微信快捷键:"), gbc);
        JTextField wakeupKeyField = new JTextField("Ctrl + Alt + W");
        wakeupKeyField.setEditable(false);
        wakeupKeyField.setPreferredSize(new Dimension(100, 26));
        gbc.gridx = 1; gbc.weightx = 0.2;
        settingsPanel.add(wakeupKeyField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        settingsPanel.add(new JLabel("检索延时(毫秒):"), gbc);
        searchDelayField = new JTextField("800");
        searchDelayField.setPreferredSize(new Dimension(80, 26));
        gbc.gridx = 3; gbc.weightx = 0.2;
        settingsPanel.add(searchDelayField, gbc);

        gbc.gridx = 4; gbc.weightx = 0;
        settingsPanel.add(new JLabel("微信发送按键:"), gbc);
        sendKeyCombo = new JComboBox<>(new String[]{"Enter", "Ctrl + Enter"});
        sendKeyCombo.setPreferredSize(new Dimension(100, 26));
        gbc.gridx = 5; gbc.weightx = 0.2;
        settingsPanel.add(sendKeyCombo, gbc);

        // Row 2 Settings
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        settingsPanel.add(new JLabel("搜索框快捷键:"), gbc);
        JTextField searchKeyField = new JTextField("Ctrl + F");
        searchKeyField.setEditable(false);
        gbc.gridx = 1; gbc.weightx = 0.2;
        settingsPanel.add(searchKeyField, gbc);

        gbc.gridx = 2; gbc.weightx = 0;
        settingsPanel.add(new JLabel("唤醒延时(毫秒):"), gbc);
        wakeupDelayField = new JTextField("500");
        gbc.gridx = 3; gbc.weightx = 0.2;
        settingsPanel.add(wakeupDelayField, gbc);

        gbc.gridx = 4; gbc.weightx = 0;
        settingsPanel.add(new JLabel("发送间隔(毫秒):"), gbc);
        intervalField = new JTextField("2000");
        gbc.gridx = 5; gbc.weightx = 0.2;
        settingsPanel.add(intervalField, gbc);

        // Row 3 Settings: Safety options
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 1; gbc.weightx = 0;
        settingsPanel.add(new JLabel("安全模式:"), gbc);

        confirmBeforeSendCb = new JCheckBox("发送前人工确认 (防错发/防失效)");
        confirmBeforeSendCb.setSelected(false);
        gbc.gridx = 1; gbc.gridwidth = 3; gbc.weightx = 0.5;
        settingsPanel.add(confirmBeforeSendCb, gbc);

        JLabel tipsLabel = new JLabel("<html><b>防错提示：</b>强烈建议使用微信唯一备注名！若搜索不到弹窗，本工具会自动发送 Esc 关闭恢复。</html>");
        tipsLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        gbc.gridx = 4; gbc.gridwidth = 2; gbc.weightx = 0.5;
        settingsPanel.add(tipsLabel, gbc);

        // 全局图片附件面板
        JPanel globalImgPanel = new JPanel(new BorderLayout(6, 6));
        globalImgPanel.setBorder(BorderFactory.createTitledBorder("全局附加图片 (发送时将连同文本依次发送)"));

        JPanel ctrlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        enableGlobalImgCb = new JCheckBox("启用全局图片附件");
        addGlobalImgBtn = new JButton("添加图片");
        delGlobalImgBtn = new JButton("删除");
        clearGlobalImgBtn = new JButton("清空");
        ctrlPanel.add(enableGlobalImgCb);
        ctrlPanel.add(addGlobalImgBtn);
        ctrlPanel.add(delGlobalImgBtn);
        ctrlPanel.add(clearGlobalImgBtn);
        globalImgPanel.add(ctrlPanel, BorderLayout.NORTH);

        globalImgListModel = new DefaultListModel<>();
        globalImgList = new JList<>(globalImgListModel);
        globalImgList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane scrollList = new JScrollPane(globalImgList);
        scrollList.setPreferredSize(new Dimension(0, 65)); // 紧凑高度
        globalImgPanel.add(scrollList, BorderLayout.CENTER);

        // 组合 settingsPanel 和 globalImgPanel
        JPanel northPanel = new JPanel(new BorderLayout(4, 4));
        northPanel.add(settingsPanel, BorderLayout.NORTH);
        northPanel.add(globalImgPanel, BorderLayout.SOUTH);

        root.add(northPanel, BorderLayout.NORTH);

        // 2. Middle Split: Contacts Table & Logs
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setDividerLocation(260);

        // Table Panel
        JPanel tablePanel = new JPanel(new BorderLayout(4, 4));
        tableModel = new DefaultTableModel(new String[]{"ID", "联系人/备注(精准名称)", "发送内容", "状态", "发送时间"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 1 || column == 2; // Only allow editing name and message
            }
        };
        contactTable = new JTable(tableModel);
        contactTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane tableScroll = new JScrollPane(contactTable);
        tablePanel.add(tableScroll, BorderLayout.CENTER);

        // Table actions
        JPanel tableActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        addRowBtn = new JButton("添加一行");
        addImgRowBtn = new JButton("添加图片行");
        clearBtn = new JButton("清空表格");
        importBtn = new JButton("导入 Excel");
        exportTplBtn = new JButton("导出模板");
        tableActions.add(addRowBtn);
        tableActions.add(addImgRowBtn);
        tableActions.add(clearBtn);
        tableActions.add(importBtn);
        tableActions.add(exportTplBtn);
        tablePanel.add(tableActions, BorderLayout.SOUTH);

        splitPane.setTopComponent(tablePanel);

        // Log / Console Panel
        JPanel logPanel = new JPanel(new BorderLayout(4, 4));
        logPanel.setBorder(BorderFactory.createTitledBorder("执行日志与注意事项"));
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(UIUtils.monoFont());
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        // Action Toolbar
        JPanel actionToolbar = new JPanel(new BorderLayout(6, 6));
        progressBar = new JProgressBar();
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(200, 26));
        actionToolbar.add(progressBar, BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        startBtn = UIUtils.button("开始群发", 100);
        stopBtn = UIUtils.button("停止群发", 100);
        stopBtn.setEnabled(false);
        btnPanel.add(startBtn);
        btnPanel.add(stopBtn);
        actionToolbar.add(btnPanel, BorderLayout.EAST);
        logPanel.add(actionToolbar, BorderLayout.SOUTH);

        splitPane.setBottomComponent(logPanel);
        root.add(splitPane, BorderLayout.CENTER);

        // Print initial warning logs
        logArea.setText("【使用说明与注意事项】\n" +
                "1. 本工具利用 Java Robot 模拟键盘按键唤醒微信，微信窗口需要能被 Ctrl+Alt+W 唤醒（或提前点击打开微信置于最前）。\n" +
                "2. 支持发送【文本】与【图片】：若发送内容为本地图片的绝对路径（如 D:\\pic.png，支持 png/jpg/jpeg/gif/bmp），将自动读取并作为图片发送。可点击“添加图片行”快速选择本地图片。\n" +
                "3. 发送前，请确保微信中已有这些联系人（支持完整昵称、备注或精准的微信号），名字不要有错别字。\n" +
                "4. 发送期间请不要移动鼠标或操作键盘，以免干扰模拟焦点导致消息错乱。\n" +
                "5. 微信发送按键请与微信电脑端设置一致（可在微信设置中查看是 Enter 还是 Ctrl+Enter）。\n" +
                "6. 建议将发送间隔设置为 2000 毫秒以上，避免发送过快导致微信接口限制。\n\n");

        return root;
    }

    private JPanel buildContactPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(UIUtils.CONTENT_PADDING);

        // 1. Top Panel: Database File Selector & Load
        JPanel topPanel = new JPanel(new GridBagLayout());
        topPanel.setBorder(BorderFactory.createTitledBorder("数据导入与说明"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 8, 6, 8);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        topPanel.add(new JLabel("解密后的数据库 (.db) 文件:"), gbc);

        wxcDbPathField = new JTextField();
        wxcDbPathField.setPreferredSize(new Dimension(250, 26));
        gbc.gridx = 1; gbc.weightx = 1.0;
        topPanel.add(wxcDbPathField, gbc);

        wxcBrowseBtn = new JButton("浏览...");
        wxcBrowseBtn.setPreferredSize(new Dimension(80, 26));
        gbc.gridx = 2; gbc.weightx = 0;
        topPanel.add(wxcBrowseBtn, gbc);

        wxcLoadBtn = UIUtils.button("读取通讯录", 100);
        gbc.gridx = 3;
        topPanel.add(wxcLoadBtn, gbc);

        // Usage Tip Label
        JLabel tipLabel = new JLabel("<html><b>使用说明：</b>微信本地数据库默认加密，请先使用 <b>PyWxDump</b> 等解密工具将微信的 <b>MicroMsg.db</b> 或 <b>contact.db</b> 解密为标准的 SQLite 文件，再在此导入。</html>");
        tipLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 4; gbc.weightx = 1.0;
        topPanel.add(tipLabel, gbc);

        panel.add(topPanel, BorderLayout.NORTH);

        // 2. Center Panel: Filters & Table
        JPanel centerPanel = new JPanel(new BorderLayout(6, 6));

        // Filter bar
        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filterBar.add(new JLabel("搜索(昵称/备注/微信号):"));
        wxcSearchField = new JTextField(15);
        wxcSearchField.setPreferredSize(new Dimension(150, 26));
        filterBar.add(wxcSearchField);

        filterBar.add(new JLabel("关系分类:"));
        wxcFilterCombo = new JComboBox<>(new String[]{"全部联系人", "仅限好友", "仅限群聊", "仅限公众号"});
        wxcFilterCombo.setPreferredSize(new Dimension(110, 26));
        filterBar.add(wxcFilterCombo);

        wxcSelectAllBtn = new JButton("全选/反选");
        wxcSelectAllBtn.setPreferredSize(new Dimension(95, 26));
        filterBar.add(wxcSelectAllBtn);

        wxcStatsLabel = new JLabel("当前显示: 0 | 已选择: 0");
        wxcStatsLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        filterBar.add(wxcStatsLabel);

        centerPanel.add(filterBar, BorderLayout.NORTH);

        // Table
        wxcTableModel = new DefaultTableModel(new Object[]{"选择", "昵称", "微信号", "备注名", "性别", "头像链接", "原始ID (UserName)"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                return super.getColumnClass(columnIndex);
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
        };

        wxcTable = new JTable(wxcTableModel);
        wxcTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        wxcTable.getTableHeader().setReorderingAllowed(false);

        // Setup table sorter and filtering
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(wxcTableModel);
        wxcTable.setRowSorter(sorter);

        // Set column widths
        wxcTable.getColumnModel().getColumn(0).setPreferredWidth(45);
        wxcTable.getColumnModel().getColumn(0).setMaxWidth(60);
        wxcTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        wxcTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        wxcTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        wxcTable.getColumnModel().getColumn(4).setPreferredWidth(50);
        wxcTable.getColumnModel().getColumn(5).setPreferredWidth(180);
        wxcTable.getColumnModel().getColumn(6).setPreferredWidth(150);

        JScrollPane tableScroll = new JScrollPane(wxcTable);
        centerPanel.add(tableScroll, BorderLayout.CENTER);

        panel.add(centerPanel, BorderLayout.CENTER);

        // 3. Bottom Panel: Actions & Progress
        JPanel bottomPanel = new JPanel(new BorderLayout(6, 6));

        JPanel actionBtnLeftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        wxcAddToSendBtn = UIUtils.button("添加到群发列表", 140);
        actionBtnLeftPanel.add(wxcAddToSendBtn);

        JPanel actionBtnRightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        wxcExportBtn = new JButton("导出通讯录 Excel");
        wxcDownloadAvatarBtn = new JButton("批量下载头像");
        actionBtnRightPanel.add(wxcExportBtn);
        actionBtnRightPanel.add(wxcDownloadAvatarBtn);

        JPanel actionBtnContainer = new JPanel(new BorderLayout());
        actionBtnContainer.add(actionBtnLeftPanel, BorderLayout.WEST);
        actionBtnContainer.add(actionBtnRightPanel, BorderLayout.EAST);
        bottomPanel.add(actionBtnContainer, BorderLayout.NORTH);

        wxcProgressBar = new JProgressBar();
        wxcProgressBar.setStringPainted(true);
        wxcProgressBar.setPreferredSize(new Dimension(200, 24));
        wxcProgressBar.setVisible(false);
        bottomPanel.add(wxcProgressBar, BorderLayout.SOUTH);

        panel.add(bottomPanel, BorderLayout.SOUTH);

        return panel;
    }

    private void setupContactListeners() {
        wxcBrowseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileFilter(new FileNameExtensionFilter("SQLite 数据库文件 (*.db; *.sqlite)", "db", "sqlite"));
            if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
                wxcDbPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        wxcLoadBtn.addActionListener(e -> {
            String path = wxcDbPathField.getText().trim();
            if (path.isEmpty()) {
                UIUtils.error(getView(), "请先选择或输入解密后的数据库文件路径！");
                return;
            }
            File file = new File(path);
            if (!file.exists()) {
                UIUtils.error(getView(), "数据库文件不存在，请检查路径！");
                return;
            }

            wxcLoadBtn.setEnabled(false);
            wxcProgressBar.setValue(0);
            wxcProgressBar.setString("正在读取数据库...");
            wxcProgressBar.setIndeterminate(true);
            wxcProgressBar.setVisible(true);

            new SwingWorker<java.util.List<WeChatContactReader.ContactInfo>, Void>() {
                @Override
                protected java.util.List<WeChatContactReader.ContactInfo> doInBackground() throws Exception {
                    return WeChatContactReader.readContacts(file);
                }

                @Override
                protected void done() {
                    wxcLoadBtn.setEnabled(true);
                    wxcProgressBar.setVisible(false);
                    wxcProgressBar.setIndeterminate(false);
                    try {
                        allContacts = get();
                        wxcTableModel.setRowCount(0);
                        for (WeChatContactReader.ContactInfo c : allContacts) {
                            String genderStr = "未知";
                            if (c.gender == 1) genderStr = "男";
                            else if (c.gender == 2) genderStr = "女";
                            wxcTableModel.addRow(new Object[]{
                                    false,
                                    c.nickname != null ? c.nickname : "",
                                    c.alias != null ? c.alias : "",
                                    c.remark != null ? c.remark : "",
                                    genderStr,
                                    c.avatarUrl != null ? c.avatarUrl : "",
                                    c.username
                            });
                        }
                        filterWxcTable();
                        UIUtils.info(getView(), "读取成功！共加载了 " + allContacts.size() + " 个联系人。");
                    } catch (Exception ex) {
                        UIUtils.error(getView(), "读取通讯录失败:\n" + ex.getMessage());
                        ex.printStackTrace();
                    }
                }
            }.execute();
        });

        wxcSearchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterWxcTable(); }
            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterWxcTable(); }
            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterWxcTable(); }
        });

        wxcFilterCombo.addActionListener(e -> filterWxcTable());

        wxcSelectAllBtn.addActionListener(e -> {
            int rowCount = wxcTable.getRowCount();
            if (rowCount == 0) return;
            boolean anyUnchecked = false;
            for (int i = 0; i < rowCount; i++) {
                int modelRow = wxcTable.convertRowIndexToModel(i);
                Boolean val = (Boolean) wxcTableModel.getValueAt(modelRow, 0);
                if (val == null || !val) {
                    anyUnchecked = true;
                    break;
                }
            }
            for (int i = 0; i < rowCount; i++) {
                int modelRow = wxcTable.convertRowIndexToModel(i);
                wxcTableModel.setValueAt(anyUnchecked, modelRow, 0);
            }
            updateWxcStats();
        });

        wxcTableModel.addTableModelListener(e -> {
            if (e.getColumn() == 0) {
                updateWxcStats();
            }
        });

        wxcAddToSendBtn.addActionListener(e -> {
            int added = 0;
            for (int i = 0; i < wxcTableModel.getRowCount(); i++) {
                Boolean val = (Boolean) wxcTableModel.getValueAt(i, 0);
                if (val != null && val) {
                    String nickname = (String) wxcTableModel.getValueAt(i, 1);
                    String alias = (String) wxcTableModel.getValueAt(i, 2);
                    String remark = (String) wxcTableModel.getValueAt(i, 3);
                    String username = (String) wxcTableModel.getValueAt(i, 6);

                    String targetName = remark;
                    if (targetName == null || targetName.trim().isEmpty()) {
                        targetName = nickname;
                    }
                    if (targetName == null || targetName.trim().isEmpty()) {
                        targetName = alias;
                    }
                    if (targetName == null || targetName.trim().isEmpty()) {
                        targetName = username;
                    }

                    int id = tableModel.getRowCount() + 1;
                    tableModel.addRow(new Object[]{id, targetName, "", "待发送", ""});
                    added++;
                }
            }
            if (added == 0) {
                UIUtils.info(getView(), "请先在通讯录表格中勾选要添加的联系人！");
            } else {
                UIUtils.info(getView(), "成功添加 " + added + " 个联系人到群发列表！");
                tabbedPane.setSelectedIndex(0);
            }
        });

        wxcExportBtn.addActionListener(e -> {
            if (allContacts.isEmpty()) {
                UIUtils.error(getView(), "当前无联系人数据，请先读取数据库！");
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setSelectedFile(new File("微信通讯录导出.xlsx"));
            chooser.setFileFilter(new FileNameExtensionFilter("Excel 2007 文件 (*.xlsx)", "xlsx"));
            if (chooser.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();

                java.util.List<WeChatContactReader.ContactInfo> toExport = new java.util.ArrayList<>();
                for (int i = 0; i < wxcTableModel.getRowCount(); i++) {
                    Boolean val = (Boolean) wxcTableModel.getValueAt(i, 0);
                    if (val != null && val) {
                        String username = (String) wxcTableModel.getValueAt(i, 6);
                        WeChatContactReader.ContactInfo info = findContactByUsername(username);
                        if (info != null) toExport.add(info);
                    }
                }

                if (toExport.isEmpty()) {
                    toExport = allContacts;
                }

                final java.util.List<WeChatContactReader.ContactInfo> finalToExport = toExport;
                wxcProgressBar.setIndeterminate(true);
                wxcProgressBar.setString("正在导出 Excel...");
                wxcProgressBar.setVisible(true);

                new SwingWorker<Void, Void>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        WeChatContactReader.exportContactsToExcel(finalToExport, file);
                        return null;
                    }

                    @Override
                    protected void done() {
                        wxcProgressBar.setVisible(false);
                        wxcProgressBar.setIndeterminate(false);
                        try {
                            get();
                            UIUtils.info(getView(), "通讯录导出成功！");
                        } catch (Exception ex) {
                            UIUtils.error(getView(), "导出失败:\n" + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }
                }.execute();
            }
        });

        wxcDownloadAvatarBtn.addActionListener(e -> {
            java.util.List<WeChatContactReader.ContactInfo> selected = new java.util.ArrayList<>();
            for (int i = 0; i < wxcTableModel.getRowCount(); i++) {
                Boolean val = (Boolean) wxcTableModel.getValueAt(i, 0);
                if (val != null && val) {
                    String username = (String) wxcTableModel.getValueAt(i, 6);
                    WeChatContactReader.ContactInfo info = findContactByUsername(username);
                    if (info != null) selected.add(info);
                }
            }

            if (selected.isEmpty()) {
                UIUtils.error(getView(), "请先勾选需要下载头像的联系人！");
                return;
            }

            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("选择头像保存文件夹");
            if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
                File dir = chooser.getSelectedFile();

                wxcDownloadAvatarBtn.setEnabled(false);
                wxcProgressBar.setIndeterminate(false);
                wxcProgressBar.setMaximum(selected.size());
                wxcProgressBar.setValue(0);
                wxcProgressBar.setString("正在准备下载...");
                wxcProgressBar.setVisible(true);

                new SwingWorker<Void, String>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        WeChatContactReader.downloadAvatars(selected, dir, (current, total, status) -> {
                            publish(current + "," + total + "," + status);
                        });
                        return null;
                    }

                    @Override
                    protected void process(java.util.List<String> chunks) {
                        if (!chunks.isEmpty()) {
                            String last = chunks.get(chunks.size() - 1);
                            String[] parts = last.split(",", 3);
                            int cur = Integer.parseInt(parts[0]);
                            int tot = Integer.parseInt(parts[1]);
                            String msg = parts[2];

                            wxcProgressBar.setValue(cur);
                            wxcProgressBar.setString(String.format("正在下载头像 (%d/%d): %s", cur, tot, msg));
                        }
                    }

                    @Override
                    protected void done() {
                        wxcDownloadAvatarBtn.setEnabled(true);
                        wxcProgressBar.setVisible(false);
                        try {
                            get();
                            UIUtils.info(getView(), "头像下载完成！保存目录为：" + dir.getAbsolutePath());
                        } catch (Exception ex) {
                            UIUtils.error(getView(), "下载异常终止:\n" + ex.getMessage());
                        }
                    }
                }.execute();
            }
        });
    }

    private void filterWxcTable() {
        String text = wxcSearchField.getText().trim();
        String filterType = (String) wxcFilterCombo.getSelectedItem();
        TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) wxcTable.getRowSorter();

        RowFilter<DefaultTableModel, Object> rf = new RowFilter<DefaultTableModel, Object>() {
            @Override
            public boolean include(Entry<? extends DefaultTableModel, ?> entry) {
                boolean matchesSearch = false;
                String searchStr = text.toLowerCase();
                if (searchStr.isEmpty()) {
                    matchesSearch = true;
                } else {
                    for (int i = 1; i < entry.getValueCount(); i++) {
                        Object val = entry.getValue(i);
                        if (val != null && val.toString().toLowerCase().contains(searchStr)) {
                            matchesSearch = true;
                            break;
                        }
                    }
                }

                if (!matchesSearch) return false;

                String username = (String) entry.getValue(6);
                WeChatContactReader.ContactInfo info = findContactByUsername(username);
                if (info == null) return true;

                if ("仅限好友".equals(filterType)) {
                    if (username.endsWith("@chatroom")) return false;
                    if (username.startsWith("gh_")) return false;
                    return (info.type & 2) != 0 || (info.type & 1) != 0 || (info.type == 3) || (info.type == 11) || (info.type == 35);
                } else if ("仅限群聊".equals(filterType)) {
                    return username.endsWith("@chatroom");
                } else if ("仅限公众号".equals(filterType)) {
                    return username.startsWith("gh_");
                }

                return true;
            }
        };
        sorter.setRowFilter(rf);
        updateWxcStats();
    }

    private void updateWxcStats() {
        int total = wxcTable.getRowCount();
        int selected = 0;
        for (int i = 0; i < wxcTableModel.getRowCount(); i++) {
            Boolean val = (Boolean) wxcTableModel.getValueAt(i, 0);
            if (val != null && val) {
                selected++;
            }
        }
        wxcStatsLabel.setText(String.format("当前显示: %d | 已选择: %d", total, selected));
    }

    private WeChatContactReader.ContactInfo findContactByUsername(String username) {
        if (username == null) return null;
        for (WeChatContactReader.ContactInfo c : allContacts) {
            if (username.equals(c.username)) {
                return c;
            }
        }
        return null;
    }

    private void setupListeners() {
        addRowBtn.addActionListener(e -> {
            int id = tableModel.getRowCount() + 1;
            tableModel.addRow(new Object[]{id, "输入联系人名称", "输入发送内容", "待发送", ""});
        });

        addImgRowBtn.addActionListener(e -> addImageRow());

        clearBtn.addActionListener(e -> {
            if (tableModel.getRowCount() > 0) {
                int opt = JOptionPane.showConfirmDialog(getView(), "确定要清空所有的联系人列表吗？", "确认清空", JOptionPane.YES_NO_OPTION);
                if (opt == JOptionPane.YES_OPTION) {
                    tableModel.setRowCount(0);
                }
            }
        });

        importBtn.addActionListener(e -> importExcel());
        exportTplBtn.addActionListener(e -> exportTemplate());

        startBtn.addActionListener(e -> startBulkSend());
        stopBtn.addActionListener(e -> stopBulkSend());

        addGlobalImgBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setMultiSelectionEnabled(true);
            chooser.setFileFilter(new FileNameExtensionFilter("图片文件 (*.png; *.jpg; *.jpeg; *.gif; *.bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
            if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
                File[] files = chooser.getSelectedFiles();
                for (File f : files) {
                    globalImgListModel.addElement(f.getAbsolutePath());
                }
            }
        });

        delGlobalImgBtn.addActionListener(e -> {
            int idx = globalImgList.getSelectedIndex();
            if (idx >= 0) {
                globalImgListModel.remove(idx);
            }
        });

        clearGlobalImgBtn.addActionListener(e -> globalImgListModel.clear());
    }

    private void addImageRow() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("图片文件 (*.png; *.jpg; *.jpeg; *.gif; *.bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            int id = tableModel.getRowCount() + 1;
            tableModel.addRow(new Object[]{id, "输入联系人名称", file.getAbsolutePath(), "待发送", ""});
        }
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + new SimpleDateFormat("HH:mm:ss").format(new Date()) + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void updateRowStatus(int row, String status, String time) {
        SwingUtilities.invokeLater(() -> {
            tableModel.setValueAt(status, row, 3);
            tableModel.setValueAt(time, row, 4);
        });
    }

    private void importExcel() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Excel 文件 (*.xlsx; *.xls)", "xlsx", "xls"));
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            new SwingWorker<Integer, Void>() {
                private String errorMsg = null;
                private int added = 0;

                @Override
                protected Integer doInBackground() throws Exception {
                    try (Workbook workbook = file.getName().endsWith(".xlsx") ? 
                            new XSSFWorkbook(new FileInputStream(file)) : new HSSFWorkbook(new FileInputStream(file))) {
                        
                        Sheet sheet = workbook.getSheetAt(0);
                        int rowCount = sheet.getLastRowNum();
                        for (int i = 1; i <= rowCount; i++) {
                            Row row = sheet.getRow(i);
                            if (row == null) continue;
                            Cell c0 = row.getCell(0);
                            Cell c1 = row.getCell(1);
                            
                            String name = c0 != null ? getCellValueAsString(c0) : "";
                            String content = c1 != null ? getCellValueAsString(c1) : "";
                            
                            if (!name.isEmpty()) {
                                final int id = tableModel.getRowCount() + 1;
                                SwingUtilities.invokeLater(() -> 
                                    tableModel.addRow(new Object[]{id, name, content, "待发送", ""})
                                );
                                added++;
                            }
                        }
                    }
                    return added;
                }

                @Override
                protected void done() {
                    try {
                        int count = get();
                        UIUtils.info(getView(), "导入成功！共导入 " + count + " 条有效联系人。");
                        log("成功从 Excel 导入 " + count + " 条数据。");
                    } catch (Exception ex) {
                        UIUtils.error(getView(), "导入失败:\n" + ex.getMessage());
                        log("导入失败: " + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        switch (cell.getCellType()) {
            case STRING: return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return new SimpleDateFormat("yyyy-MM-dd").format(cell.getDateCellValue());
                }
                double dVal = cell.getNumericCellValue();
                if (dVal == (long) dVal) {
                    return String.valueOf((long) dVal);
                }
                return String.valueOf(dVal);
            case BOOLEAN: return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue().trim();
                } catch (Exception ignored) {
                    return String.valueOf(cell.getNumericCellValue());
                }
            default: return "";
        }
    }

    private void exportTemplate() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("微信群发模板.xlsx"));
        chooser.setFileFilter(new FileNameExtensionFilter("Excel 2007 文件 (*.xlsx)", "xlsx"));
        if (chooser.showSaveDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    try (Workbook workbook = new XSSFWorkbook(); FileOutputStream fos = new FileOutputStream(file)) {
                        Sheet sheet = workbook.createSheet("微信群发");
                        Row row = sheet.createRow(0);
                        row.createCell(0).setCellValue("联系人/备注(精准名称)");
                        row.createCell(1).setCellValue("发送内容");

                        Row r1 = sheet.createRow(1);
                        r1.createCell(0).setCellValue("文件传输助手");
                        r1.createCell(1).setCellValue("您好，这是一条微信群发助手测试消息。");

                        Row r2 = sheet.createRow(2);
                        r2.createCell(0).setCellValue("张三");
                        r2.createCell(1).setCellValue("祝您生活愉快！");

                        workbook.write(fos);
                    }
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        UIUtils.info(getView(), "群发模板导出成功！");
                        log("群发模板导出成功至: " + file.getAbsolutePath());
                    } catch (Exception ex) {
                        UIUtils.error(getView(), "模板导出失败:\n" + ex.getMessage());
                        log("模板导出失败: " + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    private void startBulkSend() {
        final int rowCount = tableModel.getRowCount();
        if (rowCount == 0) {
            UIUtils.info(getView(), "请添加联系人后再启动群发！");
            return;
        }

        // Parse configurations
        final int delayWakeup;
        final int delaySearch;
        final int delayInterval;
        try {
            delayWakeup = Integer.parseInt(wakeupDelayField.getText().trim());
            delaySearch = Integer.parseInt(searchDelayField.getText().trim());
            delayInterval = Integer.parseInt(intervalField.getText().trim());
        } catch (Exception ex) {
            UIUtils.error(getView(), "请输入正确的延时或间隔毫秒数值！");
            return;
        }

        final String sendKey = (String) sendKeyCombo.getSelectedItem();
        final boolean confirmBeforeSend = confirmBeforeSendCb.isSelected();
        final boolean sendGlobalImgs = enableGlobalImgCb.isSelected();
        final java.util.List<String> globalImgsList = new java.util.ArrayList<>();
        for (int i = 0; i < globalImgListModel.getSize(); i++) {
            globalImgsList.add(globalImgListModel.getElementAt(i));
        }

        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        importBtn.setEnabled(false);
        clearBtn.setEnabled(false);
        addRowBtn.setEnabled(false);
        addImgRowBtn.setEnabled(false);
        addGlobalImgBtn.setEnabled(false);
        delGlobalImgBtn.setEnabled(false);
        clearGlobalImgBtn.setEnabled(false);
        enableGlobalImgCb.setEnabled(false);
        stopRequested = false;

        progressBar.setMaximum(rowCount);
        progressBar.setValue(0);

        sendThread = new Thread(() -> {
            try {
                Robot robot = new Robot();
                log("微信群发助手即将启动，请在 3 秒内将微信客户端置于屏幕中显示...");
                for (int i = 3; i > 0; i--) {
                    if (stopRequested) return;
                    log(i + "...");
                    Thread.sleep(1000);
                }

                log("开始自动群发流程...");
                int successCount = 0;

                for (int i = 0; i < rowCount; i++) {
                    if (stopRequested) {
                        log("发送流程已被用户手动终止。");
                        break;
                    }

                    String name = (String) tableModel.getValueAt(i, 1);
                    String msg = (String) tableModel.getValueAt(i, 2);
                    String status = (String) tableModel.getValueAt(i, 3);

                    if ("成功".equals(status)) {
                        progressBar.setValue(i + 1);
                        successCount++;
                        continue;
                    }

                    log("正在向 [" + name + "] 发送消息...");
                    updateRowStatus(i, "发送中", "");

                    try {
                        // 1. Press Escape twice to clear any left-over search popups, image views, or search-history windows
                        robot.keyPress(KeyEvent.VK_ESCAPE);
                        robot.keyRelease(KeyEvent.VK_ESCAPE);
                        robot.delay(100);
                        robot.keyPress(KeyEvent.VK_ESCAPE);
                        robot.keyRelease(KeyEvent.VK_ESCAPE);
                        robot.delay(200);

                        // 2. Wakeup WeChat (Ctrl + Alt + W)
                        simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_W);
                        robot.delay(delayWakeup);

                        // 3. Search contact (Ctrl + F)
                        simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_F);
                        robot.delay(200);

                        // Clear search field
                        simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_A);
                        robot.delay(100);
                        robot.keyPress(KeyEvent.VK_DELETE);
                        robot.keyRelease(KeyEvent.VK_DELETE);
                        robot.delay(150);

                        // Copy Name to clipboard & Paste
                        setClipboardText(name);
                        simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                        robot.delay(delaySearch);

                        // Press Enter to confirm chat window
                        robot.keyPress(KeyEvent.VK_ENTER);
                        robot.keyRelease(KeyEvent.VK_ENTER);
                        robot.delay(500);

                        // If confirmation mode is enabled
                        if (confirmBeforeSend) {
                            final int[] confirmResult = new int[]{-1};
                            final String finalMsg = msg;
                            final String finalName = name;
                            SwingUtilities.invokeAndWait(() -> {
                                JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(getView()), "群发确认 - 请核对微信聊天窗口", true);
                                dialog.setAlwaysOnTop(true);
                                dialog.setSize(400, 180);
                                dialog.setLocationRelativeTo(null);
                                dialog.setLayout(new BorderLayout(8, 8));
                                
                                JPanel msgPanel = new JPanel(new GridLayout(3, 1, 4, 4));
                                msgPanel.setBorder(new EmptyBorder(12, 16, 12, 16));
                                JLabel l1 = new JLabel("<html>当前微信窗口是否已打开目标联系人：<b><font color='red'>" + finalName + "</font></b>？</html>");
                                l1.setFont(new java.awt.Font(l1.getFont().getName(), java.awt.Font.BOLD, 14));
                                JLabel l2 = new JLabel("待发消息: " + (finalMsg.length() > 30 ? finalMsg.substring(0, 30) + "..." : finalMsg));
                                JLabel l3 = new JLabel("快捷键: Enter (确认发送) | Esc (跳过此人)");
                                l3.setForeground(java.awt.Color.GRAY);
                                msgPanel.add(l1);
                                msgPanel.add(l2);
                                msgPanel.add(l3);
                                dialog.add(msgPanel, BorderLayout.CENTER);
                                
                                JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 8));
                                JButton yesBtn = new JButton("确认发送 (Enter)");
                                JButton noBtn = new JButton("跳过此人 (Esc)");
                                btnPanel.add(yesBtn);
                                btnPanel.add(noBtn);
                                dialog.add(btnPanel, BorderLayout.SOUTH);
                                
                                yesBtn.addActionListener(ev -> {
                                    confirmResult[0] = JOptionPane.YES_OPTION;
                                    dialog.dispose();
                                });
                                noBtn.addActionListener(ev -> {
                                    confirmResult[0] = JOptionPane.NO_OPTION;
                                    dialog.dispose();
                                });
                                
                                dialog.getRootPane().setDefaultButton(yesBtn);
                                dialog.getRootPane().registerKeyboardAction(ev -> {
                                    confirmResult[0] = JOptionPane.NO_OPTION;
                                    dialog.dispose();
                                }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
                                
                                dialog.setVisible(true);
                            });
                            
                            if (confirmResult[0] != JOptionPane.YES_OPTION) {
                                updateRowStatus(i, "已跳过", "");
                                log("已跳过向 [" + name + "] 发送消息。");
                                // Clear search box or any active popup by pressing Esc twice
                                robot.keyPress(KeyEvent.VK_ESCAPE);
                                robot.keyRelease(KeyEvent.VK_ESCAPE);
                                robot.delay(100);
                                robot.keyPress(KeyEvent.VK_ESCAPE);
                                robot.keyRelease(KeyEvent.VK_ESCAPE);
                                continue;
                            }
                        }

                        if (msg != null && !msg.trim().isEmpty()) {
                            // Copy message to clipboard & Paste (Text or Image)
                            if (isImagePath(msg)) {
                                java.awt.Image img = javax.imageio.ImageIO.read(new File(msg.trim()));
                                if (img == null) {
                                    throw new Exception("无法解析图片文件，格式不支持或已损坏");
                                }
                                setClipboardImage(img);
                            } else {
                                setClipboardText(msg);
                            }
                            simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                            robot.delay(200);

                            // Press Send key
                            if ("Enter".equals(sendKey)) {
                                robot.keyPress(KeyEvent.VK_ENTER);
                                robot.keyRelease(KeyEvent.VK_ENTER);
                            } else {
                                simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER);
                            }
                            robot.delay(500);
                        }

                        // 3. Copy and Send global images if enabled
                        if (sendGlobalImgs && !globalImgsList.isEmpty()) {
                            for (String imgPath : globalImgsList) {
                                if (stopRequested) break;
                                java.awt.Image img = javax.imageio.ImageIO.read(new File(imgPath));
                                if (img != null) {
                                    setClipboardImage(img);
                                    simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                                    robot.delay(200);

                                    if ("Enter".equals(sendKey)) {
                                        robot.keyPress(KeyEvent.VK_ENTER);
                                        robot.keyRelease(KeyEvent.VK_ENTER);
                                    } else {
                                        simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER);
                                    }
                                    robot.delay(800); // delay between consecutive image sends
                                } else {
                                    log("【警告】无法读取全局图片: " + imgPath);
                                }
                            }
                        }

                        // Done
                        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                        updateRowStatus(i, "成功", timeStr);
                        log("发送成功: [" + name + "]");
                        successCount++;

                    } catch (Exception ex) {
                        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
                        updateRowStatus(i, "失败", timeStr);
                        log("发送失败: [" + name + "], 异常: " + ex.getMessage());
                        // Try to clean up WeChat state
                        robot.keyPress(KeyEvent.VK_ESCAPE);
                        robot.keyRelease(KeyEvent.VK_ESCAPE);
                        robot.delay(100);
                        robot.keyPress(KeyEvent.VK_ESCAPE);
                        robot.keyRelease(KeyEvent.VK_ESCAPE);
                    }

                    progressBar.setValue(i + 1);

                    // Wait interval
                    if (i < rowCount - 1 && !stopRequested) {
                        Thread.sleep(delayInterval);
                    }
                }

                log("群发任务结束，总计成功: " + successCount + "/" + rowCount);

            } catch (Exception ex) {
                log("线程意外中止: " + ex.getMessage());
            } finally {
                SwingUtilities.invokeLater(() -> {
                    startBtn.setEnabled(true);
                    stopBtn.setEnabled(false);
                    importBtn.setEnabled(true);
                    clearBtn.setEnabled(true);
                    addRowBtn.setEnabled(true);
                    addImgRowBtn.setEnabled(true);
                    addGlobalImgBtn.setEnabled(true);
                    delGlobalImgBtn.setEnabled(true);
                    clearGlobalImgBtn.setEnabled(true);
                    enableGlobalImgCb.setEnabled(true);
                });
            }
        });

        sendThread.start();
    }

    private void stopBulkSend() {
        stopRequested = true;
        if (sendThread != null) {
            sendThread.interrupt();
        }
        log("正在请求停止群发...");
    }

    private void simulateKeyCombo(Robot r, int... keys) {
        for (int k : keys) {
            r.keyPress(k);
            r.delay(30);
        }
        r.delay(30);
        for (int i = keys.length - 1; i >= 0; i--) {
            r.keyRelease(keys[i]);
            r.delay(30);
        }
    }

    private void setClipboardText(String text) throws Exception {
        StringSelection selection = new StringSelection(text);
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(selection, null);
    }

    private void setClipboardImage(Image img) {
        ImageTransferable transferable = new ImageTransferable(img);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
    }

    private boolean isImagePath(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.trim().toLowerCase();
        if (t.endsWith(".png") || t.endsWith(".jpg") || t.endsWith(".jpeg") || t.endsWith(".gif") || t.endsWith(".bmp")) {
            try {
                File file = new File(text.trim());
                return file.exists() && file.isFile();
            } catch (Exception ignored) {}
        }
        return false;
    }

    public static class ImageTransferable implements java.awt.datatransfer.Transferable {
        private final Image image;

        public ImageTransferable(Image image) {
            this.image = image;
        }

        @Override
        public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
            return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
            return java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) throws java.awt.datatransfer.UnsupportedFlavorException {
            if (java.awt.datatransfer.DataFlavor.imageFlavor.equals(flavor)) {
                return image;
            } else {
                throw new java.awt.datatransfer.UnsupportedFlavorException(flavor);
            }
        }
    }
}
