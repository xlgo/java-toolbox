package com.aqishi.toolbox.convert;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.ui.kit.Card;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * URL 编解码与 Query 参数解析工具
 */
public class UrlToolPanel extends ToolPanel {

    private JTextField rawUrlField;
    private JComboBox<String> charsetCombo;

    // URL 结构分解字段
    private JTextField schemeField;
    private JTextField hostField;
    private JTextField portField;
    private JTextField pathField;
    private JTextField fragmentField;

    // Query 表格模型
    private DefaultTableModel queryTableModel;
    private JTable queryTable;

    // 自由文本编解码
    private JTextArea inputArea;
    private JTextArea outputArea;

    public UrlToolPanel() {
        super("convert", "url.tool", "url", "uri", "encode", "decode", "query", "parameter", "params", "http", "编码", "解码");
    }

    @Override
    protected JComponent build() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 12));
        mainPanel.setBorder(new EmptyBorder(16, 16, 16, 16));

        // --- 顶栏：完整的 URL 解析与拼接栏 ---
        Card topCard = Card.plain();
        topCard.setLayout(new BorderLayout(12, 12));
        topCard.setBorder(new EmptyBorder(12, 16, 12, 16));

        JPanel urlForm = new JPanel(new BorderLayout(8, 0));
        urlForm.add(new JLabel("完整 URL / 链接: "), BorderLayout.WEST);

        rawUrlField = new JTextField("https://api.example.com/v1/search?q=Java+%E5%B7%A5%E5%85%B7%E7%AE%B1&category=dev&sort=desc#results");
        rawUrlField.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        urlForm.add(rawUrlField, BorderLayout.CENTER);

        JPanel actionBtnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        charsetCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "ISO-8859-1"});

        JButton parseBtn = new JButton("解析 URL 结构与参数");
        parseBtn.addActionListener(e -> parseUrl());

        JButton buildUrlBtn = new JButton("从表格参数重新合成 URL");
        buildUrlBtn.addActionListener(e -> buildUrlFromComponents());

        JButton copyUrlBtn = new JButton("复制 URL");
        copyUrlBtn.addActionListener(e -> copyToClipboard(rawUrlField.getText()));

        actionBtnBar.add(new JLabel("字符集:"));
        actionBtnBar.add(charsetCombo);
        actionBtnBar.add(parseBtn);
        actionBtnBar.add(buildUrlBtn);
        actionBtnBar.add(copyUrlBtn);

        topCard.add(urlForm, BorderLayout.CENTER);
        topCard.add(actionBtnBar, BorderLayout.SOUTH);

        // --- 中间 Tab 页 ---
        JTabbedPane tabbedPane = new JTabbedPane();

        tabbedPane.addTab("Query 参数列表", buildQueryParamsPanel());
        tabbedPane.addTab("URL 组成结构分解", buildUrlStructurePanel());
        tabbedPane.addTab("自由文本 URL Encode / Decode", buildFreeEncoderPanel());

        mainPanel.add(topCard, BorderLayout.NORTH);
        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        // 初始自动解析一次
        parseUrl();

        return mainPanel;
    }

    private JPanel buildQueryParamsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        String[] headers = {"Key (参数名)", "Value (已解码参数值)"};
        queryTableModel = new DefaultTableModel(headers, 0);
        queryTable = new JTable(queryTableModel);
        queryTable.setRowHeight(26);
        queryTable.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JScrollPane scrollPane = new JScrollPane(queryTable);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton addRowBtn = new JButton("添加参数");
        addRowBtn.addActionListener(e -> queryTableModel.addRow(new Object[]{"new_key", "value"}));

        JButton delRowBtn = new JButton("删除选中行");
        delRowBtn.addActionListener(e -> {
            int selected = queryTable.getSelectedRow();
            if (selected >= 0) {
                queryTableModel.removeRow(selected);
            }
        });

        JButton clearBtn = new JButton("清空参数");
        clearBtn.addActionListener(e -> queryTableModel.setRowCount(0));

        btnPanel.add(addRowBtn);
        btnPanel.add(delRowBtn);
        btnPanel.add(clearBtn);

        panel.add(scrollPane, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildUrlStructurePanel() {
        Card card = Card.plain();
        card.setLayout(new GridBagLayout());
        card.setBorder(new EmptyBorder(16, 16, 16, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 8, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        schemeField = createMonospacedField();
        hostField = createMonospacedField();
        portField = createMonospacedField();
        pathField = createMonospacedField();
        fragmentField = createMonospacedField();

        int row = 0;
        addFormRow(card, gbc, row++, "协议 (Scheme/Protocol):", schemeField);
        addFormRow(card, gbc, row++, "主机名 (Host):", hostField);
        addFormRow(card, gbc, row++, "端口号 (Port):", portField);
        addFormRow(card, gbc, row++, "路径 (Path):", pathField);
        addFormRow(card, gbc, row++, "片段/锚点 (Fragment/Anchor):", fragmentField);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBorder(new EmptyBorder(8, 8, 8, 8));
        wrapper.add(card, BorderLayout.NORTH);
        return wrapper;
    }

    private JPanel buildFreeEncoderPanel() {
        JPanel panel = new JPanel(new GridLayout(1, 2, 12, 0));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // 左侧输入框
        JPanel inputPanel = new JPanel(new BorderLayout(0, 8));
        inputPanel.add(new JLabel("原始 / 输入文本:"), BorderLayout.NORTH);
        inputArea = new JTextArea();
        inputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        inputPanel.add(new JScrollPane(inputArea), BorderLayout.CENTER);

        JPanel inputBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton encodeBtn = new JButton("URL 编码 (Encode) ->");
        encodeBtn.addActionListener(e -> encodeInputText());

        JButton decodeBtn = new JButton("URL 解码 (Decode) ->");
        decodeBtn.addActionListener(e -> decodeInputText());
        inputBtns.add(encodeBtn);
        inputBtns.add(decodeBtn);
        inputPanel.add(inputBtns, BorderLayout.SOUTH);

        // 右侧输出框
        JPanel outputPanel = new JPanel(new BorderLayout(0, 8));
        outputPanel.add(new JLabel("转换结果:"), BorderLayout.NORTH);
        outputArea = new JTextArea();
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        outputPanel.add(new JScrollPane(outputArea), BorderLayout.CENTER);

        JPanel outputBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton copyOutputBtn = new JButton("复制结果");
        copyOutputBtn.addActionListener(e -> copyToClipboard(outputArea.getText()));
        outputBtns.add(copyOutputBtn);
        outputPanel.add(outputBtns, BorderLayout.SOUTH);

        panel.add(inputPanel);
        panel.add(outputPanel);
        return panel;
    }

    private JTextField createMonospacedField() {
        JTextField tf = new JTextField();
        tf.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        return tf;
    }

    private void addFormRow(JPanel panel, GridBagConstraints gbc, int row, String label, JTextField field) {
        gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0;
        panel.add(new JLabel(label), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(field, gbc);
    }

    private void parseUrl() {
        String raw = rawUrlField.getText().trim();
        if (raw.isEmpty()) return;

        try {
            URI uri = new URI(raw);
            schemeField.setText(uri.getScheme() != null ? uri.getScheme() : "");
            hostField.setText(uri.getHost() != null ? uri.getHost() : "");
            portField.setText(uri.getPort() != -1 ? String.valueOf(uri.getPort()) : "");
            pathField.setText(uri.getPath() != null ? uri.getPath() : "");
            fragmentField.setText(uri.getFragment() != null ? uri.getFragment() : "");

            // 清空并重新填入 Query 参数
            queryTableModel.setRowCount(0);
            String rawQuery = uri.getRawQuery();
            String enc = (String) charsetCombo.getSelectedItem();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                String[] pairs = rawQuery.split("&");
                for (String pair : pairs) {
                    int idx = pair.indexOf("=");
                    if (idx > 0) {
                        String key = URLDecoder.decode(pair.substring(0, idx), enc);
                        String val = URLDecoder.decode(pair.substring(idx + 1), enc);
                        queryTableModel.addRow(new Object[]{key, val});
                    } else if (!pair.isEmpty()) {
                        String key = URLDecoder.decode(pair, enc);
                        queryTableModel.addRow(new Object[]{key, ""});
                    }
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(getView(), "URL 解析失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void buildUrlFromComponents() {
        String scheme = schemeField.getText().trim();
        String host = hostField.getText().trim();
        String port = portField.getText().trim();
        String path = pathField.getText().trim();
        String fragment = fragmentField.getText().trim();
        String enc = (String) charsetCombo.getSelectedItem();

        StringBuilder sb = new StringBuilder();
        if (!scheme.isEmpty()) {
            sb.append(scheme).append("://");
        }
        if (!host.isEmpty()) {
            sb.append(host);
        }
        if (!port.isEmpty()) {
            sb.append(":").append(port);
        }
        if (!path.isEmpty()) {
            if (!path.startsWith("/") && sb.length() > 0) sb.append("/");
            sb.append(path);
        }

        // 构造 Query
        if (queryTableModel.getRowCount() > 0) {
            sb.append("?");
            List<String> params = new ArrayList<>();
            try {
                for (int i = 0; i < queryTableModel.getRowCount(); i++) {
                    String k = String.valueOf(queryTableModel.getValueAt(i, 0));
                    String v = String.valueOf(queryTableModel.getValueAt(i, 1));
                    String encK = URLEncoder.encode(k, enc);
                    String encV = URLEncoder.encode(v, enc);
                    params.add(encK + "=" + encV);
                }
                sb.append(String.join("&", params));
            } catch (Exception e) {
                JOptionPane.showMessageDialog(getView(), "编码参数失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }

        if (!fragment.isEmpty()) {
            sb.append("#").append(fragment);
        }

        rawUrlField.setText(sb.toString());
    }

    private void encodeInputText() {
        String input = inputArea.getText();
        if (input.isEmpty()) return;
        try {
            String enc = (String) charsetCombo.getSelectedItem();
            outputArea.setText(URLEncoder.encode(input, enc));
        } catch (Exception e) {
            outputArea.setText("编码出错: " + e.getMessage());
        }
    }

    private void decodeInputText() {
        String input = inputArea.getText();
        if (input.isEmpty()) return;
        try {
            String enc = (String) charsetCombo.getSelectedItem();
            outputArea.setText(URLDecoder.decode(input, enc));
        } catch (Exception e) {
            outputArea.setText("解码出错: " + e.getMessage());
        }
    }

    private void copyToClipboard(String text) {
        if (text == null || text.isEmpty()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        JOptionPane.showMessageDialog(getView(), "已复制到剪贴板", "提示", JOptionPane.INFORMATION_MESSAGE);
    }
}
