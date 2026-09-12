package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.UIUtils;

import javax.net.ssl.SSLSocketFactory;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 容器文件传输：经 K8s exec WebSocket 通道下载/上传单个文件。
 *
 * <p>从 {@code K8sManagerPanel} 拆出；连接参数经 {@link K8sClusterContext} 读取，
 * 在途连接与工作线程登记到 {@link TransferRegistry} 以便应用退出时统一取消。</p>
 */
final class K8sPodFileTransfer {

    private K8sPodFileTransfer() {
    }

    /** 从容器下载文件到本地。 */
    static void download(Component parent, K8sClusterContext ctx,
                         String ns, String podName, String containerName) {
        String containerPath = UIUtils.input(parent, "请输入容器内要下载的文件路径（绝对路径）：", "/etc/hosts");
        if (containerPath == null || containerPath.trim().isEmpty()) return;
        final String finalContainerPath = containerPath.trim();

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择保存的本地文件路径");
        String defaultFileName = new File(finalContainerPath).getName();
        chooser.setSelectedFile(new File(defaultFileName));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File localFile = chooser.getSelectedFile();

        JDialog progressDialog = progress(parent, "正在下载文件");

        ctx.transfers().startWorker("k8s-file-download", () -> {
            boolean success = false;
            String errorMsg = "";
            FileOutputStream fos = null;
            org.java_websocket.client.WebSocketClient client = null;
            try {
                fos = new FileOutputStream(localFile);
                final FileOutputStream finalFos = fos;
                final StringBuilder stderr = new StringBuilder();

                String fullPath = execUrl(ctx, ns, podName, containerName,
                        "stdin=false&stdout=true&stderr=true&tty=false&command=cat"
                                + "&command=" + URLEncoder.encode(finalContainerPath, "UTF-8"));

                URI uri = new URI(fullPath);
                Map<String, String> headers = authHeaders(ctx);
                CountDownLatch latch = new CountDownLatch(1);

                client = new org.java_websocket.client.WebSocketClient(uri, headers) {
                    @Override
                    public void onOpen(org.java_websocket.handshake.ServerHandshake handshakedata) {
                        SwingUtilities.invokeLater(() -> progressDialog.setTitle("正在传输数据..."));
                    }

                    @Override
                    public void onMessage(String message) {}

                    @Override
                    public void onMessage(java.nio.ByteBuffer bytes) {
                        if (bytes.remaining() > 0) {
                            byte channel = bytes.get();
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            if (channel == 1) { // stdout
                                try {
                                    finalFos.write(data);
                                } catch (IOException e) {
                                    // ignore
                                }
                            } else if (channel == 2 || channel == 3) { // stderr/error
                                stderr.append(new String(data, StandardCharsets.UTF_8));
                            }
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Exception ex) {
                        stderr.append(ex.getMessage());
                        latch.countDown();
                    }
                };

                applyTls(client, ctx);

                ctx.transfers().register(client);
                client.connect();
                latch.await();

                if (stderr.length() > 0) {
                    errorMsg = stderr.toString();
                } else {
                    success = true;
                }
            } catch (Exception ex) {
                errorMsg = ex.getMessage();
            } finally {
                if (fos != null) {
                    try { fos.close(); } catch (Exception e) { Errors.ignored("关闭下载输出流失败，文件已写完", e); }
                }
                if (client != null) {
                    try { client.close(); } catch (Exception e) { Errors.ignored("关闭 K8s 客户端失败", e); }
                    ctx.transfers().unregister(client);
                }
            }

            final boolean finalSuccess = success;
            final String finalError = errorMsg;
            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                if (finalSuccess) {
                    UIUtils.info(parent, "文件下载成功！");
                } else {
                    try { localFile.delete(); } catch (Exception e) { Errors.ignored("删除下载失败的残留文件失败", e); }
                    UIUtils.error(parent, "文件下载失败: " + finalError);
                }
            });
        });

