package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 端口扫描与网络连通性诊断工具
 */
public class PortScannerPanel extends ToolPanel {

    private JTextField hostField;
    private JRadioButton presetRadio;
    private JRadioButton rangeRadio;
    private JCheckBox webPortsCheck;
    private JCheckBox dbPortsCheck;
    private JCheckBox opsPortsCheck;
    private JTextField customRangeField;
    private JSpinner timeoutSpinner;
    private JSpinner threadSpinner;

    private JButton startBtn;
    private JButton stopBtn;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JCheckBox onlyOpenCheckBox;

    private DefaultTableModel resultTableModel;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private JTable resultTable;

    private final AtomicBoolean isScanning = new AtomicBoolean(false);
    private ExecutorService scanExecutor;

    private static final Map<Integer, String> KNOWN_SERVICES = new HashMap<>();

    static {
        KNOWN_SERVICES.put(21, "FTP");
        KNOWN_SERVICES.put(22, "SSH");
        KNOWN_SERVICES.put(23, "Telnet");
        KNOWN_SERVICES.put(25, "SMTP");
        KNOWN_SERVICES.put(53, "DNS");
        KNOWN_SERVICES.put(80, "HTTP");
        KNOWN_SERVICES.put(110, "POP3");
        KNOWN_SERVICES.put(143, "IMAP");
        KNOWN_SERVICES.put(443, "HTTPS");
        KNOWN_SERVICES.put(1433, "MSSQL");
        KNOWN_SERVICES.put(1521, "Oracle");
        KNOWN_SERVICES.put(2181, "ZooKeeper");
        KNOWN_SERVICES.put(3306, "MySQL");
        KNOWN_SERVICES.put(3389, "RDP (远程桌面)");
        KNOWN_SERVICES.put(5432, "PostgreSQL");
        KNOWN_SERVICES.put(5900, "VNC");
        KNOWN_SERVICES.put(6379, "Redis");
        KNOWN_SERVICES.put(8080, "HTTP Alt / Tomcat");
        KNOWN_SERVICES.put(8443, "HTTPS Alt");
        KNOWN_SERVICES.put(8888, "Jupyter / WebAlt");
        KNOWN_SERVICES.put(9092, "Kafka");
        KNOWN_SERVICES.put(9200, "Elasticsearch");
        KNOWN_SERVICES.put(11211, "Memcached");
        KNOWN_SERVICES.put(27017, "MongoDB");
    }

    public PortScannerPanel() {
        super("dev", "port.scanner", "port", "scanner", "network", "ping", "nmap", "tcp", "端口扫描", "网络诊断");
    }

