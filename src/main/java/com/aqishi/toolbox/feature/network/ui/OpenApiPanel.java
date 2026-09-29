package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.catalog.ToolCatalog;
import com.aqishi.toolbox.catalog.ToolDescriptor;
import com.aqishi.toolbox.util.JsonFormatter;
import com.aqishi.toolbox.feature.network.domain.OpenApiSpec;
import com.aqishi.toolbox.feature.network.application.OpenApiRequestExecutor;
import com.aqishi.toolbox.feature.network.domain.OpenApiService;
import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.Errors;
import com.aqishi.toolbox.util.I18n;
import com.aqishi.toolbox.util.UIUtils;
import com.aqishi.toolbox.infra.ManagedResourceOwner;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

/**
 * OpenAPI / Swagger 接口工作台。
 * 支持 OpenAPI 3.x / Swagger 2.0 规范解析、分组导航、参数调试与 cURL 导出。
 */
public class OpenApiPanel extends ToolPanel implements ManagedResourceOwner {

    private final OpenApiService service;
    private OpenApiSpec currentSpec;

    // 顶部导入与源数据组件
    private JTextField urlInput;
    private JTextArea specArea;

    // 左侧导航
    private JTextField searchField;
    private DefaultListModel<OpenApiSpec.ApiEndpoint> endpointListModel;
    private JList<OpenApiSpec.ApiEndpoint> endpointList;
    private List<OpenApiSpec.ApiEndpoint> allEndpoints = new ArrayList<>();

    // 右侧调试工作区
    private JComboBox<String> serverCombo;
    private JLabel methodLabel;
    private JTextField pathField;
    private JButton sendBtn;
    private JButton cancelBtn;
    private SwingWorker<String[], Void> requestWorker;

    @Override
    public void closeResources() {
        Runnable close = () -> {
            if (requestWorker != null) requestWorker.cancel(true);
        };
        if (SwingUtilities.isEventDispatchThread()) close.run();
        else SwingUtilities.invokeLater(close);
    }
    private JButton copyCurlBtn;

    // 参数表格 (Type, Name, Required, Value, Description)
    private DefaultTableModel paramsTableModel;
    private JTable paramsTable;

    // 请求体与响应
    private JTextArea requestBodyArea;
    private JTextArea responseBodyArea;
    private JTextArea responseHeadersArea;
    private JLabel statusLabel;
    private JTextArea docSummaryArea;

    public OpenApiPanel() {
        this(ToolCatalog.OPENAPI_WORKBENCH, new OpenApiService());
    }

    public OpenApiPanel(OpenApiService service) {
        this(ToolCatalog.OPENAPI_WORKBENCH, service);
    }

    public OpenApiPanel(ToolDescriptor descriptor, OpenApiService service) {
        super(Objects.requireNonNull(descriptor, "descriptor"));
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ================= 1. 顶部规范导入卡片 =================
        Card importCard = Card.titled(I18n.get("tool.openapi.import.title", "导入 OpenAPI / Swagger 规范"));

        urlInput = Fields.mono("http://localhost:8080/v3/api-docs");
        urlInput.putClientProperty("JTextField.placeholderText", "输入远程 API 文档 URL，如 http://localhost:8080/v3/api-docs");

        JButton fetchBtn = Buttons.primary(I18n.get("tool.openapi.btn.fetch", "抓取在线规范"));
        JButton parseBtn = Buttons.secondary(I18n.get("tool.openapi.btn.parse", "解析文本规范"));
        JButton sampleBtn = Buttons.secondary(I18n.get("tool.openapi.btn.sample", "加载示例"));
        JButton fileBtn = Buttons.ghost(I18n.get("tool.openapi.btn.file", "从文件读取"));

        fetchBtn.addActionListener(e -> fetchRemote());
        parseBtn.addActionListener(e -> parseCurrentText());
        sampleBtn.addActionListener(e -> loadSample());
        fileBtn.addActionListener(e -> chooseFile());

        JPanel urlBar = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        urlBar.setOpaque(false);
        urlBar.add(urlInput, BorderLayout.CENTER);
        JPanel urlActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        urlActions.setOpaque(false);
        urlActions.add(fetchBtn);
        urlActions.add(fileBtn);
        urlActions.add(sampleBtn);
        urlBar.add(urlActions, BorderLayout.EAST);

        specArea = Fields.area(4, 30);
        specArea.setText(OpenApiService.SAMPLE_SPEC);

        FormGrid importGrid = new FormGrid();
        importGrid.row(I18n.get("tool.openapi.label.url", "文档 URL"), urlBar);
        importGrid.row(I18n.get("tool.openapi.label.spec", "规范原文 (JSON / YAML)"), Fields.scroll(specArea));

        JPanel importBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        importBottom.setOpaque(false);
        importBottom.add(parseBtn);

        JPanel importBody = Layouts.box(0, Tokens.SPACE_SM);
        importBody.add(importGrid, BorderLayout.CENTER);
        importBody.add(importBottom, BorderLayout.SOUTH);
        importCard.setContent(importBody);

        // ================= 2. 主体区：左侧 API 列表 + 右侧调试工作区 =================
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerLocation(280);
        splitPane.setResizeWeight(0.25);
        splitPane.setBorder(null);

        // ----- 左侧：端点导航 -----
        JPanel leftPanel = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        leftPanel.setOpaque(false);

        searchField = Fields.text("", "搜索 API 路径或描述...");
        searchField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filterEndpoints(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filterEndpoints(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filterEndpoints(); }
        });

