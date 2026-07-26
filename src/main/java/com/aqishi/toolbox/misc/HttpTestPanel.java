package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.UIUtils;

import javax.swing.*;
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
        JPanel root = Layouts.page();

        // ===== 顶部：请求配置卡片（方法 / URL / Header / Body 是一次请求的完整描述） =====
        methodBox = Fields.combo(new String[]{"GET", "POST", "PUT", "DELETE"}, 96);
        urlField = Fields.mono("https://httpbin.org/get");

        sendBtn = Buttons.primary("发送请求");
        sendBtn.addActionListener(e -> sendRequest());

        // 方法下拉定宽靠左，URL 放 CENTER 才能随窗口一起拉伸
        JPanel urlRow = Layouts.box(Tokens.SPACE_SM, 0);
        urlRow.add(methodBox, BorderLayout.WEST);
        urlRow.add(urlField, BorderLayout.CENTER);

        JTabbedPane reqTabs = new JTabbedPane();
        reqTabs.setBorder(null);
        reqHeadersArea = Fields.area(5, 40);
        reqHeadersArea.setText("Content-Type: application/json\nUser-Agent: JavaToolbox/1.2\nAccept: */*");
        reqTabs.addTab("请求头 (Headers)", Fields.scroll(reqHeadersArea));

        reqBodyArea = Fields.area(5, 40);
        reqBodyArea.setText("{\n  \"name\": \"toolbox\",\n  \"value\": \"hello\"\n}");
        reqBodyArea.setEnabled(false); // 默认GET，禁用请求体
        reqTabs.addTab("请求体 (Body)", Fields.scroll(reqBodyArea));

        JPanel reqBody = Layouts.box(0, Tokens.SPACE_MD);
        reqBody.add(urlRow, BorderLayout.NORTH);
        reqBody.add(reqTabs, BorderLayout.CENTER);

        Card requestCard = Card.titled("请求配置");
        requestCard.setContent(reqBody);
        requestCard.addHeaderAction(sendBtn);

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

        // ===== 响应卡片：放 CENTER 吸收剩余高度，状态行贴在结果上方 =====
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(Tokens.fontBody());

        copyRespBtn = Buttons.ghost("复制响应");
        copyRespBtn.setEnabled(false);
        copyRespBtn.addActionListener(e -> UIUtils.copyToClipboard(respBodyArea.getText()));

        JTabbedPane respTabs = new JTabbedPane();
        respTabs.setBorder(null);
        respBodyArea = Fields.output(10, 40);
        respTabs.addTab("响应体 (Body)", Fields.scroll(respBodyArea));

        respHeadersArea = Fields.output(10, 40);
        respTabs.addTab("响应头 (Headers)", Fields.scroll(respHeadersArea));

        JPanel respBody = Layouts.box(0, Tokens.SPACE_SM);
        respBody.add(statusLabel, BorderLayout.NORTH);
        respBody.add(respTabs, BorderLayout.CENTER);

        Card responseCard = Card.titled("响应");
        responseCard.setContent(respBody);
        responseCard.addHeaderAction(copyRespBtn);

        root.add(requestCard, BorderLayout.NORTH);
        root.add(responseCard, BorderLayout.CENTER);
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
