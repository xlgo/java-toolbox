package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.UIUtils;
import com.jediterm.terminal.Questioner;
import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 容器控制台（Exec）：基于 JediTerm 的交互式终端，经 K8s exec WebSocket 通道收发数据。
 *
 * <p>从 {@code K8sManagerPanel} 拆出；集群连接参数经 {@link K8sClusterContext} 读取。</p>
 */
final class K8sExecTerminal {

    private K8sExecTerminal() {
    }

    static void open(Component parent, K8sClusterContext ctx,
                     String ns, String podName, String containerName) {
        Window ancestor = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog dialog = new JDialog(ancestor instanceof Frame ? (Frame) ancestor : (Frame) null,
                "容器控制台 (Exec) - " + podName + " / " + containerName, true);
        dialog.setSize(850, 520);
        dialog.setLocationRelativeTo(ancestor);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel statusLabel = new JLabel("正在连接 API Server...");
        statusLabel.setBorder(new javax.swing.border.EmptyBorder(6, 10, 6, 10));

        java.util.concurrent.LinkedBlockingQueue<String> readQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        StringBuilder readBuffer = new StringBuilder();

        String wsUrl = toWebSocketUrl(ctx.serverUrl());

        org.java_websocket.client.WebSocketClient[] clientHolder = new org.java_websocket.client.WebSocketClient[1];

        TtyConnector connector = new TtyConnector() {
            private boolean closed = false;

            @Override
            public String getName() {
                return "K8s Container Terminal";
            }

            @Override
            public boolean init(Questioner q) {
                return true;
            }

            @Override
            public void write(byte[] bytes) throws IOException {
                org.java_websocket.client.WebSocketClient client = clientHolder[0];
                if (bytes != null && bytes.length > 0 && client != null && client.isOpen()) {
                    try {
                        byte[] frame = new byte[bytes.length + 1];
                        frame[0] = 0; // channel 0 (stdin)
                        System.arraycopy(bytes, 0, frame, 1, bytes.length);
                        client.send(frame);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            @Override
            public void write(String s) throws IOException {
                if (s != null) {
                    write(s.getBytes(StandardCharsets.UTF_8));
                }
            }

            @Override
            public int read(char[] buf, int offset, int len) throws IOException {
                if (len <= 0) return 0;
                synchronized (readBuffer) {
                    while (readBuffer.length() == 0) {
                        if (closed) return -1;
                        try {
                            String s = readQueue.take();
                            if (closed) return -1;
                            readBuffer.append(s);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return -1;
                        }
                    }
                    int count = Math.min(len, readBuffer.length());
                    readBuffer.getChars(0, count, buf, offset);
                    readBuffer.delete(0, count);
                    return count;
                }
            }

            @Override
            public void close() {
                closed = true;
                try {
                    if (clientHolder[0] != null) {
                        clientHolder[0].close();
                    }
                } catch (Exception e) {
                    Errors.ignored("关闭 K8s Exec 客户端失败", e);
                }
                readQueue.offer(""); // Unblock read thread if any
            }

            @Override
            public void resize(Dimension winSize) {
                org.java_websocket.client.WebSocketClient client = clientHolder[0];
                if (client != null && client.isOpen()) {
                    try {
                        String resizeJson = String.format("{\"Width\":%d,\"Height\":%d}", winSize.width, winSize.height);
                        byte[] data = resizeJson.getBytes(StandardCharsets.UTF_8);
                        byte[] frame = new byte[data.length + 1];
                        frame[0] = 4; // channel 4 (resize)
                        System.arraycopy(data, 0, frame, 1, data.length);
                        client.send(frame);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }

            @Override
            public void resize(Dimension winSize, Dimension pixelSize) {
                resize(winSize);
            }

            @Override
            public void resize(com.jediterm.core.util.TermSize termSize) {
                if (termSize != null) {
                    resize(new Dimension(termSize.getColumns(), termSize.getRows()));
                }
            }

            @Override
            public int waitFor() throws InterruptedException {
                return 0;
            }

            @Override
            public boolean isConnected() {
                return !closed;
            }

            @Override
            public boolean ready() {
                return !closed;
            }
        };

        DefaultSettingsProvider settingsProvider = new DefaultSettingsProvider() {
            @Override
            public Font getTerminalFont() {
                return new Font("Monospaced", Font.PLAIN, 14);
            }

            @Override
            public float getTerminalFontSize() {
                return 14.0f;
            }

            @Override
            public com.jediterm.terminal.HyperlinkStyle.HighlightMode getHyperlinkHighlightingMode() {
                return com.jediterm.terminal.HyperlinkStyle.HighlightMode.NEVER;
            }
        };

        JediTermWidget terminalWidget = new JediTermWidget(settingsProvider);
        terminalWidget.createTerminalSession(connector);
        terminalWidget.start();

        dialog.add(statusLabel, BorderLayout.NORTH);
        dialog.add(terminalWidget, BorderLayout.CENTER);

        try {
            String fullPath = wsUrl + "/api/v1/namespaces/" + ns + "/pods/" + podName + "/exec"
                    + "?container=" + containerName
                    + "&stdin=true&stdout=true&stderr=true&tty=true"
                    + "&command=sh"
                    + "&command=-c"
                    + "&command=export%20LANG%3DC.UTF-8%20%7C%7C%20export%20LANG%3Den_US.UTF-8%3B%20if%20%5B%20-x%20%2Fbin%2Fbash%20%5D%20%7C%7C%20which%20bash%20%3E%2Fdev%2Fnull%202%3E%261%3B%20then%20exec%20bash%3B%20else%20exec%20sh%3B%20fi";

            URI uri = new URI(fullPath);

            Map<String, String> headers = new HashMap<>();
            if (ctx.token() != null && !ctx.token().isEmpty()) {
                headers.put("Authorization", "Bearer " + ctx.token());
            }
            headers.put("Sec-WebSocket-Protocol", "v4.channel.k8s.io");

            org.java_websocket.client.WebSocketClient client = new org.java_websocket.client.WebSocketClient(uri, headers) {
                @Override
                public void onOpen(org.java_websocket.handshake.ServerHandshake handshakedata) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("连接成功 (容器: " + containerName + ")");
                        JComponent pref = terminalWidget.getPreferredFocusableComponent();
                        if (pref != null) {
                            pref.requestFocusInWindow();
                            pref.requestFocus();
                        } else {
                            terminalWidget.requestFocusInWindow();
                        }
                    });
                }

                @Override
                public void onMessage(String message) {
                    if (message != null) {
                        readQueue.offer(message);
                    }
                }

                @Override
                public void onMessage(java.nio.ByteBuffer bytes) {
                    if (bytes.remaining() > 0) {
                        byte channel = bytes.get();
                        if (channel == 1 || channel == 2) {
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            readQueue.offer(new String(data, StandardCharsets.UTF_8));
                        } else if (channel == 3) {
                            byte[] data = new byte[bytes.remaining()];
                            bytes.get(data);
                            String err = new String(data, StandardCharsets.UTF_8);
                            if (!err.trim().startsWith("{")) {
                                readQueue.offer("\n[K8s 错误]: " + err + "\n");
                            }
                        }
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("连接已断开 (" + reason + ")");
                        readQueue.offer("\n=== 连接已断开 ===\n");
                        javax.swing.Timer timer = new javax.swing.Timer(1500, e -> dialog.dispose());
                        timer.setRepeats(false);
                        timer.start();
                    });
                }

                @Override
                public void onError(Exception ex) {
                    SwingUtilities.invokeLater(() ->
                            readQueue.offer("\n[连接异常]: " + ex.getMessage() + "\n"));
                }
            };

            clientHolder[0] = client;

            applyTls(client, ctx);

            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    connector.close();
                    terminalWidget.stop();
                }
            });

            client.connect();

        } catch (Exception ex) {
            UIUtils.error(parent, "建立控制台连接失败: " + ex.getMessage());
            dialog.dispose();
            return;
        }

        dialog.setVisible(true);
    }

    /** 把 https/http 转为 exec WebSocket 使用的 wss/ws 地址。 */
    static String toWebSocketUrl(String serverUrl) {
        if (serverUrl.startsWith("https://")) {
            return "wss://" + serverUrl.substring(8);
        }
        if (serverUrl.startsWith("http://")) {
            return "ws://" + serverUrl.substring(7);
        }
        return serverUrl;
    }

    private static void applyTls(org.java_websocket.client.WebSocketClient client, K8sClusterContext ctx) {
        if (!ctx.serverUrl().startsWith("https://")) {
            return;
        }
        if (ctx.socketFactory() != null) {
            client.setSocketFactory(ctx.socketFactory());
        }
    }
}
