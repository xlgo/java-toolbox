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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

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
    private JButton wxcAutoCollectBtn;
    private boolean autoCollectRunning = false;
    private Thread autoCollectThread;
    private Process autoCollectProcess;
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

        wxcDbPathField = new JTextField(); // Keep initialized to avoid NPE
        
        JPanel importBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        wxcBrowseBtn = new JButton("导入外部通讯录文件 (Excel/CSV/TXT)...");
        wxcLoadBtn = new JButton("从剪贴板/文本批量导入...");
        wxcAutoCollectBtn = new JButton("模拟人工点击获取...");
        importBtnRow.add(wxcBrowseBtn);
        importBtnRow.add(wxcLoadBtn);
        importBtnRow.add(wxcAutoCollectBtn);

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 4; gbc.weightx = 1.0;
        topPanel.add(importBtnRow, gbc);

        // Usage Tip Label
        JLabel tipLabel = new JLabel("<html><b>使用说明：</b>此处用于直接导入外部通讯录列表。您可以使用任何工具导出通讯录为 Excel、CSV 或 TXT 文件后在此导入，或者直接在弹窗中从剪贴板复制粘贴通讯录文本列表。支持字段自适应匹配（如昵称、微信号、备注、性别）。</html>");
        tipLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        gbc.gridy = 1;
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
        wxcTableModel = new DefaultTableModel(new Object[]{"选择", "昵称", "微信号", "备注名", "标签", "UserName(隐藏)"}, 0) {
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
        wxcTable.getColumnModel().getColumn(4).setPreferredWidth(100);
        wxcTable.getColumnModel().getColumn(5).setMinWidth(0);
        wxcTable.getColumnModel().getColumn(5).setMaxWidth(0);
        wxcTable.getColumnModel().getColumn(5).setPreferredWidth(0);

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
            chooser.setFileFilter(new FileNameExtensionFilter("通讯录文件 (*.xlsx; *.xls; *.csv; *.txt)", "xlsx", "xls", "csv", "txt"));
            if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                wxcProgressBar.setValue(0);
                wxcProgressBar.setString("正在解析通讯录文件...");
                wxcProgressBar.setIndeterminate(true);
                wxcProgressBar.setVisible(true);

                new SwingWorker<java.util.List<WeChatContactReader.ContactInfo>, Void>() {
                    @Override
                    protected java.util.List<WeChatContactReader.ContactInfo> doInBackground() throws Exception {
                        String name = file.getName().toLowerCase();
                        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                            return WeChatContactReader.readContactsFromExcel(file);
                        } else {
                            return WeChatContactReader.readContactsFromTextFile(file);
                        }
                    }

                    @Override
                    protected void done() {
                        wxcProgressBar.setVisible(false);
                        wxcProgressBar.setIndeterminate(false);
                        try {
                            java.util.List<WeChatContactReader.ContactInfo> loaded = get();
                            allContacts.addAll(loaded);
                            refreshContactTable();
                            UIUtils.info(getView(), "导入成功！共加载了 " + loaded.size() + " 个联系人。");
                        } catch (Exception ex) {
                            UIUtils.error(getView(), "解析通讯录文件失败:\n" + ex.getMessage());
                        }
                    }
                }.execute();
            }
        });

        wxcLoadBtn.addActionListener(e -> showClipboardImportDialog());
        wxcAutoCollectBtn.addActionListener(e -> {
            if (autoCollectRunning) {
                stopAutoCollect();
            } else {
                startAutoCollect();
            }
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
                    String username = (String) wxcTableModel.getValueAt(i, 5);

                    String displayName = remark;
                    if (displayName == null || displayName.trim().isEmpty()) {
                        displayName = nickname;
                    }
                    if (displayName == null || displayName.trim().isEmpty()) {
                        displayName = alias;
                    }
                    if (displayName == null || displayName.trim().isEmpty()) {
                        displayName = username;
                    }

                    String targetName = displayName;
                    if (alias != null && !alias.trim().isEmpty() && !alias.trim().startsWith("wxid_") && !alias.trim().equals(displayName)) {
                        targetName = displayName + " (" + alias.trim() + ")";
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
                        String username = (String) wxcTableModel.getValueAt(i, 5);
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
                    String username = (String) wxcTableModel.getValueAt(i, 5);
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

                String username = (String) entry.getValue(5);
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

    private void refreshContactTable() {
        wxcTableModel.setRowCount(0);
        for (WeChatContactReader.ContactInfo c : allContacts) {
            wxcTableModel.addRow(new Object[]{
                    c.selected,
                    c.nickname != null ? c.nickname : "",
                    c.alias != null ? c.alias : "",
                    c.remark != null ? c.remark : "",
                    c.tag != null ? c.tag : "",
                    c.username
            });
        }
        filterWxcTable();
    }

    private void showClipboardImportDialog() {
        Window ancestor = SwingUtilities.getWindowAncestor(this.getView());
        JDialog dialog = new JDialog(ancestor instanceof Frame ? (Frame) ancestor : null, "从剪贴板/文本批量导入", true);
        dialog.setSize(550, 420);
        dialog.setLocationRelativeTo(ancestor);
        dialog.setLayout(new BorderLayout(8, 8));

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        top.add(new JLabel("请将包含联系人信息的文本粘贴在下方输入框中："), BorderLayout.NORTH);
        JLabel formatTip = new JLabel("<html>格式支持：<b>昵称 [制表符/逗号/空格] 微信号 [备注] [性别]</b>。每行对应一个联系人。</html>");
        formatTip.setForeground(UIManager.getColor("Label.disabledForeground"));
        top.add(formatTip, BorderLayout.SOUTH);
        dialog.add(top, BorderLayout.NORTH);

        JTextArea textArea = new JTextArea();
        textArea.setFont(UIUtils.monoFont());
        dialog.add(new JScrollPane(textArea), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton okBtn = new JButton("确定导入");
        JButton cancelBtn = new JButton("取消");
        bottom.add(okBtn);
        bottom.add(cancelBtn);
        dialog.add(bottom, BorderLayout.SOUTH);

        cancelBtn.addActionListener(e -> dialog.dispose());
        okBtn.addActionListener(e -> {
            String text = textArea.getText();
            if (text == null || text.trim().isEmpty()) {
                UIUtils.error(dialog, "粘贴的内容不能为空！");
                return;
            }
            try {
                java.util.List<WeChatContactReader.ContactInfo> parsed = WeChatContactReader.parseContactsFromText(text);
                if (parsed.isEmpty()) {
                    UIUtils.error(dialog, "未解析到有效的联系人信息！");
                    return;
                }
                allContacts.addAll(parsed);
                refreshContactTable();
                dialog.dispose();
                UIUtils.info(getView(), "导入成功！共导入了 " + parsed.size() + " 个联系人。");
            } catch (Exception ex) {
                UIUtils.error(dialog, "解析失败: " + ex.getMessage());
            }
        });

        dialog.setVisible(true);
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

                        // Extract WeChat ID from "DisplayName (Alias)" format if present
                        String searchKey = name;
                        if (name != null && name.contains(" (") && name.endsWith(")")) {
                            int startIndex = name.lastIndexOf(" (");
                            String possibleAlias = name.substring(startIndex + 2, name.length() - 1).trim();
                            if (!possibleAlias.isEmpty()) {
                                searchKey = possibleAlias;
                            }
                        }

                        // Copy Name to clipboard & Paste
                        setClipboardText(searchKey);
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

    private void startAutoCollect() {
        Window ancestor = SwingUtilities.getWindowAncestor(this.getView());
        JDialog dialog = new JDialog(ancestor instanceof Frame ? (Frame) ancestor : null, "自动获取微信通讯录参数配置", true);
        dialog.setSize(450, 360);
        dialog.setLocationRelativeTo(ancestor);
        dialog.setLayout(new GridBagLayout());
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 12, 6, 12);
        
        // Instructions
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        JLabel tipLabel = new JLabel("<html><b>获取前准备步骤：</b><br>" +
                "1. 在电脑上打开微信客户端，点击左下角的 <b>「通讯录」</b> 图标。<br>" +
                "2. 手动在联系人列表中<b>点击选中第一个好友</b>。<br>" +
                "3. 保持微信主界面可见。<br>" +
                "4. 点击下方的「开始获取」后，<b>请勿操作鼠标和键盘</b>。</html>");
        dialog.add(tipLabel, gbc);
        
        // Limit field
        gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
        dialog.add(new JLabel("获取上限数量:"), gbc);
        JTextField limitField = new JTextField("1000");
        gbc.gridx = 1; gbc.weightx = 1.0;
        dialog.add(limitField, gbc);
        
        // Delay field
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        dialog.add(new JLabel("按键/点击间隔时间(毫秒):"), gbc);
        JTextField delayField = new JTextField("300");
        gbc.gridx = 1; gbc.weightx = 1.0;
        dialog.add(delayField, gbc);

        // Mode combo
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
        dialog.add(new JLabel("操作模式:"), gbc);
        JComboBox<String> modeCombo = new JComboBox<>(new String[]{
                "键盘模式 (使用 Down 键，在键盘可用时极快)", 
                "鼠标模式 (模拟鼠标点击与滚轮，微信4.x必选)"
        });
        modeCombo.setSelectedIndex(0); // Default to Mouse mode
        gbc.gridx = 1; gbc.weightx = 1.0;
        dialog.add(modeCombo, gbc);
        
        // Action buttons
        gbc.gridx = 0; gbc.gridy = 4; gbc.gridwidth = 2; gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
        JButton runBtn = new JButton("开始获取");
        JButton cancelBtn = new JButton("取消");
        btnRow.add(runBtn);
        btnRow.add(cancelBtn);
        dialog.add(btnRow, gbc);
        
        cancelBtn.addActionListener(ev -> dialog.dispose());
        
        runBtn.addActionListener(ev -> {
            int limit = 1000;
            int delay = 300;
            try {
                limit = Integer.parseInt(limitField.getText().trim());
                delay = Integer.parseInt(delayField.getText().trim());
            } catch (Exception ex) {
                UIUtils.error(dialog, "参数格式错误，请输入正整数！");
                return;
            }
            String mode = modeCombo.getSelectedIndex() == 0 ? "keyboard" : "mouse";
            dialog.dispose();
            executeAutoCollect(limit, delay, mode);
        });
        
        dialog.setVisible(true);
    }
 
    private void executeAutoCollect(int limit, int delay, String mode) {
        autoCollectRunning = true;
        wxcAutoCollectBtn.setText("停止获取");
        wxcBrowseBtn.setEnabled(false);
        wxcLoadBtn.setEnabled(false);
        wxcExportBtn.setEnabled(false);
        wxcDownloadAvatarBtn.setEnabled(false);
        wxcAddToSendBtn.setEnabled(false);
        
        wxcProgressBar.setValue(0);
        wxcProgressBar.setString("正在启动自动获取程序...");
        wxcProgressBar.setIndeterminate(true);
        wxcProgressBar.setVisible(true);
        
        autoCollectThread = new Thread(() -> {
            File tempScriptFile = null;
            try {
                // Extract python script from JAR resource to a temp file
                try (java.io.InputStream is = WeChatPanel.class.getResourceAsStream("/tools/wechat_export.py")) {
                    if (is == null) {
                        throw new java.io.FileNotFoundException("未在 JAR 资源中找到 /tools/wechat_export.py！");
                    }
                    tempScriptFile = File.createTempFile("wechat_export_", ".py");
                    tempScriptFile.deleteOnExit();
                    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempScriptFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            fos.write(buffer, 0, bytesRead);
                        }
                    }
                }

                double delaySec = delay / 1000.0;
                
                ProcessBuilder pb = new ProcessBuilder(
                        "python",
                        tempScriptFile.getAbsolutePath(),
                        "--limit", String.valueOf(limit),
                        "--delay", String.valueOf(delaySec),
                        "--mode", mode
                );
                pb.redirectErrorStream(true);
                pb.directory(new File("."));
                
                autoCollectProcess = pb.start();
                
                ObjectMapper mapper = new ObjectMapper();
                
                StringBuilder outputLog = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(autoCollectProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int collected = 0;
                    while ((line = reader.readLine()) != null) {
                        if (!autoCollectRunning) {
                            break;
                        }
                        
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        
                        outputLog.append(trimmed).append("\n");
                        
                        try {
                            Map<String, Object> data = mapper.readValue(trimmed, Map.class);
                            if (data.containsKey("error")) {
                                String errorMsg = (String) data.get("error");
                                SwingUtilities.invokeLater(() -> {
                                    UIUtils.error(getView(), errorMsg);
                                    stopAutoCollect();
                                });
                                return;
                            } else if (data.containsKey("status")) {
                                String statusMsg = (String) data.get("status");
                                SwingUtilities.invokeLater(() -> {
                                    wxcProgressBar.setString(statusMsg);
                                });
                            } else {
                                String nickname = (String) data.get("nickname");
                                String wechatId = (String) data.get("wechat_id");
                                String remark = (String) data.get("remark");
                                String region = (String) data.get("region");
                                String tag = (String) data.get("tag");
                                
                                Integer genderVal = (Integer) data.get("gender");
                                WeChatContactReader.ContactInfo info = new WeChatContactReader.ContactInfo();
                                info.nickname = nickname;
                                info.alias = wechatId;
                                info.remark = remark;
                                info.tag = tag != null ? tag : "";
                                info.username = wechatId != null && !wechatId.isEmpty() ? wechatId : nickname;
                                info.gender = genderVal != null ? genderVal : 0;
                                info.avatarUrl = "";
                                
                                collected++;
                                final int finalCollected = collected;
                                SwingUtilities.invokeLater(() -> {
                                    if (findContactByUsername(info.username) == null) {
                                        allContacts.add(info);
                                        refreshContactTable();
                                    }
                                    wxcProgressBar.setIndeterminate(false);
                                    wxcProgressBar.setValue(Math.min(100, (finalCollected * 100) / limit));
                                    wxcProgressBar.setString(String.format("已获取 %d 个联系人: %s", finalCollected, info.getDisplayName()));
                                });
                            }
                        } catch (Exception ex) {
                            System.out.println("[WeChat AutoCollect Output] " + trimmed);
                        }
                    }
                }
                
                int exitVal = autoCollectProcess.waitFor();
                if (exitVal != 0 && autoCollectRunning) {
                    final String fullLog = outputLog.toString();
                    SwingUtilities.invokeLater(() -> {
                        UIUtils.error(getView(), "自动获取失败！错误日志：\n" + 
                                (fullLog.length() > 600 ? fullLog.substring(fullLog.length() - 600) : fullLog));
                    });
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    String msg = ex.getMessage();
                    if (msg != null && (msg.contains("Cannot run program") || msg.contains("系统找不到指定的文件") || msg.contains("not found"))) {
                        UIUtils.error(getView(), "执行自动获取异常:\n系统未检测到有效的 Python 环境！\n\n请确认：\n1. 电脑已安装 Python\n2. 安装时已勾选 “Add Python to PATH” 选项。\n安装并配置完成后，请重启本软件重试。");
                    } else {
                        UIUtils.error(getView(), "执行自动获取异常:\n" + msg);
                    }
                });
            } finally {
                if (tempScriptFile != null && tempScriptFile.exists()) {
                    try {
                        tempScriptFile.delete();
                    } catch (Exception ignored) {}
                }
                SwingUtilities.invokeLater(() -> {
                    stopAutoCollect();
                });
            }
        });
        
        autoCollectThread.start();
    }

    private void stopAutoCollect() {
        autoCollectRunning = false;
        if (autoCollectProcess != null && autoCollectProcess.isAlive()) {
            autoCollectProcess.destroy();
        }
        if (autoCollectThread != null && autoCollectThread.isAlive()) {
            autoCollectThread.interrupt();
        }
        
        wxcAutoCollectBtn.setText("模拟人工点击获取...");
        wxcBrowseBtn.setEnabled(true);
        wxcLoadBtn.setEnabled(true);
        wxcExportBtn.setEnabled(true);
        wxcDownloadAvatarBtn.setEnabled(true);
        wxcAddToSendBtn.setEnabled(true);
        wxcProgressBar.setVisible(false);
    }
}
