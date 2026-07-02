package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 HTTP 接口测试面板。
 * 支持 GET, POST, PUT, DELETE 请求，支持自定义请求头和请求体，采用 SwingWorker 异步执行网络请求。
 */
public class HttpTestPanel extends ToolPanel {

    private JComboBox<String> methodBox;
    private JTextField urlField;
    private JButton sendBtn;

    private JTextArea reqHeadersArea;
    private JTextArea reqBodyArea;

    private JLabel statusLabel;
    private JTextArea respBodyArea;
    private JTextArea respHeadersArea;

    private JButton copyRespBtn;

    public HttpTestPanel() {
        super("dev", "http.client",
                "HTTP", "接口测试", "API", "Request", "Postman", "Curl");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部：请求行 =====
        JPanel topBar = new JPanel(new BorderLayout(8, 0));
        methodBox = new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE"});
        methodBox.setPreferredSize(new Dimension(90, 32));
        
        urlField = new JTextField("https://httpbin.org/get");
        urlField.setFont(UIUtils.monoFont());
        
        sendBtn = UIUtils.button("发送请求", 100);
        sendBtn.addActionListener(e -> sendRequest());

        topBar.add(methodBox, BorderLayout.WEST);
        topBar.add(urlField, BorderLayout.CENTER);
        topBar.add(sendBtn, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        // ===== 中间：请求与响应切分面板 =====
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        mainSplit.setDividerLocation(200);
        mainSplit.setResizeWeight(0.4);

        // 1. 请求配置区
        JTabbedPane reqTabs = new JTabbedPane();
        reqHeadersArea = new JTextArea("Content-Type: application/json\nUser-Agent: JavaToolbox/1.2\nAccept: */*");
        reqHeadersArea.setFont(UIUtils.monoFont());
        reqTabs.addTab("请求头 (Headers)", new JScrollPane(reqHeadersArea));

        reqBodyArea = new JTextArea("{\n  \"name\": \"toolbox\",\n  \"value\": \"hello\"\n}");
        reqBodyArea.setFont(UIUtils.monoFont());
        reqBodyArea.setEnabled(false); // 默认GET，禁用请求体
        reqTabs.addTab("请求体 (Body)", new JScrollPane(reqBodyArea));

        methodBox.addActionListener(e -> {
            String method = (String) methodBox.getSelectedItem();
            boolean hasBody = "POST".equals(method) || "PUT".equals(method);
            reqBodyArea.setEnabled(hasBody);
            if (hasBody) {
                if (urlField.getText().endsWith("/get")) {
                    urlField.setText(urlField.getText().replace("/get", "/post"));
                }
            } else {
                if (urlField.getText().endsWith("/post")) {
                    urlField.setText(urlField.getText().replace("/post", "/get"));
                }
            }
        });

        mainSplit.setTopComponent(reqTabs);

        // 2. 响应配置区
        JPanel respPanel = new JPanel(new BorderLayout(4, 4));
        
        JPanel respHeaderBar = new JPanel(new BorderLayout(8, 0));
        respHeaderBar.setBorder(new EmptyBorder(4, 0, 4, 0));
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(UIUtils.plainFont());
        copyRespBtn = UIUtils.button("复制响应", 90);
        copyRespBtn.setEnabled(false);
        copyRespBtn.addActionListener(e -> UIUtils.copyToClipboard(respBodyArea.getText()));
        
        respHeaderBar.add(statusLabel, BorderLayout.WEST);
        respHeaderBar.add(copyRespBtn, BorderLayout.EAST);
        respPanel.add(respHeaderBar, BorderLayout.NORTH);

        JTabbedPane respTabs = new JTabbedPane();
        respBodyArea = new JTextArea();
        respBodyArea.setEditable(false);
        respBodyArea.setFont(UIUtils.monoFont());
        respTabs.addTab("响应体 (Body)", new JScrollPane(respBodyArea));

        respHeadersArea = new JTextArea();
        respHeadersArea.setEditable(false);
        respHeadersArea.setFont(UIUtils.monoFont());
        respTabs.addTab("响应头 (Headers)", new JScrollPane(respHeadersArea));

        respPanel.add(respTabs, BorderLayout.CENTER);
        mainSplit.setBottomComponent(respPanel);

        root.add(mainSplit, BorderLayout.CENTER);
        return root;
    }

    private void sendRequest() {
        String urlStr = urlField.getText().trim();
        if (urlStr.isEmpty()) {
            UIUtils.error(getView(), "请输入有效的请求 URL！");
            return;
        }

        sendBtn.setEnabled(false);
        methodBox.setEnabled(false);
        copyRespBtn.setEnabled(false);
        statusLabel.setText("请求中，请稍候...");
        respBodyArea.setText("");
        respHeadersArea.setText("");

        String method = (String) methodBox.getSelectedItem();
        String headersText = reqHeadersArea.getText();
        String bodyText = reqBodyArea.getText();

        // 采用 SwingWorker 异步发起请求，防 GUI 卡死
        new SwingWorker<ResponseData, Void>() {
            @Override
            protected ResponseData doInBackground() throws Exception {
                ResponseData resp = new ResponseData();
                long start = System.currentTimeMillis();
                HttpURLConnection conn = null;
                try {
                    URL url = new URL(urlStr);
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(method);
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(15000);
                    conn.setUseCaches(false);

                    // 设置请求头
                    for (String line : headersText.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) {
                            String key = line.substring(0, colon).trim();
                            String val = line.substring(colon + 1).trim();
                            conn.setRequestProperty(key, val);
                        }
                    }

                    // 设置请求体
                    boolean hasBody = "POST".equals(method) || "PUT".equals(method);
                    if (hasBody && bodyText != null && !bodyText.trim().isEmpty()) {
                        conn.setDoOutput(true);
                        try (OutputStream os = conn.getOutputStream()) {
                            byte[] input = bodyText.getBytes(StandardCharsets.UTF_8);
                            os.write(input, 0, input.length);
                        }
                    }

                    // 获取响应数据
                    resp.code = conn.getResponseCode();
                    resp.message = conn.getResponseMessage();

                    // 组装响应头
                    StringBuilder sbHeaders = new StringBuilder();
                    for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                        if (entry.getKey() != null) {
                            sbHeaders.append(entry.getKey()).append(": ")
                                     .append(String.join(", ", entry.getValue())).append("\n");
                        } else {
                            sbHeaders.append(String.join(", ", entry.getValue())).append("\n");
                        }
                    }
                    resp.headers = sbHeaders.toString();

                    // 读取响应体
                    InputStream is = (resp.code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                    if (is != null) {
                        StringBuilder sbBody = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sbBody.append(line).append("\n");
                            }
                        }
                        resp.body = sbBody.toString();
                        resp.sizeBytes = resp.body.getBytes(StandardCharsets.UTF_8).length;
                    } else {
                        resp.body = "";
                    }
                } catch (Exception ex) {
                    resp.error = ex.getMessage();
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                    resp.timeMs = System.currentTimeMillis() - start;
                }
                return resp;
            }

