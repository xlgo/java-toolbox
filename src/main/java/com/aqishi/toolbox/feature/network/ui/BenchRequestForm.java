package com.aqishi.toolbox.feature.network.ui;

import com.aqishi.toolbox.feature.network.domain.BenchRequest;
import com.aqishi.toolbox.feature.network.domain.CurlCommand;
import com.aqishi.toolbox.ui.kit.Buttons;
import com.aqishi.toolbox.ui.kit.Card;
import com.aqishi.toolbox.ui.kit.Fields;
import com.aqishi.toolbox.ui.kit.Layouts;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.aqishi.toolbox.util.I18n;

import javax.swing.*;
import java.awt.*;

/**
 * 压测面板的"请求"卡片：方法、URL、请求头、请求体与 cURL 导入。
 *
 * <p>cURL 导入做成页签里的粘贴区而不是弹窗：浏览器复制出来的命令动辄几十行，
 * 用户常常要对照着改几处参数再导入，放在页签里可以随时回来再导一次。</p>
 */
final class BenchRequestForm {

    private static final String[] METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"};
    private static final String[] CONTENT_TYPES = {"", "application/json", "application/x-www-form-urlencoded",
            "text/plain", "application/xml"};

    private final JComboBox<String> methodBox = Fields.combo(METHODS, 104);
    private final JTextField urlField = Fields.mono("http://127.0.0.1:8080/");
    private final JTextArea headersArea = Fields.area(5, 40);
    private final JTextArea bodyArea = Fields.area(5, 40);
    private final JComboBox<String> contentTypeBox = Fields.combo(CONTENT_TYPES);
    private final JTextArea curlArea = Fields.area(5, 40);
    private final JLabel curlStatus = Fields.caption(" ");
    private final JTabbedPane tabs = new JTabbedPane();
    private final Card card;

    BenchRequestForm() {
        methodBox.setEditable(true);
        contentTypeBox.setEditable(true);
        headersArea.setText("Accept: */*\nUser-Agent: JavaToolbox-Bench/1.0");
        headersArea.putClientProperty("JTextField.placeholderText", I18n.get("tool.httpbench.placeholder.headers"));
        curlArea.putClientProperty("JTextField.placeholderText", I18n.get("tool.httpbench.placeholder.curl"));

        JPanel urlRow = Layouts.box(Tokens.SPACE_SM, 0);
        urlRow.add(methodBox, BorderLayout.WEST);
        urlRow.add(urlField, BorderLayout.CENTER);

        JPanel bodyTab = Layouts.box(0, Tokens.SPACE_SM);
        JPanel typeRow = Layouts.box(Tokens.SPACE_SM, 0);
        typeRow.add(Fields.label(I18n.get("tool.httpbench.label.contentType")), BorderLayout.WEST);
        typeRow.add(contentTypeBox, BorderLayout.CENTER);
        bodyTab.add(typeRow, BorderLayout.NORTH);
        bodyTab.add(Fields.scroll(bodyArea), BorderLayout.CENTER);

        JButton applyCurl = Buttons.secondary(I18n.get("tool.httpbench.btn.applyCurl"));
        applyCurl.addActionListener(event -> importCurl(curlArea.getText()));
        JPanel curlFooter = Layouts.box(Tokens.SPACE_SM, 0);
        curlFooter.add(curlStatus, BorderLayout.CENTER);
        curlFooter.add(applyCurl, BorderLayout.EAST);
        JPanel curlTab = Layouts.box(0, Tokens.SPACE_SM);
        curlTab.add(Fields.scroll(curlArea), BorderLayout.CENTER);
        curlTab.add(curlFooter, BorderLayout.SOUTH);

        tabs.setBorder(null);
        tabs.addTab(I18n.get("tool.httpbench.tab.headers"), Fields.scroll(headersArea));
        tabs.addTab(I18n.get("tool.httpbench.tab.body"), bodyTab);
        tabs.addTab(I18n.get("tool.httpbench.tab.curl"), curlTab);

        JPanel content = Layouts.box(0, Tokens.SPACE_MD);
        content.add(urlRow, BorderLayout.NORTH);
        content.add(tabs, BorderLayout.CENTER);

        card = Card.titled(I18n.get("tool.httpbench.card.request"));
        card.setContent(content);
        JButton importBtn = Buttons.snug(I18n.get("tool.httpbench.btn.importCurl"));
        importBtn.addActionListener(event -> {
            tabs.setSelectedIndex(2);
            curlArea.requestFocusInWindow();
        });
        card.addHeaderAction(importBtn);
    }

    Card card() {
        return card;
    }

    /**
     * 读取表单为请求对象。
     *
     * @throws IllegalArgumentException URL 或请求头不合法（英文消息）
     */
    BenchRequest toRequest() {
        Object method = methodBox.getSelectedItem();
        Object contentType = contentTypeBox.getSelectedItem();
        return BenchRequest.of(method == null ? "GET" : method.toString(), urlField.getText(),
                BenchRequest.splitHeaderLines(headersArea.getText()), bodyArea.getText(),
                contentType == null ? "" : contentType.toString());
    }

    /**
     * 解析 cURL 命令并回填表单。
     *
     * @return 是否导入成功；失败原因显示在粘贴区下方
     */
    boolean importCurl(String command) {
        CurlCommand curl;
        try {
            curl = CurlCommand.parse(command);
            BenchRequest.fromCurl(curl);
        } catch (IllegalArgumentException invalid) {
            curlStatus.setText(I18n.get("tool.httpbench.status.curlInvalid", invalid.getMessage()));
            curlStatus.setForeground(Tokens.danger());
            return false;
        }
        methodBox.setSelectedItem(curl.method());
        urlField.setText(curl.url());
        headersArea.setText(String.join("\n", curl.headers()));
        bodyArea.setText(curl.body());
        contentTypeBox.setSelectedItem("");
        curlStatus.setText(I18n.get("tool.httpbench.status.curlImported"));
        curlStatus.setForeground(Tokens.success());
        tabs.setSelectedIndex(curl.body().isEmpty() ? 0 : 1);
        return true;
    }

    String urlText() {
        return urlField.getText();
    }

    String methodText() {
        Object method = methodBox.getSelectedItem();
        return method == null ? "" : method.toString();
    }

    String headersText() {
        return headersArea.getText();
    }

    String bodyText() {
        return bodyArea.getText();
    }

    void setEditable(boolean editable) {
        methodBox.setEnabled(editable);
        urlField.setEditable(editable);
        headersArea.setEditable(editable);
        bodyArea.setEditable(editable);
        contentTypeBox.setEnabled(editable);
        curlArea.setEditable(editable);
    }
}
