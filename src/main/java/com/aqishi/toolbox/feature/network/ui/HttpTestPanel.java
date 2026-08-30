package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.codec.domain.JsonFormatter;
import com.aqishi.toolbox.feature.network.ssh.domain.RemoteEndpoint;
import com.aqishi.toolbox.feature.network.ssh.infra.SshConfigStore;
import com.aqishi.toolbox.feature.network.ssh.domain.SshConnectionConfig;
import com.aqishi.toolbox.feature.network.ssh.infra.SshTunnelBridge;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
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
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 HTTP 接口测试面板。
 * 支持 GET, POST, PUT, DELETE 请求，支持自定义请求头和请求体，采用 SwingWorker 异步执行网络请求。
 */
public class HttpTestPanel extends ToolPanel implements ManagedResourceOwner {

    private JComboBox<String> methodBox;
    private JTextField urlField;
    private JButton sendBtn;
    private JButton browseBtn;
    private JCheckBox useSshCheck;
    private JComboBox<SshConnectionConfig> sshCombo;

    private JTextArea reqHeadersArea;
    private JTextArea reqBodyArea;

    private JLabel statusLabel;
    private JTextArea respBodyArea;
    private JTextArea respHeadersArea;

    private JButton copyRespBtn;
    private volatile SshTunnelBridge.BridgeResult activeSshBridge;

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

        JButton importCurlBtn = Buttons.secondary("导入 cURL");
        importCurlBtn.addActionListener(e -> importCurl());

        JButton exportCurlBtn = Buttons.secondary("复制 cURL");
        exportCurlBtn.addActionListener(e -> exportCurl());

        browseBtn = Buttons.secondary("在浏览器中打开");
        browseBtn.addActionListener(e -> openInBrowser());

        // 方法下拉定宽靠左，URL 放 CENTER 才能随窗口一起拉伸
        JPanel urlRow = Layouts.box(Tokens.SPACE_SM, 0);
        urlRow.add(methodBox, BorderLayout.WEST);
        urlRow.add(urlField, BorderLayout.CENTER);

        useSshCheck = Fields.check("启用 SSH 隧道", false);
        List<SshConnectionConfig> sshList = SshConfigStore.getInstance().getAll();
        sshCombo = Fields.combo(sshList.toArray(new SshConnectionConfig[0]));
        sshCombo.setEnabled(false);
        useSshCheck.addActionListener(e -> {
            sshCombo.setEnabled(useSshCheck.isSelected());
            if (!useSshCheck.isSelected()) releaseSshBridge();
        });
        SshConfigStore.getInstance().addChangeListener(this::refreshSshConfigs);
        JPanel sshRow = Layouts.box(Tokens.SPACE_MD, 0);
        sshRow.add(useSshCheck, BorderLayout.WEST);
        sshRow.add(sshCombo, BorderLayout.CENTER);

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
        reqBody.add(sshRow, BorderLayout.SOUTH);

        Card requestCard = Card.titled("请求配置");
        requestCard.setContent(reqBody);
        requestCard.addHeaderAction(importCurlBtn);
        requestCard.addHeaderAction(exportCurlBtn);
        requestCard.addHeaderAction(browseBtn);
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
        final boolean sshEnabled = useSshCheck != null && useSshCheck.isSelected();
        final String sshConfigId = sshEnabled && sshCombo.getSelectedItem() != null
                ? ((SshConnectionConfig) sshCombo.getSelectedItem()).getId() : null;
        releaseSshBridge();