        progressDialog.setVisible(true);
    }

    /** 从本地上传文件到容器。 */
    static void upload(Component parent, K8sClusterContext ctx,
                       String ns, String podName, String containerName) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要上传的本地文件");
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File localFile = chooser.getSelectedFile();

        String containerPath = UIUtils.input(parent, "请输入要上传到容器的文件保存路径（绝对路径）：", "/tmp/" + localFile.getName());
        if (containerPath == null || containerPath.trim().isEmpty()) return;
        final String finalContainerPath = containerPath.trim();

        JDialog progressDialog = progress(parent, "正在上传文件");

        ctx.transfers().startWorker("k8s-file-upload", () -> {
            boolean success = false;
            String errorMsg = "";
            org.java_websocket.client.WebSocketClient client = null;
            try {
                final StringBuilder stderr = new StringBuilder();

                String escapedPath = finalContainerPath.replace("'", "'\\''");
                String fullPath = execUrl(ctx, ns, podName, containerName,
                        "stdin=true&stdout=true&stderr=true&tty=false&command=sh&command=-c"
                                + "&command=" + URLEncoder.encode("cat > '" + escapedPath + "'", "UTF-8"));

                URI uri = new URI(fullPath);
                Map<String, String> headers = authHeaders(ctx);
                CountDownLatch latch = new CountDownLatch(1);

                client = new org.java_websocket.client.WebSocketClient(uri, headers) {
                    @Override
                    public void onOpen(org.java_websocket.handshake.ServerHandshake handshakedata) {
                        SwingUtilities.invokeLater(() -> progressDialog.setTitle("正在上传数据..."));
                        ctx.transfers().startWorker("k8s-file-upload-stream", () -> {
                            try (FileInputStream fis = new FileInputStream(localFile)) {
                                byte[] buffer = new byte[8192];
                                int read;
                                while ((read = fis.read(buffer)) != -1) {
                                    byte[] frame = new byte[read + 1];
                                    frame[0] = 0; // channel 0 (stdin)
                                    System.arraycopy(buffer, 0, frame, 1, read);
                                    send(frame);
                                }
                                Thread.sleep(800);
                            } catch (Exception e) {
                                stderr.append(e.getMessage());
                            } finally {
                                close();
                            }
                        });
                    }

                    @Override
                    public void onMessage(String message) {}

                    @Override
                    public void onMessage(java.nio.ByteBuffer bytes) {
                        if (bytes.remaining() > 0) {
                            byte channel = bytes.get();
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            if (channel == 2 || channel == 3) { // stderr/error
                                stderr.append(new String(data, StandardCharsets.UTF_8));
                            }
                        }
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Exception ex) {
                        stderr.append(ex.getMessage());
                        latch.countDown();
                    }
                };

                applyTls(client, ctx);

                ctx.transfers().register(client);
                client.connect();
                latch.await();

                if (stderr.length() > 0) {
                    errorMsg = stderr.toString();
                } else {
                    success = true;
                }
            } catch (Exception ex) {
                errorMsg = ex.getMessage();
            } finally {
                if (client != null) {
                    try { client.close(); } catch (Exception ignored) { Errors.ignored("关闭 K8s 客户端失败", ignored); }
                    ctx.transfers().unregister(client);
                }
            }

            final boolean finalSuccess = success;
            final String finalError = errorMsg;
            SwingUtilities.invokeLater(() -> {
                progressDialog.dispose();
                if (finalSuccess) {
                    UIUtils.info(parent, "文件上传成功！");
                } else {
                    UIUtils.error(parent, "文件上传失败: " + finalError);
                }
            });
        });

        progressDialog.setVisible(true);
    }

    private static JDialog progress(Component parent, String title) {
        JDialog progressDialog = new JDialog((Frame) null, title, true);
        progressDialog.setSize(300, 100);
        progressDialog.setLocationRelativeTo(parent);
        progressDialog.setLayout(new BorderLayout(8, 8));
        JLabel statusLabel = new JLabel("正在连接 API Server...", SwingConstants.CENTER);
        progressDialog.add(statusLabel, BorderLayout.CENTER);
        return progressDialog;
    }

    private static String execUrl(K8sClusterContext ctx, String ns, String podName,
                                  String containerName, String query) {
        return K8sExecTerminal.toWebSocketUrl(ctx.serverUrl())
                + "/api/v1/namespaces/" + ns + "/pods/" + podName + "/exec"
                + "?container=" + containerName + "&" + query;
    }

    private static Map<String, String> authHeaders(K8sClusterContext ctx) {
        Map<String, String> headers = new HashMap<>();
        if (ctx.token() != null && !ctx.token().isEmpty()) {
            headers.put("Authorization", "Bearer " + ctx.token());
        }
        headers.put("Sec-WebSocket-Protocol", "v4.channel.k8s.io");
        return headers;
    }

    private static void applyTls(org.java_websocket.client.WebSocketClient client, K8sClusterContext ctx) {
        SSLSocketFactory factory = ctx.socketFactory();
        if (ctx.serverUrl().startsWith("https://") && factory != null) {
            client.setSocketFactory(factory);
        }
    }
}
