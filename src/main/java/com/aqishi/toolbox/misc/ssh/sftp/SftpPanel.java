package com.aqishi.toolbox.misc.ssh.sftp;

import com.aqishi.toolbox.misc.ssh.session.SshSessionInstance;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;

/**
 * SFTP 远程文件传输与目录管理面板
 */
public class SftpPanel extends JPanel {

    private final SshSessionInstance sessionInstance;
    private final JTextField pathField;
    private final JTable fileTable;
    private final DefaultTableModel tableModel;
    private final JProgressBar progressBar;
    private final JLabel statusLabel;

    private String currentPath = "/";

    public SftpPanel(SshSessionInstance sessionInstance) {
        this.sessionInstance = sessionInstance;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // 1. 顶部路径栏与操作按钮
        JPanel topPanel = new JPanel(new BorderLayout(6, 6));

        JPanel pathBox = new JPanel(new BorderLayout(4, 4));
        JLabel pathLabel = new JLabel(" 远程路径: ");
        pathLabel.setFont(Tokens.fontSectionTitle());
        pathField = Fields.text("/");

        JButton goBtn = Buttons.secondary("Go");
        goBtn.addActionListener(e -> loadDirectory(pathField.getText()));

        pathBox.add(pathLabel, BorderLayout.WEST);
        pathBox.add(pathField, BorderLayout.CENTER);
        pathBox.add(goBtn, BorderLayout.EAST);

        JPanel btnBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton upBtn = Buttons.secondary("上一级");
        upBtn.addActionListener(e -> goUpDirectory());

        JButton refreshBtn = Buttons.secondary("刷新");
        refreshBtn.addActionListener(e -> refresh());

        JButton mkdirBtn = Buttons.secondary("新建文件夹");
        mkdirBtn.addActionListener(e -> createDirectoryDialog());

        JButton uploadBtn = Buttons.primary("上传文件");
        uploadBtn.addActionListener(e -> uploadFileDialog());

        btnBox.add(upBtn);
        btnBox.add(refreshBtn);
        btnBox.add(mkdirBtn);
        btnBox.add(uploadBtn);

        topPanel.add(pathBox, BorderLayout.CENTER);
        topPanel.add(btnBox, BorderLayout.EAST);

        add(topPanel, BorderLayout.NORTH);

        // 2. 文件列表表格
        String[] headers = {"类型", "名称", "大小", "修改时间", "权限"};
        tableModel = new DefaultTableModel(headers, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        fileTable = new JTable(tableModel);
        fileTable.setRowHeight(Tokens.TABLE_ROW_HEIGHT);
        fileTable.setFont(Tokens.fontBody());
        fileTable.getTableHeader().setFont(Tokens.fontSectionTitle());
        fileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 自定义单元格对齐与外观
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(JLabel.CENTER);
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        fileTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);

        fileTable.getColumnModel().getColumn(1).setPreferredWidth(300);
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(100);
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(160);
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(120);

        // 双击进入子目录
        fileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int row = fileTable.getSelectedRow();
                    if (row >= 0) {
                        String type = (String) tableModel.getValueAt(row, 0);
                        String name = (String) tableModel.getValueAt(row, 1);
                        if ("<DIR>".equals(type)) {
                            if ("..".equals(name)) {
                                goUpDirectory();
                            } else {
                                String nextPath = currentPath.endsWith("/") ? currentPath + name : currentPath + "/" + name;
                                loadDirectory(nextPath);
                            }
                        }
                    }
                }
            }
        });

        // 右键上下文菜单
        fileTable.setComponentPopupMenu(createPopupMenu());

        JScrollPane scrollPane = new JScrollPane(fileTable);
        scrollPane.setBorder(BorderFactory.createLineBorder(Tokens.border(), 1));
        add(scrollPane, BorderLayout.CENTER);

        // 3. 底部状态与进度条
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 4));
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(Tokens.fontCaption());

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        progressBar.setPreferredSize(new Dimension(200, 16));

        bottomPanel.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.add(progressBar, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private JPopupMenu createPopupMenu() {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem downloadItem = new JMenuItem("下载到本地...");
        downloadItem.addActionListener(e -> downloadSelectedFile());

        JMenuItem renameItem = new JMenuItem("重命名...");
        renameItem.addActionListener(e -> renameSelectedFile());

        JMenuItem deleteItem = new JMenuItem("删除");
        deleteItem.addActionListener(e -> deleteSelectedFile());

        JMenuItem refreshItem = new JMenuItem("刷新列表");
        refreshItem.addActionListener(e -> refresh());

        menu.add(downloadItem);
        menu.add(renameItem);
        menu.add(deleteItem);
        menu.addSeparator();
        menu.add(refreshItem);
        return menu;
    }

    public void refresh() {
        loadDirectory(currentPath);
    }

    public void goUpDirectory() {
        if ("/".equals(currentPath)) return;
        int idx = currentPath.lastIndexOf('/');
        if (idx <= 0) {
            loadDirectory("/");
        } else {
            loadDirectory(currentPath.substring(0, idx));
        }
    }

    /**
     * 异步加载 SFTP 目录列表
     */
    public void loadDirectory(String path) {
        if (!sessionInstance.isConnected()) {
            statusLabel.setText("提示：SSH 会话未连接，请先建立连接。");
            return;
        }
        statusLabel.setText("正在读取目录: " + path + "...");

        new Thread(() -> {
            try {
                ChannelSftp sftp = sessionInstance.getSftpChannel();
                if (sftp == null) {
                    SwingUtilities.invokeLater(() -> statusLabel.setText("获取 SFTP 通道失败。"));
                    return;
                }
                String targetPath = (path == null || path.trim().isEmpty()) ? "/" : path.trim();
                sftp.cd(targetPath);
                String pwd = sftp.pwd();

                Vector<ChannelSftp.LsEntry> entries = sftp.ls(pwd);
                List<Object[]> rows = new ArrayList<>();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                for (ChannelSftp.LsEntry entry : entries) {
                    String filename = entry.getFilename();
                    if (".".equals(filename)) continue;

                    SftpATTRS attrs = entry.getAttrs();
                    boolean isDir = attrs.isDir();
                    String type = isDir ? "<DIR>" : "<FILE>";
                    String sizeStr = isDir ? "-" : formatSize(attrs.getSize());
                    String mtime = sdf.format(new Date(attrs.getMTime() * 1000L));
                    String permissions = attrs.getPermissionsString();

                    rows.add(new Object[]{type, filename, sizeStr, mtime, permissions});
                }

                // 排序：目录在前，文件在后
                rows.sort((a, b) -> {
                    boolean isDirA = "<DIR>".equals(a[0]);
                    boolean isDirB = "<DIR>".equals(b[0]);
                    if (isDirA && !isDirB) return -1;
                    if (!isDirA && isDirB) return 1;
                    return ((String) a[1]).compareToIgnoreCase((String) b[1]);
                });

                SwingUtilities.invokeLater(() -> {
                    currentPath = pwd;
                    pathField.setText(pwd);
                    tableModel.setRowCount(0);
                    for (Object[] r : rows) {
                        tableModel.addRow(r);
                    }
                    statusLabel.setText("已加载 " + rows.size() + " 个项 (" + pwd + ")");
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> statusLabel.setText("加载目录失败: " + e.getMessage()));
            }
        }, "SFTP-LoadDir").start();
    }

    private void createDirectoryDialog() {
        String name = JOptionPane.showInputDialog(this, "输入新建文件夹名称:", "新建文件夹", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) return;

        new Thread(() -> {
            try {
                ChannelSftp sftp = sessionInstance.getSftpChannel();
                if (sftp != null) {
                    String dirPath = currentPath.endsWith("/") ? currentPath + name.trim() : currentPath + "/" + name.trim();
                    sftp.mkdir(dirPath);
                    SwingUtilities.invokeLater(this::refresh);
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "创建失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    private void uploadFileDialog() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要上传的本地文件");
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File selectedFile = chooser.getSelectedFile();
            if (selectedFile != null && selectedFile.isFile()) {
                String remoteFile = currentPath.endsWith("/") ? currentPath + selectedFile.getName() : currentPath + "/" + selectedFile.getName();
                
                progressBar.setVisible(true);
                progressBar.setValue(0);
                statusLabel.setText("正在上传: " + selectedFile.getName() + "...");

                new Thread(() -> {
                    try (FileInputStream fis = new FileInputStream(selectedFile)) {
                        ChannelSftp sftp = sessionInstance.getSftpChannel();
                        long fileSize = selectedFile.length();
                        sftp.put(fis, remoteFile, new com.jcraft.jsch.SftpProgressMonitor() {
                            private long count = 0;
                            @Override
                            public void init(int op, String src, String dest, long max) {}
                            @Override
                            public boolean count(long count) {
                                this.count += count;
                                int percent = fileSize > 0 ? (int) ((this.count * 100) / fileSize) : 100;
                                SwingUtilities.invokeLater(() -> progressBar.setValue(percent));
                                return true;
                            }
                            @Override
                            public void end() {}
                        });
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setVisible(false);
                            statusLabel.setText("上传成功: " + selectedFile.getName());
                            refresh();
                        });
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> {
                            progressBar.setVisible(false);
                            statusLabel.setText("上传失败: " + e.getMessage());
                            JOptionPane.showMessageDialog(this, "上传失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                }).start();
            }
        }
    }

    private void downloadSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row < 0) return;
        String type = (String) tableModel.getValueAt(row, 0);
        String name = (String) tableModel.getValueAt(row, 1);
        if ("<DIR>".equals(type)) {
            JOptionPane.showMessageDialog(this, "目前仅支持单个文件下载", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(name));
        chooser.setDialogTitle("保存文件到本地");
        int res = chooser.showSaveDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File saveFile = chooser.getSelectedFile();
            String remoteFile = currentPath.endsWith("/") ? currentPath + name : currentPath + "/" + name;

            progressBar.setVisible(true);
            progressBar.setValue(0);
            statusLabel.setText("正在下载: " + name + "...");

            new Thread(() -> {
                try (FileOutputStream fos = new FileOutputStream(saveFile)) {
                    ChannelSftp sftp = sessionInstance.getSftpChannel();
                    SftpATTRS attrs = sftp.stat(remoteFile);
                    long fileSize = attrs.getSize();

                    sftp.get(remoteFile, fos, new com.jcraft.jsch.SftpProgressMonitor() {
                        private long count = 0;
                        @Override
                        public void init(int op, String src, String dest, long max) {}
                        @Override
                        public boolean count(long count) {
                            this.count += count;
                            int percent = fileSize > 0 ? (int) ((this.count * 100) / fileSize) : 100;
                            SwingUtilities.invokeLater(() -> progressBar.setValue(percent));
                            return true;
                        }
                        @Override
                        public void end() {}
                    });

                    SwingUtilities.invokeLater(() -> {
                        progressBar.setVisible(false);
                        statusLabel.setText("下载成功: " + saveFile.getAbsolutePath());
                        JOptionPane.showMessageDialog(this, "下载成功！文件保存在: " + saveFile.getAbsolutePath(), "成功", JOptionPane.INFORMATION_MESSAGE);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setVisible(false);
                        statusLabel.setText("下载失败: " + e.getMessage());
                        JOptionPane.showMessageDialog(this, "下载失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        }
    }

    private void renameSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row < 0) return;
        String oldName = (String) tableModel.getValueAt(row, 1);
        String newName = JOptionPane.showInputDialog(this, "修改名称:", oldName);
        if (newName == null || newName.trim().isEmpty() || newName.equals(oldName)) return;

        new Thread(() -> {
            try {
                ChannelSftp sftp = sessionInstance.getSftpChannel();
                if (sftp != null) {
                    String oldPath = currentPath.endsWith("/") ? currentPath + oldName : currentPath + "/" + oldName;
                    String newPath = currentPath.endsWith("/") ? currentPath + newName.trim() : currentPath + "/" + newName.trim();
                    sftp.rename(oldPath, newPath);
                    SwingUtilities.invokeLater(this::refresh);
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "重命名失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    private void deleteSelectedFile() {
        int row = fileTable.getSelectedRow();
        if (row < 0) return;
        String type = (String) tableModel.getValueAt(row, 0);
        String name = (String) tableModel.getValueAt(row, 1);

        int confirm = JOptionPane.showConfirmDialog(this, "确定删除 " + name + " ?", "确认删除", JOptionPane.YES_NO_OPTION);
        if (confirm != JOptionPane.YES_OPTION) return;

        new Thread(() -> {
            try {
                ChannelSftp sftp = sessionInstance.getSftpChannel();
                if (sftp != null) {
                    String path = currentPath.endsWith("/") ? currentPath + name : currentPath + "/" + name;
                    if ("<DIR>".equals(type)) {
                        deleteRecursive(sftp, path);
                    } else {
                        sftp.rm(path);
                    }
                    SwingUtilities.invokeLater(this::refresh);
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "删除失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            }
        }).start();
    }

    private void deleteRecursive(ChannelSftp sftp, String path) throws Exception {
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(path);
        for (ChannelSftp.LsEntry entry : entries) {
            String filename = entry.getFilename();
            if (".".equals(filename) || "..".equals(filename)) continue;
            String childPath = path.endsWith("/") ? path + filename : path + "/" + filename;
            if (entry.getAttrs().isDir()) {
                deleteRecursive(sftp, childPath);
            } else {
                sftp.rm(childPath);
            }
        }
        sftp.rmdir(path);
    }

    private String formatSize(long size) {
        if (size < 1024) return size + " B";
        int z = (63 - Long.numberOfLeadingZeros(size)) / 10;
        return String.format("%.1f %cB", (double) size / (1L << (z * 10)), " KMGTPE".charAt(z));
    }
}