    @Override
    protected JComponent build() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 10));
        mainPanel.setBorder(new EmptyBorder(12, 12, 12, 12));

        mainPanel.add(buildConfigCard(), BorderLayout.NORTH);
        mainPanel.add(buildResultCard(), BorderLayout.CENTER);

        return mainPanel;
    }

    private Card buildConfigCard() {
        Card card = Card.plain();
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        // Line 1: Target Host & Controls
        JPanel row1 = new JPanel(new BorderLayout(8, 0));
        row1.add(new JLabel("目标主机/IP:"), BorderLayout.WEST);

        hostField = new JTextField("127.0.0.1");
        hostField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        row1.add(hostField, BorderLayout.CENTER);

        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        startBtn = new JButton("开始扫描");
        startBtn.setFont(startBtn.getFont().deriveFont(Font.BOLD));
        startBtn.addActionListener(e -> startScan());

        stopBtn = new JButton("停止");
        stopBtn.setEnabled(false);
        stopBtn.addActionListener(e -> stopScan());

        btnBar.add(startBtn);
        btnBar.add(stopBtn);
        row1.add(btnBar, BorderLayout.EAST);

        card.add(row1, BorderLayout.NORTH);

        // Line 2: Port Selection Modes
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        ButtonGroup group = new ButtonGroup();
        presetRadio = new JRadioButton("预设端口组", true);
        rangeRadio = new JRadioButton("自定义端口/范围", false);
        group.add(presetRadio);
        group.add(rangeRadio);

        row2.add(presetRadio);
        webPortsCheck = new JCheckBox("Web服务 (80,443,8080,8443)", true);
        dbPortsCheck = new JCheckBox("数据库 (3306,5432,1521,6379,27017)", true);
        opsPortsCheck = new JCheckBox("运维中间件 (22,3389,2181,9092,9200)", true);
        row2.add(webPortsCheck);
        row2.add(dbPortsCheck);
        row2.add(opsPortsCheck);

        card.add(row2, BorderLayout.CENTER);

        // Line 3: Custom range & parameters
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row3.add(rangeRadio);
        customRangeField = new JTextField("1-1024", 16);
        customRangeField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        row3.add(customRangeField);

        row3.add(new JLabel("  超时(ms):"));
        timeoutSpinner = new JSpinner(new SpinnerNumberModel(500, 50, 5000, 100));
        row3.add(timeoutSpinner);

        row3.add(new JLabel("并发线程数:"));
        threadSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 200, 5));
        row3.add(threadSpinner);

        card.add(row3, BorderLayout.SOUTH);

        presetRadio.addActionListener(e -> togglePortSelectionUI());
        rangeRadio.addActionListener(e -> togglePortSelectionUI());
        togglePortSelectionUI();

        return card;
    }

    private void togglePortSelectionUI() {
        boolean isPreset = presetRadio.isSelected();
        webPortsCheck.setEnabled(isPreset);
        dbPortsCheck.setEnabled(isPreset);
        opsPortsCheck.setEnabled(isPreset);

        customRangeField.setEnabled(!isPreset);
    }

    private Card buildResultCard() {
        Card card = Card.plain();
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(new EmptyBorder(10, 12, 10, 12));

        // Top Status & Filter Bar
        JPanel topBar = new JPanel(new BorderLayout());
        statusLabel = new JLabel("就绪 - 点击「开始扫描」发动连通性探测");
        topBar.add(statusLabel, BorderLayout.WEST);

        JPanel rightFilter = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        onlyOpenCheckBox = new JCheckBox("仅显示开放端口 (Open)", true);
        onlyOpenCheckBox.addActionListener(e -> filterResults());
        rightFilter.add(onlyOpenCheckBox);

        JButton copyOpenBtn = new JButton("复制所有开放端口");
        copyOpenBtn.addActionListener(e -> copyOpenPorts());
        rightFilter.add(copyOpenBtn);

        JButton clearBtn = new JButton("清空表格");
        clearBtn.addActionListener(e -> {
            resultTableModel.setRowCount(0);
            progressBar.setValue(0);
            statusLabel.setText("已清空结果");
        });
        rightFilter.add(clearBtn);

        topBar.add(rightFilter, BorderLayout.EAST);
        card.add(topBar, BorderLayout.NORTH);

        // Result Table
        resultTableModel = new DefaultTableModel(new Object[]{"端口", "开放状态", "常见服务猜想", "响应延时 (RTT)"}, 0) {
            @Override
            public Class<?> getColumnClass(int columnIndex) {
                if (columnIndex == 0) return Integer.class;
                return String.class;
            }

            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultTable = new JTable(resultTableModel);
        rowSorter = new TableRowSorter<>(resultTableModel);
        resultTable.setRowSorter(rowSorter);
        resultTable.setRowHeight(24);

        // State column renderer
        resultTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                String valStr = String.valueOf(value);
                if ("开放 (Open)".equals(valStr)) {
                    c.setForeground(new Color(46, 125, 50));
                    c.setFont(c.getFont().deriveFont(Font.BOLD));
                } else {
                    c.setForeground(Color.GRAY);
                    c.setFont(c.getFont().deriveFont(Font.PLAIN));
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(resultTable);
        card.add(scrollPane, BorderLayout.CENTER);

        // Bottom Progress Bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        card.add(progressBar, BorderLayout.SOUTH);

        return card;
    }

    private List<Integer> parsePorts() {
        List<Integer> ports = new ArrayList<>();
        if (presetRadio.isSelected()) {
            if (webPortsCheck.isSelected()) {
                Collections.addAll(ports, 80, 443, 8080, 8443);
            }
            if (dbPortsCheck.isSelected()) {
                Collections.addAll(ports, 3306, 5432, 1521, 6379, 27017, 1433);
            }
            if (opsPortsCheck.isSelected()) {
                Collections.addAll(ports, 22, 21, 23, 3389, 2181, 9092, 9200, 11211);
            }
        } else {
            String text = customRangeField.getText().trim();
            String[] parts = text.split("[,;]");
            for (String part : parts) {
                part = part.trim();
                if (part.contains("-")) {
                    String[] range = part.split("-");
                    if (range.length == 2) {
                        try {
                            int start = Integer.parseInt(range[0].trim());
                            int end = Integer.parseInt(range[1].trim());
                            for (int p = Math.min(start, end); p <= Math.max(start, end); p++) {
                                if (p >= 1 && p <= 65535) ports.add(p);
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                } else {
                    try {
                        int p = Integer.parseInt(part);
                        if (p >= 1 && p <= 65535) ports.add(p);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        return ports;
    }

    private void startScan() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            JOptionPane.showMessageDialog(getView(), "请输入要扫描的目标主机/IP！", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        List<Integer> ports = parsePorts();
        if (ports.isEmpty()) {
            JOptionPane.showMessageDialog(getView(), "未勾选或未指定有效端口！", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        resultTableModel.setRowCount(0);
        isScanning.set(true);
        startBtn.setEnabled(false);
        stopBtn.setEnabled(true);

        int timeout = (Integer) timeoutSpinner.getValue();
        int threads = (Integer) threadSpinner.getValue();

        progressBar.setMaximum(ports.size());
        progressBar.setValue(0);

        statusLabel.setText("扫描中... 目标: " + host + " (共 " + ports.size() + " 个端口)");

        scanExecutor = Executors.newFixedThreadPool(threads);
        AtomicInteger completedCount = new AtomicInteger(0);
        AtomicInteger openCount = new AtomicInteger(0);

        new Thread(() -> {
            for (int port : ports) {
                if (!isScanning.get()) break;

                scanExecutor.submit(() -> {
                    if (!isScanning.get()) return;

                    long startMs = System.currentTimeMillis();
                    boolean isOpen = false;
                    try (Socket socket = new Socket()) {
                        socket.connect(new InetSocketAddress(host, port), timeout);
                        isOpen = true;
                    } catch (IOException ignored) {
                    }
                    long costMs = System.currentTimeMillis() - startMs;

                    if (isOpen) openCount.incrementAndGet();
                    final boolean finalOpen = isOpen;

                    SwingUtilities.invokeLater(() -> {
                        String serviceName = KNOWN_SERVICES.getOrDefault(port, "未知服务/自定义");
                        String statusStr = finalOpen ? "开放 (Open)" : "关闭 (Closed)";
                        String costStr = finalOpen ? costMs + " ms" : "-";

                        resultTableModel.addRow(new Object[]{port, statusStr, serviceName, costStr});

                        int current = completedCount.incrementAndGet();
                        progressBar.setValue(current);
                        statusLabel.setText("扫描进度: " + current + " / " + ports.size() + " | 发现开放端口: " + openCount.get());

                        if (current >= ports.size() || !isScanning.get()) {
                            finishScan(openCount.get(), ports.size());
                        }
                    });
                });
            }

            scanExecutor.shutdown();
        }).start();
    }

    private void stopScan() {
        isScanning.set(false);
        if (scanExecutor != null) {
            scanExecutor.shutdownNow();
        }
        finishScan(-1, -1);
    }

    private void finishScan(int openNum, int totalNum) {
        startBtn.setEnabled(true);
        stopBtn.setEnabled(false);
        isScanning.set(false);

        filterResults();

        if (openNum >= 0) {
            statusLabel.setText("扫描完成！共检测 " + totalNum + " 个端口，发现开放端口 " + openNum + " 个。");
        } else {
            statusLabel.setText("已被用户手动中止扫描。");
        }
    }

    private void filterResults() {
        if (onlyOpenCheckBox.isSelected()) {
            rowSorter.setRowFilter(RowFilter.regexFilter("开放 \\(Open\\)", 1));
        } else {
            rowSorter.setRowFilter(null);
        }
    }

    private void copyOpenPorts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < resultTableModel.getRowCount(); i++) {
            String status = (String) resultTableModel.getValueAt(i, 1);
            if ("开放 (Open)".equals(status)) {
                int port = (Integer) resultTableModel.getValueAt(i, 0);
                String service = (String) resultTableModel.getValueAt(i, 2);
                String rtt = (String) resultTableModel.getValueAt(i, 3);
                sb.append(port).append("\t").append(service).append("\t").append(rtt).append("\n");
            }
        }
        if (sb.length() > 0) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sb.toString()), null);
            JOptionPane.showMessageDialog(getView(), "开放端口列表已复制到剪贴板", "成功", JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(getView(), "未找到开放端口", "提示", JOptionPane.WARNING_MESSAGE);
        }
    }
}
