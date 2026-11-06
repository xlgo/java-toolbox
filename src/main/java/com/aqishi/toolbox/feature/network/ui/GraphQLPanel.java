package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.feature.network.application.OpenApiRequestExecutor;
import com.aqishi.toolbox.feature.network.domain.GraphQLRequest;
import com.aqishi.toolbox.infra.ManagedResourceOwner;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.*;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.FormatUtils;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.Json;
import com.aqishi.toolbox.util.UIUtils;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.awt.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphQL 调试客户端与 Schema 内省工作台。
 *
 * <p>支持原生 GraphQL Query 与 JSON 变量分离编辑、自定义请求头、
 * 一键 Schema 内省解析，以及响应美化与耗时统计。</p>
 */
public final class GraphQLPanel extends ToolPanel implements ManagedResourceOwner {

    private static final String SAMPLE_URL = "https://countries.trevorblades.com/";
    private static final String SAMPLE_QUERY =
            """
            query GetCountry($code: ID!) {
              country(code: $code) {
                name
                native
                capital
                currency
                emoji
                languages {
                  code
                  name
                }
              }
            }
            """;
    private static final String SAMPLE_VARIABLES = "{\n  \"code\": \"CN\"\n}";

    private final JTextField urlField = Fields.text(SAMPLE_URL);
    private final JComboBox<String> methodCombo = Fields.combo(new String[]{"POST", "GET"}, 80);

    private final JTextArea queryArea = Fields.area(10, 40);
    private final JTextArea variablesArea = Fields.area(6, 40);
    private final JTextArea headersArea = Fields.area(6, 40);

    private final JTextArea responseArea = Fields.output(12, 45);
    private final JTextArea respHeadersArea = Fields.output(12, 45);
    private final JTextArea schemaArea = Fields.output(12, 45);

    private final JButton sendBtn = Buttons.primary(I18n.get("graphql.send"));
    private final JButton cancelBtn = Buttons.secondary(I18n.get("devtools.cancel"));
    private final JButton introspectBtn = Buttons.secondary(I18n.get("graphql.introspect"));
    private final JLabel statusLabel = Fields.caption(" ");

    private final JTabbedPane responseTabs = new JTabbedPane();

    private SwingWorker<?, ?> currentWorker;

    public GraphQLPanel() {
        super(ToolCatalog.GRAPHQL_CLIENT);
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // 顶部地址栏
        JPanel addressBar = Layouts.box(Tokens.SPACE_SM, 0);
        addressBar.add(methodCombo, BorderLayout.WEST);
        addressBar.add(urlField, BorderLayout.CENTER);
        addressBar.add(Layouts.wrapRow(introspectBtn, cancelBtn, sendBtn), BorderLayout.EAST);

        // 请求区域 Tabs
        JTabbedPane requestTabs = new JTabbedPane();
        requestTabs.addTab(I18n.get("graphql.tab.query"), Fields.scroll(queryArea));
        requestTabs.addTab(I18n.get("graphql.tab.variables"), Fields.scroll(variablesArea));
        requestTabs.addTab(I18n.get("graphql.tab.headers"), Fields.scroll(headersArea));

        Card requestCard = Card.flush(I18n.get("graphql.section.request"));
        requestCard.setContent(requestTabs);
        requestCard.setMinimumSize(new Dimension(200, 160));

        // 响应区域 Tabs
        responseTabs.addTab(I18n.get("graphql.tab.response"), Fields.scroll(responseArea));
        responseTabs.addTab(I18n.get("graphql.tab.respHeaders"), Fields.scroll(respHeadersArea));
        responseTabs.addTab(I18n.get("graphql.tab.schema"), Fields.scroll(schemaArea));

        Card responseCard = Card.flush(I18n.get("graphql.section.response"));
        responseCard.setContent(responseTabs);
        JButton copyBtn = Buttons.snug(I18n.get("devtools.copy"));
        copyBtn.addActionListener(e -> {
            int sel = responseTabs.getSelectedIndex();
            if (sel == 0) UIUtils.copyToClipboard(responseArea.getText());
            else if (sel == 1) UIUtils.copyToClipboard(respHeadersArea.getText());
            else UIUtils.copyToClipboard(schemaArea.getText());
        });
        responseCard.addHeaderAction(copyBtn);
        responseCard.setMinimumSize(new Dimension(200, 160));

        Component centerSplit = Layouts.splitVertical(requestCard, responseCard, 0.45);

        // 底部状态栏
        JPanel bottomBar = Layouts.box();
        bottomBar.add(statusLabel, BorderLayout.WEST);
        bottomBar.add(Fields.note(I18n.get("graphql.hint")), BorderLayout.EAST);

        root.add(addressBar, BorderLayout.NORTH);
        root.add(centerSplit, BorderLayout.CENTER);
        root.add(bottomBar, BorderLayout.SOUTH);

        cancelBtn.setEnabled(false);
        sendBtn.addActionListener(e -> executeRequest(false));
        introspectBtn.addActionListener(e -> executeRequest(true));
        cancelBtn.addActionListener(e -> cancelCurrent());

        queryArea.setText(SAMPLE_QUERY);
        variablesArea.setText(SAMPLE_VARIABLES);
        headersArea.setText("Content-Type: application/json\n");

        return root;
    }

