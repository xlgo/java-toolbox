package com.aqishi.toolbox.feature.cloud.ui;

import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.Json;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.ui.kit.ActionBar;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.fasterxml.jackson.databind.JsonNode;

import javax.net.ssl.HttpsURLConnection;
import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Pod 日志查看窗口：静态日志分页加载，可选实时追踪（Follow）。
 *
 * <p>从 {@code K8sManagerPanel} 拆出；集群连接参数经 {@link K8sClusterContext} 读取。</p>
 */
final class K8sLogViewer {

    private K8sLogViewer() {
    }

    /** 拉取目标 Pod 的容器列表后打开日志窗口。 */
    static void open(Component parent, K8sClusterContext ctx, String ns, String podName) {
        new SwingWorker<List<String>, Void>() {
            @Override
            protected List<String> doInBackground() throws Exception {
                String resp = ctx.request("GET",
                        "/api/v1/namespaces/" + ns + "/pods/" + podName, null);
                JsonNode root = Json.mapper().readTree(resp);
                List<String> list = new ArrayList<>();
                JsonNode specs = root.path("spec").path("containers");
                if (specs.isArray()) {
                    for (JsonNode c : specs) {
                        list.add(c.path("name").asText());
                    }
                }
                return list;
            }

            @Override
            protected void done() {
                try {
                    List<String> containers = get();
                    if (containers.isEmpty()) {
                        UIUtils.error(parent, "找不到容器配置！");
                        return;
                    }
                    show(parent, ctx, ns, podName, containers);
                } catch (Exception ex) {
                    UIUtils.error(parent, "加载 Pod 详情失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private static void show(Component parent, K8sClusterContext ctx,
                             String ns, String podName, List<String> containers) {
        JDialog dialog = new JDialog((Frame) null, "Pod 日志: " + podName, false);
        dialog.setSize(800, 550);
        dialog.setLocationRelativeTo(null);

        // 容器选择、追踪开关与加载动作是同一条工具栏，窄窗口下按钮不换行、下拉不被拉宽
        ActionBar top = new ActionBar();
        JComboBox<String> containerCombo = Fields.combo(new String[0], 200);
        for (String c : containers) {
            containerCombo.addItem(c);
        }
        top.left(Fields.label("选择容器 (Container)"));
        top.left(containerCombo);

        JCheckBox followCheck = Fields.check("追踪更新 (Follow)", false);
        top.left(followCheck);

        JButton loadMoreBtn = Buttons.secondary("加载前500行");
        loadMoreBtn.setEnabled(false);
        top.left(loadMoreBtn);

        JTextArea area = new JTextArea();
        area.setEditable(false);
        JScrollPane sp = UIUtils.scrollText(area, "日志输出");

        final int[] currentTailLines = {1000};
        final HttpURLConnection[] activeConn = new HttpURLConnection[1];
        final Thread[] activeThread = new Thread[1];

        JScrollBar verticalBar = sp.getVerticalScrollBar();
        verticalBar.addAdjustmentListener(e -> {
            boolean atTop = (verticalBar.getValue() == 0 && area.getDocument().getLength() > 0);
            loadMoreBtn.setEnabled(atTop && !followCheck.isSelected());
        });

        Runnable stopFollowing = () -> {
            final Thread t = activeThread[0];
            final HttpURLConnection conn = activeConn[0];
            activeThread[0] = null;
            activeConn[0] = null;

            if (t != null || conn != null) {
                new Thread(() -> {
                    if (t != null) {
                        t.interrupt();
                    }
                    if (conn != null) {
                        try {
                            conn.disconnect();
                        } catch (Exception ex) {
                            Errors.ignored("断开 K8s 日志追踪连接失败，连接已废弃", ex);
                        }
                    }
                }).start();
            }
        };

        Runnable startFollowing = () -> {
            String c = (String) containerCombo.getSelectedItem();
            if (c == null) return;
            area.setText("正在开启追踪日志...\n");
            Thread t = new Thread(() -> {
                HttpURLConnection conn = null;
                try {
                    String path = "/api/v1/namespaces/" + ns + "/pods/" + podName + "/log?container=" + c + "&follow=true&tailLines=200";
                    URL url = new URL(ctx.serverUrl().replaceAll("/+$", "") + path);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(0); // Infinite read timeout
                    conn.setRequestMethod("GET");
                    if (ctx.token() != null && !ctx.token().trim().isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + ctx.token());
                    }
                    conn.setRequestProperty("Accept", "application/json");

                    if (conn instanceof HttpsURLConnection) {
                        applyTls((HttpsURLConnection) conn, ctx);
                    }

                    activeConn[0] = conn;
                    int code = conn.getResponseCode();
                    if (code >= 200 && code < 300) {
                        SwingUtilities.invokeLater(() -> area.setText(""));
                        try (InputStream is = conn.getInputStream();
                             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                                final String finalLine = line;
                                SwingUtilities.invokeLater(() -> {
                                    area.append(finalLine + "\n");
                                    area.setCaretPosition(area.getDocument().getLength());
                                });
                            }
                        }
                    } else {
                        try (InputStream es = conn.getErrorStream();
                             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                            String err = "";
                            if (es != null) {
                                byte[] buf = new byte[4096];
                                int len;
                                while ((len = es.read(buf)) != -1) {
                                    bos.write(buf, 0, len);
                                }
                                err = bos.toString("UTF-8");
                            }
                            final String errMsg = "HTTP " + code + (err.isEmpty() ? "" : ": " + err);
                            SwingUtilities.invokeLater(() -> area.setText("无法追踪日志: " + errMsg));
                        }
                    }
                } catch (Exception ex) {
                    if (!Thread.currentThread().isInterrupted()) {
                        SwingUtilities.invokeLater(() -> area.append("\n[追踪日志断开]: " + ex.getMessage() + "\n"));
                    }
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            });
            activeThread[0] = t;
            t.setDaemon(true);
            t.start();
        };

        Runnable logFetcher = () -> {
            String c = (String) containerCombo.getSelectedItem();
            if (c == null) return;

            stopFollowing.run();
            if (followCheck.isSelected()) {
                startFollowing.run();
            } else {
                area.setText("正在加载日志，请稍候...");
                new SwingWorker<String, Void>() {
                    @Override
                    protected String doInBackground() throws Exception {
                        String path = "/api/v1/namespaces/" + ns + "/pods/" + podName + "/log?container=" + c + "&tailLines=" + currentTailLines[0];
                        return ctx.request("GET", path, null);
                    }

                    @Override
                    protected void done() {
                        try {
                            area.setText(get());
                        } catch (Exception ex) {
                            area.setText("加载日志失败: " + ex.getMessage());
                        }
                    }
                }.execute();
            }
        };

        loadMoreBtn.addActionListener(e -> {
            String c = (String) containerCombo.getSelectedItem();
            if (c == null) return;

            loadMoreBtn.setEnabled(false);
            currentTailLines[0] += 500;
            area.insert("正在加载历史日志...\n", 0);

            new SwingWorker<String, Void>() {
                @Override
                protected String doInBackground() throws Exception {
                    String path = "/api/v1/namespaces/" + ns + "/pods/" + podName + "/log?container=" + c + "&tailLines=" + currentTailLines[0];
                    return ctx.request("GET", path, null);
                }

                @Override
                protected void done() {
                    try {
                        String logs = get();
                        int oldLineCount = area.getLineCount();
                        area.setText(logs);
                        int newLineCount = area.getLineCount();
                        int addedLines = newLineCount - oldLineCount;
                        if (addedLines > 0) {
                            try {
                                int offset = area.getLineStartOffset(addedLines);
                                area.setCaretPosition(offset);
                            } catch (Exception ignored) {
                                Errors.ignored("滚动日志视图到新增行失败，不影响日志内容", ignored);
                            }
                        }
                    } catch (Exception ex) {
                        UIUtils.error(dialog, "加载更多日志失败: " + ex.getMessage());
                    }
                }
            }.execute();
        });

        containerCombo.addActionListener(e -> {
            currentTailLines[0] = 1000;
            logFetcher.run();
        });
        followCheck.addActionListener(e -> {
            currentTailLines[0] = 1000;
            logFetcher.run();
        });

        JButton refreshBtn = Buttons.secondary("刷新日志");
        refreshBtn.addActionListener(e -> {
            currentTailLines[0] = 1000;
            logFetcher.run();
        });
        top.left(refreshBtn);

        JButton copyBtn = Buttons.secondary("复制日志");
        copyBtn.addActionListener(e -> {
            UIUtils.copyToClipboard(area.getText());
            UIUtils.info(dialog, "日志已复制！");
        });
        JButton closeBtn = Buttons.ghost("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        ActionBar bottom = new ActionBar();
        bottom.right(copyBtn);
        bottom.right(closeBtn);

        // 日志区放 CENTER 吃掉全部剩余高度，工具栏与动作行只占各自首选高度
        JPanel content = Layouts.page();
        content.add(top, BorderLayout.NORTH);
        content.add(sp, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);

        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                stopFollowing.run();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                stopFollowing.run();
            }
        });

        // Fetch logs initially
        logFetcher.run();

        dialog.setVisible(true);
    }

    /** 把当前集群的 TLS 材料应用到日志追踪连接。 */
    private static void applyTls(HttpsURLConnection connection, K8sClusterContext ctx) {
        if (ctx.socketFactory() != null) {
            connection.setSSLSocketFactory(ctx.socketFactory());
        }
        if (ctx.hostnameVerifier() != null) {
            connection.setHostnameVerifier(ctx.hostnameVerifier());
        }
    }
}
