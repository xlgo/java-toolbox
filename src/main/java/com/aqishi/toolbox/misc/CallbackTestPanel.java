package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.FormGrid;
import com.aqishi.toolbox.ui.kit.KitBorders;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
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
        super("dev", "callback.mock",
                "回调", "接口测试", "Mock", "Webhook", "Server", "服务器", "HTTP Mock");
    }

    @Override
    protected JComponent build() {
        JPanel root = Layouts.page();

        // ===== 顶部：服务开关卡片，启停是全页面唯一的主操作，放标题右侧 =====
        portField = Fields.text("8080");
        serverStatusLabel = new JLabel("状态: 已停止");
        serverStatusLabel.setFont(Tokens.fontBody());

        toggleBtn = Buttons.primary("启动服务");
        toggleBtn.addActionListener(e -> toggleServer());

        // 端口是定长输入，靠左固定；剩余宽度留给随运行状态变长的监听地址
        JPanel portRow = Layouts.box(Tokens.SPACE_MD, 0);
        portRow.add(portField, BorderLayout.WEST);
        portRow.add(serverStatusLabel, BorderLayout.CENTER);

        FormGrid serverForm = new FormGrid();
        serverForm.row("服务端口:", portRow);

        Card serverCard = Card.titled("回调服务");
        serverCard.setContent(serverForm);
        serverCard.addHeaderAction(toggleBtn);

        // ===== 左侧：Mock 响应配置 + 流量记录，上下可拖动分配高度 =====
        respCodeField = Fields.text("200");
        respContentTypeField = Fields.text("application/json");

        FormGrid mockForm = new FormGrid();
        mockForm.row("状态码:", respCodeField);
        mockForm.row("Content-Type:", respContentTypeField);

        respBodyArea = Fields.area(4, 20);
        respBodyArea.setText("{\n  \"status\": \"success\",\n  \"message\": \"Callback received\"\n}");

        // 响应体是这张卡片里唯一需要长高的控件，单独放 CENTER
        JPanel respBodyBox = Layouts.box(0, Tokens.SPACE_XS);
        respBodyBox.add(Fields.caption("响应体:"), BorderLayout.NORTH);
        respBodyBox.add(boxedScroll(respBodyArea), BorderLayout.CENTER);

        JPanel mockBody = Layouts.box(0, Tokens.SPACE_MD);
        mockBody.add(mockForm, BorderLayout.NORTH);
        mockBody.add(respBodyBox, BorderLayout.CENTER);

        Card mockCard = Card.titled("自定义响应数据");
        mockCard.setContent(mockBody);

        requestListModel = new DefaultListModel<>();
        requestList = new JList<>(requestListModel);
        requestList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestList.addListSelectionListener(this::handleListSelection);
        requestList.setFont(Tokens.fontBody());

        clearBtn = Buttons.danger("清空历史记录");
        clearBtn.addActionListener(e -> clearRecords());

        Card recordsCard = Card.flush("请求流量记录");
        recordsCard.setContent(Fields.scroll(requestList));
        recordsCard.addHeaderAction(clearBtn);

        // ===== 右侧：请求详情回显，三个页签铺满整张卡片 =====
        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.setBorder(null);
        // 详情区在分栏右侧，窗口一窄三个页签就会折成两行并压进内容区，改成单行滚动
        rightTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        detailsArea = Fields.output(8, 30);
        rightTabs.addTab("请求概要 (Summary)", Fields.scroll(detailsArea));

        headersArea = Fields.output(8, 30);
        rightTabs.addTab("请求头 (Headers)", Fields.scroll(headersArea));

        bodyArea = Fields.output(8, 30);
        rightTabs.addTab("请求体 (Body)", Fields.scroll(bodyArea));

        Card detailCard = Card.plain().setFlush(true);
        detailCard.setContent(rightTabs);

        root.add(serverCard, BorderLayout.NORTH);
        root.add(Layouts.splitHorizontal(
                Layouts.splitVertical(mockCard, recordsCard, 0.45), detailCard, 0.35),
                BorderLayout.CENTER);

        return root;
    }

    /**
     * 卡片内部嵌的文本域滚动区。
     *
     * <p>卡片底色与文本域底色相同，不描一条细线的话输入框会整个「消失」在卡片里；
     * 这里只用最弱的分隔色画 1px，不会和卡片描边叠成双层边框。</p>
     */
    private static JScrollPane boxedScroll(JTextArea area) {
        JScrollPane scroll = Fields.scroll(area);
        scroll.setBorder(KitBorders.lineSubtle(1, 1, 1, 1));
        return scroll;
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
