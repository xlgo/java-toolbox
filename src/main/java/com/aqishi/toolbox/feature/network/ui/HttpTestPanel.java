package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.network.domain.HttpWorkspace;
import com.aqishi.toolbox.feature.network.application.OpenApiRequestExecutor;
import com.aqishi.toolbox.infra.secrets.SecretStore;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.feature.network.domain.CurlCommand;
import com.aqishi.toolbox.util.JsonFormatter;
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
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.Json;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.util.FormatUtils;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 HTTP 接口测试面板。
 * 支持常见 HTTP 方法、请求集合与环境变量，网络请求通过共享执行器异步执行。
 */
public class HttpTestPanel extends ToolPanel implements ManagedResourceOwner {

    private JComboBox<String> methodBox;
    private JTextField urlField;
    private JButton sendBtn;
    private JButton cancelBtn;
    private SwingWorker<ResponseData, Void> requestWorker;
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

    private final SecretStore workspaceSecrets;
    private HttpWorkspaceBar workspace;
    private String requestName = "";
    private boolean requestFavorite;
    private final Runnable sshConfigListener = this::refreshSshConfigs;
    private final SecretStore.Listener workspaceLockListener = state -> {
        if (state != SecretStore.Status.UNLOCKED) SwingUtilities.invokeLater(() -> {
            if (workspace == null) return;
            if (requestWorker != null) requestWorker.cancel(true);
            requestName = ""; requestFavorite = false;
            urlField.setText(""); reqHeadersArea.setText(""); reqBodyArea.setText("");
            respBodyArea.setText(""); respHeadersArea.setText(""); copyRespBtn.setEnabled(false);
        });
    };

    public HttpTestPanel() { this(SecretStore.disabled()); }
    public HttpTestPanel(SecretStore secrets) {
        super(ToolCatalog.HTTP_CLIENT);
        this.workspaceSecrets = secrets;
    }

    private HttpWorkspace.Request captureRequest() {
        return new HttpWorkspace.Request(requestName, (String) methodBox.getSelectedItem(), urlField.getText(),
                reqHeadersArea.getText(), reqBodyArea.getText(), requestFavorite);
    }
    private void applyRequest(HttpWorkspace.Request r) {
        requestName = r.name(); requestFavorite = r.favorite();
        methodBox.setSelectedItem(r.method()); urlField.setText(r.url());
        reqHeadersArea.setText(r.headers()); reqBodyArea.setText(r.body());
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 顶部：请求配置卡片（方法 / URL / Header / Body 是一次请求的完整描述） =====
        methodBox = Fields.combo(new String[]{"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"}, 96);
        urlField = Fields.mono("https://httpbin.org/get");

        sendBtn = Buttons.primary(I18n.get("ui.http.send"));
        sendBtn.setToolTipText(I18n.get("ui.http.sendHint"));
        sendBtn.addActionListener(e -> sendRequest());
        cancelBtn = Buttons.secondary(I18n.get("tool.openapi.btn.cancel"));
        cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> { if (requestWorker != null) requestWorker.cancel(true); });

        JMenuItem importCurlBtn = new JMenuItem(I18n.get("ui.http.importCurl"));
        importCurlBtn.addActionListener(e -> importCurl());

        JMenuItem exportCurlBtn = new JMenuItem(I18n.get("ui.http.exportCurl"));
        exportCurlBtn.addActionListener(e -> exportCurl());

        browseBtn = Buttons.secondary(I18n.get("ui.http.browser"));
        browseBtn.addActionListener(e -> openInBrowser());
        JPopupMenu requestMenu = new JPopupMenu();
        requestMenu.add(importCurlBtn); requestMenu.add(exportCurlBtn); requestMenu.addSeparator();
        JMenuItem browserItem = new JMenuItem(browseBtn.getText());
        browserItem.addActionListener(e -> browseBtn.doClick()); requestMenu.add(browserItem);
        JButton more = Buttons.snug(I18n.get("ui.actions.more"));
        more.addActionListener(e -> requestMenu.show(more, 0, more.getHeight()));

        // 方法下拉定宽靠左，URL 放 CENTER 才能随窗口一起拉伸
        JPanel urlRow = Layouts.box(Tokens.SPACE_SM, 0);
        urlRow.add(methodBox, BorderLayout.WEST);
        urlRow.add(urlField, BorderLayout.CENTER);

        useSshCheck = Fields.check(I18n.get("ui.http.ssh"), false);
        List<SshConnectionConfig> sshList = SshConfigStore.getInstance().getAll();
        sshCombo = Fields.combo(sshList.toArray(new SshConnectionConfig[0]));
        sshCombo.setEnabled(false);
        useSshCheck.addActionListener(e -> {
            sshCombo.setEnabled(useSshCheck.isSelected());
            if (!useSshCheck.isSelected()) releaseSshBridge();
        });
        SshConfigStore.getInstance().addChangeListener(sshConfigListener);
        JPanel sshRow = Layouts.box(Tokens.SPACE_MD, 0);
        sshRow.add(useSshCheck, BorderLayout.WEST);
        sshRow.add(sshCombo, BorderLayout.CENTER);

