package com.aqishi.toolbox.misc;

import com.aqishi.toolbox.ui.ToolPanel;
import com.aqishi.toolbox.util.UIUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 编解码工具面板。
 * <p>支持 JWT 实时解析（解码 + 签名验证）与构造生成（编码，支持 HS256 签名）。</p>
 */
public class JwtPanel extends ToolPanel {

    // ==================== 解码组件 ====================
    private JTextArea decInputArea;
    private JTextArea decHeaderArea;
    private JTextArea decPayloadArea;
    private JLabel decInfoLabel;
    private JPasswordField decSecretField;
    private JCheckBox decShowSecretCheck;

    // ==================== 编码组件 ====================
    private JTextArea encHeaderArea;
    private JTextArea encPayloadArea;
    private JTextField encSecretField;
    private JTextArea encOutputArea;

    /** HMAC 算法名与 JWT alg 的映射 */
    private static final Map<String, String> HMAC_ALG_MAP = new LinkedHashMap<>();

    static {
        HMAC_ALG_MAP.put("HS256", "HmacSHA256");
        HMAC_ALG_MAP.put("HS384", "HmacSHA384");
        HMAC_ALG_MAP.put("HS512", "HmacSHA512");
    }

    public JwtPanel() {
        super("开发工具", "JWT 编解码",
                "JWT", "Token", "HS256", "签名", "JWT解码",
                "JWT编码", "Json Web Token", "JWT验证", "令牌");
    }

    @Override
    protected JComponent build() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(UIUtils.CONTENT_PADDING);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("JWT 解码 (Decode)", buildDecodeTab());
        tabs.addTab("JWT 编码 (Encode)", buildEncodeTab());