        endpointListModel = new DefaultListModel<>();
        endpointList = new JList<>(endpointListModel);
        endpointList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        endpointList.setCellRenderer(new EndpointCellRenderer());
        endpointList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                onEndpointSelected(endpointList.getSelectedValue());
            }
        });

        Card listCard = Card.flush(I18n.get("tool.openapi.endpoints.title", "API 端点列表"));
        JPanel listBody = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        listBody.setOpaque(false);
        listBody.add(searchField, BorderLayout.NORTH);
        listBody.add(Fields.scroll(endpointList), BorderLayout.CENTER);
        listCard.setContent(listBody);

        splitPane.setLeftComponent(listCard);

        // ----- 右侧：详情与调试工作区 -----
        Card workCard = Card.flush(I18n.get("tool.openapi.workbench.title", "接口调试与详情"));
        JPanel workBody = new JPanel(new BorderLayout(0, Tokens.SPACE_MD));
        workBody.setOpaque(false);

        // 地址栏与发送按钮
        JPanel addressBar = new JPanel(new BorderLayout(Tokens.SPACE_SM, 0));
        addressBar.setOpaque(false);

        serverCombo = Fields.combo(new String[]{"https://httpbin.org"}, 160);
        serverCombo.setEditable(true);

        methodLabel = new JLabel("GET");
        methodLabel.setFont(Tokens.fontBodyStrong());
        methodLabel.setForeground(Tokens.accent());
        methodLabel.setBorder(BorderFactory.createEmptyBorder(0, Tokens.SPACE_SM, 0, Tokens.SPACE_SM));

        pathField = Fields.mono("/get");
        pathField.setEditable(false);

        sendBtn = Buttons.primary(I18n.get("tool.openapi.btn.send", "发送请求"));
        sendBtn.addActionListener(e -> sendApiRequest());
        cancelBtn = Buttons.secondary(I18n.get("tool.openapi.btn.cancel"));
        cancelBtn.setEnabled(false);
        cancelBtn.addActionListener(e -> {
            if (requestWorker != null) requestWorker.cancel(true);
        });

        copyCurlBtn = Buttons.secondary(I18n.get("tool.openapi.btn.curl", "复制 cURL"));
        copyCurlBtn.addActionListener(e -> copyCurl());

        JPanel addressLeft = new JPanel(new BorderLayout(Tokens.SPACE_XS, 0));
        addressLeft.setOpaque(false);
        addressLeft.add(serverCombo, BorderLayout.WEST);
        addressLeft.add(methodLabel, BorderLayout.CENTER);

        JPanel addressRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, Tokens.SPACE_SM, 0));
        addressRight.setOpaque(false);
        addressRight.add(sendBtn);
        addressRight.add(cancelBtn);
        addressRight.add(copyCurlBtn);

        addressBar.add(addressLeft, BorderLayout.WEST);
        addressBar.add(pathField, BorderLayout.CENTER);
        addressBar.add(addressRight, BorderLayout.EAST);

        // 选项卡：参数、请求体、响应、文档
        JTabbedPane tabs = new JTabbedPane();

        // Tab 1: 请求参数
        String[] colNames = {"位置", "参数名", "必填", "参数值", "类型与说明"};
        paramsTableModel = new DefaultTableModel(colNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 3; // 仅允许编辑参数值
            }
        };
        paramsTable = new JTable(paramsTableModel);
        paramsTable.setRowHeight(26);
        tabs.addTab(I18n.get("tool.openapi.tab.params", "请求参数"), Fields.scroll(paramsTable));

        // Tab 2: 请求体
        requestBodyArea = Fields.area(8, 30);
        JButton formatJsonBtn = Buttons.snug("格式化 JSON");
        formatJsonBtn.addActionListener(e -> {
            try {
                requestBodyArea.setText(JsonFormatter.pretty(requestBodyArea.getText()));
            } catch (Exception ignored) {
                Errors.ignored("请求体不是合法 JSON，保持原文不格式化", ignored);
            }
        });
        JPanel reqBodyPanel = new JPanel(new BorderLayout(0, Tokens.SPACE_XS));
        reqBodyPanel.setOpaque(false);
        JPanel reqBodyHeader = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        reqBodyHeader.setOpaque(false);
        reqBodyHeader.add(formatJsonBtn);
        reqBodyPanel.add(reqBodyHeader, BorderLayout.NORTH);
        reqBodyPanel.add(Fields.scroll(requestBodyArea), BorderLayout.CENTER);
        tabs.addTab(I18n.get("tool.openapi.tab.body", "请求体 (JSON)"), reqBodyPanel);

        // Tab 3: 响应结果
        JPanel respPanel = new JPanel(new BorderLayout(0, Tokens.SPACE_SM));
        respPanel.setOpaque(false);
        statusLabel = Fields.caption("状态：就绪");
        responseBodyArea = Fields.output(8, 30);
        responseHeadersArea = Fields.output(4, 30);

        JSplitPane respSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        respSplit.setResizeWeight(0.7);
        respSplit.setTopComponent(Fields.scroll(responseBodyArea));
        respSplit.setBottomComponent(Fields.scroll(responseHeadersArea));
        respSplit.setBorder(null);

        respPanel.add(statusLabel, BorderLayout.NORTH);
        respPanel.add(respSplit, BorderLayout.CENTER);
        tabs.addTab(I18n.get("tool.openapi.tab.response", "响应预览"), respPanel);

        // Tab 4: 接口文档摘要
        docSummaryArea = Fields.output(6, 30);
        tabs.addTab(I18n.get("tool.openapi.tab.doc", "接口文档"), Fields.scroll(docSummaryArea));

        workBody.add(addressBar, BorderLayout.NORTH);
        workBody.add(tabs, BorderLayout.CENTER);
        workCard.setContent(workBody);

        splitPane.setLeftComponent(listCard);
        splitPane.setRightComponent(workCard);

        root.add(importCard, BorderLayout.NORTH);
        root.add(splitPane, BorderLayout.CENTER);

        // 初始自动解析预设示例
        SwingUtilities.invokeLater(this::parseCurrentText);
        return root;
    }

    private void parseCurrentText() {
        try {
            String text = specArea.getText();
            currentSpec = service.parse(text);
            allEndpoints = currentSpec.getEndpoints();

            // 更新服务器地址下拉框
            serverCombo.removeAllItems();
            for (String s : currentSpec.getServers()) {
                serverCombo.addItem(s);
            }

            filterEndpoints();
            if (!allEndpoints.isEmpty()) {
                endpointList.setSelectedIndex(0);
            }
            UIUtils.info(getView(), "成功解析 " + allEndpoints.size() + " 个 API 端点！");
        } catch (Exception ex) {
            UIUtils.error(getView(), "解析失败: " + ex.getMessage());
        }
    }

    private void fetchRemote() {
        String url = urlInput.getText().trim();
        if (url.isEmpty()) return;
        fetchBtnEnabled(false);
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() throws Exception {
                return service.fetchRemoteSpec(url);
            }

            @Override
            protected void done() {
                fetchBtnEnabled(true);
                try {
                    String fetched = get();
                    specArea.setText(fetched);
                    parseCurrentText();
                } catch (Exception ex) {
                    UIUtils.error(getView(), "抓取在线文档失败: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void fetchBtnEnabled(boolean enabled) {
        urlInput.setEnabled(enabled);
    }

    private void loadSample() {
        specArea.setText(OpenApiService.SAMPLE_SPEC);
        parseCurrentText();
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 OpenAPI / Swagger 文件 (JSON 或 YAML)");
        if (chooser.showOpenDialog(getView()) == JFileChooser.APPROVE_OPTION) {
            try {
                File file = chooser.getSelectedFile();
                byte[] bytes = Files.readAllBytes(file.toPath());
                specArea.setText(new String(bytes, StandardCharsets.UTF_8));
                parseCurrentText();
            } catch (Exception ex) {
                UIUtils.error(getView(), "读取文件失败: " + ex.getMessage());
            }
        }
    }

    private void filterEndpoints() {
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        endpointListModel.clear();
        for (OpenApiSpec.ApiEndpoint ep : allEndpoints) {
            if (query.isEmpty()
                    || ep.getPath().toLowerCase(Locale.ROOT).contains(query)
                    || ep.getMethod().toLowerCase(Locale.ROOT).contains(query)
                    || (ep.getSummary() != null && ep.getSummary().toLowerCase(Locale.ROOT).contains(query))
                    || (ep.getTag() != null && ep.getTag().toLowerCase(Locale.ROOT).contains(query))) {
                endpointListModel.addElement(ep);
            }
        }
    }

    private void onEndpointSelected(OpenApiSpec.ApiEndpoint ep) {
        if (ep == null) return;
        methodLabel.setText(ep.getMethod());
        pathField.setText(ep.getPath());

        // 重新填入参数表格
        paramsTableModel.setRowCount(0);
        for (OpenApiSpec.Parameter p : ep.getParameters()) {
            paramsTableModel.addRow(new Object[]{
                    p.getIn(),
                    p.getName(),
                    p.isRequired() ? "是" : "否",
                    p.getExample() != null ? p.getExample() : "",
                    p.getType() + (p.getDescription() != null && !p.getDescription().isEmpty() ? " - " + p.getDescription() : "")
            });
        }

        // 请求体
        requestBodyArea.setText(ep.getRequestBodyExample() != null ? ep.getRequestBodyExample() : "");

        // 接口文档详情
        StringBuilder doc = new StringBuilder();
        doc.append("【").append(ep.getMethod()).append("】 ").append(ep.getPath()).append("\n");
        if (ep.getSummary() != null && !ep.getSummary().isEmpty()) {
            doc.append("概要: ").append(ep.getSummary()).append("\n");
        }
        if (ep.getDescription() != null && !ep.getDescription().isEmpty()) {
            doc.append("描述: ").append(ep.getDescription()).append("\n");
        }
        doc.append("分组: ").append(ep.getTag()).append("\n\n");
        doc.append("--- 响应定义 ---\n");
        for (OpenApiSpec.ResponseItem r : ep.getResponses()) {
            doc.append("• HTTP ").append(r.getStatusCode()).append(": ").append(r.getDescription()).append("\n");
        }
        docSummaryArea.setText(doc.toString());
    }

    private void sendApiRequest() {
        OpenApiSpec.ApiEndpoint ep = endpointList.getSelectedValue();
        if (ep == null) return;

        if (paramsTable.isEditing() && !paramsTable.getCellEditor().stopCellEditing()) return;
        String baseUrl = (String) serverCombo.getSelectedItem();
        Map<String, String> pathParams = new LinkedHashMap<>();
        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();

        for (int i = 0; i < paramsTableModel.getRowCount(); i++) {
            String in = String.valueOf(paramsTableModel.getValueAt(i, 0));
            String name = String.valueOf(paramsTableModel.getValueAt(i, 1));
            String val = String.valueOf(paramsTableModel.getValueAt(i, 3));
            if (val == null || val.trim().isEmpty()) continue;
            if ("path".equalsIgnoreCase(in)) {
                pathParams.put(name, val.trim());
            } else if ("query".equalsIgnoreCase(in)) {
                queryParams.put(name, val.trim());
            } else if ("header".equalsIgnoreCase(in)) {
                headers.put(name, val.trim());
            }
        }

        String body = requestBodyArea.getText();
        if (body != null && !body.trim().isEmpty()) {
            if (headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
                headers.put("Content-Type", ep.getRequestContentType() != null ? ep.getRequestContentType() : "application/json");
            }
        }

        sendBtn.setEnabled(false);
        cancelBtn.setEnabled(true);
        statusLabel.setText(I18n.get("tool.openapi.status.sending"));
        long start = System.currentTimeMillis();

        requestWorker = new SwingWorker<String[], Void>() {
            @Override
            protected String[] doInBackground() throws Exception {
                String fullUrl = service.buildRequestUrl(baseUrl, ep.getPath(), pathParams, queryParams);
                var response = new OpenApiRequestExecutor().execute(fullUrl, ep.getMethod(), headers, body);
                StringBuilder responseHeaders = new StringBuilder();
                response.headers().forEach((name, values) -> responseHeaders.append(name).append(": ")
                        .append(String.join(", ", values)).append("\n"));
                return new String[]{String.valueOf(response.status()), response.body(), responseHeaders.toString()};
            }

            @Override
            protected void done() {
                sendBtn.setEnabled(true);
                cancelBtn.setEnabled(false);
                requestWorker = null;
                if (isCancelled()) {
                    statusLabel.setText(I18n.get("tool.openapi.status.cancelled"));
                    return;
                }
                long cost = System.currentTimeMillis() - start;
                try {
                    String[] result = get();
                    int code = Integer.parseInt(result[0]);
                    statusLabel.setText(I18n.get("tool.openapi.status.response", code, cost));
                    if (code >= 200 && code < 300) {
                        statusLabel.setForeground(Tokens.accent());
                    } else {
                        statusLabel.setForeground(Tokens.danger());
                    }
                    String prettyBody;
                    try {
                        prettyBody = JsonFormatter.pretty(result[1]);
                    } catch (Exception ex) {
                        prettyBody = result[1];
                    }
                    responseBodyArea.setText(prettyBody);
                    responseHeadersArea.setText(result[2]);
                } catch (Exception ex) {
                    statusLabel.setText(I18n.get("tool.openapi.status.failed", Errors.describeRoot(ex)));
                    statusLabel.setForeground(Tokens.danger());
                }
            }
        };
        requestWorker.execute();
    }

    private void copyCurl() {
        OpenApiSpec.ApiEndpoint ep = endpointList.getSelectedValue();
        if (ep == null) return;
        if (paramsTable.isEditing() && !paramsTable.getCellEditor().stopCellEditing()) return;
        String baseUrl = (String) serverCombo.getSelectedItem();
        Map<String, String> pathParams = new LinkedHashMap<>();
        Map<String, String> queryParams = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();

        for (int i = 0; i < paramsTableModel.getRowCount(); i++) {
            String in = String.valueOf(paramsTableModel.getValueAt(i, 0));
            String name = String.valueOf(paramsTableModel.getValueAt(i, 1));
            String val = String.valueOf(paramsTableModel.getValueAt(i, 3));
            if (val == null || val.trim().isEmpty()) continue;
            if ("path".equalsIgnoreCase(in)) {
                pathParams.put(name, val.trim());
            } else if ("query".equalsIgnoreCase(in)) {
                queryParams.put(name, val.trim());
            } else if ("header".equalsIgnoreCase(in)) {
                headers.put(name, val.trim());
            }
        }

        String body = requestBodyArea.getText();
        if (body != null && !body.trim().isEmpty()) {
            if (headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
                headers.put("Content-Type", ep.getRequestContentType() != null ? ep.getRequestContentType() : "application/json");
            }
        }

        String curl = service.buildCurl(baseUrl, ep, pathParams, queryParams, headers, body);
        UIUtils.copyToClipboard(curl);
        UIUtils.info(getView(), "cURL 命令已复制到剪贴板！");
    }

    /**
     * 自定义端点列表渲染器。
     */
    private static class EndpointCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof OpenApiSpec.ApiEndpoint) {
                OpenApiSpec.ApiEndpoint ep = (OpenApiSpec.ApiEndpoint) value;
                String method = ep.getMethod();
                String summary = ep.getSummary();
                setText(String.format("[%s] %s %s", method, ep.getPath(), (summary != null && !summary.isEmpty() ? " - " + summary : "")));
                if (!isSelected) {
                    if ("GET".equalsIgnoreCase(method)) {
                        setForeground(new Color(40, 167, 69));
                    } else if ("POST".equalsIgnoreCase(method)) {
                        setForeground(new Color(0, 123, 255));
                    } else if ("PUT".equalsIgnoreCase(method)) {
                        setForeground(new Color(255, 153, 0));
                    } else if ("DELETE".equalsIgnoreCase(method)) {
                        setForeground(new Color(220, 53, 69));
                    }
                }
            }
            return this;
        }
    }
}
