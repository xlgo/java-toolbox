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
    private JButton clearBtn;
    private JProgressBar progressBar;

    // Config inputs
    private JTextField wakeupDelayField;
    private JTextField searchDelayField;
    private JTextField intervalField;
    private JComboBox<String> sendKeyCombo;
    private JCheckBox confirmBeforeSendCb;

    private volatile boolean stopRequested = false;
    private Thread sendThread = null;

    public WeChatPanel() {
        super("dev", "wechat.sender",
                "微信", "群发", "WeChat", "批量", "发送", "模拟按键", "联系人");
    }

    @Override
    protected JComponent build() {
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

        root.add(settingsPanel, BorderLayout.NORTH);

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
        clearBtn = new JButton("清空表格");
        importBtn = new JButton("导入 Excel");
        exportTplBtn = new JButton("导出模板");
        tableActions.add(addRowBtn);
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
                "2. 发送前，请确保微信中已有这些联系人（支持完整昵称、备注或精准的微信号），名字不要有错别字。\n" +
                "3. 发送期间请不要移动鼠标或操作键盘，以免干扰模拟焦点导致消息错乱。\n" +
                "4. 微信发送按键请与微信电脑端设置一致（可在微信设置中查看是 Enter 还是 Ctrl+Enter）。\n" +
                "5. 建议将发送间隔设置为 2000 毫秒以上，避免发送过快导致微信接口限制。\n\n");

        setupListeners();

        return root;
    }

    private void setupListeners() {
        addRowBtn.addActionListener(e -> {
            int id = tableModel.getRowCount() + 1;
            tableModel.addRow(new Object[]{id, "输入联系人名称", "输入发送内容", "待发送", ""});
        });

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

        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);
        importBtn.setEnabled(false);
        clearBtn.setEnabled(false);
        addRowBtn.setEnabled(false);
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

                        // Copy message to clipboard & Paste
                        setClipboardText(msg);
                        simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_V);
                        robot.delay(200);

                        // Press Send key
                        if ("Enter".equals(sendKey)) {
                            robot.keyPress(KeyEvent.VK_ENTER);
                            robot.keyRelease(KeyEvent.VK_ENTER);
                        } else {
                            simulateKeyCombo(robot, KeyEvent.VK_CONTROL, KeyEvent.VK_ENTER);
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
}
