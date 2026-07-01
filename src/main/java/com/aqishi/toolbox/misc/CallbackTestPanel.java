package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 回调接口测试工具（轻量级 HTTP Mock 服务器）。
 * 支持动态启动内置 HTTP 服务，自定义接口响应状态码、Content-Type 与响应内容，
 * 实时回显并解析所有接收到的 HTTP 请求包头、请求参数与请求体。
 */
public class CallbackTestPanel extends ToolPanel {

    private JTextField portField;
    private JButton toggleBtn;
    private JLabel serverStatusLabel;

    // 自定义返回数据
    private JTextField respCodeField;
    private JTextField respContentTypeField;
    private JTextArea respBodyArea;

    // 历史回调列表
    private DefaultListModel<String> requestListModel;
    private JList<String> requestList;
    private final List<MockRequestRecord> records = new ArrayList<>();
    private JButton clearBtn;

    // 详情区
    private JTextArea detailsArea;
    private JTextArea headersArea;
    private JTextArea bodyArea;

    private HttpServer server;
    private boolean isRunning = false;

    public CallbackTestPanel() {
        super("开发工具", "回调接口测试",
                "回调", "接口测试", "Mock", "Webhook", "Server", "服务器", "HTTP Mock");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        // ===== 顶部控制栏 =====
        JPanel ctrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        ctrl.add(new JLabel("服务端口:"));
        portField = new JTextField("8080", 6);
        ctrl.add(portField);

        toggleBtn = UIUtils.button("启动服务", 90);
        toggleBtn.addActionListener(e -> toggleServer());
        ctrl.add(toggleBtn);

        serverStatusLabel = new JLabel("状态: 已停止");
        serverStatusLabel.setFont(UIUtils.plainFont());
        ctrl.add(serverStatusLabel);
        root.add(ctrl, BorderLayout.NORTH);

        // ===== 中间主工作区 =====
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setDividerLocation(380);

        // 1. 左侧：配置区与历史列表
        JPanel leftPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(2, 2, 2, 2);

        // 1.1 Mock 响应自定义配置
        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                "自定义响应数据", 0, 0, UIUtils.plainFont(), UIManager.getColor("Component.accentColor")));
        
        GridBagConstraints cgbc = new GridBagConstraints();
        cgbc.fill = GridBagConstraints.HORIZONTAL;
        cgbc.insets = new Insets(4, 4, 4, 4);

        cgbc.gridx = 0; cgbc.gridy = 0;
        configPanel.add(new JLabel("状态码:"), cgbc);
        cgbc.gridx = 1;
        respCodeField = new JTextField("200");
        configPanel.add(respCodeField, cgbc);

        cgbc.gridx = 0; cgbc.gridy = 1;
        configPanel.add(new JLabel("Content-Type:"), cgbc);
        cgbc.gridx = 1;
        respContentTypeField = new JTextField("application/json");
        configPanel.add(respContentTypeField, cgbc);

        cgbc.gridx = 0; cgbc.gridy = 2; cgbc.gridwidth = 2;
        configPanel.add(new JLabel("响应体:"), cgbc);

        cgbc.gridy = 3; cgbc.weighty = 1.0; cgbc.fill = GridBagConstraints.BOTH;
        respBodyArea = new JTextArea("{\n  \"status\": \"success\",\n  \"message\": \"Callback received\"\n}", 4, 20);
        respBodyArea.setFont(UIUtils.monoFont());
        configPanel.add(new JScrollPane(respBodyArea), cgbc);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.weighty = 0.45;
        leftPanel.add(configPanel, gbc);

        // 1.2 历史回调列表
        JPanel listPanel = new JPanel(new BorderLayout(4, 4));
        listPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
                "请求流量记录", 0, 0, UIUtils.plainFont(), UIManager.getColor("Component.accentColor")));

        requestListModel = new DefaultListModel<>();
        requestList = new JList<>(requestListModel);
        requestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestList.addListSelectionListener(this::handleListSelection);
        requestList.setFont(UIUtils.plainFont());
        
        listPanel.add(new JScrollPane(requestList), BorderLayout.CENTER);

        clearBtn = UIUtils.button("清空历史记录", 110);
        clearBtn.addActionListener(e -> clearRecords());
        JPanel bBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        bBox.add(clearBtn);
        listPanel.add(bBox, BorderLayout.SOUTH);

        gbc.gridy = 1; gbc.weighty = 0.55;
        leftPanel.add(listPanel, gbc);

        mainSplit.setLeftComponent(leftPanel);

        // 2. 右侧：请求详情回显区
        JTabbedPane rightTabs = new JTabbedPane();
        
        detailsArea = new JTextArea();
        detailsArea.setEditable(false);
        detailsArea.setFont(UIUtils.monoFont());
        rightTabs.addTab("请求概要 (Summary)", new JScrollPane(detailsArea));

        headersArea = new JTextArea();
        headersArea.setEditable(false);
        headersArea.setFont(UIUtils.monoFont());
        rightTabs.addTab("请求头 (Headers)", new JScrollPane(headersArea));

        bodyArea = new JTextArea();
        bodyArea.setEditable(false);
        bodyArea.setFont(UIUtils.monoFont());
        rightTabs.addTab("请求体 (Body)", new JScrollPane(bodyArea));

        mainSplit.setRightComponent(rightTabs);
        root.add(mainSplit, BorderLayout.CENTER);

        return root;
    }

    private synchronized void toggleServer() {
        if (isRunning) {
            // 停止服务
            try {
                if (server != null) {
                    server.stop(0);
                }
                isRunning = false;
                toggleBtn.setText("启动服务");
                serverStatusLabel.setText("状态: 已停止");
                portField.setEnabled(true);
            } catch (Exception ex) {
                UIUtils.error(getView(), "停止服务器失败: " + ex.getMessage());
            }
        } else {
            // 启动服务
            String portStr = portField.getText().trim();
            int port;
            try {
                port = Integer.parseInt(portStr);
                if (port < 1 || port > 65535) throw new Exception();
            } catch (Exception ex) {
                UIUtils.error(getView(), "请输入有效的端口号 (1-65535)！");
                return;
            }

            try {
                server = HttpServer.create(new InetSocketAddress(port), 0);
                // 监听全局路径
                server.createContext("/", new MockHttpHandler());
                server.setExecutor(null); // 用默认的线程池执行
                server.start();

                isRunning = true;
                toggleBtn.setText("停止服务");
                serverStatusLabel.setText("运行中 (监听: http://localhost:" + port + "/)");
                portField.setEnabled(false);
            } catch (Exception ex) {
                UIUtils.error(getView(), "启动服务器失败，请检查端口是否被占用:\n" + ex.getMessage());
            }
        }
    }

    private void handleListSelection(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        int idx = requestList.getSelectedIndex();
        if (idx >= 0 && idx < records.size()) {
            MockRequestRecord rec = records.get(idx);
            
            // 1. 概要
            StringBuilder sbSum = new StringBuilder();
            sbSum.append("接收时间: ").append(rec.time).append("\n");
            sbSum.append("请求方法: ").append(rec.method).append("\n");
            sbSum.append("请求路径: ").append(rec.path).append("\n");
            if (rec.query != null && !rec.query.isEmpty()) {
                sbSum.append("查询参数: ").append(rec.query).append("\n");
            }
            sbSum.append("客户端地址: ").append(rec.clientIp).append("\n");
            detailsArea.setText(sbSum.toString());

            // 2. 头部
            headersArea.setText(rec.headers);

            // 3. 请求体
            String rawBody = rec.body.trim();
            if ((rawBody.startsWith("{") && rawBody.endsWith("}")) ||
                (rawBody.startsWith("[") && rawBody.endsWith("]"))) {
                try {
                    bodyArea.setText(JsonFormatter.pretty(rawBody));
                } catch (Exception ex) {
                    bodyArea.setText(rawBody);
                }
            } else {
                bodyArea.setText(rawBody);
            }
        } else {
            detailsArea.setText("");
            headersArea.setText("");
            bodyArea.setText("");
        }
    }

    private void clearRecords() {
        records.clear();
        requestListModel.clear();
        detailsArea.setText("");
        headersArea.setText("");
        bodyArea.setText("");
    }

    // ===== Mock 服务器 Handler =====
    private class MockHttpHandler implements HttpHandler {
        private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            MockRequestRecord rec = new MockRequestRecord();
            rec.time = sdf.format(new Date());
            rec.method = exchange.getRequestMethod();
            rec.path = exchange.getRequestURI().getPath();
            rec.query = exchange.getRequestURI().getQuery();
            rec.clientIp = exchange.getRemoteAddress().toString();

            // 解析 Headers
            Headers reqHeaders = exchange.getRequestHeaders();
            StringBuilder sbHead = new StringBuilder();
            for (String name : reqHeaders.keySet()) {
                sbHead.append(name).append(": ").append(String.join(", ", reqHeaders.get(name))).append("\n");
            }
            rec.headers = sbHead.toString();

            // 读取 Body
            InputStream is = exchange.getRequestBody();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int read;
            while ((read = is.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
            rec.body = baos.toString("UTF-8");

            // 将请求记录发布到 GUI 线程中
            SwingUtilities.invokeLater(() -> {
                records.add(rec);
                String listLabel = String.format("[%s] %s %s", rec.time.substring(11), rec.method, rec.path);
                requestListModel.addElement(listLabel);
                // 默认选择最新一条
                requestList.setSelectedIndex(requestListModel.getSize() - 1);
            });

            // 获取 Mock 响应配置并做出应答
            int respCode = 200;
            try {
                respCode = Integer.parseInt(respCodeField.getText().trim());
            } catch (Exception ignored) {}

            String contentType = respContentTypeField.getText().trim();
            String responseBody = respBodyArea.getText();
            byte[] respBytes = responseBody.getBytes(StandardCharsets.UTF_8);

            // 写入响应
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(respCode, respBytes.length > 0 ? respBytes.length : -1);
            if (respBytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }
            }
            exchange.close();
        }
    }

    // ===== 回调请求结构 =====
    private static class MockRequestRecord {
        String time;
        String method;
        String path;
        String query;
        String clientIp;
        String headers;
        String body;
    }
}
