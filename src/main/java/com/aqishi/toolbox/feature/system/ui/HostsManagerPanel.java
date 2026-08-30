package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;

/**
 * Hosts 域名本地环境切换管理工具
 */
public class HostsManagerPanel extends ToolPanel {

    private DefaultTableModel tableModel;
    private JTable hostsTable;
    private JTextField searchField;
    private JLabel statusLabel;
    private File hostsFile;

    public HostsManagerPanel() {
        super("misc", "hosts.manager", "hosts", "domain", "dns", "ip", "environment", "环境", "域名", "解析");
    }

    @Override
    protected JComponent build() {
        detectSystemHostsFile();

        JPanel mainPanel = new JPanel(new BorderLayout(0, 12));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        // --- 顶栏：系统文件路径、搜索与操作 ---
        Card topCard = Card.plain();
        topCard.setLayout(new BorderLayout(12, 12));
        topCard.setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel pathInfoPanel = new JPanel(new BorderLayout(8, 0));
        pathInfoPanel.add(new JLabel("系统 Hosts 文件路径: "), BorderLayout.WEST);

        JTextField pathDisplay = new JTextField(hostsFile != null ? hostsFile.getAbsolutePath() : "未找到系统 hosts");
        pathDisplay.setEditable(false);
        pathDisplay.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        pathInfoPanel.add(pathDisplay, BorderLayout.CENTER);

        JPanel searchBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        searchBar.add(new JLabel("检索过滤:"));
        searchField = new JTextField(15);
        searchField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        searchField.addActionListener(e -> filterTable());
        searchBar.add(searchField);

        JButton reloadBtn = new JButton("重新读取系统 Hosts");
        reloadBtn.addActionListener(e -> loadHostsFile());

        JButton flushDnsBtn = new JButton("刷新 DNS 缓存 (ipconfig /flushdns)");
        flushDnsBtn.addActionListener(e -> flushDnsCache(flushDnsBtn));

        searchBar.add(reloadBtn);
        searchBar.add(flushDnsBtn);

        topCard.add(pathInfoPanel, BorderLayout.CENTER);
        topCard.add(searchBar, BorderLayout.SOUTH);

        // --- 中央：Hosts 规则表格 ---
        Card tableCard = Card.plain();
        tableCard.setLayout(new BorderLayout(0, 8));
        tableCard.setBorder(new EmptyBorder(12, 12, 12, 12));

        String[] columnNames = {"启用", "IP 地址", "域名 (Host)", "注释 / 说明"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Boolean.class : String.class;
            }
        };

        hostsTable = new JTable(tableModel);
        hostsTable.setRowHeight(26);
        hostsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        hostsTable.getColumnModel().getColumn(0).setMaxWidth(60);

        JScrollPane scrollPane = new JScrollPane(hostsTable);

        JPanel tableBtnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton addRowBtn = new JButton("添加规则");
        addRowBtn.addActionListener(e -> tableModel.addRow(new Object[]{true, "127.0.0.1", "dev.example.com", "本地开发"}));

        JButton delRowBtn = new JButton("删除选中规则");
        delRowBtn.addActionListener(e -> {
            int row = hostsTable.getSelectedRow();
            if (row >= 0) tableModel.removeRow(row);
        });

        JButton enableAllBtn = new JButton("全部启用");
        enableAllBtn.addActionListener(e -> setAllRowsState(true));

        JButton disableAllBtn = new JButton("全部禁用");
        disableAllBtn.addActionListener(e -> setAllRowsState(false));

        tableBtnBar.add(addRowBtn);
        tableBtnBar.add(delRowBtn);
        tableBtnBar.add(enableAllBtn);
        tableBtnBar.add(disableAllBtn);

        tableCard.add(scrollPane, BorderLayout.CENTER);
        tableCard.add(tableBtnBar, BorderLayout.SOUTH);