        JTabbedPane reqTabs = new JTabbedPane();
        reqTabs.setBorder(null);
        reqHeadersArea = Fields.area(3, 40);
        reqHeadersArea.setText("Content-Type: application/json\nUser-Agent: JavaToolbox/1.2\nAccept: */*");
        reqTabs.addTab(I18n.get("ui.http.requestHeaders"), Fields.scroll(reqHeadersArea));

        reqBodyArea = Fields.area(3, 40);
        reqBodyArea.setText("{\n  \"name\": \"toolbox\",\n  \"value\": \"hello\"\n}");
        reqBodyArea.setEnabled(false); // 默认GET，禁用请求体
        reqTabs.addTab(I18n.get("ui.http.requestBody"), Fields.scroll(reqBodyArea));

        JPanel reqBody = Layouts.box(0, Tokens.SPACE_MD);
        reqBody.add(urlRow, BorderLayout.NORTH);
        reqBody.add(reqTabs, BorderLayout.CENTER);
        reqBody.add(sshRow, BorderLayout.SOUTH);

        Card requestCard = Card.titled(I18n.get("ui.http.request"));
        requestCard.setContent(reqBody);
        requestCard.addHeaderAction(more);
        requestCard.addHeaderAction(cancelBtn);
        requestCard.addHeaderAction(sendBtn);

        methodBox.addActionListener(e -> {
            String method = (String) methodBox.getSelectedItem();
            reqBodyArea.setEnabled(!"GET".equals(method) && !"HEAD".equals(method));
        });
        workspace = new HttpWorkspaceBar(workspaceSecrets, this::captureRequest, this::applyRequest);
        JPanel requestTop = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        requestTop.setOpaque(false); requestTop.add(urlRow, BorderLayout.NORTH); requestTop.add(workspace, BorderLayout.CENTER);
        reqBody.add(requestTop, BorderLayout.NORTH);

        // ===== 响应卡片：放 CENTER 吸收剩余高度，状态行贴在结果上方 =====
        statusLabel = new JLabel(I18n.get("ui.http.ready"));
        statusLabel.setFont(Tokens.fontBody());

        copyRespBtn = Buttons.ghost(I18n.get("ui.http.copyResponse"));
        copyRespBtn.setEnabled(false);
        copyRespBtn.addActionListener(e -> UIUtils.copyToClipboard(respBodyArea.getText()));

        JTabbedPane respTabs = new JTabbedPane();
        respTabs.setBorder(null);
        respBodyArea = Fields.output(10, 40);
        respTabs.addTab(I18n.get("ui.http.responseBody"), Fields.scroll(respBodyArea));

        respHeadersArea = Fields.output(10, 40);
        respTabs.addTab(I18n.get("ui.http.responseHeaders"), Fields.scroll(respHeadersArea));

        JPanel respBody = Layouts.box(0, Tokens.SPACE_SM);
        respBody.add(statusLabel, BorderLayout.NORTH);
        respBody.add(respTabs, BorderLayout.CENTER);

        Card responseCard = Card.titled(I18n.get("ui.http.response"));
        responseCard.setContent(respBody);
        responseCard.addHeaderAction(copyRespBtn);