        // 采用 SwingWorker 异步发起请求，防 GUI 卡死
        new SwingWorker<ResponseData, Void>() {
            @Override
            protected ResponseData doInBackground() throws Exception {
                ResponseData resp = new ResponseData();
                long start = System.currentTimeMillis();
                HttpURLConnection conn = null;
                SshTunnelBridge.BridgeResult requestBridge = null;
                try {
                    URL url = new URL(urlStr);
                    URL requestUrl = url;
                    if (sshEnabled) {
                        requestBridge = bridgeForUrl(url, sshConfigId);
                        requestUrl = localUrl(url, requestBridge);
                    }
                    conn = (HttpURLConnection) requestUrl.openConnection();
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
                    if (requestBridge != null) requestBridge.close();
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
                        if ((resp.headers != null && resp.headers.toLowerCase().contains("application/json")) ||
                            (rawBody.startsWith("{") && rawBody.endsWith("}")) ||
                            (rawBody.startsWith("[") && rawBody.endsWith("]"))) {
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                                mapper.enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
                                Object json = mapper.readValue(rawBody, Object.class);
                                respBodyArea.setText(mapper.writeValueAsString(json));
                            } catch (Exception e) {
                                try {
                                    respBodyArea.setText(JsonFormatter.pretty(rawBody));
                                } catch (Exception ex) {
                                    respBodyArea.setText(rawBody);
                                }
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

    private void openInBrowser() {
        String urlStr = urlField.getText().trim();
        if (urlStr.isEmpty()) {
            UIUtils.error(getView(), "请输入有效的请求 URL！");
            return;
        }
        SshTunnelBridge.BridgeResult bridge = null;
        try {
            URL url = new URL(urlStr);
            URL browserUrl = url;
            if (useSshCheck != null && useSshCheck.isSelected()) {
                SshConnectionConfig sshConfig = (SshConnectionConfig) sshCombo.getSelectedItem();
                if (sshConfig == null) throw new IllegalArgumentException("请选择用于隧道的 SSH 服务器配置");
                releaseSshBridge();
                bridge = bridgeForUrl(url, sshConfig.getId());
                browserUrl = localUrl(url, bridge);
                activeSshBridge = bridge;
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(browserUrl.toURI());
                statusLabel.setText("已在浏览器中打开: " + browserUrl);
            } else {
                statusLabel.setText("当前环境不支持自动打开浏览器: " + browserUrl);
            }
        } catch (Exception error) {
            if (bridge != null) bridge.close();
            UIUtils.error(getView(), "打开浏览器失败:\n" + error.getMessage());
        }
    }

    private SshTunnelBridge.BridgeResult bridgeForUrl(URL url, String sshConfigId) throws Exception {
        if (sshConfigId == null || sshConfigId.trim().isEmpty()) {
            throw new IllegalArgumentException("请选择用于隧道的 SSH 服务器配置");
        }
        String host = url.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("URL 中缺少远程主机地址");
        }
        int port = url.getPort();
        if (port < 0) port = "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
        RemoteEndpoint endpoint = new RemoteEndpoint(host, port);
        return SshTunnelBridge.bridge(sshConfigId, endpoint.getHost(), endpoint.getPort());
    }

    private static URL localUrl(URL original, SshTunnelBridge.BridgeResult bridge) throws Exception {
        if (bridge == null || bridge.getLocalPort() <= 0) {
            throw new IllegalStateException("SSH 隧道未返回有效的本地端口");
        }
        URI local = new URI(original.getProtocol(), null, bridge.getLocalHost(), bridge.getLocalPort(),
                original.getPath() == null || original.getPath().isEmpty() ? "/" : original.getPath(),
                original.getQuery(), original.getRef());
        return local.toURL();
    }

    @Override
    public void closeResources() {
        releaseSshBridge();
    }

    private void releaseSshBridge() {
        SshTunnelBridge.BridgeResult bridge = activeSshBridge;
        activeSshBridge = null;
        if (bridge != null) bridge.close();
    }

    private void refreshSshConfigs() {
        Runnable refresh = () -> {
            if (sshCombo == null) return;
            String selectedId = null;
            SshConnectionConfig selected = (SshConnectionConfig) sshCombo.getSelectedItem();
            if (selected != null) selectedId = selected.getId();
            sshCombo.removeAllItems();
            for (SshConnectionConfig config : SshConfigStore.getInstance().getAll()) sshCombo.addItem(config);
            if (selectedId != null) {
                for (int i = 0; i < sshCombo.getItemCount(); i++) {
                    if (selectedId.equals(sshCombo.getItemAt(i).getId())) {
                        sshCombo.setSelectedIndex(i);
                        break;
                    }
                }
            }
            sshCombo.setEnabled(useSshCheck != null && useSshCheck.isSelected());
        };
        if (SwingUtilities.isEventDispatchThread()) refresh.run();
        else SwingUtilities.invokeLater(refresh);
    }

    private void exportCurl() {
        String method = (String) methodBox.getSelectedItem();
        String url = urlField.getText().trim();
        String headersText = reqHeadersArea.getText();
        String bodyText = reqBodyArea.getText();

        StringBuilder sb = new StringBuilder("curl -X ").append(method).append(" \"").append(url).append("\"");

        for (String line : headersText.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) {
                sb.append(" \\\n  -H \"").append(line.replace("\"", "\\\"")).append("\"");
            }
        }

        if (("POST".equals(method) || "PUT".equals(method)) && bodyText != null && !bodyText.trim().isEmpty()) {
            sb.append(" \\\n  --data-raw '").append(bodyText.replace("'", "'\\''")).append("'");
        }

        UIUtils.copyToClipboard(sb.toString());
        UIUtils.info(getView(), "cURL 命令已成功复制到剪贴板！");
    }

    private void importCurl() {
        JTextArea textArea = new JTextArea(10, 50);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane scrollPane = new JScrollPane(textArea);
        int result = JOptionPane.showConfirmDialog(getView(), scrollPane, "请粘贴 cURL 命令字符串", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            String curlCmd = textArea.getText();
            if (curlCmd == null || curlCmd.trim().isEmpty()) return;

            try {
                parseAndApplyCurl(curlCmd);
                UIUtils.info(getView(), "cURL 导入解析成功！");
            } catch (Exception ex) {
                UIUtils.error(getView(), "cURL 解析失败: " + ex.getMessage());
            }
        }
    }

    private void parseAndApplyCurl(String curlStr) {
        // 去除换行符与反斜杠
        String singleLine = curlStr.replaceAll("\\\\\\r?\\n", " ").replaceAll("\\r?\\n", " ").trim();

        String method = "GET";
        String url = "";
        StringBuilder headersSb = new StringBuilder();
        String body = "";

        // 匹配 -X 或 --request
        java.util.regex.Matcher mMethod = java.util.regex.Pattern.compile("(?:-X|--request)\\s+([A-Z]+)").matcher(singleLine);
        if (mMethod.find()) {
            method = mMethod.group(1);
        }

        // 匹配 -H 或 --header
        java.util.regex.Matcher mHeader = java.util.regex.Pattern.compile("(?:-H|--header)\\s+[\"']([^\"']+)[\"']").matcher(singleLine);
        while (mHeader.find()) {
            if (headersSb.length() > 0) headersSb.append("\n");
            headersSb.append(mHeader.group(1));
        }

        // 匹配 -d, --data, --data-raw
        java.util.regex.Matcher mBody = java.util.regex.Pattern.compile("(?:-d|--data|--data-raw|--data-binary)\\s+['\"](.*?)['\"](?=\\s+-|$)").matcher(singleLine);
        if (mBody.find()) {
            body = mBody.group(1);
            if ("GET".equals(method)) method = "POST";
        }

        // 匹配 URL
        java.util.regex.Matcher mUrl = java.util.regex.Pattern.compile("(https?://[^\\s'\"]+)").matcher(singleLine);
        if (mUrl.find()) {
            url = mUrl.group(1);
        }

        if (url.isEmpty()) {
            throw new IllegalArgumentException("未能在 cURL 命令中解析到有效 URL");
        }

        // 应用到 UI
        methodBox.setSelectedItem(method);
        urlField.setText(url);
        if (headersSb.length() > 0) {
            reqHeadersArea.setText(headersSb.toString());
        }
        if (!body.isEmpty()) {
            reqBodyArea.setText(body);
            reqBodyArea.setEnabled(true);
        }
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