        // --- 底栏：环境快捷 Preset 与 保存 ---
        Card bottomCard = Card.plain();
        bottomCard.setLayout(new BorderLayout(12, 8));
        bottomCard.setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel presetBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        presetBar.add(new JLabel("常用场景模板: "));

        addPresetBtn(presetBar, "添加本地 Loopback (127.0.0.1)", "127.0.0.1", "localhost");
        addPresetBtn(presetBar, "屏蔽广告/垃圾站点 (0.0.0.0)", "0.0.0.0", "ad.example.com");

        JPanel saveBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        statusLabel = new JLabel("就绪");
        statusLabel.setForeground(Color.GRAY);

        JButton saveBtn = new JButton("保存修改至系统 Hosts");
        saveBtn.setFont(saveBtn.getFont().deriveFont(Font.BOLD));
        saveBtn.addActionListener(e -> saveHostsFile());

        saveBar.add(statusLabel);
        saveBar.add(saveBtn);

        bottomCard.add(presetBar, BorderLayout.WEST);
        bottomCard.add(saveBar, BorderLayout.EAST);

        mainPanel.add(topCard, BorderLayout.NORTH);
        mainPanel.add(tableCard, BorderLayout.CENTER);
        mainPanel.add(bottomCard, BorderLayout.SOUTH);

        // 初始读取 Hosts 文件
        loadHostsFile();

