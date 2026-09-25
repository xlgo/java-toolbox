package com.aqishi.toolbox.feature.system.ui;

import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.system.domain.HostsFile;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;

/**
 * Hosts 域名本地环境切换管理工具
 */
public class HostsManagerPanel extends ToolPanel {

    private DefaultTableModel tableModel;
    private JTable hostsTable;
    private JTextField searchField;
    private JLabel statusLabel;
    private File hostsFile;
    /** 上次成功读取的文件；读取失败时为 null，此时禁止保存，以免用空表格覆盖系统 hosts。 */
    private HostsFile loadedDocument;
    private java.nio.charset.Charset loadedCharset = java.nio.charset.StandardCharsets.UTF_8;
    private javax.swing.table.TableRowSorter<DefaultTableModel> rowSorter;
    /** 隐藏列：规则在原文件中的行号；新加的行为 null。 */
    private static final int ID_COLUMN = 4;

    public HostsManagerPanel() {
        super(ToolCatalog.HOSTS_MANAGER);
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
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { filterTable(); }
        });
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

        String[] columnNames = {"启用", "IP 地址", I18n.get("tool.hosts.column.hosts"), "注释 / 说明", "id"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Boolean.class;
                return columnIndex == ID_COLUMN ? Integer.class : String.class;
            }
        };

        hostsTable = new JTable(tableModel);
        hostsTable.setRowHeight(26);
        hostsTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        hostsTable.getColumnModel().getColumn(0).setMaxWidth(60);
        hostsTable.removeColumn(hostsTable.getColumnModel().getColumn(ID_COLUMN));
        rowSorter = new javax.swing.table.TableRowSorter<>(tableModel);
        hostsTable.setRowSorter(rowSorter);

        JScrollPane scrollPane = new JScrollPane(hostsTable);

        JPanel tableBtnBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton addRowBtn = new JButton("添加规则");
        addRowBtn.addActionListener(e -> tableModel.addRow(new Object[]{true, "127.0.0.1", "dev.example.com", "本地开发", null}));

        JButton delRowBtn = new JButton("删除选中规则");
        delRowBtn.addActionListener(e -> {
            int row = hostsTable.getSelectedRow();
            if (row >= 0) tableModel.removeRow(hostsTable.convertRowIndexToModel(row));
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
        loadedDocument = null;
        tableModel.setRowCount(0);
        if (hostsFile == null || !hostsFile.exists()) {
            statusLabel.setText("未找到系统 hosts 文件");
            return;
        }
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(hostsFile.toPath());
            loadedCharset = detectCharset(raw);
            loadedDocument = HostsFile.parse(new String(raw, loadedCharset));
            for (HostsFile.Entry entry : loadedDocument.entries()) {
                tableModel.addRow(new Object[]{entry.enabled(), entry.ip(), entry.hostsText(), entry.comment(), entry.id()});
            }
            statusLabel.setText("成功载入 " + tableModel.getRowCount() + " 条解析规则");
        } catch (Exception e) {
            statusLabel.setText("读取失败: " + Errors.describeRoot(e));
        }
    }

    /**
     * hosts 文件没有编码声明：能按 UTF-8 严格解码就用 UTF-8，否则按系统原生编码（中文 Windows 上是 GBK）。
     * 保存时沿用读取时的编码，避免把原有注释写成乱码。
     */
    private static java.nio.charset.Charset detectCharset(byte[] raw) {
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw));
            return java.nio.charset.StandardCharsets.UTF_8;
        } catch (java.nio.charset.CharacterCodingException notUtf8) {
            String nativeName = System.getProperty("native.encoding");
            try {
                return nativeName == null ? java.nio.charset.Charset.defaultCharset()
                        : java.nio.charset.Charset.forName(nativeName);
            } catch (RuntimeException unsupported) {
                return java.nio.charset.Charset.defaultCharset();
            }
        }
    }

    private void saveHostsFile() {
        if (hostsFile == null) return;
        if (loadedDocument == null) {
            UIUtils.error(getView(), I18n.get("tool.hosts.notLoaded"));
            return;
        }
        // 正在编辑的单元格要先提交，否则最后一处修改会丢。
        if (hostsTable.isEditing() && hostsTable.getCellEditor() != null) {
            hostsTable.getCellEditor().stopCellEditing();
        }

        java.util.List<HostsFile.Entry> edited = new java.util.ArrayList<>();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            edited.add(HostsFile.entry(
                    (Integer) tableModel.getValueAt(i, ID_COLUMN),
                    Boolean.TRUE.equals(tableModel.getValueAt(i, 0)),
                    String.valueOf(tableModel.getValueAt(i, 1)),
                    String.valueOf(tableModel.getValueAt(i, 2)),
                    String.valueOf(tableModel.getValueAt(i, 3))));
        }
        String content = loadedDocument.render(edited);

        java.nio.file.Path target = hostsFile.toPath();
        java.nio.file.Path backup = target.resolveSibling(target.getFileName() + ".javatoolbox.bak");
        try {
            // 先备份再原地写：保留系统 hosts 文件本身的权限与属性；写入中途失败也能从备份恢复。
            java.nio.file.Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            java.nio.file.Files.write(target, content.getBytes(loadedCharset));
            loadedDocument = HostsFile.parse(content);
            loadHostsFile();
            statusLabel.setText(I18n.get("tool.hosts.saved.status", String.valueOf(backup.getFileName())));
            UIUtils.info(getView(), I18n.get("tool.hosts.saved", String.valueOf(backup)));
        } catch (Exception e) {
            statusLabel.setText("保存失败 (无写入权限)");
            UIUtils.copyToClipboard(content);
            UIUtils.warn(getView(), I18n.get("tool.hosts.writeFailed", Errors.describeRoot(e), hostsFile.getAbsolutePath()), "权限受限提示");
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
                    statusLabel.setText("刷新 DNS 失败");
                    UIUtils.error(getView(), "刷新 DNS 失败: " + Errors.describeRoot(ex));
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
        String query = searchField.getText().trim();
        rowSorter.setRowFilter(query.isEmpty() ? null
                : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(query), 1, 2, 3));
    }

    private void setAllRowsState(boolean enabled) {
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            tableModel.setValueAt(enabled, i, 0);
        }
    }

    private void addPresetBtn(JPanel panel, String label, String ip, String host) {
        JButton btn = new JButton(label);
        btn.addActionListener(e -> tableModel.addRow(new Object[]{true, ip, host, "预设解析", null}));
        panel.add(btn);
    }
}