            @Override
            protected void done() {
                try {
                    ResponseData resp = get();
                    if (resp.error != null) {
                        statusLabel.setText("请求失败");
                        respBodyArea.setText("错误信息: " + resp.error);
                    } else {
                        String statusStr = String.format("Status: %d %s  |  Time: %d ms  |  Size: %s",
                                resp.code, resp.message, resp.timeMs, formatSize(resp.sizeBytes));
                        statusLabel.setText(statusStr);
                        respHeadersArea.setText(resp.headers);

                        // 如果响应是 JSON，则自动美化
                        String rawBody = resp.body.trim();
                        if ((rawBody.startsWith("{") && rawBody.endsWith("}")) ||
                            (rawBody.startsWith("[") && rawBody.endsWith("]"))) {
                            try {
                                respBodyArea.setText(JsonFormatter.pretty(rawBody));
                            } catch (Exception e) {
                                respBodyArea.setText(rawBody);
                            }
                        } else {
                            respBodyArea.setText(rawBody);
                        }
                        copyRespBtn.setEnabled(true);
                    }
                } catch (Exception ex) {
                    statusLabel.setText("内部错误");
                    respBodyArea.setText(ex.getMessage());
                } finally {
                    sendBtn.setEnabled(true);
                    methodBox.setEnabled(true);
                }
            }
        }.execute();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
    }

    private static class ResponseData {
        int code;
        String message;
        String body;
        String headers;
        long timeMs;
        long sizeBytes;
        String error;
    }
}