        root.add(tabs, BorderLayout.CENTER);
        return root;
    }

    // ================================================================
    //  1. 解码面板
    // ================================================================
    private JComponent buildDecodeTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        // ---- 输入区 ----
        decInputArea = new JTextArea(4, 40);
        decInputArea.setFont(UIUtils.monoFont());
        decInputArea.setLineWrap(true);
        p.add(UIUtils.scrollText(decInputArea, "输入 JWT (header.payload.signature)"), BorderLayout.NORTH);

        // ---- 签名密钥区 ----
        JPanel secretPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        secretPanel.add(new JLabel("签名密钥（验证用, 可选）："));
        decSecretField = new JPasswordField(24);
        decSecretField.setFont(UIUtils.monoFont());
        secretPanel.add(decSecretField);

        decShowSecretCheck = new JCheckBox("显示");
        decShowSecretCheck.addActionListener(e ->
                decSecretField.setEchoChar(decShowSecretCheck.isSelected() ? (char) 0 : '•'));
        secretPanel.add(decShowSecretCheck);

        // 将密钥行放在 NORTH 区域的南边
        JPanel northWrapper = new JPanel(new BorderLayout());
        northWrapper.add(UIUtils.scrollText(decInputArea, "输入 JWT (header.payload.signature)"), BorderLayout.CENTER);
        northWrapper.add(secretPanel, BorderLayout.SOUTH);
        p.add(northWrapper, BorderLayout.NORTH);

        // ---- Header / Payload 展示 ----
        decHeaderArea = new JTextArea();
        decHeaderArea.setFont(UIUtils.monoFont());
        decHeaderArea.setEditable(false);

        decPayloadArea = new JTextArea();
        decPayloadArea.setFont(UIUtils.monoFont());
        decPayloadArea.setEditable(false);

        JPanel centerPanel = new JPanel(new GridLayout(1, 2, 8, 0));
        centerPanel.add(UIUtils.scrollText(decHeaderArea, "Header (头部)"));
        centerPanel.add(UIUtils.scrollText(decPayloadArea, "Payload (载荷)"));
        p.add(centerPanel, BorderLayout.CENTER);

        // ---- 底部按钮与状态 ----
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 4));
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton decodeBtn = UIUtils.button("解码", 80);
        JButton clearBtn = UIUtils.button("清空", 80);
        btnRow.add(decodeBtn);
        btnRow.add(clearBtn);
        bottomPanel.add(btnRow, BorderLayout.WEST);

        decInfoLabel = new JLabel("  就绪");
        decInfoLabel.setFont(UIUtils.plainFont());
        bottomPanel.add(decInfoLabel, BorderLayout.CENTER);
        p.add(bottomPanel, BorderLayout.SOUTH);

        // 事件
        decodeBtn.addActionListener(e -> doDecode());
        clearBtn.addActionListener(e -> {
            decInputArea.setText("");
            decSecretField.setText("");
            decHeaderArea.setText("");
            decPayloadArea.setText("");
            decInfoLabel.setText("  就绪");
            decInfoLabel.setForeground(UIManager.getColor("Label.foreground"));
        });

        // 默认演示 JWT
        decInputArea.setText("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJleHAiOjI1MTYyMzkwMjJ9." +
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
        // 同时填入演示密钥
        decSecretField.setText("your-256-bit-secret");
        doDecode();

        return p;
    }

    // ================================================================
    //  2. 编码面板
    // ================================================================
    private JComponent buildEncodeTab() {
        JPanel p = new JPanel(new BorderLayout(8, 8));

        // 左侧：输入配置区
        JPanel leftPanel = new JPanel(new GridLayout(2, 1, 0, 8));
        encHeaderArea = new JTextArea();
        encHeaderArea.setFont(UIUtils.monoFont());
        encHeaderArea.setText("{\n  \"alg\": \"HS256\",\n  \"typ\": \"JWT\"\n}");

        encPayloadArea = new JTextArea();
        encPayloadArea.setFont(UIUtils.monoFont());
        encPayloadArea.setText("{\n  \"sub\": \"1234567890\",\n  \"name\": \"John Doe\",\n  \"iat\": 1516239022\n}");

        leftPanel.add(UIUtils.scrollText(encHeaderArea, "编辑 Header (JSON格式)"));
        leftPanel.add(UIUtils.scrollText(encPayloadArea, "编辑 Payload (JSON格式)"));

        // 右侧：输出及密钥配置
        JPanel rightPanel = new JPanel(new GridBagLayout());
        rightPanel.setBorder(BorderFactory.createTitledBorder("签名密钥与生成结果"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);

        // 签名密钥输入
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        rightPanel.add(new JLabel("HMAC 密钥："), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        encSecretField = new JTextField("your-256-bit-secret");
        encSecretField.setFont(UIUtils.monoFont());
        rightPanel.add(encSecretField, gbc);

        // 生成按钮行
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 0;
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton genBtn = UIUtils.button("生成 JWT Token", 120);
        JButton copyBtn = UIUtils.button("复制 Token", 90);
        btnRow.add(genBtn);
        btnRow.add(copyBtn);
        rightPanel.add(btnRow, gbc);

        // 输出 JWT
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        encOutputArea = new JTextArea();
        encOutputArea.setFont(UIUtils.monoFont());
        encOutputArea.setLineWrap(true);
        encOutputArea.setEditable(false);
        rightPanel.add(UIUtils.scrollText(encOutputArea, "生成的 JWT Token"), gbc);

        // 左右分割
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        split.setResizeWeight(0.5);
        p.add(split, BorderLayout.CENTER);

        // 事件
        genBtn.addActionListener(e -> doEncode());
        copyBtn.addActionListener(e -> {
            String token = encOutputArea.getText().trim();
            if (!token.isEmpty()) {
                UIUtils.copyToClipboard(token);
                UIUtils.info(p, "JWT Token 已成功复制到剪贴板。");
            }
        });

        return p;
    }

    // ================================================================
    //  3. 解码逻辑（含签名验证）
    // ================================================================
    private void doDecode() {
        String token = decInputArea.getText().trim();
        if (token.isEmpty()) {
            decInfoLabel.setText("  提示：请输入 JWT Token");
            decInfoLabel.setForeground(Color.RED);
            return;
        }

        String[] parts = token.split("\\.");
        if (parts.length < 2 || parts.length > 3) {
            decInfoLabel.setText("  错误：JWT 格式不正确（必须包含点 \".\" 分隔符）");
            decInfoLabel.setForeground(Color.RED);
            decHeaderArea.setText("");
            decPayloadArea.setText("");
            return;
        }

        try {
            // 1. 解码 Header
            byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
            String headerJson = new String(headerBytes, StandardCharsets.UTF_8);
            decHeaderArea.setText(JsonFormatter.pretty(headerJson));

            // 2. 解码 Payload
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            decPayloadArea.setText(JsonFormatter.pretty(payloadJson));

            // 3. 检查 exp 过期
            parseExpiration(payloadJson);

            // 4. 签名验证（如果有签名段且提供了密钥）
            String secret = new String(decSecretField.getPassword());
            if (parts.length == 3 && !secret.isEmpty()) {
                doVerifySignature(headerJson, parts, secret);
            } else if (parts.length == 3 && secret.isEmpty()) {
                decInfoLabel.setText(decInfoLabel.getText() + "  |  输入签名密钥可验证签名");
                decInfoLabel.setForeground(UIManager.getColor("Label.foreground"));
            }
            // parts.length == 2 时无签名，不做验证

        } catch (Exception ex) {
            decHeaderArea.setText("");
            decPayloadArea.setText("");
            decInfoLabel.setText("  错误：Base64/JSON 解码失败 — " + ex.getMessage());
            decInfoLabel.setForeground(Color.RED);
        }
    }

    /**
     * 验证 JWT 签名。
     * @param headerJson 原始解码后的 Header JSON 字符串
     * @param parts      JWT 各部分（[header, payload, signature]）
     * @param secret     签名密钥
     */
    private void doVerifySignature(String headerJson, String[] parts, String secret) {
        try {
            // 解析 header 中的 alg 字段
            String alg = extractAlg(headerJson);
            if (alg == null || alg.isEmpty()) {
                decInfoLabel.setText(decInfoLabel.getText() + "  |  签名验证：Header 缺少 alg 字段");
                decInfoLabel.setForeground(Color.RED);
                return;
            }

            // 查找对应的 Java Mac 算法名
            String macAlg = HMAC_ALG_MAP.get(alg);
            if (macAlg == null) {
                decInfoLabel.setText(decInfoLabel.getText()
                        + "  |  签名验证：不支持的算法 " + alg + "（仅支持 HS256/HS384/HS512）");
                decInfoLabel.setForeground(Color.RED);
                return;
            }

            // 计算期望签名
            String content = parts[0] + "." + parts[1];
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlg);
            Mac mac = Mac.getInstance(macAlg);
            mac.init(keySpec);
            byte[] expectedSigBytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            String expectedSig = Base64.getUrlEncoder().withoutPadding().encodeToString(expectedSigBytes);

            // 对比签名
            String actualSig = parts[2];
            if (expectedSig.equals(actualSig)) {
                decInfoLabel.setText(decInfoLabel.getText() + "  |  ✅ 签名验证通过 (" + alg + ")");
                decInfoLabel.setForeground(new Color(46, 125, 50));
            } else {
                decInfoLabel.setText(decInfoLabel.getText() + "  |  ❌ 签名验证失败 (" + alg + ")");
                decInfoLabel.setForeground(Color.RED);
            }
        } catch (Exception ex) {
            decInfoLabel.setText(decInfoLabel.getText() + "  |  签名验证异常：" + ex.getMessage());
            decInfoLabel.setForeground(Color.RED);
        }
    }

    /**
     * 从 Header JSON 中提取 alg 字段值。
     */
    private static String extractAlg(String headerJson) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"alg\"\\s*:\\s*\"([^\"]+)\"");
        java.util.regex.Matcher m = p.matcher(headerJson);
        return m.find() ? m.group(1) : null;
    }

    // ================================================================
    //  4. 编码逻辑
    // ================================================================
    private void doEncode() {
        try {
            String headerRaw = encHeaderArea.getText().trim();
            String payloadRaw = encPayloadArea.getText().trim();
            String secret = encSecretField.getText();

            // 压缩 JSON
            String headerJson = JsonFormatter.compact(headerRaw);
            String payloadJson = JsonFormatter.compact(payloadRaw);

            // Base64URL 编码 Header 与 Payload
            String headerB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String content = headerB64 + "." + payloadB64;

            // 签名计算（默认使用 HS256 / HmacSHA256）
            String signatureB64 = "";
            if (secret != null && !secret.isEmpty()) {
                SecretKeySpec secretKey = new SecretKeySpec(
                        secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(secretKey);
                byte[] rawHmac = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
                signatureB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
            }

            encOutputArea.setText(content + "." + signatureB64);
        } catch (Exception ex) {
            UIUtils.error(encOutputArea, "JWT 生成失败：" + ex.getMessage());
        }
    }

    // ================================================================
    //  5. 过期时间解析
    // ================================================================
    private void parseExpiration(String json) {
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");
            java.util.regex.Matcher m = p.matcher(json);
            if (m.find()) {
                long expSeconds = Long.parseLong(m.group(1));
                long expMillis = expSeconds * 1000;
                Date expDate = new Date(expMillis);
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                String formattedDate = sdf.format(expDate);

                long now = System.currentTimeMillis();
                if (now > expMillis) {
                    decInfoLabel.setText("  状态：已过期，过期时间为 " + formattedDate);
                    decInfoLabel.setForeground(Color.RED);
                } else {
                    long diffHours = (expMillis - now) / (1000 * 60 * 60);
                    if (diffHours > 24) {
                        long diffDays = diffHours / 24;
                        decInfoLabel.setText("  状态：有效，" + diffDays + " 天后过期 (" + formattedDate + ")");
                    } else {
                        decInfoLabel.setText("  状态：有效，" + diffHours + " 小时后过期 (" + formattedDate + ")");
                    }
                    decInfoLabel.setForeground(new Color(46, 125, 50));
                }
            } else {
                decInfoLabel.setText("  状态：解析成功 (未包含 exp 过期声明)");
                decInfoLabel.setForeground(UIManager.getColor("Label.foreground"));
            }
        } catch (Exception e) {
            decInfoLabel.setText("  状态：解析成功");
            decInfoLabel.setForeground(UIManager.getColor("Label.foreground"));
        }
    }
}