    private void executeRequest(boolean isIntrospection) {
        if (currentWorker != null) return;

        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            UIUtils.error(urlField, I18n.get("graphql.urlRequired"));
            return;
        }

        String query = isIntrospection ? GraphQLRequest.INTROSPECTION_QUERY : queryArea.getText();
        String variables = isIntrospection ? "" : variablesArea.getText();
        String method = (String) methodCombo.getSelectedItem();
        if (method == null) method = "POST";

        Map<String, String> headers = parseHeaders(headersArea.getText());
        headers.putIfAbsent("Content-Type", "application/json");
        headers.putIfAbsent("Accept", "application/json");

        final String reqMethod = method;
        sendBtn.setEnabled(false);
        introspectBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        statusLabel.setText(I18n.get("devtools.working"));

        currentWorker = new SwingWorker<OpenApiRequestExecutor.Response, Void>() {
            private long elapsedMs;

            @Override
            protected OpenApiRequestExecutor.Response doInBackground() throws Exception {
                long start = System.currentTimeMillis();
                try {
                    String reqUrl = url;
                    String body = null;

                    if ("GET".equalsIgnoreCase(reqMethod)) {
                        String encodedQuery = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
                        String sep = reqUrl.contains("?") ? "&" : "?";
                        reqUrl += sep + "query=" + encodedQuery;
                        if (variables != null && !variables.isBlank()) {
                            reqUrl += "&variables=" + URLEncoder.encode(variables.trim(), StandardCharsets.UTF_8);
                        }
                    } else {
                        body = GraphQLRequest.buildJsonPayload(query, variables, null);
                    }

                    return new OpenApiRequestExecutor().execute(reqUrl, reqMethod, headers, body, Duration.ofSeconds(20));
                } finally {
                    elapsedMs = System.currentTimeMillis() - start;
                }
            }

            @Override
            protected void done() {
                try {
                    if (isCancelled()) {
                        statusLabel.setText(I18n.get("devtools.cancelled"));
                        return;
                    }

                    OpenApiRequestExecutor.Response resp = get();
                    String rawBody = resp.body() != null ? resp.body().trim() : "";
                    String prettyBody = prettyJson(rawBody);

                    responseArea.setText(prettyBody);
                    responseArea.setCaretPosition(0);

                    // 响应头
                    StringBuilder hb = new StringBuilder();
                    resp.headers().forEach((k, v) -> hb.append(k).append(": ").append(String.join(", ", v)).append("\n"));
                    respHeadersArea.setText(hb.toString());

                    // 状态说明
                    int byteLen = rawBody.getBytes(StandardCharsets.UTF_8).length;
                    String statusText = String.format("Status: %d | Time: %d ms | Size: %s",
                            resp.status(), elapsedMs, FormatUtils.bytes(byteLen));
                    statusLabel.setText(statusText);

                    // 若是内省请求，自动解析 Schema 概览并切换至 Schema Tab
                    if (isIntrospection) {
                        String summary = GraphQLRequest.summarizeIntrospection(rawBody);
                        schemaArea.setText(summary);
                        schemaArea.setCaretPosition(0);
                        responseTabs.setSelectedIndex(2);
                    } else {
                        responseTabs.setSelectedIndex(0);
                    }
                } catch (Exception err) {
                    statusLabel.setText(Errors.describeRoot(err));
                    responseArea.setText(Errors.describeRoot(err));
                } finally {
                    currentWorker = null;
                    sendBtn.setEnabled(true);
                    introspectBtn.setEnabled(true);
                    cancelBtn.setEnabled(false);
                }
            }
        };
        currentWorker.execute();
    }

    private void cancelCurrent() {
        if (currentWorker != null) {
            currentWorker.cancel(true);
        }
    }

    private static Map<String, String> parseHeaders(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text == null) return map;
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                map.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return map;
    }

    private static String prettyJson(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            JsonNode node = Json.mapper().readTree(raw);
            return node.toPrettyString();
        } catch (Exception e) {
            return raw;
        }
    }

    @Override
    public void closeResources() {
        cancelCurrent();
    }
}