        JScrollPane requestScroll = Fields.scrollVertical(requestCard);
        requestScroll.setMinimumSize(new Dimension(0, 210));
        responseCard.setMinimumSize(new Dimension(0, 160));
        root.add(Layouts.splitVertical(requestScroll, responseCard, 0.55, 0.60), BorderLayout.CENTER);
        root.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ctrl ENTER"), "sendRequest");
        root.getActionMap().put("sendRequest", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                if (sendBtn.isEnabled()) sendBtn.doClick();
            }
        });
        workspaceSecrets.addListener(workspaceLockListener);
        return root;
    }

    private void sendRequest() {
        HttpWorkspace.Request template = captureRequest();
        final HttpWorkspace.Request resolved;
        try { resolved = workspace.resolve(template, false); }
        catch (Exception error) { UIUtils.error(getView(), Errors.describeRoot(error)); return; }
        String urlStr = resolved.url().trim();
        if (urlStr.isEmpty()) {
            UIUtils.error(getView(), "请输入有效的请求 URL！");
            return;
        }
        final java.util.function.UnaryOperator<String> redactError;
        try { redactError = workspace.errorRedactor(); }
        catch (Exception error) { UIUtils.error(getView(), Errors.describeRoot(error)); return; }

        sendBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        methodBox.setEnabled(false);
        copyRespBtn.setEnabled(false);
        statusLabel.setText("请求中，请稍候...");
        respBodyArea.setText("");
        respHeadersArea.setText("");

        String method = (String) methodBox.getSelectedItem();
        String headersText = resolved.headers();
        String bodyText = resolved.body();
        workspace.remember(template);
        final boolean sshEnabled = useSshCheck != null && useSshCheck.isSelected();
        final String sshConfigId = sshEnabled && sshCombo.getSelectedItem() != null
                ? ((SshConnectionConfig) sshCombo.getSelectedItem()).getId() : null;
        releaseSshBridge();

        // 采用 SwingWorker 异步发起请求，防 GUI 卡死
        requestWorker = new SwingWorker<ResponseData, Void>() {
            @Override
            protected ResponseData doInBackground() throws Exception {
                ResponseData resp = new ResponseData();
                long start = System.currentTimeMillis();
                SshTunnelBridge.BridgeResult requestBridge = null;
                try {
                    URL url = new URL(urlStr);
                    URL requestUrl = url;
                    if (sshEnabled) {
                        requestBridge = bridgeForUrl(url, sshConfigId);
                        requestUrl = localUrl(url, requestBridge);
                    }
                    Map<String, String> headers = new java.util.LinkedHashMap<>();
                    for (String line : headersText.split("\n")) {
                        int colon = line.indexOf(':');
                        if (colon > 0) headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                        else if (!line.isBlank()) throw new IllegalArgumentException(I18n.get("http.workspace.headerLine"));
                    }
                    var response = new OpenApiRequestExecutor().execute(requestUrl.toString(), method, headers, bodyText);
                    resp.code = response.status(); resp.message = "";
                    StringBuilder responseHeaders = new StringBuilder();
                    response.headers().forEach((key, values) -> responseHeaders.append(key).append(": ").append(String.join(", ", values)).append("\n"));
                    resp.headers = responseHeaders.toString(); resp.body = response.body();
                    resp.sizeBytes = resp.body.getBytes(StandardCharsets.UTF_8).length;
                } catch (Exception ex) {
                    resp.error = redactError.apply(Errors.describeRoot(ex));
                } finally {
                    if (requestBridge != null) requestBridge.close();
                    resp.timeMs = System.currentTimeMillis() - start;
                }
                return resp;
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) { statusLabel.setText(I18n.get("tool.openapi.status.cancelled")); return; }
                    ResponseData resp = get();
                    if (resp.error != null) {
                        statusLabel.setText("请求失败");
                        respBodyArea.setText("错误信息: " + resp.error);
                    } else {
                        String statusStr = String.format("Status: %d %s  |  Time: %d ms  |  Size: %s",
                                resp.code, resp.message, resp.timeMs, FormatUtils.bytes(resp.sizeBytes));
                        statusLabel.setText(statusStr);
                        respHeadersArea.setText(resp.headers);

                        // 如果响应是 JSON，则自动美化
                        String rawBody = resp.body.trim();
                        if ((resp.headers != null && resp.headers.toLowerCase().contains("application/json")) ||
                            (rawBody.startsWith("{") && rawBody.endsWith("}")) ||
                            (rawBody.startsWith("[") && rawBody.endsWith("]"))) {
                            try {
                                com.fasterxml.jackson.databind.ObjectMapper mapper = Json.prettyMapper();
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
                    cancelBtn.setEnabled(false);
                    requestWorker = null;
                    methodBox.setEnabled(true);
                }
            }
        };
        requestWorker.execute();
    }

    private void openInBrowser() {
        final String urlStr;
        try { urlStr = workspace.resolve(captureRequest(), false).url().trim(); }
        catch (Exception error) { UIUtils.error(getView(), Errors.describeRoot(error)); return; }
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
        workspaceSecrets.removeListener(workspaceLockListener);
        SwingWorker<ResponseData, Void> worker = requestWorker;
        if (worker != null) worker.cancel(true);
        SshConfigStore.getInstance().removeChangeListener(sshConfigListener);
        if (workspace != null) {
            if (SwingUtilities.isEventDispatchThread()) workspace.close();
            else SwingUtilities.invokeLater(workspace::close);
        }
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

        java.util.List<String> headers = new java.util.ArrayList<>();
        for (String line : headersText.split("\n")) {
            if (!line.trim().isEmpty()) {
                headers.add(line.trim());
            }
        }
        boolean hasBody = (!"GET".equals(method) && !"HEAD".equals(method))
                && bodyText != null && !bodyText.trim().isEmpty();
        CurlCommand curl = new CurlCommand(method, url, headers, hasBody ? bodyText : "");

        UIUtils.copyToClipboard(curl.toShell());
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
        CurlCommand curl;
        try {
            curl = CurlCommand.parse(curlStr);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("未能解析 cURL 命令：" + invalid.getMessage(), invalid);
        }
        String method = curl.method();
        String url = curl.url();
        String body = curl.body();
        StringBuilder headersSb = new StringBuilder(String.join("\n", curl.headers()));

        // 应用到 UI
        methodBox.setSelectedItem(method);
        urlField.setText(url);
        reqHeadersArea.setText(headersSb.toString());
        reqBodyArea.setText(body);
        requestName = ""; requestFavorite = false;
        reqBodyArea.setEnabled(!"GET".equals(method) && !"HEAD".equals(method));
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