        return mainPanel;
    }

    private void detectSystemHostsFile() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            hostsFile = new File("C:\\Windows\\System32\\drivers\\etc\\hosts");
        } else {
            hostsFile = new File("/etc/hosts");
        }
    }

    private void loadHostsFile() {
        if (hostsFile == null || !hostsFile.exists()) {
            statusLabel.setText("未找到系统 hosts 文件");
            return;
        }

        tableModel.setRowCount(0);
        try (BufferedReader reader = new BufferedReader(new FileReader(hostsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                boolean enabled = !trimmed.startsWith("#");
                String cleanLine = enabled ? trimmed : trimmed.substring(1).trim();

                // 拆分 IP 和 域名
                String[] parts = cleanLine.split("\\s+");
                if (parts.length >= 2 && isIpAddress(parts[0])) {
                    String ip = parts[0];
                    String host = parts[1];
                    StringBuilder comment = new StringBuilder();
                    for (int i = 2; i < parts.length; i++) {
                        comment.append(parts[i]).append(" ");
                    }
                    tableModel.addRow(new Object[]{enabled, ip, host, comment.toString().trim()});
                } else if (!enabled) {
                    // 纯注释行跳过或加入说明
                }
            }
            statusLabel.setText("成功载入 " + tableModel.getRowCount() + " 条解析规则");
        } catch (Exception e) {
            statusLabel.setText("读取失败: " + e.getMessage());
        }
    }

    private boolean isIpAddress(String str) {
        if (str == null) return false;
        return str.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || str.equals("::1") || str.startsWith("0.0.0.0");
    }

    private void saveHostsFile() {
        if (hostsFile == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("# Generated by Java Toolbox - Hosts Manager\n");
        sb.append("# Timestamp: ").append(new java.util.Date()).append("\n\n");

        for (int i = 0; i < tableModel.getRowCount(); i++) {
            boolean enabled = (Boolean) tableModel.getValueAt(i, 0);
            String ip = String.valueOf(tableModel.getValueAt(i, 1)).trim();
            String host = String.valueOf(tableModel.getValueAt(i, 2)).trim();
            String comment = String.valueOf(tableModel.getValueAt(i, 3)).trim();

            if (ip.isEmpty() || host.isEmpty()) continue;

            if (!enabled) sb.append("# ");
            sb.append(ip).append("\t").append(host);
            if (!comment.isEmpty()) sb.append("\t# ").append(comment);
            sb.append("\n");
        }

        try (FileWriter writer = new FileWriter(hostsFile)) {
            writer.write(sb.toString());
            statusLabel.setText("保存成功！已更新系统 Hosts");
            UIUtils.info(getView(), "系统 Hosts 文件已成功更新！");
        } catch (Exception e) {
            statusLabel.setText("保存失败 (无写入权限)");
            UIUtils.copyToClipboard(sb.toString());
            UIUtils.warn(getView(), "写入系统 Hosts 失败（可能是由于没有管理员权限）。\n最新 Hosts 内容已自动复制到剪贴板，您可以手动保存至:\n" + hostsFile.getAbsolutePath(), "权限受限提示");
        }
    }

    /**
     * Refreshes the OS DNS cache off the event thread.
     *
     * <p>{@code waitFor()} blocks until the child process exits, and the macOS
     * command runs under {@code sudo}: when it prompts for a password the
     * process never exits, so calling it on the EDT would freeze the whole
     * window with no way back. Running in a worker also lets it time out, and
     * the command is passed as an argument array so the shell cannot
     * reinterpret it.</p>
     */
    private void flushDnsCache(JButton flushDnsBtn) {
        String[] command = flushDnsCommand();
        if (command == null) {
            UIUtils.error(getView(), "当前操作系统暂不支持自动刷新 DNS 缓存。");
            return;
        }

        flushDnsBtn.setEnabled(false);
        statusLabel.setText("正在刷新 DNS 缓存...");

        new SwingWorker<Integer, Void>() {
            @Override
            protected Integer doInBackground() throws Exception {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true);
                Process process = builder.start();
                // Drain the stream: a chatty command would otherwise fill the
                // pipe buffer and stall before it can exit.
                try (java.io.InputStream output = process.getInputStream()) {
                    byte[] buffer = new byte[4096];
                    while (output.read(buffer) != -1) {
                        // Discarded; the exit code is the only signal shown.
                    }
                }
                if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return null;
                }
                return process.exitValue();
            }

            @Override
            protected void done() {
                flushDnsBtn.setEnabled(true);
                try {
                    Integer exit = get();
                    if (exit == null) {
                        statusLabel.setText("刷新 DNS 超时");
                        UIUtils.error(getView(), "刷新 DNS 超时（15 秒）。\n"
                                + "若该命令需要管理员密码，请在终端中手动执行。");
                    } else if (exit == 0) {
                        statusLabel.setText("DNS 缓存已刷新");
                        UIUtils.info(getView(), "DNS 缓存已成功刷新！");
                    } else {
                        statusLabel.setText("DNS 刷新命令已执行");
                        UIUtils.info(getView(), "DNS 刷新命令已执行 (退出代码: " + exit + ")");
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    statusLabel.setText("刷新 DNS 失败");
                    UIUtils.error(getView(), "刷新 DNS 失败: " + cause.getMessage());
                }
            }
        }.execute();
    }

    /** Returns the platform flush command as discrete arguments, or null if unknown. */
    private static String[] flushDnsCommand() {
        String os = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) {
            return new String[]{"ipconfig", "/flushdns"};
        }
        if (os.contains("mac")) {
            return new String[]{"sudo", "killall", "-HUP", "mDNSResponder"};
        }
        if (os.contains("nix") || os.contains("nux")) {
            return new String[]{"systemd-resolve", "--flush-caches"};
        }
        return null;
    }

    private void filterTable() {
        String query = searchField.getText().trim().toLowerCase();
        if (query.isEmpty()) return;

        for (int i = tableModel.getRowCount() - 1; i >= 0; i--) {
            String host = String.valueOf(tableModel.getValueAt(i, 2)).toLowerCase();
            String ip = String.valueOf(tableModel.getValueAt(i, 1)).toLowerCase();
            if (!host.contains(query) && !ip.contains(query)) {
                // 可隐藏或排序
            }
        }
    }

    private void setAllRowsState(boolean enabled) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            tableModel.setValueAt(enabled, i, 0);
        }
    }

    private void addPresetBtn(JPanel panel, String label, String ip, String host) {
        JButton btn = new JButton(label);
        btn.addActionListener(e -> tableModel.addRow(new Object[]{true, ip, host, "预设解析"}));
        panel.add(btn);
    }
}
