package com.aqishi.toolbox.misc.ssh.ui;

import com.aqishi.toolbox.misc.ssh.model.SshConfigStore;
import com.aqishi.toolbox.misc.ssh.model.SshConnectionConfig;
import com.aqishi.toolbox.misc.ssh.model.SshTunnelConfig;
import com.aqishi.toolbox.misc.ssh.session.SshSessionInstance;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Tokens;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * 端口转发 / 服务隧道管理面板
 */
public class SshTunnelPanel extends JPanel {

    private final SshSessionInstance sessionInstance;
    private final SshConnectionConfig connectionConfig;
    private final DefaultTableModel tableModel;
    private final JTable tunnelTable;
    private final JLabel statusHintLabel;

    public SshTunnelPanel(SshSessionInstance sessionInstance) {
        this.sessionInstance = sessionInstance;
        this.connectionConfig = sessionInstance.getConfig();

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 1. 顶部说明与快捷工具栏
        JPanel topPanel = new JPanel(new BorderLayout(6, 6));

        JLabel infoLabel = new JLabel("💡 开启服务连接时，系统会自动在 127.0.0.1 分配本地端口，无需手动设置端口映射。");
        infoLabel.setFont(Tokens.fontCaption());
        infoLabel.setForeground(Tokens.mutedForeground());

        JPanel actionBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton startBtn = Buttons.primary("开启连接");
        startBtn.addActionListener(e -> startSelectedTunnel());

        JButton stopBtn = Buttons.secondary("关闭连接");
        stopBtn.addActionListener(e -> stopSelectedTunnel());

        JButton browseBtn = Buttons.secondary("🌐 在浏览器中打开");
        browseBtn.addActionListener(e -> openInBrowser());

        JButton copyBtn = Buttons.secondary("复制本地地址");
        copyBtn.addActionListener(e -> copyLocalAddress());

        JButton addBtn = Buttons.primary("+ 添加服务");
        addBtn.addActionListener(e -> openAddDialog());

        actionBox.add(startBtn);
        actionBox.add(stopBtn);
        actionBox.add(browseBtn);
        actionBox.add(copyBtn);
        actionBox.add(addBtn);

        topPanel.add(infoLabel, BorderLayout.WEST);
        topPanel.add(actionBox, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);

        // 2. 表格展示
        String[] headers = {"服务名称", "远程目标服务", "自动分配本地访问地址", "运行状态", "随SSH自动启动"};
        tableModel = new DefaultTableModel(headers, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        tunnelTable = new JTable(tableModel);
        tunnelTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        tunnelTable.setFont(Tokens.fontBody());
        tunnelTable.getTableHeader().setFont(Tokens.fontSectionTitle());
        tunnelTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        tunnelTable.getColumnModel().getColumn(2).setCellRenderer(centerRenderer);
        tunnelTable.getColumnModel().getColumn(3).setCellRenderer(centerRenderer);
        tunnelTable.getColumnModel().getColumn(4).setCellRenderer(centerRenderer);

        tunnelTable.getColumnModel().getColumn(0).setPreferredWidth(180);
        tunnelTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        tunnelTable.getColumnModel().getColumn(2).setPreferredWidth(180);
        tunnelTable.getColumnModel().getColumn(3).setPreferredWidth(140);
        tunnelTable.getColumnModel().getColumn(4).setPreferredWidth(120);

        // 双击列表行自动切换开启/停止
        tunnelTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    toggleSelectedTunnel();
                }
            }
        });

        // 右键菜单
        tunnelTable.setComponentPopupMenu(createPopupMenu());

        JScrollPane scrollPane = new JScrollPane(tunnelTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(Tokens.border(), 1));
        add(scrollPane, BorderLayout.CENTER);

        // 3. 底部状态与操作
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 4));
        statusHintLabel = new JLabel("就绪");
        statusHintLabel.setFont(Tokens.fontCaption());

        JPanel editBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton editBtn = Buttons.secondary("修改配置");
        editBtn.addActionListener(e -> editSelectedTunnel());

        JButton delBtn = Buttons.secondary("删除");
        delBtn.addActionListener(e -> deleteSelectedTunnel());

        editBox.add(editBtn);
        editBox.add(delBtn);

        bottomPanel.add(statusHintLabel, BorderLayout.WEST);
        bottomPanel.add(editBox, BorderLayout.EAST);

        add(bottomPanel, BorderLayout.SOUTH);

        // 刷新列表数据
        refreshTable();
    }

    private JPopupMenu createPopupMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem startItem = new JMenuItem("⚡ 开启服务连接");
        startItem.addActionListener(e -> startSelectedTunnel());

        JMenuItem stopItem = new JMenuItem("⏹ 关闭服务连接");
        stopItem.addActionListener(e -> stopSelectedTunnel());

        JMenuItem browseItem = new JMenuItem("🌐 在系统浏览器中打开");
        browseItem.addActionListener(e -> openInBrowser());

        JMenuItem copyItem = new JMenuItem("📋 复制本地访问地址 (127.0.0.1:Port)");
        copyItem.addActionListener(e -> copyLocalAddress());

        JMenuItem editItem = new JMenuItem("修改服务...");
        editItem.addActionListener(e -> editSelectedTunnel());

        JMenuItem delItem = new JMenuItem("删除服务");
        delItem.addActionListener(e -> deleteSelectedTunnel());

        menu.add(startItem);
        menu.add(stopItem);
        menu.add(browseItem);
        menu.add(copyItem);
        menu.addSeparator();
        menu.add(editItem);
        menu.add(delItem);

        return menu;
    }

    private void openInBrowser() {
        SshTunnelConfig tunnel = getSelectedTunnel();
        if (tunnel != null) {
            if (!ensureTunnelReady(tunnel)) return;

            String url = tunnel.getBrowserUrl();
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(new java.net.URI(url));
                    statusHintLabel.setText("已在系统浏览器中打开: " + url);
                } else {
                    JOptionPane.showMessageDialog(this, "当前环境不支持自动打开浏览器，请手动访问: " + url, "提示", JOptionPane.INFORMATION_MESSAGE);
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "打开浏览器失败: " + ex.getMessage() + "\n链接: " + url, "错误", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            JOptionPane.showMessageDialog(this, "请先在列表中选择要浏览的 HTTP 服务隧道", "提示", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    public void refreshTable() {
        String selectedId = null;
        SshTunnelConfig selected = getSelectedTunnel();
        if (selected != null) selectedId = selected.getId();
        tableModel.setRowCount(0);
        List<SshTunnelConfig> tunnels = connectionConfig.getTunnels();
        for (SshTunnelConfig t : tunnels) {
            String remoteTarget = t.getRemoteHost() + ":" + t.getRemotePort();
            String localAddr = t.getLocalConnectionString();
            String statusStr = t.getStatus().getLabel();
            if (t.getErrorMessage() != null) {
                statusStr += " (" + t.getErrorMessage() + ")";
            }
            String autoStartStr = t.isAutoStart() ? "是" : "否";

            tableModel.addRow(new Object[]{t.getName(), remoteTarget, localAddr, statusStr, autoStartStr});
        }
        if (selectedId != null) {
            for (int i = 0; i < tunnels.size(); i++) {
                if (selectedId.equals(tunnels.get(i).getId())) {
                    tunnelTable.getSelectionModel().setSelectionInterval(i, i);
                    break;
                }
            }
        }
    }

    private SshTunnelConfig getSelectedTunnel() {
        int row = tunnelTable.getSelectedRow();
        if (row >= 0 && row < connectionConfig.getTunnels().size()) {
            return connectionConfig.getTunnels().get(row);
        }
        return null;
    }

    private void startSelectedTunnel() {
        SshTunnelConfig tunnel = getSelectedTunnel();
        if (tunnel != null) {
            if (ensureTunnelReady(tunnel)) {
                statusHintLabel.setText("服务连接已开启：127.0.0.1:" + tunnel.getAssignedLocalPort());
            }
        } else {
            JOptionPane.showMessageDialog(this, "请选择要开启的远程服务隧道", "提示", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /** Starts the forward and verifies the SSH-side target before exposing it to a client. */
    private boolean ensureTunnelReady(SshTunnelConfig tunnel) {
        if (!sessionInstance.isConnected()) {
            statusHintLabel.setText("提示：SSH 未连接，无法开启隧道");
            JOptionPane.showMessageDialog(this, "SSH 连接尚未建立，请先建立 SSH 连接。", "提示", JOptionPane.WARNING_MESSAGE);
            return false;
        }

        boolean started = tunnel.getStatus() == SshTunnelConfig.Status.RUNNING
                && tunnel.getAssignedLocalPort() > 0;
        if (!started) {
            started = sessionInstance.startTunnel(tunnel);
        }
        if (started) {
            try {
                sessionInstance.verifyTunnelTarget(tunnel);
            } catch (Exception error) {
                sessionInstance.stopTunnel(tunnel);
                tunnel.setErrorMessage("远程目标不可用：" + error.getMessage());
                started = false;
            }
        }
        refreshTable();
        if (!started) {
            String message = tunnel.getErrorMessage();
            if (message == null || message.trim().isEmpty()) message = "隧道无法连接远程目标";
            statusHintLabel.setText("开启失败：" + message);
            JOptionPane.showMessageDialog(this, "开启隧道失败：" + message, "错误", JOptionPane.ERROR_MESSAGE);
        }
        return started;
    }

    private void stopSelectedTunnel() {
        SshTunnelConfig tunnel = getSelectedTunnel();
        if (tunnel != null) {
            sessionInstance.stopTunnel(tunnel);
            refreshTable();
            statusHintLabel.setText("服务连接已关闭");
        } else {
            JOptionPane.showMessageDialog(this, "请选择要关闭的远程服务隧道", "提示", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void toggleSelectedTunnel() {
        SshTunnelConfig tunnel = getSelectedTunnel();
        if (tunnel != null) {
            if (tunnel.getStatus() == SshTunnelConfig.Status.RUNNING) {
                stopSelectedTunnel();
            } else {
                startSelectedTunnel();
            }
        }
    }

    private void copyLocalAddress() {
        SshTunnelConfig tunnel = getSelectedTunnel();
        if (tunnel != null) {
            if (tunnel.getStatus() == SshTunnelConfig.Status.RUNNING && tunnel.getAssignedLocalPort() > 0) {
                String addr = "127.0.0.1:" + tunnel.getAssignedLocalPort();
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(addr), null);
                statusHintLabel.setText("已复制本地访问地址: " + addr);
                JOptionPane.showMessageDialog(this, "已复制本地服务地址: " + addr, "成功", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this, "该服务连接尚未开启，请先开启连接。", "提示", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void openAddDialog() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        SshTunnelDialog dialog = new SshTunnelDialog(owner, null);
        dialog.setVisible(true);
        if (dialog.isSaved()) {
            SshTunnelConfig newTunnel = dialog.getConfig();
            connectionConfig.getTunnels().add(newTunnel);
            SshConfigStore.getInstance().save();
            refreshTable();

            if (sessionInstance.isConnected()) {
                boolean ok = ensureTunnelReady(newTunnel);
                statusHintLabel.setText(ok
                        ? "服务连接已开启：127.0.0.1:" + newTunnel.getAssignedLocalPort()
                        : "开启失败：" + newTunnel.getErrorMessage());
            }
        }
    }

    private void editSelectedTunnel() {
        SshTunnelConfig tunnel = getSelectedTunnel();
        if (tunnel != null) {
            Window owner = SwingUtilities.getWindowAncestor(this);
            SshTunnelDialog dialog = new SshTunnelDialog(owner, tunnel);
            dialog.setVisible(true);
            if (dialog.isSaved()) {
                boolean wasRunning = tunnel.getStatus() == SshTunnelConfig.Status.RUNNING;
                if (wasRunning) sessionInstance.stopTunnel(tunnel);
                SshTunnelConfig updated = dialog.getConfig();
                tunnel.setName(updated.getName());
                tunnel.setRemoteHost(updated.getRemoteHost());
                tunnel.setRemotePort(updated.getRemotePort());
                tunnel.setAutoStart(updated.isAutoStart());
                tunnel.setBrowserScheme(updated.getBrowserScheme());
                tunnel.setBrowserPath(updated.getBrowserPath());

                SshConfigStore.getInstance().save();
                refreshTable();
                if (wasRunning && sessionInstance.isConnected()) {
                    ensureTunnelReady(tunnel);
                }
            }
        }
    }

    private void deleteSelectedTunnel() {
        SshTunnelConfig tunnel = getSelectedTunnel();
        if (tunnel != null) {
            int confirm = JOptionPane.showConfirmDialog(this, "确定删除服务隧道 \"" + tunnel.getName() + "\" ?", "确认", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                sessionInstance.stopTunnel(tunnel);
                connectionConfig.getTunnels().remove(tunnel);
                SshConfigStore.getInstance().save();
                refreshTable();
            }
        }
    }
}
