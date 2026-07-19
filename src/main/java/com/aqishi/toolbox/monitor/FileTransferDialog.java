package com.aqishi.toolbox.monitor;

import com.aqishi.toolbox.util.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 文件传输管理及进度显示窗口。
 */
public class FileTransferDialog extends JDialog {

    private final DesktopChannel channel;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, TransferTask> activeTasks = new ConcurrentHashMap<>();

    private JPanel tasksPanel;
    private JButton sendFileBtn;

    public FileTransferDialog(Window parent, DesktopChannel channel) {
        super(parent, I18n.get("remote_desktop.ft_title"), ModalityType.MODELESS);
        this.channel = channel;

        initComponents();
        setSize(550, 350);
        setLocationRelativeTo(parent);
    }

    private void initComponents() {
        setLayout(new BorderLayout(5, 5));

        // 顶部操作栏
        JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        sendFileBtn = new JButton(I18n.get("remote_desktop.ft_btn_send"));
        topBar.add(sendFileBtn);
        add(topBar, BorderLayout.NORTH);

        // 中部任务列表
        tasksPanel = new JPanel();
        tasksPanel.setLayout(new BoxLayout(tasksPanel, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(tasksPanel);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")));
        add(scroll, BorderLayout.CENTER);

        // 选择文件发送监听器
        sendFileBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle(I18n.get("remote_desktop.ft_choose_title"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                startSendFile(file);
            }
        });
    }

    /**
     * 发送端：初始化发送任务并通知接收端。
     */
    private void startSendFile(File file) {
        String fileId = UUID.randomUUID().toString();
        TransferTask task = new TransferTask(fileId, file.getName(), file.length(), true);
        task.localFile = file;
        activeTasks.put(fileId, task);

        // 添加任务UI项
        addTaskUI(task);

        // 发送 start_send 信令
        try {
            Map<String, Object> msg = new ConcurrentHashMap<>();
            msg.put("action", "start_send");
            msg.put("fileId", fileId);
            msg.put("fileName", file.getName());
            msg.put("fileSize", file.length());

            byte[] jsonBytes = mapper.writeValueAsString(msg).getBytes(StandardCharsets.UTF_8);
            byte[] payload = new byte[1 + jsonBytes.length];
            payload[0] = 0x00; // 0x00 表示 JSON 控制指令
            System.arraycopy(jsonBytes, 0, payload, 1, jsonBytes.length);

            channel.send(new DesktopMessage(DesktopMessage.TYPE_FILE_TRANSFER, payload));
            task.statusLabel.setText(I18n.get("remote_desktop.ft_waiting"));
        } catch (Exception e) {
            task.statusLabel.setText(I18n.get("remote_desktop.ft_failed", e.getMessage()));
        }
    }

    /**
     * 接收端：被控端收到 "start_send" 的静态处理辅助方法。
     */
    public static void handleFileTransferMessage(DesktopChannel channel, byte[] data, Map<String, FileReceiver> receivers, Consumer<String> log) {
        if (data == null || data.length < 1) return;
        byte subType = data[0];

        ObjectMapper mapper = new ObjectMapper();

        if (subType == 0x00) {
            // JSON 控制指令
            try {
                String jsonStr = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
                Map<String, Object> msg = mapper.readValue(jsonStr, Map.class);
                String action = (String) msg.get("action");
                String fileId = (String) msg.get("fileId");

                if ("start_send".equals(action)) {
                    String fileName = (String) msg.get("fileName");
                    Number fileSizeNum = (Number) msg.get("fileSize");
                    long fileSize = fileSizeNum.longValue();

                    log.accept(I18n.get("remote_desktop.ft_peer_req", fileName, formatBytes(fileSize)));

                    // 默认静默保存在被控端下载目录
                    String userHome = System.getProperty("user.home");
                    File downloadDir = new File(userHome, "Downloads");
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                    File localFile = new File(downloadDir, fileName + ".tmp");

                    FileReceiver receiver = new FileReceiver(fileId, fileName, fileSize, localFile);
                    receivers.put(fileId, receiver);

                    // 被控端自动同意接收
                    Map<String, Object> resp = new ConcurrentHashMap<>();
                    resp.put("action", "accept");
                    resp.put("fileId", fileId);

                    byte[] jsonBytes = mapper.writeValueAsString(resp).getBytes(StandardCharsets.UTF_8);
                    byte[] payload = new byte[1 + jsonBytes.length];
                    payload[0] = 0x00;
                    System.arraycopy(jsonBytes, 0, payload, 1, jsonBytes.length);

                    channel.send(new DesktopMessage(DesktopMessage.TYPE_FILE_TRANSFER, payload));
                    log.accept(I18n.get("remote_desktop.ft_peer_accept"));

                } else if ("complete".equals(action)) {
                    FileReceiver receiver = receivers.remove(fileId);
                    if (receiver != null) {
                        receiver.close();
                        File finalFile = new File(receiver.targetFile.getParent(), receiver.fileName);
                        if (finalFile.exists()) {
                            finalFile.delete();
                        }
                        if (receiver.targetFile.renameTo(finalFile)) {
                            log.accept(I18n.get("remote_desktop.ft_peer_success", finalFile.getAbsolutePath()));
                        } else {
                            log.accept(I18n.get("remote_desktop.ft_peer_rename_fail", receiver.targetFile.getAbsolutePath()));
                        }
                    }
                }
            } catch (Exception e) {
                log.accept("Exception in control signal: " + e.getMessage());
            }
        } else if (subType == 0x01) {
            // 二进制数据块
            try {
                if (data.length < 1 + 36 + 8 + 4) return;

                String fileId = new String(data, 1, 36, StandardCharsets.UTF_8);

                ByteBuffer buf = ByteBuffer.wrap(data, 37, 12);
                long chunkIndex = buf.getLong();
                int chunkSize = buf.getInt();

                FileReceiver receiver = receivers.get(fileId);
                if (receiver != null) {
                    receiver.writeChunk(chunkIndex, data, 49, chunkSize);
                }
            } catch (Exception e) {
                log.accept("Exception in writing block: " + e.getMessage());
            }
        }
    }

    /**
     * 控制端：处理从被控端收到的文件指令。
     */
    public void handleControlResponse(byte[] data) {
        if (data == null || data.length < 1) return;
        byte subType = data[0];
        if (subType != 0x00) return;

        try {
            String jsonStr = new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
            Map<String, Object> msg = mapper.readValue(jsonStr, Map.class);
            String action = (String) msg.get("action");
            String fileId = (String) msg.get("fileId");

            TransferTask task = activeTasks.get(fileId);
            if (task == null) return;

            if ("accept".equals(action)) {
                task.statusLabel.setText(I18n.get("remote_desktop.ft_sending"));
                new Thread(() -> sendChunksLoop(task)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void sendChunksLoop(TransferTask task) {
        int chunkSize = 64 * 1024;
        byte[] buffer = new byte[chunkSize];
        long totalSent = 0;
        long lastTime = System.currentTimeMillis();
        long lastSent = 0;

        try (FileInputStream fis = new FileInputStream(task.localFile)) {
            int readBytes;
            long chunkIndex = 0;

            while ((readBytes = fis.read(buffer)) != -1 && task.running) {
                byte[] fileIdBytes = task.fileId.getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[1 + 36 + 8 + 4 + readBytes];

                payload[0] = 0x01;
                System.arraycopy(fileIdBytes, 0, payload, 1, 36);

                ByteBuffer buf = ByteBuffer.allocate(12);
                buf.putLong(chunkIndex);
                buf.putInt(readBytes);
                System.arraycopy(buf.array(), 0, payload, 37, 12);

                System.arraycopy(buffer, 0, payload, 49, readBytes);

                channel.send(new DesktopMessage(DesktopMessage.TYPE_FILE_TRANSFER, payload));

                totalSent += readBytes;
                chunkIndex++;

                final long currentSent = totalSent;
                long now = System.currentTimeMillis();
                if (now - lastTime >= 500) {
                    double speed = (currentSent - lastSent) / 1024.0 / 1024.0 / ((now - lastTime) / 1000.0);
                    lastTime = now;
                    lastSent = currentSent;

                    SwingUtilities.invokeLater(() -> {
                        int pct = (int) (currentSent * 100 / task.fileSize);
                        task.progressBar.setValue(pct);
                        task.speedLabel.setText(String.format("%.2f MB/s", speed));
                    });
                }
                Thread.sleep(1);
            }

            if (task.running) {
                Map<String, Object> msg = new ConcurrentHashMap<>();
                msg.put("action", "complete");
                msg.put("fileId", task.fileId);

                byte[] jsonBytes = mapper.writeValueAsString(msg).getBytes(StandardCharsets.UTF_8);
                byte[] payload = new byte[1 + jsonBytes.length];
                payload[0] = 0x00;
                System.arraycopy(jsonBytes, 0, payload, 1, jsonBytes.length);

                channel.send(new DesktopMessage(DesktopMessage.TYPE_FILE_TRANSFER, payload));

                SwingUtilities.invokeLater(() -> {
                    task.progressBar.setValue(100);
                    task.statusLabel.setText(I18n.get("remote_desktop.ft_completed"));
                    task.speedLabel.setText("0.00 KB/s");
                });
            }

        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> task.statusLabel.setText(I18n.get("remote_desktop.ft_failed", e.getMessage())));
        } finally {
            activeTasks.remove(task.fileId);
        }
    }

    private void addTaskUI(TransferTask task) {
        JPanel container = new JPanel(new BorderLayout(5, 5));
        container.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JPanel infoPanel = new JPanel(new GridLayout(2, 1, 2, 2));
        JLabel nameLbl = new JLabel(task.fileName);
        nameLbl.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        JLabel sizeLbl = new JLabel(formatBytes(task.fileSize));
        sizeLbl.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        sizeLbl.setForeground(Color.GRAY);
        infoPanel.add(nameLbl);
        infoPanel.add(sizeLbl);
        container.add(infoPanel, BorderLayout.WEST);

        JPanel progressPanel = new JPanel(new BorderLayout(5, 2));
        task.progressBar = new JProgressBar(0, 100);
        task.progressBar.setStringPainted(true);
        progressPanel.add(task.progressBar, BorderLayout.CENTER);

        JPanel statusPanel = new JPanel(new BorderLayout());
        task.statusLabel = new JLabel(I18n.get("remote_desktop.ft_waiting"));
        task.statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        task.speedLabel = new JLabel("0.00 KB/s");
        task.speedLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        statusPanel.add(task.statusLabel, BorderLayout.WEST);
        statusPanel.add(task.speedLabel, BorderLayout.EAST);
        progressPanel.add(statusPanel, BorderLayout.SOUTH);

        container.add(progressPanel, BorderLayout.CENTER);

        JButton cancelBtn = new JButton(I18n.get("remote_desktop.ft_cancel_btn"));
        cancelBtn.addActionListener(e -> {
            task.running = false;
            task.statusLabel.setText(I18n.get("remote_desktop.ft_canceled"));
            cancelBtn.setEnabled(false);
        });
        container.add(cancelBtn, BorderLayout.EAST);

        tasksPanel.add(container);
        tasksPanel.revalidate();
        tasksPanel.repaint();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
    }

    private static class TransferTask {
        String fileId;
        String fileName;
        long fileSize;
        boolean isSender;
        boolean running = true;
        File localFile;

        JProgressBar progressBar;
        JLabel statusLabel;
        JLabel speedLabel;

        TransferTask(String fileId, String fileName, long fileSize, boolean isSender) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.isSender = isSender;
        }
    }

    public static class FileReceiver {
        String fileId;
        String fileName;
        long fileSize;
        File targetFile;
        java.io.RandomAccessFile raf;

        FileReceiver(String fileId, String fileName, long fileSize, File targetFile) throws IOException {
            this.fileId = fileId;
            this.fileName = fileName;
            this.fileSize = fileSize;
            this.targetFile = targetFile;
            this.raf = new java.io.RandomAccessFile(targetFile, "rw");
        }

        synchronized void writeChunk(long index, byte[] data, int offset, int length) throws IOException {
            long pos = index * 64 * 1024;
            raf.seek(pos);
            raf.write(data, offset, length);
        }

        synchronized void close() {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {}
                raf = null;
            }
        }
    }
}
